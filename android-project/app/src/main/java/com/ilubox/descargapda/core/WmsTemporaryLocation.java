package com.ilubox.descargapda.core;

import java.util.Locale;

/** Valida solo el formato local; no consulta existencia ni bodega en XLWMS. */
public final class WmsTemporaryLocation {
    private WmsTemporaryLocation() {}

    public static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    public static String error(String value) {
        if (value != null) for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) < 32 || value.charAt(i) == 127)
                return "La temporal contiene caracteres de control; escanee solo la ubicación.";
        }
        String normalized = normalize(value);
        if (normalized.isEmpty()) return "Escanee o escriba la temporal WMS para cerrar la tarima.";
        if (!normalized.matches("[A-Z0-9][A-Z0-9._/-]{0,79}"))
            return "Temporal: use 1–80 caracteres (A–Z, números, punto, guion, guion bajo o /), sin espacios internos.";
        if (normalized.matches("(?:T-|TR-)[0-9]+|[ID](?:0[1-9]|10)"))
            return "T-xx, TR-xx e I01/D01 son identificadores locales, no la temporal del WMS.";
        return "";
    }

    public static boolean isCanonical(String value) {
        return value != null && error(value).isEmpty() && value.equals(normalize(value));
    }
}
