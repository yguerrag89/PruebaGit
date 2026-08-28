import argparse
import getpass
import os
from store import Store


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description="Administración local de ILUBOX LAN")
    parser.add_argument("action", choices=["init", "backup"])
    parser.add_argument("--db", default=os.environ.get("ILUBOX_DB", "/var/lib/ilubox-putaway/putaway.sqlite3"))
    parser.add_argument("--output")
    args = parser.parse_args()
    store = Store(args.db)
    if args.action == "init":
        password = getpass.getpass("Contraseña del supervisor (mínimo 14 caracteres): ")
        if password != getpass.getpass("Repita la contraseña: "):
            parser.error("No coinciden las contraseñas.")
        store.initialize(password)
        print("Base inicializada. No se modificaron otros servicios.")
    else:
        if not args.output:
            parser.error("backup requiere --output con una ruta nueva")
        store.backup(args.output)
        print("Respaldo SQLite consistente completado.")


if __name__ == "__main__":
    main()
