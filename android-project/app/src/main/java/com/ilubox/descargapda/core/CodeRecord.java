package com.ilubox.descargapda.core;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Registro lógico del Packing List.
 *
 * V0.16 separa el código/familia del identificador físico de cada caja:
 * - U_SEQUENCE: CODIGOU001...UN
 * - HYPHEN_SEQUENCE: CODIGO-1...-N (p.ej. MOYU)
 * - HYPHEN_UNIQUE: CODIGO-<id externo>, no necesariamente consecutivo (ZGA/ZGC/ZGD/FUE)
 * - EXPLICIT: lista exacta de barcodes suministrada por Windows.
 */
public class CodeRecord implements Serializable {
    private static final long serialVersionUID = 2L;

    public static final String U_SEQUENCE = "U_SEQUENCE";
    public static final String HYPHEN_SEQUENCE = "HYPHEN_SEQUENCE";
    public static final String HYPHEN_UNIQUE = "HYPHEN_UNIQUE";
    public static final String EXPLICIT = "EXPLICIT";

    public final String code;
    public final int boxes;
    public final double cbm;
    public final double cbmPerBox;
    public final Double weightPerBox;
    public final String description;
    public final String warehouse;
    public final String identityMode;
    public final ArrayList<String> expectedBoxIds;

    public CodeRecord(String code, int boxes, double cbm, double cbmPerBox,
                      Double weightPerBox, String description, String warehouse) {
        this(code, boxes, cbm, cbmPerBox, weightPerBox, description, warehouse, "", null);
    }

    public CodeRecord(String code, int boxes, double cbm, double cbmPerBox,
                      Double weightPerBox, String description, String warehouse,
                      String identityMode, List<String> expectedBoxIds) {
        this.code = canonical(code);
        this.boxes = boxes;
        this.cbm = cbm;
        this.cbmPerBox = cbmPerBox > 0 ? cbmPerBox : (boxes > 0 ? cbm / boxes : 0.0);
        this.weightPerBox = weightPerBox;
        this.description = description == null ? "" : description;
        this.warehouse = warehouse == null ? "" : warehouse;

        ArrayList<String> ids = new ArrayList<>();
        if (expectedBoxIds != null) {
            for (String raw : expectedBoxIds) {
                String id = canonical(raw);
                if (!id.isEmpty() && !ids.contains(id)) ids.add(id);
            }
        }
        this.expectedBoxIds = ids;

        String requested = identityMode == null ? "" : identityMode.trim().toUpperCase(Locale.ROOT);
        if (!ids.isEmpty()) this.identityMode = EXPLICIT;
        else if (U_SEQUENCE.equals(requested) || HYPHEN_SEQUENCE.equals(requested)
                || HYPHEN_UNIQUE.equals(requested) || EXPLICIT.equals(requested)) {
            this.identityMode = EXPLICIT.equals(requested) ? inferIdentityMode(this.code) : requested;
        } else {
            this.identityMode = inferIdentityMode(this.code);
        }

        if (EXPLICIT.equals(this.identityMode) && this.expectedBoxIds.size() != boxes) {
            throw new IllegalArgumentException("La lista explícita de cajas de " + this.code
                    + " debe contener exactamente " + boxes + " identificadores");
        }
    }

    public static String inferIdentityMode(String code) {
        String c = canonical(code);
        if (c.startsWith("MOYU")) return HYPHEN_SEQUENCE;
        if (c.matches("^(ZGA|ZGC|ZGD)\\d*[-_/].*") || c.startsWith("FUE")) return HYPHEN_UNIQUE;
        return U_SEQUENCE;
    }

    /** Identificador exacto cuando puede conocerse antes del escaneo. */
    public String expectedBarcode(int ordinal) {
        if (ordinal < 1 || ordinal > boxes) return "";
        if (EXPLICIT.equals(identityMode)) return expectedBoxIds.get(ordinal - 1);
        if (HYPHEN_SEQUENCE.equals(identityMode)) return code + "-" + ordinal;
        if (U_SEQUENCE.equals(identityMode)) return code + "U" + String.format(Locale.ROOT, "%03d", ordinal);
        return ""; // HYPHEN_UNIQUE: el sufijo externo se conoce al escanear.
    }

    public int ordinalOfExplicit(String barcode) {
        String b = canonical(barcode);
        for (int i = 0; i < expectedBoxIds.size(); i++) if (expectedBoxIds.get(i).equals(b)) return i + 1;
        return 0;
    }

    /** Identidad externa no consecutiva: se valida familia + unicidad + cantidad, no rango 1..N. */
    public boolean isDynamicIdentity() {
        return HYPHEN_UNIQUE.equals(identityMode) && expectedBoxIds.isEmpty();
    }

    public boolean hasKnownIdentities() {
        return !isDynamicIdentity();
    }

    public List<String> expectedIdsReadOnly() {
        return Collections.unmodifiableList(expectedBoxIds);
    }

    private static String canonical(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }
}
