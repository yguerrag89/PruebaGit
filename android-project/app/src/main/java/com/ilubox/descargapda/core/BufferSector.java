package com.ilubox.descargapda.core;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Sector físico dentro de una tarima buffer. Regla V0.4: un sector solo puede contener un código.
 * El mismo código sí puede ocupar varios sectores cuando crece; cada caja conserva el sector exacto
 * en el que fue colocada para que luego sea fácil recogerla al formar la tarima definitiva.
 */
public class BufferSector implements Serializable {
    private static final long serialVersionUID = 1L;

    public final int bufferIndex;
    public final int sectorIndex;
    public String code = "";
    public int boxes = 0;
    public double actualCbm = 0.0;
    public boolean codeComplete = false;
    public final ArrayList<String> barcodes = new ArrayList<>();
    public final ArrayList<Integer> boxNumbers = new ArrayList<>();

    public BufferSector(int bufferIndex, int sectorIndex) {
        this.bufferIndex = bufferIndex;
        this.sectorIndex = sectorIndex;
    }

    public String label() {
        char letter = (char) ('A' + Math.max(0, sectorIndex));
        return String.format("B%02d-%c", bufferIndex, letter);
    }

    public boolean isFree() {
        return code == null || code.isEmpty();
    }

    public void assign(String newCode) {
        if (!isFree() && !code.equals(newCode)) {
            throw new IllegalStateException(label() + " ya pertenece a " + code);
        }
        code = newCode == null ? "" : newCode;
    }

    public void addBox(String normalizedBarcode, int boxNumber, double cbm) {
        boxes += 1;
        actualCbm += Math.max(0.0, cbm);
        barcodes.add(normalizedBarcode);
        boxNumbers.add(boxNumber);
    }

    public boolean removeBarcode(String normalizedBarcode, double cbmPerBox) {
        int idx = barcodes.indexOf(normalizedBarcode);
        if (idx < 0) return false;
        barcodes.remove(idx);
        if (idx < boxNumbers.size()) boxNumbers.remove(idx);
        boxes = Math.max(0, boxes - 1);
        actualCbm = Math.max(0.0, actualCbm - Math.max(0.0, cbmPerBox));
        if (boxes == 0) clear();
        return true;
    }

    public List<String> barcodeSnapshot() {
        return new ArrayList<>(barcodes);
    }

    public void clear() {
        code = "";
        boxes = 0;
        actualCbm = 0.0;
        codeComplete = false;
        barcodes.clear();
        boxNumbers.clear();
    }
}
