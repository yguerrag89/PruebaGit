#!/usr/bin/env bash
# Read-only inventory. No package installation, reload, firewall change or reboot.
set -eu
uname -m
python3 --version
command -v python3
df -h /opt /var/lib
free -h
ss -lnt '( sport = :8600 or sport = :8443 )'
systemctl is-active nginx || true
if getent passwd ilubox-putaway; then
    echo 'ATENCIÓN: ya existe el usuario propuesto; verificar propietario y uso.'
fi
for target in /opt/ilubox-putaway /var/lib/ilubox-putaway /etc/ilubox-putaway /etc/systemd/system/ilubox-putaway-lan.service; do
    if [ -e "$target" ]; then
        echo "ATENCIÓN: ya existe $target; no sobrescribir."
    fi
done
echo 'Inventario terminado. La configuración de nginx, UFW y TLS requiere revisión del administrador.'
