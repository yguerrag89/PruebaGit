"""Empaqueta la aplicación Windows V0.15 sin sesiones, secretos ni datos operativos."""
from pathlib import Path
import zipfile


ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / "windows-project"
TARGET = ROOT / "release/v0.15/Ilubox_WMS_Windows_v0.15_Operacion_Simplificada.zip"

ALLOWED_SUFFIXES = {".py", ".txt", ".md", ".bat", ".xlsx", ".json"}


def include(path: Path) -> bool:
    relative = path.relative_to(SOURCE)
    if "__pycache__" in relative.parts or ".streamlit" in relative.parts:
        return False
    if relative.parts[:2] == ("data", "sessions"):
        return path.name == "LEEME.txt"
    return path.suffix.lower() in ALLOWED_SUFFIXES


TARGET.parent.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(TARGET, "w", zipfile.ZIP_DEFLATED) as archive:
    for path in sorted(SOURCE.rglob("*")):
        if path.is_file() and include(path):
            archive.write(path, Path("Ilubox_WMS_Windows_v0.15") / path.relative_to(SOURCE))

print(TARGET)
