# Estado sintético V0.9

`v09-state.ser` se generó con el motor V0.9 previo a las modificaciones de V0.10. No contiene datos de una descarga real.

Contenedor `PRUEBA-MIGRACION`: GRANDE 10 cajas (4 m³), CHICO 2 cajas (0.2 m³), 2 posiciones izquierdas. Capturas: GRANDEU009 (cierre parcial, validada y retirada), GRANDEU003 (directa abierta), CHICOU001 en TR-01 enviada y aún sin distribuir.

El test `ContinuousEngineSelfTest` verifica que la deserialización nueva preserve las tres cajas, la posición ocupada y la validación antigua, y que la siguiente captura utilice TR-02. Esta compatibilidad interna no copia las bases de datos entre los APK instalados en paralelo.
