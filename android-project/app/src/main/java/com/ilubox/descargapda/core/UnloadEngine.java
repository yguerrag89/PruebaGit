package com.ilubox.descargapda.core;

import java.io.Serializable;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class UnloadEngine implements Serializable {
    private static final long serialVersionUID = 2L;
    public static final int MAX_PER_SIDE = 10;
    public static final String ENGINE_VERSION = "0.8-wms-strict";

    public static class FinalPalletView implements Serializable {
        private static final long serialVersionUID = 1L;
        public String label;
        public boolean direct;
        public int expected;
        public int received;
        public int codeCount;
        public String status;

        public FinalPalletView(String label, boolean direct) {
            this.label = label;
            this.direct = direct;
        }
    }

    public static class ScanMeta implements Serializable {
        private static final long serialVersionUID = 2L;
        public String position;
        public String code;
        public String time;
        public int boxNumber;
        public String rawScan;

        public ScanMeta(String position, String code, String time, int boxNumber, String rawScan) {
            this.position = position;
            this.code = code;
            this.time = time;
            this.boxNumber = boxNumber;
            this.rawScan = rawScan;
        }
    }

    public static class ParsedScan {
        public boolean valid;
        public String rawCanonical = "";
        public String code = "";
        public String normalizedBarcode = "";
        public int boxNumber = 0;
        public String status = "";
        public String message = "";
    }

    public final String containerId;
    public final Settings settings;
    public final LinkedHashMap<String, CodeRecord> records = new LinkedHashMap<>();
    public final LinkedHashMap<String, Integer> received = new LinkedHashMap<>();
    public final LinkedHashMap<String, Set<Integer>> receivedBoxNumbers = new LinkedHashMap<>();
    public final HashMap<String, String> positionForCode = new HashMap<>();
    public final ArrayList<Position> positions = new ArrayList<>();
    /** Clave = barcode normalizado (CODIGOUxxx). */
    public final HashMap<String, ScanMeta> scannedUniqueBarcodes = new HashMap<>();
    public final ArrayList<Integer> boxCounts = new ArrayList<>();
    public int peakPositions = 0;
    /** AUTO conserva el algoritmo original. MANUAL deja que el operador seleccione la tarima.
     * BUFFER usa tarimas buffer divididas en sectores y forma definitivas solo cuando hay bloques listos. */
    public String assignmentMode = "AUTO";
    /** Posición/tarima seleccionada por el operador en modo MANUAL. */
    public String manualActivePosition = "";
    /** Motor del buffer modular; solo se crea en modo BUFFER. */
    public BufferManager bufferManager = null;
    /** Plan caja -> tarima definitiva para TRASLADO DIRIGIDO. */
    public final LinkedHashMap<String, String> finalPalletForBarcode = new LinkedHashMap<>();
    /** Códigos que se forman directamente al pie del contenedor. */
    public final HashSet<String> directFinalCodes = new HashSet<>();
    /** Caja -> traslado físico en el que salió del pie del contenedor. */
    public final LinkedHashMap<String, String> transferForBarcode = new LinkedHashMap<>();
    public final HashSet<String> transfersInDistribution = new HashSet<>();
    public final HashSet<String> distributedTransfers = new HashSet<>();
    public int transferPalletSeq = 1;
    public int transferIncidentCount = 0;

    public UnloadEngine(String containerId, List<CodeRecord> inputRecords, Settings settings,
                        int initialLeft, int initialRight) {
        this(containerId, inputRecords, settings, initialLeft, initialRight, "AUTO");
    }

    public UnloadEngine(String containerId, List<CodeRecord> inputRecords, Settings settings,
                        int initialLeft, int initialRight, String assignmentMode) {
        this(containerId, inputRecords, settings, initialLeft, initialRight, assignmentMode, 4);
    }

    public UnloadEngine(String containerId, List<CodeRecord> inputRecords, Settings settings,
                        int initialLeft, int initialRight, String assignmentMode, int bufferPallets) {
        this.containerId = containerId == null ? "CONTENEDOR" : containerId.trim().toUpperCase(Locale.ROOT);
        this.settings = settings == null ? new Settings() : settings;
        if ("TRASLADO".equalsIgnoreCase(assignmentMode)) this.assignmentMode = "TRASLADO";
        else if ("MANUAL".equalsIgnoreCase(assignmentMode)) this.assignmentMode = "MANUAL";
        else if ("BUFFER".equalsIgnoreCase(assignmentMode)) this.assignmentMode = "BUFFER";
        else this.assignmentMode = "AUTO";
        if ("BUFFER".equals(this.assignmentMode)) this.bufferManager = new BufferManager(bufferPallets);
        for (CodeRecord r : inputRecords) {
            String c = r.code.toUpperCase(Locale.ROOT);
            records.put(c, r);
            received.put(c, 0);
            receivedBoxNumbers.put(c, new HashSet<>());
            boxCounts.add(Math.max(1, r.boxes));
        }
        Collections.sort(boxCounts);

        int left = clamp(initialLeft, 0, MAX_PER_SIDE);
        int right = clamp(initialRight, 0, MAX_PER_SIDE);
        for (int slot = 1; slot <= MAX_PER_SIDE; slot++) positions.add(new Position("I", slot, slot <= left));
        for (int slot = 1; slot <= MAX_PER_SIDE; slot++) positions.add(new Position("D", slot, slot <= right));
        if (isManualMode()) {
            for (Position p : positions) {
                if (p.enabled) { manualActivePosition = p.label(); break; }
            }
        }
        if (isTransferMode()) buildTransferPlan();
    }

    public boolean isManualMode() {
        return "MANUAL".equalsIgnoreCase(assignmentMode);
    }

    public boolean isBufferMode() {
        return "BUFFER".equalsIgnoreCase(assignmentMode);
    }

    public boolean isTransferMode() {
        return "TRASLADO".equalsIgnoreCase(assignmentMode);
    }

    public String currentTransferPallet() {
        return String.format(Locale.ROOT, "TR-%02d", transferPalletSeq);
    }

    public String startNextTransferPallet() {
        transferPalletSeq += 1;
        return currentTransferPallet();
    }

    public boolean currentTransferInDistribution() {
        return transfersInDistribution.contains(currentTransferPallet());
    }

    public int currentTransferBoxCount() {
        int n = 0;
        String current = currentTransferPallet();
        for (String tr : transferForBarcode.values()) if (current.equals(tr)) n++;
        return n;
    }

    public LinkedHashMap<String, Integer> currentTransferDestinations() {
        LinkedHashMap<String, Integer> out = new LinkedHashMap<>();
        String current = currentTransferPallet();
        for (Map.Entry<String, String> e : transferForBarcode.entrySet()) {
            if (!current.equals(e.getValue())) continue;
            String target = finalPalletForBarcode.get(e.getKey());
            Integer old = out.get(target);
            out.put(target, old == null ? 1 : old + 1);
        }
        return out;
    }

    public ActionResult sendCurrentTransfer() {
        String current = currentTransferPallet();
        if (currentTransferBoxCount() == 0) return new ActionResult(false, current, "El traslado está vacío", false);
        if (transfersInDistribution.contains(current)) return new ActionResult(false, current, current + " ya está en distribución", false);
        transfersInDistribution.add(current);
        return new ActionResult(true, current, current + " enviado al tendido", false);
    }

    public ActionResult confirmCurrentTransferDistribution() {
        String current = currentTransferPallet();
        if (!transfersInDistribution.contains(current)) {
            return new ActionResult(false, current, "Primero envíe el traslado al tendido", false);
        }
        transfersInDistribution.remove(current);
        distributedTransfers.add(current);
        String next = startNextTransferPallet();
        return new ActionResult(true, current, current + " distribuido · activo " + next, true);
    }

    public List<FinalPalletView> finalPalletViews() {
        LinkedHashMap<String, FinalPalletView> views = new LinkedHashMap<>();
        LinkedHashMap<String, HashSet<String>> codes = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : finalPalletForBarcode.entrySet()) {
            String barcode = e.getKey();
            String pallet = e.getValue();
            String code = "";
            int u = barcode.lastIndexOf('U');
            if (u > 0) code = barcode.substring(0, u);
            FinalPalletView v = views.get(pallet);
            if (v == null) {
                v = new FinalPalletView(pallet, directFinalCodes.contains(code));
                views.put(pallet, v);
                codes.put(pallet, new HashSet<>());
            }
            v.direct = v.direct || directFinalCodes.contains(code);
            v.expected++;
            if (scannedUniqueBarcodes.containsKey(barcode)) v.received++;
            codes.get(pallet).add(code);
        }
        ArrayList<FinalPalletView> out = new ArrayList<>();
        for (FinalPalletView v : views.values()) {
            v.codeCount = codes.get(v.label).size();
            if (v.received <= 0) v.status = "PREPARAR";
            else if (v.received >= v.expected) v.status = "LISTA";
            else v.status = "EN FORMACIÓN";
            out.add(v);
        }
        return out;
    }

    /**
     * Crea un plan sencillo y determinista antes de descargar. Los códigos grandes se mantienen
     * juntos y directos; los pequeños comparten tarima respetando el CBM objetivo. El plan se hace
     * por caja para permitir dividir un código que requiera más de una tarima.
     */
    private void buildTransferPlan() {
        finalPalletForBarcode.clear();
        directFinalCodes.clear();
        int pallet = 1;
        double used = 0.0;
        for (CodeRecord r : records.values()) {
            boolean large = r.cbm >= settings.targetCapacity * settings.largeRatio;
            if (large && used > 1e-9) { pallet++; used = 0.0; }
            if (large) directFinalCodes.add(r.code);
            for (int box = 1; box <= r.boxes; box++) {
                double unit = Math.max(0.0, r.cbmPerBox);
                if (used > 1e-9 && used + unit > settings.targetCapacity + 1e-9) {
                    pallet++;
                    used = 0.0;
                }
                String barcode = r.code + "U" + String.format(Locale.ROOT, "%03d", box);
                finalPalletForBarcode.put(barcode, String.format(Locale.ROOT, "T-%02d", pallet));
                used += unit;
            }
            if (large && used > 1e-9) { pallet++; used = 0.0; }
        }
    }

    public int plannedFinalPalletCount() {
        HashSet<String> x = new HashSet<>(finalPalletForBarcode.values());
        return x.size();
    }

    public ScanResult scanTransfer(String rawScan) {
        if (currentTransferInDistribution()) {
            transferIncidentCount++;
            ScanResult locked = ScanResult.fail("TRASLADO EN DISTRIBUCIÓN",
                    "Confirme la distribución de " + currentTransferPallet() + " antes de continuar");
            locked.position = currentTransferPallet();
            return locked;
        }
        ParsedScan parsed = parseScan(rawScan);
        if (!parsed.valid) {
            transferIncidentCount++;
            ScanResult out = ScanResult.fail(parsed.status, parsed.message);
            out.rawScan = parsed.rawCanonical; out.scan = parsed.rawCanonical; out.code = parsed.code;
            return out;
        }
        String code = parsed.code;
        CodeRecord r = records.get(code);
        String normalized = parsed.normalizedBarcode;
        if (scannedUniqueBarcodes.containsKey(normalized)) {
            transferIncidentCount++;
            ScanMeta prior = scannedUniqueBarcodes.get(normalized);
            ScanResult out = ScanResult.fail("DUPLICADA", "YA ESCANEADA · destino " + prior.position);
            out.code = code; out.position = prior.position; out.finalPallet = prior.position;
            out.normalizedBarcode = normalized; out.scan = normalized; out.boxNumber = parsed.boxNumber;
            out.firstScanTime = prior.time; out.received = received.get(code); out.expected = r.boxes;
            return out;
        }
        if (parsed.boxNumber > r.boxes) {
            transferIncidentCount++;
            ScanResult out = ScanResult.fail("FUERA DE RANGO", "POSIBLE SOBRANTE · esperadas " + r.boxes);
            out.code = code; out.normalizedBarcode = normalized; out.scan = normalized;
            out.boxNumber = parsed.boxNumber; out.received = received.get(code); out.expected = r.boxes;
            return out;
        }
        String target = finalPalletForBarcode.get(normalized);
        if (target == null) return ScanResult.fail("SIN PLAN", "La caja no tiene tarima definitiva calculada");
        Set<Integer> set = receivedBoxNumbers.get(code);
        set.add(parsed.boxNumber);
        int newReceived = set.size();
        received.put(code, newReceived);
        scannedUniqueBarcodes.put(normalized, new ScanMeta(target, code, now(), parsed.boxNumber, parsed.rawCanonical));

        boolean direct = directFinalCodes.contains(code);
        ScanResult out = new ScanResult();
        out.ok = true; out.status = direct ? "DIRECTO A DEFINITIVA" : "MARCAR Y TRASLADAR";
        out.message = direct ? "FORMAR " + target + " AL PIE DEL CONTENEDOR"
                : "MARCAR " + target.substring(2) + " · COLOCAR EN " + currentTransferPallet();
        out.code = code; out.rawScan = parsed.rawCanonical; out.normalizedBarcode = normalized;
        out.scan = normalized; out.boxNumber = parsed.boxNumber; out.uniqueBoxId = true;
        out.position = target; out.finalPallet = target; out.directToFinal = direct;
        out.transferPallet = direct ? "" : currentTransferPallet();
        if (!direct) transferForBarcode.put(normalized, currentTransferPallet());
        out.received = newReceived; out.expected = r.boxes; out.remaining = r.boxes - newReceived;
        return out;
    }

    public String getManualActivePosition() {
        Position p = findPosition(manualActivePosition);
        if (p != null && p.enabled && !p.waitingRemoval) return p.label();
        for (Position q : positions) {
            if (q.enabled && !q.waitingRemoval) {
                manualActivePosition = q.label();
                return q.label();
            }
        }
        manualActivePosition = "";
        return "";
    }

    public ActionResult setManualActivePosition(String label) {
        Position p = findPosition(label);
        if (p == null) return new ActionResult(false, label, "Posición no encontrada", false);
        if (!p.enabled) return new ActionResult(false, p.label(), p.label() + " no está habilitada", false);
        if (p.waitingRemoval) return new ActionResult(false, p.label(), p.label() + " está pendiente de retiro", false);
        manualActivePosition = p.label();
        return new ActionResult(true, p.label(), "Tarima activa: " + p.label(), false);
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
    private static String now() { return new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date()); }

    public static String canonicalScan(String scan) {
        if (scan == null) return "";
        String normalized = java.text.Normalizer.normalize(scan.trim(), java.text.Normalizer.Form.NFKC);
        return normalized.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }

    /**
     * Identifica código + número de caja. La regla del piloto V0.2 exige un identificador individual
     * para códigos de más de una caja. Se toleran lecturas concatenadas siempre que contengan una
     * única combinación inequívoca CODIGO + Uxxx.
     */
    public ParsedScan parseScan(String rawScan) {
        ParsedScan out = new ParsedScan();
        out.rawCanonical = canonicalScan(rawScan);
        if (out.rawCanonical.isEmpty()) {
            out.status = "VACÍO";
            out.message = "Escaneo vacío";
            return out;
        }

        String best = null;
        boolean ambiguousCode = false;
        for (String c : records.keySet()) {
            if (!out.rawCanonical.contains(c)) continue;
            if (best == null || c.length() > best.length()) {
                best = c;
                ambiguousCode = false;
            } else if (c.length() == best.length() && !c.equals(best)) {
                ambiguousCode = true;
            }
        }
        if (best == null) {
            out.status = "NO ENCONTRADA";
            out.message = "La caja no pertenece al Packing List";
            return out;
        }
        if (ambiguousCode) {
            out.status = "LECTURA AMBIGUA";
            out.message = "La lectura contiene más de un código. Escanee nuevamente.";
            return out;
        }
        out.code = best;
        CodeRecord r = records.get(best);

        // Uxxx debe estar asociado al código detectado. No aceptamos un U suelto de otra
        // etiqueta o de un prefijo del lector, porque inventaría una identidad de caja.
        LinkedHashMap<Integer, Boolean> candidates = new LinkedHashMap<>();
        Pattern exact = Pattern.compile(Pattern.quote(best) + "[^A-Z0-9]{0,3}U(\\d{1,3})(?!\\d)");
        Matcher em = exact.matcher(out.rawCanonical);
        while (em.find()) {
            try { candidates.put(Integer.parseInt(em.group(1)), true); } catch (Exception ignored) {}
        }

        if (candidates.isEmpty()) {
            // Para un código unitario, el código base por sí solo identifica de forma inequívoca U001.
            if (r.boxes == 1 && out.rawCanonical.equals(best)) {
                candidates.put(1, true);
            } else {
                out.status = "LECTURA INCOMPLETA";
                out.message = "Falta el identificador individual Uxxx. Escanee la etiqueta de la caja.";
                return out;
            }
        }
        if (candidates.size() > 1) {
            out.status = "LECTURA AMBIGUA";
            out.message = "Se detectaron varios números de caja. Escanee nuevamente.";
            return out;
        }

        int boxNumber = candidates.keySet().iterator().next();
        if (boxNumber <= 0) {
            out.status = "LECTURA INVÁLIDA";
            out.message = "Número de caja inválido";
            return out;
        }
        out.boxNumber = boxNumber;
        out.normalizedBarcode = best + "U" + String.format(Locale.ROOT, "%03d", boxNumber);
        out.valid = true;
        return out;
    }

    public String resolveCode(String scan) {
        ParsedScan p = parseScan(scan);
        return p.code == null || p.code.isEmpty() ? null : p.code;
    }

    public Position findPosition(String label) {
        if (label == null) return null;
        String x = label.trim().toUpperCase(Locale.ROOT);
        for (Position p : positions) if (p.label().equals(x)) return p;
        return null;
    }

    private void updatePeak() {
        int occupied = 0;
        for (Position p : positions) {
            if (p.enabled && (!"LIBRE".equals(p.kind) || p.waitingRemoval)) occupied++;
        }
        peakPositions = Math.max(peakPositions, occupied);
    }

    public int enabledCount(String side) {
        int n = 0;
        for (Position p : positions) if (p.enabled && (side == null || side.equals(p.side))) n++;
        return n;
    }

    public String enableNext(String side) {
        if (side == null) return null;
        side = side.trim().toUpperCase(Locale.ROOT);
        if (!side.equals("I") && !side.equals("D")) return null;
        for (Position p : positions) {
            if (p.side.equals(side) && !p.enabled) {
                p.enabled = true;
                return p.label();
            }
        }
        return null;
    }

    public ActionResult disableLastFree(String side) {
        if (side == null) return new ActionResult(false, "", "Lado inválido", false);
        side = side.trim().toUpperCase(Locale.ROOT);
        Position lastEnabled = null;
        for (Position p : positions) {
            if (p.side.equals(side) && p.enabled) {
                if (lastEnabled == null || p.slot > lastEnabled.slot) lastEnabled = p;
            }
        }
        if (lastEnabled == null) return new ActionResult(false, "", "No hay posiciones habilitadas", false);
        if (!lastEnabled.isFree()) {
            return new ActionResult(false, lastEnabled.label(), lastEnabled.label() + " no está libre", false);
        }
        lastEnabled.enabled = false;
        return new ActionResult(true, lastEnabled.label(), lastEnabled.label() + " deshabilitada", false);
    }

    private double movementPercentile(int boxes) {
        if (boxCounts.isEmpty()) return 1.0;
        int count = 0;
        for (int x : boxCounts) if (x <= Math.max(1, boxes)) count++;
        return ((double) count) / boxCounts.size();
    }

    private int desiredSlot(CodeRecord r, boolean forceFar) {
        int maxEnabled = 1;
        boolean any = false;
        for (Position p : positions) if (p.enabled) { maxEnabled = Math.max(maxEnabled, p.slot); any = true; }
        if (!any) maxEnabled = MAX_PER_SIDE;
        if (forceFar) return maxEnabled;
        double percentile = movementPercentile(r.boxes);
        int desired = (int) Math.round(maxEnabled - percentile * (maxEnabled - 1));
        return clamp(desired, 1, maxEnabled);
    }

    private List<Position> freePositions() {
        ArrayList<Position> out = new ArrayList<>();
        for (Position p : positions) if (p.isFree()) out.add(p);
        return out;
    }

    private Position chooseFree(CodeRecord r, boolean forceFar) {
        List<Position> free = freePositions();
        if (free.isEmpty()) return null;
        final int desired = desiredSlot(r, forceFar);
        final Map<String, Integer> occupiedSide = new HashMap<>();
        occupiedSide.put("I", 0); occupiedSide.put("D", 0);
        for (Position p : positions) {
            if (p.enabled && !p.isFree()) occupiedSide.put(p.side, occupiedSide.get(p.side) + 1);
        }
        free.sort(new Comparator<Position>() {
            @Override public int compare(Position a, Position b) {
                int c = Integer.compare(Math.abs(a.slot - desired), Math.abs(b.slot - desired));
                if (c != 0) return c;
                c = Integer.compare(occupiedSide.get(a.side), occupiedSide.get(b.side));
                if (c != 0) return c;
                c = Integer.compare(a.slot, b.slot);
                if (c != 0) return c;
                return a.side.compareTo(b.side);
            }
        });
        return free.get(0);
    }

    private int positionCodeLimit(Position p) {
        if (p.reservedCodes.isEmpty()) return settings.maxCodesSmall;
        int limit = Integer.MAX_VALUE;
        for (String c : p.reservedCodes) {
            CodeRecord r = records.get(c);
            String cat = settings.categoryFor(r.cbm, r.boxes == 1);
            limit = Math.min(limit, settings.maxCodesFor(cat));
        }
        return limit == Integer.MAX_VALUE ? settings.maxCodesSmall : limit;
    }

    private Position reserveNewCode(String code) {
        CodeRecord r = records.get(code);
        if (r == null) return null;

        if (r.boxes == 1) {
            for (Position p : positions) {
                if (!p.enabled || p.waitingRemoval) continue;
                if ("UNIT".equals(p.kind)
                        && p.reservedCodes.size() < settings.maxCodesUnit
                        && p.reservedCbm + r.cbm <= settings.targetCapacity + 1e-9) {
                    p.reservedCodes.add(code);
                    p.reservedCbm += r.cbm;
                    positionForCode.put(code, p.label());
                    return p;
                }
            }
            Position p = chooseFree(r, true);
            if (p == null) return null;
            p.kind = "UNIT";
            p.reservedCodes.add(code);
            p.reservedCbm = r.cbm;
            positionForCode.put(code, p.label());
            updatePeak();
            return p;
        }

        String cat = settings.categoryFor(r.cbm, false);
        if (r.cbm > settings.targetCapacity || "G".equals(cat)) {
            Position p = chooseFree(r, false);
            if (p == null) return null;
            double unitCbm = Math.max(r.cbmPerBox, 1e-9);
            p.kind = "DEDICADA";
            p.dedicatedCode = code;
            p.reservedCodes.add(code);
            p.palletBoxCapacity = Math.max(1, (int) Math.floor(settings.targetCapacity / unitCbm));
            p.palletTargetBoxes = Math.min(r.boxes, p.palletBoxCapacity);
            p.reservedCbm = p.palletTargetBoxes * unitCbm;
            positionForCode.put(code, p.label());
            updatePeak();
            return p;
        }

        int itemLimit = settings.maxCodesFor(cat);
        Position best = null;
        double bestGap = Double.MAX_VALUE;
        for (Position p : positions) {
            if (!p.enabled || p.waitingRemoval || !"MIX".equals(p.kind)) continue;
            int limit = itemLimit;
            for (String c : p.reservedCodes) {
                CodeRecord existing = records.get(c);
                limit = Math.min(limit, settings.maxCodesFor(settings.categoryFor(existing.cbm, false)));
            }
            if (p.reservedCodes.size() >= limit) continue;
            if (p.reservedCbm + r.cbm > settings.targetCapacity + 1e-9) continue;
            double gap = settings.targetCapacity - (p.reservedCbm + r.cbm);
            if (best == null || gap < bestGap) { best = p; bestGap = gap; }
        }
        if (best == null) {
            best = chooseFree(r, false);
            if (best == null) return null;
            best.kind = "MIX";
            updatePeak();
        }
        best.reservedCodes.add(code);
        best.reservedCbm += r.cbm;
        positionForCode.put(code, best.label());
        return best;
    }

    private void markWaitingRemoval(Position p, String reason, boolean manual) {
        if (p.waitingRemoval) return;
        p.waitingRemoval = true;
        p.removalReason = reason == null ? "" : reason;
        p.manuallyClosed = manual;
    }

    private void markWaitingRemoval(Position p, String reason) {
        markWaitingRemoval(p, reason, false);
    }

    private void maybeMarkComplete(Position p) {
        if (p.waitingRemoval || "LIBRE".equals(p.kind)) return;
        if ("DEDICADA".equals(p.kind)) {
            String code = p.dedicatedCode;
            if (code == null) return;
            CodeRecord r = records.get(code);
            int remaining = r.boxes - received.get(code);
            int target = p.palletTargetBoxes != null ? p.palletTargetBoxes
                    : (p.palletBoxCapacity != null ? p.palletBoxCapacity : r.boxes);
            if (p.boxesOnCurrentPallet >= target || remaining <= 0) {
                markWaitingRemoval(p, remaining > 0 ? "Tarima dedicada llena" : "Código completo");
            }
            return;
        }

        boolean allComplete = !p.reservedCodes.isEmpty();
        for (String c : p.reservedCodes) if (!p.completeCodes.contains(c)) { allComplete = false; break; }
        if (!allComplete) return;

        if ("UNIT".equals(p.kind)) {
            boolean atLimit = p.reservedCodes.size() >= settings.maxCodesUnit
                    || p.reservedCbm >= settings.targetCapacity * 0.98;
            if (atLimit) markWaitingRemoval(p, "Tarima de unitarios completa");
            return;
        }
        if ("MIX".equals(p.kind)) {
            int limit = positionCodeLimit(p);
            boolean atLimit = p.reservedCodes.size() >= limit
                    || p.reservedCbm >= settings.targetCapacity * 0.98;
            if (atLimit) markWaitingRemoval(p, "Tarima mixta completa");
        }
    }

    private void markAllRemainingWhenContainerComplete() {
        int[] pr = progress();
        if (pr[0] != pr[1]) return;
        for (Position p : positions) {
            if (p.enabled && !"LIBRE".equals(p.kind) && !p.waitingRemoval) {
                markWaitingRemoval(p, "Contenedor completo");
            }
        }
    }

    public ScanResult scan(String rawScan) {
        ParsedScan parsed = parseScan(rawScan);
        if (!parsed.valid) {
            ScanResult out = ScanResult.fail(parsed.status, parsed.message);
            out.rawScan = parsed.rawCanonical;
            out.scan = parsed.rawCanonical;
            out.code = parsed.code;
            return out;
        }

        String code = parsed.code;
        CodeRecord r = records.get(code);
        String normalized = parsed.normalizedBarcode;
        int boxNumber = parsed.boxNumber;

        // Duplicado se decide por caja individual, nunca por contador global ni por cadena bruta.
        if (scannedUniqueBarcodes.containsKey(normalized)) {
            ScanMeta prior = scannedUniqueBarcodes.get(normalized);
            ScanResult out = ScanResult.fail("DUPLICADA", "YA ESCANEADA · asignada a " + prior.position);
            out.rawScan = parsed.rawCanonical;
            out.normalizedBarcode = normalized;
            out.scan = normalized;
            out.boxNumber = boxNumber;
            out.code = code;
            out.position = prior.position;
            out.firstScanTime = prior.time;
            out.received = received.get(code);
            out.expected = r.boxes;
            return out;
        }

        // Una Uxxx fuera del rango declarado es posible sobrante. No se contabiliza.
        if (boxNumber > r.boxes) {
            ScanResult out = ScanResult.fail("FUERA DE RANGO",
                    "POSIBLE SOBRANTE · Packing List U001–U" + String.format(Locale.ROOT, "%03d", r.boxes)
                            + " · recibida U" + String.format(Locale.ROOT, "%03d", boxNumber));
            out.rawScan = parsed.rawCanonical;
            out.normalizedBarcode = normalized;
            out.scan = normalized;
            out.boxNumber = boxNumber;
            out.code = code;
            out.position = positionForCode.getOrDefault(code, "");
            out.received = received.get(code);
            out.expected = r.boxes;
            return out;
        }

        String label = positionForCode.get(code);
        Position p = label == null ? null : findPosition(label);
        if (p == null) p = reserveNewCode(code);
        if (p == null) {
            ScanResult out = ScanResult.fail("SIN POSICIÓN", "Solicite al supervisor habilitar otra posición");
            out.rawScan = parsed.rawCanonical;
            out.normalizedBarcode = normalized;
            out.scan = normalized;
            out.boxNumber = boxNumber;
            out.code = code;
            out.received = received.get(code);
            out.expected = r.boxes;
            return out;
        }

        if (p.waitingRemoval) {
            ScanResult out = ScanResult.fail("POSICIÓN PENDIENTE", p.label() + " está COMPLETA. Marque POSICIÓN LISTA antes de continuar.");
            out.rawScan = parsed.rawCanonical;
            out.normalizedBarcode = normalized;
            out.scan = normalized;
            out.boxNumber = boxNumber;
            out.code = code;
            out.position = p.label();
            out.received = received.get(code);
            out.expected = r.boxes;
            return out;
        }

        Set<Integer> set = receivedBoxNumbers.get(code);
        if (set == null) {
            set = new HashSet<>();
            receivedBoxNumbers.put(code, set);
        }
        set.add(boxNumber);
        int newReceived = set.size();
        received.put(code, newReceived);
        p.actualCbm += r.cbmPerBox;
        p.addBox(code);
        int remaining = r.boxes - newReceived;

        scannedUniqueBarcodes.put(normalized,
                new ScanMeta(p.label(), code, now(), boxNumber, parsed.rawCanonical));

        String status = "OK";
        String message = "CAJA REGISTRADA";
        if (remaining == 0) {
            p.completeCodes.add(code);
            status = "CÓDIGO COMPLETO";
            message = "CÓDIGO COMPLETO";
        }

        maybeMarkComplete(p);
        markAllRemainingWhenContainerComplete();
        if (p.waitingRemoval) {
            status = "TARIMA COMPLETA";
            message = "TARIMA COMPLETA · RETIRAR";
        }
        updatePeak();

        ScanResult out = new ScanResult();
        out.ok = true;
        out.status = status;
        out.message = message;
        out.code = code;
        out.rawScan = parsed.rawCanonical;
        out.normalizedBarcode = normalized;
        out.scan = normalized;
        out.boxNumber = boxNumber;
        out.uniqueBoxId = true;
        out.position = p.label();
        out.received = newReceived;
        out.expected = r.boxes;
        out.remaining = remaining;
        out.waitingRemoval = p.waitingRemoval;
        return out;
    }


    // =============================================================================================
    // V0.4 BUFFER MODULAR
    // =============================================================================================

    private BufferManager ensureBufferManager() {
        if (bufferManager == null) bufferManager = new BufferManager(4);
        return bufferManager;
    }

    public int bufferPalletCount() {
        return isBufferMode() ? ensureBufferManager().palletCount : 0;
    }

    public int bufferTotalSectors() {
        return isBufferMode() ? ensureBufferManager().totalSectors() : 0;
    }

    public int bufferOccupiedSectors() {
        return isBufferMode() ? ensureBufferManager().occupiedSectors() : 0;
    }

    public int bufferFreeSectors() {
        return isBufferMode() ? ensureBufferManager().freeSectors() : 0;
    }

    public boolean addBufferPallet() {
        return isBufferMode() && ensureBufferManager().addBufferPallet();
    }

    public boolean removeLastEmptyBufferPallet() {
        return isBufferMode() && ensureBufferManager().removeLastEmptyBufferPallet();
    }

    public List<BufferSector> bufferSectors() {
        if (!isBufferMode()) return Collections.emptyList();
        return new ArrayList<>(ensureBufferManager().sectors);
    }

    /** Posición definitiva abierta para unitarios; solo se crea cuando llega un qty=1. */
    private Position bufferUnitaryPosition(CodeRecord r) {
        for (Position p : positions) {
            if (!p.enabled || p.waitingRemoval || !"UNIT".equals(p.kind)) continue;
            if (p.actualCbm + r.cbmPerBox <= settings.targetCapacity + 1e-9) return p;
        }
        Position p = chooseFree(r, true);
        if (p == null) return null;
        p.kind = "UNIT";
        updatePeak();
        return p;
    }

    /**
     * Modo BUFFER: el operador nunca elige dónde va una caja. La aplicación devuelve un sector Bxx-X
     * o, para qty=1, una posición definitiva Ixx/Dxx. Cada sector contiene un solo código.
     */
    public ScanResult scanBuffer(String rawScan) {
        ParsedScan parsed = parseScan(rawScan);
        if (!parsed.valid) {
            ScanResult out = ScanResult.fail(parsed.status, parsed.message);
            out.rawScan = parsed.rawCanonical;
            out.scan = parsed.rawCanonical;
            out.code = parsed.code;
            return out;
        }

        String code = parsed.code;
        CodeRecord r = records.get(code);
        String normalized = parsed.normalizedBarcode;
        int boxNumber = parsed.boxNumber;

        if (scannedUniqueBarcodes.containsKey(normalized)) {
            ScanMeta prior = scannedUniqueBarcodes.get(normalized);
            ScanResult out = ScanResult.fail("DUPLICADA", "YA ESCANEADA · asignada a " + prior.position);
            out.rawScan = parsed.rawCanonical;
            out.normalizedBarcode = normalized;
            out.scan = normalized;
            out.boxNumber = boxNumber;
            out.code = code;
            out.position = prior.position;
            out.firstScanTime = prior.time;
            out.received = received.get(code);
            out.expected = r.boxes;
            return out;
        }

        if (boxNumber > r.boxes) {
            ScanResult out = ScanResult.fail("FUERA DE RANGO",
                    "POSIBLE SOBRANTE · Packing List U001–U" + String.format(Locale.ROOT, "%03d", r.boxes)
                            + " · recibida U" + String.format(Locale.ROOT, "%03d", boxNumber));
            out.rawScan = parsed.rawCanonical;
            out.normalizedBarcode = normalized;
            out.scan = normalized;
            out.boxNumber = boxNumber;
            out.code = code;
            out.received = received.get(code);
            out.expected = r.boxes;
            return out;
        }

        Set<Integer> set = receivedBoxNumbers.get(code);
        if (set == null) {
            set = new HashSet<>();
            receivedBoxNumbers.put(code, set);
        }

        // Unitario: ya está completo desde la primera caja, por eso va directo a definitiva.
        if (r.boxes == 1) {
            Position p = bufferUnitaryPosition(r);
            if (p == null) {
                ScanResult out = ScanResult.fail("SIN POSICIÓN DEFINITIVA",
                        "No hay una posición definitiva libre para unitarios");
                out.rawScan = parsed.rawCanonical;
                out.normalizedBarcode = normalized;
                out.scan = normalized;
                out.boxNumber = boxNumber;
                out.code = code;
                out.received = received.get(code);
                out.expected = r.boxes;
                return out;
            }
            set.add(boxNumber);
            received.put(code, set.size());
            if (!p.reservedCodes.contains(code)) p.reservedCodes.add(code);
            p.completeCodes.add(code);
            p.actualCbm += r.cbmPerBox;
            p.reservedCbm = p.actualCbm;
            p.addBox(code);
            scannedUniqueBarcodes.put(normalized,
                    new ScanMeta(p.label(), code, now(), boxNumber, parsed.rawCanonical));
            if (p.actualCbm >= settings.targetCapacity * 0.98) {
                markWaitingRemoval(p, "Tarima definitiva de unitarios completa");
            }
            ScanResult out = new ScanResult();
            out.ok = true;
            out.status = p.waitingRemoval ? "TARIMA COMPLETA" : "UNITARIO";
            out.message = p.waitingRemoval ? "UNITARIOS · RETIRAR TARIMA" : "UNITARIO · DIRECTO A DEFINITIVA";
            out.code = code;
            out.rawScan = parsed.rawCanonical;
            out.normalizedBarcode = normalized;
            out.scan = normalized;
            out.boxNumber = boxNumber;
            out.uniqueBoxId = true;
            out.position = p.label();
            out.received = 1;
            out.expected = 1;
            out.remaining = 0;
            out.waitingRemoval = p.waitingRemoval;
            updatePeak();
            return out;
        }

        BufferManager bm = ensureBufferManager();
        BufferSector sector = bm.chooseSectorForBox(code, r.cbmPerBox, settings.physicalCapacity);
        if (sector == null) {
            ScanResult out = ScanResult.fail("BUFFER SATURADO",
                    "Sin sectores libres · forme una definitiva o habilite otra tarima buffer");
            out.rawScan = parsed.rawCanonical;
            out.normalizedBarcode = normalized;
            out.scan = normalized;
            out.boxNumber = boxNumber;
            out.code = code;
            out.received = received.get(code);
            out.expected = r.boxes;
            return out;
        }

        if (sector.isFree()) sector.assign(code);
        set.add(boxNumber);
        int newReceived = set.size();
        received.put(code, newReceived);
        sector.addBox(normalized, boxNumber, r.cbmPerBox);
        scannedUniqueBarcodes.put(normalized,
                new ScanMeta(sector.label(), code, now(), boxNumber, parsed.rawCanonical));

        int remaining = Math.max(0, r.boxes - newReceived);
        String status = "BUFFER";
        String message = "CAJA REGISTRADA EN BUFFER";
        if (remaining == 0) {
            bm.markCodeComplete(code);
            status = "CÓDIGO COMPLETO";
            message = "CÓDIGO COMPLETO · LISTO PARA CONSOLIDAR";
        } else {
            // Para códigos >1 tarima, puede aparecer un bloque listo antes de completar el código entero.
            for (BufferCandidate c : bm.readyCandidates(records, received, settings)) {
                if (code.equals(c.code) && "BLOQUE GRANDE LISTO".equals(c.reason)) {
                    status = "BLOQUE LISTO";
                    message = "HAY SUFICIENTE PARA FORMAR UNA DEFINITIVA";
                    break;
                }
            }
        }

        ScanResult out = new ScanResult();
        out.ok = true;
        out.status = status;
        out.message = message;
        out.code = code;
        out.rawScan = parsed.rawCanonical;
        out.normalizedBarcode = normalized;
        out.scan = normalized;
        out.boxNumber = boxNumber;
        out.uniqueBoxId = true;
        out.position = sector.label();
        out.received = newReceived;
        out.expected = r.boxes;
        out.remaining = remaining;
        return out;
    }

    public List<BufferCandidate> bufferReadyCandidates() {
        if (!isBufferMode()) return Collections.emptyList();
        return ensureBufferManager().readyCandidates(records, received, settings);
    }

    public List<BufferCandidate> suggestDefinitive(Set<String> excludedIds) {
        if (!isBufferMode()) return Collections.emptyList();
        return ensureBufferManager().suggestInitial(records, received, settings, excludedIds);
    }

    public List<BufferCandidate> suggestDefinitiveWithLocked(List<String> lockedIds,
                                                              Set<String> excludedIds,
                                                              boolean fillToPhysicalTarget) {
        if (!isBufferMode()) return Collections.emptyList();
        double limit = fillToPhysicalTarget
                ? settings.targetCapacity
                : Math.min(settings.targetCapacity, Math.max(0.25, settings.targetCapacity * 0.88));
        return ensureBufferManager().suggestWithLocked(lockedIds, records, received, settings, excludedIds, limit);
    }

    private Position chooseDefinitivePosition(List<BufferCandidate> candidates) {
        CodeRecord seed = null;
        if (candidates != null && !candidates.isEmpty()) seed = records.get(candidates.get(0).code);
        if (seed == null && !records.isEmpty()) seed = records.values().iterator().next();
        if (seed == null) return null;
        return chooseFree(seed, false);
    }

    /**
     * Confirma la tarima que el operador ya probó físicamente. Los sectores seleccionados se liberan,
     * y las cajas conservan trazabilidad actualizando su ubicación a la definitiva Ixx/Dxx.
     */
    public ActionResult formDefinitiveFromBuffer(List<String> candidateIds) {
        if (!isBufferMode()) return new ActionResult(false, "", "Modo BUFFER no activo", false);
        if (candidateIds == null || candidateIds.isEmpty()) {
            return new ActionResult(false, "", "Seleccione al menos un código/bloque", false);
        }
        BufferManager bm = ensureBufferManager();
        ArrayList<BufferCandidate> candidates = new ArrayList<>();
        for (String id : candidateIds) {
            BufferCandidate c = bm.findCandidateById(id, records, received, settings);
            if (c == null) return new ActionResult(false, "", "La propuesta cambió; vuelva a calcularla", false);
            candidates.add(c);
        }
        Position p = chooseDefinitivePosition(candidates);
        if (p == null) {
            return new ActionResult(false, "", "No hay posición definitiva libre. Retire una tarima o habilite otra.", false);
        }

        int totalBoxes = 0;
        double totalCbm = 0.0;
        for (BufferCandidate c : candidates) {
            int currentBoxes = bm.boxesForCandidate(c);
            if (currentBoxes <= 0) continue;
            totalBoxes += currentBoxes;
            totalCbm += c.cbm;
            if (!p.reservedCodes.contains(c.code)) p.reservedCodes.add(c.code);
            p.boxesByCodeOnCurrentPallet.put(c.code, p.boxesForCode(c.code) + currentBoxes);
            p.boxesOnCurrentPallet += currentBoxes;
            if (c.completeCode) p.completeCodes.add(c.code);

            for (String barcode : bm.barcodesForCandidate(c)) {
                ScanMeta meta = scannedUniqueBarcodes.get(barcode);
                if (meta != null) meta.position = p.label();
            }
        }
        p.kind = "DEFINITIVA";
        p.actualCbm = totalCbm;
        p.reservedCbm = totalCbm;
        markWaitingRemoval(p, "Tarima definitiva formada desde buffer");

        // Liberar buffer solo después de que la propuesta quedó confirmada físicamente.
        for (BufferCandidate c : candidates) bm.clearCandidate(c);
        updatePeak();
        return new ActionResult(true, p.label(), p.label() + " definitiva · " + totalBoxes + " cajas · "
                + String.format(Locale.getDefault(), "%.2f m³", totalCbm) + " · RETIRAR", false);
    }


    /** Devuelve otra tarima ABIERTA que ya contiene el código. Las tarimas cerradas no bloquean una continuación. */
    public Position findOtherOpenPositionForCode(String code, String excludeLabel) {
        if (code == null) return null;
        String c = code.trim().toUpperCase(Locale.ROOT);
        String exclude = excludeLabel == null ? "" : excludeLabel.trim().toUpperCase(Locale.ROOT);
        for (Position p : positions) {
            if (!p.enabled || p.waitingRemoval || "LIBRE".equals(p.kind)) continue;
            if (p.label().equals(exclude)) continue;
            if (p.reservedCodes.contains(c)) return p;
        }
        return null;
    }

    /**
     * Modo MANUAL: el operador elige físicamente la tarima antes de escanear.
     * Si el código ya está en otra tarima abierta, se devuelve CÓDIGO EN OTRA TARIMA y NO se contabiliza.
     * allowSplit=true se usa solo tras confirmación explícita del operador.
     */
    public ScanResult scanManual(String rawScan, String targetLabel, boolean allowSplit) {
        ParsedScan parsed = parseScan(rawScan);
        if (!parsed.valid) {
            ScanResult out = ScanResult.fail(parsed.status, parsed.message);
            out.rawScan = parsed.rawCanonical;
            out.scan = parsed.rawCanonical;
            out.code = parsed.code;
            return out;
        }

        String code = parsed.code;
        CodeRecord r = records.get(code);
        String normalized = parsed.normalizedBarcode;
        int boxNumber = parsed.boxNumber;

        if (scannedUniqueBarcodes.containsKey(normalized)) {
            ScanMeta prior = scannedUniqueBarcodes.get(normalized);
            ScanResult out = ScanResult.fail("DUPLICADA", "YA ESCANEADA · asignada a " + prior.position);
            out.rawScan = parsed.rawCanonical;
            out.normalizedBarcode = normalized;
            out.scan = normalized;
            out.boxNumber = boxNumber;
            out.code = code;
            out.position = prior.position;
            out.firstScanTime = prior.time;
            out.received = received.get(code);
            out.expected = r.boxes;
            return out;
        }

        if (boxNumber > r.boxes) {
            ScanResult out = ScanResult.fail("FUERA DE RANGO",
                    "POSIBLE SOBRANTE · Packing List U001–U" + String.format(Locale.ROOT, "%03d", r.boxes)
                            + " · recibida U" + String.format(Locale.ROOT, "%03d", boxNumber));
            out.rawScan = parsed.rawCanonical;
            out.normalizedBarcode = normalized;
            out.scan = normalized;
            out.boxNumber = boxNumber;
            out.code = code;
            out.position = targetLabel == null ? "" : targetLabel;
            out.received = received.get(code);
            out.expected = r.boxes;
            return out;
        }

        Position target = findPosition(targetLabel);
        if (target == null) {
            ScanResult out = ScanResult.fail("SELECCIONE TARIMA", "Seleccione una tarima antes de escanear");
            out.rawScan = parsed.rawCanonical;
            out.normalizedBarcode = normalized;
            out.scan = normalized;
            out.boxNumber = boxNumber;
            out.code = code;
            out.received = received.get(code);
            out.expected = r.boxes;
            return out;
        }
        if (!target.enabled) {
            ScanResult out = ScanResult.fail("TARIMA NO HABILITADA", target.label() + " no está habilitada");
            out.rawScan = parsed.rawCanonical;
            out.normalizedBarcode = normalized;
            out.scan = normalized;
            out.boxNumber = boxNumber;
            out.code = code;
            out.position = target.label();
            out.received = received.get(code);
            out.expected = r.boxes;
            return out;
        }
        if (target.waitingRemoval) {
            ScanResult out = ScanResult.fail("TARIMA CERRADA", target.label() + " está pendiente de retiro");
            out.rawScan = parsed.rawCanonical;
            out.normalizedBarcode = normalized;
            out.scan = normalized;
            out.boxNumber = boxNumber;
            out.code = code;
            out.position = target.label();
            out.received = received.get(code);
            out.expected = r.boxes;
            return out;
        }

        if (!target.reservedCodes.contains(code)) {
            Position other = findOtherOpenPositionForCode(code, target.label());
            if (other != null && !allowSplit) {
                ScanResult out = ScanResult.fail("CÓDIGO EN OTRA TARIMA",
                        "Este código ya está en " + other.label() + " · seleccionada " + target.label());
                out.rawScan = parsed.rawCanonical;
                out.normalizedBarcode = normalized;
                out.scan = normalized;
                out.boxNumber = boxNumber;
                out.code = code;
                out.position = other.label();
                out.received = received.get(code);
                out.expected = r.boxes;
                return out;
            }

            if (target.isFree()) target.kind = "MANUAL";
            else if (!"MANUAL".equals(target.kind)) {
                ScanResult out = ScanResult.fail("TARIMA NO MANUAL", target.label() + " pertenece al modo automático");
                out.rawScan = parsed.rawCanonical;
                out.normalizedBarcode = normalized;
                out.scan = normalized;
                out.boxNumber = boxNumber;
                out.code = code;
                out.position = target.label();
                out.received = received.get(code);
                out.expected = r.boxes;
                return out;
            }
            target.reservedCodes.add(code);
            positionForCode.put(code, target.label());
        }

        Set<Integer> set = receivedBoxNumbers.get(code);
        if (set == null) {
            set = new HashSet<>();
            receivedBoxNumbers.put(code, set);
        }
        set.add(boxNumber);
        int newReceived = set.size();
        received.put(code, newReceived);
        target.actualCbm += r.cbmPerBox;
        target.reservedCbm = target.actualCbm;
        target.addBox(code);
        int remaining = r.boxes - newReceived;

        scannedUniqueBarcodes.put(normalized,
                new ScanMeta(target.label(), code, now(), boxNumber, parsed.rawCanonical));

        String status = "OK";
        String message = "CAJA REGISTRADA";
        if (remaining == 0) {
            for (Position p : positions) if (p.reservedCodes.contains(code)) p.completeCodes.add(code);
            status = "CÓDIGO COMPLETO";
            message = "CÓDIGO COMPLETO";
        }

        double weight = estimatedWeight(target);
        if (weight > 250.0) {
            message = message + " · ⚠ PESO > 250 kg";
        } else if (target.actualCbm > settings.targetCapacity + 1e-9) {
            message = message + " · ⚠ REVISAR CAPACIDAD";
        }

        markAllRemainingWhenContainerComplete();
        updatePeak();

        ScanResult out = new ScanResult();
        out.ok = true;
        out.status = target.waitingRemoval ? "TARIMA COMPLETA" : status;
        out.message = target.waitingRemoval ? "TARIMA COMPLETA · RETIRAR" : message;
        out.code = code;
        out.rawScan = parsed.rawCanonical;
        out.normalizedBarcode = normalized;
        out.scan = normalized;
        out.boxNumber = boxNumber;
        out.uniqueBoxId = true;
        out.position = target.label();
        out.received = newReceived;
        out.expected = r.boxes;
        out.remaining = remaining;
        out.waitingRemoval = target.waitingRemoval;
        return out;
    }

    /** Operador: físicamente la tarima ya no admite más mercancía. */
    public ActionResult closePositionEarly(String label) {
        Position p = findPosition(label);
        if (p == null) return new ActionResult(false, label, "Posición no encontrada", false);
        if (!p.enabled || "LIBRE".equals(p.kind)) return new ActionResult(false, p.label(), "La posición está libre", false);
        if (p.waitingRemoval) return new ActionResult(false, p.label(), "La tarima ya está pendiente de retiro", false);
        if (p.boxesOnCurrentPallet <= 0) return new ActionResult(false, p.label(), "No hay cajas registradas en esta tarima", false);
        markWaitingRemoval(p, "Cierre anticipado: TARIMA LLENA / NO CABE MÁS", true);
        return new ActionResult(true, p.label(), p.label() + " marcada TARIMA LLENA · RETIRAR", false);
    }

    /** Supervisor: revierte un cierre antes de que la posición haya sido marcada LISTA. */
    public ActionResult reopenPosition(String label) {
        Position p = findPosition(label);
        if (p == null) return new ActionResult(false, label, "Posición no encontrada", false);
        if (!p.waitingRemoval) return new ActionResult(false, p.label(), "La posición no está cerrada", false);

        boolean hasPending = false;
        for (String c : p.reservedCodes) {
            CodeRecord r = records.get(c);
            if (r != null && received.get(c) < r.boxes) { hasPending = true; break; }
        }
        if (!p.manuallyClosed && !hasPending) {
            return new ActionResult(false, p.label(), "No se puede reabrir: los códigos de la tarima ya están completos", false);
        }
        p.waitingRemoval = false;
        p.removalReason = "";
        p.manuallyClosed = false;
        return new ActionResult(true, p.label(), p.label() + " reabierta", false);
    }

    public ActionResult markPositionReady(String label) {
        Position p = findPosition(label);
        if (p == null) return new ActionResult(false, label, "Posición no encontrada", false);
        if (!p.waitingRemoval) return new ActionResult(false, p.label(), p.label() + " no está pendiente de retiro", false);

        String kindBefore = p.kind;
        String code = p.dedicatedCode;
        p.palletSeq += 1;

        if ("DEDICADA".equals(kindBefore) && code != null) {
            CodeRecord r = records.get(code);
            int remaining = Math.max(0, r.boxes - received.get(code));
            if (remaining > 0) {
                p.waitingRemoval = false;
                p.removalReason = "";
                p.manuallyClosed = false;
                p.actualCbm = 0.0;
                p.boxesOnCurrentPallet = 0;
                p.boxesByCodeOnCurrentPallet.clear();
                p.completeCodes.remove(code);
                int cap = p.palletBoxCapacity != null ? p.palletBoxCapacity : 1;
                p.palletTargetBoxes = Math.min(cap, remaining);
                p.reservedCbm = p.palletTargetBoxes * r.cbmPerBox;
                positionForCode.put(code, p.label());
                return new ActionResult(true, p.label(), p.label() + " lista. Continúa " + code + " en nueva tarima.", true);
            }
        }

        ArrayList<String> toRemove = new ArrayList<>();
        for (Map.Entry<String, String> e : positionForCode.entrySet()) {
            if (p.label().equals(e.getValue())) toRemove.add(e.getKey());
        }
        p.resetKeepingIdentity();
        for (String c : toRemove) {
            Position other = findOtherOpenPositionForCode(c, p.label());
            if (other == null) positionForCode.remove(c);
            else positionForCode.put(c, other.label());
        }
        return new ActionResult(true, p.label(), p.label() + " quedó LIBRE", false);
    }

    /**
     * Supervisor: anula un escaneo aceptado. La interfaz solo permite usarlo cuando ese escaneo es
     * el último evento, para evitar reconstrucciones ambiguas del estado físico.
     */
    public ActionResult undoAcceptedBox(String normalizedBarcode) {
        if (normalizedBarcode == null) return new ActionResult(false, "", "Barcode vacío", false);
        String key = canonicalScan(normalizedBarcode);
        ScanMeta meta = scannedUniqueBarcodes.get(key);
        if (meta == null) return new ActionResult(false, "", "La caja no está registrada como aceptada", false);
        CodeRecord r = records.get(meta.code);
        if (r == null) return new ActionResult(false, meta.position, "Código no encontrado", false);

        // Si el contenedor se había marcado completo por esta caja, retirar ese cierre automático.
        for (Position q : positions) {
            if (q.waitingRemoval && "Contenedor completo".equals(q.removalReason)) {
                q.waitingRemoval = false;
                q.removalReason = "";
                q.manuallyClosed = false;
            }
        }

        scannedUniqueBarcodes.remove(key);
        Set<Integer> set = receivedBoxNumbers.get(meta.code);
        if (set != null) set.remove(meta.boxNumber);
        int newReceived = set == null ? 0 : set.size();
        received.put(meta.code, newReceived);

        if (isTransferMode()) {
            transferForBarcode.remove(key);
            return new ActionResult(true, meta.position,
                    "Escaneo anulado: " + key + " · destino " + meta.position, false);
        }

        // V0.4 BUFFER: si la caja aún está físicamente en un sector Bxx-X, corregir allí.
        if (isBufferMode() && meta.position != null && meta.position.startsWith("B")) {
            BufferSector bs = ensureBufferManager().findSector(meta.position);
            if (bs != null && meta.code.equals(bs.code)) {
                bs.removeBarcode(key, r.cbmPerBox);
                for (BufferSector s : ensureBufferManager().sectorsForCode(meta.code)) s.codeComplete = false;
            }
            return new ActionResult(true, meta.position,
                    "Escaneo anulado: " + key + " · " + meta.position, false);
        }

        Position p = findPosition(meta.position);
        if (p != null && !"LIBRE".equals(p.kind)) {
            p.actualCbm = Math.max(0.0, p.actualCbm - r.cbmPerBox);
            p.removeBox(meta.code);
            p.completeCodes.remove(meta.code);
            if (p.waitingRemoval && !p.manuallyClosed) {
                p.waitingRemoval = false;
                p.removalReason = "";
            }

            boolean removeReservation = newReceived == 0
                    || ("MANUAL".equals(p.kind) && p.boxesForCode(meta.code) == 0);
            if (removeReservation) {
                p.reservedCodes.remove(meta.code);
                p.completeCodes.remove(meta.code);
                if ("MANUAL".equals(p.kind)) p.reservedCbm = p.actualCbm;
                else p.reservedCbm = Math.max(0.0, p.reservedCbm - r.cbm);

                Position other = findOtherOpenPositionForCode(meta.code, p.label());
                if (other == null) positionForCode.remove(meta.code);
                else positionForCode.put(meta.code, other.label());

                if ("DEDICADA".equals(p.kind) || p.reservedCodes.isEmpty()) {
                    p.resetKeepingIdentity();
                }
            }
        }
        return new ActionResult(true, meta.position,
                "Escaneo anulado: " + key + " · " + meta.position, false);
    }

    public List<String> missingBoxes(String code, int limit) {
        ArrayList<String> out = new ArrayList<>();
        CodeRecord r = records.get(code);
        if (r == null) return out;
        Set<Integer> set = receivedBoxNumbers.get(code);
        if (set == null) set = Collections.emptySet();
        for (int i = 1; i <= r.boxes; i++) {
            if (!set.contains(i)) {
                out.add("U" + String.format(Locale.ROOT, "%03d", i));
                if (limit > 0 && out.size() >= limit) break;
            }
        }
        return out;
    }

    public int[] progress() {
        int expected = 0, got = 0;
        for (CodeRecord r : records.values()) expected += r.boxes;
        for (int x : received.values()) got += x;
        return new int[]{got, expected};
    }

    public Pressure pressure() {
        Pressure x = new Pressure();
        for (Position p : positions) {
            if (p.enabled) {
                x.enabled++;
                if (!p.isFree()) x.occupied++;
                if (p.isFree()) x.free++;
                if (p.waitingRemoval) x.pendingRemoval++;
                if ("I".equals(p.side)) x.leftEnabled++; else x.rightEnabled++;
            }
        }
        x.availableToEnable = MAX_PER_SIDE * 2 - x.enabled;
        x.peak = peakPositions;
        x.ratio = x.enabled == 0 ? 0.0 : ((double) x.occupied) / x.enabled;
        if (x.ratio >= 1.0) x.level = "SATURADA";
        else if (x.ratio >= 0.90) x.level = "ALTA";
        else if (x.ratio >= 0.75) x.level = "ATENCIÓN";
        else x.level = "NORMAL";
        return x;
    }

    public List<Position> pendingRemovalPositions() {
        ArrayList<Position> out = new ArrayList<>();
        for (Position p : positions) if (p.enabled && p.waitingRemoval) out.add(p);
        return out;
    }

    private int[] positionProgress(Position p) {
        if ("DEDICADA".equals(p.kind) && p.dedicatedCode != null) {
            int target = p.palletTargetBoxes != null ? p.palletTargetBoxes
                    : (p.palletBoxCapacity != null ? p.palletBoxCapacity : 1);
            return new int[]{p.boxesOnCurrentPallet, target};
        }
        if (!p.reservedCodes.isEmpty()) {
            int expected = 0, current = p.boxesOnCurrentPallet;
            for (String c : p.reservedCodes) {
                CodeRecord r = records.get(c);
                expected += r.boxes;
            }
            return new int[]{current, expected};
        }
        return new int[]{0, 0};
    }

    public double estimatedWeight(Position p) {
        double total = 0.0;
        boolean any = false;
        for (Map.Entry<String, Integer> e : p.boxesByCodeOnCurrentPallet.entrySet()) {
            CodeRecord r = records.get(e.getKey());
            if (r != null && r.weightPerBox != null) {
                total += r.weightPerBox * e.getValue();
                any = true;
            }
        }
        return any ? total : -1.0;
    }

    public List<PositionCard> positionCards() {
        ArrayList<PositionCard> rows = new ArrayList<>();
        for (Position p : positions) {
            int[] pg = positionProgress(p);
            int current = pg[0], target = pg[1];
            double fill = target > 0 ? ((double) current) / target : 0.0;
            String state;
            if (!p.enabled) state = "NO HABILITADA";
            else if (p.waitingRemoval) state = "COMPLETA";
            else if ("LIBRE".equals(p.kind)) state = "LIBRE";
            else if (fill >= 0.8) state = "PRÓXIMA";
            else state = "EN PROCESO";

            String title, detail;
            if ("DEDICADA".equals(p.kind) && p.dedicatedCode != null) {
                title = p.dedicatedCode;
                detail = current + "/" + target + " cajas";
            } else if ("UNIT".equals(p.kind)) {
                title = "UNITARIOS · " + p.reservedCodes.size() + " cód.";
                detail = p.boxesOnCurrentPallet + " cajas";
            } else if ("MIX".equals(p.kind)) {
                title = p.reservedCodes.size() + " códigos";
                detail = p.boxesOnCurrentPallet + " cajas · " + String.format(Locale.getDefault(), "%.2f m³", p.actualCbm);
            } else if ("MANUAL".equals(p.kind)) {
                title = p.reservedCodes.size() + " códigos";
                double w = estimatedWeight(p);
                detail = p.boxesOnCurrentPallet + " cajas · " + String.format(Locale.getDefault(), "%.2f m³", p.actualCbm)
                        + (w >= 0 ? " · " + String.format(Locale.getDefault(), "%.0f kg", w) : "");
            } else if ("DEFINITIVA".equals(p.kind)) {
                title = "DEFINITIVA · " + p.reservedCodes.size() + " cód.";
                detail = p.boxesOnCurrentPallet + " cajas · " + String.format(Locale.getDefault(), "%.2f m³", p.actualCbm);
            } else {
                title = p.enabled ? "LIBRE" : "NO HABILITADA";
                detail = "";
            }
            rows.add(new PositionCard(p.label(), state, title, detail, p.enabled, p.slot, p.side,
                    p.waitingRemoval, p.reservedCodes.size(), p.boxesOnCurrentPallet, p.actualCbm));
        }
        return rows;
    }
}
