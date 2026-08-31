# V0.13 — Manual Asistida Local

## Objetivo

Reducir decisiones y toques durante la descarga sin perder identidad individual ni permitir que una caja no validada llegue a la plantilla WMS.

## Flujo de aceptación

### Windows, antes de descargar

1. Cargar el Packing List.
2. Revisar contenedor, cantidades y advertencias.
3. Descargar el archivo JSON para PDA.

### Q9, durante la descarga

1. Importar el JSON e iniciar **MANUAL ASISTIDA**.
2. Seleccionar `I01`, `I02`, `D01`, etc. La selección continúa activa; no se escanea de nuevo entre cajas.
3. Escanear una caja individual. La pantalla confirma `T-xxx · Ixx/Dxx` y el avance de la tarima.
4. Si el mismo código ya está en otra tarima abierta, regresar a ella o confirmar conscientemente la división.
5. Pulsar **TARIMA LLENA / NO CABE MÁS** cuando corresponda. Completar el Packing List también deja las tarimas con cajas listas para revisar.
6. Abrir la tarima cerrada y pulsar **VALIDAR TARIMA**. Comprobar físicamente cajas y cantidades, registrar responsable y temporal WMS.
7. Retirar la tarima físicamente. Después pulsar **RETIRAR Y LIBERAR Ixx/Dxx**.
8. Exportar el resultado JSON para Windows cuando todas las tarimas con cajas estén validadas.

### Windows, después de descargar

1. Importar el resultado de la misma descarga.
2. La aplicación concilia cada `Uxxx`, la `T-xxx`, la posición física y la temporal WMS.
3. Registrar la orden Putaway.
4. Si faltan cajas, confirmar el cierre parcial y escribir el motivo.
5. Revisar la vista previa y descargar el XLSX exclusivo WMS.
6. Usar primero la función de análisis de XLWMS antes de ejecutar la carga definitiva.

## Datos que nunca deben confundirse

| Dato | Ejemplo | Función |
|---|---|---|
| Caja individual | `MJ260510161U003` | Identidad de una caja concreta |
| Tarima definitiva | `T-007` | Identidad logística estable |
| Posición física | `I03` | Espacio reutilizable durante la descarga |
| Temporal WMS | `2B-TMP-07` | Destino capturado al validar la tarima |

## Bloqueos deliberados

- No acepta el código base cuando el Packing List espera varias cajas.
- No acepta `U000`, números mayores al total ni una caja ya escaneada.
- No exporta desde la PDA si existe una tarima manual abierta o sin revisión física.
- No permite reabrir una tarima ya validada con temporal WMS.
- Windows no habilita cajas cuya tarima no esté validada.
- Una descarga parcial no genera plantilla sin confirmación y motivo suficiente.

## Límites de esta prueba

- Una sola PDA por contenedor.
- Intercambio mediante archivos, sin sincronización en tiempo real.
- Validación humana del contenido físico; no hay segundo escaneo completo al cerrar.
- La temporal se valida por formato, no contra el catálogo real de XLWMS.
- La aplicación crea el archivo; no lo sube ni ejecuta movimientos en WMS.
