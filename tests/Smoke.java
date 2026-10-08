package com.ilubox.movimientosq9.tests;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import org.json.JSONArray;
import org.json.JSONObject;
import com.ilubox.movimientosq9.LocalStore;
import com.ilubox.movimientosq9.NativeClient;
import com.ilubox.movimientosq9.Rules;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import android.util.Base64;

/** Se ejecuta en SQLite y Activity reales de Android, sin librerías de prueba. */
public final class Smoke extends Instrumentation {
    private int checks=0;
    private File output;
    private interface Test { void run() throws Exception; }
    private void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    private void rejects(Test test) throws Exception {boolean failed=false;try{test.run();}catch(Exception e){failed=true;}check(failed,"La acción inválida fue admitida");}
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Bundle report=new Bundle();try{output=new File(getTargetContext().getFilesDir(),"qa");output.mkdirs();runTests();report.putString("stream","OK · "+checks+" comprobaciones Android\n");finish(Activity.RESULT_OK,report);}catch(Throwable e){StringWriter b=new StringWriter();e.printStackTrace(new PrintWriter(b));report.putString("stream",b.toString());finish(Activity.RESULT_CANCELED,report);}}
    private File fixture(String name,String op) throws Exception {
        File dir=new File(getTargetContext().getFilesDir(),name);dir.mkdirs();SQLiteDatabase db=SQLiteDatabase.openOrCreateDatabase(new File(dir,"inventory.db"),null);
        db.execSQL("CREATE TABLE info(key TEXT PRIMARY KEY,value TEXT)");
        JSONObject snap=new JSONObject().put("id",1).put("row_count",6).put("filename","Demo Android").put("demo",1).put("cut_at","2026-01-01T08:00:00");
        db.execSQL("INSERT INTO info VALUES('protocol','2')");db.execSQL("INSERT INTO info VALUES('operation_id',?)",new Object[]{op});db.execSQL("INSERT INTO info VALUES('snapshot',?)",new Object[]{snap.toString()});
        db.execSQL("CREATE TABLE inventory(barcode TEXT,barcode_norm TEXT,box_type TEXT,customer_code TEXT,origin TEXT,origin_key TEXT,total TEXT,available TEXT,locked TEXT,status TEXT,reason TEXT)");
        db.execSQL("CREATE INDEX inv_code ON inventory(barcode_norm)");db.execSQL("CREATE TABLE locations(origin TEXT,origin_key TEXT)");db.execSQL("CREATE TABLE guards(identity_key TEXT PRIMARY KEY,reason TEXT)");
        for(int n=1;n<=6;n++){String code=String.format(java.util.Locale.ROOT,"DEMOU%03d",n),origin=n==6?"2A_M16-A001":"2A-TMP0001";String status=n==4?"BLOCKED":n==5?"REVIEW":"ACCEPTED",reason=n==4?"Inventario bloqueado":n==5?"Datos de inventario incompletos o inválidos":"Validada con el inventario cargado";db.execSQL("INSERT INTO inventory VALUES(?,?,?,?,?,?,?,?,?,?,?)",new Object[]{code,code,"DEMO-C"+n,"999999",origin,Rules.locationKey(origin),"1",n==4?"0":"1",n==4?"1":n==5?"":"0",status,reason});}
        db.execSQL("INSERT INTO locations VALUES('2A_M16-A001','2AM16A001')");db.execSQL("INSERT INTO locations VALUES('2A-TMP0001','2ATMP0001')");db.close();
        NativeClient.write(new File(dir,"profile.json"),new JSONObject().put("protocol",2).put("server","http://10.0.2.2:8765").put("device_id","22222222-2222-4222-8222-222222222222").put("token","aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa").put("operation",new JSONObject().put("id",op).put("name","Prueba Android Q9")).put("snapshot",snap));return dir;
    }
    private void runTests() throws Exception {
        check(Rules.barcode(" thz26080730326u2 ").equals("THZ26080730326U002"),"Normalización caja");
        check(Rules.proposedDestination("2A?M16'A001").equals("2A_M16-A001"),"Rack Q9");
        check(Rules.proposedDestination("2A'TMP2136").equals("2A-TMP2136"),"Temporal Q9");
        rejects(()->NativeClient.server("http://8.8.8.8:8765"));rejects(()->NativeClient.server("http://usuario:clave@192.168.1.1:8765"));
        String op="11111111111141118111111111111111";File dir=fixture("qa_capture",op);LocalStore s=new LocalStore(dir);
        JSONObject first=s.scan("demou1");String id=first.getJSONObject("record").getString("id");check(first.getString("result").equals("ACCEPTED")&&s.lotCount()==1,"Aceptación local");
        check(s.scan("DEMOU001").getString("result").equals("DUPLICATE")&&s.lotCount()==1,"Repetida sin duplicar lote");
        check(s.scan("DEMOU004").getString("result").equals("BLOCKED")&&s.lotCount()==1,"Bloqueada excluida");
        JSONObject blocked=s.records("ISSUES","",0).getJSONObject(0);s.incident(blocked.getString("id"),"","Separada / ubicación opcional ñ");check(s.records("ISSUES","",0).getJSONObject(0).getString("actual_location").isEmpty(),"Ubicación opcional");
        check(s.scan("DEMOU005").getString("result").equals("REVIEW"),"Bloqueo ausente sin aceptación");check(s.scan("NOEXISTEU001").getString("result").equals("REVIEW"),"Caja ausente");
        rejects(()->s.scan("2A?M16'A001"));rejects(s::seal);
        JSONObject d=s.destination("2A?M16'A001");check(d.getBoolean("known")&&d.getString("destination").equals("2A_M16-A001"),"Destino reconocido");
        s.closeLot("2A_M16-A001","2A_M16-A001",1,1);check(s.lotCount()==0&&s.lots().length()==2,"Cierre y siguiente lote");rejects(()->s.cancel(id,"Ya colocada"));
        check(s.scan("DEMOU001").getString("result").equals("DUPLICATE")&&s.lotCount()==0,"Duplicado después de colocación");
        JSONObject r=s.scan("DEMOU002");s.cancel(r.getJSONObject("record").getString("id"),"Corrección");check(s.lotCount()==0,"Retirada de lote");check(s.scan("DEMOU002").getJSONObject("record").getString("id").equals(r.getJSONObject("record").getString("id")),"Reescaneo misma identidad");s.closeLot("2A-TMP2136","2A-TMP2136",2,1);
        int pending=s.pending();JSONArray sent=s.pendingEvents(50);JSONObject wrong=new JSONObject().put("answers",new JSONArray()).put("ack_seq",999);rejects(()->s.acknowledge(sent,wrong));check(s.pending()==pending,"Confirmación incompleta conserva pendientes");
        s.seal();rejects(()->s.scan("DEMOU003"));s.close();LocalStore restarted=new LocalStore(dir);check(restarted.sealed()&&restarted.pending()==pending+1,"Persistencia tras reinicio");
        JSONObject backup=restarted.backup(op,"22222222-2222-4222-8222-222222222222");NativeClient.write(new File(output,"android_backup.json"),backup);
        String canonical=NativeClient.canonical(new JSONObject().put("z","Separada / ñ\n").put("a",true).put("n",2));check(canonical.equals("{\"a\":true,\"n\":2,\"z\":\"Separada / ñ\\n\"}"),"JSON canónico compatible con Python");
        MessageDigest hash=MessageDigest.getInstance("SHA-256");JSONArray events=backup.getJSONArray("events");for(int i=0;i<events.length();i++)hash.update((NativeClient.canonical(events.getJSONObject(i))+"\n").getBytes(StandardCharsets.UTF_8));NativeClient.write(new File(output,"android_result.json"),new JSONObject().put("checks",checks).put("event_sha256",NativeClient.hex(hash.digest())).put("stats",restarted.stats()));restarted.close();
        // SQLite y comprobante PRODUCIDOS POR WINDOWS: comprueba el contrato
        // entre plataformas, incluyendo acuse HMAC, hash y fechas originales.
        File imported=fixture("qa_windows_inventory",op);File gz=new File(imported,"from_windows.gz");try(FileOutputStream out=new FileOutputStream(gz)){out.write(Base64.decode(asset("inventory.gz.b64"),Base64.DEFAULT));}
        JSONObject importedProfile=NativeClient.read(new File(imported,"profile.json"));NativeClient.importInventory(gz,imported,importedProfile,t->{});LocalStore importedStore=new LocalStore(imported);check(importedStore.scan("DEMOU004").getString("result").equals("BLOCKED"),"SQLite de Windows legible en Android 6");importedStore.close();
        File usbDir=fixture("qa_usb_receipt",op);LocalStore usbStore=new LocalStore(usbDir);JSONArray received=new JSONArray(asset("received_events.json"));SQLiteDatabase injection=SQLiteDatabase.openDatabase(new File(usbDir,"capture.db").getAbsolutePath(),null,SQLiteDatabase.OPEN_READWRITE);
        for(int i=0;i<received.length();i++){JSONObject e=received.getJSONObject(i);injection.execSQL("INSERT INTO events(seq,event_id,kind,payload,occurred_at) VALUES(?,?,?,?,?)",new Object[]{e.getInt("seq"),e.getString("event_id"),e.getString("kind"),e.getJSONObject("payload").toString(),e.getString("occurred_at")});}injection.close();
        NativeClient usbClient=new NativeClient(usbDir,NativeClient.read(new File(usbDir,"profile.json")));JSONObject receipt=new JSONObject(asset("receipt.json"));JSONObject forged=new JSONObject(receipt.toString());forged.put("ack_seq",999);rejects(()->usbClient.receipt(usbStore,forged));check(usbStore.pending()==received.length(),"Comprobante alterado conserva pendientes");usbClient.receipt(usbStore,receipt);check(usbStore.pending()==0,"Comprobante de Windows confirma bitácora Android exactamente");usbStore.close();
        String uiOp="33333333333343338333333333333333";File uiDir=fixture("operations/"+uiOp,uiOp);getTargetContext().getSharedPreferences("movimientos",0).edit().putString("current_operation",uiOp).commit();
        Intent intent=new Intent(getTargetContext(),com.ilubox.movimientosq9.MainActivity.class);intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);Activity activity=startActivitySync(intent);waitForIdleSync();Thread.sleep(500);screenshot("01_escaneo.png");
        EditText field=findInput(activity.getWindow().getDecorView(),"Escanea código de caja");check(field!=null,"Pantalla nativa de escaneo");
        runOnMainSync(()->{field.setText("DEMOU001");field.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_ENTER));field.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_ENTER));});waitForIdleSync();Thread.sleep(500);screenshot("02_aceptada.png");
        runOnMainSync(()->{field.setText("DEMOU004");field.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_ENTER));field.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_ENTER));});waitForIdleSync();Thread.sleep(500);screenshot("03_bloqueada.png");
        LocalStore uiStore=new LocalStore(uiDir);check(uiStore.stats().getInt("ACCEPTED")==1&&uiStore.stats().getInt("BLOCKED")==1&&uiStore.pending()==2,"Enter del lector registra una sola acción");uiStore.close();runOnMainSync(activity::finish);
    }
    private EditText findInput(View v,String hint){if(v instanceof EditText&&hint.contentEquals(((EditText)v).getHint()))return (EditText)v;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){EditText found=findInput(g.getChildAt(i),hint);if(found!=null)return found;}}return null;}
    private String asset(String name) throws Exception {try(InputStream in=getContext().getAssets().open(name)){ByteArrayOutputStream b=new ByteArrayOutputStream();NativeClient.copy(in,b,256*1024);return b.toString("UTF-8");}}
    private void screenshot(String name) throws Exception {Bitmap b=getUiAutomation().takeScreenshot();if(b==null)throw new IOException("Sin captura Android");try(FileOutputStream out=new FileOutputStream(new File(output,name))){b.compress(Bitmap.CompressFormat.PNG,100,out);}b.recycle();}
}
