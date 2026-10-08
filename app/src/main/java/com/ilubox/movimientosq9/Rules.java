package com.ilubox.movimientosq9;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Normalización compatible con core.inventory de Windows. Sin dependencia de UI. */
public final class Rules {
    private static final Pattern BOX_SUFFIX=Pattern.compile("(.+?)U0*(\\d+)");
    private static final Pattern LEADING_ZERO=Pattern.compile("^0+(?!$)");
    private static final Pattern LOCATION_PUNCTUATION=Pattern.compile("[^A-Z0-9]");
    private Rules() {}
    public static String clean(String raw) { return raw == null ? "" : raw.trim().replace('\u3000', ' ').trim(); }
    public static String barcode(String raw) {
        String upper = clean(raw).toUpperCase(Locale.ROOT);
        StringBuilder compact = new StringBuilder();
        // Android 6 no admite la bandera regex (?U). Python y Android deben
        // eliminar también espacios Unicode y el separador NEL.
        for (int i = 0; i < upper.length(); i++) {
            char c = upper.charAt(i);
            if (!Character.isWhitespace(c) && !Character.isSpaceChar(c) && c != '\u0085') compact.append(c);
        }
        String s = compact.toString();
        Matcher m = BOX_SUFFIX.matcher(s);
        if (!m.matches()) return s;
        String number = LEADING_ZERO.matcher(m.group(2)).replaceFirst("");
        while (number.length() < 3) number = "0" + number;
        return m.group(1) + "U" + number;
    }
    public static String locationKey(String raw) {
        return LOCATION_PUNCTUATION.matcher(Normalizer.normalize(clean(raw), Normalizer.Form.NFKC).toUpperCase(Locale.ROOT)).replaceAll("");
    }
    public static boolean looksLocation(String raw) {
        return clean(raw).toUpperCase(Locale.ROOT).matches("(?:2[AB]|MC|MD)[?_'\\-].*") && !barcode(raw).matches(".*U\\d+$");
    }
    public static String proposedDestination(String raw) {
        String key = locationKey(raw);
        if (key.isEmpty() || clean(raw).length() > 100 || key.matches(".*U\\d+$")) throw new IllegalArgumentException("Escanea una ubicación destino válida.");
        Matcher m = Pattern.compile("(2[AB])M(\\d+)([A-Z]\\d{3,})").matcher(key);
        Matcher t = Pattern.compile("(2[AB])TMP(\\d+)").matcher(key);
        String proposed = m.matches() ? m.group(1) + "_M" + m.group(2) + "-" + m.group(3) : t.matches() ? t.group(1) + "-TMP" + t.group(2) : clean(raw).toUpperCase(Locale.ROOT);
        if (proposed.matches(".*[?'\"<>].*")) throw new IllegalArgumentException("Escribe el código oficial de la ubicación del WMS.");
        return proposed;
    }
    public static String label(String status) {
        switch (status) {
            case "ACCEPTED": return "ACEPTADA";
            case "BLOCKED": return "CAJA BLOQUEADA";
            case "DUPLICATE": return "YA ESCANEADA";
            case "CANCELLED": return "RETIRADA DEL LOTE";
            default: return "REVISAR CAJA";
        }
    }
}
