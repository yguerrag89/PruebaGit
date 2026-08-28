package com.ilubox.descargapda.core;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Motor físico del BUFFER MODULAR V0.4.
 * - Cada tarima buffer se divide en 4 sectores base A-D.
 * - Cada sector contiene un solo código.
 * - Un código puede crecer hacia otros sectores sin mover las cajas ya colocadas.
 * - Los códigos normales solo quedan disponibles para definitiva cuando están completos.
 * - Los códigos que por Packing List ocupan más de una tarima pueden liberar un bloque antes de
 *   completar el código entero cuando ya hay suficiente mercancía acumulada en buffer.
 */
public class BufferManager implements Serializable {
    private static final long serialVersionUID = 1L;
    public static final int SECTORS_PER_PALLET = 4;
    public static final int MAX_BUFFER_PALLETS = 10;

    public final ArrayList<BufferSector> sectors = new ArrayList<>();
    public int palletCount;

    public BufferManager(int palletCount) {
        this.palletCount = Math.max(1, Math.min(MAX_BUFFER_PALLETS, palletCount));
        rebuildEmpty(this.palletCount);
    }

    private void rebuildEmpty(int count) {
        sectors.clear();
        for (int b = 1; b <= count; b++) {
            for (int s = 0; s < SECTORS_PER_PALLET; s++) sectors.add(new BufferSector(b, s));
        }
    }

    public int totalSectors() { return sectors.size(); }
    public int occupiedSectors() {
        int n = 0;
        for (BufferSector s : sectors) if (!s.isFree()) n++;
        return n;
    }
    public int freeSectors() { return totalSectors() - occupiedSectors(); }

    public BufferSector findSector(String label) {
        if (label == null) return null;
        String x = label.trim().toUpperCase(Locale.ROOT);
        for (BufferSector s : sectors) if (s.label().equals(x)) return s;
        return null;
    }

    public List<BufferSector> sectorsForCode(String code) {
        ArrayList<BufferSector> out = new ArrayList<>();
        if (code == null) return out;
        for (BufferSector s : sectors) if (code.equals(s.code)) out.add(s);
        Collections.sort(out, (a, b) -> a.label().compareTo(b.label()));
        return out;
    }

    public BufferSector chooseSectorForBox(String code, double boxCbm, double physicalCapacity) {
        double sectorCap = Math.max(0.01, physicalCapacity / SECTORS_PER_PALLET);
        List<BufferSector> existing = sectorsForCode(code);

        // 1) Reutilizar un sector del mismo código si la nueva caja cabe aproximadamente en él.
        BufferSector best = null;
        double bestFree = Double.MAX_VALUE;
        for (BufferSector s : existing) {
            double free = sectorCap - s.actualCbm;
            if (s.actualCbm <= 0 || free + 1e-9 >= boxCbm) {
                double after = Math.max(0.0, free - boxCbm);
                if (after < bestFree) { best = s; bestFree = after; }
            }
        }
        if (best != null) return best;

        // 2) Abrir otro sector. Preferimos la misma tarima física para que el código quede localizado.
        if (!existing.isEmpty()) {
            int preferredBuffer = existing.get(0).bufferIndex;
            for (BufferSector s : sectors) {
                if (s.bufferIndex == preferredBuffer && s.isFree()) return s;
            }
        }

        // 3) Primera posición libre global, de B01-A hacia adelante.
        for (BufferSector s : sectors) if (s.isFree()) return s;
        return null;
    }

    public boolean addBufferPallet() {
        if (palletCount >= MAX_BUFFER_PALLETS) return false;
        palletCount += 1;
        for (int s = 0; s < SECTORS_PER_PALLET; s++) sectors.add(new BufferSector(palletCount, s));
        return true;
    }

    public boolean removeLastEmptyBufferPallet() {
        if (palletCount <= 1) return false;
        for (BufferSector s : sectors) {
            if (s.bufferIndex == palletCount && !s.isFree()) return false;
        }
        int last = palletCount;
        for (Iterator<BufferSector> it = sectors.iterator(); it.hasNext();) {
            if (it.next().bufferIndex == last) it.remove();
        }
        palletCount -= 1;
        return true;
    }

    public void markCodeComplete(String code) {
        for (BufferSector s : sectors) if (code.equals(s.code)) s.codeComplete = true;
    }

    public double cbmForCode(String code) {
        double x = 0.0;
        for (BufferSector s : sectors) if (code.equals(s.code)) x += s.actualCbm;
        return x;
    }

    public int boxesForCode(String code) {
        int x = 0;
        for (BufferSector s : sectors) if (code.equals(s.code)) x += s.boxes;
        return x;
    }

    private double weightForSectors(List<BufferSector> selected, CodeRecord rec) {
        if (rec == null || rec.weightPerBox == null) return -1.0;
        int n = 0;
        for (BufferSector s : selected) n += s.boxes;
        return n * rec.weightPerBox;
    }

    /**
     * Crea candidatos disponibles para una tarima definitiva.
     * Para un código <=1 tarima: solo aparece al completar TODO el código.
     * Para >1 tarima: aparece un bloque cuando el buffer acumulado alcanza promotionThreshold.
     * Si el código total ya terminó, el remanente también se habilita aunque sea menor.
     */
    public List<BufferCandidate> readyCandidates(Map<String, CodeRecord> records,
                                                  Map<String, Integer> received,
                                                  Settings settings) {
        ArrayList<BufferCandidate> out = new ArrayList<>();
        double target = Math.max(0.10, settings.targetCapacity);
        double promotionThreshold = Math.min(target, Math.max(target * 0.82, target - 0.35));

        LinkedHashSet<String> codes = new LinkedHashSet<>();
        for (BufferSector s : sectors) if (!s.isFree()) codes.add(s.code);

        for (String code : codes) {
            CodeRecord rec = records.get(code);
            if (rec == null) continue;
            List<BufferSector> codeSectors = sectorsForCode(code);
            if (codeSectors.isEmpty()) continue;
            Integer codeReceived = received.get(code);
            boolean wholeCodeComplete = (codeReceived == null ? 0 : codeReceived) >= rec.boxes;
            double buffered = 0.0;
            for (BufferSector s : codeSectors) buffered += s.actualCbm;

            if (rec.cbm <= target + 1e-9) {
                if (!wholeCodeComplete) continue;
                BufferCandidate c = candidateFrom(code, codeSectors, rec, true, "CÓDIGO COMPLETO");
                out.add(c);
                continue;
            }

            // Código de más de una tarima: extraer un bloque de sectores enteros, sin mezclar códigos.
            if (buffered + 1e-9 < promotionThreshold && !wholeCodeComplete) continue;

            ArrayList<BufferSector> sorted = new ArrayList<>(codeSectors);
            Collections.sort(sorted, (a,b) -> Double.compare(b.actualCbm, a.actualCbm));
            ArrayList<BufferSector> chosen = new ArrayList<>();
            double sum = 0.0;
            for (BufferSector s : sorted) {
                if (s.actualCbm <= 0) continue;
                if (sum + s.actualCbm <= target + 1e-9) {
                    chosen.add(s);
                    sum += s.actualCbm;
                }
            }
            if (chosen.isEmpty()) chosen.add(sorted.get(0));
            sum = 0.0;
            for (BufferSector s : chosen) sum += s.actualCbm;

            // Si aún no termina el código, exigimos un bloque razonablemente lleno.
            if (!wholeCodeComplete && sum + 1e-9 < promotionThreshold) continue;

            boolean candidateCompletesRemainingCode = wholeCodeComplete && chosen.size() == codeSectors.size();
            BufferCandidate c = candidateFrom(code, chosen, rec, candidateCompletesRemainingCode,
                    wholeCodeComplete ? "REMANENTE COMPLETO" : "BLOQUE GRANDE LISTO");
            out.add(c);
        }

        // Orden estable: primero los bloques grandes / voluminosos, luego completos menores.
        Collections.sort(out, (a,b) -> {
            int reason = Boolean.compare(b.reason.contains("GRANDE"), a.reason.contains("GRANDE"));
            if (reason != 0) return reason;
            int cbmCmp = Double.compare(b.cbm, a.cbm);
            if (cbmCmp != 0) return cbmCmp;
            return a.code.compareTo(b.code);
        });
        return out;
    }

    private BufferCandidate candidateFrom(String code, List<BufferSector> selected, CodeRecord rec,
                                          boolean completeCode, String reason) {
        BufferCandidate c = new BufferCandidate();
        c.code = code;
        c.completeCode = completeCode;
        c.reason = reason;
        StringBuilder id = new StringBuilder(code);
        for (BufferSector s : selected) {
            c.sectorLabels.add(s.label());
            c.boxes += s.boxes;
            c.cbm += s.actualCbm;
            id.append("|").append(s.label());
        }
        c.weight = weightForSectors(selected, rec);
        c.id = id.toString();
        return c;
    }

    public BufferCandidate findCandidateById(String id, Map<String, CodeRecord> records,
                                             Map<String, Integer> received, Settings settings) {
        for (BufferCandidate c : readyCandidates(records, received, settings)) {
            if (c.id.equals(id)) return c;
        }
        return null;
    }

    /**
     * Propuesta inicial conservadora. No existe límite de cantidad de códigos: solo CBM teórico.
     * La realidad física/visibilidad la confirma el operador.
     */
    public List<BufferCandidate> suggestInitial(Map<String, CodeRecord> records,
                                                Map<String, Integer> received,
                                                Settings settings,
                                                Set<String> excludedIds) {
        return suggestWithLocked(Collections.emptyList(), records, received, settings, excludedIds,
                Math.min(settings.targetCapacity, Math.max(0.25, settings.targetCapacity * 0.88)));
    }

    /** Agrega candidatos conservando los ya probados/aceptados en el borrador. */
    public List<BufferCandidate> suggestWithLocked(List<String> lockedIds,
                                                   Map<String, CodeRecord> records,
                                                   Map<String, Integer> received,
                                                   Settings settings,
                                                   Set<String> excludedIds,
                                                   double maxCbm) {
        ArrayList<BufferCandidate> ready = new ArrayList<>(readyCandidates(records, received, settings));
        HashMap<String, BufferCandidate> byId = new HashMap<>();
        for (BufferCandidate c : ready) byId.put(c.id, c);

        ArrayList<BufferCandidate> out = new ArrayList<>();
        HashSet<String> used = new HashSet<>();
        double sum = 0.0;
        if (lockedIds != null) {
            for (String id : lockedIds) {
                BufferCandidate c = byId.get(id);
                if (c != null) {
                    out.add(c); used.add(c.id); sum += c.cbm;
                }
            }
        }

        // Best-fit sencillo por bloques disponibles; no limita el número de códigos.
        boolean added;
        do {
            added = false;
            BufferCandidate best = null;
            double bestAfter = -1.0;
            for (BufferCandidate c : ready) {
                if (used.contains(c.id)) continue;
                if (excludedIds != null && excludedIds.contains(c.id)) continue;
                double after = sum + c.cbm;
                if (after <= maxCbm + 1e-9 && after > bestAfter) {
                    best = c;
                    bestAfter = after;
                }
            }
            if (best != null) {
                out.add(best); used.add(best.id); sum += best.cbm; added = true;
            }
        } while (added);

        if (out.isEmpty() && !ready.isEmpty()) {
            // Si el único bloque disponible ya es grande, proponemos probarlo solo.
            for (BufferCandidate c : ready) {
                if (excludedIds == null || !excludedIds.contains(c.id)) {
                    out.add(c); break;
                }
            }
        }
        return out;
    }

    public List<String> barcodesForCandidate(BufferCandidate c) {
        ArrayList<String> out = new ArrayList<>();
        if (c == null) return out;
        for (String label : c.sectorLabels) {
            BufferSector s = findSector(label);
            if (s != null && c.code.equals(s.code)) out.addAll(s.barcodeSnapshot());
        }
        return out;
    }

    public int boxesForCandidate(BufferCandidate c) {
        int n = 0;
        if (c == null) return 0;
        for (String label : c.sectorLabels) {
            BufferSector s = findSector(label);
            if (s != null && c.code.equals(s.code)) n += s.boxes;
        }
        return n;
    }

    public void clearCandidate(BufferCandidate c) {
        if (c == null) return;
        for (String label : c.sectorLabels) {
            BufferSector s = findSector(label);
            if (s != null && c.code.equals(s.code)) s.clear();
        }
    }
}
