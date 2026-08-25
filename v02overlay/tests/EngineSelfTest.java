import com.ilubox.descargapda.core.*;
import java.util.*;

public class EngineSelfTest {
    private static void ok(boolean x, String msg) {
        if (!x) throw new AssertionError(msg);
    }

    public static void main(String[] args) {
        Settings s = new Settings();
        List<CodeRecord> records = Arrays.asList(
            new CodeRecord("BIG", 5, 2.5, 0.5, null, "", ""),
            new CodeRecord("SMALL", 2, 0.20, 0.10, null, "", ""),
            new CodeRecord("UNITA", 1, 0.05, 0.05, null, "", "")
        );
        UnloadEngine live = new UnloadEngine("TEST", records, s, 2, 1);
        ok(live.pressure().enabled == 3, "posiciones iniciales");
        ok("D02".equals(live.enableNext("D")), "habilitar D02");

        ScanResult r1 = live.scan("BIGU001");
        ScanResult r2 = live.scan("BIGU002");
        ScanResult r3 = live.scan("BIGU003");
        ok(r1.ok && r2.ok && r3.ok, "BIG primeros 3");
        ok("TARIMA COMPLETA".equals(r3.status), "tarima BIG completa");
        String pos = r3.position;
        ok(live.findPosition(pos).waitingRemoval, "posición debe bloquearse");

        ScanResult r4 = live.scan("BIGU004");
        ok(!r4.ok && "POSICIÓN PENDIENTE".equals(r4.status), "bloqueo antes de POSICIÓN LISTA");
        ok(live.received.get("BIG") == 3, "no incrementar bloqueado");

        ActionResult a1 = live.markPositionReady(pos);
        ok(a1.ok && a1.continues, "continúa BIG misma posición");
        ScanResult r5 = live.scan("BIGU004");
        ScanResult r6 = live.scan("BIGU005");
        ok(r5.ok && r6.ok && r5.position.equals(pos) && r6.position.equals(pos), "segunda tarima misma posición");
        ActionResult a2 = live.markPositionReady(pos);
        ok(a2.ok && !a2.continues && live.findPosition(pos).isFree(), "posición libre al terminar BIG");

        ScanResult small1 = live.scan("SMALLU001");
        ScanResult dup = live.scan("SMALLU001");
        ok(small1.ok, "small aceptada");
        ok(!dup.ok && "DUPLICADA".equals(dup.status), "duplicado");
        ok(dup.position.equals(small1.position), "duplicado muestra posición original");
        ok(live.received.get("SMALL") == 1, "duplicado no incrementa");

        ScanResult unit = live.scan("UNITAU001");
        ok(unit.ok, "unitario");
        ok(live.positionCards().size() == 20, "mapa 20 posiciones");

        List<CodeRecord> nr = Arrays.asList(
            new CodeRecord("THZ26063090342", 4, 0.40, 0.10, 12.0, "", "")
        );
        UnloadEngine n = new UnloadEngine("NORM", nr, s, 1, 0);
        ScanResult n1 = n.scan("THZ26063090342U001");
        ok(n1.ok && n1.boxNumber == 1, "U001 válida");
        ScanResult corruptDup = n.scan("THZ26063THZ26063090342U001090342U001");
        ok(!corruptDup.ok && "DUPLICADA".equals(corruptDup.status), "lectura concatenada normaliza al mismo U001");
        ok("THZ26063090342U001".equals(corruptDup.normalizedBarcode), "barcode normalizado correcto");
        ok(n.received.get("THZ26063090342") == 1, "concatenada duplicada no incrementa");

        ScanResult n2 = n.scan("THZ26063090342U003");
        ok(n2.ok && n.received.get("THZ26063090342") == 2, "U003 válida aunque U002 falte");
        ScanResult outRange = n.scan("THZ26063090342U005");
        ok(!outRange.ok && "FUERA DE RANGO".equals(outRange.status), "U005 fuera de rango");
        ok(n.received.get("THZ26063090342") == 2, "fuera de rango no incrementa");
        ok(n.missingBoxes("THZ26063090342", 10).equals(Arrays.asList("U002", "U004")), "faltantes exactos");

        ScanResult incomplete = n.scan("THZ26063090342");
        ok(!incomplete.ok && "LECTURA INCOMPLETA".equals(incomplete.status), "multi-caja requiere Uxxx");

        ActionResult early = n.closePositionEarly(n1.position);
        ok(early.ok && n.findPosition(n1.position).waitingRemoval, "cierre anticipado");
        ActionResult reopen = n.reopenPosition(n1.position);
        ok(reopen.ok && !n.findPosition(n1.position).waitingRemoval, "reabrir cierre anticipado");

        ActionResult undo = n.undoAcceptedBox("THZ26063090342U003");
        ok(undo.ok && n.received.get("THZ26063090342") == 1, "anular escaneo");
        ok(n.missingBoxes("THZ26063090342", 10).contains("U003"), "U003 vuelve a faltante");

        List<CodeRecord> r2s = Arrays.asList(
            new CodeRecord("A", 10, 3.0, 0.3, null, "", ""),
            new CodeRecord("B", 10, 3.0, 0.3, null, "", "")
        );
        UnloadEngine live2 = new UnloadEngine("TEST2", r2s, s, 1, 0);
        ok(live2.scan("A-U001").ok, "A entra");
        ScanResult no = live2.scan("B-U001");
        ok(!no.ok && "SIN POSICIÓN".equals(no.status), "sin posición");
        ok("D01".equals(live2.enableNext("D")), "habilitar D01");
        ok(live2.scan("B-U001").ok, "B entra después de habilitar");

        System.out.println("OK Android core V0.2 tests");
    }
}
