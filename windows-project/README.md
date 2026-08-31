# Ilubox WMS Windows V0.13 — Manual Asistida Local

Aplicación local para preparar la descarga, generar el JSON de trabajo de la PDA, validar el resultado móvil y llenar una copia de la plantilla oficial `PutawayCrossDockImport`.

## Instalación

1. Descomprime toda la carpeta en Windows.
2. Ejecuta `INSTALAR.bat` una sola vez.
3. Ejecuta `INICIAR_APP.bat` para abrir la aplicación.

Requiere Python 3.10 o posterior. `INSTALAR.bat` instala Streamlit y openpyxl.

## Flujo Windows–PDA–WMS

1. En **Supervisor**, carga el Packing List XLSX/ZIP/RAR.
2. Selecciona el contenedor y descarga **archivo para PDA (.json)**.
3. Importa el JSON en la Q9 y realiza la operación MANUAL ASISTIDA.
4. En la Q9, valida todas las `T-xxx` con revisión física, responsable y temporal WMS.
5. Exporta `resultado_PDA_<contenedor>.json` y cópialo a Windows.
6. En **Exportación WMS**, importa ese resultado. Windows verifica contenedor, huella del Packing List, `Uxxx`, duplicados, tarimas, temporales y totales.
7. Captura la orden Putaway. Si faltan cajas, confirma la carga parcial y registra el motivo.
8. Revisa la vista previa y descarga el XLSX exclusivo de cuatro columnas.
9. En XLWMS usa primero **Comenzar a analizar** antes de ejecutar la carga.

La aplicación no se conecta al WMS ni confirma movimientos. El reporte operativo y el archivo exclusivo WMS son descargas diferentes.

## Validación estricta

- Grupos multicaja: exige `CODIGOU001…CODIGOUN`, comenzando por 1.
- Unitarios: el código base inequívoco se normaliza a `U001`.
- Bloquea duplicados, lecturas ambiguas y números fuera de rango.
- El resultado v4 debe acreditar que cada caja está en una tarima validada y que la temporal de la caja coincide con la temporal de la tarima.
- Los resultados v3/v2 se conservan bajo sus reglas anteriores; v1 sirve solo para auditoría.

## Plantilla oficial WMS

Se genera una fila por caja, cantidad `1`, conservando los cuatro encabezados oficiales. Se bloquea la descarga si falta la orden Putaway, la temporal, una identidad individual, la validación final o la conciliación del Packing List.

`I01/D01` son posiciones físicas reutilizables. `T-xxx` identifica la tarima definitiva. La temporal WMS es un tercer dato y procede de la validación en la PDA. Su formato se comprueba, pero su existencia debe confirmarse en XLWMS.

## Verificación técnica

Ejecuta `VERIFICAR_APP.bat`. Las pruebas cubren motor, generador WMS, intercambio estricto, interfaz y compatibilidad del resultado Java MANUAL ASISTIDA.
