import com.ilubox.descargapda.core.*;
import java.util.*;

public class EngineSelfTest {
    private static void ok(boolean x, String msg) {
        if (!x) throw new AssertionError(msg);
    }

    public static void main(String[] args) {
        ok("0.11-temporal-obligatoria".equals(UnloadEngine.ENGINE_VERSION), "versión del contrato PDA");
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

        ScanResult unit = live.scan("UNITA");
        ok(unit.ok && "UNITAU001".equals(unit.normalizedBarcode), "unitario base normaliza a U001");
        ok(live.positionCards().size() == 20, "mapa 20 posiciones");

        // Reglas V0.2: U001-U004 válidas, U005 fuera de rango, duplicado por barcode normalizado.
        List<CodeRecord> nr = Arrays.asList(
            new CodeRecord("THZ26063090342", 4, 0.40, 0.10, 12.0, "", "")
        );
        UnloadEngine n = new UnloadEngine("NORM", nr, s, 1, 0);
        ScanResult n1 = n.scan("THZ26063090342U001");
        ok(n1.ok && n1.boxNumber == 1, "U001 válida");
        ScanResult corrupt = n.scan("THZ26063THZ26063090342U001090342U001");
        ok(!corrupt.ok && "LECTURA INCOMPLETA".equals(corrupt.status), "lectura corrupta no inventa Uxxx");
        ScanResult exactDup = n.scan("THZ26063090342U001THZ26063090342U001");
        ok(!exactDup.ok && "DUPLICADA".equals(exactDup.status), "barcode completo repetido conserva U001");
        ok("THZ26063090342U001".equals(exactDup.normalizedBarcode), "barcode normalizado correcto");
        ok(n.received.get("THZ26063090342") == 1, "duplicada no incrementa");

        ScanResult n2 = n.scan("THZ26063090342U003");
        ok(n2.ok && n.received.get("THZ26063090342") == 2, "U003 válida aunque U002 falte");
        ScanResult outRange = n.scan("THZ26063090342U005");
        ok(!outRange.ok && "FUERA DE RANGO".equals(outRange.status), "U005 fuera de rango");
        ok(n.received.get("THZ26063090342") == 2, "fuera de rango no incrementa");
        ok(n.missingBoxes("THZ26063090342", 10).equals(Arrays.asList("U002", "U004")), "faltantes exactos");

        ScanResult incomplete = n.scan("THZ26063090342");
        ok(!incomplete.ok && "LECTURA INCOMPLETA".equals(incomplete.status), "multi-caja requiere Uxxx");
        ScanResult detachedU = n.scan("U002THZ26063090342");
        ok(!detachedU.ok && "LECTURA INCOMPLETA".equals(detachedU.status), "Uxxx suelto no identifica la caja");

        // Cierre anticipado + reapertura supervisor.
        ActionResult early = n.closePositionEarly(n1.position);
        ok(early.ok && n.findPosition(n1.position).waitingRemoval, "cierre anticipado");
        ActionResult reopen = n.reopenPosition(n1.position);
        ok(reopen.ok && !n.findPosition(n1.position).waitingRemoval, "reabrir cierre anticipado");

        // Anular caja aceptada devuelve U003 a faltante y no borra trazabilidad del resto.
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


        // V0.3 MANUAL: operador selecciona tarima; mismo código en otra tarima abierta genera advertencia.
        List<CodeRecord> manualRecords = Arrays.asList(
            new CodeRecord("MANA", 4, 0.40, 0.10, 20.0, "", ""),
            new CodeRecord("MANB", 2, 0.20, 0.10, 30.0, "", "")
        );
        UnloadEngine m = new UnloadEngine("MANUAL", manualRecords, s, 2, 1, "MANUAL");
        ok(m.isManualMode(), "modo manual");
        ok("I01".equals(m.getManualActivePosition()), "tarima activa inicial");
        ScanResult m1 = m.scanManual("MANAU001", "I01", false);
        ok(m1.ok && "I01".equals(m1.position), "MANA entra manualmente en I01");
        ScanResult m2 = m.scanManual("MANBU001", "I01", false);
        ok(m2.ok && "I01".equals(m2.position), "códigos distintos pueden compartir I01");

        ScanResult warn = m.scanManual("MANAU002", "I02", false);
        ok(!warn.ok && "CÓDIGO EN OTRA TARIMA".equals(warn.status), "advierte código ya ubicado");
        ok("I01".equals(warn.position), "advierte posición existente");
        ok(m.received.get("MANA") == 1, "advertencia no contabiliza");

        ScanResult redirected = m.scanManual("MANAU002", "I01", false);
        ok(redirected.ok && m.received.get("MANA") == 2, "usar tarima existente");
        ScanResult warn2 = m.scanManual("MANAU003", "I02", false);
        ok(!warn2.ok && "CÓDIGO EN OTRA TARIMA".equals(warn2.status), "segunda advertencia");
        ScanResult split = m.scanManual("MANAU003", "I02", true);
        ok(split.ok && "I02".equals(split.position), "división confirmada");
        ScanResult sameSplitPallet = m.scanManual("MANAU004", "I02", false);
        ok(sameSplitPallet.ok, "una vez dividido, seguir en la misma tarima no vuelve a advertir");
        ok(m.findPosition("I01").boxesForCode("MANA") == 2, "I01 conserva 2 cajas MANA");
        ok(m.findPosition("I02").boxesForCode("MANA") == 2, "I02 conserva 2 cajas MANA");

        ScanResult dupManual = m.scanManual("MANAU004", "I01", false);
        ok(!dupManual.ok && "DUPLICADA".equals(dupManual.status) && "I02".equals(dupManual.position),
                "duplicada muestra tarima física original");

        ActionResult closeI01 = m.closePositionEarly("I01");
        ok(closeI01.ok, "cerrar tarima manual");
        ScanResult afterClosed = m.scanManual("MANBU002", "I02", false);
        ok(afterClosed.ok, "código puede continuar en otra tarima si la anterior está cerrada");

        // V0.4 BUFFER MODULAR: 1 código por sector, unitarios directos, completos listos y promoción de grandes.
        List<CodeRecord> bufferRecords = Arrays.asList(
            new CodeRecord("BUF1", 2, 0.20, 0.10, 10.0, "", ""),
            new CodeRecord("BUF2", 3, 0.30, 0.10, 11.0, "", ""),
            new CodeRecord("HUGE", 10, 2.50, 0.25, 12.0, "", ""),
            new CodeRecord("ONE", 1, 0.05, 0.05, 5.0, "", "")
        );
        UnloadEngine b = new UnloadEngine("BUFFER", bufferRecords, s, 2, 0, "BUFFER", 2);
        ok(b.isBufferMode(), "modo buffer");
        ok(b.bufferPalletCount() == 2 && b.bufferTotalSectors() == 8, "2 buffers x 4 sectores");

        ScanResult b1 = b.scanBuffer("BUF1U001");
        ScanResult b2 = b.scanBuffer("BUF2U001");
        ok(b1.ok && b1.position.startsWith("B"), "BUF1 va a sector");
        ok(b2.ok && b2.position.startsWith("B") && !b2.position.equals(b1.position), "cada código usa sector propio");
        ok(b.bufferOccupiedSectors() == 2, "dos sectores ocupados");
        ScanResult b1c = b.scanBuffer("BUF1U002");
        ok(b1c.ok && "CÓDIGO COMPLETO".equals(b1c.status), "BUF1 completo");
        ok(!b.bufferReadyCandidates().isEmpty(), "BUF1 listo para definitiva");

        ScanResult one = b.scanBuffer("ONEU001");
        ok(one.ok && !one.position.startsWith("B") && "UNITARIO".equals(one.status), "unitario directo a definitiva");

        // HUGE necesita >1 tarima: al acumular suficiente CBM aparece un bloque promocionable.
        for (int i = 1; i <= 7; i++) {
            ScanResult hx = b.scanBuffer(String.format("HUGEU%03d", i));
            ok(hx.ok, "HUGE caja " + i);
        }
        boolean hugeReady = false;
        for (BufferCandidate c : b.bufferReadyCandidates()) if ("HUGE".equals(c.code)) hugeReady = true;
        ok(hugeReady, "bloque grande listo antes de completar código");

        List<BufferCandidate> proposal = b.suggestDefinitive(new HashSet<>());
        ok(!proposal.isEmpty(), "propuesta definitiva");
        ArrayList<String> ids = new ArrayList<>();
        for (BufferCandidate c : proposal) ids.add(c.id);
        ActionResult formed = b.formDefinitiveFromBuffer(ids);
        ok(formed.ok && b.findPosition(formed.position).waitingRemoval, "definitiva formada y lista para retirar");
        ok(b.bufferFreeSectors() > 0, "formar definitiva libera sectores buffer");

        // V0.9 TRASLADO DIRIGIDO: Uxxx consecutivo, directas dinámicas y estado WMS estricto.
        List<CodeRecord> transferRecords = Arrays.asList(
            new CodeRecord("GRANDE", 4, 1.60, 0.40, 30.0, "", ""),
            new CodeRecord("GRANDE2", 2, 1.50, 0.75, 30.0, "", ""),
            new CodeRecord("CHICO", 3, 0.30, 0.10, 5.0, "", "")
        );
        UnloadEngine t = new UnloadEngine("TRASLADO", transferRecords, s, 1, 0, "TRASLADO");
        ok(t.isTransferMode(), "modo traslado dirigido");
        ok(t.plannedTendidoPalletCount() == 1, "planifica una tarima de tendido");
        ok(t.initialDirectFootPalletCount() == 1, "respeta una sola posición al pie");
        ok(t.initialPhysicalPalletCount() == 3, "tendido + directa al pie + TR-01");

        // El orden de llegada no altera la tarima: U004 puede llegar antes que U001.
        ScanResult tg = t.scanTransfer("GRANDEU004");
        ok(tg.ok && tg.directToFinal && tg.position.startsWith("T-"), "grande directo a definitiva");
        ok("I01".equals(tg.physicalPosition), "grande muestra posición física al pie");
        ok(tg.transferPallet.isEmpty(), "grande no usa traslado");
        ScanResult tg1 = t.scanTransfer("GRANDEU001");
        ok(tg1.ok && tg1.position.equals(tg.position), "Uxxx aleatorios permanecen en tarima activa");

        ScanResult noFoot = t.scanTransfer("GRANDE2U001");
        ok(!noFoot.ok && "SIN POSICIÓN AL PIE".equals(noFoot.status), "límite físico al pie");

        ok("PENDIENTE_VERIFICAR".equals(t.boxPhysicalState("GRANDEU004")), "directa tampoco prueba presencia por escaneo");
        ActionResult closedDirect = t.closeDirectPalletEarly(tg.position, "Falta de espacio");
        ok(closedDirect.ok, "cierre físico anticipado de directa");
        ActionResult validatedDirect = t.validateFinalPallet(tg.position, "OP-01", "2B-TMP-01");
        ok(validatedDirect.ok && t.validatedFinalPallets.contains(tg.position), "directa validada");
        ok(t.isBoxWmsEligible("GRANDEU004"), "directa validada elegible WMS");
        ok(!t.scanTransfer("GRANDE2U001").ok, "verificar no libera la posición");
        ok(t.releaseFinalPallet(tg.position).ok, "retiro físico independiente");
        ScanResult g2 = t.scanTransfer("GRANDE2U001");
        ok(g2.ok && "I01".equals(g2.physicalPosition), "posición liberada se reutiliza");

        ScanResult tc = t.scanTransfer("CHICOU001");
        ok(tc.ok && !tc.directToFinal && tc.position.startsWith("T-"), "chico tiene destino final");
        ok("TR-01".equals(tc.transferPallet), "chico usa traslado activo");
        ok("PENDIENTE_VERIFICAR".equals(t.boxPhysicalState("CHICOU001")), "chico aún no está verificado");
        ok(!t.isBoxWmsEligible("CHICOU001"), "traslado sin distribuir no llega al WMS");
        ok(t.currentTransferBoxCount() == 1 && t.currentTransferDestinations().size() == 1,
                "resumen del traslado activo");
        ActionResult sent = t.changeCurrentTransfer();
        ok(sent.ok && t.isTransferClosed("TR-01") && "TR-02".equals(t.currentTransferPallet()), "cambia traslado sin bloqueo");
        ok("PENDIENTE_VERIFICAR".equals(t.boxPhysicalState("CHICOU001")), "cambiar traslado no confirma llegada física");
        ok(!t.isBoxWmsEligible("CHICOU001"), "tarima aún necesita validación");
        ScanResult tc2 = t.scanTransfer("CHICOU002");
        ok(tc2.ok && "TR-02".equals(tc2.transferPallet), "continúa capturando mientras se acomoda el anterior");
        ScanResult td = t.scanTransfer("CHICOU002");
        ok(!td.ok && "DUPLICADA".equals(td.status) && td.position.equals(tc2.position),
                "duplicado conserva destino definitivo");
        // Completar la captura, cambiar la TR activa y revisar físicamente habilita WMS.
        ScanResult tc3 = t.scanTransfer("CHICOU003");
        ok(tc3.ok, "tercera caja chica");
        ok(!t.validateFinalPallet(tc.position, "OP-01", "2B-TMP-02").ok, "no verifica cajas que siguen en la TR activa");
        ok(t.changeCurrentTransfer().ok, "cambia TR-02 por TR-03");
        ActionResult validateTendido = t.validateFinalPallet(tc.position, "OP-01", "2B-TMP-02");
        ok(validateTendido.ok && t.isBoxWmsEligible("CHICOU001")
                && t.isBoxWmsEligible("CHICOU002") && t.isBoxWmsEligible("CHICOU003"),
                "tendido validado habilita sus cajas para WMS");

        boolean sawDirect = false, sawTendido = false;
        for (UnloadEngine.FinalPalletView v : t.finalPalletViews()) {
            if (v.direct && v.received > 0) sawDirect = true;
            if (!v.direct && v.received > 0) sawTendido = true;
        }
        ok(sawDirect && sawTendido, "tablero separa al pie y tendido final");

        System.out.println("OK Android core V0.11: AUTO/MANUAL/BUFFER + TRASLADO continuo + temporal WMS obligatoria");
    }
}
