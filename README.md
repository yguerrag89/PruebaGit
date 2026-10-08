# Movimientos Q9 independiente v0.3.0

APK Android nativa: importa BoxInventory .xlsx desde el selector de archivos de Android, conserva inventario y escaneos en SQLite y genera plantillas XLWMS y reportes directamente en la PDA. No requiere servidor ni conexión de red.

Cajas aceptadas por lotes con destino físico confirmado. Cajas bloqueadas y casos para revisión quedan fuera de las plantillas y se conservan en el reporte; su ubicación física es opcional. Se puede cargar un corte más reciente antes de generar. Un paquete inmutable por operación, plantillas de hasta 500 movimientos, auditoría Excel y respaldo completo con restauración sin sobrescribir otra captura.

Paquete `com.ilubox.movimientosq9`, versionCode 30, Android 6 o posterior. Usa el lector como teclado con sufijo Enter. Las consultas corresponden al corte cargado y no consultan el WMS en vivo. Generar no aplica movimientos al WMS.

`build_native.py` usa JDK 17, SDK 35, aapt2, d8, zipalign y apksigner, sin dependencias externas. Recibe rutas y firma mediante variables `Q9_*`; no incluye una clave privada. `build_tests.py` compila instrumentación para probar importación XLSX, recuperación, lotes, bloqueo, idempotencia, límite 500, bitácora y capacidad de inventario. Los fixtures y datos generados son ficticios.
