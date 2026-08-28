# Ilubox WMS V0.10 — Operador continuo · Windows + PDA Q9

Esta rama contiene dos aplicaciones coordinadas:

- `windows-project`: carga el Packing List, genera el manifiesto para PDA, valida el resultado móvil y crea la plantilla oficial WMS.
- `android-project`: escaneo offline en AUTOID Q9, asignación de tarimas definitivas/traslado y exportación estricta hacia Windows.

El contrato compartido exige códigos individuales consecutivos `U001…UN` para cualquier grupo de varias cajas y utiliza una huella del Packing List para evitar mezclar descargas.

Los códigos grandes se asignan dinámicamente a una tarima `T-xx` al pie del contenedor; los pequeños se planifican en el tendido y viajan en `TR-xx`. El orden de llegada de los U no altera la identidad de la caja.

## Cambios V0.10

- Operador con vistas **ESCANEAR / TARIMAS**: avance por tarima, desglose por código e individuales bajo demanda; sin exportaciones.
- **CAMBIAR TRASLADO** abre el siguiente viaje inmediatamente, sin confirmar cada distribución ni bloquear toda la descarga.
- Verificación física por tarima, con responsable y fecha; independiente del retiro y de la ubicación WMS.
- Liberación explícita de la posición solo después del retiro físico.
- Cierre parcial con motivo para resolver falta de espacio al pie; conserva la previsión original y los pendientes del contenedor.
- Guardado transaccional de lectura/acción y estado; recuperación del estado previo si falla.
- Resultado PDA v3 y conciliación estricta en Windows de cajas, tarimas, viajes y totales. Se conserva lectura v2/v1 con sus restricciones.

El archivo WMS sigue siendo una copia de la plantilla oficial de cuatro columnas, una fila por caja y cantidad 1. Nunca se habilita una caja solamente por cambiar de TR. No hay API de XLWMS, rackeo automático ni QR en esta versión.

El APK de prueba se instala como **Ilubox PDA V0.10**, independiente de V0.9: no desinstala la aplicación anterior ni copia su sesión. Importar el manifiesto de Windows para comenzar una nueva prueba. No es una publicación de producción con firma estable.

Ver [flujo y checklist](docs/V0_10_OPERACION.md). Las vistas son modos de uso, no autenticación de usuarios; no hay sincronización entre equipos.

La compilación automática publica los entregables en `release/`.
