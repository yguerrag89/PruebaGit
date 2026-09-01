# Ilubox WMS V0.14 — Optimización Global y Replanificación

Variante local para Windows y PDA AUTOID Q9. Windows convierte el Packing List en un plan global sellado; la PDA valida cada caja individual y ejecuta ese mismo plan sin depender del orden de descarga.

## Cambios principales

- Minimiza primero la cantidad de tarimas; después, las divisiones de código y la diversidad por tarima.
- Restricciones duras: 1.94 m³ objetivo y 1,000 kg por tarima. La meta de 600 kg se aplica solo cuando el volumen y los datos disponibles lo permiten.
- No existe un límite artificial de códigos por tarima.
- Los códigos de una caja se agrupan únicamente entre sí. Un código completo de dos cajas puede incorporarse como par indivisible solo si no aumenta el total.
- Solo se forman al pie del contenedor códigos que requieren dos o más tarimas por volumen o peso.
- El manifiesto `PDA_<contenedor>.json` incluye la asignación exacta de cada `CODIGOUxxx`; Windows y PDA no recalculan planes independientes.
- Las tarimas multicódigo sugieren rack bajo; las homogéneas de reserva, alto. Peso y seguridad siempre tienen prioridad sobre esa sugerencia.
- `TARIMA LLENA / NO CABE MÁS` congela lo ya enviado físicamente, cierra la tarima real y replanifica únicamente las cajas pendientes.
- Si una caja ya escaneada permanece en la TR activa y cambia de destino, la aplicación exige remarcarla antes de validar.
- La validación final continúa exigiendo responsable y posición temporal WMS. El XLSX para XLWMS se genera en Windows solamente con cajas físicamente validadas.

## Flujo local

1. Windows carga el Packing List y muestra cuántas tarimas colocar en el tendido y cuántas dejar al pie.
2. Windows genera el manifiesto JSON con secuencia estricta `U001…UN` y el plan global.
3. La PDA importa el JSON y opera en modo TRASLADO.
4. Cada caja se escanea una vez; la PDA muestra `T-xx` y `TR-xx` o la posición directa al pie.
5. Al terminar físicamente una definitiva se revisa, se captura su temporal WMS y se valida.
6. La PDA exporta `resultado_PDA_<contenedor>.json`.
7. Windows concilia el resultado y habilita la copia exclusiva de la plantilla oficial XLWMS.

Consulta [las reglas operativas V0.14](docs/V0_14_OPTIMIZACION_GLOBAL.md).

## Compatibilidad

El APK usa el paquete independiente `com.ilubox.descargapda.v014`; puede instalarse junto con versiones anteriores. El resultado conserva `ilubox.pda.result.v4`. No se conecta ni ejecuta movimientos dentro de XLWMS.
