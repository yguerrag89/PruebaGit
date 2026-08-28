import com.ilubox.descargapda.core.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Casos de cierre con temporal, recuperación y no herencia de I01. Solo datos sintéticos. */
public class TemporaryLocationSelfTest {
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static UnloadEngine restore(UnloadEngine engine) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(buffer)) { out.writeObject(engine); }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(buffer.toByteArray()))) {
            return (UnloadEngine) in.readObject();
        }
    }

    private static void export(Path path, UnloadEngine engine) throws Exception {
        Files.createDirectories(path.getParent());
        List<PdaResultWriter.AcceptedScan> scans = new ArrayList<>();
        for (Map.Entry<String, UnloadEngine.ScanMeta> entry : engine.scannedUniqueBarcodes.entrySet()) {
            UnloadEngine.ScanMeta m = entry.getValue();
            scans.add(new PdaResultWriter.AcceptedScan(m.rawScan, entry.getKey(), m.code, m.boxNumber, "2026-08-28 10:00:00"));
        }
        try (OutputStream out = Files.newOutputStream(path)) { PdaResultWriter.write(out, engine, scans); }
    }

    public static void main(String[] args) throws Exception {
        Settings settings = new Settings();
        settings.targetCapacity = 1.0;
        UnloadEngine engine = new UnloadEngine("DIRECTAS-TEMPORAL", Arrays.asList(
                new CodeRecord("GRANDE", 4, 2.0, 0.5, 1.0, "", "")), settings, 1, 0, "TRASLADO");
        ScanResult first = engine.scanTransfer("GRANDEU001");
        check(first.ok && first.directToFinal, "tarima directa");
        check(engine.scanTransfer("GRANDEU002").ok, "dos cajas por primera tarima");
        check(!engine.validateFinalPallet(first.position, "OP-1").ok, "API antigua no permite omitir temporal");
        String[] invalid = {null, "", "   ", "I01", "D10", "T-01", "TR-04", "=A1", "+A1", "-A1", "@A1",
                "2B TMP 01", "2B\nTMP", "2B\tTMP", "2B\u007fTMP", "\u00002B-TMP", "2B-TMP\r", "{\"location\":\"2B\"}", "X".repeat(81)};
        for (String location : invalid) {
            check(!engine.validateFinalPallet(first.position, "OP-1", location).ok, "rechaza temporal inválida");
            check(engine.wmsEligibleBoxCount() == 0 && !engine.validatedFinalPallets.contains(first.position)
                    && engine.wmsTemporaryForPallet(first.position).isEmpty(), "rechazo sin cambios de estado");
        }
        check(!engine.releaseFinalPallet(first.position).ok, "no libera sin cierre y temporal");
        check(engine.validateFinalPallet(first.position, "OP-1", " 2b-tmp-01 ").ok, "normaliza al cerrar");
        check("2B-TMP-01".equals(engine.wmsTemporaryForPallet(first.position)), "temporal canónica por T");
        check(engine.wmsEligibleBoxCount() == 2 && engine.activeFinalPalletForFootPosition.size() == 1, "cerrar no libera I01");
        check(!engine.validateFinalPallet(first.position, "OP-2", "2B-TMP-99").ok
                && "2B-TMP-01".equals(engine.wmsTemporaryForPallet(first.position)), "no sobrescribe cierre confirmado");
        check(!engine.undoAcceptedBox("GRANDEU002").ok, "contenido cerrado inmutable");
        check(engine.releaseFinalPallet(first.position).ok, "retiro posterior explícito");
        ScanResult second = engine.scanTransfer("GRANDEU003");
        check(second.ok && !second.position.equals(first.position) && second.physicalPosition.equals(first.physicalPosition), "reutiliza I01, no el ID de tarima");
        check(engine.wmsTemporaryForPallet(second.position).isEmpty(), "no hereda temporal de la tarima retirada");
        check(engine.scanTransfer("GRANDEU004").ok, "completa segunda directa");
        check(!engine.validateFinalPallet(second.position, "OP-2", "").ok && engine.wmsEligibleBoxCount() == 2, "segunda tarima exige su propia captura");
        UnloadEngine sharedTemporary = restore(engine);
        check(sharedTemporary.validateFinalPallet(second.position, "OP-2", "2B-TMP-01").ok
                && sharedTemporary.wmsEligibleBoxCount() == 4, "dos T pueden compartir temporal sin herencia automática");
        check(engine.validateFinalPallet(second.position, "OP-2", "2B-TMP-02").ok, "segunda ubicación independiente");
        UnloadEngine restored = restore(engine);
        check(restored.wmsEligibleBoxCount() == 4 && restored.isPalletRetired(first.position), "persisten estado y retiro");
        check("2B-TMP-01".equals(restored.wmsTemporaryForPallet(first.position))
                && "2B-TMP-02".equals(restored.wmsTemporaryForPallet(second.position)), "persisten ambas temporales por ID");
        export(Paths.get(args[1]).resolve("v4-direct.json"), restored);

        UnloadEngine old;
        try (ObjectInputStream in = new ObjectInputStream(Files.newInputStream(Paths.get(args[0])))) { old = (UnloadEngine) in.readObject(); }
        String pallet = old.finalPalletForBarcode.get("CAJAU001");
        check(old.acceptedBoxCount() == 1 && old.isBarcodeInFinal("CAJAU001"), "recupera estado V0.10 sin borrar prueba física");
        check(old.wmsTemporaryForPallet(pallet).isEmpty() && old.wmsEligibleBoxCount() == 0, "no fabrica temporal para V0.10");
        check(!old.releaseFinalPallet(pallet).ok, "no retira verificación heredada sin temporal");
        boolean refused = false;
        try { PdaResultWriter.write(new ByteArrayOutputStream(), old, Collections.emptyList()); }
        catch (IllegalStateException e) { refused = e.getMessage().contains("sin temporal WMS"); }
        check(refused, "no etiqueta resultado antiguo sin temporal como V0.11");
        System.out.println("OK V0.11 temporal obligatoria: " + checks + " comprobaciones");
    }
}
