import com.ilubox.descargapda.core.*;
import java.util.*;

public class V016IdentitySelfTest {
    private static int checks;
    private static void ok(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        Settings s = new Settings();

        UnloadEngine u = new UnloadEngine("U6", Arrays.asList(
                new CodeRecord("THZTEST", 1000, 10.0, 0.01, 1.0, "", "")
        ), s, 1, 0, "MANUAL");
        UnloadEngine.ParsedScan u1000 = u.parseScan("THZTESTU1000");
        ok(u1000.valid && u1000.boxNumber == 1000 && "THZTESTU1000".equals(u1000.normalizedBarcode),
                "U_SEQUENCE admite más de tres dígitos sin truncar identidad");

        UnloadEngine moyu = new UnloadEngine("MOYU", Arrays.asList(
                new CodeRecord("MOYU10042606100005", 8, 0.8, 0.1, 2.0, "", "")
        ), s, 1, 0, "MANUAL");
        UnloadEngine.ParsedScan m = moyu.parseScan("MOYU10042606100005-5");
        ok(m.valid && "MOYU10042606100005-5".equals(m.normalizedBarcode) && m.boxNumber == 5,
                "MOYU usa secuencia con guion");
        ok(!moyu.parseScan("MOYU10042606100005-9").valid, "MOYU respeta cantidad esperada");

        CodeRecord zgc = new CodeRecord("ZGC1-20260525", 6, 0.6, 0.1, 2.0, "", "");
        ok(CodeRecord.HYPHEN_UNIQUE.equals(zgc.identityMode) && zgc.isDynamicIdentity(),
                "ZGC se reconoce como identidad externa no consecutiva");
        UnloadEngine z = new UnloadEngine("ZGC", Arrays.asList(zgc), s, 1, 0, "TRASLADO");
        int[] suffixes = {23, 33, 21, 32, 28, 27};
        for (int suffix : suffixes) {
            ScanResult r = z.scanTransfer(String.format(Locale.ROOT, "ZGC1-20260525-%04d", suffix));
            ok(r.ok, "ZGC externo válido " + suffix);
        }
        ScanResult extra = z.scanTransfer("ZGC1-20260525-0040");
        ok(!extra.ok && "EXCESO".equals(extra.status), "séptima identidad ZGC no se contabiliza");
        ScanResult duplicate = z.scanTransfer("ZGC1-20260525-0023");
        ok(!duplicate.ok && "DUPLICADA".equals(duplicate.status), "duplicado ZGC no se contabiliza");

        CodeRecord explicit = new CodeRecord("FAMILIA-X", 2, 0.2, 0.1, 1.0, "", "",
                CodeRecord.EXPLICIT, Arrays.asList("CAJA-ABC-0091", "CAJA-ABC-0127"));
        UnloadEngine e = new UnloadEngine("EXP", Arrays.asList(explicit), s, 1, 0, "MANUAL");
        UnloadEngine.ParsedScan ex = e.parseScan("CAJA-ABC-0127");
        ok(ex.valid && "FAMILIA-X".equals(ex.code) && ex.boxNumber == 2,
                "lista explícita resuelve identidad exacta sin depender del formato");

        System.out.println("OK Android V0.16 identidad: " + checks + " comprobaciones");
    }
}
