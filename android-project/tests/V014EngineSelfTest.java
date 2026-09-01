import com.ilubox.descargapda.core.*;
import java.util.*;

/** Reglas nuevas: plan global, unitarios aislados, directas multitarima y NO CABE. */
public class V014EngineSelfTest {
    private static int checks;
    private static void ok(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static List<String> assignedTo(UnloadEngine engine, String pallet) {
        ArrayList<String> out = new ArrayList<>();
        for (Map.Entry<String, String> e : engine.finalPalletForBarcode.entrySet())
            if (pallet.equals(e.getValue())) out.add(e.getKey());
        Collections.sort(out);
        return out;
    }

    public static void main(String[] args) throws Exception {
        Settings s = new Settings();
        s.targetCapacity = 1.0;
        s.maxWeight = 1000.0;
        UnloadEngine e = new UnloadEngine("GLOBAL", Arrays.asList(
                new CodeRecord("GENERAL_A", 4, 0.80, 0.20, 100.0, "", ""),
                new CodeRecord("GENERAL_B", 4, 0.80, 0.20, 100.0, "", ""),
                new CodeRecord("UNIT_A", 1, 0.20, 0.20, 50.0, "", ""),
                new CodeRecord("UNIT_B", 1, 0.20, 0.20, 50.0, "", ""),
                new CodeRecord("MULTI", 5, 2.00, 0.40, 100.0, "", "")
        ), s, 1, 0, "TRASLADO");

        ok(e.directCodeCount() == 1 && e.estimatedDirectFinalPallets == 3,
                "solo MULTI es directo porque requiere varias tarimas");
        ok(e.plannedTendidoPalletCount() == 3, "dos mixtas globales y una de unitarios");
        String unitPallet = e.finalPalletForBarcode.get("UNIT_AU001");
        ok(unitPallet.equals(e.finalPalletForBarcode.get("UNIT_BU001")), "unitarios agrupados entre sí");
        ok(!unitPallet.equals(e.finalPalletForBarcode.get("GENERAL_AU001")), "unitarios no mezclados con multicaja");
        for (String pallet : e.plannedTendidoPallets) {
            ok(e.plannedCbmForPallet(pallet) <= 1.0 + 1e-9, "CBM duro");
            ok(e.plannedWeightForPallet(pallet) <= 1000.0 + 1e-9, "peso duro");
        }

        String source = null;
        List<String> sourceBoxes = null;
        for (String pallet : e.plannedTendidoPallets) {
            List<String> boxes = assignedTo(e, pallet);
            if (!pallet.equals(unitPallet) && boxes.size() >= 3) { source = pallet; sourceBoxes = boxes; break; }
        }
        ok(source != null, "tarima candidata para excepción física");
        String old = source;
        String first = sourceBoxes.get(0), active = sourceBoxes.get(1);
        ok(e.scanTransfer(first).ok && e.changeCurrentTransfer().ok, "primera caja ya salió en TR cerrada");
        ok(e.scanTransfer(active).ok, "segunda caja permanece en TR activa");
        int totalAssignments = e.finalPalletForBarcode.size();
        ActionResult closed = e.closeFinalPalletEarly(old, "Las cajas ya no caben físicamente");
        ok(closed.ok && e.expectedForPallet(old) == 1 && e.originalExpectedForPallet(old) > 1,
                "cierra contenido físico y conserva previsión original");
        String newTarget = e.finalPalletForBarcode.get(active);
        ok(newTarget != null && !newTarget.equals(old), "caja de TR activa replanificada");
        ok(e.remarkRequiredForPallet(newTarget).contains(active), "exige remarcar solo la caja ya escaneada");
        ok(!e.validateFinalPallet(newTarget, "OP", "2B-TMP-N").ok, "no valida con remarcado pendiente");
        ok(e.confirmRemarking(newTarget).ok, "operador confirma la excepción");
        ok(e.finalPalletForBarcode.size() == totalAssignments, "replanificación no pierde cajas");
        ok(e.validateFinalPallet(old, "OP", "2B-TMP-OLD").ok, "tarima física parcial puede validarse");

        System.out.println("OK Android V0.14: " + checks + " comprobaciones");
    }
}
