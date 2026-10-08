package com.ilubox.movimientosq9;

import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import org.json.JSONObject;
import java.io.*;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.*;

/** El inventario se construye aparte: un archivo inválido nunca sustituye el corte activo. */
public final class InventoryImport {
    private InventoryImport() {}
    private static final String[] FIELDS={"box_type","barcode","customer","origin","total","available","locked"};
    private static final String[][] ALIASES={
        {"Box type No./箱类型号","Box type No.","箱类型号"},
        {"Customize Barcode/自定义箱条码","Customize Barcode","barcode","自定义箱条码"},
        {"Customer/客户","Customer","客户"},
        {"cellNo","Location","Ubicación","ubicacion","库位"},
        {"Total Stock/总库存","Total Stock","总库存"},
        {"Available stock/可用库存","Available stock","可用库存"},
        {"Locked Inventory/锁定库存","Locked Inventory","锁定库存"}
    };
    public static String header(String s){return Normalizer.normalize(Rules.clean(s),Normalizer.Form.NFKD).toLowerCase(Locale.ROOT).replaceAll("\\p{M}","").replaceAll("[^a-z0-9\\u4e00-\\u9fff]","");}
    public static String customer(String s){s=Rules.clean(s);if(s.contains("|"))return "";Matcher m=Pattern.compile("\\(([0-9]+)\\)").matcher(s);return m.find()?m.group(1):s.matches("[0-9]+")?s:"";}
    private static BigDecimal numeric(String s){try{BigDecimal n=new BigDecimal(Rules.clean(s));return n.signum()<0?null:n;}catch(Exception e){return null;}}
    public static String[] assess(String box,String client,String origin,String total,String available,String locked){
        BigDecimal t=numeric(total),a=numeric(available),b=numeric(locked),one=BigDecimal.ONE;
        if(b!=null&&b.signum()>0)return new String[]{"BLOCKED","Inventario bloqueado"};
        if(t==null||a==null||b==null)return new String[]{"REVIEW","Datos de inventario incompletos o inválidos"};
        if(a.compareTo(one)<0||t.compareTo(one)<0)return new String[]{"REVIEW","Sin disponibilidad para mover"};
        if(a.compareTo(t)>0||b.compareTo(t)>0)return new String[]{"REVIEW","Cantidades de inventario inconsistentes"};
        if(t.compareTo(one)!=0||a.compareTo(one)!=0)return new String[]{"REVIEW","El registro representa más de una unidad; requiere revisión"};
        if(box.isEmpty())return new String[]{"REVIEW","Falta identificador interno de caja"};
        if(client.isEmpty())return new String[]{"REVIEW","Falta código de cliente"};
        if(origin.isEmpty()||Rules.locationKey(origin).isEmpty())return new String[]{"REVIEW","Falta ubicación origen en WMS"};
        return new String[]{"ACCEPTED","Validada con el inventario cargado"};
    }
    public static JSONObject load(File xlsx,File directory,String opid,String filename,String cut,boolean demo,int snapshotId,NativeClient.Progress progress) throws Exception {
        directory.mkdirs();File file=new File(directory,"inventory.db"),strings=new File(directory,"strings.db");
        if(file.exists())throw new IOException("La carpeta de carga ya contiene inventario.");
        SQLiteDatabase db=SQLiteDatabase.openOrCreateDatabase(file,null);final int[] count={0};final Map<String,String> mapping=new LinkedHashMap<>();final boolean[] headers={false};
        try{
            db.execSQL("PRAGMA journal_mode=OFF");db.execSQL("CREATE TABLE info(key TEXT PRIMARY KEY,value TEXT)");db.execSQL("CREATE TABLE inventory(barcode TEXT,barcode_norm TEXT,box_type TEXT,customer_code TEXT,origin TEXT,origin_key TEXT,total TEXT,available TEXT,locked TEXT,status TEXT,reason TEXT)");db.execSQL("CREATE TABLE locations(origin TEXT,origin_key TEXT)");db.execSQL("CREATE TABLE guards(identity_key TEXT PRIMARY KEY,reason TEXT)");
            db.beginTransaction();
            try(SQLiteStatement insert=db.compileStatement("INSERT INTO inventory VALUES(?,?,?,?,?,?,?,?,?,?,?)")){
                Xlsx.read(xlsx,strings,new Xlsx.Rows(){
                    public boolean wants(String column){return !headers[0]||mapping.containsValue(column);}
                    public void row(int number,Map<String,String> cells) throws Exception {
                        if(!headers[0]){
                            boolean empty=true;for(String v:cells.values())if(!Rules.clean(v).isEmpty())empty=false;if(empty)return;
                            for(int n=0;n<FIELDS.length;n++){String match=null;for(Map.Entry<String,String> c:cells.entrySet())for(String alias:ALIASES[n])if(header(c.getValue()).equals(header(alias))){if(match!=null&&!match.equals(c.getKey()))throw new IOException("Columnas ambiguas: "+ALIASES[n][0]);match=c.getKey();}
                                if(match==null)throw new IOException("Falta la columna obligatoria "+ALIASES[n][0]+". Usa BoxInventory del WMS.");mapping.put(FIELDS[n],match);}
                            headers[0]=true;return;
                        }
                        String[] v=new String[FIELDS.length];boolean empty=true;for(int n=0;n<v.length;n++){v[n]=Rules.clean(cells.get(mapping.get(FIELDS[n])));if(!v[n].isEmpty())empty=false;}
                        if(empty)return;
                        if(v[1].isEmpty())throw new IOException("Fila "+number+": falta el código de caja. Se conservó la operación anterior.");
                        if(v[1].length()>160||v[0].length()>200||v[3].length()>100)throw new IOException("Fila "+number+": un identificador excede el tamaño permitido.");
                        String code=Rules.barcode(v[1]),client=customer(v[2]);String[] state=assess(v[0],client,v[3],v[4],v[5],v[6]);
                        String[] values={v[1],code,v[0],client,v[3],Rules.locationKey(v[3]),v[4],v[5],v[6],state[0],state[1]};
                        for(int n=0;n<values.length;n++)insert.bindString(n+1,values[n]);insert.executeInsert();count[0]++;if(count[0]%1000==0)progress.report("Cargando cajas: "+count[0]);
                    }
                },progress);
                if(!headers[0]||count[0]==0)throw new IOException("El inventario no contiene cajas.");
                db.setTransactionSuccessful();
            }finally{db.endTransaction();}
            progress.report("Preparando consulta rápida de "+count[0]+" registros…");
            db.execSQL("CREATE INDEX inv_code ON inventory(barcode_norm)");db.execSQL("INSERT INTO locations SELECT DISTINCT origin,origin_key FROM inventory WHERE origin<>''");db.execSQL("CREATE INDEX loc_key ON locations(origin_key)");
            JSONObject snapshot=new JSONObject().put("id",snapshotId).put("filename",filename).put("cut_at",cut).put("loaded_at",LocalStore.utc()).put("demo",demo?1:0).put("row_count",count[0]).put("sha256",OfflineOperations.sha(xlsx)).put("time_basis","Hora indicada por el operador");
            db.execSQL("INSERT INTO info VALUES('protocol','2')");db.execSQL("INSERT INTO info VALUES('operation_id',?)",new Object[]{opid});db.execSQL("INSERT INTO info VALUES('snapshot',?)",new Object[]{snapshot.toString()});
            progress.report("Inventario comprobado: "+count[0]+" registros");return snapshot;
        }finally{db.close();strings.delete();}
    }
}
