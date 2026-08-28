package com.ilubox.descargapda;

import android.content.ContentValues;
import android.content.Context;
import com.ilubox.descargapda.core.*;
import com.ilubox.descargapda.data.*;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public class LanDatabaseTest {
    private PilotDatabase db;
    private UnloadEngine engine;
    private Context context;
    private final String sid="00000000-0000-0000-0000-000000000001";

    @Before public void setup() throws Exception {
        context=RuntimeEnvironment.getApplication();
        context.deleteDatabase("ilubox_descarga_piloto.db");
        db=new PilotDatabase(context);
        engine=new UnloadEngine("SYNC-TEST",Arrays.asList(new CodeRecord("CAJA",2,.2,.1,1.0,"","")),new Settings(),1,0,"TRASLADO");
        db.startNewSession(engine,"Inicio prueba");
        // Network credentials/Keystore are deliberately NOT mocked as secure storage.
        // This fixture tests real SQLite state/outbox transactions, not device TLS.
        ContentValues v=new ContentValues();v.put("id",1);v.put("server","https://testserver");
        v.put("session_id",sid);v.put("secret","test-only-not-a-credential");
        v.put("device",sid);v.put("manifest_hash","fixture");
        db.getWritableDatabase().insertOrThrow("sync_state",null,v);
    }
    @After public void close() { db.close(); }
    private void scan(String barcode) throws Exception { ScanResult r=engine.scanTransfer(barcode); assertTrue(r.message,r.ok);db.saveScanAndEngine(r,engine); }
    private JSONObject packet() throws Exception { return new JSONObject(new String(db.pendingSnapshot(),StandardCharsets.UTF_8)); }

    @Test public void durableRetryAndLateAck() throws Exception {
        byte[] empty=db.pendingSnapshot();
        Path output=Paths.get("build/lan-fixtures");Files.createDirectories(output);Files.write(output.resolve("empty.json"),empty);
        scan("CAJAU001");
        byte[] first=db.pendingSnapshot();long revision=packet().getLong("revision");
        assertArrayEquals(first,db.pendingSnapshot());
        db.close();db=new PilotDatabase(context);engine=db.loadEngine();
        assertArrayEquals(first,db.pendingSnapshot());
        scan("CAJAU002");
        db.acknowledge(sid,revision);
        assertNotNull(db.pendingSnapshot());
        assertEquals(2,packet().getJSONObject("result").getJSONObject("progress").getInt("received"));
        db.acknowledge("another-session",999);
        assertNotNull(db.pendingSnapshot());
        Files.write(output.resolve("pending.json"),db.pendingSnapshot());
        db.acknowledge(sid,db.syncState().getLong("revision"));
        assertNull(db.pendingSnapshot());
    }

    @Test public void finalRevisionBlocksAllMutations() throws Exception {
        scan("CAJAU001");scan("CAJAU002");
        try { db.sealForServer("");fail(); } catch(IllegalStateException expected) { }
        ActionResult change=engine.changeCurrentTransfer();assertTrue(change.ok);db.saveActionAndEngine("TRASLADO CAMBIADO","",change.message,engine);
        ActionResult verify=engine.validateFinalPallet("T-01","OP-TEST","2B-TMP-01");assertTrue(verify.message,verify.ok);db.saveActionAndEngine("TARIMA VERIFICADA","T-01",verify.message,engine);
        assertFalse(db.canReplaceSession());
        db.sealForServer("");assertTrue(db.isServerSealed());
        byte[] bytes=db.pendingSnapshot();assertTrue(packet().getBoolean("sealed"));
        Path output=Paths.get("build/lan-fixtures");Files.createDirectories(output);Files.write(output.resolve("final.json"),bytes);
        try { db.saveEngine(engine);fail(); } catch(IllegalStateException expected) { }
        try { db.clearSession();fail(); } catch(IllegalStateException expected) { }
        try { db.startNewSession(engine,"bad");fail(); } catch(IllegalStateException expected) { }
        assertArrayEquals(bytes,db.pendingSnapshot());
        db.close();db=new PilotDatabase(context);assertTrue(db.isServerSealed());
        assertArrayEquals(bytes,db.pendingSnapshot());
        db.acknowledge(sid,packet().getLong("revision"));assertTrue(db.canReplaceSession());
        assertNull(db.pendingSnapshot());
    }

    @Test public void auditAndEngineRollbackTogether() throws Exception {
        long before=db.syncState().getLong("revision");int events=db.allEvents().size();
        db.getWritableDatabase().execSQL("CREATE TRIGGER fail_save BEFORE INSERT ON session_state BEGIN SELECT RAISE(ABORT,'test failure'); END");
        ScanResult result=engine.scanTransfer("CAJAU001");
        try { db.saveScanAndEngine(result,engine);fail(); } catch(Exception expected) { }
        assertEquals(events,db.allEvents().size());assertEquals(before,db.syncState().getLong("revision"));
        assertEquals(0,db.loadEngine().acceptedBoxCount());
    }

    @Test public void rejectsUnsafeServerAddress() throws Exception {
        for(String address:Arrays.asList("http://example.com","https://user:pass@example.com","https://example.com/path","https://example.com?token=x")) {
            try { LanClient.origin(address);fail(address); } catch(IllegalArgumentException expected) { }
        }
        assertEquals("https://example.com:8443",LanClient.origin("https://example.com:8443/"));
    }
}
