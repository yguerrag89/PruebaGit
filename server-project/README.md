# ILUBOX V0.12 · Piloto de servidor LAN y PDA

Este paquete NO instala, reinicia ni modifica el servidor por sí mismo. Está diseñado
para coexistir con Nginx, Streamlit, Node y Tailscale ya instalados. No es una
certificación de producción. Antes de usar mercancía real, completar las pruebas
de aceptación al final de esta guía.

## Qué cambia

- Supervisor en navegador Windows: Packing List XLSX → plan inicial → asignar una
  PDA → consultar avance y tarimas → plantilla exclusiva WMS.
- PDA Android V0.12 independiente de V0.11: descarga el manifiesto por HTTPS;
  mantiene el motor de códigos individuales U001…UN, sin exigir orden de llegada.
- La PDA guarda cada operación localmente y envía revisiones acumuladas con historial.
  Una revisión repetida confirma lo mismo; no suma cajas de nuevo.
- La verificación física de cada tarima y su temporal WMS siguen siendo obligatorias.
  Cambiar de TR no confirma distribución. No se agrega una aprobación del supervisor.
- Cerrar **la descarga** en PDA es distinto de cerrar una tarima. Congela los datos
  para exportación; un cierre sin conexión queda pendiente. El servidor exporta
  solo cuando recibe esa revisión final. Cierre parcial exige motivo y conserva faltantes.
- Se conserva el generador WMS existente: cuatro columnas oficiales, una fila por caja,
  cantidad 1, temporal capturada en PDA y orden Putaway escrita por el supervisor.
  Repetir descarga del XLSX devuelve el mismo archivo; no debe importarse dos veces.

## Arquitectura y límites elegidos

Una instancia Python/FastAPI, un proceso Uvicorn en **127.0.0.1:8600**, SQLite WAL
en disco local y un virtual host Nginx dedicado en **192.168.100.228:8443** (propuestos;
verificar disponibilidad). No abrir 8600 a la red. No usar Docker ni instalar PostgreSQL
en esta variante: SQLite transaccional evita otra dependencia en el servidor compartido.
Una PDA por descarga; pueden existir descargas independientes. Este piloto limita
cada Packing List a un contenedor, 10 000 cajas (máximo 999 por código, U001…U999)
y 16 MiB por envío. Retirar filas de totales y corregir cantidades/volúmenes antes
de cargar: el servidor rechaza filas inválidas en vez de omitirlas. Medir carga antes
de ampliar concurrencia; para alta disponibilidad/múltiples servidores, migrar a PostgreSQL.

No hay cambios en XLWMS por API, catálogo de temporales, QR, exportación por lotes de
una descarga aún abierta, reasignación de PDA en caliente ni edición remota del motor.
La validez de una temporal se comprueba por formato, **no por existencia en XLWMS**.
El supervisor web tiene contraseña; los modos supervisor/operador de la PDA conservan
la separación visual anterior, no un control de identidad individual.

La sincronización se intenta cada 10 segundos mientras la aplicación está visible,
y al reabrirla. No se promete ejecución permanente en segundo plano. La pantalla web
es la última revisión recibida y se actualiza con «Actualizar avance»; no puede saber
cuántas operaciones tiene una PDA desconectada. No borrar datos ni reinstalar la PDA
durante una descarga: el servidor no restaura un motor Android desde un JSON.

## 1. Preparación sin cambiar los programas existentes

Copiar/descomprimir el paquete en un directorio nuevo del usuario, no encima de otra app.
Ejecutar `bash server-project/deploy/preflight.sh` desde el paquete. Si 8600/8443 están
ocupados, o ya existen usuario/directorios propuestos, detenerse y ajustar el despliegue.
Revisar también `sudo nginx -T` localmente: no publicar su salida sin ocultar secretos.
Reservar **192.168.100.228** por DHCP o configuración aprobada. No usar .227 Wi-Fi.

El administrador debe confirmar Python 3 con venv, Nginx y certificados. Si falta
python3-venv, planificar su instalación; no ejecutar actualización general ni reinicio.
No ejecutar `tailscale serve`, `tailscale funnel`, `ufw reset` ni reemplazar la web de 3100.
El Funnel existente es público y queda **fuera** de este despliegue.

## 2. Instalación aislada (administrador, después de revisión)

Solo si los nombres/rutas están libres:

```bash
sudo useradd --system --user-group --home-dir /var/lib/ilubox-putaway --shell /usr/sbin/nologin ilubox-putaway
sudo install -d -m 0755 /opt/ilubox-putaway
sudo install -d -m 0700 -o ilubox-putaway -g ilubox-putaway /var/lib/ilubox-putaway
sudo install -d -m 0750 -o root -g ilubox-putaway /etc/ilubox-putaway
```

Copiar **la carpeta server-project y su carpeta shared** del ZIP a
`/opt/ilubox-putaway/app/server-project`, propiedad root y sin escritura para el usuario
del servicio. En un checkout Git, `shared` no se guarda: ejecutar primero
`python3 scripts/package_v012.py` y usar el ZIP resultante.

Crear el entorno propio; no usar pip en el Python global:

```bash
sudo python3 -m venv /opt/ilubox-putaway/venv
sudo /opt/ilubox-putaway/venv/bin/python -m pip install -r /opt/ilubox-putaway/app/server-project/requirements.txt
sudo -u ilubox-putaway /opt/ilubox-putaway/venv/bin/python /opt/ilubox-putaway/app/server-project/manage.py init
```

La contraseña se solicita sin mostrarla ni guardarla en el historial del shell.
Conservarla en el gestor de contraseñas de la empresa. No hay contraseña predeterminada.
Copiar `deploy/lan.env.example` a `/etc/ilubox-putaway/lan.env`, propietario root,
grupo ilubox-putaway y modo 0640. Ajustar el origen HTTPS exacto si cambia la dirección.
Copiar la unidad a `/etc/systemd/system/ilubox-putaway-lan.service` y ejecutar:

```bash
sudo systemctl daemon-reload
sudo systemctl start ilubox-putaway-lan
sudo systemctl status ilubox-putaway-lan --no-pager
curl --fail --header 'Host: 192.168.100.228:8443' http://127.0.0.1:8600/health
```

`daemon-reload` relee unidades; no reinicia los servicios existentes. Si falla la nueva
unidad, consultar `journalctl -u ilubox-putaway-lan -n 80 --no-pager`. No tocar otras unidades.

## 3. TLS, Nginx y acceso exclusivamente LAN

Usar un certificado de la CA interna de la empresa con SAN IP **192.168.100.228**
(o un nombre DNS interno consistente en certificado, Nginx, ILUBOX_ORIGIN y PDA).
La CA debe estar instalada y ser confiable en Windows y Q9. La clave privada de la CA
nunca va en el APK ni en la PDA. No deshabilitar validación TLS ni usar HTTP como solución.

El administrador instala el certificado/clave del servidor en las rutas de la plantilla,
con clave legible solo por root; revisa `deploy/nginx-lan.conf.example` y crea un archivo
**nuevo** dentro de la configuración de sitios de Nginx. No cambiar default, portal, 80,
443, rutas de 3100 ni Funnel. Antes de activar: `sudo nginx -t`. Recargar Nginx solo
después de validar y dentro de una ventana acordada; volver a probar las apps existentes.

Agregar únicamente la regla LAN, después de comprobar que la política de red lo permite:

```bash
sudo ufw allow in on enp3s0 from 192.168.100.0/24 to 192.168.100.228 port 8443 proto tcp comment 'ILUBOX Putaway LAN piloto'
```

No abrir Anywhere. No eliminar reglas de 22/80 de otras apps. El endpoint propuesto es
`https://192.168.100.228:8443`. Probar desde la misma Wi-Fi/VLAN que usará la PDA.
Acceder a `/login`, cargar XLSX y guardar temporalmente la credencial de asignación que
se muestra una sola vez. En PDA: SERVIDOR → URL HTTPS → código de asignación → INICIAR.
Si se pierde el código antes de conectar la PDA, el panel permite generar otro y
revoca el anterior. También permite anular un Packing List incorrecto **solo antes
de que una PDA lo reclame**. Una vez asignado no hay reinicio remoto: puede haber
cajas capturadas offline que el servidor aún no conoce.

## 4. Respaldo, recuperación y reversión

No sincronizar la base viva, `-wal` ni `-shm` con Syncthing. El comando usa SQLite Backup
para obtener un respaldo consistente mientras se opera:

```bash
sudo -u ilubox-putaway /opt/ilubox-putaway/venv/bin/python /opt/ilubox-putaway/app/server-project/manage.py backup --output /var/lib/ilubox-putaway/respaldo-piloto-001.sqlite3
```

La ruta debe ser nueva; nunca sobrescribe. Programar respaldo diario y después de cierres,
copiar el respaldo terminado a un destino corporativo protegido y probar restauración
en una instancia aislada. Los backups contienen inventario y hashes de credenciales.
No restituir un backup antiguo sobre la base activa mientras una PDA está operando.
Si se pierde la PDA, se corrompe su estado o se restaura una base antigua: detener
esa descarga y conciliar los datos, sin asignar otra PDA ni asumir que está completa.

Para revertir el piloto: detener solo `ilubox-putaway-lan`, desactivar solo su nuevo
virtual host tras `nginx -t` y retirar solo la regla 8443 creada para el piloto.
Conservar base, backups, APK y logs para revisión. No borrar directorios ni reiniciar
el servidor. Después de aceptación, habilitar arranque con `systemctl enable ilubox-putaway-lan`.

## Pruebas de aceptación obligatorias con la Q9

1. Probar primero un contenedor sintético, no una descarga real ya iniciada en V0.11.
2. Comprobar plan: tendido, definitivas al pie y una TR; verificar U001, duplicado,
   U000, fuera de rango y lectura HID+Enter con buena velocidad.
3. Cortar Wi-Fi, escanear varias cajas, cerrar/reabrir la app sin borrar datos;
   reconectar y confirmar que la revisión recibida contiene cada caja una sola vez.
4. Intentar asignar la misma descarga a otra PDA: debe rechazarla.
5. Cambiar TR y seguir escaneando. Verificar definitiva con temporal; probar rechazo
   sin temporal y que I01 reutilizada no herede la ubicación de la tarima anterior.
6. Cerrar descarga offline, comprobar bloqueo de nuevos escaneos y ausencia de XLSX
   web hasta reconectar. Probar cierre parcial y revisión expresa de faltantes.
7. Descargar XLSX, comprobar cuatro columnas y temporales. Probar importación de
   pocas cajas en XLWMS con autorización; el generador no conoce el estado de importación.
8. Medir latencia y uso de recursos; probar respaldo/restauración aislada y acceso
   denegado fuera de LAN. Confirmar que siguen funcionando todas las otras aplicaciones.

El APK generado por CI usa firma debug: apto para piloto separado, no distribución
de producción ni actualizaciones garantizadas con firma estable. No desinstalar durante
operación. Antes de producción se requiere firma administrada, recuperación operativa,
política de acceso y aceptación de estos ensayos en el dispositivo real.
