package com.ilubox.movimientosq9;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.StatFs;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.zip.*;

/** Importación, cortes, protección entre operaciones y respaldos locales. */
public final class OfflineOperations {
    private OfflineOperations() {}
    public static String sha(File file) throws Exception {MessageDigest h=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)h.update(b,0,n);}return NativeClient.hex(h.digest());}
    public static String eventHash(LocalStore s) throws Exception {MessageDigest h=MessageDigest.getInstance("SHA-256");try(Cursor c=s.db.rawQuery("SELECT * FROM events ORDER BY seq",null)){while(c.moveToNext()){JSONObject r=LocalStore.row(c),e=new JSONObject().put("seq",r.getLong("seq")).put("event_id",r.getString("event_id")).put("kind",r.getString("kind")).put("payload",new JSONObject(r.getString("payload"))).put("occurred_at",r.getString("occurred_at"));h.update((NativeClient.canonical(e)+"\n").getBytes(StandardCharsets.UTF_8));}}return NativeClient.hex(h.digest());}
    public static void deleteTree(File f){if(f.isDirectory()){File[] children=f.listFiles();if(children!=null)for(File c:children)deleteTree(c);}f.delete();}
    public static String validCut(String raw) throws Exception {
        String value=Rules.clean(raw).replace(' ','T');if(value.length()==16)value+=":00";
        if(!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}"))throw new IOException("Indica el corte WMS como aaaa-mm-dd hh:mm, en hora local.");
        SimpleDateFormat f=new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss",Locale.ROOT);f.setLenient(false);Date d=f.parse(value);if(d==null||!f.format(d).equals(value)||d.after(new Date(System.currentTimeMillis()+60000)))throw new IOException("La fecha del corte es inválida o está en el futuro. Revisa también la hora de la PDA.");return value;
    }
    public static JSONObject snapshot(File file) throws Exception {try(SQLiteDatabase db=SQLiteDatabase.openDatabase(file.getPath(),null,SQLiteDatabase.OPEN_READONLY);Cursor c=db.rawQuery("SELECT value FROM info WHERE key='snapshot'",null)){if(!c.moveToFirst())throw new IOException("Inventario sin datos de corte.");return new JSONObject(c.getString(0));}}
    public static JSONObject validationSnapshot(LocalStore s) throws Exception {File latest=new File(s.directory,"revalidation.db");return latest.isFile()?snapshot(latest):new JSONObject(s.inventoryValue("snapshot"));}
    private static void copyInput(InputStream in,File target) throws Exception {if(in==null)throw new IOException("No se pudo abrir el archivo. Si la USB no aparece, copia el Excel a Descargas.");try(FileOutputStream out=new FileOutputStream(target)){NativeClient.copy(in,out,400L*1024*1024);out.getFD().sync();}if(new StatFs(target.getParent()).getAvailableBytes()<Math.max(256L*1024*1024,target.length()*5))throw new IOException("Falta espacio libre para procesar el inventario. Libera al menos 256 MB sin borrar los datos de la aplicación.");}
    public static File start(InputStream in,File operations,String name,String filename,String cut,boolean demo,NativeClient.Progress progress) throws Exception {
        name=Rules.clean(name);if(name.isEmpty()||name.length()>120)throw new IOException("Escribe un nombre de operación de hasta 120 caracteres.");cut=validCut(cut);operations.mkdirs();String id=UUID.randomUUID().toString().replace("-","");File stage=new File(operations,"incoming_"+id),target=new File(operations,id);stage.mkdirs();
        try {
            progress.report("Copiando el Excel seleccionado…");File raw=new File(stage,"source.xlsx");copyInput(in,raw);
            JSONObject snap=InventoryImport.load(raw,stage,id,filename,cut,demo,1,progress);raw.delete();
            progress.report("Comprobando movimientos de operaciones anteriores…");guards(operations,new File(stage,"inventory.db"),demo);
            JSONObject profile=new JSONObject().put("protocol",2).put("mode","standalone").put("app_version","0.3.0").put("operation_id",id).put("device_id",UUID.randomUUID().toString()).put("operation",new JSONObject().put("id",id).put("name",name).put("created_at",LocalStore.utc())).put("snapshot",snap);
            NativeClient.write(new File(stage,"profile.json"),profile);try(LocalStore s=new LocalStore(stage)){s.setting("standalone","1");}
            if(!stage.renameTo(target))throw new IOException("No se pudo guardar la nueva operación. Se conservó la anterior.");return target;
        }finally{deleteTree(stage);}
    }
    private static void guards(File operations,File inventory,boolean demo) throws Exception {
        File[] dirs=operations.listFiles();if(dirs==null)return;
        try(SQLiteDatabase current=SQLiteDatabase.openDatabase(inventory.getPath(),null,SQLiteDatabase.OPEN_READWRITE)){
            current.beginTransaction();try{
                Map<String,JSONObject> recent=new HashMap<>();
                for(File dir:dirs){if(!dir.getName().matches("[a-f0-9]{32}")||!new File(dir,"capture.db").isFile())continue;JSONObject profile=NativeClient.read(new File(dir,"profile.json"));if((profile.getJSONObject("snapshot").optInt("demo")==1)!=demo)continue;
                    try(SQLiteDatabase old=SQLiteDatabase.openDatabase(new File(dir,"capture.db").getPath(),null,SQLiteDatabase.OPEN_READONLY);Cursor c=old.rawQuery("SELECT r.identity_key,r.barcode,r.origin,l.destination,l.closed_at FROM records r JOIN lots l ON l.number=r.lot_number WHERE r.status='ACCEPTED' AND l.state='CLOSED' ORDER BY l.closed_at DESC",null)){
                        while(c.moveToNext()){JSONObject row=LocalStore.row(c);String key=row.getString("identity_key");JSONObject previous=recent.get(key);if(previous==null||row.getString("closed_at").compareTo(previous.getString("closed_at"))>0)recent.put(key,row);}
                    }
                }
                for(Map.Entry<String,JSONObject> entry:recent.entrySet()){JSONObject old=entry.getValue();try(Cursor now=current.rawQuery("SELECT * FROM inventory WHERE barcode_norm=? LIMIT 3",new String[]{old.getString("barcode")})){
                    if(now.getCount()!=1)continue;now.moveToFirst();JSONObject r=LocalStore.row(now);String key="BOX:"+r.optString("customer_code")+":"+r.optString("box_type");if(r.optString("status").equals("ACCEPTED")&&key.equals(entry.getKey())&&!Rules.locationKey(old.getString("origin")).equals(Rules.locationKey(old.getString("destination")))&&!r.optString("origin_key").equals(Rules.locationKey(old.getString("destination"))))current.execSQL("INSERT OR IGNORE INTO guards VALUES(?,?)",new Object[]{key,"La caja tiene otro movimiento físico pendiente de conciliar con WMS"});
                }}
                current.setTransactionSuccessful();
            }finally{current.endTransaction();}
        }
    }
    public static void revalidate(InputStream in,LocalStore s,String filename,String cut,NativeClient.Progress progress) throws Exception {
        if(!s.sealed()||!s.setting("export_path").isEmpty())throw new IOException("El corte de validación se carga después de finalizar la captura y antes de generar.");
        JSONObject initial=new JSONObject(s.inventoryValue("snapshot"));cut=validCut(cut);if(cut.compareTo(initial.getString("cut_at"))<0)throw new IOException("El corte de validación no puede ser anterior al de captura.");
        File stage=new File(s.directory,"validation_"+UUID.randomUUID());stage.mkdirs();try{
            File raw=new File(stage,"source.xlsx");copyInput(in,raw);InventoryImport.load(raw,stage,s.inventoryValue("operation_id"),filename,cut,initial.optInt("demo")==1,2,progress);raw.delete();
            File target=new File(s.directory,"revalidation.db");if(!new File(stage,"inventory.db").renameTo(target))throw new IOException("No se pudo guardar el corte nuevo. Se conserva la validación anterior.");
        }finally{deleteTree(stage);}
    }
    public static void zipDirectory(File source,File target) throws Exception {
        File[] files=source.listFiles();if(files==null)throw new IOException("No hay archivos para guardar.");Arrays.sort(files,(a,b)->a.getName().compareTo(b.getName()));
        try(FileOutputStream f=new FileOutputStream(target);ZipOutputStream z=new ZipOutputStream(f)){for(File file:files){if(!file.isFile())continue;z.putNextEntry(new ZipEntry(file.getName()));try(InputStream in=new FileInputStream(file)){NativeClient.copy(in,z,1024L*1024*1024);}z.closeEntry();}z.finish();z.flush();f.getFD().sync();}
    }
    public static File backup(LocalStore s,JSONObject profile,File output) throws Exception {
        synchronized(s){
            File stage=new File(output.getParent(),"backup_"+UUID.randomUUID());stage.mkdirs();try{
                s.db.disableWriteAheadLogging();
                try {
                    String packet=s.setting("export_path");List<String> files=new ArrayList<>(Arrays.asList("inventory.db","capture.db"));if(new File(s.directory,"revalidation.db").isFile())files.add("revalidation.db");if(!packet.isEmpty()){if(!packet.matches("(?:DEMO_)?Movimientos_(?:[a-f0-9]{8}|[a-f0-9]{32})\\.zip"))throw new IOException("Paquete guardado inválido.");files.add(packet);}
                    for(String name:files){try(InputStream in=new FileInputStream(new File(s.directory,name));FileOutputStream out=new FileOutputStream(new File(stage,name))){NativeClient.copy(in,out,1024L*1024*1024);out.getFD().sync();}}
                    JSONObject safe=new JSONObject(profile.toString());for(String key:new String[]{"token","server","pair_code"})safe.remove(key);safe.put("mode","standalone");NativeClient.write(new File(stage,"profile.json"),safe);
                }finally{s.db.enableWriteAheadLogging();}
                JSONObject hashes=new JSONObject();File[] children=stage.listFiles();if(children==null)throw new IOException("No se pudo preparar el respaldo.");for(File f:children)hashes.put(f.getName(),sha(f));
                NativeClient.write(new File(stage,"respaldo.json"),new JSONObject().put("backup_protocol",3).put("operation_id",NativeClient.opId(profile)).put("created_at",LocalStore.utc()).put("files",hashes));
                File temporary=new File(output+".part");zipDirectory(stage,temporary);if(!temporary.renameTo(output))throw new IOException("No se pudo guardar el respaldo completo.");return output;
            }finally{deleteTree(stage);}
        }
    }
    public static File restore(InputStream in,File operations,NativeClient.Progress progress) throws Exception {
        operations.mkdirs();File stage=new File(operations,"restore_"+UUID.randomUUID());stage.mkdirs();
        try {
            Set<String> names=new HashSet<>();long total=0;progress.report("Comprobando respaldo completo…");
            try(ZipInputStream z=new ZipInputStream(in)){ZipEntry e;while((e=z.getNextEntry())!=null){String name=e.getName();if(e.isDirectory()||names.size()>=8||!names.add(name)||!(Arrays.asList("inventory.db","capture.db","profile.json","revalidation.db","respaldo.json").contains(name)||name.matches("(?:DEMO_)?Movimientos_(?:[a-f0-9]{8}|[a-f0-9]{32})\\.zip")))throw new IOException("El ZIP no es un respaldo completo válido de Movimientos Q9.");File f=new File(stage,name);try(FileOutputStream out=new FileOutputStream(f)){NativeClient.copy(z,out,1024L*1024*1024);out.getFD().sync();}total+=f.length();if(total>1500000000L)throw new IOException("El respaldo es demasiado grande.");z.closeEntry();}}
            JSONObject manifest=NativeClient.read(new File(stage,"respaldo.json")),profile=NativeClient.read(new File(stage,"profile.json"));String op=NativeClient.opId(profile);
            if(manifest.getInt("backup_protocol")!=3||!op.matches("[a-f0-9]{32}")||!op.equals(manifest.getString("operation_id")))throw new IOException("El respaldo no corresponde a esta versión.");
            JSONObject hashes=manifest.getJSONObject("files");if(hashes.length()!=names.size()-1)throw new IOException("El respaldo contiene archivos faltantes o adicionales.");Iterator<String> keys=hashes.keys();while(keys.hasNext()){String name=keys.next();if(!names.contains(name)||!sha(new File(stage,name)).equals(hashes.getString(name)))throw new IOException("Respaldo dañado: "+name);}
            if(!names.containsAll(Arrays.asList("inventory.db","capture.db","profile.json")))throw new IOException("El respaldo está incompleto.");
            if(!snapshot(new File(stage,"inventory.db")).optString("sha256").equals(profile.getJSONObject("snapshot").optString("sha256")))throw new IOException("El perfil no coincide con su inventario.");
            try(LocalStore check=new LocalStore(stage)){if(!check.inventoryValue("operation_id").equals(op))throw new IOException("El inventario pertenece a otra operación.");check.stats();String packet=check.setting("export_path");if(!packet.isEmpty()&&(!names.contains(packet)||!sha(new File(stage,packet)).equals(check.setting("export_sha256"))))throw new IOException("El respaldo no contiene su paquete final íntegro.");}
            File target=new File(operations,op);if(target.exists())throw new IOException("Esta operación ya existe en la PDA. Ábrela desde Operaciones guardadas; no se sobrescribieron sus lecturas.");
            new File(stage,"respaldo.json").delete();if(!stage.renameTo(target))throw new IOException("No se pudo restaurar la operación. Se conservaron los datos anteriores.");return target;
        }finally{deleteTree(stage);}
    }
}
