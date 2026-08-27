package com.ilubox.descargapda.core;

import java.io.Serializable;

public class CodeRecord implements Serializable {
    private static final long serialVersionUID = 1L;

    public final String code;
    public final int boxes;
    public final double cbm;
    public final double cbmPerBox;
    public final Double weightPerBox;
    public final String description;
    public final String warehouse;

    public CodeRecord(String code, int boxes, double cbm, double cbmPerBox,
                      Double weightPerBox, String description, String warehouse) {
        this.code = code == null ? "" : code.trim().toUpperCase();
        this.boxes = boxes;
        this.cbm = cbm;
        this.cbmPerBox = cbmPerBox > 0 ? cbmPerBox : (boxes > 0 ? cbm / boxes : 0.0);
        this.weightPerBox = weightPerBox;
        this.description = description == null ? "" : description;
        this.warehouse = warehouse == null ? "" : warehouse;
    }
}
