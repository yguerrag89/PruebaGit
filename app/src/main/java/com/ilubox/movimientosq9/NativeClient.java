package com.ilubox.movimientosq9;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.StatFs;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class NativeClient {
    public interface Progress { void report(String text); }
    public final File directory;
    public JSONObject profile;
    public NativeClient(File directory,JSONObject profile){this.directory=directory;this.profile=profile;}

    public static String server(String value) throws Exception {
        String text=Rules.clean(value);if(!text.contains("://"))text="http://"+text;
        URI u=new URI(text);String host=u.getHost();
        if(!("http".equals(u.getScheme())||"https".equals(u.getScheme()))||host==null||u.getUserInfo()!=null||u.getQuery()!=null||u.getFragment()!=null||!(u.getPath().isEmpty()||u.getPath().equals("/")))throw new IllegalArgumentException("Escribe la dirección de Windows: http://192.168.1.10:8765");
        String[] parts=host.split("\\.");if(parts.length!=4)throw new IllegalArgumentException("Usa la IPv4 privada de Wi-Fi o Ethernet de Windows.");
        int[] p=new int[4];for(int i=0;i<4;i++){if(!parts[i].matches("[0-9]{1,3}"))throw new IllegalArgumentException("Dirección IPv4 inválida.");p[i]=Integer.parseInt(parts[i]);if(p[i]>255)throw new IllegalArgumentException("Dirección IPv4 inválida.");}
        if(!(p[0]==10||(p[0]==192&&p[1]==168)||(p[0]==172&&p[1]>=16&&p[1]<=31)))throw new IllegalArgumentException("Usa una IPv4 de red privada: 10.x, 192.168.x o 172.16–31.x.");
        int port=u.getPort()<0?("https".equals(u.getScheme())?443:8765):u.getPort();if(port<1||port>65535)throw new IllegalArgumentException("Puerto inválido.");
        return u.getScheme()+"://"+p[0]+"."+p[1]+"."+p[2]+"."+p[3]+":"+port;
    }
    public static String opId(JSONObject profile) throws Exception { return profile.has("operation")?profile.getJSONObject("operation").getString("id"):profile.getString("operation_id"); }
    public static JSONObject read(File file) throws Exception {try(InputStream i=new FileInputStream(file)){return new JSONObject(new String(bytes(i,128*1024),StandardCharsets.UTF_8));}}
    public static void write(File file,JSONObject data) throws Exception {
        file.getParentFile().mkdirs();File temp=new File(file.getAbsolutePath()+".part");
        try(FileOutputStream out=new FileOutputStream(temp)){out.write(data.toString().getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
        if(!temp.renameTo(file))throw new IOException("No se pudo guardar la configuración. Revisa el espacio libre.");
    }
    public void save() throws Exception {write(new File(directory,"profile.json"),profile);}
    private HttpURLConnection connection(String path,String method,boolean auth,int timeout) throws Exception {
        URL url=new URL(server(profile.getString("server"))+path);HttpURLConnection c=(HttpURLConnection)url.openConnection();
        c.setInstanceFollowRedirects(false);c.setConnectTimeout(8000);c.setReadTimeout(timeout);c.setRequestMethod(method);
        c.setRequestProperty("Accept-Encoding","identity");c.setRequestProperty("X-Movimientos","1");c.setRequestProperty("X-Client-Mode","native");
        if(auth){c.setRequestProperty("Authorization","Bearer "+profile.getString("token"));c.setRequestProperty("X-Device-Id",profile.getString("device_id"));c.setRequestProperty("X-Operation-Id",opId(profile));}
        return c;
    }
    private static byte[] bytes(InputStream in,int maximum) throws Exception {
        ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] block=new byte[8192];int n;
        while((n=in.read(block))!=-1){if(b.size()+n>maximum)throw new IOException("La respuesta supera el tamaño esperado.");b.write(block,0,n);}return b.toByteArray();
    }
    private static void ok(HttpURLConnection c) throws Exception {
        int status=c.getResponseCode();if(status>=200&&status<300)return;
        String message="Windows respondió "+status+". Se conservan las lecturas en la PDA.";
        InputStream err=c.getErrorStream();if(err!=null){try(InputStream in=err){message=new JSONObject(new String(bytes(in,65536),StandardCharsets.UTF_8)).optString("error",message);}catch(IOException e){throw e;}catch(Exception ignored){}}
        throw new IOException(message);
    }
    private JSONObject json(String path,JSONObject body,boolean auth,int timeout) throws Exception {
        HttpURLConnection c=connection(path,body==null?"GET":"POST",auth,timeout);
        try {
            if(body!=null){byte[] data=body.toString().getBytes(StandardCharsets.UTF_8);c.setDoOutput(true);c.setFixedLengthStreamingMode(data.length);c.setRequestProperty("Content-Type","application/json");try(OutputStream out=c.getOutputStream()){out.write(data);}}
            ok(c);try(InputStream in=c.getInputStream()){return new JSONObject(new String(bytes(in,2*1024*1024),StandardCharsets.UTF_8));}
        }finally{c.disconnect();}
    }
    public JSONObject info() throws Exception {return json("/api/native/info",null,true,30000);}
    public void pair(String code,Progress progress) throws Exception {
        progress.report("Vinculando con Windows…");JSONObject result;
        if(profile.has("snapshot"))result=info();
        else {
            try {result=json("/api/native/pair",new JSONObject().put("code",code).put("device_id",profile.getString("device_id")).put("token",profile.getString("token")),false,60000);}
            catch(IOException original){try{result=info();}catch(Exception missing){throw original;}}
        }
        if(result.getInt("protocol")!=2||!result.getJSONObject("operation").getString("id").equals(opId(profile)))throw new IOException("La respuesta pertenece a otra operación.");
        profile.put("operation",result.getJSONObject("operation")).put("snapshot",result.getJSONObject("snapshot"));save();
        if(new File(directory,"inventory.db").isFile())return;
        progress.report("Preparando inventario local…");JSONObject meta=json("/api/native/inventory/meta",null,true,120000);
        long size=meta.getLong("size");if(size<=0||size>128L*1024*1024)throw new IOException("Tamaño de inventario no permitido.");
        if(new StatFs(directory.getAbsolutePath()).getAvailableBytes()<size+512L*1024*1024)throw new IOException("Libera al menos 512 MB en la PDA para cargar el inventario.");
        File gzip=new File(directory,"inventory.download.gz");HttpURLConnection c=connection("/api/native/inventory","GET",true,30000);
        try {
            ok(c);long total=0,last=0;MessageDigest hash=MessageDigest.getInstance("SHA-256");
            try(InputStream in=c.getInputStream();FileOutputStream out=new FileOutputStream(gzip)) {
                byte[] b=new byte[65536];int n;
                while((n=in.read(b))!=-1){total+=n;if(total>size)throw new IOException("Inventario de tamaño inesperado.");hash.update(b,0,n);out.write(b,0,n);
                    long at=System.currentTimeMillis();if(at-last>300){progress.report("Descargando inventario · "+total*100/size+" %");last=at;}}
                out.getFD().sync();
            }
            if(total!=size||!hex(hash.digest()).equals(meta.getString("sha256")))throw new IOException("Transferencia incompleta. Reintenta; las lecturas existentes se conservan.");
            importInventory(gzip,directory,profile,progress);profile.put("inventory_sha256",meta.getString("sha256"));save();
        }finally{c.disconnect();gzip.delete();}
    }
    public static void importInventory(File gzip,File directory,JSONObject profile,Progress progress) throws Exception {
        progress.report("Comprobando inventario…");File temp=new File(directory,"inventory.new.db");long count=0;
        try {
            try(InputStream in=new GZIPInputStream(new FileInputStream(gzip));FileOutputStream out=new FileOutputStream(temp)) {
                byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1){count+=n;if(count>512L*1024*1024)throw new IOException("El inventario supera 512 MB.");out.write(b,0,n);}out.getFD().sync();
            }
            SQLiteDatabase inv=SQLiteDatabase.openDatabase(temp.getAbsolutePath(),null,SQLiteDatabase.OPEN_READONLY);
            try {
                Map<String,String> meta=new HashMap<>();try(Cursor c=inv.rawQuery("SELECT key,value FROM info",null)){while(c.moveToNext())meta.put(c.getString(0),c.getString(1));}
                if(!"2".equals(meta.get("protocol"))||!opId(profile).equals(meta.get("operation_id"))||new JSONObject(meta.get("snapshot")).getLong("id")!=profile.getJSONObject("snapshot").getLong("id"))throw new IOException("El inventario pertenece a otra operación o corte.");
                try(Cursor c=inv.rawQuery("PRAGMA quick_check",null)){if(!c.moveToFirst()||!"ok".equals(c.getString(0)))throw new IOException("Inventario dañado. Vuelve a descargarlo.");}
                try(Cursor c=inv.rawQuery("SELECT COUNT(*) FROM inventory",null)){if(!c.moveToFirst()||c.getLong(0)!=profile.getJSONObject("snapshot").getLong("row_count"))throw new IOException("Faltan cajas en el inventario transferido.");}
            }finally{inv.close();}
            if(!temp.renameTo(new File(directory,"inventory.db")))throw new IOException("No se pudo instalar el inventario local.");
        }finally{temp.delete();}
    }
    public JSONObject sync(LocalStore store,Progress progress) throws Exception {
        JSONObject reply=null;
        for(int i=0;i<10000;i++) {
            JSONArray sent=store.pendingEvents(50);if(sent.length()==0)break;
            progress.report("Sincronizando · "+store.pending()+" acciones pendientes");
            reply=json("/api/native/sync",new JSONObject().put("operation_id",opId(profile)).put("events",sent),true,30000);
            if(reply.getInt("protocol")!=2||!reply.getJSONObject("operation").getString("id").equals(opId(profile)))throw new IOException("Respuesta de otra operación. Conservamos las acciones.");
            store.acknowledge(sent,reply);
        }
        if(reply==null)reply=info();store.setting("server_info",reply.toString());return reply;
    }
    public JSONObject generate(Progress progress) throws Exception {
        progress.report("Revalidando y generando plantillas en Windows…");
        return json("/api/native/generate",new JSONObject().put("operation_id",opId(profile)),true,120000);
    }
    public File downloadExport(JSONObject export,Progress progress) throws Exception {
        progress.report("Descargando plantillas y reporte…");File out=new File(directory,"Movimientos_"+opId(profile).substring(0,8)+".zip");File temp=new File(out.getAbsolutePath()+".part");
        HttpURLConnection c=connection("/api/native/export/"+export.getLong("id"),"GET",true,30000);
        try {ok(c);try(InputStream in=c.getInputStream();FileOutputStream f=new FileOutputStream(temp)){copy(in,f,128L*1024*1024);f.getFD().sync();}if(!temp.renameTo(out))throw new IOException("No se pudo guardar el paquete.");return out;}finally{c.disconnect();temp.delete();}
    }
    static void copy(InputStream in,OutputStream out,long maximum) throws Exception {byte[] b=new byte[65536];long total=0;int n;while((n=in.read(b))!=-1){total+=n;if(total>maximum)throw new IOException("Archivo demasiado grande.");out.write(b,0,n);}}
    public static String hex(byte[] bytes){StringBuilder b=new StringBuilder();for(byte v:bytes)b.append(String.format(Locale.ROOT,"%02x",v&255));return b.toString();}
    private static String quote(String value) {
        StringBuilder b=new StringBuilder("\"");for(int i=0;i<value.length();i++){char c=value.charAt(i);switch(c){case '"':b.append("\\\"");break;case '\\':b.append("\\\\");break;case '\b':b.append("\\b");break;case '\f':b.append("\\f");break;case '\n':b.append("\\n");break;case '\r':b.append("\\r");break;case '\t':b.append("\\t");break;default:if(c<32)b.append(String.format(Locale.ROOT,"\\u%04x",(int)c));else b.append(c);}}return b.append('"').toString();
    }
    public static String canonical(Object value) throws Exception {
        if(value==null||value==JSONObject.NULL)return "null";
        if(value instanceof JSONObject){JSONObject j=(JSONObject)value;List<String> keys=new ArrayList<>();Iterator<String> it=j.keys();while(it.hasNext())keys.add(it.next());Collections.sort(keys);StringBuilder b=new StringBuilder("{");for(String key:keys){if(b.length()>1)b.append(',');b.append(quote(key)).append(':').append(canonical(j.get(key)));}return b.append('}').toString();}
        if(value instanceof JSONArray){JSONArray j=(JSONArray)value;StringBuilder b=new StringBuilder("[");for(int i=0;i<j.length();i++){if(i>0)b.append(',');b.append(canonical(j.get(i)));}return b.append(']').toString();}
        if(value instanceof String)return quote((String)value);if(value instanceof Boolean||value instanceof Number)return value.toString();throw new IllegalArgumentException("Valor inesperado en bitácora.");
    }
    public void receipt(LocalStore store,JSONObject data) throws Exception {
        if(data.getInt("protocol")!=2||!data.getString("operation_id").equals(opId(profile))||!data.getString("device_id").equals(profile.getString("device_id"))||data.getLong("snapshot_id")!=profile.getJSONObject("snapshot").getLong("id"))throw new IOException("Comprobante de otra operación o PDA.");
        String signature=data.getString("signature");JSONObject unsigned=new JSONObject(data.toString());unsigned.remove("signature");
        byte[] key=MessageDigest.getInstance("SHA-256").digest(profile.getString("token").getBytes(StandardCharsets.UTF_8));Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));
        if(!MessageDigest.isEqual(hex(mac.doFinal(canonical(unsigned).getBytes(StandardCharsets.UTF_8))).getBytes(StandardCharsets.US_ASCII),signature.getBytes(StandardCharsets.US_ASCII)))throw new IOException("El comprobante no fue generado por la computadora vinculada.");
        store.acknowledgeReceipt(data);
    }
}
