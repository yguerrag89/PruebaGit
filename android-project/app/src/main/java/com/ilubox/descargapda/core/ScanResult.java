package com.ilubox.descargapda.core;

public class ScanResult {
    public boolean ok;
    public String status = "";
    public String message = "";
    /** Escaneo bruto/canónico recibido del lector. */
    public String rawScan = "";
    /** Barcode individual normalizado, por ejemplo THZ...U003. */
    public String normalizedBarcode = "";
    /** Compatibilidad con exportaciones previas: se usa como barcode normalizado si existe. */
    public String scan = "";
    public String code = "";
    public String position = "";
    public int boxNumber = 0;
    public int received = 0;
    public int expected = 0;
    public int remaining = 0;
    public boolean uniqueBoxId = false;
    public boolean waitingRemoval = false;
    public String firstScanTime = "";
    /** Tarima definitiva calculada, por ejemplo T-07. */
    public String finalPallet = "";
    /** Posición física al pie, por ejemplo I01. Para tendido se muestra TENDIDO. */
    public String physicalPosition = "";
    /** Tarima usada para el viaje agrupado, por ejemplo TR-02. Vacía cuando va directo. */
    public String transferPallet = "";
    /** Verdadero cuando el código grande se forma directamente al pie del contenedor. */
    public boolean directToFinal = false;

    public static ScanResult fail(String status, String message) {
        ScanResult r = new ScanResult();
        r.ok = false;
        r.status = status;
        r.message = message;
        return r;
    }
}
