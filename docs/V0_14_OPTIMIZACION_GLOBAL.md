# Reglas operativas V0.14

## Orden de decisión

1. Respetar 1.94 m³ y 1,000 kg.
2. Minimizar la cantidad total de tarimas.
3. Minimizar divisiones del mismo código.
4. Reducir diversidad cuando no aumenta el total.
5. Acercarse a 600 kg cuando el volumen y los pesos conocidos lo permitan.

El cálculo es global y determinista: el orden de las filas del Packing List y el orden de escaneo no deciden la composición.

## Familias

- **Pie del contenedor:** solo códigos cuya cantidad excede la capacidad de una tarima por volumen o peso. La posición física Ixx/Dxx se reutiliza; la identidad T-xx no.
- **Tendido optimizado:** códigos de dos o más cajas que caben en una sola tarima de código y pueden combinarse globalmente.
- **Unitarios:** códigos de una caja agrupados únicamente con otros unitarios.
- **Par excepcional:** código completo de dos cajas, indivisible, admitido en una tarima unitaria solo si no aumenta la cantidad total.

No hay límite de códigos por tarima. La sugerencia de rack es informativa: multicódigo/surtido en bajo; homogénea/reserva en alto. Una carga pesada, inestable o incompatible debe quedar abajo aunque contradiga la sugerencia comercial.

## Excepción física: NO CABE

Cuando la tarima del tendido se llena antes de lo calculado:

1. El operador cambia la TR cuando esta sale físicamente hacia el tendido.
2. En la tarima afectada pulsa `TARIMA LLENA / NO CABE MÁS` e indica el motivo.
3. El motor congela las cajas escaneadas que pertenecen a traslados ya cerrados; esas son el contenido físico de la T parcial.
4. Reasigna únicamente cajas no escaneadas y cajas de la TR activa. Primero usa espacio compatible de otras T abiertas; después crea nuevas T.
5. Las cajas ya escaneadas cuyo número T cambió aparecen como `REMARCAR` y bloquean la validación hasta confirmar el cambio físico de marca.
6. La T parcial conserva cantidad prevista original, cantidad real, motivo, hora y trazabilidad del código dividido.
7. Se valida con responsable y temporal WMS; luego puede retirarse.

No se mueve informáticamente una caja que ya salió en una TR cerrada a otra tarima. No se modifican tarimas verificadas o retiradas.

## Ubicación temporal y WMS

La sugerencia de nivel de rack no es la posición temporal WMS. La temporal real se captura al validar cada definitiva. Windows solo exporta al XLSX oficial cajas cuya T esté validada y tenga temporal canónica. La aplicación no verifica que esa ubicación exista o esté libre dentro de XLWMS.
