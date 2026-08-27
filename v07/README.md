# V0.7 Supervisor Simple Q9

Variante de prueba para reducir la información visible y concentrar la pantalla del supervisor en decisiones operativas.

- Resumen de cajas, definitivas activas, traslado actual e incidencias.
- Bloques separados para definitivas al pie del contenedor y en el tendido final.
- Cada tarima muestra solamente número, avance y estado.
- El desglose por código aparece al tocar la tarima.
- Flujo de traslado controlado: `EN FORMACIÓN → EN DISTRIBUCIÓN → DISTRIBUIDA`.
- Durante la distribución se bloquean nuevos escaneos para evitar mezclar dos viajes.
- Reportes y correcciones quedan dentro de un menú secundario.

Esta versión conserva la lógica de asignación de V0.6 y modifica principalmente el control del traslado y la experiencia del supervisor.
