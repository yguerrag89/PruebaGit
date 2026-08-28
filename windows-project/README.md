# Ilubox WMS Windows V0.11

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
5. En Windows V0.11, entra en **Exportación WMS** e importa el resultado de la PDA V0.11.
6. Windows verifica contenedor, huella del Packing List, códigos individuales, rangos, duplicados y conciliación de tarimas/viajes/totales.
7. Verifica que el contenido de cada `T-xx` esté comprobado físicamente. El cambio de traslado no sustituye esa revisión.
8. Captura la orden Putaway y revisa la temporal WMS ya registrada en la PDA para cada `T-xx`. En resultados v4 no se puede sustituir por una ubicación genérica ni recapturarla en Windows.
9. Confirma la carga parcial si corresponde y revisa la vista previa.
10. Descarga el XLSX exclusivo de cuatro columnas y usa primero **Comenzar a analizar** en el WMS.

La vista Operador permite consultar las tarimas del JSON importado sin exportaciones. Es una copia de la última exportación, no un tablero sincronizado en tiempo real con la Q9. El escaneo local de Windows se conserva solo como prueba independiente y no sustituye el resultado verificado de la PDA.

V0.11 acepta resultados v4 y conserva los v3/v2 anteriores con un aviso explícito: estos últimos requieren ubicación manual en Windows, pues no fue capturada en la PDA. Los resultados v1 se admiten solo para auditoría, sin habilitar el WMS. Windows V0.10 no puede leer el nuevo resultado v4: usar las dos aplicaciones V0.11 juntas. No cambiar el número de versión de un JSON para forzar su importación.

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
- temporal WMS obligatoria capturada en la PDA, coincidente entre caja y tarima (v4); ubicación manual por tarima o predeterminada solo para resultados legados;
- caja individual real;
- ausencia de duplicados;
- bloqueo de texto interpretable como fórmula;
- confirmación expresa si la descarga está incompleta;
- caja en `EN_DEFINITIVA` y tarima final verificada; en v3/v4, toda su mercancía debe haber salido de la TR activa;
- revisión final del supervisor.

`I01/D01` son posiciones físicas locales. `T-xx` identifica una tarima definitiva y `TR-xx` un traslado. La temporal WMS es un dato diferente: se captura al validar y cerrar en la PDA. La validación comprueba formato, no existencia ni bodega en XLWMS. El reporte Excel contiene auditoría y desglose; no debe cargarse al WMS.

La temporal de una tarima cerrada es de solo lectura. No existe en esta variante un flujo de corrección/reapertura. Revísela antes de cerrar y no edite el JSON para sustituirla.

## Recuperación

El avance local se guarda en `data/sessions`. **Activar o reanudar descarga** recupera la sesión; **Reiniciar seguimiento** exige una confirmación y elimina el progreso de ese contenedor.

## Verificación

Ejecuta `VERIFICAR_APP.bat`. Deben aprobarse el motor, el generador WMS, el intercambio estricto PDA y la interfaz Streamlit.

Las pruebas de flujo continuo incluyen resultados sintéticos generados por el motor Android, verificación por tarima y rechazo de resúmenes inconsistentes. Los archivos de `tests/fixtures` son exclusivamente datos de prueba: no cargarlos al WMS.
