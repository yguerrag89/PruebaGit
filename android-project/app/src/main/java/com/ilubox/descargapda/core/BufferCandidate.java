package com.ilubox.descargapda.core;

import java.util.ArrayList;
import java.util.List;

/** Bloque que ya puede probarse físicamente en una tarima definitiva. */
public class BufferCandidate {
    public String id = "";
    public String code = "";
    public String reason = "";
    public int boxes = 0;
    public double cbm = 0.0;
    public double weight = -1.0;
    public boolean completeCode = false;
    public final ArrayList<String> sectorLabels = new ArrayList<>();

    public String sourceText() {
        if (sectorLabels.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String s : sectorLabels) {
            if (sb.length() > 0) sb.append(" + ");
            sb.append(s);
        }
        return sb.toString();
    }

    public List<String> sectors() {
        return new ArrayList<>(sectorLabels);
    }
}
