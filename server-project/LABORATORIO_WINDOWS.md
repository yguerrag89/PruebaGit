# Laboratorio local V0.12 para Windows

Esta prueba permite recorrer el proceso **antes de instalar nada en Ubuntu**. Usa
el importador estricto, el protocolo de revisiones, la base SQLite y el generador
WMS reales de V0.12, pero sustituye temporalmente la Q9 por una pantalla de PDA
simulada en el mismo navegador.

## Límites de seguridad

- Escucha únicamente en `127.0.0.1:8876`: otro equipo de la red no puede entrar.
- Guarda sus datos separados en `%LOCALAPPDATA%\IluboxLabV012R2\datos`.
- No se conecta a XLWMS y no importa movimientos en el WMS.
- No usa ni modifica las instalaciones V0.11/V0.12, Streamlit, Nginx o Ubuntu.
- El simulador cubre el modo operativo **TRASLADO**. No reemplaza la prueba final
  de lector HID, suspensión, Wi-Fi y persistencia en la AUTOID Q9.

Use un Packing List sintético o una copia que no pertenezca a una descarga activa.
La plantilla WMS descargada es de prueba: no debe cargarse a XLWMS.

## Cómo iniciar

1. Descomprima `Ilubox_WMS_Ubuntu_v0.12.zip` en una carpeta nueva de Windows.
2. Entre en `server-project`.
3. Ejecute `INICIAR_LABORATORIO_WINDOWS.bat`.
4. En el primer inicio, Windows crea un entorno aislado e instala los componentes.
   Se requiere Python 3 y acceso a Internet únicamente para esa instalación inicial.
5. El navegador abre `http://127.0.0.1:8876`. Si no abre automáticamente, escriba
   esa dirección en el mismo equipo.
6. Para detener la prueba, cierre el navegador y presione `Ctrl+C` en la ventana negra.

El inicio siguiente reutiliza los componentes y los datos guardados. No es necesario
volver a instalar.

## Recorrido recomendado

1. Cargue el XLSX y defina posiciones izquierda/derecha.
2. Confirme el resumen: tarimas en tendido, directas al pie y una TR.
3. Escanee un código correcto, un `U000`, un número mayor al esperado y un duplicado.
4. Pulse **DESCONECTAR WI-FI**, escanee cajas y compare `Servidor/PDA`: las revisiones
   deben ser distintas mientras la red simulada está caída.
5. Pulse **RECONECTAR WI-FI**: ambas revisiones deben igualarse sin duplicar cajas.
6. Cierre la TR que contiene las cajas de una tarima de tendido.
7. Intente validar primero con una temporal inválida como `I01`; debe rechazarse.
8. Valide con responsable y una temporal de prueba, por ejemplo `2B-TMP-01`.
9. En una tarima directa, verifique, retire y confirme que la posición al pie puede
   reutilizarse sin heredar la temporal anterior.
10. Pulse **PROBAR REINTENTO DOBLE**; debe informar que no duplicó cajas.
11. Cuando todas las cajas escaneadas sean elegibles WMS, cierre la descarga. Si faltan
    cajas, escriba un motivo de al menos ocho caracteres.
12. Descargue la plantilla y compruebe:
    - cuatro encabezados oficiales;
    - una fila por caja individual;
    - cantidad numérica `1`;
    - la temporal validada para cada tarima.

## Reiniciar el laboratorio

En la parte inferior abra **Eliminar exclusivamente esta prueba local**, escriba
`BORRAR PRUEBA` y confirme. Esto elimina únicamente la base y la PDA simulada de
este laboratorio. Si se quiere conservar evidencia, copie antes el XLSX exportado
y anote las incidencias; el historial también se ve dentro de la pantalla.

## Criterio para pasar a Ubuntu y Q9

La prueba local es satisfactoria cuando el Packing List real de muestra se admite,
las lecturas erróneas se rechazan, el modo offline conserva cajas, el reintento no
duplica, ninguna caja sale al WMS sin tarima verificada y el XLSX coincide con lo
esperado. Después se instala la misma lógica central en Ubuntu y se repite el ensayo
con la Q9 real; solo esa segunda prueba valida el hardware y la red interna.
