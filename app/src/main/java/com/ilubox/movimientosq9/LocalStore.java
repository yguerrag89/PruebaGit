package com.ilubox.movimientosq9;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;

/** Cada lectura y su acción de sincronización se confirman en la misma transacción. */
public final class LocalStore implements AutoCloseable {
    final File directory;
    final SQLiteDatabase db;
    final SQLiteDatabase inventory;

    public LocalStore(File directory) throws Exception {
        this.directory = directory;
        db = SQLiteDatabase.openOrCreateDatabase(new File(directory, "capture.db"), null);
        db.enableWriteAheadLogging();
        db.execSQL("PRAGMA synchronous=FULL");
        db.execSQL("CREATE TABLE IF NOT EXISTS settings(key TEXT PRIMARY KEY,value TEXT)");
        db.execSQL("CREATE TABLE IF NOT EXISTS lots(number INTEGER PRIMARY KEY,state TEXT,destination TEXT DEFAULT '',raw TEXT DEFAULT '',created_at TEXT,closed_at TEXT DEFAULT '')");
        db.execSQL("CREATE TABLE IF NOT EXISTS records(id TEXT PRIMARY KEY,identity_key TEXT UNIQUE,barcode TEXT,raw_scan TEXT,status TEXT,reason TEXT,box_type TEXT,customer_code TEXT,origin TEXT,total TEXT,available TEXT,locked TEXT,lot_number INTEGER,actual_location TEXT DEFAULT '',note TEXT DEFAULT '',first_seen TEXT,last_seen TEXT,attempts INTEGER DEFAULT 1)");
        db.execSQL("CREATE INDEX IF NOT EXISTS record_barcode ON records(barcode)");
        db.execSQL("CREATE TABLE IF NOT EXISTS events(seq INTEGER PRIMARY KEY,event_id TEXT UNIQUE,kind TEXT,payload TEXT,occurred_at TEXT,acked INTEGER DEFAULT 0)");
        if (scalar("SELECT COUNT(*) FROM lots", null) == 0) db.execSQL("INSERT INTO lots(number,state,created_at) VALUES(1,'OPEN',?)", new Object[]{utc()});
        inventory = SQLiteDatabase.openDatabase(new File(directory, "inventory.db").getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
        if (!"2".equals(inventoryValue("protocol"))) throw new IllegalArgumentException("Inventario incompatible con esta APK.");
    }
    public static String utc() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC")); return f.format(new Date());
    }
    static JSONObject row(Cursor c) throws Exception {
        JSONObject result = new JSONObject();
        for (int i=0; i<c.getColumnCount(); i++) {
            if (c.isNull(i)) result.put(c.getColumnName(i), JSONObject.NULL);
            else if (c.getType(i) == Cursor.FIELD_TYPE_INTEGER) result.put(c.getColumnName(i), c.getLong(i));
            else result.put(c.getColumnName(i), c.getString(i));
        }
        return result;
    }
    private JSONObject first(String sql, String... args) throws Exception {
        try(Cursor c = db.rawQuery(sql, args)) { return c.moveToFirst() ? row(c) : null; }
    }
    private int scalar(String sql, String[] args) {
        try(Cursor c=db.rawQuery(sql,args)){ return c.moveToFirst() ? c.getInt(0) : 0; }
    }
    public synchronized String inventoryValue(String key) {
        try(Cursor c=inventory.rawQuery("SELECT value FROM info WHERE key=?",new String[]{key})){ return c.moveToFirst()?c.getString(0):""; }
    }
    public synchronized String setting(String key) {
        try(Cursor c=db.rawQuery("SELECT value FROM settings WHERE key=?",new String[]{key})){ return c.moveToFirst()?c.getString(0):""; }
    }
    public synchronized void setting(String key,String value) { db.execSQL("INSERT OR REPLACE INTO settings VALUES(?,?)",new Object[]{key,value}); }
    public synchronized boolean sealed() { return "1".equals(setting("sealed")); }
    private void open() { if(sealed()) throw new IllegalStateException("La captura está finalizada. Sincroniza y genera las plantillas."); }
    private int lot() { return scalar("SELECT number FROM lots WHERE state='OPEN'",null); }
    public synchronized int pending() { return scalar("SELECT COUNT(*) FROM events WHERE acked=0", null); }
    public synchronized int lotCount() { return scalar("SELECT COUNT(*) FROM records WHERE status='ACCEPTED' AND lot_number=?",new String[]{String.valueOf(lot())}); }
    private void event(String kind,JSONObject payload,String stamp) {
        db.execSQL("INSERT INTO events(event_id,kind,payload,occurred_at) VALUES(?,?,?,?)",new Object[]{UUID.randomUUID().toString(),kind,payload.toString(),stamp});
    }
    public synchronized JSONObject scan(String raw) throws Exception {
        open(); raw=Rules.clean(raw); String code=Rules.barcode(raw);
        if(code.isEmpty() || code.length()>160 || raw.matches("(?s).*[\\x00-\\x1F].*")) throw new IllegalArgumentException("Lectura vacía o inválida.");
        if(Rules.looksLocation(raw)) throw new IllegalArgumentException("Es una ubicación. Pulsa Poner ubicación al lote.");
        JSONObject inv=null; String status="REVIEW",reason="No aparece en el inventario cargado";
        try(Cursor c=inventory.rawQuery("SELECT * FROM inventory WHERE barcode_norm=? LIMIT 3",new String[]{code})) {
            if(c.getCount()==1){c.moveToFirst();inv=row(c);status=inv.getString("status");reason=inv.getString("reason");}
            else if(c.getCount()>1) reason="Código con varias coincidencias en inventario";
        }
        String identity=inv!=null&&!inv.optString("box_type").isEmpty()&&!inv.optString("customer_code").isEmpty()?"BOX:"+inv.getString("customer_code")+":"+inv.getString("box_type"):"CODE:"+code;
        try(Cursor g=inventory.rawQuery("SELECT reason FROM guards WHERE identity_key=?",new String[]{identity})) {
            if(status.equals("ACCEPTED")&&g.moveToFirst()){status="REVIEW";reason=g.getString(0);}
        }
        String stamp=utc(); String id;
        db.beginTransaction();
        try {
            JSONObject existing=first("SELECT * FROM records WHERE identity_key=? OR barcode=? ORDER BY (status='ACCEPTED') DESC LIMIT 1",identity,code);
            id=existing==null?UUID.randomUUID().toString():existing.getString("id");
            if(existing!=null && existing.getString("status").equals("ACCEPTED")) {
                status="DUPLICATE";reason="La caja ya está registrada en esta operación";
                db.execSQL("UPDATE records SET attempts=attempts+1,last_seen=? WHERE id=?",new Object[]{stamp,id});
            } else {
                ContentValues v=new ContentValues();v.put("id",id);v.put("identity_key",identity);v.put("barcode",code);v.put("raw_scan",raw);v.put("status",status);v.put("reason",reason);v.put("last_seen",stamp);
                for(String k:new String[]{"box_type","customer_code","origin","total","available","locked"})v.put(k,inv==null?"":inv.optString(k,""));
                if(status.equals("ACCEPTED"))v.put("lot_number",lot());else v.putNull("lot_number");
                if(existing==null){v.put("first_seen",stamp);db.insertOrThrow("records",null,v);}
                else {v.put("attempts",existing.getInt("attempts")+1);db.update("records",v,"id=?",new String[]{id});}
            }
            event("SCAN",new JSONObject().put("code",raw).put("record_id",id).put("result",status),stamp);
            db.setTransactionSuccessful();
        } finally {db.endTransaction();}
        JSONObject rec=first("SELECT r.*,l.destination,l.state AS lot_state FROM records r LEFT JOIN lots l ON l.number=r.lot_number WHERE r.id=?",id);
        return new JSONObject().put("result",status).put("reason",reason).put("record",rec).put("lot_count",lotCount());
    }
    public synchronized JSONObject destination(String raw) throws Exception {
        open(); String key=Rules.locationKey(raw); String normalized=Rules.clean(raw).toUpperCase(Locale.ROOT);
        String result=""; boolean known=false; int matches=0; boolean exact=false;
        Rules.proposedDestination(raw); // Valida que no sea un código de caja.
        try(Cursor c=inventory.rawQuery("SELECT origin FROM locations WHERE origin_key=? ORDER BY origin",new String[]{key})) {
            while(c.moveToNext()){matches++;result=c.getString(0);if(result.equals(normalized)){exact=true;break;}}
        }
        if(matches>1&&!exact)throw new IllegalArgumentException("Ubicación ambigua. Escribe exactamente su código oficial WMS.");
        if(matches>0)known=true;else result=Rules.proposedDestination(raw);
        return new JSONObject().put("destination",result).put("known",known).put("box_count",lotCount()).put("lot_number",lot());
    }
    public synchronized void closeLot(String raw,String destination,int expectedLot,int expectedCount) throws Exception {
        open(); JSONObject d=destination(raw);int count=lotCount();
        if(count==0||lot()!=expectedLot||count!=expectedCount||!d.getString("destination").equals(destination))throw new IllegalStateException("El lote cambió. Revisa el destino antes de confirmar.");
        String stamp=utc(); db.beginTransaction();
        try {
            db.execSQL("UPDATE lots SET state='CLOSED',destination=?,raw=?,closed_at=? WHERE number=?",new Object[]{destination,raw,stamp,expectedLot});
            db.execSQL("INSERT INTO lots(number,state,created_at) VALUES(?,'OPEN',?)",new Object[]{expectedLot+1,stamp});
            event("LOT_CLOSE",new JSONObject().put("raw",raw).put("destination",destination).put("lot_number",expectedLot).put("box_count",count).put("confirmed",true),stamp);
            db.setTransactionSuccessful();
        } finally {db.endTransaction();}
    }
    public synchronized void incident(String id,String location,String note) throws Exception {
        open();location=Rules.clean(location);note=Rules.clean(note);
        JSONObject r=first("SELECT * FROM records WHERE id=?",id);
        if(r==null||!r.getString("status").matches("BLOCKED|REVIEW")||location.length()>100||note.length()>500)throw new IllegalArgumentException("Revisa la ubicación y la nota de esta incidencia.");
        db.beginTransaction();
        try {
            db.execSQL("UPDATE records SET actual_location=?,note=? WHERE id=?",new Object[]{location,note,id});
            event("INCIDENT",new JSONObject().put("record_id",id).put("location",location).put("note",note),utc());db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    public synchronized void cancel(String id,String note) throws Exception {
        open();note=Rules.clean(note);
        JSONObject r=first("SELECT r.*,l.state AS lot_state FROM records r LEFT JOIN lots l ON l.number=r.lot_number WHERE r.id=?",id);
        if(r==null||!r.getString("status").equals("ACCEPTED")||!r.optString("lot_state").equals("OPEN")||note.length()>500)throw new IllegalStateException("Solo puedes retirar cajas del lote sin destino.");
        db.beginTransaction();
        try{
            db.execSQL("UPDATE records SET status='CANCELLED',lot_number=NULL,reason=?,note=? WHERE id=?",new Object[]{"Retirada del lote: "+(note.isEmpty()?"Corrección de captura":note),note,id});
            event("CANCEL",new JSONObject().put("record_id",id).put("note",note),utc());db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    public synchronized void seal() throws Exception {
        if(sealed())return;
        if(lotCount()>0)throw new IllegalStateException("Hay cajas sin destino. Confirma primero la ubicación del lote.");
        db.beginTransaction();
        try {setting("sealed","1");event("SEAL",new JSONObject(),utc());db.setTransactionSuccessful();}finally{db.endTransaction();}
    }
    public synchronized JSONArray pendingEvents(int limit) throws Exception { return events(" WHERE acked=0 LIMIT "+limit); }
    private JSONArray events(String condition) throws Exception {
        JSONArray result=new JSONArray();
        String sql="SELECT * FROM events"+condition.replace(" LIMIT "," ORDER BY seq LIMIT ");
        if(!condition.contains("LIMIT"))sql+=" ORDER BY seq";
        try(Cursor c=db.rawQuery(sql,null)){while(c.moveToNext()){
            JSONObject r=row(c); result.put(new JSONObject().put("seq",r.getLong("seq")).put("event_id",r.getString("event_id")).put("kind",r.getString("kind")).put("payload",new JSONObject(r.getString("payload"))).put("occurred_at",r.getString("occurred_at")));
        }}return result;
    }
    public synchronized void acknowledge(JSONArray sent,JSONObject reply) throws Exception {
        JSONArray answers=reply.getJSONArray("answers");
        if(answers.length()!=sent.length())throw new IllegalStateException("Confirmación incompleta. Conservamos las acciones pendientes.");
        for(int i=0;i<sent.length();i++)if(answers.getJSONObject(i).getLong("seq")!=sent.getJSONObject(i).getLong("seq"))throw new IllegalStateException("La confirmación no coincide con la bitácora.");
        long through=sent.getJSONObject(sent.length()-1).getLong("seq");
        if(reply.getLong("ack_seq")<through)throw new IllegalStateException("Windows no confirmó todas las acciones.");
        db.beginTransaction();try{
            db.execSQL("UPDATE events SET acked=1 WHERE seq<=?",new Object[]{through});setting("last_sync",utc());setting("server_info",reply.toString());db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    public synchronized JSONObject stats() throws Exception {
        JSONObject stats=new JSONObject();
        for(String status:new String[]{"ACCEPTED","BLOCKED","REVIEW","CANCELLED"})stats.put(status,scalar("SELECT COUNT(*) FROM records WHERE status=?",new String[]{status}));
        return stats.put("lot_number",lot()).put("lot_count",lotCount()).put("pending",pending()).put("attempts",scalar("SELECT COUNT(*) FROM events WHERE kind='SCAN'",null)).put("sealed",sealed()).put("last_sync",setting("last_sync"));
    }
    public synchronized JSONArray records(String filter,String search,int offset) throws Exception {
        String where="1=1";java.util.ArrayList<String> args=new java.util.ArrayList<>();
        if(filter.equals("ISSUES"))where="r.status IN ('BLOCKED','REVIEW')";
        else if(filter.equals("LOT")){where="r.status='ACCEPTED' AND l.state='OPEN'";}
        else if(!filter.isEmpty()){where="r.status=?";args.add(filter);}
        if(!search.isEmpty()){where+=" AND instr(r.barcode,?)>0";args.add(Rules.barcode(search));}
        JSONArray rows=new JSONArray();
        try(Cursor c=db.rawQuery("SELECT r.*,l.destination,l.state AS lot_state FROM records r LEFT JOIN lots l ON l.number=r.lot_number WHERE "+where+" ORDER BY r.last_seen DESC,r.rowid DESC LIMIT 40 OFFSET "+Math.max(0,offset),args.toArray(new String[0]))){while(c.moveToNext())rows.put(row(c));}
        return rows;
    }
    public synchronized JSONArray lots() throws Exception {
        JSONArray result=new JSONArray();try(Cursor c=db.rawQuery("SELECT l.*,COUNT(r.id) AS box_count FROM lots l LEFT JOIN records r ON r.lot_number=l.number AND r.status='ACCEPTED' GROUP BY l.number ORDER BY l.number DESC",null)){while(c.moveToNext())result.put(row(c));}return result;
    }
    public synchronized JSONObject backup(String opid,String deviceId) throws Exception {
        return new JSONObject().put("protocol",2).put("operation_id",opid).put("device_id",deviceId).put("snapshot",new JSONObject(inventoryValue("snapshot"))).put("events",events("")).put("stats",stats()).put("exported_at",utc());
    }
    public synchronized void acknowledgeReceipt(JSONObject receipt) throws Exception {
        long through=receipt.getLong("ack_seq");
        java.security.MessageDigest hash=java.security.MessageDigest.getInstance("SHA-256");long last=0;
        try(Cursor c=db.rawQuery("SELECT * FROM events WHERE seq<=? ORDER BY seq",new String[]{String.valueOf(through)})){
            while(c.moveToNext()){
                JSONObject r=row(c);last=r.getLong("seq");
                JSONObject e=new JSONObject().put("seq",last).put("event_id",r.getString("event_id")).put("kind",r.getString("kind")).put("payload",new JSONObject(r.getString("payload"))).put("occurred_at",r.getString("occurred_at"));
                hash.update((NativeClient.canonical(e)+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        if(last!=through||!NativeClient.hex(hash.digest()).equals(receipt.getString("event_sha256")))throw new IllegalStateException("La bitácora recibida en Windows no coincide con esta PDA. Conservamos las acciones pendientes.");
        db.beginTransaction();try{db.execSQL("UPDATE events SET acked=1 WHERE seq<=?",new Object[]{through});setting("last_sync",receipt.getString("last_sync"));db.setTransactionSuccessful();}finally{db.endTransaction();}
    }
    public synchronized void close(){inventory.close();db.close();}
}
