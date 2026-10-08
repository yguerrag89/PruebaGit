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
    private final ExecutorService network=Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    private LocalStore local;
    private NativeClient client;
    private LinearLayout root,body;
    private TextView syncText,countsText,lotText,resultTitle,resultBody;
    private EditText scanInput;
    private Button scanButton,lotButton;
    private boolean captureBusy,networkBusy,destroyed,switching;
    private String page="scan",lastNetwork="Inventario local",recordFilter="",recordSearch="";
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
            if(getIntent().getData()!=null)pairScreen(getIntent().getData().toString());
        }catch(Exception e){error(e);setupScreen("");}
    }
    @Override public void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);if(intent.getData()!=null)pairScreen(intent.getData().toString());}
    @Override public void onResume(){super.onResume();focusScan();}
    @Override public void onDestroy(){destroyed=true;captures.shutdown();network.shutdown();super.onDestroy();}
    @Override public void onBackPressed(){if(page.equals("scan")&&local!=null){new AlertDialog.Builder(this).setMessage("Las lecturas están guardadas. ¿Salir de Movimientos Q9?").setPositiveButton("Salir",(d,w)->finish()).setNegativeButton("Continuar",(d,w)->focusScan()).show();}else{page="scan";render();}}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private TextView text(String value,int size,int color){TextView v=new TextView(this);v.setText(value);v.setTextSize(size);v.setTextColor(color);v.setPadding(0,dp(4),0,dp(4));return v;}
    private GradientDrawable background(int color){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(9));return d;}
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private void add(View view){body.addView(view,new LinearLayout.LayoutParams(-1,-2));}
    private Button button(String name,Task task){Button b=new Button(this);b.setText(name);b.setAllCaps(false);b.setTextSize(15);b.setMinHeight(dp(46));b.setOnClickListener(v->{try{task.run();}catch(Exception e){error(e);}});return b;}
    private EditText input(String hint,boolean scanner){EditText e=new EditText(this);e.setSingleLine(true);e.setHint(hint);e.setTextSize(19);e.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);e.setImeOptions(EditorInfo.IME_ACTION_DONE);if(scanner)e.setShowSoftInputOnFocus(false);return e;}
    private void enter(EditText field,Task task){
        field.setOnKeyListener((v,key,event)->{if(key!=KeyEvent.KEYCODE_ENTER)return false;if(event.getAction()==KeyEvent.ACTION_DOWN&&event.getRepeatCount()==0){try{task.run();}catch(Exception e){error(e);}}return true;});
        field.setOnEditorActionListener((v,id,event)->{if(event!=null)return false;if(id==EditorInfo.IME_ACTION_DONE||id==EditorInfo.IME_ACTION_GO){try{task.run();}catch(Exception e){error(e);}return true;}return false;});
    }
    private void frame(String title) {
        scanInput=null;resultTitle=null;countsText=null;lotText=null;lotButton=null;scanButton=null;
        root=column();root.setBackgroundColor(0xfff3f6f8);
        LinearLayout bar=new LinearLayout(this);bar.setGravity(Gravity.CENTER_VERTICAL);bar.setPadding(dp(14),dp(4),dp(8),dp(4));bar.setBackgroundColor(BLUE);
        TextView brand=text(title,19,Color.WHITE);brand.setTypeface(null,Typeface.BOLD);bar.addView(brand,new LinearLayout.LayoutParams(0,dp(44),1));
        Button menu=button("⋮",this::menu);menu.setTextSize(24);bar.addView(menu,new LinearLayout.LayoutParams(dp(48),dp(44)));root.addView(bar);
        syncText=text(lastNetwork,12,BLUE);syncText.setPadding(dp(14),dp(5),dp(14),dp(5));root.addView(syncText);
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
            add(text("Corte: "+snapshot.getString("cut_at").replace('T',' ')+" · hora de Windows",12,AMBER));
            if(page.equals("scan"))scanScreen();else if(page.equals("records"))recordsScreen();else endScreen();
            updateCounts();focusScan();
        }catch(Exception e){error(e);}
    }
    private void setupScreen(String qr){
        page="setup";frame("Preparar Movimientos Q9");add(text("Valida cajas antes de moverlas",22,BLUE));
        add(text("Carga el inventario una vez. Cada escaneo y lote queda guardado en esta PDA, incluso sin Wi‑Fi.",15,BLUE));
        add(button("Vincular por QR a Windows",()->pairScreen(qr)));
        add(button("Importar paquete inicial por USB",()->pick("BOOTSTRAP","application/zip")));
        if(client!=null&&!new File(client.directory,"inventory.db").isFile())add(button("Reintentar descarga del inventario",()->networkTask("Descargando inventario",()->{client.pair(client.profile.optString("pair_code"),this::progress);openReady();},this::render)));
        add(button("Operaciones guardadas",this::history));
        add(text("En Windows: carga BoxInventory, abre una operación y muestra el QR para APK. Con el lector de la Q9, escanéalo dentro de Vincular por QR. Si no hay conexión, copia el paquete inicial ZIP por USB e impórtalo aquí.",14,BLUE));
        add(text("Movimientos Q9 0.2.0 · Android 6 o posterior · lector teclado + Enter",12,AMBER));
    }
    private boolean canSwitch(String op) throws Exception {
        if(local==null||NativeClient.opId(client.profile).equals(op))return true;
        if(!local.sealed()||local.pending()>0)throw new IllegalStateException("Finaliza y entrega la operación actual antes de cambiar. Puedes confirmar una entrega USB importando el comprobante de Windows.");
        return true;
    }
    private void pairScreen(String raw){
        page="pair";frame("Vincular por QR");add(text("Escanea el QR de Windows aquí",20,BLUE));
        EditText qr=input("QR de Movimientos Q9",true);qr.setText(raw);add(qr);
        Task connect=()->pairQr(qr.getText().toString());enter(qr,connect);add(button("Vincular y cargar inventario",connect));
        add(text("Conecta la Q9 y Windows a la misma red. El QR debe decir «para APK nativa».",14,BLUE));
        EditText address=input("http://192.168.1.10:8765",false),code=input("Código de vinculación",false),operation=input("Identificador de operación",false);add(address);add(code);add(operation);
        add(button("Vincular con datos escritos",()->pairDetails(address.getText().toString(),code.getText().toString(),operation.getText().toString())));
        qr.requestFocus();
    }
    private void pairQr(String raw) throws Exception {
        Uri u=Uri.parse(Rules.clean(raw));if(!"movimientosq9".equals(u.getScheme())||!"pair".equals(u.getHost()))throw new IllegalArgumentException("Usa el QR para APK nativa de Movimientos Q9.");
        pairDetails(u.getQueryParameter("server"),u.getQueryParameter("code"),u.getQueryParameter("operation_id"));
    }
    private void pairDetails(String server,String code,String op) throws Exception {
        String address=NativeClient.server(server);op=Rules.clean(op);if(!op.matches("[a-f0-9]{32}")||code==null||!code.matches("[A-Za-z0-9_-]{20,100}"))throw new IllegalArgumentException("Revisa el código y el identificador de operación.");
        canSwitch(op);if(networkBusy||captureBusy)throw new IllegalStateException("Espera a que termine la acción en curso.");
        File directory=new File(getFilesDir(),"operations/"+op);directory.mkdirs();JSONObject profile;
        if(new File(directory,"profile.json").isFile())profile=NativeClient.read(new File(directory,"profile.json"));
        else {String device=prefs.getString("device_id","");if(device.isEmpty()){device=UUID.randomUUID().toString();prefs.edit().putString("device_id",device).commit();}byte[] key=new byte[32];new SecureRandom().nextBytes(key);profile=new JSONObject().put("protocol",2).put("operation_id",op).put("device_id",device).put("token",NativeClient.hex(key));}
        profile.put("server",address).put("pair_code",code);NativeClient next=new NativeClient(directory,profile);next.save();
        switching=true;
        prefs.edit().putString("current_operation",op).commit();
        networkTask("Vinculando",()->{next.pair(code,this::progress);if(local!=null)local.close();client=next;local=new LocalStore(directory);prefs.edit().putString("current_operation",NativeClient.opId(profile)).commit();lastResult=null;},()->{page="scan";lastNetwork="Inventario listo · lectura local";render();});
    }
    private void loadOperation(File directory) throws Exception {
        client=new NativeClient(directory,NativeClient.read(new File(directory,"profile.json")));
        if(new File(directory,"inventory.db").isFile())local=new LocalStore(directory);
    }
    private void openReady() throws Exception {if(local!=null)local.close();local=new LocalStore(client.directory);prefs.edit().putString("current_operation",NativeClient.opId(client.profile)).commit();page="scan";}
    private void scanScreen() throws Exception {
        countsText=text("",13,BLUE);add(countsText);lotText=text("",17,BLUE);lotText.setTypeface(null,Typeface.BOLD);add(lotText);
        scanInput=input("Escanea código de caja",true);scanInput.setTextSize(23);scanInput.setMinHeight(dp(55));scanInput.setEnabled(!local.sealed());add(scanInput);enter(scanInput,this::scan);
        scanButton=button("Validar caja",this::scan);scanButton.setEnabled(!local.sealed());add(scanButton);
        LinearLayout box=column();box.setPadding(dp(12),dp(10),dp(12),dp(10));box.setBackground(background(BLUE));
        resultTitle=text(local.sealed()?"CAPTURA FINALIZADA":"LISTO PARA ESCANEAR",21,Color.WHITE);resultTitle.setTypeface(null,Typeface.BOLD);resultBody=text(local.sealed()?"Sincroniza y genera las plantillas.":"Espera el resultado antes de mover la caja.",15,Color.WHITE);box.addView(resultTitle);box.addView(resultBody);add(box);
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
    private void updateCounts(){try{if(local==null)return;JSONObject s=local.stats();if(countsText!=null)countsText.setText(s.getInt("ACCEPTED")+" aceptadas · "+s.getInt("BLOCKED")+" bloqueadas · "+s.getInt("REVIEW")+" revisar");if(lotText!=null)lotText.setText("Lote "+s.getInt("lot_number")+" · "+s.getInt("lot_count")+" cajas sin destino");if(lotButton!=null)lotButton.setEnabled(s.getInt("lot_count")>0&&!local.sealed());if(syncText!=null&&!networkBusy)syncText.setText(s.getInt("pending")+" acciones por entregar · "+(s.optString("last_sync").isEmpty()?"inventario local":"última entrega "+s.getString("last_sync")));}catch(Exception e){error(e);}}
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
        JSONObject s=local.stats();add(text(s.getInt("ACCEPTED")+" cajas aceptadas\n"+s.getInt("BLOCKED")+" bloqueadas\n"+s.getInt("REVIEW")+" para revisión\n"+s.getInt("pending")+" acciones pendientes de entrega",18,BLUE));
        add(button("Sincronizar con Windows",this::sync));
        if(!local.sealed())add(button("Finalizar captura",this::sealDialog));
        else {add(text("Captura finalizada. Todas las cajas aceptadas tienen destino físico.",15,GREEN));add(button("Generar plantillas y reporte",this::generate));}
        add(text("Las plantillas se crean en Windows, revalidando con su inventario más reciente. La generación deja los movimientos pendientes de cargar al WMS.",14,BLUE));
        add(button("Guardar respaldo para entregar por USB",this::backup));
        add(button("Importar comprobante de entrega USB",()->pick("RECEIPT","application/json")));
        String serverInfo=local.setting("server_info");if(!serverInfo.isEmpty()){JSONObject info=new JSONObject(serverInfo);JSONObject latest=info.optJSONObject("current_snapshot");if(latest!=null&&latest.optLong("id")!=client.profile.getJSONObject("snapshot").getLong("id"))add(text("Windows tiene un corte más reciente. Esta captura conserva el inventario inicial; las diferencias quedarán en la conciliación.",14,AMBER));}
        File packet=new File(client.directory,"Movimientos_"+NativeClient.opId(client.profile).substring(0,8)+".zip");if(packet.isFile())add(button("Guardar ZIP de plantillas en el equipo",()->save(packet,"application/zip")));
    }
    private void sealDialog() throws Exception {
        if(local.lotCount()>0)throw new IllegalStateException("Confirma primero la ubicación de las cajas del lote.");
        new AlertDialog.Builder(this).setTitle("Finalizar captura").setMessage("Se conservarán cajas, lotes e incidencias. Después de finalizar podrás sincronizar y generar plantillas; la captura de esta operación quedará cerrada.").setPositiveButton("Finalizar",(d,w)->{try{if(captureBusy)throw new IllegalStateException("Espera a que termine la lectura.");local.seal();render();toast("Captura finalizada y guardada. Entrega la bitácora por Wi‑Fi o USB.");}catch(Exception e){error(e);}}).setNegativeButton("Continuar capturando",null).show();
    }
    private void sync() throws Exception {if(local==null)return;networkTask("Sincronizando",()->client.sync(local,this::progress),()->{lastNetwork="Entrega confirmada en Windows";render();toast("Sincronización terminada.");});}
    private void generate() throws Exception {
        if(!local.sealed())throw new IllegalStateException("Finaliza la captura primero.");
        networkTask("Generando plantillas",()->{client.sync(local,this::progress);if(local.pending()>0)throw new IllegalStateException("Hay acciones pendientes. Completa la sincronización.");JSONObject export=client.generate(this::progress);local.setting("export",export.toString());saveFile=client.downloadExport(export,this::progress);},()->{render();new AlertDialog.Builder(this).setTitle("Plantillas y reporte guardados").setMessage("El mismo paquete está en Windows. Las cajas bloqueadas e incidencias permanecen en el reporte; quedan fuera de las plantillas. Pendiente de cargar al WMS.").setPositiveButton("Guardar ZIP",(d,w)->{try{save(saveFile,"application/zip");}catch(Exception e){error(e);}}).setNegativeButton("Cerrar",null).show();});
    }
    private void backup() throws Exception {
        if(local==null)return;File file=new File(client.directory,"Respaldo_Movimientos_"+NativeClient.opId(client.profile).substring(0,8)+".json");
        networkTask("Preparando respaldo",()->{JSONObject b=local.backup(NativeClient.opId(client.profile),client.profile.getString("device_id"));try(FileOutputStream out=new FileOutputStream(file)){out.write(b.toString().getBytes(StandardCharsets.UTF_8));out.getFD().sync();}},()->{try{save(file,"application/json");}catch(Exception e){error(e);}});
    }
    private void save(File file,String mime) throws Exception {saveFile=file;Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType(mime);i.putExtra(Intent.EXTRA_TITLE,file.getName());startActivityForResult(i,10);}
    private void pick(String kind,String mime) throws Exception {if(captureBusy||networkBusy)throw new IllegalStateException("Espera a que termine la acción actual.");openKind=kind;Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("*/*");i.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{mime,"application/octet-stream"});startActivityForResult(i,11);}
    @Override public void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null||data.getData()==null){focusScan();return;}Uri uri=data.getData();
        if(request==10&&saveFile!=null){File file=saveFile;networkTask("Guardando archivo",()->{try(InputStream in=new FileInputStream(file);OutputStream out=getContentResolver().openOutputStream(uri)){if(out==null)throw new IOException("No se puede escribir en esa carpeta.");NativeClient.copy(in,out,128L*1024*1024);}},()->toast("Archivo guardado: "+file.getName()));}
        else if(request==11){String kind=openKind;if(kind.equals("BOOTSTRAP")){switching=true;networkTask("Importando inventario por USB",()->importBootstrap(uri),()->{page="scan";lastNetwork="Inventario importado por USB · lectura local";render();});}else if(kind.equals("RECEIPT"))networkTask("Comprobando entrega USB",()->{try(InputStream in=getContentResolver().openInputStream(uri)){if(in==null)throw new IOException("Archivo no disponible.");ByteArrayOutputStream b=new ByteArrayOutputStream();NativeClient.copy(in,b,128*1024);client.receipt(local,new JSONObject(b.toString("UTF-8")));}},()->{render();toast("Entrega a Windows comprobada. Las acciones confirmadas ya no están pendientes.");});}
    }
    private void importBootstrap(Uri uri) throws Exception {
        File staging=new File(getFilesDir(),"incoming_"+UUID.randomUUID());staging.mkdirs();JSONObject profile=null;File gzip=new File(staging,"inventory.db.gz");boolean inv=false;
        try {
            try(InputStream file=getContentResolver().openInputStream(uri);ZipInputStream zip=new ZipInputStream(file)){
                ZipEntry entry;int entries=0;while((entry=zip.getNextEntry())!=null){entries++;if(entries>2||entry.isDirectory())throw new IOException("Paquete inicial no válido.");if(entry.getName().equals("inicio.json")&&profile==null){ByteArrayOutputStream b=new ByteArrayOutputStream();NativeClient.copy(zip,b,128*1024);profile=new JSONObject(b.toString("UTF-8"));}
                    else if(entry.getName().equals("inventory.db.gz")&&!inv){try(FileOutputStream out=new FileOutputStream(gzip)){NativeClient.copy(zip,out,128L*1024*1024);out.getFD().sync();}inv=true;}else throw new IOException("El ZIP no es un paquete inicial de Movimientos Q9.");zip.closeEntry();}
            }
            if(profile==null||!inv||profile.getInt("protocol")!=2)throw new IOException("Paquete inicial incompleto.");String op=NativeClient.opId(profile);if(!op.matches("[a-f0-9]{32}"))throw new IOException("Identificador de operación inválido.");canSwitch(op);NativeClient.server(profile.getString("server"));UUID.fromString(profile.getString("device_id"));if(!profile.getString("token").matches("[A-Za-z0-9_-]{40,100}"))throw new IOException("Clave del paquete inicial inválida.");
            MessageDigest hash=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(gzip)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)hash.update(b,0,n);}if(gzip.length()!=profile.getLong("inventory_size")||!NativeClient.hex(hash.digest()).equals(profile.getString("inventory_sha256")))throw new IOException("Inventario dañado o incompleto.");
            File directory=new File(getFilesDir(),"operations/"+op);directory.mkdirs();File old=new File(directory,"profile.json");
            if(old.isFile()){JSONObject previous=NativeClient.read(old);if(!previous.getString("device_id").equals(profile.getString("device_id"))||!previous.getString("token").equals(profile.getString("token")))throw new IOException("Esta operación ya tiene otra identidad local. Se conservaron sus lecturas.");}
            if(!new File(directory,"inventory.db").isFile())NativeClient.importInventory(gzip,directory,profile,this::progress);
            NativeClient next=new NativeClient(directory,profile);next.save();if(local!=null)local.close();client=next;local=new LocalStore(directory);prefs.edit().putString("current_operation",op).commit();lastResult=null;
        }finally{gzip.delete();staging.delete();}
    }
    private void networkTask(String title,Task work,Task done){
        if(networkBusy){toast("Ya hay una transferencia en proceso.");return;}networkBusy=true;progress(title);
        network.execute(()->{try{work.run();ui.post(()->{networkBusy=false;switching=false;if(destroyed)return;try{done.run();updateCounts();}catch(Exception e){error(e);}});}catch(Exception e){ui.post(()->{networkBusy=false;switching=false;lastNetwork="Entrega pendiente · lecturas conservadas";if(!destroyed){updateCounts();error(e);}});}});
    }
    private void progress(String value){ui.post(()->{lastNetwork=value;if(syncText!=null&&!destroyed)syncText.setText(value);});}
    private String message(Exception e){if(e instanceof java.net.SocketTimeoutException)return "Windows no respondió a tiempo. Revisa IP, Wi‑Fi y permiso de Python en el firewall. Reintenta; tus lecturas se conservan.";if(e instanceof java.net.ConnectException)return "No se pudo conectar a Windows. Puedes trabajar con el inventario local y entregar la bitácora por USB.";return e.getMessage()==null?"No se pudo completar la acción. Tus lecturas guardadas se conservan.":e.getMessage();}
    private void error(Exception e){if(destroyed)return;new AlertDialog.Builder(this).setTitle("Revisar").setMessage(message(e)).setPositiveButton("Cerrar",(d,w)->focusScan()).show();}
    private void toast(String text){if(!destroyed)Toast.makeText(this,text,Toast.LENGTH_LONG).show();}
    private void tone(String status){try{ToneGenerator t=new ToneGenerator(AudioManager.STREAM_NOTIFICATION,90);t.startTone(status.equals("ACCEPTED")?ToneGenerator.TONE_PROP_ACK:ToneGenerator.TONE_PROP_NACK,status.equals("ACCEPTED")?120:350);ui.postDelayed(t::release,500);if(!status.equals("ACCEPTED")){Vibrator v=(Vibrator)getSystemService(VIBRATOR_SERVICE);if(v!=null)v.vibrate(new long[]{0,140,70,140},-1);}}catch(Exception ignored){}}
    private void menu(){String[] options={"Vincular / cambiar operación","Importar paquete inicial USB","Cambiar dirección de Windows","Operaciones guardadas","Guardar respaldo USB","Ayuda"};new AlertDialog.Builder(this).setTitle("Movimientos Q9").setItems(options,(d,w)->{try{switch(w){case 0:pairScreen("");break;case 1:pick("BOOTSTRAP","application/zip");break;case 2:addressDialog();break;case 3:history();break;case 4:backup();break;default:new AlertDialog.Builder(this).setTitle("Uso de la Q9").setMessage("1. Lector en modo teclado / HID, sufijo Enter.\n2. Escanea y espera ACEPTADA antes de mover.\n3. CAJA BLOQUEADA: sepárala; su ubicación es opcional.\n4. Al terminar el lote, confirma su destino físico.\n5. Finaliza, sincroniza y genera plantillas.\n\nEl corte es una copia del WMS, no una consulta en vivo. Por USB importa el paquete inicial y entrega el respaldo final a Windows. Importa luego el comprobante para confirmar la entrega.\n\nNo borres los datos de esta aplicación durante una operación.").setPositiveButton("Cerrar",null).show();}}catch(Exception e){error(e);}}).show();}
    private void addressDialog() throws Exception {if(client==null)throw new IllegalStateException("Vincula una operación primero.");EditText address=input("http://192.168.1.10:8765",false);address.setText(client.profile.getString("server"));new AlertDialog.Builder(this).setTitle("Dirección de Windows").setView(address).setPositiveButton("Guardar",(d,w)->{try{if(networkBusy)throw new IllegalStateException("Espera a que termine la transferencia.");client.profile.put("server",NativeClient.server(address.getText().toString()));client.save();toast("Dirección actualizada. Los datos locales se conservan.");}catch(Exception e){error(e);}}).setNegativeButton("Cancelar",null).show();}
    private void history() throws Exception {
        File[] dirs=new File(getFilesDir(),"operations").listFiles();if(dirs==null||dirs.length==0){toast("No hay operaciones guardadas.");return;}
        java.util.ArrayList<File> files=new java.util.ArrayList<>();java.util.ArrayList<String> names=new java.util.ArrayList<>();for(File f:dirs){File p=new File(f,"profile.json");if(p.isFile()){JSONObject profile=NativeClient.read(p);files.add(f);names.add(profile.has("operation")?profile.getJSONObject("operation").getString("name"):f.getName());}}
        new AlertDialog.Builder(this).setTitle("Operaciones guardadas").setItems(names.toArray(new String[0]),(d,w)->{try{if(networkBusy||captureBusy)throw new IllegalStateException("Espera a que termine la acción actual.");canSwitch(files.get(w).getName());if(local!=null)local.close();local=null;loadOperation(files.get(w));prefs.edit().putString("current_operation",files.get(w).getName()).commit();lastResult=null;page="scan";render();}catch(Exception e){error(e);}}).show();
    }
}
