package com.ilubox.movimientosq9;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Interfaz nativa para la pantalla de 4 pulgadas de la Autoid Q9. */
public final class MainActivity extends Activity {
    private static final int BLUE=0xff143b50,TEAL=0xff127f86,RED=0xffae2435,GREEN=0xff147a4b,AMBER=0xff946119;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService captures=Executors.newSingleThreadExecutor();
    private final ExecutorService files=Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    private LocalStore local;
    private NativeClient client;
    private LinearLayout root,body;
    private TextView syncText,countsText,lotText,resultTitle,resultBody;
    private EditText scanInput;
    private Button scanButton,lotButton;
    private boolean captureBusy,fileBusy,destroyed,switching;
    private String page="scan",lastStatus="Inventario local",recordFilter="",recordSearch="";
    private int recordOffset=0;
    private JSONObject lastResult;
    private File saveFile;
    private String openKind="";
    private interface Task { void run() throws Exception; }

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs=getSharedPreferences("movimientos",MODE_PRIVATE);
        try {
            String op=prefs.getString("current_operation","");
            if(op.matches("[a-f0-9]{32}"))loadOperation(new File(getFilesDir(),"operations/"+op));
            render();
            if(saved!=null){openKind=saved.getString("open_kind","");String path=saved.getString("save_path","");if(!path.isEmpty()){File candidate=new File(path);if(candidate.isFile()&&(candidate.getCanonicalPath().startsWith(getFilesDir().getCanonicalPath()+"/")||candidate.getCanonicalPath().startsWith(getCacheDir().getCanonicalPath()+"/")))saveFile=candidate;}}
        }catch(Exception e){error(e);setupScreen("");}
    }
    @Override public void onResume(){super.onResume();focusScan();}
    @Override public void onDestroy(){destroyed=true;captures.shutdown();files.shutdown();super.onDestroy();}
    @Override public void onBackPressed(){if(page.equals("scan")&&local!=null){new AlertDialog.Builder(this).setMessage("Las lecturas están guardadas. ¿Salir de Movimientos Q9?").setPositiveButton("Salir",(d,w)->finish()).setNegativeButton("Continuar",(d,w)->focusScan()).show();}else{page="scan";render();}}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private TextView text(String value,int size,int color){TextView v=new TextView(this);v.setText(value);v.setTextSize(size);v.setTextColor(color);v.setPadding(0,dp(4),0,dp(4));return v;}
    private GradientDrawable background(int color){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(9));return d;}
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private void add(View view){body.addView(view,new LinearLayout.LayoutParams(-1,-2));}
    private Button button(String name,Task task){Button b=new Button(this);b.setText(name);b.setAllCaps(false);b.setTextSize(15);b.setMinHeight(dp(46));b.setOnClickListener(v->{try{if(fileBusy){toast("Espera a que termine la acción actual.");return;}task.run();}catch(Exception e){error(e);}});return b;}
    private EditText input(String hint,boolean scanner){EditText e=new EditText(this);e.setSingleLine(true);e.setHint(hint);e.setTextSize(19);e.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);e.setImeOptions(EditorInfo.IME_ACTION_DONE);if(scanner)e.setShowSoftInputOnFocus(false);return e;}
    private void enter(EditText field,Task task){
        field.setOnKeyListener((v,key,event)->{if(key!=KeyEvent.KEYCODE_ENTER)return false;if(event.getAction()==KeyEvent.ACTION_DOWN&&event.getRepeatCount()==0){try{task.run();}catch(Exception e){error(e);}}return true;});
        field.setOnEditorActionListener((v,id,event)->{if(event!=null)return false;if(id==EditorInfo.IME_ACTION_DONE||id==EditorInfo.IME_ACTION_GO){try{task.run();}catch(Exception e){error(e);}return true;}return false;});
    }
    private void frame(String title) {
        scanInput=null;resultTitle=null;countsText=null;lotText=null;lotButton=null;scanButton=null;
        root=column();root.setBackgroundColor(0xfff3f6f8);
        LinearLayout bar=new LinearLayout(this);bar.setGravity(Gravity.CENTER_VERTICAL);bar.setPadding(dp(14),dp(4),dp(8),dp(4));bar.setBackgroundColor(BLUE);
        boolean demo=local!=null&&client!=null&&client.profile.optJSONObject("snapshot")!=null&&client.profile.optJSONObject("snapshot").optInt("demo")==1;
        TextView brand=text(demo?"DEMO · Movimientos Q9":title,19,Color.WHITE);brand.setTypeface(null,Typeface.BOLD);brand.setMaxLines(1);brand.setEllipsize(android.text.TextUtils.TruncateAt.END);bar.addView(brand,new LinearLayout.LayoutParams(0,dp(44),1));
        Button menu=button("⋮",this::menu);menu.setTextSize(24);bar.addView(menu,new LinearLayout.LayoutParams(dp(48),dp(44)));root.addView(bar);
        syncText=text(lastStatus,12,BLUE);syncText.setPadding(dp(14),dp(5),dp(14),dp(5));root.addView(syncText);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(false);body=column();body.setPadding(dp(14),0,dp(14),dp(12));scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        if(local!=null){LinearLayout nav=new LinearLayout(this);String[] pages={"scan","records","end"},labels={"Escanear","Cajas / lotes","Cierre"};for(int i=0;i<pages.length;i++){final String p=pages[i];Button b=button(labels[i],()->{page=p;recordOffset=0;render();});b.setTextSize(13);nav.addView(b,new LinearLayout.LayoutParams(0,dp(52),1));}root.addView(nav);}
        setContentView(root);
    }
    private void render(){
        if(destroyed)return;
        try {
            if(local==null){setupScreen("");return;}
            frame("Movimientos Q9");JSONObject op=client.profile.getJSONObject("operation"),snapshot=client.profile.getJSONObject("snapshot");
            TextView name=text(op.getString("name"),16,BLUE);name.setTypeface(null,Typeface.BOLD);add(name);
            if(snapshot.optInt("demo")==1){TextView demo=text("DEMOSTRACIÓN · No cargar las plantillas al WMS",14,RED);add(demo);}
            add(text("Corte: "+snapshot.getString("cut_at").replace('T',' ')+" · hora indicada",12,AMBER));
            if(page.equals("scan"))scanScreen();else if(page.equals("records"))recordsScreen();else endScreen();
            updateCounts();focusScan();
        }catch(Exception e){error(e);}
    }

    @Override public void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);state.putString("open_kind",openKind);if(saveFile!=null)state.putString("save_path",saveFile.getPath());}
    private void setupScreen(String unused){
        page="setup";frame("Movimientos Q9 independiente");add(text("Inventario y plantillas en la PDA",22,BLUE));
        add(text("Selecciona el Excel BoxInventory desde la memoria de la PDA o una USB. Cada caja y lote queda guardado aquí.",16,BLUE));
        add(button("Cargar inventario Excel",()->{canSwitch("");pick("INVENTORY");}));
        add(button("Iniciar demostración",this::demo));add(button("Operaciones guardadas",this::history));add(button("Restaurar respaldo completo",()->{canSwitch("");pick("RESTORE");}));
        add(text("Movimientos Q9 0.3.0 · Android 6 o posterior · lector teclado + Enter",12,AMBER));
        add(text("El inventario es una copia del corte WMS cargado. Las plantillas se generan aquí y quedan pendientes de cargar al WMS.",14,BLUE));
    }
    private boolean canSwitch(String op) throws Exception {
        if(captureBusy||fileBusy)throw new IllegalStateException("Espera a que termine la acción actual.");
        if(local==null||NativeClient.opId(client.profile).equals(op)||local.stats().getInt("attempts")==0)return true;
        if(!local.sealed()||local.setting("export_path").isEmpty())throw new IllegalStateException("Finaliza la captura y genera su paquete antes de cambiar de operación. Las lecturas actuales se conservan.");return true;
    }
    private void loadOperation(File directory) throws Exception {
        JSONObject profile=NativeClient.read(new File(directory,"profile.json"));LocalStore next=new LocalStore(directory);
        client=new NativeClient(directory,profile);local=next;
        lastStatus="Inventario local · guardado automático";
    }
    private void activate(File directory) throws Exception {
        JSONObject profile=NativeClient.read(new File(directory,"profile.json"));LocalStore next=new LocalStore(directory);if(local!=null)local.close();client=new NativeClient(directory,profile);local=next;
        prefs.edit().putString("current_operation",NativeClient.opId(profile)).commit();lastResult=null;page="scan";
    }
    private void demo() throws Exception {
        canSwitch("");fileTask("Preparando demostración",()->{
            File sample=new File(getCacheDir(),"Inventario_DEMO_Q9.xlsx");
            String[] h={"Box type No./箱类型号","Customize Barcode/自定义箱条码","Customer/客户","cellNo","Total Stock/总库存","Available stock/可用库存","Locked Inventory/锁定库存"};
            Xlsx.write(sample,java.util.Collections.singletonList(new Xlsx.Sheet("Inventario",h,sink->{for(int i=1;i<=6;i++)sink.row("DEMO-C"+i,String.format(java.util.Locale.ROOT,"DEMOU%03d",i),"DEMOSTRACION (999999)",i==6?"2A_M16-A001":"2A-TMP0001",1,i==4?0:1,i==4?"1":i==5?"":"0");})));
            String cut=new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss",java.util.Locale.ROOT).format(new java.util.Date());
            try(InputStream in=new FileInputStream(sample)){activate(OfflineOperations.start(in,new File(getFilesDir(),"operations"),"Demostración Q9",sample.getName(),cut,true,this::progress));}sample.delete();
        },()->{lastStatus="Demostración lista · guardado automático";render();new AlertDialog.Builder(this).setTitle("Códigos de prueba").setMessage("DEMOU001: aceptada\nDEMOU004: bloqueada\nDEMOU005: revisar\n\nRack: 2A?M16'A001\nTemporal: 2A'TMP2136\n\nNo cargar las plantillas demo al WMS.").setPositiveButton("Escanear",(d,w)->focusScan()).show();});
    }
    private void scanScreen() throws Exception {
        countsText=text("",13,BLUE);add(countsText);lotText=text("",17,BLUE);lotText.setTypeface(null,Typeface.BOLD);add(lotText);
        scanInput=input("Escanea código de caja",true);scanInput.setTextSize(23);scanInput.setMinHeight(dp(55));scanInput.setEnabled(!local.sealed());add(scanInput);enter(scanInput,this::scan);
        scanButton=button("Validar caja",this::scan);scanButton.setEnabled(!local.sealed());add(scanButton);
        LinearLayout box=column();box.setPadding(dp(12),dp(10),dp(12),dp(10));box.setBackground(background(BLUE));
        resultTitle=text(local.sealed()?"CAPTURA FINALIZADA":"LISTO PARA ESCANEAR",21,Color.WHITE);resultTitle.setTypeface(null,Typeface.BOLD);resultBody=text(local.sealed()?"Genera y guarda las plantillas.":"Espera el resultado antes de mover la caja.",15,Color.WHITE);box.addView(resultTitle);box.addView(resultBody);add(box);
        if(lastResult!=null)showResult(lastResult);
        lotButton=button("Poner ubicación a todo el lote",this::destinationDialog);lotButton.setEnabled(local.lotCount()>0&&!local.sealed());add(lotButton);
        add(button("Ubicación / nota de la última incidencia",()->{if(lastResult==null||!lastResult.getJSONObject("record").getString("status").matches("BLOCKED|REVIEW"))throw new IllegalStateException("Escanea una caja bloqueada o abre su ficha en Cajas / lotes.");recordDialog(lastResult.getJSONObject("record"));}));
    }
    private void scan() throws Exception {
        if(captureBusy||switching||scanInput==null||local.sealed())return;
        String raw=scanInput.getText().toString();if(Rules.clean(raw).isEmpty())return;
        captureBusy=true;scanInput.setEnabled(false);scanButton.setEnabled(false);resultTitle.setText("GUARDANDO LECTURA…");resultBody.setText("Espera antes de mover la caja.");
        captures.execute(()->{
            try {JSONObject r=local.scan(raw);ui.post(()->{captureBusy=false;if(destroyed)return;lastResult=r;if(scanInput!=null){scanInput.setText("");scanInput.setEnabled(true);scanButton.setEnabled(true);showResult(r);updateCounts();focusScan();}tone(r.optString("result"));});}
            catch(Exception e){ui.post(()->{captureBusy=false;if(scanInput!=null){scanInput.setEnabled(true);scanButton.setEnabled(true);resultTitle.setText("LECTURA NO GUARDADA");resultBody.setText("No muevas la caja. "+message(e));((View)resultTitle.getParent()).setBackground(background(RED));focusScan();}tone("ERROR");});}
        });
    }
    private void showResult(JSONObject result){
        if(resultTitle==null)return;String status=result.optString("result");JSONObject r=result.optJSONObject("record");
        resultTitle.setText(Rules.label(status));int color=status.equals("ACCEPTED")?GREEN:status.equals("BLOCKED")?RED:AMBER;((View)resultTitle.getParent()).setBackground(background(color));
        String detail=r==null?"":r.optString("barcode")+"\n";
        detail+=status.equals("ACCEPTED")?"Guardada en el lote. Puedes moverla.":status.equals("BLOCKED")?"Sepárala. No se puede mover en WMS.":status.equals("DUPLICATE")?"Ya está registrada. Evita moverla otra vez.":result.optString("reason")+". Sepárala para revisión.";
        if(r!=null&&!r.optString("origin").isEmpty())detail+="\nOrigen WMS: "+r.optString("origin");resultBody.setText(detail);
    }
    private void updateCounts(){try{if(local==null)return;JSONObject s=local.stats();if(countsText!=null)countsText.setText("Aceptadas: "+s.getInt("ACCEPTED")+" · Bloqueadas: "+s.getInt("BLOCKED")+" · Revisar: "+s.getInt("REVIEW"));if(lotText!=null)lotText.setText("Lote "+s.getInt("lot_number")+" · "+s.getInt("lot_count")+(s.getInt("lot_count")==1?" caja sin destino":" cajas sin destino"));if(lotButton!=null)lotButton.setEnabled(s.getInt("lot_count")>0&&!local.sealed());if(syncText!=null&&!fileBusy)syncText.setText(local.setting("export_path").isEmpty()?"Guardado automático · inventario local":"Paquete final guardado · pendiente de cargar al WMS");}catch(Exception e){error(e);}}
    private void focusScan(){if(scanInput!=null&&!captureBusy&&!local.sealed())scanInput.post(()->{if(scanInput!=null)scanInput.requestFocus();});}
    private void destinationDialog() throws Exception {
        if(captureBusy)throw new IllegalStateException("Espera a que se guarde la lectura.");if(local.lotCount()==0)throw new IllegalStateException("El lote no tiene cajas aceptadas.");
        LinearLayout content=column();content.setPadding(dp(20),dp(8),dp(20),dp(8));EditText dest=input("Escanea ubicación de rack o temporal",true);content.addView(dest);TextView help=text(local.lotCount()+" cajas aceptadas. Indica dónde quedó físicamente el lote.",15,BLUE);content.addView(help);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Ubicación del lote").setView(content).setPositiveButton("Revisar destino",null).setNegativeButton("Volver",(d,w)->focusScan()).create();
        Task preview=()->{JSONObject d=local.destination(dest.getText().toString());dialog.dismiss();confirmDestination(d);};
        dialog.setOnShowListener(v->{dialog.getButton(-1).setOnClickListener(b->{try{preview.run();}catch(Exception e){error(e);}});enter(dest,preview);dest.requestFocus();});dialog.show();
    }
    private void confirmDestination(JSONObject preview) throws Exception {
        LinearLayout content=column();content.setPadding(dp(20),0,dp(20),0);String proposed=preview.getString("destination");
        EditText official=input("Código oficial WMS del destino",false);official.setText(proposed);content.addView(official);
        content.addView(text(preview.getBoolean("known")?"Ubicación reconocida en el inventario.":"Ubicación sin coincidencia: comprueba que este sea su código oficial del WMS.",14,AMBER));
        CheckBox confirm=new CheckBox(this);confirm.setText("Estas "+preview.getInt("box_count")+" cajas quedaron físicamente en esta ubicación");content.addView(confirm);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Confirmar destino de todo el lote").setView(content).setPositiveButton("Guardar ubicación",null).setNegativeButton("Volver",(d,w)->focusScan()).create();
        dialog.setOnShowListener(v->dialog.getButton(-1).setOnClickListener(b->{try{if(!confirm.isChecked())throw new IllegalArgumentException("Confirma primero dónde quedó físicamente el lote.");String raw=official.getText().toString();JSONObject d=local.destination(raw);if(!d.getString("destination").equals(Rules.clean(raw).toUpperCase(java.util.Locale.ROOT)))throw new IllegalArgumentException("Revisa el formato oficial: "+d.getString("destination"));local.closeLot(raw,d.getString("destination"),preview.getInt("lot_number"),preview.getInt("box_count"));lastResult=null;dialog.dismiss();render();toast("Ubicación guardada. Lote nuevo listo.");}catch(Exception e){error(e);}}));dialog.show();
    }
    private void recordsScreen() throws Exception {
        add(button("Ver lotes y destinos",this::lotsDialog));
        LinearLayout filters=new LinearLayout(this);String[] labels={"Todas","Lote","Incidencias"},values={"","LOT","ISSUES"};for(int i=0;i<values.length;i++){final String f=values[i];Button b=button(labels[i],()->{recordFilter=f;recordOffset=0;render();});b.setTextSize(12);filters.addView(b,new LinearLayout.LayoutParams(0,-2,1));}add(filters);
        EditText search=input("Buscar código de caja",false);search.setText(recordSearch);add(search);Task run=()->{recordSearch=search.getText().toString();recordOffset=0;render();};enter(search,run);add(button("Buscar",run));
        JSONArray rows=local.records(recordFilter,recordSearch,recordOffset);
        if(rows.length()==0)add(text("Sin cajas para este filtro.",16,BLUE));
        for(int i=0;i<rows.length();i++){JSONObject r=rows.getJSONObject(i);String label=r.getString("barcode")+"\n"+Rules.label(r.getString("status"));if(!r.optString("destination").isEmpty())label+=" · "+r.getString("destination");add(button(label,()->recordDialog(r)));}
        if(recordOffset>0)add(button("Página anterior",()->{recordOffset=Math.max(0,recordOffset-40);render();}));if(rows.length()==40)add(button("Página siguiente",()->{recordOffset+=40;render();}));
    }
    private void lotsDialog() throws Exception {
        JSONArray lots=local.lots();StringBuilder detail=new StringBuilder();for(int i=0;i<lots.length();i++){JSONObject l=lots.getJSONObject(i);detail.append("Lote ").append(l.getInt("number")).append(" · ").append(l.getInt("box_count")).append(" cajas\n").append(l.getString("state").equals("CLOSED")?l.getString("destination"):"Destino pendiente").append("\n\n");}
        new AlertDialog.Builder(this).setTitle("Lotes guardados").setMessage(detail.toString()).setPositiveButton("Cerrar",null).show();
    }
    private void recordDialog(JSONObject r) throws Exception {
        LinearLayout content=column();content.setPadding(dp(20),0,dp(20),0);
        content.addView(text(Rules.label(r.getString("status"))+"\n"+r.optString("reason")+"\nOrigen WMS: "+(r.optString("origin").isEmpty()?"No disponible":r.getString("origin")),15,BLUE));
        if(!r.optString("destination").isEmpty())content.addView(text("Destino físico: "+r.getString("destination"),15,GREEN));
        boolean issue=r.getString("status").matches("BLOCKED|REVIEW");EditText location=input("Ubicación física · opcional",false),note=input("Nota · opcional",false);location.setText(r.optString("actual_location"));note.setText(r.optString("note"));
        if(issue){content.addView(location);content.addView(note);content.addView(text("Puedes guardar sin ubicación.",13,AMBER));}
        else if(r.getString("status").equals("ACCEPTED")&&r.optString("lot_state").equals("OPEN"))content.addView(note);
        String action=issue?"Guardar incidencia":"Retirar del lote";boolean editable=!local.sealed()&&(issue||(r.getString("status").equals("ACCEPTED")&&r.optString("lot_state").equals("OPEN")));
        AlertDialog.Builder builder=new AlertDialog.Builder(this).setTitle(r.getString("barcode")).setView(content).setNegativeButton("Cerrar",(d,w)->focusScan());if(editable)builder.setPositiveButton(action,null);AlertDialog dialog=builder.create();
        if(editable)dialog.setOnShowListener(v->dialog.getButton(-1).setOnClickListener(b->{try{if(issue)local.incident(r.getString("id"),location.getText().toString(),note.getText().toString());else local.cancel(r.getString("id"),note.getText().toString());dialog.dismiss();lastResult=null;render();toast("Cambio guardado en la PDA.");}catch(Exception e){error(e);}}));dialog.show();
    }

    private void endScreen() throws Exception {
        JSONObject s=local.stats();add(text(s.getInt("ACCEPTED")+" cajas aceptadas\n"+s.getInt("BLOCKED")+" bloqueadas\n"+s.getInt("REVIEW")+" para revisión",18,BLUE));
        if(!local.sealed())add(button("Finalizar captura",this::sealDialog));
        else {
            add(text("Captura finalizada. Todas las cajas aceptadas tienen destino físico.",15,GREEN));
            if(local.setting("export_path").isEmpty()){
                add(button("Revalidar con otro BoxInventory · opcional",()->pick("REVALIDATE")));
                JSONObject validation=OfflineOperations.validationSnapshot(local);add(text("Corte para generar: "+validation.getString("cut_at").replace('T',' '),14,AMBER));
                add(button("Generar plantillas y reporte",this::generate));
            }else {JSONObject counts=new JSONObject(local.setting("export_counts"));add(text(counts.optInt("READY")+" movimientos en plantilla\n"+counts.optInt("PHYSICAL_INCIDENT")+" incidencias físicas",16,BLUE));add(button("Guardar ZIP de plantillas",this::generate));}
        }
        add(text("Se genera un ZIP con las plantillas XLWMS y el reporte. Puedes guardarlo en Descargas o en la USB que muestre Android. Generar no aplica los movimientos al WMS.",14,BLUE));
        add(button("Guardar respaldo completo",this::backup));
    }
    private void sealDialog() throws Exception {
        if(local.lotCount()>0)throw new IllegalStateException("Confirma primero la ubicación de las cajas del lote.");
        new AlertDialog.Builder(this).setTitle("Finalizar captura").setMessage("Se conservarán cajas, lotes e incidencias. Después podrás revalidar con un corte nuevo, generar y guardar las plantillas. La captura de esta operación quedará cerrada.").setPositiveButton("Finalizar",(d,w)->{try{if(captureBusy||fileBusy)throw new IllegalStateException("Espera a que termine la acción actual.");local.seal();render();toast("Captura finalizada. Puedes generar las plantillas aquí.");}catch(Exception e){error(e);}}).setNegativeButton("Continuar capturando",null).show();
    }
    private void generate() throws Exception {
        if(!local.sealed())throw new IllegalStateException("Finaliza la captura primero.");fileTask("Generando plantillas en la PDA",()->{saveFile=LocalExport.generate(local,client.profile,this::progress);},()->{render();new AlertDialog.Builder(this).setTitle("Plantillas y reporte guardados").setMessage("Las bloqueadas e incidencias están en el reporte y quedan fuera de las plantillas. Guardar otra copia entrega el mismo paquete. Pendiente de cargar al WMS.").setPositiveButton("Guardar ZIP",(d,w)->{try{save(saveFile,"application/zip");}catch(Exception e){error(e);}}).setNegativeButton("Cerrar",null).show();});
    }
    private void backup() throws Exception {
        if(local==null)throw new IOException("Abre una operación primero.");File file=new File(getCacheDir(),"Respaldo_Q9_"+NativeClient.opId(client.profile)+".zip");
        fileTask("Preparando respaldo completo",()->{OfflineOperations.backup(local,client.profile,file);},()->save(file,"application/zip"));
    }
    private void save(File file,String mime) throws Exception {saveFile=file;Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType(mime);i.putExtra(Intent.EXTRA_TITLE,file.getName());startActivityForResult(i,10);}
    private void pick(String kind) throws Exception {if(captureBusy||fileBusy)throw new IllegalStateException("Espera a que termine la acción actual.");openKind=kind;Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("*/*");startActivityForResult(i,11);}
    private String filename(Uri uri){try(android.database.Cursor c=getContentResolver().query(uri,new String[]{android.provider.OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst())return c.getString(0);}catch(Exception ignored){}return uri.getLastPathSegment()==null?"BoxInventory.xlsx":uri.getLastPathSegment();}
    @Override public void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null||data.getData()==null){focusScan();return;}Uri uri=data.getData();
        if(request==10&&saveFile!=null){File file=saveFile;fileTask("Guardando archivo seleccionado",()->{try(InputStream in=new FileInputStream(file);OutputStream out=getContentResolver().openOutputStream(uri)){if(out==null)throw new IOException("No se puede escribir en esa ubicación.");NativeClient.copy(in,out,1024L*1024*1024);out.flush();if(out instanceof FileOutputStream)((FileOutputStream)out).getFD().sync();}},()->toast("Archivo guardado: "+file.getName()));}
        else if(request==11){String kind=openKind;if(kind.equals("RESTORE")){fileTask("Restaurando respaldo completo",()->{try(InputStream in=getContentResolver().openInputStream(uri)){activate(OfflineOperations.restore(in,new File(getFilesDir(),"operations"),this::progress));}},()->{render();toast("Operación restaurada. Sus lecturas se conservaron.");});}else importDialog(uri,kind.equals("REVALIDATE"));}
    }
    private void importDialog(Uri uri,boolean validation){
        LinearLayout content=column();content.setPadding(dp(18),dp(8),dp(18),dp(8));content.addView(text(filename(uri),14,BLUE));EditText name=input("Nombre de operación",false),cut=input("Corte WMS: aaaa-mm-dd hh:mm",false);
        if(!validation){name.setText("Movimientos "+new java.text.SimpleDateFormat("yyyy-MM-dd",java.util.Locale.ROOT).format(new java.util.Date()));content.addView(name);}content.addView(cut);content.addView(text("Indica la fecha y hora local del inventario exportado del WMS. El archivo debe ser BoxInventory .xlsx.",14,AMBER));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(validation?"Corte nuevo para revalidar":"Cargar inventario y abrir operación").setView(content).setPositiveButton("Cargar",null).setNegativeButton("Cancelar",null).create();
        dialog.setOnShowListener(v->dialog.getButton(-1).setOnClickListener(b->{try{String date=OfflineOperations.validCut(cut.getText().toString()),opname=name.getText().toString();if(!validation)canSwitch("");dialog.dismiss();fileTask("Importando BoxInventory",()->{try(InputStream in=getContentResolver().openInputStream(uri)){if(validation)OfflineOperations.revalidate(in,local,filename(uri),date,this::progress);else activate(OfflineOperations.start(in,new File(getFilesDir(),"operations"),opname,filename(uri),date,false,this::progress));}},()->{lastStatus="Inventario listo · guardado automático";render();toast(validation?"Corte de validación guardado.":"Inventario listo. Puedes escanear.");});}catch(Exception e){error(e);}}));dialog.show();
    }
    private void fileTask(String title,Task work,Task done){
        if(fileBusy||captureBusy){toast("Espera a que termine la acción actual.");return;}fileBusy=true;switching=true;progress(title);
        files.execute(()->{try{work.run();ui.post(()->{fileBusy=false;switching=false;if(destroyed)return;try{done.run();updateCounts();}catch(Exception e){error(e);}});}catch(Exception e){ui.post(()->{fileBusy=false;switching=false;lastStatus="Acción no completada · lecturas conservadas";if(!destroyed){updateCounts();error(e);}});}});
    }
    private void progress(String value){ui.post(()->{lastStatus=value;if(syncText!=null&&!destroyed)syncText.setText(value);});}
    private String message(Exception e){return e.getMessage()==null?"No se pudo completar la acción. Tus lecturas guardadas se conservan.":e.getMessage();}
    private void error(Exception e){if(destroyed)return;new AlertDialog.Builder(this).setTitle("Revisar").setMessage(message(e)).setPositiveButton("Cerrar",(d,w)->focusScan()).show();}
    private void toast(String value){if(!destroyed)Toast.makeText(this,value,Toast.LENGTH_LONG).show();}
    private void tone(String status){try{ToneGenerator t=new ToneGenerator(AudioManager.STREAM_NOTIFICATION,90);t.startTone(status.equals("ACCEPTED")?ToneGenerator.TONE_PROP_ACK:ToneGenerator.TONE_PROP_NACK,status.equals("ACCEPTED")?120:350);ui.postDelayed(t::release,500);if(!status.equals("ACCEPTED")){Vibrator v=(Vibrator)getSystemService(VIBRATOR_SERVICE);if(v!=null)v.vibrate(new long[]{0,140,70,140},-1);}}catch(Exception ignored){}}
    private void menu(){String[] options={"Nueva operación / cargar Excel","Operaciones guardadas","Guardar respaldo completo","Restaurar respaldo completo","Ayuda"};new AlertDialog.Builder(this).setTitle("Movimientos Q9 independiente").setItems(options,(d,w)->{try{if(fileBusy||captureBusy)throw new IllegalStateException("Espera a que termine la acción actual.");switch(w){case 0:canSwitch("");pick("INVENTORY");break;case 1:history();break;case 2:backup();break;case 3:canSwitch("");pick("RESTORE");break;default:new AlertDialog.Builder(this).setTitle("Uso de la Q9").setMessage("1. Carga BoxInventory desde la memoria o una USB.\n2. Lector teclado / HID, sufijo Enter.\n3. Espera ACEPTADA antes de mover.\n4. CAJA BLOQUEADA: sepárala; su ubicación es opcional.\n5. Confirma el destino físico de cada lote.\n6. Finaliza, genera y guarda el ZIP de plantillas.\n\nPuedes cargar un corte nuevo antes de generar. Las diferencias conservan el destino físico en el reporte.\n\nSi Android no muestra la USB, copia el Excel a Descargas y selecciona esa copia.\n\nEl corte es una copia del WMS. La aplicación no confirma la importación de las plantillas al sistema.\n\nConserva un respaldo completo y los datos de la aplicación.").setPositiveButton("Cerrar",null).show();}}catch(Exception e){error(e);}}).show();}
    private void history() throws Exception {
        File[] dirs=new File(getFilesDir(),"operations").listFiles();if(dirs==null||dirs.length==0){toast("No hay operaciones guardadas.");return;}
        java.util.ArrayList<File> saved=new java.util.ArrayList<>();java.util.ArrayList<String> names=new java.util.ArrayList<>();for(File f:dirs){if(!f.getName().matches("[a-f0-9]{32}"))continue;File p=new File(f,"profile.json");if(p.isFile()){JSONObject profile=NativeClient.read(p);saved.add(f);names.add(profile.getJSONObject("operation").getString("name")+" · "+profile.getJSONObject("snapshot").optString("cut_at").replace('T',' '));}}
        new AlertDialog.Builder(this).setTitle("Operaciones guardadas").setItems(names.toArray(new String[0]),(d,w)->{try{canSwitch(saved.get(w).getName());activate(saved.get(w));lastResult=null;page="scan";render();}catch(Exception e){error(e);}}).show();
    }
}
