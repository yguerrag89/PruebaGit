"""Build an isolated Ubuntu archive from tracked source; no live data or secrets."""
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parent.parent
target = ROOT / "release/v0.12/Ilubox_WMS_Ubuntu_v0.12.zip"
lab_target = ROOT / "release/v0.12/Ilubox_WMS_Laboratorio_Windows_v0.12_R2.zip"


def build(archive_path):
    archive_path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(archive_path, "w", zipfile.ZIP_DEFLATED) as archive:
        for path in sorted((ROOT / "server-project").rglob("*")):
            if path.is_file() and (path.suffix in {".py", ".txt", ".md", ".html", ".sh", ".bat", ".service", ".example"}) and "__pycache__" not in path.parts:
                archive.write(path, path.relative_to(ROOT))
        for path in sorted((ROOT / "windows-project/core").glob("*.py")):
            archive.write(path, "server-project/shared/core/" + path.name)
        template = ROOT / "windows-project/assets/templates/Plantilla_oficial_WMS_PutawayCrossDockImport.xlsx"
        archive.write(template, "server-project/shared/assets/templates/" + template.name)


for output in (target, lab_target):
    build(output)
    print(output)
