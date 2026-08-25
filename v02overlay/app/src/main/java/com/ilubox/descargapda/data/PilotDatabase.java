package com.ilubox.descargapda.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.ilubox.descargapda.core.ScanResult;
import com.ilubox.descargapda.core.UnloadEngine;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class PilotDatabase extends SQLiteOpenHelper {
    private static final String DB_NAME = "ilubox_descarga_piloto.db";
    private static final int DB_VERSION = 2;

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
        getWritableDatabase().insertWithOnConflict("session_state", null, values, SQLiteDatabase.CONFLICT_REPLACE);
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
        getWritableDatabase().insert("events", null, v);
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
        getWritableDatabase().insert("events", null, v);
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
                "('POSICIÓN LISTA','TARIMA LLENA MANUAL','TARIMA REABIERTA','POSICIÓN HABILITADA','POSICIÓN DESHABILITADA')";
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
}
