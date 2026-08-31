# Ilubox WMS V0.13 — Manual Asistida Local

Variante de prueba sin servidor: Windows prepara la descarga, una PDA Q9 opera un contenedor sin conexión y Windows valida el resultado antes de crear el XLSX exclusivo para XLWMS.

## Componentes

- `windows-project`: carga el Packing List, genera el manifiesto JSON para la PDA, concilia el resultado y llena una copia de la plantilla oficial WMS.
- `android-project`: aplicación offline de la Q9 con modo MANUAL ASISTIDA, código individual estricto y validación física por tarima.
- `server-project`: laboratorio V0.12 conservado como referencia; no forma parte del flujo V0.13.

## Contrato operativo V0.13

1. Windows genera `PDA_<contenedor>.json` desde el Packing List.
2. La Q9 importa ese archivo y trabaja en modo **MANUAL ASISTIDA**.
3. El operador selecciona una posición física `Ixx/Dxx` una vez; permanece activa para los siguientes escaneos.
4. La primera caja asigna una identidad estable `T-xxx` a la tarima física. La posición puede reutilizarse después, la T no.
5. Cada grupo multicaja exige el código individual `CODIGOU001…CODIGOUN`; se rechazan códigos base ambiguos, duplicados y números fuera de rango.
6. Al llenarse la tarima, el operador la cierra. Antes de retirarla debe revisar el contenido, capturar una temporal WMS válida y registrar responsable.
7. La tarima validada no puede reabrirse. Se retira físicamente y después se libera la posición.
8. La PDA exporta solamente `resultado_PDA_<contenedor>.json`; no genera una plantilla WMS.
9. Windows comprueba contenedor, huella del Packing List, cajas, tarimas, temporales y totales. Solo entonces habilita el XLSX oficial.

El cierre parcial de una descarga exige confirmación y un motivo de al menos ocho caracteres. La aplicación valida el formato de la temporal, pero no su existencia ni su bodega dentro de XLWMS.

Consulta [el flujo y checklist V0.13](docs/V0_13_MANUAL_ASISTIDA_LOCAL.md).

## Compatibilidad y seguridad

El APK usa el paquete independiente `com.ilubox.descargapda.v013`; no reemplaza versiones anteriores ni migra sus sesiones. El resultado mantiene el contrato `ilubox.pda.result.v4`, por lo que Windows conserva la conciliación estricta ya probada.

Esta versión no se conecta al servidor, no modifica XLWMS y no confirma rackeo. El supervisor descarga el archivo generado y lo carga manualmente en WMS después de revisarlo.
