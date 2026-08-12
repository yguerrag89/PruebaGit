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

    public static ScanResult fail(String status, String message) {
        ScanResult r = new ScanResult();
        r.ok = false;
        r.status = status;
        r.message = message;
        return r;
    }
}
