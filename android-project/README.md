# Ilubox WMS PDA V0.11 — temporal obligatoria

Aplicación Android offline para la AUTOID Q9. Recibe de Windows un manifiesto de descarga, valida cada caja individual, asigna su tarima definitiva y devuelve un resultado que Windows puede convertir en la plantilla oficial del WMS.

## Instalación de prueba

El APK instala **Ilubox PDA V0.11** junto a V0.10 (`com.ilubox.descargapda.v011`). No desinstalar V0.10: sus datos permanecen en la aplicación anterior. V0.11 no copia su sesión abierta; importar el manifiesto para comenzar una nueva prueba. El APK está firmado para depuración/piloto, no como distribución de producción.

## Flujo principal

1. En Windows, carga el Packing List y descarga `PDA_<contenedor>.json`.
2. Copia ese archivo a la Q9 y pulsa **IMPORTAR DESCARGA**.
3. Inicia en modo **TRASLADO**, que queda preseleccionado.
4. Escanea la etiqueta individual de cada caja.
5. La pantalla muestra la tarima definitiva `T-xx`; para códigos pequeños también muestra `TR-xx`.
6. Cuando sustituya físicamente la TR, pulse **CAMBIAR TRASLADO** y continúe capturando. No hay confirmación por cada distribución.
7. Abra **TARIMAS**, consulte el desglose y los Uxx bajo demanda. Pulse **OPERAR TARIMA → VALIDAR Y CERRAR · TEMPORAL WMS** solo después de comprobar físicamente las cajas. Lea o capture la temporal WMS, revise el destino mostrado, registre nombre/iniciales y confirme la revisión.
8. Después de retirarla físicamente, pulse **RETIRADA / POSICIÓN LIBRE**. Verificar no libera automáticamente el espacio.
9. Si no hay espacio y ninguna directa está completa, use **CERRAR PARCIAL** con motivo; conserve las cajas pendientes y luego verifique y retire.
10. En Supervisor, use **EXPORTAR RESULTADO PARA WINDOWS** y copie `resultado_PDA_<contenedor>.json` a Windows V0.11. La temporal viaja en el resultado; no necesita capturarse de nuevo.

El lector con sufijo Enter termina la captura del campo, pero **no cierra la tarima automáticamente**. La temporal acepta letras A–Z, números, punto, guion, barra y guion bajo; máximo 80 caracteres, iniciando por letra/número. Se normalizan mayúsculas y espacios externos; se rechazan controles, espacios internos e identificadores locales como `T-01`, `TR-01`, `I01`, `D01`. No se consulta un catálogo WMS: el responsable debe comprobar existencia y bodega. Puede haber varias tarimas en una misma temporal; no se impone exclusividad.

Una vez cerrada, la temporal y el contenido quedan bloqueados. Esta variante no incluye reapertura ni corrección de una temporal cerrada. Revise el destino antes de confirmar; ante un error, detenga la exportación de esa sesión y conserve la evidencia, sin editar el JSON.

## Validación estricta

- Para un código con varias cajas se exige `CODIGOUxxx`, con rango consecutivo `U001…UN` comenzando en 1.
- El código base por sí solo se rechaza; nunca se inventa un número de caja.
- `U2` se normaliza a `U002` solo cuando está unido inequívocamente al código.
- Se bloquean duplicados, números fuera de rango, códigos desconocidos y lecturas ambiguas.
- Un código con una sola caja puede leerse como código base y se normaliza a `U001`.
- Los códigos grandes toman dinámicamente una tarima activa y una posición libre al pie; el orden de llegada de `Uxxx` no cambia esa asignación.
- Todos los códigos permanecen en `PENDIENTE_VERIFICAR` hasta comprobar su tarima final. Una TR puede alimentar varias T, y una T reunir varios viajes.
- `Registradas / previstas` es el avance de esa tarima, no el total de cajas del producto.
- El inicio muestra cuántas tarimas se deben colocar en el tendido, cuántas definitivas quedan al pie y cuántas TR se requieren.
- Una corrección devuelve la caja a pendiente sin borrar el evento de auditoría.

## Integridad del intercambio

El manifiesto conserva la huella SHA-256 del catálogo `código + cantidad`. El resultado v4 declara contenido, cierre de viajes, comprobación por tarima, responsable, fecha, temporal WMS y retiro. Windows concilia detalle y resúmenes, y bloquea cualquier caja no elegible. No se inventan cajas ni se omiten pendientes para desbloquear una exportación. Un estado antiguo sin temporal no se convierte en un cierre v4 válido.

## Controles visibles del supervisor

- cajas procesadas e incidencias;
- definitivas al pie del contenedor;
- definitivas en el tendido final;
- traslado actual y sus destinos;
- plan inicial de tendido, posiciones al pie y TR-01;
- consulta compartida del contenido y del avance;
- exportar resultado para Windows;
- cargar una nueva descarga con confirmación previa;
- historial CSV, reporte Excel y corrección del último escaneo.

## Operación offline y recuperación

El motor y el historial se guardan juntos en una transacción SQLite. Si falla el guardado, no se muestra la lectura como aceptada; se recupera el estado anterior o se bloquea la captura. La sesión V0.11 se reanuda al abrir la misma aplicación, incluidas las temporales. Importación/exportación usan el selector de archivos Android y no requieren Wi-Fi.

No hay autenticación por usuario ni sincronización entre dispositivos. La comprobación de contenido es humana: sin segundo escaneo no detecta automáticamente el intercambio de dos cajas del mismo código. Una tarima verificada no equivale a rackeo confirmado ni a aceptación en XLWMS.

## Lector Q9

Configura el lector como teclado/HID y agrega `Enter` como sufijo. El foco se recupera en **ESCANEAR**; en **TARIMAS** o en un diálogo la captura se pausa. Vuelva a ESCANEAR antes de leer la siguiente caja.

## Compilación

- `compileSdk 36`
- `minSdk 23`
- `targetSdk 36`
- Java 17

Abre `android-project` en Android Studio y ejecuta **Build APK(s)**. El APK debug se crea en `app/build/outputs/apk/debug/app-debug.apk`.
