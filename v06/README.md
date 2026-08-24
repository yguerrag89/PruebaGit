# V0.6 Traslado Dirigido Q9

Piloto de la variante operativa acordada para reducir recorridos durante la descarga.

- Calcula una tarima definitiva `T-xx` para cada caja antes de iniciar.
- Al escanear un código pequeño muestra: número a marcar y tarima de traslado `TR-xx`.
- Los códigos grandes se forman directamente al pie del contenedor.
- El operador confirma cuándo sale una tarima de traslado e inicia la siguiente.
- Conserva detección de duplicados, cajas fuera de rango y trazabilidad del destino original.
- Mantiene MANUAL y BUFFER como modos comparativos; TRASLADO es el modo predeterminado.

La composición es un plan preliminar basado en CBM. La validación física de peso, estabilidad y
capacidad continúa siendo obligatoria durante el piloto.
