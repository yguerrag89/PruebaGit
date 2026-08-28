# Ilubox WMS V0.12 — Servidor LAN Ubuntu + PDA Q9

Variante piloto independiente: panel del supervisor desde el navegador de Windows,
una PDA por descarga, captura offline, sincronización por revisiones y plantilla WMS
solo después de recibir el cierre. Conserva la validación individual y temporal
obligatoria de V0.11. No instala nada automáticamente en el servidor de la empresa.

Leer [instalación aislada, operación, límites y pruebas de aceptación](server-project/README.md).
Paquetes generados por CI en `release/v0.12/`. La carpeta `windows-project` conserva
la aplicación local anterior y aporta el mismo generador/validador al servidor.
No se migran sesiones locales ni se comparte una descarga entre V0.11 y V0.12.

## Referencia: versión local V0.11

Esta rama contiene dos aplicaciones coordinadas:

- `windows-project`: carga el Packing List, genera el manifiesto para PDA, valida el resultado móvil y crea la plantilla oficial WMS.
- `android-project`: escaneo offline en AUTOID Q9, asignación de tarimas definitivas/traslado y exportación estricta hacia Windows.

El contrato compartido exige códigos individuales consecutivos `U001…UN` para cualquier grupo de varias cajas y utiliza una huella del Packing List para evitar mezclar descargas.

Los códigos grandes se asignan dinámicamente a una tarima `T-xx` al pie del contenedor; los pequeños se planifican en el tendido y viajan en `TR-xx`. El orden de llegada de los U no altera la identidad de la caja.

## Cambios V0.11

- **VALIDAR Y CERRAR** exige revisar físicamente la tarima, capturar/leer su temporal WMS y registrar responsable. Aplica a tendido y directas.
- La temporal pertenece a `T-xx`, no a `I01/D01`: reutilizar una posición no copia la ubicación de otra tarima.
- Resultado PDA v4 con temporal por tarima y por caja. Windows concilia ambos y genera el WMS sin recapturar ubicaciones ni aplicar una predeterminada.
- La validación es de formato, no de existencia ni de bodega en XLWMS. Revisar el destino antes de cerrar.
- Cerrar no libera la posición ni envía movimientos al WMS. El retiro físico sigue siendo posterior y explícito.

## Se conserva de V0.10

- Operador con vistas **ESCANEAR / TARIMAS**: avance por tarima, desglose por código e individuales bajo demanda; sin exportaciones.
- **CAMBIAR TRASLADO** abre el siguiente viaje inmediatamente, sin confirmar cada distribución ni bloquear toda la descarga.
- Verificación física por tarima, con responsable y fecha; independiente del retiro.
- Liberación explícita de la posición solo después del retiro físico.
- Cierre parcial con motivo para resolver falta de espacio al pie; conserva la previsión original y los pendientes del contenedor.
- Guardado transaccional de lectura/acción y estado; recuperación del estado previo si falla.
- Conciliación estricta en Windows de cajas, tarimas, viajes y totales. Se conserva lectura v3/v2 con ubicación manual y v1 solo para auditoría.

El archivo WMS sigue siendo una copia de la plantilla oficial de cuatro columnas, una fila por caja y cantidad 1. Nunca se habilita una caja solamente por cambiar de TR. No hay API de XLWMS, rackeo automático ni QR en esta versión.

El APK de prueba se instala como **Ilubox PDA V0.11**, independiente de V0.10: no desinstala la aplicación anterior ni copia su sesión. Importar el manifiesto de Windows para comenzar una nueva prueba. No es una publicación de producción con firma estable.

Ver [flujo y checklist V0.11](docs/V0_11_TEMPORAL_WMS.md). Las vistas son modos de uso, no autenticación de usuarios; no hay sincronización entre equipos. Una temporal cerrada no se modifica en esta variante.

La compilación automática publica los entregables en `release/v0.11/`, conservando los anteriores.
