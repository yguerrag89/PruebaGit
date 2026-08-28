"""One central SQLite database; never share this file with PDAs or Syncthing."""
import contextlib
import hashlib
import json
import secrets
import sqlite3
import time
from pathlib import Path


def digest(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def encode(value) -> bytes:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"), allow_nan=False).encode()


def password_hash(password, salt=None):
    salt = salt or secrets.token_hex(16)
    return salt + ":" + hashlib.pbkdf2_hmac("sha256", password.encode(), salt.encode(), 600_000).hex()


class Store:
    def __init__(self, path):
        self.path = str(path)

    @contextlib.contextmanager
    def transaction(self):
        con = sqlite3.connect(self.path, timeout=10, isolation_level=None)
        con.row_factory = sqlite3.Row
        try:
            con.execute("PRAGMA foreign_keys=ON")
            con.execute("BEGIN IMMEDIATE")
            yield con
            con.commit()
        except BaseException:
            con.rollback()
            raise
        finally:
            con.close()

    def initialize(self, password):
        if len(password) < 14:
            raise ValueError("Use una contraseña de al menos 14 caracteres.")
        Path(self.path).parent.mkdir(parents=True, exist_ok=True)
        with sqlite3.connect(self.path) as con:
            con.execute("PRAGMA journal_mode=WAL")
            con.executescript('''
                CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS sessions (
                    id TEXT PRIMARY KEY, container TEXT NOT NULL, manifest TEXT NOT NULL,
                    manifest_hash TEXT NOT NULL, left_positions INTEGER NOT NULL,
                    right_positions INTEGER NOT NULL, pairing_hash TEXT NOT NULL,
                    device TEXT, revision INTEGER NOT NULL DEFAULT 0,
                    payload TEXT, payload_hash TEXT, sealed INTEGER NOT NULL DEFAULT 0,
                    created REAL NOT NULL, updated REAL NOT NULL);
                CREATE UNIQUE INDEX IF NOT EXISTS active_container ON sessions(container) WHERE sealed=0;
                CREATE TABLE IF NOT EXISTS web_sessions (
                    token_hash TEXT PRIMARY KEY, csrf TEXT NOT NULL, expires REAL NOT NULL);
                CREATE TABLE IF NOT EXISTS exports (
                    id INTEGER PRIMARY KEY, session_id TEXT NOT NULL REFERENCES sessions(id),
                    revision INTEGER NOT NULL, order_id TEXT NOT NULL,
                    content BLOB NOT NULL, hash TEXT NOT NULL, created REAL NOT NULL,
                    UNIQUE(session_id, revision));
                CREATE TABLE IF NOT EXISTS attempts (
                    key TEXT PRIMARY KEY, failures INTEGER NOT NULL, expires REAL NOT NULL);
            ''')
            if con.execute("SELECT 1 FROM meta WHERE key='password'").fetchone():
                raise ValueError("La base ya está inicializada; no se reemplazó la contraseña.")
            con.execute("INSERT INTO meta VALUES ('password', ?)", (password_hash(password),))
            con.execute("INSERT INTO meta VALUES ('schema', '1')")

    def get(self, session_id):
        with self.transaction() as con:
            row = con.execute("SELECT * FROM sessions WHERE id=?", (session_id,)).fetchone()
            return dict(row) if row else None

    def backup(self, target):
        # Exclusive creation avoids overwriting a previous backup.
        with open(target, "xb"):
            pass
        with sqlite3.connect(self.path) as source, sqlite3.connect(target) as destination:
            source.backup(destination)

    def rate_limit(self, key):
        with self.transaction() as con:
            now = time.time()
            con.execute("DELETE FROM attempts WHERE expires < ?", (now,))
            row = con.execute("SELECT failures FROM attempts WHERE key=?", (key,)).fetchone()
            if row and row[0] >= 20:
                return False
            con.execute("INSERT INTO attempts VALUES (?,1,?) ON CONFLICT(key) DO UPDATE SET failures=failures+1",
                        (key, now + 300))
            return True

