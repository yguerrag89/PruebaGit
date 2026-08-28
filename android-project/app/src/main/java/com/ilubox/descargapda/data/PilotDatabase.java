package com.ilubox.descargapda.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.ilubox.descargapda.core.ScanResult;
import com.ilubox.descargapda.core.UnloadEngine;
import com.ilubox.descargapda.core.CodeRecord;
import com.ilubox.descargapda.core.PdaResultWriter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;

public class PilotDatabase extends SQLiteOpenHelper {
    private static final String DB_NAME = "ilubox_descarga_piloto.db";
    private static final int DB_VERSION = 3;

    public static class EventRow {
        public long id;
        public String time;
        public String scan;
        public String normalizedScan;
        public int boxNumber;
        public String code;
        public String position;
        public String status;
        public String message;
        public int received;
        public int expected;
        public boolean accepted;
    }

    public PilotDatabase(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE session_state (id INTEGER PRIMARY KEY CHECK(id=1), container_id TEXT, engine_blob BLOB NOT NULL, updated_at TEXT)");
        db.execSQL("CREATE TABLE events (id INTEGER PRIMARY KEY AUTOINCREMENT, time TEXT, scan TEXT, normalized_scan TEXT, box_number INTEGER DEFAULT 0, code TEXT, position TEXT, status TEXT, message TEXT, received INTEGER, expected INTEGER, accepted INTEGER)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            try { db.execSQL("ALTER TABLE events ADD COLUMN normalized_scan TEXT DEFAULT ''"); } catch (Exception ignored) {}
            try { db.execSQL("ALTER TABLE events ADD COLUMN box_number INTEGER DEFAULT 0"); } catch (Exception ignored) {}
            // El engine V0.1 no se reutiliza porque cambió la semántica del conteo individual.
            db.delete("session_state", null, null);
        }
        if (oldVersion < 3) {
            // El estado serializado V0.8 no conoce validación de tarima ni asignación directa dinámica.
            db.delete("session_state", null, null);
        }
    }

    private static String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
    }

    public void saveEngine(UnloadEngine engine) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bos)) {
            out.writeObject(engine);
        }
        ContentValues values = new ContentValues();
        values.put("id", 1);
        values.put("container_id", engine.containerId);
        values.put("engine_blob", bos.toByteArray());
        values.put("updated_at", now());
        long row = getWritableDatabase().insertWithOnConflict("session_state", null, values, SQLiteDatabase.CONFLICT_REPLACE);
        if (row < 0) throw new IllegalStateException("No se guardó la sesión");
    }

    /** El historial y el estado se confirman juntos: nunca mostrar OK si falla el guardado. */
    public void saveScanAndEngine(ScanResult result, UnloadEngine engine) throws Exception {
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            insertScanEvent(result);
            saveEngine(engine);
            database.setTransactionSuccessful();
        } finally { database.endTransaction(); }
    }

    public void saveActionAndEngine(String status, String position, String message, UnloadEngine engine) throws Exception {
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            insertSystemEvent(status, position, message);
            saveEngine(engine);
            database.setTransactionSuccessful();
        } finally { database.endTransaction(); }
    }

    public void startNewSession(UnloadEngine engine, String message) throws Exception {
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            database.delete("events", null, null);
            database.delete("session_state", null, null);
            database.execSQL("DELETE FROM sqlite_sequence WHERE name='events'");
            saveEngine(engine);
            insertSystemEvent("INICIO", "", message);
            database.setTransactionSuccessful();
        } finally { database.endTransaction(); }
    }

    public UnloadEngine loadEngine() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT engine_blob FROM session_state WHERE id=1", null)) {
            if (!c.moveToFirst()) return null;
            byte[] blob = c.getBlob(0);
            try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(blob))) {
                Object obj = in.readObject();
                return (UnloadEngine) obj;
            }
        } catch (Exception e) {
            return null;
        }
    }

    public void clearSession() {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("events", null, null);
            db.delete("session_state", null, null);
            db.execSQL("DELETE FROM sqlite_sequence WHERE name='events'");
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public void insertScanEvent(ScanResult r) {
        ContentValues v = new ContentValues();
        v.put("time", now());
        v.put("scan", r.rawScan == null ? "" : r.rawScan);
        v.put("normalized_scan", r.normalizedBarcode == null ? "" : r.normalizedBarcode);
        v.put("box_number", r.boxNumber);
        v.put("code", r.code == null ? "" : r.code);
        v.put("position", r.position == null ? "" : r.position);
        v.put("status", r.status == null ? "" : r.status);
        v.put("message", r.message == null ? "" : r.message);
        v.put("received", r.received);
        v.put("expected", r.expected);
        v.put("accepted", r.ok ? 1 : 0);
        getWritableDatabase().insertOrThrow("events", null, v);
    }

    public void insertSystemEvent(String status, String position, String message) {
        ContentValues v = new ContentValues();
        v.put("time", now());
        v.put("scan", "");
        v.put("normalized_scan", "");
        v.put("box_number", 0);
        v.put("code", "");
        v.put("position", position == null ? "" : position);
        v.put("status", status == null ? "" : status);
        v.put("message", message == null ? "" : message);
        v.put("received", 0);
        v.put("expected", 0);
        v.put("accepted", 1);
        getWritableDatabase().insertOrThrow("events", null, v);
    }

    private EventRow readEvent(Cursor c) {
        EventRow r = new EventRow();
        r.id = c.getLong(0);
        r.time = c.getString(1);
        r.scan = c.getString(2);
        r.normalizedScan = c.getString(3);
        r.boxNumber = c.getInt(4);
        r.code = c.getString(5);
        r.position = c.getString(6);
        r.status = c.getString(7);
        r.message = c.getString(8);
        r.received = c.getInt(9);
        r.expected = c.getInt(10);
        r.accepted = c.getInt(11) == 1;
        return r;
    }

    public List<EventRow> recentEvents(int limit) {
        ArrayList<EventRow> rows = new ArrayList<>();
        String sql = "SELECT id,time,scan,normalized_scan,box_number,code,position,status,message,received,expected,accepted FROM events ORDER BY id DESC LIMIT " + Math.max(1, limit);
        try (Cursor c = getReadableDatabase().rawQuery(sql, null)) {
            while (c.moveToNext()) rows.add(readEvent(c));
        }
        return rows;
    }

    public EventRow lastEvent() {
        List<EventRow> rows = recentEvents(1);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public EventRow lastAcceptedScanEvent() {
        String sql = "SELECT id,time,scan,normalized_scan,box_number,code,position,status,message,received,expected,accepted " +
                "FROM events WHERE accepted=1 AND normalized_scan IS NOT NULL AND normalized_scan<>'' ORDER BY id DESC LIMIT 1";
        try (Cursor c = getReadableDatabase().rawQuery(sql, null)) {
            return c.moveToFirst() ? readEvent(c) : null;
        }
    }

    public boolean hasStateChangeAfter(long eventId) {
        String sql = "SELECT COUNT(*) FROM events WHERE id>? AND scan='' AND status IN " +
                "('POSICIÓN LISTA','TARIMA LLENA MANUAL','TARIMA REABIERTA','POSICIÓN HABILITADA','POSICIÓN DESHABILITADA'," +
                "'DEFINITIVA FORMADA','BUFFER HABILITADO','BUFFER DESHABILITADO','TRASLADO ENVIADO'," +
                "'TRASLADO DISTRIBUIDO','TARIMA DIRECTA CERRADA','TARIMA VALIDADA','TRASLADO CAMBIADO'," +
                "'TARIMA PARCIAL CERRADA','TARIMA VERIFICADA','TARIMA RETIRADA')";
        try (Cursor c = getReadableDatabase().rawQuery(sql, new String[]{String.valueOf(eventId)})) {
            return c.moveToFirst() && c.getInt(0) > 0;
        }
    }

    private static String csv(String s) {
        if (s == null) return "";
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    public void writeEventsCsv(OutputStream output) throws Exception {
        String header = "N,FechaHora,Escaneo,BarcodeNormalizado,NumeroCaja,Codigo,Posicion,Estado,Mensaje,Recibidas,Esperadas,Aceptado\r\n";
        output.write(new byte[]{(byte)0xEF, (byte)0xBB, (byte)0xBF});
        output.write(header.getBytes(StandardCharsets.UTF_8));
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT id,time,scan,normalized_scan,box_number,code,position,status,message,received,expected,accepted FROM events ORDER BY id", null)) {
            while (c.moveToNext()) {
                String line = c.getLong(0) + "," + csv(c.getString(1)) + "," + csv(c.getString(2)) + "," +
                        csv(c.getString(3)) + "," + c.getInt(4) + "," + csv(c.getString(5)) + "," +
                        csv(c.getString(6)) + "," + csv(c.getString(7)) + "," + csv(c.getString(8)) + "," +
                        c.getInt(9) + "," + c.getInt(10) + "," + c.getInt(11) + "\r\n";
                output.write(line.getBytes(StandardCharsets.UTF_8));
            }
        }
        output.flush();
    }

    public List<EventRow> allEvents() {
        ArrayList<EventRow> rows = new ArrayList<>();
        String sql = "SELECT id,time,scan,normalized_scan,box_number,code,position,status,message,received,expected,accepted FROM events ORDER BY id";
        try (Cursor c = getReadableDatabase().rawQuery(sql, null)) {
            while (c.moveToNext()) rows.add(readEvent(c));
        }
        return rows;
    }

    private static String json(String value) {
        String text = value == null ? "" : value;
        StringBuilder out = new StringBuilder(text.length() + 16);
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) out.append(String.format(Locale.ROOT, "\\u%04x", (int)c));
                    else out.append(c);
            }
        }
        return out.append('"').toString();
    }

    private static String recordSignature(UnloadEngine engine) throws Exception {
        ArrayList<String> codes = new ArrayList<>(engine.records.keySet());
        Collections.sort(codes);
        StringBuilder canonical = new StringBuilder();
        for (String code : codes) {
            CodeRecord record = engine.records.get(code);
            canonical.append(code.trim().toUpperCase(Locale.ROOT))
                    .append(':').append(record == null ? 0 : record.boxes).append('\n');
        }
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) hex.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return hex.toString();
    }

    /**
     * Contrato v3. Conserva las cajas aceptadas vigentes y la prueba de contenido por tarima.
     * Un cambio de TR nunca equivale a una confirmación física.
     */
    public void writePdaResultJson(OutputStream output, UnloadEngine engine) throws Exception {
        LinkedHashMap<String, EventRow> latestByBarcode = new LinkedHashMap<>();
        for (EventRow event : allEvents()) {
            String barcode = safe(event.normalizedScan).trim().toUpperCase(Locale.ROOT);
            if (!event.accepted || barcode.isEmpty()) continue;
            if (!engine.scannedUniqueBarcodes.containsKey(barcode)) continue;
            latestByBarcode.put(barcode, event);
        }
        ArrayList<EventRow> accepted = new ArrayList<>(latestByBarcode.values());
        Collections.sort(accepted, (a, b) -> Long.compare(a.id, b.id));

        ArrayList<PdaResultWriter.AcceptedScan> scans = new ArrayList<>();
        for (EventRow event : accepted) scans.add(new PdaResultWriter.AcceptedScan(event.scan,
                event.normalizedScan.trim().toUpperCase(Locale.ROOT), event.code, event.boxNumber, event.time));
        PdaResultWriter.write(output, engine, scans);
    }

    public void setTemporalForCurrentPallet(String position, String temporal) {
        String t = temporal == null ? "" : temporal.trim().toUpperCase(Locale.ROOT);
        if (t.isEmpty()) throw new IllegalArgumentException("La temporal no puede estar vacía");
        insertSystemEvent("TEMPORAL ASIGNADA", position, t);
    }

    public String currentTemporalForPosition(String position) {
        if (position == null || position.trim().isEmpty()) return "";
        String sql = "SELECT message FROM events WHERE position=? AND status='TEMPORAL ASIGNADA' " +
                "AND id > COALESCE((SELECT MAX(id) FROM events WHERE position=? AND status='POSICIÓN LISTA'),0) " +
                "ORDER BY id DESC LIMIT 1";
        try (Cursor c = getReadableDatabase().rawQuery(sql, new String[]{position, position})) {
            return c.moveToFirst() ? safe(c.getString(0)) : "";
        }
    }

    public void setPutawayOrder(String order) {
        String x = order == null ? "" : order.trim().toUpperCase(Locale.ROOT);
        if (x.isEmpty()) throw new IllegalArgumentException("La orden Putaway no puede estar vacía");
        insertSystemEvent("PUTAWAY ORDER", "", x);
    }

    public String latestPutawayOrder() {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT message FROM events WHERE status='PUTAWAY ORDER' ORDER BY id DESC LIMIT 1", null)) {
            return c.moveToFirst() ? safe(c.getString(0)) : "";
        }
    }

    private static String safe(String x) { return x == null ? "" : x; }

    private static boolean isPhysicalPosition(String p) {
        if (p == null || p.length() != 3) return false;
        char a = p.charAt(0);
        return (a == 'I' || a == 'D') && Character.isDigit(p.charAt(1)) && Character.isDigit(p.charAt(2));
    }

    private static class PalletBucket {
        String key;
        String position;
        int cycle;
        int displayNo;
        String openedAt = "";
        String closedAt = "";
        String temporal = "";
        String status = "ABIERTA";
        final List<EventRow> items = new ArrayList<>();
        final Set<String> codes = new LinkedHashSet<>();
    }

    private static class ExportSnapshot {
        final List<PalletBucket> pallets = new ArrayList<>();
        final Map<String, PalletBucket> byKey = new LinkedHashMap<>();
    }

    private ExportSnapshot buildSnapshot() {
        ExportSnapshot snap = new ExportSnapshot();
        HashMap<String, Integer> cycleByPos = new HashMap<>();
        int nextDisplay = 1;
        for (EventRow e : allEvents()) {
            String pos = safe(e.position).trim().toUpperCase(Locale.ROOT);
            if (!isPhysicalPosition(pos)) continue;
            int cycle = cycleByPos.containsKey(pos) ? cycleByPos.get(pos) : 1;
            String key = pos + "#" + cycle;
            boolean acceptedBox = e.accepted && !safe(e.normalizedScan).trim().isEmpty();
            boolean palletEvent = "TEMPORAL ASIGNADA".equals(e.status)
                    || "TARIMA LLENA MANUAL".equals(e.status)
                    || "TARIMA REABIERTA".equals(e.status)
                    || "POSICIÓN LISTA".equals(e.status);
            PalletBucket b = snap.byKey.get(key);
            if ((acceptedBox || palletEvent) && b == null) {
                b = new PalletBucket();
                b.key = key;
                b.position = pos;
                b.cycle = cycle;
                b.displayNo = nextDisplay++;
                b.openedAt = e.time;
                snap.byKey.put(key, b);
                snap.pallets.add(b);
            }
            if (acceptedBox && b != null) {
                b.items.add(e);
                b.codes.add(safe(e.code));
                if ("TARIMA COMPLETA".equals(e.status)) {
                    b.status = "CERRADA";
                    b.closedAt = e.time;
                }
            }
            if (b != null && "TEMPORAL ASIGNADA".equals(e.status)) b.temporal = safe(e.message).trim().toUpperCase(Locale.ROOT);
            if (b != null && "TARIMA LLENA MANUAL".equals(e.status)) { b.status = "CERRADA"; b.closedAt = e.time; }
            if (b != null && "TARIMA REABIERTA".equals(e.status)) { b.status = "ABIERTA"; b.closedAt = ""; }
            if ("POSICIÓN LISTA".equals(e.status)) {
                if (b != null) { b.status = "RETIRADA"; if (b.closedAt.isEmpty()) b.closedAt = e.time; }
                cycleByPos.put(pos, cycle + 1);
            }
        }
        return snap;
    }

    public int missingTemporalPallets() {
        int n = 0;
        for (PalletBucket b : buildSnapshot().pallets) if (!b.items.isEmpty() && b.temporal.isEmpty()) n++;
        return n;
    }

    private static String palletLabel(PalletBucket b) { return String.format(Locale.ROOT, "Tarima %03d", b.displayNo); }

    private static String missingList(CodeRecord r, UnloadEngine engine) {
        Set<Integer> seen = engine.receivedBoxNumbers.get(r.code);
        StringBuilder s = new StringBuilder();
        int shown = 0;
        for (int i=1; i<=r.boxes; i++) {
            if (seen != null && seen.contains(i)) continue;
            if (shown > 0) s.append(" ");
            s.append("U").append(String.format(Locale.ROOT, "%03d", i));
            shown++;
            if (shown >= 60 && i < r.boxes) { s.append(" …"); break; }
        }
        return s.toString();
    }

    private void writeTransferReportXlsx(OutputStream output, UnloadEngine engine) throws Exception {
        List<SimpleXlsxWriter.Sheet> sheets = new ArrayList<>();
        int expectedBoxes = 0, completeCodes = 0, partialCodes = 0, notStartedCodes = 0;
        for (CodeRecord record : engine.records.values()) {
            expectedBoxes += record.boxes;
            int got = engine.received.containsKey(record.code) ? engine.received.get(record.code) : 0;
            if (got >= record.boxes) completeCodes++; else if (got > 0) partialCodes++; else notStartedCodes++;
        }
        int rejectedAttempts = 0;
        Set<String> uniqueIncidents = new LinkedHashSet<>();
        HashMap<String,Integer> incidence = new LinkedHashMap<>();
        for (EventRow event : allEvents()) if (!event.accepted) {
            rejectedAttempts++;
            String status = safe(event.status);
            incidence.put(status, incidence.containsKey(status) ? incidence.get(status) + 1 : 1);
            String identity = safe(event.normalizedScan).trim();
            if (identity.isEmpty()) identity = safe(event.scan).trim();
            uniqueIncidents.add(status + "|" + identity);
        }

        SimpleXlsxWriter.Sheet summary = new SimpleXlsxWriter.Sheet("Resumen");
        summary.add("Concepto", "Valor");
        summary.add("Contenedor", engine.containerId);
        summary.add("Motor", UnloadEngine.ENGINE_VERSION);
        summary.add("Cajas esperadas Packing List", expectedBoxes);
        summary.add("Cajas válidas únicas escaneadas", engine.acceptedBoxCount());
        summary.add("Cajas confirmadas en definitiva", engine.inFinalBoxCount());
        summary.add("Cajas elegibles para WMS", engine.wmsEligibleBoxCount());
        summary.add("Cajas pendientes de escanear", Math.max(0, expectedBoxes - engine.acceptedBoxCount()));
        summary.add("Códigos completos", completeCodes);
        summary.add("Códigos parciales", partialCodes);
        summary.add("Códigos no iniciados", notStartedCodes);
        summary.add("Definitivas estimadas totales", engine.plannedFinalPalletCount());
        summary.add("Tendido final inicial", engine.plannedTendidoPalletCount());
        summary.add("Definitivas iniciales al pie", engine.initialDirectFootPalletCount());
        summary.add("Traslados iniciales", 1);
        summary.add("Intentos rechazados", rejectedAttempts);
        summary.add("Incidencias únicas", uniqueIncidents.size());
        for (Map.Entry<String,Integer> x : incidence.entrySet()) summary.add("Intentos · " + x.getKey(), x.getValue());
        sheets.add(summary);

        SimpleXlsxWriter.Sheet pallets = new SimpleXlsxWriter.Sheet("Tarimas");
        pallets.add("Tarima final", "Formación", "Posición física", "Estado", "Registradas",
                "Verificadas físicamente", "Previstas", "Códigos registrados", "Verificada", "Elegibles WMS",
                "Códigos previstos", "Previsión original", "Motivo cierre parcial", "Retirada", "Verificado por", "Fecha verificación", "Método verificación");
        for (UnloadEngine.FinalPalletView pallet : engine.finalPalletViews()) {
            int eligible = 0;
            for (Map.Entry<String,String> entry : engine.finalPalletForBarcode.entrySet()) {
                if (pallet.label.equals(entry.getValue()) && engine.isBoxWmsEligible(entry.getKey())) eligible++;
            }
            UnloadEngine.PalletVerification proof = engine.verificationForPallet(pallet.label);
            pallets.add(pallet.label, pallet.direct ? "PIE" : "TENDIDO", pallet.physicalPosition,
                    pallet.status, pallet.scanned, pallet.received, pallet.expected, pallet.codeCount,
                    pallet.validated ? 1 : 0, eligible, pallet.plannedCodeCount, pallet.originalExpected,
                    pallet.closureReason, pallet.retired ? 1 : 0, proof == null ? "" : proof.responsible,
                    proof == null ? "" : proof.time, proof == null ? "" : proof.method);
        }
        sheets.add(pallets);

        LinkedHashMap<String, EventRow> acceptedByBarcode = new LinkedHashMap<>();
        for (EventRow event : allEvents()) {
            String barcode = safe(event.normalizedScan).trim().toUpperCase(Locale.ROOT);
            if (event.accepted && !barcode.isEmpty() && engine.scannedUniqueBarcodes.containsKey(barcode)) {
                acceptedByBarcode.put(barcode, event);
            }
        }
        SimpleXlsxWriter.Sheet detail = new SimpleXlsxWriter.Sheet("Detalle_tarimas");
        detail.add("FechaHora", "Barcode normalizado", "Código", "Número caja", "Tarima final",
                "Formación", "Posición física", "Tarima traslado", "Estado físico", "Tarima validada", "Elegible WMS");
        for (Map.Entry<String, EventRow> entry : acceptedByBarcode.entrySet()) {
            String barcode = entry.getKey();
            EventRow event = entry.getValue();
            String pallet = safe(engine.finalPalletForBarcode.get(barcode));
            boolean direct = engine.directFinalCodes.contains(safe(event.code).toUpperCase(Locale.ROOT));
            detail.add(event.time, barcode, event.code, event.boxNumber, pallet, direct ? "PIE" : "TENDIDO",
                    engine.physicalPositionForPallet(pallet), safe(engine.transferForBarcode.get(barcode)),
                    engine.boxPhysicalState(barcode), engine.validatedFinalPallets.contains(pallet) ? 1 : 0,
                    engine.isBoxWmsEligible(barcode) ? 1 : 0);
        }
        sheets.add(detail);

        SimpleXlsxWriter.Sheet transfers = new SimpleXlsxWriter.Sheet("Traslados");
        transfers.add("Traslado", "Estado", "Cajas registradas", "Destinos", "Cajas verificadas en definitiva");
        for (String transfer : engine.transferLabels()) {
            StringBuilder destinations = new StringBuilder();
            for (Map.Entry<String, Integer> destination : engine.transferDestinations(transfer).entrySet()) {
                if (destinations.length() > 0) destinations.append(" · ");
                destinations.append(destination.getKey()).append(" (").append(destination.getValue()).append(")");
            }
            transfers.add(transfer, engine.transferStatus(transfer), engine.transferBoxCount(transfer),
                    destinations.toString(), engine.transferVerifiedBoxCount(transfer));
        }
        sheets.add(transfers);

        SimpleXlsxWriter.Sheet compare = new SimpleXlsxWriter.Sheet("Packing_vs_Escaneo");
        compare.add("Código", "Esperadas PL", "Escaneadas únicas", "Pendientes", "Avance %", "Estado",
                "U pendientes", "CBM PL", "CBM/caja", "Peso/caja", "Bodega");
        for (CodeRecord record : engine.records.values()) {
            int got = engine.received.containsKey(record.code) ? engine.received.get(record.code) : 0;
            int missing = Math.max(0, record.boxes - got);
            String state = got >= record.boxes ? "COMPLETO" : (got > 0 ? "PARCIAL" : "NO RECIBIDO");
            double pct = record.boxes <= 0 ? 0.0 : (100.0 * got / record.boxes);
            compare.add(record.code, record.boxes, got, missing, pct, state, missingList(record, engine),
                    record.cbm, record.cbmPerBox, record.weightPerBox == null ? "" : record.weightPerBox, record.warehouse);
        }
        sheets.add(compare);

        SimpleXlsxWriter.Sheet history = new SimpleXlsxWriter.Sheet("Historial_escaneo");
        history.add("N sesión", "FechaHora", "Escaneo bruto", "BarcodeNormalizado", "NumeroCaja", "Codigo",
                "Destino", "Estado", "Mensaje", "Recibidas", "Esperadas", "Aceptado");
        int sessionNo = 0;
        for (EventRow event : allEvents()) {
            sessionNo++;
            history.add(sessionNo, event.time, event.scan, event.normalizedScan, event.boxNumber, event.code,
                    event.position, event.status, event.message, event.received, event.expected, event.accepted ? 1 : 0);
        }
        sheets.add(history);
        SimpleXlsxWriter.write(output, sheets);
    }

    public void writeReportXlsx(OutputStream output, UnloadEngine engine) throws Exception {
        if (engine.isTransferMode()) {
            writeTransferReportXlsx(output, engine);
            return;
        }
        ExportSnapshot snap = buildSnapshot();
        List<SimpleXlsxWriter.Sheet> sheets = new ArrayList<>();
        int expectedBoxes=0, scannedBoxes=0, completeCodes=0, partialCodes=0, notStartedCodes=0;
        for (CodeRecord r : engine.records.values()) {
            expectedBoxes += r.boxes;
            int got = engine.received.containsKey(r.code) ? engine.received.get(r.code) : 0;
            scannedBoxes += got;
            if (got >= r.boxes) completeCodes++; else if (got > 0) partialCodes++; else notStartedCodes++;
        }
        HashMap<String,Integer> incidence = new LinkedHashMap<>();
        for (EventRow e : allEvents()) if (!e.accepted) {
            String k=safe(e.status); incidence.put(k, incidence.containsKey(k) ? incidence.get(k)+1 : 1);
        }

        SimpleXlsxWriter.Sheet summary = new SimpleXlsxWriter.Sheet("Resumen");
        summary.add("Concepto","Valor");
        summary.add("Contenedor",engine.containerId);
        summary.add("Cajas esperadas Packing List",expectedBoxes);
        summary.add("Cajas válidas únicas escaneadas",scannedBoxes);
        summary.add("Cajas faltantes",Math.max(0,expectedBoxes-scannedBoxes));
        summary.add("Códigos Packing List",engine.records.size());
        summary.add("Códigos completos",completeCodes);
        summary.add("Códigos parciales",partialCodes);
        summary.add("Códigos no iniciados",notStartedCodes);
        summary.add("Tarimas locales detectadas",snap.pallets.size());
        summary.add("Tarimas sin temporal",missingTemporalPallets());
        summary.add("Orden Putaway WMS",latestPutawayOrder());
        for (Map.Entry<String,Integer> x: incidence.entrySet()) summary.add("Incidencia · "+x.getKey(),x.getValue());
        sheets.add(summary);

        SimpleXlsxWriter.Sheet pallets = new SimpleXlsxWriter.Sheet("Tarimas");
        pallets.add("Tarima local","Posición física","Ciclo posición","Temporal","Estado","Cajas","Códigos distintos","Inicio","Cierre");
        for (PalletBucket b:snap.pallets) if (!b.items.isEmpty() || !b.temporal.isEmpty())
            pallets.add(palletLabel(b),b.position,b.cycle,b.temporal,b.status,b.items.size(),b.codes.size(),b.openedAt,b.closedAt);
        sheets.add(pallets);

        SimpleXlsxWriter.Sheet detail = new SimpleXlsxWriter.Sheet("Detalle_tarimas");
        detail.add("Tarima local","Posición física","Temporal","FechaHora","Barcode normalizado","Código","Número caja","Estado escaneo");
        for (PalletBucket b:snap.pallets) for (EventRow e:b.items)
            detail.add(palletLabel(b),b.position,b.temporal,e.time,e.normalizedScan,e.code,e.boxNumber,e.status);
        sheets.add(detail);

        SimpleXlsxWriter.Sheet compare = new SimpleXlsxWriter.Sheet("Packing_vs_Escaneo");
        compare.add("Código","Esperadas PL","Escaneadas únicas","Faltantes","Avance %","Estado","U faltantes","CBM PL","CBM/caja","Peso/caja","Bodega");
        for (CodeRecord r:engine.records.values()) {
            int got=engine.received.containsKey(r.code)?engine.received.get(r.code):0;
            int missing=Math.max(0,r.boxes-got);
            String state=got>=r.boxes?"COMPLETO":(got>0?"PARCIAL":"NO RECIBIDO");
            double pct=r.boxes<=0?0.0:(100.0*got/r.boxes);
            compare.add(r.code,r.boxes,got,missing,pct,state,missingList(r,engine),r.cbm,r.cbmPerBox,r.weightPerBox==null?"":r.weightPerBox,r.warehouse);
        }
        sheets.add(compare);

        SimpleXlsxWriter.Sheet history = new SimpleXlsxWriter.Sheet("Historial_escaneo");
        history.add("N","FechaHora","Escaneo","BarcodeNormalizado","NumeroCaja","Codigo","Posicion","Estado","Mensaje","Recibidas","Esperadas","Aceptado");
        for (EventRow e:allEvents()) history.add(e.id,e.time,e.scan,e.normalizedScan,e.boxNumber,e.code,e.position,e.status,e.message,e.received,e.expected,e.accepted?1:0);
        sheets.add(history);

        SimpleXlsxWriter.write(output,sheets);
    }

    public void writeWmsPutawayXlsx(OutputStream output) throws Exception {
        String order=latestPutawayOrder();
        if (order.isEmpty()) throw new IllegalStateException("Falta capturar la Orden Putaway WMS");
        ExportSnapshot snap=buildSnapshot();
        int missing=0;
        for (PalletBucket b:snap.pallets) if (!b.items.isEmpty() && b.temporal.isEmpty()) missing++;
        if (missing>0) throw new IllegalStateException("Faltan temporales en "+missing+" tarima(s)");
        SimpleXlsxWriter.Sheet wms=new SimpleXlsxWriter.Sheet("Sheet1");
        wms.add("Putaway Order/上架单号","Box Type or Custom Box Barcode/箱类型号or自定义箱条码","Putaway Qty/上架数","Location/上架库位");
        for (PalletBucket b:snap.pallets) for (EventRow e:b.items) wms.add(order,e.normalizedScan,1,b.temporal);
        ArrayList<SimpleXlsxWriter.Sheet> sheets=new ArrayList<>(); sheets.add(wms);
        SimpleXlsxWriter.write(output,sheets);
    }

}
