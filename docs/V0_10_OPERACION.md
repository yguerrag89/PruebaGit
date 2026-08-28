# V0.10 — Operador continuo

## Alcance

- PDA: escaneo sin bloqueo global mientras se acomodan los viajes anteriores.
- Operador: consulta de tarimas y avance, separada de importaciones/exportaciones.
- Un cambio por viaje: **CAMBIAR TRASLADO** sella su contenido y abre la siguiente TR.
- Comprobación final por tarima a cargo de quien la observa físicamente; registra nombre/iniciales y fecha.
- **VERIFICAR TARIMA** no libera el espacio. **TARIMA RETIRADA / POSICIÓN LIBRE** es una acción posterior.
- Cierre parcial de una directa con motivo para resolver falta de espacio; no reduce el total pendiente del contenedor.
- Windows: acepta resultados v3 y conserva la lectura de resultados v2/v1 bajo sus reglas originales.

## Significado de los datos

`Registradas` significa escaneadas y asignadas a T-xx, no presencia física comprobada. `Previstas` es el objetivo de esa tarima, no el total del código. Los números U pueden llegar en cualquier orden, dentro de U001…UN.

Antes de la comprobación, todas las cajas nuevas permanecen en `PENDIENTE_VERIFICAR`, incluidas las directas. Al verificar el contenido se convierten en `EN_DEFINITIVA`. Esto no significa ubicadas en un rack ni aceptadas por XLWMS.

Un viaje cerrado conserva sus destinos. Su estado `VERIFICADO_POR_TARIMAS` se calcula únicamente cuando todas sus cajas pertenecen a definitivas verificadas; no se inventa una hora de distribución. Una T puede reunir cajas de varios viajes y una TR puede alimentar varias T.

## Reglas que no se relajan

- No contar códigos sin identificador individual, fuera de rango o duplicados.
- No verificar una T incompleta ni una T con cajas todavía registradas en la TR activa.
- No reutilizar una posición hasta que se confirme su liberación física.
- No cambiar el contenido de una tarima verificada mediante anulación del último escaneo.
- No omitir silenciosamente cajas no verificadas al generar el WMS.
- Mantener orden Putaway y ubicación WMS separados de T-xx, TR-xx e Ixx/Dxx.

## Compatibilidad y límites

El APK se instala como **Ilubox PDA V0.10**, con un identificador independiente de V0.9. No borra ni copia automáticamente la sesión de la aplicación anterior. Se inicia una nueva prueba importando el manifiesto de Windows. No desinstalar V0.9 para instalar este piloto; conservar sus exportaciones.

El motor también mantiene compatibilidad probada con el estado serializado V0.9 para una futura actualización con firma estable: una TR ya enviada da paso a una nueva TR al recuperar la sesión y las verificaciones previas se identifican como `LEGADO_V09`, sin inventar responsable ni fecha. Esto es compatibilidad interna, no un importador de sesiones entre las dos aplicaciones instaladas.

Las dos vistas son modos operativos, no autenticación de usuarios. El piloto sigue siendo local/offline: no hay sincronización entre equipos, API de XLWMS ni generación de etiquetas QR en esta versión. Los cálculos de capacidad son estimaciones; no demuestran acomodo físico ni peso seguro cuando el Packing List no proporciona pesos.

La verificación física es una declaración humana. Sin una segunda lectura no detecta automáticamente un intercambio de cajas individuales del mismo código. Revisar físicamente cualquier diferencia antes de confirmar.

## Prueba de aceptación

1. Escanear cajas para TR-01, cambiar a TR-02 y continuar sin confirmar distribución.
2. Comprobar que TR-01 conserva sus cajas, aunque se capturen otras en TR-02.
3. Consultar T-xx desde Operador: avance por tarima, desglose por código e individuales bajo demanda.
4. Verificar una T que agrupe varios viajes cerrados; comprobar que no habilita otras T del mismo viaje.
5. Verificar una directa; comprobar que su posición continúa ocupada hasta marcar retiro.
6. Cerrar una directa parcial por falta de espacio; verificar, retirar y reescanear la caja antes rechazada.
7. Reiniciar la aplicación y comprobar cantidades, viajes, verificaciones y posiciones.
8. Rechazar duplicado, U000, U fuera de rango y código desconocido sin incremento de cajas.
9. Exportar a Windows: bloquear una T sin verificar y aceptar las verificadas con orden/ubicación válidas.
10. Probar el teclado/Enter y el regreso desde Tarimas en la Q9 real antes de una descarga completa.
