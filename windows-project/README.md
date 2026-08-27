# Ilubox WMS Windows V0.9

Aplicación local para preparar la descarga, generar el archivo de trabajo de la PDA, validar el resultado móvil y crear una copia de la plantilla oficial `PutawayCrossDockImport`.

La aplicación no se conecta al WMS ni confirma movimientos. El supervisor revisa y carga manualmente el XLSX final. El reporte operativo y el archivo exclusivo WMS son descargas independientes.

## Instalación

1. Descomprime toda la carpeta en Windows.
2. Ejecuta `INSTALAR.bat` una sola vez.
3. Ejecuta `INICIAR_APP.bat` para abrir la aplicación.

Requiere Python 3.10 o posterior. `INSTALAR.bat` instala Streamlit y openpyxl.

## Flujo recomendado Windows–PDA–WMS

1. En **Supervisor**, carga el Packing List XLSX/ZIP/RAR.
2. Selecciona el contenedor y descarga **archivo para PDA (.json)**.
3. Importa ese JSON en la Q9 y realiza el escaneo.
4. En la Q9, abre Supervisor y pulsa **EXPORTAR RESULTADO PARA WINDOWS**.
5. En Windows, entra en **Exportación WMS** e importa el resultado de la PDA V0.9.
6. Windows verifica contenedor, huella del Packing List, códigos individuales, rangos y duplicados.
7. Verifica que el estado físico indique definitiva y que cada `T-xx` esté validada.
8. Captura la orden Putaway y la ubicación WMS de cada tarima `T-xx`.
9. Confirma la carga parcial si corresponde y revisa la vista previa.
10. Descarga el XLSX exclusivo de cuatro columnas y usa primero **Comenzar a analizar** en el WMS.

También puede escanearse localmente en Windows; aplica la misma regla estricta.

## Regla de identidad de caja

- Grupo con varias cajas: exige `CODIGOUxxx`, con números válidos de `U001` a `UN`.
- Código base multi-caja: rechazado, sin incrementar cantidades.
- Unitario: el código base se convierte de forma segura en `U001`.
- Se bloquean duplicados, lecturas ambiguas y sufijos fuera de rango.

## Protección del intercambio

El manifiesto contiene una huella SHA-256 de cada código y su cantidad. El resultado de la PDA debe conservar la misma huella y el mismo contenedor. Esto evita mezclar accidentalmente descargas o usar un resultado creado con otra versión del Packing List.

## Plantilla oficial WMS

La aplicación rellena una copia de `assets/templates/Plantilla_oficial_WMS_PutawayCrossDockImport.xlsx` y conserva los encabezados oficiales. Genera una fila por caja, con cantidad entera `1`, y valida:

- orden Putaway obligatoria;
- ubicación WMS obligatoria por tarima o predeterminada;
- caja individual real;
- ausencia de duplicados;
- bloqueo de texto interpretable como fórmula;
- confirmación expresa si la descarga está incompleta;
- caja en `EN_DEFINITIVA`, traslado distribuido y tarima final validada;
- revisión final del supervisor.

`I01/D01` son posiciones físicas locales. `T-xx` identifica una tarima definitiva y `TR-xx` un traslado. La ubicación real del WMS se captura por separado. El reporte Excel contiene auditoría y desglose; no debe cargarse al WMS.

## Recuperación

El avance local se guarda en `data/sessions`. **Activar o reanudar descarga** recupera la sesión; **Reiniciar seguimiento** exige una confirmación y elimina el progreso de ese contenedor.

## Verificación

Ejecuta `VERIFICAR_APP.bat`. Deben aprobarse el motor, el generador WMS, el intercambio estricto PDA y la interfaz Streamlit.
