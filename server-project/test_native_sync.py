"""Consume snapshots emitted by the actual Android SQLite outbox (Robolectric)."""
import json
import sys
import tempfile
import uuid
from pathlib import Path
from protocol import create_session, claim_session, accept_snapshot, export_wms
from store import Store, encode
from core.parser import CodeRecord, ParsedContainer
from core.optimizer import Settings
from core.pda_exchange import build_pda_manifest

directory=Path(sys.argv[1])
with tempfile.TemporaryDirectory() as temp:
    store=Store(Path(temp)/"test.sqlite3")
    store.initialize("Synthetic-native-test-2026")
    records=[CodeRecord("CAJA",2,.2,.1,1.0)]
    manifest=json.loads(build_pda_manifest(ParsedContainer("SYNC-TEST","test.xlsx","",records,[]),Settings()))
    for name in ("empty.json","pending.json","final.json"):
        # Independent runs: each Robolectric test starts a fresh local database.
        if name != "empty.json":
            with store.transaction() as con: con.execute("UPDATE sessions SET sealed=1")
        sid,pairing=create_session(store,manifest,1,0)
        token=pairing.split(".")[1];device=str(uuid.uuid4())
        claimed=claim_session(store,sid,token,device)
        packet=json.loads((directory/name).read_bytes())
        packet.update(session_id=sid,manifest_hash=claimed["manifest_hash"])
        first=accept_snapshot(store,sid,token,device,encode(packet))
        assert first==accept_snapshot(store,sid,token,device,encode(packet))
        if name=="final.json":
            content,_=export_wms(store,sid,"PAS-NATIVE")
            assert content.startswith(b"PK")
print("OK Android SQLite outbox → API validator → plantilla WMS: vacío, pendiente, cierre y reintento")
