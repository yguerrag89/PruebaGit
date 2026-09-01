import com.ilubox.descargapda.core.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Regresiones del flujo continuo, estado V0.9 sin temporales ficticias y contrato nativo v4. */
public class ContinuousEngineSelfTest {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static UnloadEngine copy(UnloadEngine source) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) { out.writeObject(source); }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return (UnloadEngine) in.readObject();
        }
    }

    private static void export(Path directory, String name, UnloadEngine engine) throws Exception {
        Files.createDirectories(directory);
        List<PdaResultWriter.AcceptedScan> scans = new ArrayList<>();
        List<String> keys = new ArrayList<>(engine.scannedUniqueBarcodes.keySet());
        Collections.sort(keys);
        for (String barcode : keys) {
            UnloadEngine.ScanMeta meta = engine.scannedUniqueBarcodes.get(barcode);
            scans.add(new PdaResultWriter.AcceptedScan(meta.rawScan, barcode, meta.code, meta.boxNumber, "2026-08-28 10:00:00"));
        }
        try (OutputStream out = Files.newOutputStream(directory.resolve(name))) { PdaResultWriter.write(out, engine, scans); }
    }

    public static void main(String[] args) throws Exception {
        Path output = Paths.get(args.length > 1 ? args[1] : "test-output/v011");
        Settings settings = new Settings();
        settings.targetCapacity = 0.60;
        settings.largeRatio = 2.0;
        UnloadEngine engine = new UnloadEngine("CONTINUO", Arrays.asList(
                new CodeRecord("CHICOA", 3, 0.60, 0.20, 1.0, "", ""),
                new CodeRecord("CHICOB", 3, 0.60, 0.20, 1.0, "", "")
        ), settings, 1, 0, "TRASLADO");
        check(engine.plannedTendidoPalletCount() == 2, "dos definitivas previstas");
        check(!engine.changeCurrentTransfer().ok && engine.transferPalletSeq == 1, "TR vacía no cambia");
        ScanResult a1 = engine.scanTransfer("CHICOAU001");
        ScanResult b1 = engine.scanTransfer("CHICOBU001");
        check(a1.ok && b1.ok && !a1.position.equals(b1.position), "TR mixta con dos destinos");
        check(engine.currentTransferBoxCount() == 2 && engine.currentTransferDestinations().size() == 2, "contenido correcto TR-01");
        check(engine.changeCurrentTransfer().ok, "cierra primer viaje");
        check(!engine.changeCurrentTransfer().ok && engine.transferPalletSeq == 2, "doble pulsación no crea viajes vacíos");
        check(!engine.undoAcceptedBox("CHICOBU001").ok, "no anula cajas de una TR que salió");
        check(engine.scanTransfer("CHICOAU003").ok && engine.scanTransfer("CHICOAU002").ok, "U desordenados y escaneo continuo");
        check(engine.transferBoxCount("TR-01") == 2 && engine.currentTransferBoxCount() == 2, "no mezcla viajes");
        check(engine.isPalletReadyForVerification(a1.position), "captura completa avisa revisar");
        check(!engine.validateFinalPallet(a1.position, "OP-1", "2B-TMP-01").ok, "TR activa impide verificar solo esa T");
        check(engine.changeCurrentTransfer().ok, "cierra segundo viaje");
        check(engine.wmsEligibleBoxCount() == 0 && engine.inFinalBoxCount() == 0, "cerrar viajes no inventa presencia física");
        export(output, "v4-pending.json", engine);
        check(!engine.validateFinalPallet(a1.position, "", "2B-TMP-01").ok, "responsable obligatorio");
        check(!engine.validateFinalPallet(a1.position, "OP\n1", "2B-TMP-01").ok, "responsable sin saltos de línea");
        check(engine.validateFinalPallet(a1.position, "OP-1 \"Ana\"", "2B-TMP-01").ok, "verifica una T de dos viajes");
        check(engine.wmsEligibleBoxCount() == 3 && !engine.isBoxWmsEligible("CHICOBU001"), "no verifica otra T del mismo viaje");
        check("PENDIENTE_VERIFICACION".equals(engine.transferStatus("TR-01")), "TR mixta sigue parcialmente pendiente");
        check("VERIFICADO_POR_TARIMAS".equals(engine.transferStatus("TR-02")), "TR verificada por todas sus T");
        check(!engine.validateFinalPallet(a1.position, "OP-1", "2B-TMP-01").ok, "verificación idempotente");
        check(!engine.undoAcceptedBox("CHICOAU002").ok, "contenido verificado inmutable");
        export(output, "v4-mixed.json", engine);
        check(engine.scanTransfer("CHICOBU002").ok && engine.scanTransfer("CHICOBU003").ok, "sigue capturando B");
        check(engine.changeCurrentTransfer().ok && engine.validateFinalPallet(b1.position, "OP-2", "2B-TMP-02").ok, "verifica B sin confirmar distribución");
        check(engine.wmsEligibleBoxCount() == 6, "seis cajas elegibles");
        check(engine.releaseFinalPallet(a1.position).ok && engine.isPalletRetired(a1.position), "retiro del tendido conserva verificación");
        check(!engine.releaseFinalPallet(a1.position).ok, "retiro idempotente");
        export(output, "v4-verified.json", engine);
        UnloadEngine restored = copy(engine);
        check(restored.wmsEligibleBoxCount() == 6 && restored.isPalletRetired(a1.position), "persisten verificaciones/retiros");
        check(restored.transferPalletSeq == 4 && restored.transferBoxCount("TR-01") == 2, "persisten viajes");
        check(restored.verificationForPallet(a1.position).responsible.equals("OP-1 \"Ana\""), "persiste responsable");

        Settings footSettings = new Settings();
        footSettings.targetCapacity = 1.0;
        UnloadEngine foot = new UnloadEngine("PARCIAL", Arrays.asList(
                new CodeRecord("GRANDE", 5, 2.0, 0.4, 10.0, "", ""),
                new CodeRecord("OTRO", 5, 2.0, 0.4, 10.0, "", "")
        ), footSettings, 1, 0, "TRASLADO");
        ScanResult first = foot.scanTransfer("GRANDEU005");
        check(first.ok && foot.expectedForPallet(first.position) == 2, "previsión local distinta del total del código");
        check(!foot.isBarcodeInFinal("GRANDEU005"), "directa pendiente de verificación");
        check(!foot.scanTransfer("OTROU001").ok && foot.acceptedBoxCount() == 1, "sin espacio no cuenta caja");
        check(!foot.closeDirectPalletEarly(first.position, "").ok, "motivo parcial obligatorio");
        check(!foot.validateFinalPallet(first.position, "OP", "2B-TMP-03").ok, "no verifica parcial sin cierre explícito");
        check(foot.closeDirectPalletEarly(first.position, "Falta de espacio al pie").ok, "cierre parcial");
        check(foot.expectedForPallet(first.position) == 1 && foot.originalExpectedForPallet(first.position) == 2, "conserva previsión original");
        check(foot.progress()[1] == 10 && foot.received.get("GRANDE") == 1, "no borra pendientes del contenedor");
        check(!foot.undoAcceptedBox("GRANDEU005").ok, "no deshace cierre parcial a espaldas del historial");
        check(!foot.releaseFinalPallet(first.position).ok, "no libera tarima sin verificar");
        check(foot.validateFinalPallet(first.position, "OP", "2B-TMP-03").ok, "verifica parcial");
        check(foot.activeFinalPalletForFootPosition.size() == 1 && !foot.isPalletRetired(first.position), "verificar no libera espacio");
        check(!foot.scanTransfer("OTROU001").ok, "posición continúa ocupada");
        check(foot.releaseFinalPallet(first.position).ok && foot.activeFinalPalletForFootPosition.isEmpty(), "libera al confirmar retiro");
        ScanResult next = foot.scanTransfer("GRANDEU001");
        check(next.ok && !next.position.equals(first.position) && next.physicalPosition.equals(first.physicalPosition), "nuevo ID, misma posición");
        check(foot.expectedForPallet(next.position) == 2 && foot.received.get("GRANDE") == 2, "remanentes conservados");
        ScanResult duplicate = foot.scanTransfer("GRANDEU005");
        check(!duplicate.ok && duplicate.position.equals(first.position), "duplicado conserva destino retirado");
        check(!foot.scanTransfer("GRANDEU000").ok && !foot.scanTransfer("GRANDEU006").ok
                && !foot.scanTransfer("GRANDE").ok && foot.acceptedBoxCount() == 2, "identidad estricta sin efectos laterales");
        check(foot.undoAcceptedBox("GRANDEU001").ok && foot.activeFinalPalletForFootPosition.isEmpty(), "anular única caja no reserva un espacio fantasma");
        export(output, "v4-partial.json", foot);
        check(copy(foot).originalExpectedForPallet(first.position) == 2, "persiste cierre parcial");

        UnloadEngine composition = new UnloadEngine("DESGLOSE", Arrays.asList(
                new CodeRecord("UNO", 1, 0.1, 0.1, 1.0, "", ""),
                new CodeRecord("DOS", 2, 0.2, 0.1, 1.0, "", "")
        ), new Settings(), 0, 0, "TRASLADO");
        ScanResult unit = composition.scanTransfer("UNO");
        UnloadEngine.FinalPalletView view = null;
        for (UnloadEngine.FinalPalletView candidate : composition.finalPalletViews())
            if (candidate.label.equals(unit.position)) view = candidate;
        check(view != null && view.codeCount == 1 && view.plannedCodeCount == 1, "unitario aislado de códigos multicaja");
        check(composition.palletCodeViews(unit.position).size() == 1 && composition.palletAcceptedBarcodes(unit.position).size() == 1,
                "desglose unitario con individuales bajo demanda");
        boolean refusedIncompleteHistory = false;
        try { PdaResultWriter.write(new ByteArrayOutputStream(), composition, Collections.emptyList()); }
        catch (IllegalStateException correct) { refusedIncompleteHistory = true; }
        check(refusedIncompleteHistory, "exportador no omite cajas del historial");

        Path fixture = Paths.get(args.length > 0 ? args[0] : "android-project/tests/fixtures/v09-state.ser");
        UnloadEngine migrated;
        try (ObjectInputStream in = new ObjectInputStream(Files.newInputStream(fixture))) { migrated = (UnloadEngine) in.readObject(); }
        check(migrated.acceptedBoxCount() == 3 && migrated.progress()[1] == 12, "migra V0.9 sin perder cajas");
        check("TR-02".equals(migrated.currentTransferPallet()) && migrated.isTransferClosed("TR-01"), "TR antigua enviada no recibe cajas nuevas");
        String legacyPallet = migrated.finalPalletForBarcode.get("GRANDEU009");
        check(!migrated.isBoxWmsEligible("GRANDEU009") && migrated.isPalletRetired(legacyPallet)
                && migrated.isBarcodeInFinal("GRANDEU009"), "conserva historia física, no inventa temporal ni elegibilidad V0.11");
        check("LEGADO_V09".equals(migrated.verificationForPallet(legacyPallet).method)
                && migrated.verificationForPallet(legacyPallet).time.isEmpty(), "no inventa fecha de comprobación antigua");
        check(migrated.activeFinalPalletForFootPosition.size() == 1 && !migrated.isBoxWmsEligible("GRANDEU003"), "respeta directa antigua sin verificar");
        boolean refusedLegacy = false;
        try { PdaResultWriter.write(new ByteArrayOutputStream(), migrated, Collections.emptyList()); }
        catch (IllegalStateException correct) { refusedLegacy = correct.getMessage().contains("sin temporal WMS"); }
        check(refusedLegacy, "no exporta como V0.11 una verificación anterior sin temporal");
        check(migrated.scanTransfer("CHICOU002").ok && migrated.transferBoxCount("TR-01") == 1, "continúa tras migrar sin mezclar viajes");
        check(copy(migrated).currentTransferPallet().equals("TR-02"), "no avanza dos veces al recuperar V0.10");
        System.out.println("OK V0.11 flujo continuo: " + checks + " comprobaciones; fixtures nativos: " + output);
    }
}
