# Ilubox WMS V0.8 — Windows + PDA Q9

Esta rama contiene dos aplicaciones coordinadas:

- `windows-project`: carga el Packing List, genera el manifiesto para PDA, valida el resultado móvil y crea la plantilla oficial WMS.
- `android-project`: escaneo offline en AUTOID Q9, asignación de tarimas definitivas/traslado y exportación estricta hacia Windows.

El contrato compartido exige código individual `Uxxx` para cualquier grupo de varias cajas y utiliza una huella del Packing List para evitar mezclar descargas.

La compilación automática publica los entregables en `release/`.
