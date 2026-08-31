# Ilubox WMS PDA V0.13 — Manual Asistida Local

Aplicación Android offline para AUTOID Q9. Recibe de Windows un manifiesto de descarga, valida cada caja individual, mantiene la identidad `T-xxx` separada de la posición `Ixx/Dxx` y devuelve un resultado que Windows puede convertir en la plantilla oficial del WMS.

## Instalación de prueba

El APK se instala como **Ilubox PDA V0.13 Manual** con paquete `com.ilubox.descargapda.v013`. No reemplaza versiones anteriores ni copia una sesión abierta. Importa un manifiesto nuevo para comenzar la prueba.

## Operación MANUAL ASISTIDA

1. Importa `PDA_<contenedor>.json` desde Windows.
2. Inicia en modo **MANUAL** y define las posiciones físicas disponibles.
3. Selecciona una posición una sola vez. Permanece activa hasta cambiarla o cerrar su tarima.
4. Escanea cada etiqueta individual. La pantalla muestra la `T-xxx`, la posición y el avance.
5. Si un código ya existe en otra tarima abierta, la aplicación advierte antes de dividirlo.
6. Cuando no quepan más cajas, pulsa **TARIMA LLENA / NO CABE MÁS**.
7. En la tarima cerrada pulsa **VALIDAR TARIMA**. Revisa físicamente el desglose, captura la temporal WMS, registra responsable y confirma.
8. Retira físicamente la tarima y pulsa **RETIRAR Y LIBERAR Ixx/Dxx**.
9. En Supervisor exporta **RESULTADO JSON PARA WINDOWS**. La PDA no genera un XLSX WMS.

Una `T-xxx` validada no puede reabrirse. Cerrar no libera el espacio: la liberación ocurre solamente después del retiro físico.

## Regla individual

- Multicaja: `CODIGOU001…CODIGOUN`, con números desde 1 y padding de tres cifras.
- Código base multicaja: rechazado.
- Unitario inequívoco: se convierte a `U001`.
- Duplicado, `U000`, caja mayor a N y lectura ambigua: rechazados sin incrementar avance.

## Integridad y límites

El resultado v4 conserva contenedor, huella del Packing List, caja, `T-xxx`, posición física, temporal, responsable y estado de validación. No se exporta si existe una tarima manual con cajas abierta o sin revisión.

La comprobación de contenido es humana; sin un segundo escaneo total no detecta automáticamente el intercambio físico de dos cajas. La aplicación valida el formato de la temporal, no consulta el catálogo XLWMS y no confirma rackeo.

## Compilación

- `compileSdk 36`
- `minSdk 23`
- `targetSdk 36`
- Java 17

Abre `android-project` en Android Studio y ejecuta **Build APK(s)**. El APK debug se crea en `app/build/outputs/apk/debug/app-debug.apk`.
