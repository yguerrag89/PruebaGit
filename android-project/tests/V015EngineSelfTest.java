import com.ilubox.descargapda.core.*;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** V0.15: la falta de posiciones al pie no bloquea y la exportación contiene solo evidencia real. */
public class V015EngineSelfTest {
    private static int checks;
    private static void ok(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        Settings settings = new Settings();
        settings.targetCapacity = 1.0;
        settings.maxWeight = 1000.0;
        UnloadEngine engine = new UnloadEngine("SIMPLE", Arrays.asList(
                new CodeRecord("DIRECT_A", 5, 2.0, 0.4, 100.0, "", ""),
                new CodeRecord("DIRECT_B", 5, 2.0, 0.4, 100.0, "", ""),
                new CodeRecord("GENERAL_C", 3, 0.6, 0.2, 100.0, "", "")
        ), settings, 1, 0, "TRASLADO");

        ScanResult a1 = engine.scanTransfer("DIRECT_AU001");
        ok(a1.ok && a1.directToFinal && "I01".equals(engine.physicalPositionForPallet(a1.finalPallet)),
                "primer código directo ocupa la única posición al pie");

        ScanResult b1 = engine.scanTransfer("DIRECT_BU001");
        ScanResult b2 = engine.scanTransfer("DIRECT_BU002");
        ok(b1.ok && b2.ok, "la falta de posición nunca rechaza cajas válidas");
        ok("CONTINGENCIA TR".equals(b1.status) && !b1.directToFinal, "desvía la caja a la TR como contingencia");
        ok(b1.finalPallet.equals(b2.finalPallet) && engine.isOverflowTendidoPallet(b1.finalPallet),
                "mantiene una definitiva homogénea para el código desviado");
        ok(engine.expectedForPallet(b1.finalPallet) == 2 && engine.overflowTransferBoxCount() == 2,
                "respeta la capacidad calculada y contabiliza la contingencia");
        ok(engine.changeCurrentTransfer().ok, "el viaje se cierra con una sola acción física");
        ok(engine.validateFinalPallet(b1.finalPallet, "OP", "2B-TMP-01").ok,
                "la temporal WMS valida la definitiva de contingencia");

        List<PdaResultWriter.AcceptedScan> scans = Arrays.asList(
                new PdaResultWriter.AcceptedScan("DIRECT_AU001", "DIRECT_AU001", "DIRECT_A", 1, "2026-09-01T10:00:00Z"),
                new PdaResultWriter.AcceptedScan("DIRECT_BU001", "DIRECT_BU001", "DIRECT_B", 1, "2026-09-01T10:00:01Z"),
                new PdaResultWriter.AcceptedScan("DIRECT_BU002", "DIRECT_BU002", "DIRECT_B", 2, "2026-09-01T10:00:02Z")
        );
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PdaResultWriter.write(output, engine, scans);
        String json = output.toString(StandardCharsets.UTF_8.name());
        ok(json.contains("\"plan_export_policy\":\"ACTUAL_SCANNED_ONLY\""), "declara la política de evidencia real");
        ok(!json.contains("\"scanned\":0"), "no exporta tarimas teóricas sin escaneo");
        ok(json.contains("\"direct_to_final\":false"), "la contingencia no se declara falsamente como PIE");

        System.out.println("OK Android V0.15: " + checks + " comprobaciones");
    }
}
