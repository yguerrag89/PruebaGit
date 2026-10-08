package com.ilubox.movimientosq9;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
/** Utilidades locales y lectura de perfiles anteriores. No abre conexiones. */
public final class NativeClient {
    public interface Progress {void report(String text);}
    public final File directory;public JSONObject profile;
    public NativeClient(File directory,JSONObject profile){this.directory=directory;this.profile=profile;}
    public static String opId(JSONObject profile) throws Exception{return profile.has("operation")?profile.getJSONObject("operation").getString("id"):profile.getString("operation_id");}
    public static JSONObject read(File file) throws Exception{try(InputStream in=new FileInputStream(file)){ByteArrayOutputStream b=new ByteArrayOutputStream();copy(in,b,128*1024);return new JSONObject(b.toString("UTF-8"));}}
    public static void write(File file,JSONObject data) throws Exception{file.getParentFile().mkdirs();File temp=new File(file+".part");try(FileOutputStream out=new FileOutputStream(temp)){out.write(data.toString().getBytes(StandardCharsets.UTF_8));out.getFD().sync();}if(!temp.renameTo(file))throw new IOException("No se pudo guardar la configuración. Revisa espacio disponible.");}
    public void save() throws Exception{write(new File(directory,"profile.json"),profile);}
    public static void copy(InputStream in,OutputStream out,long maximum) throws Exception {byte[] b=new byte[65536];long total=0;int n;while((n=in.read(b))!=-1){total+=n;if(total>maximum)throw new IOException("Archivo demasiado grande.");out.write(b,0,n);}}
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
}
