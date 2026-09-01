# Reglas operativas V0.15

## Objetivo

Reducir decisiones y confirmaciones en una sola AUTOID Q9 por contenedor sin debilitar la identidad individual `CODIGOUxxx`, la evidencia física ni la plantilla exclusiva para XLWMS.

## Flujo normal

1. Windows carga el Packing List, calcula el plan global y genera `PDA_<contenedor>.json`.
2. La Q9 importa el manifiesto y muestra la cantidad inicial sugerida de tarimas de tendido, al pie y de traslado.
3. Cada lectura válida asigna una `T-xx`. Los códigos multitarima utilizan el pie cuando hay una posición habilitada libre; el resto utiliza la `TR-xx`.
4. `CAMBIAR TR` registra únicamente la sustitución física del traslado. No requiere confirmar distribución caja por caja.
5. Cuando una definitiva no admite más cajas, `NO CABE` conserva lo ya escaneado y recalcula solo lo pendiente.
6. La definitiva se cierra al revisar su contenido y escanear una temporal WMS válida.

## Falta de espacio al pie

La falta de una posición no rechaza la lectura. El código directo se mantiene homogéneo, recibe una nueva `T-xx` en el tendido y sus cajas viajan en la `TR-xx` activa. Si se habilita una posición adicional antes de la siguiente caja, la aplicación puede volver a formar una definitiva al pie.

## Interfaz Q9

- Una sola pantalla principal de escaneo.
- Acceso visible a `TARIMAS` y a una acción contextual.
- Botones de operación fuera del área desplazable.
- En `CONTROL`, botones fijos para añadir posiciones izquierda/derecha y volver a escanear.
- Exportación, correcciones y nueva descarga quedan en `MÁS` porque no forman parte de cada lectura.

## Trazabilidad y exportación

- El plan completo es una previsión y permanece separado.
- `resultado_PDA_<contenedor>.json` enumera cada lectura aceptada y solo tarimas con evidencia real.
- El reporte Excel de la PDA excluye tarimas planificadas con cero escaneos.
- Windows genera el XLSX WMS únicamente con cajas individuales en una definitiva validada y con temporal WMS.
- La PDA valida el formato de la temporal, no su existencia en XLWMS.

## Decisión de seguridad

Se eliminan selecciones redundantes, pero se conservan las fronteras que prueban un hecho físico: cambio de `TR-xx`, cierre por `NO CABE`, captura de temporal y liberación de una posición. Un fallo de guardado bloquea la operación para impedir que la pantalla y la mercancía diverjan.
