package com.ilubox.descargapda;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;

import com.ilubox.descargapda.core.ActionResult;
import com.ilubox.descargapda.core.BufferCandidate;
import com.ilubox.descargapda.core.BufferSector;
import com.ilubox.descargapda.core.Position;
import com.ilubox.descargapda.core.PositionCard;
import com.ilubox.descargapda.core.Pressure;
import com.ilubox.descargapda.core.ScanResult;
import com.ilubox.descargapda.core.UnloadEngine;
import com.ilubox.descargapda.data.ManifestImporter;
import com.ilubox.descargapda.data.PilotDatabase;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class MainActivity extends ComponentActivity {
    private static final int REQ_IMPORT = 1201;
    private static final int REQ_EXPORT = 1202;
    private static final int REQ_EXPORT_XLSX = 1203;
    private static final int REQ_EXPORT_WMS = 1204;
    private static final int REQ_EXPORT_PDA_RESULT = 1205;

    private static final int C_DARK = Color.rgb(23, 32, 51);
    private static final int C_BLUE = Color.rgb(36, 87, 214);
    private static final int C_GREEN = Color.rgb(21, 128, 61);
    private static final int C_RED = Color.rgb(185, 28, 28);
    private static final int C_ORANGE = Color.rgb(194, 65, 12);
    private static final int C_GRAY = Color.rgb(107, 114, 128);
    private static final int C_LIGHT = Color.rgb(248, 250, 252);
    private static final int C_BORDER = Color.rgb(203, 213, 225);
    private static final int C_DISABLED = Color.rgb(226, 232, 240);
    private static final int C_YELLOW = Color.rgb(254, 243, 199);
    private static final int C_LIGHT_BLUE = Color.rgb(219, 234, 254);
    private static final int C_LIGHT_GREEN = Color.rgb(220, 252, 231);
    private static final int C_LIGHT_RED = Color.rgb(254, 226, 226);
    private static final int C_LIGHT_ORANGE = Color.rgb(255, 237, 213);

    private PilotDatabase db;
    private UnloadEngine engine;
    private ManifestImporter.ManifestData pendingManifest;
    private ToneGenerator tones;
    private Vibrator vibrator;
    private String lastPosition = "";
    private boolean inSupervisor = false;
    private boolean inPalletView = false;
    private boolean showAllPallets = false;
    private boolean storageBlocked = false;
    private int operationDialogs = 0;
    private Button changeTransferButton;
    private final OnBackPressedCallback returnToScanner = new OnBackPressedCallback(false) {
        @Override public void handleOnBackPressed() { showOperator(); }
    };

    private EditText scanInput;
    private TextView positionResult;
    private TextView statusResult;
    private TextView codeResult;
    private TextView countResult;
    private TextView progressText;
    private TextView pressureText;
    private LinearLayout pendingReadyBox;
    private GridLayout mapGrid;
    private TextView recentText;
    private Button activePositionButton;

    private int setupLeft = 2;
    private int setupRight = 2;
    private int setupBufferPallets = 4;
    /** V0.10 prioriza traslado continuo; MANUAL/BUFFER se conservan para comparación. */
    private String setupMode = "TRASLADO";

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getOnBackPressedDispatcher().addCallback(this, returnToScanner);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        db = new PilotDatabase(this);
        engine = db.loadEngine();
        tones = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90);
        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (engine == null) showHome(); else showOperator();
    }

    @Override protected void onDestroy() {
        if (tones != null) tones.release();
        db.close();
        super.onDestroy();
    }

    @Override protected void onResume() {
        super.onResume();
        focusScanner();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) focusScanner();
    }

    private void focusScanner() {
        if (inSupervisor || inPalletView || storageBlocked || operationDialogs > 0 || scanInput == null) return;
        scanInput.postDelayed(() -> {
            if (scanInput != null && !inSupervisor && !inPalletView && !storageBlocked && operationDialogs == 0) {
                scanInput.requestFocus();
                scanInput.setSelection(scanInput.getText().length());
            }
        }, 60);
    }

    private void vibrateOk() { vibratePattern(new long[]{0, 45}); }
    private void vibrateDuplicate() { vibratePattern(new long[]{0, 120, 70, 120}); }
    private void vibrateError() { vibratePattern(new long[]{0, 220}); }

    private void vibratePattern(long[] pattern) {
        if (vibrator == null || !vibrator.hasVibrator()) return;
        try {
            if (Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1));
            else vibrator.vibrate(pattern, -1);
        } catch (Exception ignored) {}
    }

    @Override public void onContentChanged() {
        super.onContentChanged();
        // Solo interceptar regreso desde consulta/administración; en escaneo actúa el sistema.
        if (returnToScanner != null) returnToScanner.setEnabled(engine != null && (inSupervisor || inPalletView));
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private boolean compactPda() {
        android.content.res.Configuration c = getResources().getConfiguration();
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        String model = android.os.Build.MODEL == null ? "" : android.os.Build.MODEL.toUpperCase(Locale.ROOT);
        // La AUTOID Q9 reporta una pantalla física estrecha (480 px).
        // Detectamos por píxeles además de dp para no depender de la densidad configurada por Android.
        return model.contains("Q9")
                || dm.widthPixels <= 540
                || dm.heightPixels <= 960
                || c.screenWidthDp <= 360
                || c.screenHeightDp <= 640;
    }

    private int rh(int normalDp, int compactDp) {
        return dp(compactPda() ? compactDp : normalDp);
    }

    private float rsp(float normalSp, float compactSp) {
        return compactPda() ? compactSp : normalSp;
    }

    private GradientDrawable box(int fill, int stroke, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radiusDp));
        d.setStroke(dp(1), stroke);
        return d;
    }

    private TextView tv(String text, float sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setGravity(Gravity.CENTER_VERTICAL);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }

    private Button button(String text, int bg, int fg) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(fg);
        b.setTextSize(rsp(15, 12));
        b.setAllCaps(false);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setBackground(box(bg, bg, 10));
        b.setPadding(dp(compactPda() ? 6 : 12), dp(compactPda() ? 4 : 8), dp(compactPda() ? 6 : 12), dp(compactPda() ? 4 : 8));
        return b;
    }

    private LinearLayout root() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setBackgroundColor(C_LIGHT);
        r.setPadding(dp(compactPda() ? 6 : 12), dp(compactPda() ? 5 : 10), dp(compactPda() ? 6 : 12), dp(compactPda() ? 5 : 10));
        return r;
    }

    private View spacer(int h) {
        Space s = new Space(this);
        s.setLayoutParams(new LinearLayout.LayoutParams(1, dp(h)));
        return s;
    }

    private void showHome() {
        inSupervisor = false;
        inPalletView = false;
        scanInput = null;
        LinearLayout r = root();

        TextView title = tv(compactPda() ? "ILUBOX DESCARGA" : "ILUBOX · DESCARGA PDA", rsp(26, 20), C_DARK, true);
        title.setGravity(Gravity.CENTER);
        title.setSingleLine(true);
        r.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rh(60, 38)));

        TextView sub = tv("V0.10 · operador continuo + WMS estricto", rsp(18, 13), C_GRAY, true);
        sub.setGravity(Gravity.CENTER);
        r.addView(sub, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rh(34, 24)));
        r.addView(spacer(compactPda() ? 8 : 24));

        String introText = compactPda()
                ? "Importa la descarga (.json). Después la PDA trabaja sin Wi‑Fi ni computadora."
                : "Importa el archivo preparado desde el Packing List. Después la PDA puede trabajar sin Wi‑Fi ni computadora.";
        TextView intro = tv(introText, rsp(17, 13), C_DARK, false);
        intro.setGravity(Gravity.CENTER);
        intro.setPadding(dp(compactPda() ? 7 : 10), dp(compactPda() ? 6 : 10), dp(compactPda() ? 7 : 10), dp(compactPda() ? 6 : 10));
        intro.setBackground(box(Color.WHITE, C_BORDER, 12));
        r.addView(intro, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        r.addView(spacer(compactPda() ? 10 : 22));

        Button importBtn = button(compactPda() ? "📂  IMPORTAR .JSON" : "📂  IMPORTAR DESCARGA (.json)", C_BLUE, Color.WHITE);
        importBtn.setOnClickListener(v -> chooseManifest());
        r.addView(importBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rh(64, 50)));

        r.addView(spacer(compactPda() ? 7 : 12));
        Button demoBtn = button("CARGAR DEMO DE PRUEBA", Color.WHITE, C_BLUE);
        demoBtn.setBackground(box(Color.WHITE, C_BLUE, 10));
        demoBtn.setOnClickListener(v -> loadDemo());
        r.addView(demoBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rh(54, 42)));

        r.addView(spacer(compactPda() ? 10 : 24));
        TextView hint = tv(compactPda() ? "Lector: modo HID + sufijo Enter" : "Lector recomendado para el piloto: modo teclado (HID / keyboard wedge) y sufijo Enter.", rsp(14, 10), C_GRAY, false);
        hint.setGravity(Gravity.CENTER);
        r.addView(hint);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(r);
        setContentView(scroll);
    }

    private void chooseManifest() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/json");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/plain", "application/octet-stream"});
        startActivityForResult(i, REQ_IMPORT);
    }

    private void loadDemo() {
        try (InputStream in = getAssets().open("demo_descarga.json")) {
            pendingManifest = ManifestImporter.parse(in);
            applyRecommendedBufferPlan();
            showSetup();
        } catch (Exception e) {
            Toast.makeText(this, "No se pudo cargar demo: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private int recommendedBufferPallets(ManifestImporter.ManifestData m) {
        if (m == null || m.records == null) return 4;
        int nonUnit = 0, small = 0;
        double target = m.settings == null ? 1.94 : m.settings.targetCapacity;
        for (com.ilubox.descargapda.core.CodeRecord c : m.records) {
            if (c.boxes <= 1) continue;
            nonUnit++;
            if (c.cbm <= target * 0.25) small++;
        }
        int suggestion = 3;
        if (nonUnit >= 70 || small >= 45) suggestion++;
        if (nonUnit >= 150 || small >= 100) suggestion++;
        return Math.max(2, Math.min(6, suggestion));
    }

    private int recommendedDefinitivePositions(ManifestImporter.ManifestData m) {
        if (m == null || m.records == null) return 4;
        double target = m.settings == null ? 1.94 : m.settings.targetCapacity;
        int directCodes = 0;
        for (com.ilubox.descargapda.core.CodeRecord c : m.records) {
            if (c.cbm >= target * (m.settings == null ? 0.70 : m.settings.largeRatio)) directCodes++;
        }
        if (directCodes <= 0) return 0;
        return Math.min(6, directCodes);
    }

    private void applyRecommendedBufferPlan() {
        setupMode = "TRASLADO";
        setupBufferPallets = recommendedBufferPallets(pendingManifest);
        int definitive = recommendedDefinitivePositions(pendingManifest);
        setupLeft = (definitive + 1) / 2;
        setupRight = definitive / 2;
    }

    private String bufferPlanSummary() {
        if (pendingManifest == null) return "";
        int units = 0, multi = 0, nonUnit = 0;
        double target = pendingManifest.settings == null ? 1.94 : pendingManifest.settings.targetCapacity;
        for (com.ilubox.descargapda.core.CodeRecord c : pendingManifest.records) {
            if (c.boxes == 1) units++; else nonUnit++;
            if (c.cbm > target) multi++;
        }
        return "PLAN SUGERIDO · " + setupBufferPallets + " buffer × 4 sectores · "
                + (setupLeft + setupRight) + " definitivas\n"
                + nonUnit + " códigos a buffer · " + units + " unitarios directos · " + multi + " códigos >1 tarima";
    }

    private UnloadEngine pendingTransferPlanner() {
        if (pendingManifest == null) return null;
        return new UnloadEngine(pendingManifest.containerId, pendingManifest.records, pendingManifest.settings,
                setupLeft, setupRight, "TRASLADO", setupBufferPallets);
    }

    private String transferPlanSummary() {
        UnloadEngine plan = pendingTransferPlanner();
        if (plan == null) return "";
        int footDefinitive = plan.initialDirectFootPalletCount();
        int footTotal = footDefinitive + 1; // una TR-xx activa
        int replacements = Math.max(0, plan.estimatedDirectFinalPallets - footDefinitive);
        return "PREPARACIÓN FÍSICA INICIAL\n"
                + "Tendido final: " + plan.plannedTendidoPalletCount() + " tarimas\n"
                + "Al pie: " + footDefinitive + " definitivas + 1 traslado = " + footTotal + "\n"
                + "Total a tender ahora: " + plan.initialPhysicalPalletCount() + " tarimas\n"
                + "Estimado de toda la descarga: " + plan.plannedFinalPalletCount() + " definitivas"
                + (replacements > 0 ? " · " + replacements + " reemplazos al pie" : "");
    }

    private void showSetup() {
        inSupervisor = true;
        LinearLayout r = root();
        TextView title = tv("PREPARAR DESCARGA", rsp(25, 20), C_DARK, true);
        title.setSingleLine(true);
        title.setGravity(Gravity.CENTER_VERTICAL);
        r.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rh(42, 34)));
        r.addView(spacer(compactPda() ? 4 : 10));

        int boxes = 0;
        double cbm = 0;
        for (com.ilubox.descargapda.core.CodeRecord c : pendingManifest.records) { boxes += c.boxes; cbm += c.cbm; }
        String summary = pendingManifest.containerId + "\n" + pendingManifest.records.size() + " códigos · " + boxes + " cajas · " + String.format(Locale.getDefault(), "%.1f m³", cbm);
        TextView info = tv(summary, rsp(18, 14), C_DARK, true);
        info.setPadding(dp(compactPda() ? 8 : 12), dp(compactPda() ? 6 : 12), dp(compactPda() ? 8 : 12), dp(compactPda() ? 6 : 12));
        info.setBackground(box(Color.WHITE, C_BORDER, 12));
        r.addView(info);
        r.addView(spacer(compactPda() ? 7 : 18));

        TextView help = tv("TRASLADO".equals(setupMode)
                ? "TRASLADO DIRIGIDO: escanea, marca el número de tarima final y agrupa el viaje. Los códigos grandes van directo."
                : ("BUFFER".equals(setupMode)
                ? "BUFFER MODULAR: cada tarima buffer se divide en 4 sectores (A-D), un código por sector."
                : (compactPda()
                    ? "Posiciones definitivas disponibles al iniciar · máximo 10 por lado."
                    : "Selecciona únicamente las posiciones definitivas físicamente disponibles al iniciar. Máximo 10 por lado.")),
                rsp(16, 12), C_GRAY, false);
        r.addView(help);
        r.addView(spacer(compactPda() ? 6 : 12));

        TextView modeLabel = tv("ESTRATEGIA DE DESCARGA", rsp(14, 11), C_DARK, true);
        modeLabel.setGravity(Gravity.CENTER);
        r.addView(modeLabel);
        r.addView(spacer(compactPda() ? 3 : 6));
        r.addView(modeControl());
        r.addView(spacer(compactPda() ? 7 : 12));

        if ("BUFFER".equals(setupMode)) {
            TextView plan = tv(bufferPlanSummary(), rsp(14, 11), C_DARK, true);
            plan.setPadding(dp(8), dp(6), dp(8), dp(6));
            plan.setBackground(box(C_LIGHT_GREEN, C_GREEN, 10));
            r.addView(plan);
            r.addView(spacer(compactPda() ? 6 : 10));
            r.addView(bufferNumberControl());
            r.addView(spacer(compactPda() ? 6 : 10));
        }

        if ("TRASLADO".equals(setupMode)) {
            TextView plan = tv(transferPlanSummary(), rsp(15, 11), C_DARK, true);
            plan.setTag("transferPlan");
            plan.setPadding(dp(9), dp(7), dp(9), dp(7));
            plan.setBackground(box(C_LIGHT_GREEN, C_GREEN, 10));
            r.addView(plan);
            r.addView(spacer(compactPda() ? 7 : 12));
        }

        TextView definitiveLabel = tv("TRASLADO".equals(setupMode)
                ? "DEFINITIVAS EN BLANCO AL PIE" : "POSICIONES DEFINITIVAS INICIALES",
                rsp(14, 11), C_DARK, true);
        definitiveLabel.setGravity(Gravity.CENTER);
        r.addView(definitiveLabel);
        r.addView(spacer(4));

        TextView leftValue = tv("", rsp(32, 24), C_DARK, true);
        TextView rightValue = tv("", rsp(32, 24), C_DARK, true);
        r.addView(numberControl("IZQUIERDA", leftValue, true));
        r.addView(spacer(compactPda() ? 5 : 10));
        r.addView(numberControl("DERECHA", rightValue, false));
        updateSetupValues(leftValue, rightValue);

        r.addView(spacer(compactPda() ? 9 : 22));
        Button start = button("▶  INICIAR DESCARGA", C_GREEN, Color.WHITE);
        start.setOnClickListener(v -> {
            UnloadEngine preview = "TRASLADO".equals(setupMode) ? pendingTransferPlanner() : null;
            if (setupLeft + setupRight <= 0 && (preview == null || preview.directCodeCount() > 0)) {
                Toast.makeText(this, "Habilita al menos una posición para las definitivas directas", Toast.LENGTH_SHORT).show();
                return;
            }
            UnloadEngine candidate = new UnloadEngine(pendingManifest.containerId, pendingManifest.records, pendingManifest.settings,
                    setupLeft, setupRight, setupMode, setupBufferPallets);
            try {
                db.startNewSession(candidate, "Descarga iniciada · modo=" + setupMode
                        + " · I=" + setupLeft + " D=" + setupRight
                        + ("BUFFER".equals(setupMode) ? " · Buffer=" + setupBufferPallets + "×4 sectores" : "")
                        + ("TRASLADO".equals(setupMode) ? " · Plan=" + candidate.plannedFinalPalletCount()
                                + " definitivas · tendido=" + candidate.plannedTendidoPalletCount()
                                + " · pie=" + candidate.initialDirectFootPalletCount() + "+TR" : ""));
                engine = candidate;
                storageBlocked = false;
                lastPosition = "";
                pendingManifest = null;
                showOperator();
            } catch (Exception e) {
                Toast.makeText(this, "Error guardando descarga: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
        r.addView(start, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rh(64, 50)));

        r.addView(spacer(compactPda() ? 5 : 10));
        Button back = button("Cancelar", Color.WHITE, C_GRAY);
        back.setBackground(box(Color.WHITE, C_BORDER, 10));
        back.setOnClickListener(v -> { pendingManifest = null; if (engine != null) showSupervisor(); else showHome(); });
        r.addView(back, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rh(50, 38)));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(r);
        setContentView(scroll);
    }

    private View modeControl() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);

        Button transfer = button("TRASLADO", "TRASLADO".equals(setupMode) ? C_GREEN : Color.WHITE,
                "TRASLADO".equals(setupMode) ? Color.WHITE : C_GREEN);
        Button buffer = button("BUFFER", "BUFFER".equals(setupMode) ? C_GREEN : Color.WHITE,
                "BUFFER".equals(setupMode) ? Color.WHITE : C_GREEN);
        Button manual = button("MANUAL", "MANUAL".equals(setupMode) ? C_BLUE : Color.WHITE,
                "MANUAL".equals(setupMode) ? Color.WHITE : C_BLUE);
        transfer.setOnClickListener(v -> { setupMode = "TRASLADO"; showSetup(); });
        buffer.setOnClickListener(v -> { setupMode = "BUFFER"; showSetup(); });
        manual.setOnClickListener(v -> { setupMode = "MANUAL"; showSetup(); });

        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, rh(48, 40), 1f);
        lp1.setMargins(dp(2), 0, dp(2), 0);
        row.addView(transfer, lp1);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, rh(48, 40), 1f);
        lp2.setMargins(dp(2), 0, dp(2), 0);
        row.addView(manual, lp2);
        LinearLayout.LayoutParams lp3 = new LinearLayout.LayoutParams(0, rh(48, 40), 1f);
        lp3.setMargins(dp(2), 0, dp(2), 0);
        row.addView(buffer, lp3);
        return row;
    }

    private View bufferNumberControl() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(6), dp(4), dp(6), dp(4));
        row.setBackground(box(Color.WHITE, C_GREEN, 12));

        TextView lbl = tv(compactPda() ? "BUFFER" : "TARIMAS BUFFER", rsp(18, 14), C_DARK, true);
        row.addView(lbl, new LinearLayout.LayoutParams(0, rh(56, 42), 1f));

        Button minus = button("−", C_DISABLED, C_DARK);
        TextView value = tv(String.valueOf(setupBufferPallets), rsp(30, 23), C_DARK, true);
        value.setTag("setupBuffer");
        value.setGravity(Gravity.CENTER);
        Button plus = button("+", C_GREEN, Color.WHITE);

        minus.setOnClickListener(v -> {
            setupBufferPallets = Math.max(1, setupBufferPallets - 1);
            updateSetupValuesFromRoot();
        });
        plus.setOnClickListener(v -> {
            setupBufferPallets = Math.min(10, setupBufferPallets + 1);
            updateSetupValuesFromRoot();
        });

        row.addView(minus, new LinearLayout.LayoutParams(rh(58, 44), rh(50, 40)));
        row.addView(value, new LinearLayout.LayoutParams(rh(70, 48), rh(50, 40)));
        row.addView(plus, new LinearLayout.LayoutParams(rh(58, 44), rh(50, 40)));
        return row;
    }

    private View numberControl(String label, TextView value, boolean left) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(compactPda() ? 6 : 10), dp(compactPda() ? 4 : 8), dp(compactPda() ? 6 : 10), dp(compactPda() ? 4 : 8));
        row.setBackground(box(Color.WHITE, C_BORDER, 12));

        String shown = compactPda() ? (left ? "IZQ." : "DER.") : label;
        TextView lbl = tv(shown, rsp(18, 14), C_DARK, true);
        lbl.setSingleLine(true);
        row.addView(lbl, new LinearLayout.LayoutParams(0, rh(56, 42), 1f));
        Button minus = button("−", C_DISABLED, C_DARK);
        Button plus = button("+", C_BLUE, Color.WHITE);
        row.addView(minus, new LinearLayout.LayoutParams(rh(58, 44), rh(50, 40)));
        value.setGravity(Gravity.CENTER);
        value.setSingleLine(true);
        row.addView(value, new LinearLayout.LayoutParams(rh(70, 48), rh(50, 40)));
        row.addView(plus, new LinearLayout.LayoutParams(rh(58, 44), rh(50, 40)));

        minus.setOnClickListener(v -> {
            if (left) setupLeft = Math.max(0, setupLeft - 1); else setupRight = Math.max(0, setupRight - 1);
            updateSetupValuesFromRoot();
        });
        plus.setOnClickListener(v -> {
            if (left) setupLeft = Math.min(10, setupLeft + 1); else setupRight = Math.min(10, setupRight + 1);
            updateSetupValuesFromRoot();
        });
        value.setTag(left ? "setupLeft" : "setupRight");
        return row;
    }

    private void updateSetupValues(TextView left, TextView right) {
        left.setText(String.valueOf(setupLeft));
        right.setText(String.valueOf(setupRight));
    }

    private void updateSetupValuesFromRoot() {
        View root = findViewById(android.R.id.content);
        TextView l = findTagged(root, "setupLeft");
        TextView d = findTagged(root, "setupRight");
        TextView b = findTagged(root, "setupBuffer");
        TextView p = findTagged(root, "transferPlan");
        if (l != null) l.setText(String.valueOf(setupLeft));
        if (d != null) d.setText(String.valueOf(setupRight));
        if (b != null) b.setText(String.valueOf(setupBufferPallets));
        if (p != null) p.setText(transferPlanSummary());
    }

    private TextView findTagged(View root, String tag) {
        if (root == null) return null;
        if (tag.equals(root.getTag()) && root instanceof TextView) return (TextView) root;
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                TextView x = findTagged(g.getChildAt(i), tag);
                if (x != null) return x;
            }
        }
        return null;
    }

    private void operatorHeader(LinearLayout screen, boolean pallets) {
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(8), dp(3), dp(4), dp(3));
        header.setBackground(box(C_DARK, C_DARK, 10));
        header.addView(tv(engine.containerId, rsp(20, 16), Color.WHITE, true),
                new LinearLayout.LayoutParams(0, rh(48, 38), 1f));
        Button supervisor = button("SUPERVISOR", Color.WHITE, C_DARK);
        supervisor.setOnClickListener(v -> showSupervisor());
        header.addView(supervisor, new LinearLayout.LayoutParams(dp(102), rh(42, 36)));
        screen.addView(header);
        LinearLayout tabs = new LinearLayout(this);
        Button scan = button("ESCANEAR", pallets ? Color.WHITE : C_BLUE, pallets ? C_BLUE : Color.WHITE);
        Button list = button("TARIMAS", pallets ? C_BLUE : Color.WHITE, pallets ? Color.WHITE : C_BLUE);
        scan.setOnClickListener(v -> showContinuousOperator());
        list.setOnClickListener(v -> showOperatorPallets());
        tabs.addView(scan, new LinearLayout.LayoutParams(0, rh(44, 38), 1f));
        tabs.addView(list, new LinearLayout.LayoutParams(0, rh(44, 38), 1f));
        screen.addView(spacer(4));
        screen.addView(tabs);
        progressText = tv("", rsp(18, 15), C_DARK, true);
        progressText.setGravity(Gravity.CENTER);
        int[] progress = engine.progress();
        progressText.setText("Contenedor: " + progress[0] + " / " + progress[1] + " cajas");
        screen.addView(progressText, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rh(36, 30)));
    }

    private void showContinuousOperator() {
        if (engine == null) { showHome(); return; }
        inSupervisor = false;
        inPalletView = false;
        mapGrid = null;
        activePositionButton = null;
        LinearLayout screen = root();
        operatorHeader(screen, false);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content);
        screen.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        scanInput = new EditText(this);
        scanInput.setSingleLine(true);
        scanInput.setTextSize(rsp(20, 17));
        scanInput.setHint(storageBlocked ? "CAPTURA BLOQUEADA: REVISAR GUARDADO" : "ESCANEAR CAJA");
        scanInput.setEnabled(!storageBlocked);
        scanInput.setGravity(Gravity.CENTER);
        scanInput.setBackground(box(Color.WHITE, C_BLUE, 10));
        scanInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
        scanInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        scanInput.setShowSoftInputOnFocus(false);
        scanInput.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode != KeyEvent.KEYCODE_ENTER) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) processScan();
            return true;
        });
        scanInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE && event == null) { processScan(); return true; }
            return false;
        });
        content.addView(scanInput, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rh(56, 46)));
        content.addView(spacer(5));

        LinearLayout resultBox = new LinearLayout(this);
        resultBox.setOrientation(LinearLayout.VERTICAL);
        resultBox.setGravity(Gravity.CENTER);
        resultBox.setPadding(dp(6), dp(8), dp(6), dp(8));
        resultBox.setTag("resultBox");
        resultBox.setBackground(box(C_LIGHT_BLUE, C_BLUE, 12));
        positionResult = tv("LISTA", rsp(54, 40), C_BLUE, true);
        statusResult = tv("ESCANEE UNA CAJA", rsp(19, 15), C_DARK, true);
        codeResult = tv("", rsp(15, 12), C_GRAY, false);
        countResult = tv("", rsp(20, 16), C_DARK, true);
        for (TextView field : new TextView[]{positionResult, statusResult, codeResult, countResult}) {
            field.setGravity(Gravity.CENTER);
            resultBox.addView(field);
        }
        // Altura por contenido: nunca recortar las instrucciones en una Q9 estrecha.
        content.addView(resultBox);
        if (!lastPosition.isEmpty() && engine.palletScannedCount(lastPosition) > 0) {
            positionResult.setText(lastPosition);
            statusResult.setText("DESTINO DE LA ÚLTIMA LECTURA");
            countResult.setText("Tarima: " + engine.palletScannedCount(lastPosition) + " / "
                    + engine.expectedForPallet(lastPosition) + " previstas");
        }
        pressureText = tv("", rsp(16, 13), C_BLUE, true);
        pressureText.setGravity(Gravity.CENTER);
        pressureText.setPadding(0, dp(6), 0, dp(6));
        content.addView(pressureText);
        changeTransferButton = button("CAMBIAR TRASLADO", C_BLUE, Color.WHITE);
        changeTransferButton.setOnClickListener(v -> changeTransfer());
        content.addView(changeTransferButton, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rh(52, 44)));
        TextView tripHint = tv("Pulse al sustituir físicamente la TR. No detiene los siguientes escaneos.", rsp(12, 10), C_GRAY, false);
        tripHint.setPadding(dp(3), dp(3), dp(3), dp(5));
        content.addView(tripHint);
        pendingReadyBox = new LinearLayout(this);
        pendingReadyBox.setOrientation(LinearLayout.VERTICAL);
        content.addView(pendingReadyBox);
        Button trips = button("VER TRASLADOS Y DESTINOS", Color.WHITE, C_BLUE);
        trips.setOnClickListener(v -> showTransferList());
        content.addView(trips, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rh(44, 38)));
        recentText = tv("", rsp(12, 10), C_GRAY, false);
        recentText.setPadding(dp(4), dp(8), dp(4), dp(8));
        content.addView(recentText);
        setContentView(screen);
        refreshOperator();
        focusScanner();
    }

    private void showOperatorPallets() {
        if (engine == null) { showHome(); return; }
        inSupervisor = false;
        inPalletView = true;
        scanInput = null;
        mapGrid = null;
        changeTransferButton = null;
        pressureText = null;
        pendingReadyBox = null;
        recentText = null;
        LinearLayout screen = root();
        operatorHeader(screen, true);
        TextView hint = tv("Consulta de tarimas · vuelva a ESCANEAR para leer cajas", rsp(12, 10), C_GRAY, false);
        screen.addView(hint);
        Button filter = button(showAllPallets ? "MOSTRAR SOLO ACTIVAS" : "VER TODAS / HISTORIAL", Color.WHITE, C_BLUE);
        filter.setOnClickListener(v -> { showAllPallets = !showAllPallets; showOperatorPallets(); });
        screen.addView(filter, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rh(44, 38)));
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        List<UnloadEngine.FinalPalletView> views = engine.finalPalletViews();
        addFinalPalletSection(content, "AL PIE DEL CONTENEDOR", true, views);
        content.addView(spacer(8));
        addFinalPalletSection(content, "TENDIDO FINAL", false, views);
        TextView plan = tv("Preparación inicial: " + engine.plannedTendidoPalletCount() + " tendido · "
                + engine.initialDirectFootPalletCount() + " al pie · 1 traslado", rsp(13, 11), C_GRAY, false);
        plan.setPadding(dp(4), dp(12), dp(4), dp(12));
        content.addView(plan);
        scroll.addView(content);
        screen.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(screen);
    }

    private void showOperator() {
        if (engine == null) { showHome(); return; }
        if (engine.isTransferMode()) { showContinuousOperator(); return; }
        inSupervisor = false;
        inPalletView = false;
        LinearLayout r = root();

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView cont = tv(engine.containerId, rsp(20, 16), Color.WHITE, true);
        header.setPadding(dp(compactPda() ? 8 : 12), dp(compactPda() ? 3 : 6), dp(compactPda() ? 4 : 8), dp(compactPda() ? 3 : 6));
        header.setBackground(box(C_DARK, C_DARK, 10));
        header.addView(cont, new LinearLayout.LayoutParams(0, rh(52, 42), 1f));
        Button supervisor = button("SUPERVISOR", Color.WHITE, C_DARK);
        supervisor.setOnClickListener(v -> showSupervisor());
        header.addView(supervisor, new LinearLayout.LayoutParams(dp(compactPda() ? 102 : 126), rh(42, 36)));
        r.addView(header);

        progressText = tv("", rsp(17, 13), C_GRAY, true);
        progressText.setGravity(Gravity.CENTER);
        r.addView(progressText, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rh(36, 26)));

        if (engine.isManualMode()) {
            activePositionButton = button("TARIMA ACTIVA", C_BLUE, Color.WHITE);
            activePositionButton.setOnClickListener(v -> showManualPositionPicker());
            r.addView(activePositionButton, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rh(46, 38)));
            Button temporal = button("TEMPORAL DE ESTA TARIMA", Color.WHITE, C_BLUE);
            temporal.setBackground(box(Color.WHITE, C_BLUE, 9));
            temporal.setOnClickListener(v -> showTemporalDialog(engine.getManualActivePosition(), false, null));
            r.addView(temporal, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rh(42, 34)));
            TextView manualHint = tv("La temporal puede capturarse al inicio o antes de liberar la tarima", rsp(11, 9), C_GRAY, false);
            manualHint.setGravity(Gravity.CENTER);
            r.addView(manualHint, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rh(22, 18)));
        } else {
            activePositionButton = null;
        }

        scanInput = new EditText(this);
        scanInput.setSingleLine(true);
        scanInput.setTextSize(rsp(18, 15));
        scanInput.setHint("ESCANEAR CAJA");
        scanInput.setGravity(Gravity.CENTER);
        scanInput.setBackground(box(Color.WHITE, C_BLUE, 10));
        scanInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
        scanInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        if (android.os.Build.VERSION.SDK_INT >= 21) scanInput.setShowSoftInputOnFocus(false);
        scanInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN)) {
                processScan();
                return true;
            }
            return false;
        });
        scanInput.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN) {
                processScan();
                return true;
            }
            return false;
        });
        r.addView(scanInput, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rh(54, 44)));
        r.addView(spacer(compactPda() ? 3 : 8));

        LinearLayout resultBox = new LinearLayout(this);
        resultBox.setOrientation(LinearLayout.VERTICAL);
        resultBox.setGravity(Gravity.CENTER);
        resultBox.setPadding(dp(compactPda() ? 4 : 8), dp(compactPda() ? 4 : 8), dp(compactPda() ? 4 : 8), dp(compactPda() ? 4 : 8));
        resultBox.setTag("resultBox");
        resultBox.setBackground(box(C_LIGHT_BLUE, C_BLUE, 14));
        positionResult = tv("LISTA", rsp(54, 36), C_BLUE, true);
        positionResult.setGravity(Gravity.CENTER);
        statusResult = tv("ESCANEE UNA CAJA", rsp(19, 14), C_DARK, true);
        statusResult.setGravity(Gravity.CENTER);
        codeResult = tv("", rsp(15, 11), C_GRAY, false);
        codeResult.setGravity(Gravity.CENTER);
        countResult = tv("", rsp(20, 15), C_DARK, true);
        countResult.setGravity(Gravity.CENTER);
        resultBox.addView(positionResult);
        resultBox.addView(statusResult);
        resultBox.addView(codeResult);
        resultBox.addView(countResult);
        r.addView(resultBox, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                engine.isManualMode() ? rh(150, 102) : rh(166, 112)));

        pressureText = tv("", rsp(14, 11), C_GRAY, true);
        pressureText.setGravity(Gravity.CENTER);
        r.addView(pressureText, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rh(32, 24)));

        pendingReadyBox = new LinearLayout(this);
        pendingReadyBox.setOrientation(LinearLayout.VERTICAL);
        r.addView(pendingReadyBox);

        ScrollView scroll = new ScrollView(this);
        LinearLayout lower = new LinearLayout(this);
        lower.setOrientation(LinearLayout.VERTICAL);
        TextView mapTitle = tv(engine.isTransferMode() ? "TENDIDO FINAL · PLAN PRELIMINAR"
                : (engine.isBufferMode() ? "BUFFER · 1 SECTOR = 1 CÓDIGO" : "MAPA DE POSICIONES"),
                rsp(16, 13), C_DARK, true);
        lower.addView(mapTitle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rh(30, 24)));
        mapGrid = new GridLayout(this);
        mapGrid.setColumnCount(2);
        mapGrid.setUseDefaultMargins(false);
        lower.addView(mapGrid);
        lower.addView(spacer(8));
        TextView recentTitle = tv("ÚLTIMOS 5 EVENTOS", rsp(14, 12), C_DARK, true);
        lower.addView(recentTitle);
        recentText = tv("", rsp(13, 11), C_GRAY, false);
        recentText.setPadding(dp(8), dp(6), dp(8), dp(12));
        recentText.setBackground(box(Color.WHITE, C_BORDER, 8));
        lower.addView(recentText);
        scroll.addView(lower);
        r.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(r);
        refreshOperator();
        focusScanner();
    }

    private void processScan() {
        if (scanInput == null || engine == null || inSupervisor || inPalletView || operationDialogs > 0 || storageBlocked) return;
        String raw = scanInput.getText().toString();
        scanInput.setText("");
        if (raw.trim().isEmpty()) { scanInput.requestFocus(); return; }

        ScanResult result = engine.isTransferMode()
                ? engine.scanTransfer(raw)
                : engine.isBufferMode()
                ? engine.scanBuffer(raw)
                : (engine.isManualMode()
                    ? engine.scanManual(raw, engine.getManualActivePosition(), false)
                    : engine.scan(raw));
        try {
            db.saveScanAndEngine(result, engine);
        } catch (Exception failure) {
            restoreAfterSaveFailure();
            return;
        }

        if (engine.isManualMode() && "CÓDIGO EN OTRA TARIMA".equals(result.status)) {
            showCodeAlreadyOnOtherPallet(raw, result);
            return;
        }

        if (result.ok) {
            lastPosition = result.position;
        }
        showScanResult(result);
        refreshOperator();
        focusScanner();
    }

    private void showManualPositionPicker() {
        if (engine == null || !engine.isManualMode()) return;
        ArrayList<String> labels = new ArrayList<>();
        for (Position p : engine.positions) {
            if (p.enabled && !p.waitingRemoval) {
                labels.add(p.label() + (p.isFree() ? " · LIBRE" : " · " + p.boxesOnCurrentPallet + " cajas"));
            }
        }
        if (labels.isEmpty()) {
            Toast.makeText(this, "No hay posiciones disponibles. Solicite al supervisor habilitar una.", Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Seleccionar tarima activa")
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    String label = labels.get(which).substring(0, 3);
                    ActionResult a = engine.setManualActivePosition(label);
                    if (a.ok) {
                        db.insertSystemEvent("TARIMA ACTIVA", label, a.message);
                        saveQuietly();
                        refreshOperator();
                    }
                    focusScanner();
                })
                .setNegativeButton("Cancelar", (d, w) -> focusScanner())
                .show();
    }

    private void showCodeAlreadyOnOtherPallet(String rawScan, ScanResult warning) {
        final String selected = engine.getManualActivePosition();
        final String existing = warning.position == null ? "" : warning.position;

        showScanResult(warning);
        refreshOperator();

        String msg = warning.code + "\\n\\nEste código ya tiene cajas en " + existing
                + ".\\nTarima seleccionada: " + selected
                + "\\n\\nPara evitar dispersarlo, usa la tarima existente. "
                + "Solo divide el código si físicamente es necesario.";

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("⚠ CÓDIGO YA UBICADO")
                .setMessage(msg)
                .setNegativeButton("CANCELAR", (d, w) -> focusScanner())
                .setNeutralButton("DIVIDIR " + selected, null)
                .setPositiveButton("USAR " + existing, null)
                .create();

        dialog.setOnShowListener(d -> {
            Button useExisting = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            Button split = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);

            useExisting.setOnClickListener(v -> {
                ActionResult a = engine.setManualActivePosition(existing);
                if (!a.ok) {
                    Toast.makeText(this, a.message, Toast.LENGTH_LONG).show();
                    return;
                }
                ScanResult accepted = engine.scanManual(rawScan, existing, false);
                db.insertScanEvent(accepted);
                if (accepted.ok) {
                    db.insertSystemEvent("REDIRECCIÓN CÓDIGO", existing,
                            warning.code + " redirigido desde " + selected + " a " + existing);
                    saveQuietly();
                    lastPosition = accepted.position;
                }
                dialog.dismiss();
                showScanResult(accepted);
                refreshOperator();
                focusScanner();
            });

            split.setOnClickListener(v -> {
                new AlertDialog.Builder(this)
                        .setTitle("Confirmar división")
                        .setMessage(warning.code + " quedará distribuido entre " + existing + " y " + selected + ".")
                        .setNegativeButton("No dividir", (x, y) -> focusScanner())
                        .setPositiveButton("SÍ, DIVIDIR", (x, y) -> {
                            ScanResult accepted = engine.scanManual(rawScan, selected, true);
                            db.insertScanEvent(accepted);
                            if (accepted.ok) {
                                db.insertSystemEvent("DIVISIÓN CONFIRMADA", selected,
                                        warning.code + " dividido: " + existing + " + " + selected);
                                saveQuietly();
                                lastPosition = accepted.position;
                            }
                            dialog.dismiss();
                            showScanResult(accepted);
                            refreshOperator();
                            focusScanner();
                        })
                        .show();
            });
        });
        dialog.setOnDismissListener(d -> focusScanner());
        dialog.show();
    }

    private void showScanResult(ScanResult x) {
        LinearLayout resultBox = (LinearLayout) findTaggedView(findViewById(android.R.id.content), "resultBox");
        int fill, border, main;
        if (x.ok) {
            if ("TARIMA COMPLETA".equals(x.status) || "DIRECTO A DEFINITIVA".equals(x.status)
                    || "TARIMA LISTA".equals(x.status)) {
                fill = C_LIGHT_GREEN; border = C_GREEN; main = C_GREEN;
                tones.startTone(ToneGenerator.TONE_PROP_ACK, 130);
                vibrateOk();
            } else {
                fill = C_LIGHT_BLUE; border = C_BLUE; main = C_BLUE;
                tones.startTone(ToneGenerator.TONE_PROP_BEEP, 90);
                vibrateOk();
            }
        } else if ("DUPLICADA".equals(x.status)) {
            fill = C_LIGHT_RED; border = C_RED; main = C_RED;
            tones.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 250);
            vibrateDuplicate();
        } else {
            fill = C_LIGHT_ORANGE; border = C_ORANGE; main = C_ORANGE;
            tones.startTone(ToneGenerator.TONE_CDMA_ABBR_ALERT, 220);
            vibrateError();
        }
        if (resultBox != null) resultBox.setBackground(box(fill, border, 14));

        if (x.ok) {
            positionResult.setText(x.position);
            positionResult.setTextColor(main);
            statusResult.setText(x.message);
            codeResult.setText((x.normalizedBarcode == null || x.normalizedBarcode.isEmpty()) ? x.code : x.normalizedBarcode);
            countResult.setText(engine.isTransferMode()
                    ? "Tarima: " + engine.palletScannedCount(x.finalPallet) + " / " + engine.expectedForPallet(x.finalPallet) + " previstas"
                    : x.received + " / " + x.expected);
        } else if ("DUPLICADA".equals(x.status)) {
            // La posición original es la información más útil si el operador olvidó dónde colocarla.
            positionResult.setText(x.position == null || x.position.isEmpty() ? "⛔" : x.position);
            positionResult.setTextColor(main);
            statusResult.setText("YA ESCANEADA");
            String msg = x.normalizedBarcode == null ? "" : x.normalizedBarcode;
            if (x.firstScanTime != null && !x.firstScanTime.isEmpty()) msg += " · " + x.firstScanTime;
            codeResult.setText(msg);
            countResult.setText("NO CONTABILIZAR DE NUEVO");
        } else if ("CÓDIGO EN OTRA TARIMA".equals(x.status)) {
            positionResult.setText(x.position == null || x.position.isEmpty() ? "⚠" : x.position);
            positionResult.setTextColor(C_ORANGE);
            statusResult.setText("CÓDIGO YA UBICADO");
            codeResult.setText(x.code == null ? "" : x.code);
            countResult.setText("NO AGREGADA TODAVÍA");
        } else if ("FUERA DE RANGO".equals(x.status)) {
            positionResult.setText("⚠");
            positionResult.setTextColor(main);
            statusResult.setText("FUERA DE RANGO · POSIBLE SOBRANTE");
            codeResult.setText(x.message == null ? "" : x.message);
            countResult.setText("NO CONTABILIZADA");
        } else if (x.status != null && x.status.startsWith("LECTURA")) {
            positionResult.setText("⚠");
            positionResult.setTextColor(main);
            statusResult.setText(x.status);
            codeResult.setText(x.message == null ? "" : x.message);
            countResult.setText("VOLVER A ESCANEAR");
        } else {
            positionResult.setText(x.position == null || x.position.isEmpty() ? "⛔" : x.position);
            positionResult.setTextColor(main);
            statusResult.setText(x.status);
            codeResult.setText(x.message == null ? "" : x.message);
            countResult.setText(x.expected > 0 ? x.received + " / " + x.expected : "NO COLOCAR");
        }
    }

    private View findTaggedView(View root, String tag) {
        if (root == null) return null;
        if (tag.equals(root.getTag())) return root;
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                View x = findTaggedView(g.getChildAt(i), tag);
                if (x != null) return x;
            }
        }
        return null;
    }

    private void refreshOperator() {
        if (engine == null) return;
        int[] pg = engine.progress();
        Pressure pr = engine.pressure();
        if (progressText != null) progressText.setText((engine.isTransferMode() ? "Contenedor: " : "") + pg[0] + " / " + pg[1] + " cajas");
        if (pressureText != null) {
            if (engine.isTransferMode()) {
                pressureText.setText(engine.currentTransferPallet() + " · " + engine.currentTransferBoxCount()
                        + " cajas · pie libre: " + (engine.enabledCount(null) - engine.activeFinalPalletForFootPosition.size()));
                pressureText.setTextColor(C_BLUE);
            } else if (engine.isBufferMode()) {
                int ready = engine.bufferReadyCandidates().size();
                pressureText.setText("BUFFER " + engine.bufferOccupiedSectors() + "/" + engine.bufferTotalSectors()
                        + " sectores · " + ready + " listos · definitivas libres " + pr.free);
                int freeBuffer = engine.bufferFreeSectors();
                pressureText.setTextColor(freeBuffer <= 1 ? C_RED : (freeBuffer <= 3 ? C_ORANGE : C_GRAY));
            } else {
                pressureText.setText(pr.occupied + " ocupadas · " + pr.free + " libres · " + pr.enabled + "/20 habilitadas · presión " + pr.level);
                pressureText.setTextColor("SATURADA".equals(pr.level) || "ALTA".equals(pr.level) ? C_ORANGE : C_GRAY);
            }
        }
        if (engine.isManualMode() && activePositionButton != null) {
            String active = engine.getManualActivePosition();
            Position p = engine.findPosition(active);
            String detail = "";
            if (p != null) detail = " · " + p.boxesOnCurrentPallet + " cajas · " + p.reservedCodes.size() + " cód.";
            String tmp = active.isEmpty() ? "" : db.currentTemporalForPosition(active);
            if (!tmp.isEmpty()) detail += " · TEMP " + tmp;
            activePositionButton.setText("TARIMA ACTIVA · " + (active.isEmpty() ? "SELECCIONAR" : active) + detail);
        }
        refreshPendingReady();
        if (changeTransferButton != null && engine.isTransferMode()) {
            changeTransferButton.setEnabled(!storageBlocked && engine.currentTransferBoxCount() > 0);
        }
        refreshMap();
        refreshRecent();
    }

    private void refreshPendingReady() {
        if (pendingReadyBox == null) return;
        pendingReadyBox.removeAllViews();

        if (engine.isTransferMode()) {
            int ready = 0, waitingRemoval = 0;
            for (UnloadEngine.FinalPalletView view : engine.finalPalletViews()) {
                if ("REVISAR".equals(view.status)) ready++;
                if (view.validated && !view.retired) waitingRemoval++;
            }
            if (ready + waitingRemoval > 0) {
                Button check = button(ready + " POR REVISAR · " + waitingRemoval + " POR RETIRAR", C_LIGHT_ORANGE, C_ORANGE);
                check.setOnClickListener(v -> showOperatorPallets());
                pendingReadyBox.addView(check, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rh(48, 40)));
            }
            return;
        }

        if (engine.isBufferMode()) {
            final int readyCount = engine.bufferReadyCandidates().size();
            if (readyCount > 0) {
                Button form = button("📦  FORMAR DEFINITIVA · " + readyCount + " LISTOS", C_BLUE, Color.WHITE);
                form.setOnClickListener(v -> showDefinitiveBuilder(new HashSet<>(), null));
                LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rh(52, 43));
                fp.setMargins(0, dp(4), 0, dp(4));
                pendingReadyBox.addView(form, fp);
            }
        }

        List<Position> pending = engine.pendingRemovalPositions();
        for (Position p : pending) {
            Button b = button("✓  " + p.label() + " · POSICIÓN LISTA", C_GREEN, Color.WHITE);
            b.setOnClickListener(v -> markPositionReadyWithTemporal(p.label()));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rh(50, 42));
            lp.setMargins(0, dp(4), 0, dp(4));
            pendingReadyBox.addView(b, lp);
        }
    }

    private ArrayList<String> candidateIds(List<BufferCandidate> candidates) {
        ArrayList<String> ids = new ArrayList<>();
        if (candidates != null) for (BufferCandidate c : candidates) ids.add(c.id);
        return ids;
    }

    /**
     * Pantalla deliberadamente simple: la app propone y el operador solo confirma realidad física.
     * NO CABE quita ese bloque y busca sustitutos; no existe límite fijo de cantidad de códigos.
     */
    private void showDefinitiveBuilder(Set<String> rejectedIds, List<String> lockedIds) {
        if (engine == null || !engine.isBufferMode()) return;
        inSupervisor = true;

        final HashSet<String> rejected = new HashSet<>();
        if (rejectedIds != null) rejected.addAll(rejectedIds);

        List<BufferCandidate> draft = lockedIds == null
                ? engine.suggestDefinitive(rejected)
                : engine.suggestDefinitiveWithLocked(lockedIds, rejected, true);

        if (draft == null || draft.isEmpty()) {
            Toast.makeText(this, "No hay códigos/bloques listos para formar definitiva", Toast.LENGTH_LONG).show();
            showOperator();
            return;
        }

        LinearLayout r = root();
        TextView title = tv("FORMAR TARIMA DEFINITIVA", rsp(23, 18), C_DARK, true);
        title.setGravity(Gravity.CENTER);
        r.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rh(46, 38)));

        double totalCbm = 0.0;
        int totalBoxes = 0;
        for (BufferCandidate c : draft) { totalCbm += c.cbm; totalBoxes += c.boxes; }

        TextView summary = tv(draft.size() + " código/bloque(s) · " + totalBoxes + " cajas · "
                + String.format(Locale.getDefault(), "%.2f m³ teóricos", totalCbm)
                + "\nPruebe físicamente manteniendo los códigos visibles.",
                rsp(15, 12), C_DARK, true);
        summary.setGravity(Gravity.CENTER);
        summary.setPadding(dp(8), dp(7), dp(8), dp(7));
        summary.setBackground(box(C_LIGHT_BLUE, C_BLUE, 10));
        r.addView(summary);
        r.addView(spacer(6));

        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);

        final ArrayList<String> currentIds = candidateIds(draft);
        for (BufferCandidate c : draft) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(7), dp(5), dp(7), dp(5));
            row.setBackground(box(Color.WHITE, C_BORDER, 9));

            String source = c.sourceText();
            TextView details = tv(source + "\n" + c.code + "\n" + c.boxes + " cajas · "
                    + String.format(Locale.getDefault(), "%.2f m³", c.cbm) + " · " + c.reason,
                    rsp(13, 10), C_DARK, true);
            row.addView(details, new LinearLayout.LayoutParams(0, rh(72, 60), 1f));

            Button noFit = button("NO\nCABE", C_ORANGE, Color.WHITE);
            noFit.setOnClickListener(v -> {
                HashSet<String> nextRejected = new HashSet<>(rejected);
                nextRejected.add(c.id);
                ArrayList<String> keep = new ArrayList<>(currentIds);
                keep.remove(c.id);
                showDefinitiveBuilder(nextRejected, keep);
            });
            row.addView(noFit, new LinearLayout.LayoutParams(dp(compactPda() ? 64 : 82), rh(58, 50)));

            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rp.setMargins(0, dp(3), 0, dp(3));
            list.addView(row, rp);
        }
        scroll.addView(list);
        r.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        Button tryMore = button("+  PROBAR OTRO CÓDIGO", Color.WHITE, C_BLUE);
        tryMore.setBackground(box(Color.WHITE, C_BLUE, 10));
        tryMore.setOnClickListener(v -> {
            List<BufferCandidate> more = engine.suggestDefinitiveWithLocked(currentIds, rejected, true);
            if (more.size() <= currentIds.size()) {
                Toast.makeText(this, "No hay otro bloque compatible dentro del CBM teórico", Toast.LENGTH_SHORT).show();
            } else {
                showDefinitiveBuilder(rejected, candidateIds(more));
            }
        });
        r.addView(tryMore, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rh(50, 42)));
        r.addView(spacer(5));

        Button confirm = button("✓  CABEN · FORMAR DEFINITIVA", C_GREEN, Color.WHITE);
        confirm.setOnClickListener(v -> {
            ActionResult a = engine.formDefinitiveFromBuffer(currentIds);
            if (!a.ok) {
                Toast.makeText(this, a.message, Toast.LENGTH_LONG).show();
                return;
            }
            db.insertSystemEvent("DEFINITIVA FORMADA", a.position, a.message);
            saveQuietly();
            Toast.makeText(this, a.message, Toast.LENGTH_LONG).show();
            showOperator();
        });
        r.addView(confirm, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rh(58, 48)));
        r.addView(spacer(5));

        Button cancel = button("CANCELAR · DEJAR EN BUFFER", Color.WHITE, C_GRAY);
        cancel.setBackground(box(Color.WHITE, C_BORDER, 10));
        cancel.setOnClickListener(v -> showOperator());
        r.addView(cancel, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rh(46, 38)));

        setContentView(r);
    }

    private void refreshMap() {
        if (mapGrid == null) return;
        mapGrid.removeAllViews();

        // Operador en BUFFER ve sectores Bxx-A..D. Supervisor ve posiciones definitivas I/D.
        if (engine.isBufferMode() && !inSupervisor) {
            for (BufferSector sector : engine.bufferSectors()) addBufferCard(sector);
            return;
        }

        List<PositionCard> cards = engine.positionCards();
        for (int slot = 1; slot <= 10; slot++) {
            addCard(findCard(cards, "I", slot));
            addCard(findCard(cards, "D", slot));
        }
    }

    private void addBufferCard(BufferSector s) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setPadding(dp(3), dp(3), dp(3), dp(3));

        boolean free = s == null || s.isFree();
        boolean ready = false;
        if (!free) {
            for (BufferCandidate c : engine.bufferReadyCandidates()) {
                if (c.sectorLabels.contains(s.label())) { ready = true; break; }
            }
        }

        int fill = free ? Color.WHITE : (ready || s.codeComplete ? C_LIGHT_GREEN : C_LIGHT_BLUE);
        int stroke = free ? C_BORDER : (ready || s.codeComplete ? C_GREEN : C_BLUE);
        if (s != null && s.label().equals(lastPosition)) stroke = C_DARK;
        card.setBackground(box(fill, stroke, 8));

        TextView label = tv(s == null ? "" : s.label(), rsp(18, 14), C_DARK, true);
        label.setGravity(Gravity.CENTER);
        card.addView(label);

        if (free) {
            TextView state = tv("LIBRE", rsp(11, 9), C_GRAY, true);
            state.setGravity(Gravity.CENTER);
            card.addView(state);
        } else {
            com.ilubox.descargapda.core.CodeRecord rec = engine.records.get(s.code);
            Integer registered = engine.received.get(s.code);
            int global = registered == null ? 0 : registered;
            int expected = rec == null ? 0 : rec.boxes;
            String shortCode = s.code.length() > 12 ? s.code.substring(s.code.length() - 12) : s.code;
            TextView code = tv(shortCode, rsp(10, 8), C_DARK, true);
            code.setGravity(Gravity.CENTER);
            code.setSingleLine(true);
            card.addView(code);
            TextView detail = tv(s.boxes + " aquí · " + global + "/" + expected
                    + (ready ? " · LISTO" : ""), rsp(10, 8), ready ? C_GREEN : C_GRAY, true);
            detail.setGravity(Gravity.CENTER);
            detail.setSingleLine(true);
            card.addView(detail);
        }

        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        lp.width = Math.max(dp(compactPda() ? 92 : 120), (screenWidth - dp(compactPda() ? 18 : 32)) / 2);
        lp.height = rh(64, 52);
        lp.setMargins(dp(2), dp(2), dp(2), dp(2));
        mapGrid.addView(card, lp);
    }

    private PositionCard findCard(List<PositionCard> cards, String side, int slot) {
        for (PositionCard c : cards) if (c.side.equals(side) && c.slot == slot) return c;
        return null;
    }

    private void addCard(PositionCard c) {
        if (c == null) return;
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setPadding(dp(4), dp(4), dp(4), dp(4));

        int fill = Color.WHITE, stroke = C_BORDER, txt = C_DARK;
        if ("NO HABILITADA".equals(c.state)) { fill = C_DISABLED; stroke = C_DISABLED; txt = C_GRAY; }
        else if ("LIBRE".equals(c.state)) { fill = Color.WHITE; stroke = C_BORDER; }
        else if ("EN PROCESO".equals(c.state)) { fill = C_LIGHT_BLUE; stroke = C_BLUE; }
        else if ("PRÓXIMA".equals(c.state)) { fill = C_YELLOW; stroke = C_ORANGE; }
        else if ("COMPLETA".equals(c.state)) { fill = C_LIGHT_GREEN; stroke = C_GREEN; }
        if (c.label.equals(lastPosition)) stroke = C_DARK;
        if (engine != null && engine.isManualMode() && c.label.equals(engine.getManualActivePosition())) {
            stroke = C_DARK;
            fill = c.waitingRemoval ? fill : C_LIGHT_GREEN;
        }
        card.setBackground(box(fill, stroke, 8));

        TextView label = tv(c.label, rsp(18, 15), txt, true);
        label.setGravity(Gravity.CENTER);
        TextView title = tv(c.title, rsp(11, 9), txt, true);
        title.setGravity(Gravity.CENTER);
        title.setMaxLines(1);
        TextView detail = tv(c.detail, rsp(10, 8), C_GRAY, false);
        detail.setGravity(Gravity.CENTER);
        detail.setMaxLines(1);
        card.addView(label);
        card.addView(title);
        if (c.detail != null && !c.detail.isEmpty()) card.addView(detail);

        if (c.enabled && !"NO HABILITADA".equals(c.state)) {
            card.setClickable(true);
            if (engine != null && engine.isManualMode() && !c.waitingRemoval) {
                card.setOnClickListener(v -> {
                    ActionResult a = engine.setManualActivePosition(c.label);
                    if (a.ok) {
                        lastPosition = c.label;
                        db.insertSystemEvent("TARIMA ACTIVA", c.label, a.message);
                        saveQuietly();
                        refreshOperator();
                    } else Toast.makeText(this, a.message, Toast.LENGTH_SHORT).show();
                    focusScanner();
                });
                card.setOnLongClickListener(v -> {
                    showPositionDetail(c.label);
                    return true;
                });
            } else {
                card.setOnClickListener(v -> showPositionDetail(c.label));
            }
        }

        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        lp.width = Math.max(dp(compactPda() ? 92 : 120), (screenWidth - dp(compactPda() ? 18 : 32)) / 2);
        lp.height = rh(72, 56);
        lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED);
        lp.setMargins(dp(2), dp(2), dp(2), dp(2));
        mapGrid.addView(card, lp);
    }

    private String positionDetailText(Position p) {
        StringBuilder sb = new StringBuilder();
        if (p == null) return "Posición no encontrada";
        sb.append(p.label()).append(" · ");
        if (!p.enabled) sb.append("NO HABILITADA");
        else if (p.waitingRemoval) sb.append("COMPLETA / PENDIENTE DE RETIRO");
        else if (p.isFree()) sb.append("LIBRE");
        else sb.append("EN PROCESO");
        if (engine != null && engine.isManualMode() && p.label().equals(engine.getManualActivePosition())) {
            sb.append(" · ACTIVA");
        }
        sb.append("\n");

        if (p.isFree()) return sb.toString();
        sb.append("Tarima local en posición: ").append(p.palletSeq + 1).append(" · ").append(p.kind).append("\n");
        if (engine != null && engine.isManualMode()) {
            String tmp = db.currentTemporalForPosition(p.label());
            sb.append("Temporal: ").append(tmp.isEmpty() ? "PENDIENTE" : tmp).append("\n");
        }
        sb.append("Cajas en esta tarima: ").append(p.boxesOnCurrentPallet).append("\n");
        sb.append(String.format(Locale.getDefault(), "CBM registrado: %.3f m³\n", p.actualCbm));
        double weight = engine.estimatedWeight(p);
        if (weight >= 0) sb.append(String.format(Locale.getDefault(), "Peso estimado: %.1f kg\n", weight));
        if (p.waitingRemoval && p.removalReason != null && !p.removalReason.isEmpty()) {
            sb.append("Motivo de cierre: ").append(p.removalReason).append("\n");
        }
        sb.append("\nCÓDIGOS EN TARIMA\n");
        for (String code : p.reservedCodes) {
            com.ilubox.descargapda.core.CodeRecord rec = engine.records.get(code);
            if (rec == null) continue;
            int local = p.boxesForCode(code);
            int total = engine.received.get(code);
            sb.append(code).append("  ·  ").append(local).append(" caja(s) aquí")
                    .append("  ·  total ").append(total).append("/").append(rec.boxes).append("\n");
            List<String> missing = engine.missingBoxes(code, 6);
            if (!missing.isEmpty() && total < rec.boxes) {
                sb.append("   Faltan: ");
                for (int i = 0; i < missing.size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(missing.get(i));
                }
                if (rec.boxes - total > missing.size()) sb.append(" …");
                sb.append("\n");
            }
        }
        return sb.toString().trim();
    }

    private void showPositionDetail(String label) {
        Position p = engine == null ? null : engine.findPosition(label);
        if (p == null) return;
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("Detalle " + p.label())
                .setMessage(positionDetailText(p))
                .setNegativeButton("Cerrar", (d, w) -> focusScanner());

        if (!inSupervisor) {
            if (p.waitingRemoval) {
                b.setPositiveButton("✓ POSICIÓN LISTA", (d, w) -> markPositionReadyWithTemporal(p.label()));
            } else if (!p.isFree() && p.boxesOnCurrentPallet > 0) {
                b.setPositiveButton("TARIMA LLENA / NO CABE MÁS", (d, w) -> {
                    ActionResult a = engine.closePositionEarly(p.label());
                    if (a.ok) {
                        db.insertSystemEvent("TARIMA LLENA MANUAL", a.position, a.message);
                        saveQuietly();
                        lastPosition = a.position;
                        if (engine.isManualMode()) engine.setManualActivePosition(a.position);
                        Toast.makeText(this, a.message, Toast.LENGTH_SHORT).show();
                        refreshOperator();
                    } else Toast.makeText(this, a.message, Toast.LENGTH_SHORT).show();
                    focusScanner();
                });
            }
        } else if (p.waitingRemoval) {
            b.setPositiveButton("REABRIR TARIMA", (d, w) -> {
                ActionResult a = engine.reopenPosition(p.label());
                if (a.ok) {
                    db.insertSystemEvent("TARIMA REABIERTA", a.position, a.message);
                    saveQuietly();
                    Toast.makeText(this, a.message, Toast.LENGTH_SHORT).show();
                    showSupervisor();
                } else Toast.makeText(this, a.message, Toast.LENGTH_LONG).show();
            });
        }
        b.setOnDismissListener(d -> focusScanner());
        b.show();
    }

    private void refreshRecent() {
        if (recentText == null) return;
        List<PilotDatabase.EventRow> rows = db.recentEvents(5);
        StringBuilder sb = new StringBuilder();
        for (PilotDatabase.EventRow e : rows) {
            String time = e.time == null ? "" : e.time;
            if (time.length() >= 8) time = time.substring(time.length() - 8);
            sb.append(time).append("  ");
            if (e.position != null && !e.position.isEmpty()) sb.append(e.position).append("  ");
            sb.append(e.status == null ? "" : e.status);
            if (e.code != null && !e.code.isEmpty()) sb.append(" · ").append(e.code);
            sb.append("\n");
        }
        recentText.setText(sb.toString().trim());
    }

    private void showSupervisor() {
        if (engine == null) { showHome(); return; }
        if (engine.isTransferMode()) {
            showSimpleTransferSupervisor();
            return;
        }
        inSupervisor = true;
        // Q9: cabecera fija + contenido desplazable. En V0.4 los controles del
        // supervisor excedían la altura útil de la PDA y EXPORTAR quedaba fuera
        // de alcance. El ScrollView exterior garantiza acceso a todas las acciones.
        LinearLayout screen = root();
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        TextView title = tv("SUPERVISOR", rsp(24, 19), C_DARK, true);
        header.addView(title, new LinearLayout.LayoutParams(0, rh(54, 44), 1f));
        Button op = button(compactPda() ? "OPERADOR" : "VOLVER A OPERADOR", C_BLUE, Color.WHITE);
        op.setOnClickListener(v -> showOperator());
        header.addView(op, new LinearLayout.LayoutParams(dp(compactPda() ? 98 : 180), rh(46, 38)));
        screen.addView(header);

        ScrollView pageScroll = new ScrollView(this);
        pageScroll.setFillViewport(true);
        pageScroll.setVerticalScrollBarEnabled(true);
        pageScroll.addView(r, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        screen.addView(pageScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        int[] pg = engine.progress();
        String modeName = engine.isTransferMode() ? "TRASLADO DIRIGIDO"
                : (engine.isBufferMode() ? "BUFFER MODULAR" : (engine.isManualMode() ? "MANUAL" : "AUTOMÁTICO"));
        TextView info = tv(engine.containerId + "\n" + pg[0] + " / " + pg[1] + " cajas"
                + "\nModo: " + modeName,
                rsp(18, 14), C_DARK, true);
        info.setGravity(Gravity.CENTER);
        info.setBackground(box(Color.WHITE, C_BORDER, 10));
        info.setPadding(dp(8), dp(8), dp(8), dp(8));
        r.addView(info);
        r.addView(spacer(10));

        Pressure pr = engine.pressure();
        String pressureMsg = engine.isBufferMode()
                ? ("BUFFER " + engine.bufferOccupiedSectors() + "/" + engine.bufferTotalSectors()
                    + " sectores · " + engine.bufferPalletCount() + " tarimas buffer"
                    + "\nDEFINITIVAS " + pr.occupied + " ocupadas · " + pr.free + " libres")
                : (pr.occupied + " ocupadas · " + pr.free + " libres · " + pr.enabled + "/20 habilitadas · pico " + pr.peak);
        TextView pressure = tv(pressureMsg, rsp(15, 11), C_GRAY, true);
        pressure.setGravity(Gravity.CENTER);
        r.addView(pressure);
        r.addView(spacer(compactPda() ? 5 : 10));

        // Exportación prioritaria: queda arriba de los controles de capacidad
        // para que sea accesible sin recorrer toda la pantalla del supervisor.
        Button export = button("⬇  EXPORTAR HISTORIAL CSV", C_DARK, Color.WHITE);
        export.setOnClickListener(v -> exportCsv());
        r.addView(export, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rh(46, 38)));
        r.addView(spacer(compactPda() ? 4 : 7));

        Button exportExcel = button("📊  REPORTE EXCEL · TARIMAS + PACKING", C_BLUE, Color.WHITE);
        exportExcel.setOnClickListener(v -> exportExcelReport());
        r.addView(exportExcel, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rh(46, 38)));
        r.addView(spacer(compactPda() ? 4 : 7));

        if (engine.isManualMode()) {
            String order = db.latestPutawayOrder();
            Button putaway = button(order.isEmpty() ? "ORDEN PUTAWAY WMS" : ("WMS · " + order), Color.WHITE, C_BLUE);
            putaway.setBackground(box(Color.WHITE, C_BLUE, 9));
            putaway.setOnClickListener(v -> showPutawayOrderDialog());
            r.addView(putaway, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rh(44, 36)));
            r.addView(spacer(compactPda() ? 4 : 7));

            Button exportWms = button("⬇  PLANTILLA WMS PUTAWAY", C_GREEN, Color.WHITE);
            exportWms.setOnClickListener(v -> exportWmsTemplate());
            r.addView(exportWms, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rh(46, 38)));
            TextView wmsHint = tv("Formato PutawayCrossDockImport · exige temporal en cada tarima", rsp(11, 9), C_GRAY, false);
            wmsHint.setGravity(Gravity.CENTER);
            r.addView(wmsHint, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rh(24, 18)));
        }
        r.addView(spacer(compactPda() ? 5 : 10));

        if (engine.isBufferMode()) {
            r.addView(supervisorBufferRow());
            r.addView(spacer(8));
            TextView finalLabel = tv("POSICIONES DEFINITIVAS", rsp(14, 11), C_DARK, true);
            finalLabel.setGravity(Gravity.CENTER);
            r.addView(finalLabel);
            r.addView(spacer(4));
        }

        r.addView(supervisorSideRow("IZQUIERDA", "I"));
        r.addView(spacer(8));
        r.addView(supervisorSideRow("DERECHA", "D"));
        r.addView(spacer(16));

        Button undoScan = button("↶  CORREGIR ÚLTIMO ESCANEO ACEPTADO", C_ORANGE, Color.WHITE);
        undoScan.setOnClickListener(v -> showUndoLastScanDialog());
        r.addView(undoScan, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rh(54, 44)));
        r.addView(spacer(10));

        Button newUnload = button("IMPORTAR NUEVA DESCARGA", Color.WHITE, C_RED);
        newUnload.setBackground(box(Color.WHITE, C_RED, 10));
        newUnload.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Nueva descarga")
                .setMessage("La descarga actual quedará reemplazada. Exporta primero CSV, reporte Excel y plantilla WMS si necesitas conservarlos.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Continuar", (d, which) -> chooseManifest())
                .show());
        r.addView(newUnload, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rh(54, 44)));

        r.addView(spacer(14));
        TextView mapTitle = tv(engine.isBufferMode() ? "DEFINITIVAS I/D" : "ESTADO DE POSICIONES", rsp(16, 13), C_DARK, true);
        r.addView(mapTitle);
        mapGrid = new GridLayout(this);
        mapGrid.setColumnCount(2);
        r.addView(mapGrid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        r.addView(spacer(12));
        setContentView(screen);
        refreshMap();
    }

    /** V0.10: consulta compartida y herramientas administrativas separadas de la operación. */
    private void showSimpleTransferSupervisor() {
        if (engine == null) { showHome(); return; }
        inSupervisor = true;
        inPalletView = false;
        scanInput = null;
        changeTransferButton = null;
        LinearLayout screen = root();

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = tv("SUPERVISOR", rsp(23, 18), C_DARK, true);
        header.addView(title, new LinearLayout.LayoutParams(0, rh(50, 42), 1f));
        Button op = button("OPERADOR", C_BLUE, Color.WHITE);
        op.setOnClickListener(v -> showOperator());
        header.addView(op, new LinearLayout.LayoutParams(dp(102), rh(44, 36)));
        screen.addView(header);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(r);
        screen.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        int[] pg = engine.progress();
        List<UnloadEngine.FinalPalletView> views = engine.finalPalletViews();
        int active = 0;
        for (UnloadEngine.FinalPalletView v : views) if (v.scanned > 0 && !v.retired) active++;

        TextView container = tv(engine.containerId, rsp(20, 16), C_DARK, true);
        container.setGravity(Gravity.CENTER);
        r.addView(container, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rh(34, 28)));

        GridLayout metrics = new GridLayout(this);
        metrics.setColumnCount(2);
        metrics.addView(simpleMetric("CAJAS", pg[0] + " / " + pg[1], C_BLUE));
        metrics.addView(simpleMetric("DEFINITIVAS ACTIVAS", String.valueOf(active), C_GREEN));
        metrics.addView(simpleMetric("TRASLADO ACTIVO", engine.currentTransferPallet()
                + " · " + engine.currentTransferBoxCount(), C_BLUE));
        metrics.addView(simpleMetric("INCIDENCIAS", String.valueOf(engine.transferIncidentCount),
                engine.transferIncidentCount > 0 ? C_ORANGE : C_GRAY));
        r.addView(metrics);
        TextView plan = tv("PLAN INICIAL · tendido " + engine.plannedTendidoPalletCount()
                + " · pie " + engine.initialDirectFootPalletCount() + " + 1 TR"
                + " · total físico " + engine.initialPhysicalPalletCount()
                + "\nWMS elegibles: " + engine.wmsEligibleBoxCount() + " de " + engine.acceptedBoxCount() + " escaneadas",
                rsp(13, 10), C_GRAY, true);
        plan.setGravity(Gravity.CENTER);
        plan.setPadding(dp(5), dp(4), dp(5), dp(4));
        r.addView(plan);
        r.addView(spacer(10));

        Button filter = button(showAllPallets ? "MOSTRAR SOLO ACTIVAS" : "VER TODAS / HISTORIAL", Color.WHITE, C_BLUE);
        filter.setOnClickListener(v -> { showAllPallets = !showAllPallets; showSimpleTransferSupervisor(); });
        r.addView(filter, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rh(44, 38)));
        addFinalPalletSection(r, "AL PIE DEL CONTENEDOR", true, views);
        r.addView(spacer(8));
        addFinalPalletSection(r, "TENDIDO FINAL", false, views);
        r.addView(spacer(10));

        LinearLayout transfer = new LinearLayout(this);
        transfer.setOrientation(LinearLayout.VERTICAL);
        transfer.setPadding(dp(10), dp(8), dp(10), dp(8));
        transfer.setBackground(box(C_LIGHT_BLUE, C_BLUE, 12));
        String trState = "EN FORMACIÓN";
        TextView trTitle = tv("TRASLADO ACTUAL · " + engine.currentTransferPallet(), rsp(18, 14), C_DARK, true);
        TextView trInfo = tv(engine.currentTransferBoxCount() + " cajas · " + trState
                + "\nDestinos: " + transferDestinationsText(), rsp(15, 11), C_GRAY, false);
        transfer.addView(trTitle);
        transfer.addView(trInfo);
        Button trAction = button("VER TRASLADOS Y DESTINOS", Color.WHITE, C_BLUE);
        trAction.setOnClickListener(v -> showTransferList());
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rh(50, 42));
        ap.setMargins(0, dp(7), 0, 0);
        transfer.addView(trAction, ap);
        r.addView(transfer);
        r.addView(spacer(10));

        Button exportWindows = button("EXPORTAR RESULTADO PARA WINDOWS", C_GREEN, Color.WHITE);
        exportWindows.setOnClickListener(v -> exportPdaResult());
        r.addView(exportWindows, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rh(50, 42)));
        r.addView(spacer(8));

        Button tools = button("REPORTES Y CORRECCIONES", Color.WHITE, C_DARK);
        tools.setBackground(box(Color.WHITE, C_BORDER, 9));
        tools.setOnClickListener(v -> showSimpleSupervisorTools());
        r.addView(tools, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rh(48, 40)));
        r.addView(spacer(8));

        Button newUnload = button("NUEVA DESCARGA / CARGAR LISTA", Color.WHITE, C_RED);
        newUnload.setBackground(box(Color.WHITE, C_RED, 9));
        newUnload.setOnClickListener(v -> confirmNewUnloadImport());
        r.addView(newUnload, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rh(48, 40)));
        r.addView(spacer(12));
        setContentView(screen);
    }

    private View simpleMetric(String label, String value, int accent) {
        LinearLayout boxView = new LinearLayout(this);
        boxView.setOrientation(LinearLayout.VERTICAL);
        boxView.setGravity(Gravity.CENTER);
        boxView.setPadding(dp(4), dp(5), dp(4), dp(5));
        boxView.setBackground(box(Color.WHITE, C_BORDER, 8));
        TextView v = tv(value, rsp(20, 15), accent, true);
        v.setGravity(Gravity.CENTER);
        TextView l = tv(label, rsp(11, 9), C_GRAY, true);
        l.setGravity(Gravity.CENTER);
        boxView.addView(v);
        boxView.addView(l);
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = 0;
        lp.height = rh(70, 58);
        lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        lp.setMargins(dp(2), dp(2), dp(2), dp(2));
        boxView.setLayoutParams(lp);
        return boxView;
    }

    private void addFinalPalletSection(LinearLayout parent, String title, boolean direct,
                                       List<UnloadEngine.FinalPalletView> views) {
        TextView heading = tv(title, rsp(15, 12), C_DARK, true);
        parent.addView(heading, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rh(28, 23)));
        int shown = 0;
        for (UnloadEngine.FinalPalletView v : views) {
            if (v.direct != direct || (!showAllPallets && (v.scanned <= 0 || v.retired))) continue;
            shown++;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(9), dp(4), dp(9), dp(4));
            row.setMinimumHeight(rh(68, 56));
            int border = v.retired ? C_GRAY : v.validated ? C_GREEN : "REVISAR".equals(v.status) ? C_ORANGE : C_BLUE;
            row.setBackground(box(Color.WHITE, border, 9));
            String shownId = v.label + (v.physicalPosition == null || v.physicalPosition.isEmpty()
                    ? "" : " · " + v.physicalPosition + (v.retired ? " (anterior)" : ""));
            LinearLayout top = new LinearLayout(this);
            top.setGravity(Gravity.CENTER_VERTICAL);
            top.addView(tv(shownId, rsp(19, 16), border, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView state = tv(v.status, rsp(12, 10), border, true);
            top.addView(state);
            row.addView(top);
            row.addView(tv("Registradas: " + v.scanned + " · previstas: " + v.expected, rsp(16, 13), C_DARK, true));
            if (!v.closureReason.isEmpty()) row.addView(tv("Cierre parcial · previsión inicial " + v.originalExpected, rsp(12, 10), C_ORANGE, false));
            row.setOnClickListener(x -> showFinalPalletDetail(v.label));
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rp.setMargins(0, dp(2), 0, dp(2));
            parent.addView(row, rp);
        }
        if (shown == 0) {
            TextView empty = tv("Sin tarimas activas · consulte Todas para ver el plan", rsp(13, 10), C_GRAY, false);
            empty.setPadding(dp(8), dp(5), dp(8), dp(5));
            parent.addView(empty);
        }
    }

    private String transferDestinationsText() {
        LinkedHashMap<String, Integer> destinations = engine.currentTransferDestinations();
        if (destinations.isEmpty()) return "—";
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, Integer> e : destinations.entrySet()) {
            if (out.length() > 0) out.append(" · ");
            out.append(e.getKey()).append(" (").append(e.getValue()).append(")");
        }
        return out.toString();
    }

    private void showFinalPalletDetail(String label) {
        UnloadEngine.FinalPalletView found = null;
        for (UnloadEngine.FinalPalletView v : engine.finalPalletViews()) if (v.label.equals(label)) found = v;
        if (found == null) return;
        final UnloadEngine.FinalPalletView selected = found;
        StringBuilder msg = new StringBuilder();
        msg.append(selected.direct ? "Formación: al pie del contenedor" : "Formación: tendido final");
        if (selected.direct && selected.physicalPosition != null && !selected.physicalPosition.isEmpty()) {
            msg.append(" · ").append(selected.physicalPosition).append(selected.retired ? " (anterior)" : "");
        }
        msg.append("\nRegistradas: ").append(selected.scanned).append(" / ").append(selected.expected).append(" previstas");
        msg.append("\nVerificadas físicamente: ").append(selected.received);
        msg.append("\nCódigos registrados: ").append(selected.codeCount).append(" · previstos: ").append(selected.plannedCodeCount);
        msg.append("\nEstado: ").append(selected.status);
        if (!selected.closureReason.isEmpty()) {
            msg.append("\nCierre parcial: ").append(selected.closureReason)
                    .append(" · previsión original ").append(selected.originalExpected);
        }
        UnloadEngine.PalletVerification proof = engine.verificationForPallet(label);
        if (proof != null) {
            msg.append("\nVerificación: ").append("LEGADO_V09".equals(proof.method)
                    ? "V0.9, sin responsable/fecha registrados" : proof.responsible + " · " + proof.time);
        }
        msg.append("\n\nCÓDIGO · REGISTRADAS / PREVISTAS\n");
        for (UnloadEngine.PalletCodeView row : engine.palletCodeViews(label)) {
            msg.append(row.code).append(" · ").append(row.scanned).append("/").append(row.expected)
                    .append(" · faltan ").append(Math.max(0, row.expected - row.scanned)).append("\n");
        }
        msg.append("\nLas registradas no prueban presencia física. Verificada no significa ubicada en el WMS.");
        AlertDialog.Builder dialog = new AlertDialog.Builder(this).setTitle(label).setMessage(msg.toString().trim())
                .setNegativeButton("Cerrar", null)
                .setNeutralButton("CAJAS Uxx", (d, w) -> showIndividualBoxes(label));
        if (selected.scanned > 0 && !selected.retired) {
            dialog.setPositiveButton("OPERAR TARIMA", (d, w) -> showPalletActions(label));
        }
        showOperationDialog(dialog.create());
    }

    private void showIndividualBoxes(String pallet) {
        StringBuilder message = new StringBuilder("Cajas registradas, no pendientes:\n\n");
        List<String> barcodes = engine.palletAcceptedBarcodes(pallet);
        for (String barcode : barcodes) message.append(barcode).append('\n');
        if (barcodes.isEmpty()) message.append("Todavía no hay cajas registradas.");
        showOperationDialog(new AlertDialog.Builder(this).setTitle(pallet + " · " + barcodes.size() + " cajas")
                .setMessage(message.toString()).setPositiveButton("Cerrar", null).create());
    }

    private void showPalletActions(String pallet) {
        ArrayList<String> options = new ArrayList<>();
        if (engine.validatedFinalPallets.contains(pallet) && !engine.isPalletRetired(pallet)) {
            options.add("RETIRADA / POSICIÓN LIBRE");
        } else if (engine.isPalletReadyForVerification(pallet)) {
            options.add("VERIFICAR CONTENIDO");
        } else if (engine.directCodeForPallet.containsKey(pallet)) {
            options.add("CERRAR PARCIAL");
        }
        if (options.isEmpty()) {
            showOperationDialog(new AlertDialog.Builder(this).setTitle(pallet)
                    .setMessage("La captura está incompleta. Consulte las cajas pendientes en el desglose.")
                    .setPositiveButton("Entendido", null).create());
            return;
        }
        showOperationDialog(new AlertDialog.Builder(this).setTitle("Operar " + pallet)
                .setItems(options.toArray(new String[0]), (d, which) -> {
                    String action = options.get(which);
                    if (action.startsWith("VERIFICAR")) showVerifyPallet(pallet);
                    else if (action.startsWith("CERRAR")) showPartialClosure(pallet);
                    else showReleasePallet(pallet);
                }).setNegativeButton("Cancelar", null).create());
    }

    private void showPartialClosure(String pallet) {
        String[] reasons = {"Falta de espacio al pie", "Las cajas ya no caben", "Fin de descarga parcial"};
        showOperationDialog(new AlertDialog.Builder(this).setTitle("Motivo de cierre · " + pallet)
                .setItems(reasons, (d, selected) -> {
                    String reason = reasons[selected];
                    showOperationDialog(new AlertDialog.Builder(this).setTitle("Cerrar parcial " + pallet)
                            .setMessage(engine.palletScannedCount(pallet) + " de " + engine.expectedForPallet(pallet)
                                    + " cajas previstas. Las restantes seguirán pendientes del contenedor y necesitarán otra tarima.\n\n"
                                    + "Después deberá verificar el contenido y retirar físicamente esta tarima.\nMotivo: " + reason)
                            .setNegativeButton("Cancelar", null)
                            .setPositiveButton("CERRAR PARCIAL", (x, y) -> commitOperation("TARIMA PARCIAL CERRADA",
                                    () -> engine.closeDirectPalletEarly(pallet, reason))).create());
                }).setNegativeButton("Cancelar", null).create());
    }

    private void showVerifyPallet(String pallet) {
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(dp(18), dp(8), dp(18), dp(8));
        EditText responsible = new EditText(this);
        responsible.setSingleLine(true);
        responsible.setHint("Nombre o iniciales del responsable");
        responsible.setText(getPreferences(MODE_PRIVATE).getString("last_verifier", ""));
        fields.addView(responsible);
        CheckBox checked = new CheckBox(this);
        checked.setText("Revisé físicamente las cajas y coinciden con el desglose");
        fields.addView(checked);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Verificar " + pallet)
                .setMessage(engine.palletScannedCount(pallet) + " cajas registradas. Confirme solo después de comprobar su contenido real."
                        + "\nNo libera la posición ni registra una ubicación WMS.")
                .setView(fields).setNegativeButton("Cancelar", null).setPositiveButton("VERIFICAR", null).create();
        dialog.setOnShowListener(d -> {
            Button confirm = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            confirm.setEnabled(false);
            checked.setOnCheckedChangeListener((b, value) -> confirm.setEnabled(value));
            confirm.setOnClickListener(v -> {
                String actor = responsible.getText().toString().trim();
                if (actor.isEmpty() || actor.length() > 80) { responsible.setError("Indique nombre o iniciales (máximo 80)"); return; }
                if (commitOperation("TARIMA VERIFICADA", () -> engine.validateFinalPallet(pallet, actor))) {
                    getPreferences(MODE_PRIVATE).edit().putString("last_verifier", actor).apply();
                    dialog.dismiss();
                }
            });
        });
        showOperationDialog(dialog);
    }

    private void showReleasePallet(String pallet) {
        String position = engine.physicalPositionForPallet(pallet);
        showOperationDialog(new AlertDialog.Builder(this).setTitle("Retiro físico · " + pallet)
                .setMessage("Confirme únicamente si " + pallet + " ya fue retirada"
                        + (position.isEmpty() ? " del tendido." : " y " + position + " quedó disponible para otra tarima.")
                        + "\nLa verificación del contenido se conserva.")
                .setNegativeButton("Todavía está aquí", null)
                .setPositiveButton("RETIRADA / LIBRE", (d, w) -> commitOperation("TARIMA RETIRADA",
                        () -> engine.releaseFinalPallet(pallet))).create());
    }

    private void changeTransfer() {
        if (scanInput != null && !scanInput.getText().toString().trim().isEmpty()) {
            Toast.makeText(this, "Termine o borre la lectura pendiente antes de cambiar de traslado", Toast.LENGTH_LONG).show();
            focusScanner();
            return;
        }
        commitOperation("TRASLADO CAMBIADO", () -> engine.changeCurrentTransfer());
    }

    private void showTransferList() {
        List<String> transfers = engine.transferLabels();
        ArrayList<String> labels = new ArrayList<>();
        for (String transfer : transfers) labels.add(transfer + " · " + engine.transferBoxCount(transfer) + " cajas · "
                + (engine.isTransferClosed(transfer) ? "cerrada" : "activa"));
        showOperationDialog(new AlertDialog.Builder(this).setTitle("Traslados · consulta")
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    String transfer = transfers.get(which);
                    StringBuilder detail = new StringBuilder();
                    detail.append(engine.transferBoxCount(transfer)).append(" cajas registradas\n")
                            .append(engine.transferVerifiedBoxCount(transfer)).append(" verificadas en sus tarimas finales\n\nDESTINOS\n");
                    for (Map.Entry<String, Integer> e : engine.transferDestinations(transfer).entrySet()) {
                        detail.append(e.getKey()).append(" · ").append(e.getValue()).append(" cajas\n");
                    }
                    detail.append("\nCerrar el traslado no confirma su distribución.");
                    showOperationDialog(new AlertDialog.Builder(this).setTitle(transfer).setMessage(detail.toString())
                            .setPositiveButton("Cerrar", null).create());
                }).setNegativeButton("Cerrar", null).create());
    }

    private void showOperationDialog(AlertDialog dialog) {
        operationDialogs++;
        dialog.setOnDismissListener(d -> { operationDialogs = Math.max(0, operationDialogs - 1); focusScanner(); });
        dialog.show();
    }

    private interface EngineOperation { ActionResult run(); }

    private boolean commitOperation(String event, EngineOperation operation) {
        if (storageBlocked) { Toast.makeText(this, "Operación bloqueada por fallo de guardado", Toast.LENGTH_LONG).show(); return false; }
        ActionResult result = operation.run();
        if (!result.ok) { Toast.makeText(this, result.message, Toast.LENGTH_LONG).show(); return false; }
        try {
            db.saveActionAndEngine(event, result.position, result.message, engine);
        } catch (Exception failure) {
            restoreAfterSaveFailure();
            return false;
        }
        Toast.makeText(this, result.message, Toast.LENGTH_LONG).show();
        refreshOperationScreen();
        return true;
    }

    private void refreshOperationScreen() {
        if (inSupervisor) showSupervisor();
        else if (inPalletView) showOperatorPallets();
        else showOperator();
    }

    private void restoreAfterSaveFailure() {
        UnloadEngine restored = db.loadEngine();
        storageBlocked = restored == null;
        if (restored != null) engine = restored;
        refreshOperationScreen();
        showOperationDialog(new AlertDialog.Builder(this).setTitle("NO SE GUARDÓ LA OPERACIÓN")
                .setMessage(restored == null
                        ? "Captura y exportación bloqueadas. No continúe colocando cajas. Reinicie y revise la recuperación de la sesión."
                        : "Se recuperó el último estado guardado. La operación no se contabilizó. No coloque la caja ni retire la tarima hasta repetir la acción correctamente.")
                .setPositiveButton("Entendido", null).create());
    }

    private void showSimpleSupervisorTools() {
        String[] options = new String[]{"Exportar historial CSV", "Reporte Excel", "Corregir último escaneo"};
        new AlertDialog.Builder(this).setTitle("Reportes y correcciones").setItems(options, (d, which) -> {
            if (which == 0) exportCsv();
            else if (which == 1) exportExcelReport();
            else showUndoLastScanDialog();
        }).setNegativeButton("Cerrar", null).show();
    }

    private void confirmNewUnloadImport() {
        new AlertDialog.Builder(this)
                .setTitle("Nueva descarga")
                .setMessage("La descarga actual se reemplazará al iniciar la nueva. Exporte primero el resultado para Windows si necesita conservarla.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("CARGAR LISTA", (d, which) -> chooseManifest())
                .show();
    }

    private View supervisorBufferRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(6), dp(8), dp(6));
        row.setBackground(box(C_LIGHT_GREEN, C_GREEN, 10));

        TextView lbl = tv("BUFFER  " + engine.bufferPalletCount() + " tarimas · "
                + engine.bufferOccupiedSectors() + "/" + engine.bufferTotalSectors() + " sectores",
                rsp(17, 12), C_DARK, true);
        row.addView(lbl, new LinearLayout.LayoutParams(0, rh(56, 44), 1f));

        Button minus = button("−", C_DISABLED, C_DARK);
        Button plus = button("+", C_GREEN, Color.WHITE);

        minus.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Quitar tarima buffer")
                .setMessage("Solo puede quitarse la última tarima buffer si sus 4 sectores están libres.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Quitar", (d, which) -> {
                    if (!engine.removeLastEmptyBufferPallet()) {
                        Toast.makeText(this, "No se puede quitar: la última buffer tiene mercancía o ya queda solo una.", Toast.LENGTH_LONG).show();
                    } else {
                        db.insertSystemEvent("BUFFER DESHABILITADO", "", "Tarima buffer retirada · quedan " + engine.bufferPalletCount());
                        saveQuietly();
                        showSupervisor();
                    }
                }).show());

        plus.setOnClickListener(v -> {
            if (!engine.addBufferPallet()) {
                Toast.makeText(this, "Máximo 10 tarimas buffer", Toast.LENGTH_SHORT).show();
            } else {
                db.insertSystemEvent("BUFFER HABILITADO", "", "Tarima buffer agregada · total " + engine.bufferPalletCount());
                saveQuietly();
                showSupervisor();
            }
        });

        row.addView(minus, new LinearLayout.LayoutParams(dp(58), dp(46)));
        row.addView(plus, new LinearLayout.LayoutParams(dp(58), dp(46)));
        return row;
    }

    private View supervisorSideRow(String label, String side) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(6), dp(8), dp(6));
        row.setBackground(box(Color.WHITE, C_BORDER, 10));
        TextView lbl = tv((compactPda() ? ("I".equals(side) ? "IZQ." : "DER.") : label) + "  " + engine.enabledCount(side) + "/10", rsp(18, 13), C_DARK, true);
        row.addView(lbl, new LinearLayout.LayoutParams(0, rh(52, 40), 1f));
        Button minus = button("−", C_DISABLED, C_DARK);
        Button plus = button("+", C_GREEN, Color.WHITE);
        minus.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Deshabilitar posición")
                .setMessage("Solo se deshabilitará la última posición libre del lado " + side + ". ¿Continuar?")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Deshabilitar", (d, which) -> {
                    ActionResult a = engine.disableLastFree(side);
                    if (!a.ok) Toast.makeText(this, a.message, Toast.LENGTH_SHORT).show();
                    else {
                        db.insertSystemEvent("POSICIÓN DESHABILITADA", a.position, a.message);
                        saveQuietly();
                        showSupervisor();
                    }
                }).show());
        plus.setOnClickListener(v -> {
            String pos = engine.enableNext(side);
            if (pos == null) Toast.makeText(this, "Ya están habilitadas las 10 posiciones de ese lado", Toast.LENGTH_SHORT).show();
            else {
                db.insertSystemEvent("POSICIÓN HABILITADA", pos, pos + " habilitada por supervisor");
                saveQuietly();
                showSupervisor();
            }
        });
        row.addView(minus, new LinearLayout.LayoutParams(dp(62), dp(48)));
        row.addView(plus, new LinearLayout.LayoutParams(dp(62), dp(48)));
        return row;
    }

    private void showUndoLastScanDialog() {
        PilotDatabase.EventRow e = db.lastAcceptedScanEvent();
        if (e == null) {
            Toast.makeText(this, "No hay un escaneo aceptado para corregir", Toast.LENGTH_SHORT).show();
            return;
        }
        if (db.hasStateChangeAfter(e.id)) {
            new AlertDialog.Builder(this)
                    .setTitle("Corrección bloqueada")
                    .setMessage("Después de ese escaneo hubo un cambio físico de posición/tarima. Para conservar trazabilidad no se puede anular automáticamente.")
                    .setPositiveButton("Entendido", null)
                    .show();
            return;
        }
        if (e.normalizedScan == null || e.normalizedScan.isEmpty()) {
            Toast.makeText(this, "El último registro no tiene barcode individual", Toast.LENGTH_SHORT).show();
            return;
        }
        EditText reason = new EditText(this);
        reason.setHint("Motivo de la corrección (obligatorio)");
        reason.setSingleLine(false);
        reason.setPadding(dp(14), dp(10), dp(14), dp(10));
        new AlertDialog.Builder(this)
                .setTitle("Anular último escaneo aceptado")
                .setMessage(e.normalizedScan + "\nPosición: " + e.position + "\nHora: " + e.time +
                        "\n\nLa caja volverá a quedar pendiente y el evento original NO se borrará.")
                .setView(reason)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("ANULAR", (d, which) -> {
                    String why = reason.getText().toString().trim();
                    if (why.isEmpty()) {
                        Toast.makeText(this, "No se anuló: escriba un motivo", Toast.LENGTH_LONG).show();
                        return;
                    }
                    commitOperation("ESCANEO ANULADO", () -> {
                        ActionResult result = engine.undoAcceptedBox(e.normalizedScan);
                        if (result.ok) result.message += " · Evento original #" + e.id + " · Motivo: " + why;
                        return result;
                    });
                })
                .show();
    }

    private void saveQuietly() {
        try { db.saveEngine(engine); } catch (Exception e) {
            Toast.makeText(this, "No se pudo guardar estado", Toast.LENGTH_SHORT).show();
        }
    }

    private void exportCsv() {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/csv");
        i.putExtra(Intent.EXTRA_TITLE, "resultado_" + engine.containerId + ".csv");
        startActivityForResult(i, REQ_EXPORT);
    }

    private void exportExcelReport() {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        i.putExtra(Intent.EXTRA_TITLE, "reporte_descarga_" + engine.containerId + ".xlsx");
        startActivityForResult(i, REQ_EXPORT_XLSX);
    }

    private void exportPdaResult() {
        if (storageBlocked || engine == null) {
            Toast.makeText(this, "Exportación bloqueada: revise el guardado de la sesión", Toast.LENGTH_LONG).show();
            return;
        }
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/json");
        i.putExtra(Intent.EXTRA_TITLE, "resultado_PDA_" + engine.containerId + ".json");
        startActivityForResult(i, REQ_EXPORT_PDA_RESULT);
    }

    private void exportWmsTemplate() {
        if (!engine.isManualMode()) {
            Toast.makeText(this, "La plantilla WMS por tarima está habilitada para modo MANUAL", Toast.LENGTH_LONG).show();
            return;
        }
        String order = db.latestPutawayOrder();
        if (order.isEmpty()) {
            showPutawayOrderDialog();
            Toast.makeText(this, "Capture la Orden Putaway y vuelva a exportar", Toast.LENGTH_LONG).show();
            return;
        }
        int missing = db.missingTemporalPallets();
        if (missing > 0) {
            Toast.makeText(this, "Faltan temporales en " + missing + " tarima(s). Complételas antes de generar WMS.", Toast.LENGTH_LONG).show();
            return;
        }
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        i.putExtra(Intent.EXTRA_TITLE, "WMS_Putaway_" + engine.containerId + ".xlsx");
        startActivityForResult(i, REQ_EXPORT_WMS);
    }

    private void showPutawayOrderDialog() {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("Ej. orden Putaway del WMS");
        input.setText(db.latestPutawayOrder());
        new AlertDialog.Builder(this)
                .setTitle("ORDEN PUTAWAY WMS")
                .setMessage("La plantilla oficial requiere Putaway Order. Este dato no viene en el Packing List actual, por eso se captura aquí.")
                .setView(input)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("GUARDAR", (d,w) -> {
                    try {
                        db.setPutawayOrder(input.getText().toString());
                        Toast.makeText(this, "Orden Putaway guardada", Toast.LENGTH_SHORT).show();
                        if (inSupervisor) showSupervisor(); else refreshOperator();
                    } catch (Exception e) {
                        Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                }).show();
    }

    private void showTemporalDialog(String position, boolean continueReady, Runnable afterSave) {
        if (position == null || position.trim().isEmpty()) {
            Toast.makeText(this, "Seleccione una tarima primero", Toast.LENGTH_SHORT).show();
            return;
        }
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("TEMPORAL WMS");
        input.setText(db.currentTemporalForPosition(position));
        new AlertDialog.Builder(this)
                .setTitle("TEMPORAL · " + position)
                .setMessage("Puede capturarse al inicio o al final. Quedará asociada únicamente a la tarima actual de esta posición.")
                .setView(input)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("GUARDAR", (d,w) -> {
                    try {
                        db.setTemporalForCurrentPallet(position, input.getText().toString());
                        Toast.makeText(this, "Temporal guardada: " + input.getText().toString().trim().toUpperCase(Locale.ROOT), Toast.LENGTH_SHORT).show();
                        if (afterSave != null) afterSave.run();
                        else if (inSupervisor) showSupervisor(); else refreshOperator();
                    } catch (Exception e) {
                        Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                }).show();
    }

    private void markPositionReadyWithTemporal(String label) {
        if (engine != null && engine.isManualMode() && db.currentTemporalForPosition(label).isEmpty()) {
            showTemporalDialog(label, true, () -> markPositionReadyWithTemporal(label));
            return;
        }
        ActionResult a = engine.markPositionReady(label);
        if (a.ok) {
            db.insertSystemEvent("POSICIÓN LISTA", a.position, a.message);
            saveQuietly();
            lastPosition = a.position;
            if (engine.isManualMode()) engine.setManualActivePosition(a.position);
            Toast.makeText(this, a.message, Toast.LENGTH_SHORT).show();
            refreshOperator();
        } else Toast.makeText(this, a.message, Toast.LENGTH_LONG).show();
        focusScanner();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQ_IMPORT) {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                pendingManifest = ManifestImporter.parse(in);
                applyRecommendedBufferPlan();
                showSetup();
            } catch (Exception e) {
                Toast.makeText(this, "Archivo inválido: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == REQ_EXPORT) {
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                db.writeEventsCsv(out);
                Toast.makeText(this, "Historial CSV exportado", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "No se pudo exportar CSV: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == REQ_EXPORT_XLSX) {
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                db.writeReportXlsx(out, engine);
                Toast.makeText(this, "Reporte Excel exportado", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "No se pudo exportar Excel: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == REQ_EXPORT_WMS) {
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                db.writeWmsPutawayXlsx(out);
                Toast.makeText(this, "Plantilla WMS exportada", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "No se pudo generar WMS: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == REQ_EXPORT_PDA_RESULT) {
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                db.writePdaResultJson(out, engine);
                Toast.makeText(this, "Resultado listo para importar en Windows", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "No se pudo exportar resultado PDA: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
    }
}
