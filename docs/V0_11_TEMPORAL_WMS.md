# V0.11 — Validar y cerrar con temporal WMS

## Operación

1. El operador sigue escaneando y cambiando TR sin confirmaciones rutinarias del supervisor ni bloqueo global.
2. Quien comprueba la tarima final abre **TARIMAS → OPERAR TARIMA → VALIDAR Y CERRAR · TEMPORAL WMS**.
3. Lee o captura la temporal, revisa `T-xx → TEMPORAL`, indica responsable y confirma que revisó contenido, ubicación y bodega. Enter del lector no confirma el cierre.
4. El cierre exige contenido completo, viajes involucrados cerrados y temporal válida. Una directa parcial requiere antes motivo y ajuste de objetivo; no desaparecen los pendientes del contenedor.
5. Se guardan juntos estado e historial. La temporal queda ligada a T-xx, con responsable y fecha de revisión. El contenido y la temporal quedan bloqueados.
6. **RETIRADA / POSICIÓN LIBRE** sigue siendo una acción posterior al retiro físico. Cerrar no libera espacio ni envía nada al WMS.
7. Supervisor exporta el resultado v4 hacia Windows V0.11. Windows concilia cajas y tarimas, usa las temporales recibidas y pide orden Putaway y revisión previa al XLSX.

Aplica a todas las definitivas, tanto tendido como al pie; no a TR-xx. Una ubicación WMS puede contener varias tarimas si la operación lo permite. La aplicación no impone una tarima por temporal.

## Contrato y límites

- Resultado `ilubox.pda.result.v4`, `verification_model=FINAL_PALLET_WMS_TEMPORARY`, `wms_location_validation=FORMAT_ONLY`.
- `wms_temporary_location` aparece en cada tarima y en cada evento aceptado. En pendientes queda vacío; en verificadas debe ser válido y coincidir.
- Formato: 1–80 caracteres, inicia con A–Z/0–9, continúa con A–Z/0–9 o `._/-`. La captura pasa a mayúsculas y elimina espacios externos. No se admiten controles, espacios internos, fórmulas ni identificadores locales T-/TR-/I01…I10/D01…D10.
- No existe catálogo ni conexión WMS: una cadena válida **no demuestra** existencia de ubicación, bodega correcta, espacio disponible ni aceptación del movimiento.
- Windows v4 no aplica valores predeterminados guardados ni reasigna ubicaciones. La plantilla conserva las cuatro columnas oficiales, una fila por individual y cantidad 1. El reporte operativo incluye la temporal por separado.
- La huella del Packing List y la conciliación detectan inconsistencias; no son una firma de autenticidad del archivo. Los modos Operador/Supervisor no autentican usuarios.
- Esta variante no incluye corrección/reapertura de una temporal cerrada, QR, API, sincronización ni instalador Windows autónomo. Ante un destino incorrecto, no cargar el archivo al WMS ni editar el JSON para forzarlo.

## Compatibilidad

El APK V0.11 tiene paquete independiente `com.ilubox.descargapda.v011`, para conservar V0.10 y sus datos. Iniciar una nueva prueba importando el manifiesto; no hay migración de la sesión instalada ni deben escanearse dos veces cajas reales como si fueran descargas distintas.

Windows mantiene resultados v3/v2 con aviso y asignación manual de ubicación; v1 solo auditoría. No se inventa retroactivamente una temporal para verificaciones anteriores. El motor conserva el historial serializado antiguo, pero no lo habilita para exportación v4 o retiro sin una temporal válida; esto no es una migración de sesión entre aplicaciones.

## Checklist del piloto Q9

- Una definitiva sin temporal no se cierra ni libera posición.
- Se rechazan `T-01`, `TR-01`, `I01`, controles, espacios internos y códigos fuera del formato.
- Un código WMS real se captura con el lector; su Enter no cierra el diálogo sin revisión.
- Una T con mercancía en la TR activa no se cierra. Cambiar TR permite continuar escaneando.
- Una T cerrada muestra su temporal en tarjeta/detalle y conserva el dato al reiniciar.
- Retirar una directa y reutilizar I01 no transfiere su temporal a la siguiente T.
- Dos T pueden usar la misma temporal cuando sea correcto operacionalmente.
- Windows importa v4, muestra las temporales sin pedirlas de nuevo y no utiliza una predeterminada antigua.
- El XLSX exclusivo tiene los encabezados oficiales, un individual por fila, cantidad 1 y temporal exacta de su T.
- Validar primero un lote pequeño en el análisis de importación del WMS; no duplicar movimientos ya cargados.

Las pruebas automatizadas no sustituyen este piloto con la PDA, etiquetas y ubicaciones reales.
