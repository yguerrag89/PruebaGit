# Ilubox WMS V0.9 — Windows + PDA Q9

Esta rama contiene dos aplicaciones coordinadas:

- `windows-project`: carga el Packing List, genera el manifiesto para PDA, valida el resultado móvil y crea la plantilla oficial WMS.
- `android-project`: escaneo offline en AUTOID Q9, asignación de tarimas definitivas/traslado y exportación estricta hacia Windows.

El contrato compartido exige códigos individuales consecutivos `U001…UN` para cualquier grupo de varias cajas y utiliza una huella del Packing List para evitar mezclar descargas.

La V0.9 asigna los códigos grandes de forma dinámica: una tarima `T-xx` activa ocupa una posición libre al pie del contenedor y recibe cualquier `Uxxx` válido hasta cerrarse. Los códigos pequeños se planifican en el tendido final y viajan agrupados en `TR-xx`.

El archivo WMS es independiente del reporte operativo. Windows solo habilita la exportación oficial cuando la caja está en su tarima definitiva, el traslado fue distribuido y la tarima fue validada.

La compilación automática publica los entregables en `release/`.
