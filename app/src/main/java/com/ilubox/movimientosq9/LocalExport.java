package com.ilubox.movimientosq9;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/** Plantillas y auditoría se generan en la PDA, de forma inmutable por operación. */
public final class LocalExport {
    private LocalExport() {}
    public static final String[] HEADERS={"Box type No./箱类型号","Customer code/客户代码","Original location code/原库位编码","Number of moves/移库数量","Target Location code/目标库位编码","Remark/备注"};
    private static final String[] DETAIL={"Código caja","Resultado final","Motivo","Lote","Origen WMS al escanear","Destino físico confirmado","Ubicación física de incidencia","Identificador interno","Código cliente","Stock total al escanear","Disponible al escanear","Bloqueado al escanear","Origen WMS al revalidar","Disponible al revalidar","Bloqueado al revalidar","Corte al escanear","Primera lectura UTC","Última lectura UTC","Intentos","Nota"};
    public static String label(String key){switch(key){case "READY":return "En plantilla XLWMS";case "BLOCKED":return "Bloqueadas al escanear";case "REVIEW":return "Requieren revisión";case "PHYSICAL_INCIDENT":return "Movidas físicamente con incidencia";case "ALREADY_TARGET":return "WMS ya muestra el destino";case "CANCELLED":return "Retiradas del lote";default:return "Pendientes de destino";}}
    private static JSONObject first(SQLiteDatabase db,String sql,String... args) throws Exception {try(Cursor c=db.rawQuery(sql,args)){return c.moveToFirst()?LocalStore.row(c):null;}}
    private static void prepare(LocalStore s,JSONObject initial,JSONObject latest) throws Exception {
        SQLiteDatabase validation=new File(s.directory,"revalidation.db").isFile()?SQLiteDatabase.openDatabase(new File(s.directory,"revalidation.db").getPath(),null,SQLiteDatabase.OPEN_READONLY):s.inventory;
        // Las consultas WAL pueden utilizar conexiones diferentes. La tabla de
        // preparación debe ser visible para todas, también en Android 6.
        s.db.beginTransaction();
        s.db.execSQL("DROP TABLE IF EXISTS export_rows");s.db.execSQL("CREATE TABLE export_rows(id TEXT PRIMARY KEY,category TEXT,export_reason TEXT,latest_origin TEXT,latest_available TEXT,latest_locked TEXT)");
        try(Cursor c=s.db.rawQuery("SELECT r.*,l.destination,l.state AS lot_state FROM records r LEFT JOIN lots l ON l.number=r.lot_number ORDER BY r.rowid",null)){
            while(c.moveToNext()){
                JSONObject r=LocalStore.row(c);String status=r.getString("status"),category=status,reason=r.optString("reason"),origin="",available="",locked="";
                if(status.equals("ACCEPTED")){
                    if(!r.optString("lot_state").equals("CLOSED")||r.optString("destination").isEmpty())throw new IOException("Hay cajas sin destino confirmado. No se generaron plantillas.");
                    JSONObject v=null;int matches;try(Cursor inv=validation.rawQuery("SELECT * FROM inventory WHERE barcode_norm=? LIMIT 3",new String[]{r.getString("barcode")})){matches=inv.getCount();if(matches==1){inv.moveToFirst();v=LocalStore.row(inv);}}
                    if(v==null){category="PHYSICAL_INCIDENT";reason=matches>1?"El corte de validación tiene varias coincidencias para esta caja":"La caja ya no aparece en el corte de validación";}
                    else {
                        origin=v.optString("origin");available=v.optString("available");locked=v.optString("locked");
                        if(!v.optString("box_type").equals(r.getString("box_type"))||!v.optString("customer_code").equals(r.getString("customer_code"))){category="PHYSICAL_INCIDENT";reason="Cambió la identidad de la caja en el corte de validación";}
                        else if(!v.optString("status").equals("ACCEPTED")){category="PHYSICAL_INCIDENT";reason="Movimiento físico pendiente de revisión: "+v.optString("reason");}
                        else if(Rules.locationKey(origin).equals(Rules.locationKey(r.getString("destination")))){category="ALREADY_TARGET";reason="El WMS del corte cargado ya muestra el destino confirmado";}
                        else if(!Rules.locationKey(origin).equals(Rules.locationKey(r.getString("origin")))){category="PHYSICAL_INCIDENT";reason="El origen del corte de validación difiere del origen al escanear";}
                        else {category="READY";reason="Movimiento validado con el corte cargado";}
                    }
                }
                if(!Arrays.asList("READY","BLOCKED","REVIEW","PHYSICAL_INCIDENT","ALREADY_TARGET","CANCELLED").contains(category))throw new IOException("Un registro tiene estado inválido. Conservamos la captura sin generar.");
                s.db.execSQL("INSERT INTO export_rows VALUES(?,?,?,?,?,?)",new Object[]{r.getString("id"),category,reason,origin,available,locked});
            }
            s.db.setTransactionSuccessful();
        }finally{s.db.endTransaction();if(validation!=s.inventory)validation.close();}
    }
    private static Xlsx.Sheet details(LocalStore s,String title,String categories,String cut){
        return new Xlsx.Sheet(title,DETAIL,sink->{try(Cursor c=s.db.rawQuery("SELECT r.*,l.destination,x.category,x.export_reason,x.latest_origin,x.latest_available,x.latest_locked FROM records r JOIN export_rows x ON x.id=r.id LEFT JOIN lots l ON l.number=r.lot_number WHERE "+categories+" ORDER BY r.rowid",null)){while(c.moveToNext()){
            JSONObject r=LocalStore.row(c);String physical=r.optString("actual_location");if(physical.isEmpty()&&r.getString("status").matches("BLOCKED|REVIEW"))physical="Sin ubicación física informada";
            sink.row(r.getString("barcode"),label(r.getString("category")),r.getString("export_reason"),r.isNull("lot_number")?"":r.getInt("lot_number"),r.getString("origin"),r.optString("destination"),physical,r.getString("box_type"),r.getString("customer_code"),r.getString("total"),r.getString("available"),r.getString("locked"),r.getString("latest_origin"),r.getString("latest_available"),r.getString("latest_locked"),cut,r.getString("first_seen"),r.getString("last_seen"),r.getInt("attempts"),r.getString("note"));
        }}});
    }
    private static File cached(LocalStore s,JSONObject profile) throws Exception {
        String path=s.setting("export_path");if(path.isEmpty())return null;File f=new File(s.directory,path);
        if(!path.matches("(?:DEMO_)?Movimientos_(?:[a-f0-9]{8}|[a-f0-9]{32})\\.zip")||!f.isFile()||!OfflineOperations.sha(f).equals(s.setting("export_sha256")))throw new IOException("Falta el paquete final o está dañado. Restaura el respaldo completo; no se generará otro paquete para la misma operación.");
        return f;
    }
    public static File generate(LocalStore s,JSONObject profile,NativeClient.Progress progress) throws Exception {
        synchronized(s){
            if(!s.sealed())throw new IOException("Finaliza la captura y confirma todos los destinos primero.");
            File existing=cached(s,profile);if(existing!=null)return existing;
            // Conserva un paquete ya recibido por la variante anterior.
            File legacy=new File(s.directory,"Movimientos_"+NativeClient.opId(profile).substring(0,8)+".zip");
            if(legacy.isFile()){try(ZipFile z=new ZipFile(legacy)){ZipEntry e=z.getEntry("Trazabilidad.json");if(e==null)throw new IOException("El paquete anterior no tiene trazabilidad.");ByteArrayOutputStream b=new ByteArrayOutputStream();try(InputStream in=z.getInputStream(e)){NativeClient.copy(in,b,128*1024);}JSONObject m=new JSONObject(b.toString("UTF-8"));if(!NativeClient.opId(profile).equals(m.getString("operation_id")))throw new IOException("El paquete anterior pertenece a otra operación.");record(s,legacy,m.getJSONObject("counts"));return legacy;}}
            if(s.stats().getInt("attempts")==0)throw new IOException("La operación no tiene lecturas.");
            JSONObject initial=new JSONObject(s.inventoryValue("snapshot"));JSONObject latest=OfflineOperations.validationSnapshot(s);
            String opid=NativeClient.opId(profile),name=(initial.optInt("demo")==1?"DEMO_":"")+"Movimientos_"+opid+".zip";File output=new File(s.directory,name);
            // Recupera una generación completada justo antes de un cierre inesperado.
            if(output.isFile()){try(ZipFile z=new ZipFile(output)){ZipEntry entry=z.getEntry("Trazabilidad.json");if(entry==null)throw new IOException("Paquete existente sin trazabilidad.");ByteArrayOutputStream b=new ByteArrayOutputStream();try(InputStream in=z.getInputStream(entry)){NativeClient.copy(in,b,128*1024);}JSONObject m=new JSONObject(b.toString("UTF-8"));if(!opid.equals(m.getString("operation_id"))||!OfflineOperations.eventHash(s).equals(m.getString("event_sha256")))throw new IOException("El paquete existente no corresponde a esta captura.");record(s,output,m.getJSONObject("counts"));return output;}}
            progress.report("Revalidando cajas y destinos…");prepare(s,initial,latest);
            JSONObject counts=new JSONObject();for(String key:new String[]{"READY","BLOCKED","REVIEW","PHYSICAL_INCIDENT","ALREADY_TARGET","CANCELLED"})counts.put(key,0);
            try(Cursor c=s.db.rawQuery("SELECT category,COUNT(*) FROM export_rows GROUP BY category",null)){while(c.moveToNext())counts.put(c.getString(0),c.getInt(1));}
            File stage=new File(s.directory,"export_build");OfflineOperations.deleteTree(stage);stage.mkdirs();
            try {
                String cut=initial.getString("cut_at"),opname=profile.getJSONObject("operation").getString("name"),stamp=LocalStore.utc();List<Xlsx.Sheet> sheets=new ArrayList<>();
                sheets.add(new Xlsx.Sheet("Resumen",new String[]{"Indicador","Valor"},sink->{sink.row("Operación",opname);sink.row("Identificador de operación",opid);sink.row("Generado UTC",stamp);sink.row("Modo",initial.optInt("demo")==1?"DEMOSTRACIÓN":"Operación real");sink.row("Archivo de inventario inicial",initial.getString("filename"));sink.row("Corte inicial · hora indicada",cut);sink.row("Corte de validación · hora indicada",latest.getString("cut_at"));sink.row("Archivo de validación",latest.getString("filename"));sink.row("SHA-256 inventario inicial",initial.optString("sha256"));sink.row("SHA-256 inventario de validación",latest.optString("sha256"));for(String key:new String[]{"READY","BLOCKED","REVIEW","PHYSICAL_INCIDENT","ALREADY_TARGET","CANCELLED"})sink.row(label(key),counts.getInt(key));sink.row("Intentos de escaneo",s.stats().getInt("attempts"));sink.row("Estado WMS","Plantillas pendientes de aplicar; la generación no confirma su importación");}));
                sheets.add(details(s,"Movimientos","x.category='READY'",cut));sheets.add(details(s,"Bloqueadas","x.category='BLOCKED'",cut));sheets.add(details(s,"Incidencias","x.category IN ('REVIEW','PHYSICAL_INCIDENT')",cut));sheets.add(details(s,"Sin_movimiento","x.category IN ('ALREADY_TARGET','CANCELLED')",cut));sheets.add(details(s,"Detalle_completo","1=1",cut));
                sheets.add(new Xlsx.Sheet("Lotes",new String[]{"Lote","Estado","Destino","Cajas","Abierto UTC","Confirmado UTC"},sink->{try(Cursor c=s.db.rawQuery("SELECT l.*,COUNT(r.id) AS cajas FROM lots l LEFT JOIN records r ON r.lot_number=l.number AND r.status='ACCEPTED' GROUP BY l.number ORDER BY l.number",null)){while(c.moveToNext()){JSONObject r=LocalStore.row(c);sink.row(r.getInt("number"),r.getString("state"),r.getString("destination"),r.getInt("cajas"),r.getString("created_at"),r.getString("closed_at"));}}}));
                sheets.add(new Xlsx.Sheet("Bitacora_lecturas",new String[]{"Fecha UTC","Lectura original","Código normalizado","Resultado","Motivo","ID de lectura","Corte inicial"},sink->{try(Cursor c=s.db.rawQuery("SELECT * FROM events WHERE kind='SCAN' ORDER BY seq",null)){while(c.moveToNext()){JSONObject r=LocalStore.row(c),p=new JSONObject(r.getString("payload"));sink.row(r.getString("occurred_at"),p.getString("code"),Rules.barcode(p.getString("code")),Rules.label(p.getString("result")),p.optString("reason","Lectura de la versión 0.2"),r.getString("event_id"),cut);}}}));
                sheets.add(new Xlsx.Sheet("Cambios",new String[]{"Fecha UTC","Acción","Detalle","Secuencia","ID de acción"},sink->{try(Cursor c=s.db.rawQuery("SELECT * FROM events ORDER BY seq",null)){while(c.moveToNext()){JSONObject r=LocalStore.row(c);sink.row(r.getString("occurred_at"),r.getString("kind"),r.getString("payload"),r.getInt("seq"),r.getString("event_id"));}}}));
                progress.report("Creando reporte de movimientos y bloqueadas…");Xlsx.write(new File(stage,"Reporte_operacion.xlsx"),sheets);
                int ready=counts.getInt("READY");for(int start=0,n=1;start<ready;start+=500,n++){
                    final int offset=start;progress.report("Creando plantilla "+n+" de "+((ready+499)/500));String filename=(initial.optInt("demo")==1?"DEMO_":"")+String.format(Locale.ROOT,"XLWMS_movimientos_%03d.xlsx",n);
                    Xlsx.write(new File(stage,filename),Collections.singletonList(new Xlsx.Sheet("Movimientos",HEADERS,sink->{try(Cursor c=s.db.rawQuery("SELECT r.box_type,r.customer_code,r.origin,l.destination FROM records r JOIN export_rows x ON x.id=r.id JOIN lots l ON l.number=r.lot_number WHERE x.category='READY' ORDER BY r.rowid LIMIT 500 OFFSET "+offset,null)){while(c.moveToNext())sink.row(c.getString(0),c.getString(1),c.getString(2),1,c.getString(3),"Movimientos Q9 · "+opid.substring(0,8));}})));
                }
                String instructions="MOVIMIENTOS Q9 INDEPENDIENTE v0.3.0\nOperación: "+opname+"\nIdentificador: "+opid+"\nCorte de validación: "+latest.getString("cut_at")+"\n\n"+(initial.optInt("demo")==1?"DEMOSTRACIÓN: no importar estas plantillas al WMS.\n\n":"")+"Carga únicamente XLWMS_movimientos_*.xlsx, una sola vez. Cada plantilla contiene como máximo 500 movimientos.\nReporte_operacion.xlsx conserva bloqueadas e incidencias, con ubicación opcional.\nLa generación no ejecuta movimientos ni actualiza el WMS. Conserva este ZIP y registra el resultado de la carga.\nSi no hay movimientos aptos, se incluye el reporte sin plantillas de movimiento.\nGuardar otra copia entrega exactamente el mismo paquete.\n";
                try(FileOutputStream f=new FileOutputStream(new File(stage,"LEER_ANTES_DE_IMPORTAR.txt"))){f.write(instructions.getBytes(StandardCharsets.UTF_8));}
                JSONObject hashes=new JSONObject();File[] files=stage.listFiles();if(files==null)throw new IOException("No se pudieron preparar los archivos.");for(File f:files)hashes.put(f.getName(),OfflineOperations.sha(f));
                JSONObject manifest=new JSONObject().put("app_version","0.3.0").put("operation_id",opid).put("name",opname).put("demo",initial.optInt("demo")==1).put("generated_at",stamp).put("inventory",initial).put("validation_inventory",latest).put("counts",counts).put("event_sha256",OfflineOperations.eventHash(s)).put("files",hashes);
                NativeClient.write(new File(stage,"Trazabilidad.json"),manifest);File temporary=new File(output+".part");OfflineOperations.zipDirectory(stage,temporary);if(!temporary.renameTo(output))throw new IOException("No se pudo guardar el paquete final. Revisa espacio disponible.");record(s,output,counts);return output;
            }finally{OfflineOperations.deleteTree(stage);s.db.execSQL("DROP TABLE IF EXISTS export_rows");}
        }
    }
    private static void record(LocalStore s,File output,JSONObject counts) throws Exception {s.db.beginTransaction();try{s.setting("export_path",output.getName());s.setting("export_sha256",OfflineOperations.sha(output));s.setting("export_counts",counts.toString());s.db.setTransactionSuccessful();}finally{s.db.endTransaction();}}
}
