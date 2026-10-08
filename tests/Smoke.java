package com.ilubox.movimientosq9.tests;

import android.app.*;
import android.content.*;
import android.content.pm.PackageInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import com.ilubox.movimientosq9.*;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/** Prueba en Android real de importación XLSX, persistencia, exportación y recuperación. */
public final class Smoke extends Instrumentation {
    private int checks;private File output;
    private static final String[] H={"Box type No./箱类型号","Customize Barcode/自定义箱条码","Customer/客户","cellNo","Total Stock/总库存","Available stock/可用库存","Locked Inventory/锁定库存"};
    private interface Test{void run() throws Exception;}
    private void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    private void rejects(Test action) throws Exception{boolean failed=false;try{action.run();}catch(Exception e){failed=true;}check(failed,"No se rechazó la acción inválida");}
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Bundle report=new Bundle();try{output=new File(getTargetContext().getFilesDir(),"qa");output.mkdirs();tests();report.putString("stream","OK · "+checks+" comprobaciones Android independiente\n");finish(Activity.RESULT_OK,report);}catch(Throwable e){StringWriter s=new StringWriter();e.printStackTrace(new PrintWriter(s));report.putString("stream",s.toString());finish(Activity.RESULT_CANCELED,report);}}
    private File assetFile(String name) throws Exception{File file=new File(output,name);try(InputStream in=getContext().getAssets().open(name);FileOutputStream out=new FileOutputStream(file)){NativeClient.copy(in,out,20*1024*1024);}return file;}
    private File start(File source,File root,String name) throws Exception{try(InputStream in=new FileInputStream(source)){return OfflineOperations.start(in,root,name,source.getName(),"2026-01-01 08:00",true,t->{});}}
    private void copy(File a,File b) throws Exception{try(InputStream in=new FileInputStream(a);FileOutputStream out=new FileOutputStream(b)){NativeClient.copy(in,out,1024L*1024*1024);}}
    private void single(File f,String origin,String locked) throws Exception{Xlsx.write(f,Collections.singletonList(new Xlsx.Sheet("Inventario",H,sink->sink.row("DEMO-C1","DEMOU001","Cliente (001234)",origin,1,locked.equals("1")?0:1,locked))));}
    private JSONObject profile(File dir) throws Exception{return NativeClient.read(new File(dir,"profile.json"));}
    private void tests() throws Exception{
        PackageInfo info=getTargetContext().getPackageManager().getPackageInfo(getTargetContext().getPackageName(),android.content.pm.PackageManager.GET_PERMISSIONS);
        check(info.versionCode==30,"Versión independiente");check(info.requestedPermissions==null||!Arrays.asList(info.requestedPermissions).contains("android.permission.INTERNET"),"Sin permiso de Internet");
        check(Rules.barcode(" thz26080730326u2 ").equals("THZ26080730326U002"),"Código normalizado");check(Rules.proposedDestination("2A?M16'A001").equals("2A_M16-A001"),"Rack Q9");check(Rules.proposedDestination("2A'TMP2136").equals("2A-TMP2136"),"Temporal Q9");
        check(InventoryImport.assess("B","001234","","1","0","1")[0].equals("BLOCKED"),"Bloqueada sin origen");check(InventoryImport.assess("B","001234","2A-TMP0001","2","2","0")[0].equals("REVIEW"),"Más de una unidad");check(InventoryImport.assess("B","001234","2A-TMP0001","1","1","")[0].equals("REVIEW"),"Bloqueo ausente");check(InventoryImport.assess("B","001234","2A-TMP0001","1","2","0")[0].equals("REVIEW"),"Cantidad inconsistente");
        File source=assetFile("inventory_shared.xlsx"),root=new File(getTargetContext().getFilesDir(),"test_operations");File dir=start(source,root,"Captura autónoma");LocalStore s=new LocalStore(dir);JSONObject p=profile(dir);
        check(new JSONObject(s.inventoryValue("snapshot")).getInt("row_count")==8,"XLSX compartido completo");
        JSONObject a=s.scan("DEMOU1");String aid=a.getJSONObject("record").getString("id");check(a.getString("result").equals("ACCEPTED")&&a.getJSONObject("record").getString("customer_code").equals("001234"),"Cliente con ceros iniciales");
        check(s.scan("DEMOU001").getString("result").equals("DUPLICATE")&&s.lotCount()==1,"Repetida no duplica lote");JSONObject b=s.scan("DEMOU004");check(b.getString("result").equals("BLOCKED")&&b.getJSONObject("record").getString("origin").isEmpty(),"Bloqueada sin ubicación");s.incident(b.getJSONObject("record").getString("id"),"","Separada / nota ñ =SUM(A1:A2)");
        check(s.scan("DEMOU005").getString("result").equals("REVIEW"),"Datos incompletos");check(s.scan("NOEXISTEU001").getString("result").equals("REVIEW"),"Caja desconocida");check(s.scan("DEMOU007").getString("result").equals("REVIEW"),"Varias coincidencias");
        rejects(s::seal);rejects(()->s.scan("2A?M16'A001"));rejects(()->LocalExport.generate(s,p,t->{}));
        int attempts=s.stats().getInt("attempts");File invalid=assetFile("inventory_missing_column.xlsx");rejects(()->start(invalid,root,"Archivo inválido"));check(s.stats().getInt("attempts")==attempts&&s.lotCount()==1,"Importación fallida conserva captura");
        File formula=assetFile("inventory_formula.xlsx");rejects(()->start(formula,root,"Excel con fórmula"));
        s.closeLot("2A_M16-A001","2A_M16-A001",1,1);rejects(()->s.cancel(aid,"Ya colocada"));check(s.scan("DEMOU001").getString("result").equals("DUPLICATE"),"Repetición después del destino");
        JSONObject second=s.scan("DEMOU002");s.cancel(second.getJSONObject("record").getString("id"),"Corrección");check(s.lotCount()==0,"Retirada");check(s.scan("DEMOU002").getJSONObject("record").getString("id").equals(second.getJSONObject("record").getString("id")),"Reescaneo conserva identidad");s.closeLot("2A-TMP2136","2A-TMP2136",2,1);
        JSONObject cancelled=s.scan("DEMOU003");s.cancel(cancelled.getJSONObject("record").getString("id"),"No movida");s.scan("DEMOU006");s.closeLot("2A_M16-A001","2A_M16-A001",3,1);s.seal();rejects(()->s.scan("DEMOU003"));s.close();
        LocalStore reopened=new LocalStore(dir);check(reopened.sealed()&&reopened.stats().getInt("ACCEPTED")==3,"Persistencia tras reapertura");
        File packet=LocalExport.generate(reopened,p,t->{});JSONObject counts=new JSONObject(reopened.setting("export_counts"));check(counts.getInt("READY")==2&&counts.getInt("BLOCKED")==1&&counts.getInt("ALREADY_TARGET")==1&&counts.getInt("CANCELLED")==1,"Conciliación de categorías");
        String packetHash=OfflineOperations.sha(packet);check(OfflineOperations.sha(LocalExport.generate(reopened,p,t->{})).equals(packetHash),"Exportación repetida entrega mismo ZIP");copy(packet,new File(output,"captura_package.zip"));android.net.Uri tree=android.net.Uri.parse("content://com.ilubox.movimientosq9.tests.documents/tree/root");OfflineOperations.exportDocuments(packet,getTargetContext().getContentResolver(),tree,t->{});String folder;try(Cursor folders=getTargetContext().getContentResolver().query(android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree,"root"),null,null,null,null)){check(folders!=null&&folders.moveToFirst(),"Carpeta de archivos sueltos creada");folder=folders.getString(folders.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID));}try(Cursor documents=getTargetContext().getContentResolver().query(android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree,folder),null,null,null,null)){check(documents!=null&&documents.getCount()==4,"Excel, reporte y trazabilidad guardados por URI Android");}
        File backup=new File(output,"respaldo_test.zip");OfflineOperations.backup(reopened,p,backup);File restored;try(InputStream in=new FileInputStream(backup)){restored=OfflineOperations.restore(in,new File(getTargetContext().getFilesDir(),"restored_operations"),t->{});}
        try(LocalStore restoredStore=new LocalStore(restored)){check(restoredStore.stats().getInt("ACCEPTED")==3&&restoredStore.sealed(),"Respaldo restaura cajas y lotes");check(OfflineOperations.sha(LocalExport.generate(restoredStore,profile(restored),t->{})).equals(packetHash),"Respaldo restaura el paquete inmutable");}
        rejects(()->{try(InputStream in=new FileInputStream(backup)){OfflineOperations.restore(in,root,t->{});}});check(reopened.stats().getInt("ACCEPTED")==3,"Restauración no sobrescribe operación existente");
        File malicious=new File(output,"bad_backup.zip");try(ZipOutputStream z=new ZipOutputStream(new FileOutputStream(malicious))){z.putNextEntry(new ZipEntry("../capture.db"));z.write(1);z.closeEntry();}rejects(()->{try(InputStream in=new FileInputStream(malicious)){OfflineOperations.restore(in,root,t->{});}});
        // Protección entre operaciones: archivo viejo y corte que ya refleja destino.
        File next=start(source,root,"Corte sin conciliar");try(LocalStore guarded=new LocalStore(next)){check(guarded.scan("DEMOU001").getString("result").equals("REVIEW"),"Segundo movimiento pendiente WMS bloqueado");}
        File reconciled=new File(output,"reconciled.xlsx");single(reconciled,"2A_M16-A001","0");File available=start(reconciled,root,"Destino ya conciliado");try(LocalStore reconciledStore=new LocalStore(available)){check(reconciledStore.scan("DEMOU001").getString("result").equals("ACCEPTED"),"Corte conciliado permite nuevo movimiento");}
        // Bloqueo posterior conserva el destino físico y queda fuera de plantilla.
        File revalRoot=new File(getTargetContext().getFilesDir(),"revalidation_operations"),initial=new File(output,"initial.xlsx");single(initial,"2A-TMP0001","0");File revalDir=start(initial,revalRoot,"Revalidación");
        try(LocalStore rv=new LocalStore(revalDir)){rv.scan("DEMOU001");rv.closeLot("2A_M16-A001","2A_M16-A001",1,1);rv.seal();File changed=new File(output,"new_block.xlsx");single(changed,"2A-TMP0001","1");try(InputStream in=new FileInputStream(changed)){OfflineOperations.revalidate(in,rv,changed.getName(),"2026-01-01 09:00",t->{});}File rp=LocalExport.generate(rv,profile(revalDir),t->{});JSONObject rc=new JSONObject(rv.setting("export_counts"));check(rc.getInt("READY")==0&&rc.getInt("PHYSICAL_INCIDENT")==1,"Nuevo bloqueo excluido de plantilla");copy(rp,new File(output,"revalidation_package.zip"));rejects(()->{try(InputStream in=new FileInputStream(changed)){OfflineOperations.revalidate(in,rv,changed.getName(),"2026-01-01 10:00",t->{});}});}
        // 501 movimientos: dos plantillas, con máximo 500 filas cada una.
        File many=new File(output,"501.xlsx");Xlsx.write(many,Collections.singletonList(new Xlsx.Sheet("Inventario",H,sink->{for(int i=1;i<=501;i++)sink.row("B"+i,"LOTE"+i+"U001","Cliente (001234)","2A-TMP0001",1,1,0);})));
        File manyDir=start(many,new File(getTargetContext().getFilesDir(),"limit_operations"),"Límite 500");try(LocalStore ms=new LocalStore(manyDir)){for(int i=1;i<=501;i++)check(ms.scan("LOTE"+i+"U001").getString("result").equals("ACCEPTED"),"Caja del lote grande");ms.closeLot("2A-TMP2136","2A-TMP2136",1,501);ms.seal();File mp=LocalExport.generate(ms,profile(manyDir),t->{});copy(mp,new File(output,"limit_package.zip"));try(ZipFile z=new ZipFile(mp)){int templates=0;Enumeration<? extends ZipEntry> entries=z.entries();while(entries.hasMoreElements())if(entries.nextElement().getName().contains("XLWMS_movimientos_"))templates++;check(templates==2,"501 movimientos en dos plantillas");}}
        reopened.close();uiTest(source);
        // Inventario grande sin mantener todas las cadenas o filas en memoria.
        long begin=SystemClock.elapsedRealtime();File large=new File(getTargetContext().getCacheDir(),"large.xlsx");Xlsx.write(large,Collections.singletonList(new Xlsx.Sheet("Inventario",H,sink->{for(int i=1;i<=311795;i++)sink.row("G"+i,"GRANDE"+i+"U001","Cliente (001234)","2A-TMP0001",1,1,0);})));
        File largeDir=start(large,new File(getTargetContext().getFilesDir(),"large_operations"),"Inventario de capacidad");try(LocalStore ls=new LocalStore(largeDir)){check(new JSONObject(ls.inventoryValue("snapshot")).getInt("row_count")==311795,"311795 registros importados");check(ls.scan("GRANDE311795U001").getString("result").equals("ACCEPTED"),"Consulta del último registro");}
        NativeClient.write(new File(output,"result.json"),new JSONObject().put("checks",checks).put("large_rows",311795).put("large_write_and_import_ms",SystemClock.elapsedRealtime()-begin).put("max_heap_bytes",Runtime.getRuntime().maxMemory()).put("standalone",true));large.delete();
    }
    private void uiTest(File source) throws Exception{
        getTargetContext().getSharedPreferences("movimientos",0).edit().remove("current_operation").commit();Intent intent=new Intent(getTargetContext(),MainActivity.class);intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);Activity a=startActivitySync(intent);waitForIdleSync();screenshot("01_setup.png");
        Button load=findButton(a.getWindow().getDecorView(),"Cargar inventario Excel");check(load!=null,"Carga directa en pantalla inicial");runOnMainSync(load::performClick);waitForIdleSync();Thread.sleep(300);screenshot("02_selector_archivos.png");sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);Thread.sleep(300);
        runOnMainSync(()->((MainActivity)a).onActivityResult(11,Activity.RESULT_OK,new Intent().setData(Uri.parse("content://com.ilubox.movimientosq9.tests.inventory/inventory_shared.xlsx"))));waitForIdleSync();Thread.sleep(300);
        EditText cut=findInput(a.getWindow().getDecorView(),"Corte WMS: aaaa-mm-dd hh:mm");
        // Los diálogos tienen su propio árbol; se usan UiAutomation y teclas sobre el campo con foco.
        android.view.accessibility.AccessibilityNodeInfo dialogRoot=getUiAutomation().getRootInActiveWindow();List<android.view.accessibility.AccessibilityNodeInfo> fields=dialogRoot.findAccessibilityNodeInfosByText("Corte WMS:");check(!fields.isEmpty(),"Campo del corte WMS");Bundle value=new Bundle();value.putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"2026-01-01 08:00");check(fields.get(0).performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,value),"Fecha escrita en formulario");
        android.view.accessibility.AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();List<android.view.accessibility.AccessibilityNodeInfo> buttons=root.findAccessibilityNodeInfosByText("Cargar");check(!buttons.isEmpty(),"Diálogo de corte");for(android.view.accessibility.AccessibilityNodeInfo b:buttons)if(b.isClickable())b.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);
        Thread.sleep(1200);waitForIdleSync();screenshot("03_inventario_cargado.png");EditText field=findInput(a.getWindow().getDecorView(),"Escanea código de caja");check(field!=null,"Importación por URI Android completada");
        runOnMainSync(()->{field.setText("DEMOU001");field.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_ENTER));field.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_ENTER));});Thread.sleep(500);waitForIdleSync();screenshot("04_aceptada.png");
        runOnMainSync(()->{field.setText("DEMOU004");field.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_ENTER));field.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_ENTER));});Thread.sleep(500);waitForIdleSync();screenshot("05_bloqueada.png");
        String op=getTargetContext().getSharedPreferences("movimientos",0).getString("current_operation","");try(LocalStore store=new LocalStore(new File(getTargetContext().getFilesDir(),"operations/"+op))){check(store.stats().getInt("ACCEPTED")==1&&store.stats().getInt("BLOCKED")==1&&store.stats().getInt("attempts")==2,"Enter registra una sola lectura");}
        Button close=findButton(a.getWindow().getDecorView(),"Cierre");runOnMainSync(close::performClick);waitForIdleSync();screenshot("06_cierre.png");check(findButton(a.getWindow().getDecorView(),"Guardar respaldo completo")!=null,"Respaldo autónomo visible");runOnMainSync(a::finish);
    }
    private EditText findInput(View v,String hint){if(v instanceof EditText&&hint.contentEquals(((EditText)v).getHint()))return (EditText)v;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){EditText f=findInput(g.getChildAt(i),hint);if(f!=null)return f;}}return null;}
    private Button findButton(View v,String text){if(v instanceof Button&&text.contentEquals(((Button)v).getText()))return (Button)v;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){Button b=findButton(g.getChildAt(i),text);if(b!=null)return b;}}return null;}
    private void screenshot(String name) throws Exception{Bitmap b=getUiAutomation().takeScreenshot();if(b==null)throw new IOException("Sin captura Android");try(FileOutputStream out=new FileOutputStream(new File(output,name))){b.compress(Bitmap.CompressFormat.PNG,100,out);}b.recycle();}
    public static final class InventoryProvider extends android.content.ContentProvider {
        public boolean onCreate(){return true;}
        public String getType(Uri u){return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";}
        public Cursor query(Uri u,String[] p,String s,String[] a,String o){android.database.MatrixCursor c=new android.database.MatrixCursor(new String[]{android.provider.OpenableColumns.DISPLAY_NAME});c.addRow(new Object[]{"inventory_shared.xlsx"});return c;}
        public Uri insert(Uri u,android.content.ContentValues v){throw new UnsupportedOperationException();}public int delete(Uri u,String s,String[] a){return 0;}public int update(Uri u,android.content.ContentValues v,String s,String[] a){return 0;}
        public ParcelFileDescriptor openFile(Uri uri,String mode) throws FileNotFoundException{try{File f=new File(getContext().getFilesDir(),"inventory_shared.xlsx");try(InputStream in=getContext().getAssets().open("inventory_shared.xlsx");FileOutputStream out=new FileOutputStream(f)){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}return ParcelFileDescriptor.open(f,ParcelFileDescriptor.MODE_READ_ONLY);}catch(Exception e){throw new FileNotFoundException(e.toString());}}
    }

    public static final class ExportProvider extends android.provider.DocumentsProvider {
        private final java.util.Map<String,File> files=new java.util.HashMap<>();
        public boolean onCreate(){File root=new File(getContext().getFilesDir(),"documents");root.mkdirs();files.put("root",root);return true;}
        private File file(String id) throws FileNotFoundException{File f=files.get(id);if(f==null)throw new FileNotFoundException(id);return f;}
        public Cursor queryRoots(String[] projection){return new android.database.MatrixCursor(new String[]{android.provider.DocumentsContract.Root.COLUMN_ROOT_ID});}
        private android.database.MatrixCursor cursor(){return new android.database.MatrixCursor(new String[]{android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE,android.provider.DocumentsContract.Document.COLUMN_FLAGS});}
        private void add(android.database.MatrixCursor c,String id,File f){c.addRow(new Object[]{id,f.getName(),f.isDirectory()?android.provider.DocumentsContract.Document.MIME_TYPE_DIR:"application/octet-stream",f.isDirectory()?android.provider.DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE:android.provider.DocumentsContract.Document.FLAG_SUPPORTS_WRITE});}
        public Cursor queryDocument(String id,String[] projection) throws FileNotFoundException{android.database.MatrixCursor c=cursor();add(c,id,file(id));return c;}
        public Cursor queryChildDocuments(String parent,String[] projection,String sort) throws FileNotFoundException{android.database.MatrixCursor c=cursor();File p=file(parent);for(java.util.Map.Entry<String,File> e:files.entrySet())if(e.getValue().getParentFile().equals(p))add(c,e.getKey(),e.getValue());return c;}
        public String createDocument(String parent,String mime,String name) throws FileNotFoundException{try{String id=java.util.UUID.randomUUID().toString();File f=new File(file(parent),name);if(mime.equals(android.provider.DocumentsContract.Document.MIME_TYPE_DIR)){if(!f.mkdir())throw new IOException("No se creó carpeta");}else if(!f.createNewFile())throw new IOException("Archivo existente");files.put(id,f);return id;}catch(IOException e){throw new FileNotFoundException(e.toString());}}
        public boolean isChildDocument(String parent,String child){try{return file(child).getAbsolutePath().startsWith(file(parent).getAbsolutePath()+"/");}catch(FileNotFoundException e){return false;}}
        public ParcelFileDescriptor openDocument(String id,String mode,CancellationSignal signal) throws FileNotFoundException{return ParcelFileDescriptor.open(file(id),mode.contains("w")?ParcelFileDescriptor.MODE_WRITE_ONLY|ParcelFileDescriptor.MODE_TRUNCATE:ParcelFileDescriptor.MODE_READ_ONLY);}
    }
}
