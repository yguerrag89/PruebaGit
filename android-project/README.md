# Ilubox WMS PDA V0.9 — escaneo individual estricto

Aplicación Android offline para la AUTOID Q9. Recibe de Windows un manifiesto de descarga, valida cada caja individual, asigna su tarima definitiva y devuelve un resultado que Windows puede convertir en la plantilla oficial del WMS.

## Flujo principal

1. En Windows, carga el Packing List y descarga `PDA_<contenedor>.json`.
2. Copia ese archivo a la Q9 y pulsa **IMPORTAR DESCARGA**.
3. Inicia en modo **TRASLADO**, que queda preseleccionado.
4. Escanea la etiqueta individual de cada caja.
5. La pantalla muestra la tarima definitiva `T-xx`; para códigos pequeños también muestra `TR-xx`.
6. En Supervisor, valida las definitivas y usa **EXPORTAR RESULTADO PARA WINDOWS**.
7. Copia `resultado_PDA_<contenedor>.json` a Windows.

## Validación estricta

- Para un código con varias cajas se exige `CODIGOUxxx`, con rango consecutivo `U001…UN` comenzando en 1.
- El código base por sí solo se rechaza; nunca se inventa un número de caja.
- `U2` se normaliza a `U002` solo cuando está unido inequívocamente al código.
- Se bloquean duplicados, números fuera de rango, códigos desconocidos y lecturas ambiguas.
- Un código con una sola caja puede leerse como código base y se normaliza a `U001`.
- Los códigos grandes toman dinámicamente una tarima activa y una posición libre al pie; el orden de llegada de `Uxxx` no cambia esa asignación.
- Los códigos pequeños conservan su destino `T-xx`, viajan en `TR-xx` y solo pasan a definitiva al confirmar la distribución.
- El inicio muestra cuántas tarimas se deben colocar en el tendido, cuántas definitivas quedan al pie y cuántas TR se requieren.
- Una corrección devuelve la caja a pendiente sin borrar el evento de auditoría.

## Integridad del intercambio

El manifiesto contiene una huella SHA-256 del catálogo `código + cantidad`. La PDA verifica esa huella al importar y la incluye otra vez en el resultado V0.9. El JSON declara el estado físico, la distribución del traslado y la validación de cada tarima; Windows rechaza una caja no elegible para WMS.

## Controles visibles del supervisor

- cajas procesadas e incidencias;
- definitivas al pie del contenedor;
- definitivas en el tendido final;
- traslado actual y sus destinos;
- plan inicial de tendido, posiciones al pie y TR-01;
- cierre y validación de tarimas directas;
- enviar al tendido / confirmar distribución;
- exportar resultado para Windows;
- cargar una nueva descarga con confirmación previa;
- historial CSV, reporte Excel y corrección del último escaneo.

## Operación offline y recuperación

El motor y el historial se guardan en SQLite después de cada acción. La descarga se reanuda al volver a abrir la aplicación. La importación y la exportación usan el selector de archivos de Android; no requieren Wi-Fi.

## Lector Q9

Configura el lector como teclado/HID y agrega `Enter` como sufijo. La aplicación mantiene el foco en el campo de escaneo.

## Compilación

- `compileSdk 36`
- `minSdk 23`
- `targetSdk 36`
- Java 17

Abre `android-project` en Android Studio y ejecuta **Build APK(s)**. El APK debug se crea en `app/build/outputs/apk/debug/app-debug.apk`.
