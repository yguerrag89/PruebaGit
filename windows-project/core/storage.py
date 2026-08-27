from __future__ import annotations

from datetime import datetime, timezone
from hashlib import sha256
from pathlib import Path
import json

from .live import LiveUnload


APP_DIR = Path(__file__).resolve().parent.parent
SESSION_DIR = APP_DIR / "data" / "sessions"


def _digest(container_key: str) -> str:
    return sha256(container_key.encode("utf-8")).hexdigest()[:20]


def _path(container_key: str, suffix: str) -> Path:
    SESSION_DIR.mkdir(parents=True, exist_ok=True)
    return SESSION_DIR / f"{_digest(container_key)}.{suffix}.json"


def _atomic_write(path: Path, payload: dict) -> None:
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    temporary.replace(path)


def save_live_session(container_key: str, live: LiveUnload) -> Path:
    path = _path(container_key, "session")
    _atomic_write(path, {
        "schema_version": 1,
        "container_key": container_key,
        "saved_at": datetime.now(timezone.utc).isoformat(),
        "live": live.to_state(),
    })
    return path


def load_live_session(container_key: str, records, settings) -> LiveUnload | None:
    path = _path(container_key, "session")
    if not path.exists():
        return None
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
        if payload.get("schema_version") != 1:
            return None
        if payload.get("container_key") != container_key:
            return None
        return LiveUnload.from_state(records, settings, payload.get("live") or {})
    except (OSError, ValueError, TypeError, json.JSONDecodeError):
        return None


def delete_live_session(container_key: str) -> None:
    path = _path(container_key, "session")
    if path.exists():
        path.unlink()


def save_wms_config(container_key: str, config: dict) -> Path:
    path = _path(container_key, "wms")
    _atomic_write(path, {
        "schema_version": 1,
        "container_key": container_key,
        "saved_at": datetime.now(timezone.utc).isoformat(),
        "config": config,
    })
    return path


def load_wms_config(container_key: str) -> dict:
    path = _path(container_key, "wms")
    if not path.exists():
        return {}
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
        if payload.get("schema_version") != 1:
            return {}
        if payload.get("container_key") != container_key:
            return {}
        config = payload.get("config")
        return config if isinstance(config, dict) else {}
    except (OSError, ValueError, TypeError, json.JSONDecodeError):
        return {}
