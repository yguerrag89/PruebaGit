package com.ilubox.descargapda.data;

import com.ilubox.descargapda.core.CodeRecord;
import com.ilubox.descargapda.core.Settings;
import com.ilubox.descargapda.core.UnloadEngine;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class ManifestImporter {
    public static class ManifestData {
        public String containerId;
        public Settings settings;
        public List<CodeRecord> records;
        public String sourceFile;
        public String recordSignature;
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
        String s = out.toString(StandardCharsets.UTF_8.name());
        if (!s.isEmpty() && s.charAt(0) == '\uFEFF') s = s.substring(1);
        return s;
    }

    public static ManifestData parse(InputStream in) throws Exception {
        JSONObject root = new JSONObject(readAll(in));
        if (!"ilubox.pda.manifest.v2".equals(root.optString("schema", ""))) {
            throw new IllegalArgumentException("Use el archivo PDA generado por Windows V0.11 (manifiesto v2)");
        }
        if (root.optInt("version", 0) != 2 || !root.optBoolean("strict_individual_barcodes", false)) {
            throw new IllegalArgumentException("El manifiesto no exige códigos individuales; genere uno nuevo en Windows");
        }
        JSONObject sequence = root.optJSONObject("individual_sequence");
        if (sequence == null || !"U".equalsIgnoreCase(sequence.optString("prefix", ""))
                || sequence.optInt("start", 0) != 1 || !sequence.optBoolean("consecutive", false)
                || sequence.optInt("padding", 0) != 3) {
            throw new IllegalArgumentException("La lista debe definir U001…UN consecutivos comenzando en 1");
        }
        ManifestData out = new ManifestData();
        out.containerId = UnloadEngine.canonicalScan(root.optString("container_id", ""));
        if (out.containerId.isEmpty()) throw new IllegalArgumentException("Falta el identificador del contenedor");
        out.sourceFile = root.optString("source_file", "");
        out.recordSignature = root.optString("record_signature", "").trim().toLowerCase(Locale.ROOT);
        out.settings = new Settings();

        JSONObject s = root.optJSONObject("settings");
        if (s != null) {
            out.settings.physicalCapacity = s.optDouble("physical_capacity", out.settings.physicalCapacity);
            out.settings.targetCapacity = s.optDouble("target_capacity", out.settings.targetCapacity);
            out.settings.largeRatio = s.optDouble("large_ratio", out.settings.largeRatio);
            out.settings.mediumHighRatio = s.optDouble("medium_high_ratio", out.settings.mediumHighRatio);
            out.settings.mediumRatio = s.optDouble("medium_ratio", out.settings.mediumRatio);
            out.settings.maxCodesUnit = s.optInt("max_codes_unit", out.settings.maxCodesUnit);
            out.settings.maxCodesSmall = s.optInt("max_codes_small", out.settings.maxCodesSmall);
            out.settings.maxCodesMedium = s.optInt("max_codes_medium", out.settings.maxCodesMedium);
            out.settings.maxCodesMediumHigh = s.optInt("max_codes_medium_high", out.settings.maxCodesMediumHigh);
        }

        JSONArray arr = root.getJSONArray("records");
        ArrayList<CodeRecord> records = new ArrayList<>();
        Set<String> seenCodes = new HashSet<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject r = arr.getJSONObject(i);
            String code = UnloadEngine.canonicalScan(r.getString("code"));
            int boxes = r.getInt("boxes");
            double cbm = r.getDouble("cbm");
            double cbmPerBox = r.optDouble("cbm_per_box", boxes > 0 ? cbm / boxes : 0.0);
            Double weight = r.has("weight_per_box") && !r.isNull("weight_per_box") ? r.getDouble("weight_per_box") : null;
            String description = r.optString("description", "");
            String warehouse = r.optString("warehouse", "");
            if (code.isEmpty() || boxes <= 0 || cbm < 0) {
                throw new IllegalArgumentException("Registro inválido en la fila " + (i + 1));
            }
            if (!seenCodes.add(code)) throw new IllegalArgumentException("Código duplicado en el manifiesto: " + code);
            records.add(new CodeRecord(code, boxes, cbm, cbmPerBox, weight, description, warehouse));
        }
        if (records.isEmpty()) throw new IllegalArgumentException("El archivo no contiene códigos válidos");
        String calculated = recordSignature(records);
        if (out.recordSignature.isEmpty() || !out.recordSignature.equals(calculated)) {
            throw new IllegalArgumentException("La firma del Packing List no coincide; genere nuevamente el archivo PDA");
        }
        out.records = records;
        return out;
    }

    private static String recordSignature(List<CodeRecord> records) throws Exception {
        ArrayList<String> lines = new ArrayList<>();
        for (CodeRecord record : records) {
            lines.add(UnloadEngine.canonicalScan(record.code) + ":" + record.boxes + "\n");
        }
        Collections.sort(lines);
        StringBuilder canonical = new StringBuilder();
        for (String line : lines) canonical.append(line);
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) hex.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return hex.toString();
    }
}
