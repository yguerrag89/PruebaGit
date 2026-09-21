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
import java.util.LinkedHashMap;
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
        public int manifestVersion;
        public LinkedHashMap<String, String> transferAssignments = new LinkedHashMap<>();
        public Set<String> directCodes = new HashSet<>();
        public Set<String> unitaryPallets = new HashSet<>();
        public Set<String> exceptionalPairCodes = new HashSet<>();
        public LinkedHashMap<String, String> rackSuggestions = new LinkedHashMap<>();
        public int estimatedDirectPallets = 0;
        public String transferStrategy = "";
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
        String schema = root.optString("schema", "");
        int version = root.optInt("version", 0);
        boolean v2 = "ilubox.pda.manifest.v2".equals(schema) && version == 2;
        boolean v3 = "ilubox.pda.manifest.v3".equals(schema) && version == 3;
        if (!v2 && !v3) {
            throw new IllegalArgumentException("Use el archivo PDA generado por Windows V0.16 (manifiesto v3)");
        }
        if (!root.optBoolean("strict_individual_barcodes", false)) {
            throw new IllegalArgumentException("El manifiesto no exige códigos individuales; genere uno nuevo en Windows");
        }
        if (v2) {
            JSONObject sequence = root.optJSONObject("individual_sequence");
            if (sequence == null || !"U".equalsIgnoreCase(sequence.optString("prefix", ""))
                    || sequence.optInt("start", 0) != 1 || !sequence.optBoolean("consecutive", false)
                    || sequence.optInt("padding", 0) != 3) {
                throw new IllegalArgumentException("El manifiesto v2 debe definir U001…UN consecutivos");
            }
        } else {
            JSONObject policy = root.optJSONObject("identity_policy");
            if (policy == null || !"MIXED_MANIFEST_DRIVEN".equals(policy.optString("model", ""))) {
                throw new IllegalArgumentException("El manifiesto v3 no contiene la política de identidad V0.16");
            }
        }

        ManifestData out = new ManifestData();
        out.manifestVersion = version;
        out.containerId = UnloadEngine.canonicalScan(root.optString("container_id", ""));
        if (out.containerId.isEmpty()) throw new IllegalArgumentException("Falta el identificador del contenedor");
        out.sourceFile = root.optString("source_file", "");
        out.recordSignature = root.optString("record_signature", "").trim().toLowerCase(Locale.ROOT);
        out.settings = new Settings();

        JSONObject s = root.optJSONObject("settings");
        if (s != null) {
            out.settings.physicalCapacity = s.optDouble("physical_capacity", out.settings.physicalCapacity);
            out.settings.targetCapacity = s.optDouble("target_capacity", out.settings.targetCapacity);
            out.settings.maxWeight = s.optDouble("max_weight", out.settings.maxWeight);
            out.settings.desirableMinWeight = s.optDouble("desirable_min_weight", out.settings.desirableMinWeight);
            out.settings.heavyLowThreshold = s.optDouble("heavy_low_threshold", out.settings.heavyLowThreshold);
            out.settings.largeRatio = s.optDouble("large_ratio", out.settings.largeRatio);
            out.settings.mediumHighRatio = s.optDouble("medium_high_ratio", out.settings.mediumHighRatio);
            out.settings.mediumRatio = s.optDouble("medium_ratio", out.settings.mediumRatio);
            out.settings.maxCodesUnit = s.optInt("max_codes_unit", out.settings.maxCodesUnit);
            out.settings.maxCodesSmall = s.optInt("max_codes_small", out.settings.maxCodesSmall);
            out.settings.maxCodesMedium = s.optInt("max_codes_medium", out.settings.maxCodesMedium);
            out.settings.maxCodesMediumHigh = s.optInt("max_codes_medium_high", out.settings.maxCodesMediumHigh);
        }

        JSONObject plan = root.optJSONObject("transfer_plan");
        if (plan != null) {
            out.transferStrategy = plan.optString("strategy", "");
            out.estimatedDirectPallets = plan.optInt("estimated_direct_pallets", 0);
            JSONObject assignments = plan.optJSONObject("assignments");
            if (assignments != null) {
                java.util.Iterator<String> keys = assignments.keys();
                while (keys.hasNext()) {
                    String rawKey = keys.next();
                    String barcode = UnloadEngine.canonicalScan(rawKey);
                    String pallet = UnloadEngine.canonicalScan(assignments.optString(rawKey, ""));
                    if (!barcode.isEmpty() && pallet.matches("T-\\d+")) out.transferAssignments.put(barcode, pallet);
                }
            }
            JSONArray directs = plan.optJSONArray("direct_codes");
            if (directs != null) for (int i = 0; i < directs.length(); i++)
                out.directCodes.add(UnloadEngine.canonicalScan(directs.optString(i, "")));
            JSONArray units = plan.optJSONArray("unitary_pallets");
            if (units != null) for (int i = 0; i < units.length(); i++)
                out.unitaryPallets.add(UnloadEngine.canonicalScan(units.optString(i, "")));
            JSONArray pairs = plan.optJSONArray("exceptional_pair_codes");
            if (pairs != null) for (int i = 0; i < pairs.length(); i++)
                out.exceptionalPairCodes.add(UnloadEngine.canonicalScan(pairs.optString(i, "")));
            JSONObject racks = plan.optJSONObject("rack_suggestions");
            if (racks != null) {
                java.util.Iterator<String> keys = racks.keys();
                while (keys.hasNext()) {
                    String pallet = keys.next();
                    out.rackSuggestions.put(UnloadEngine.canonicalScan(pallet), racks.optString(pallet, ""));
                }
            }
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

            String identityMode = v3 ? r.optString("identity_mode", "") : CodeRecord.U_SEQUENCE;
            ArrayList<String> expectedIds = new ArrayList<>();
            JSONArray ids = r.optJSONArray("expected_box_ids");
            if (ids != null) for (int j = 0; j < ids.length(); j++) {
                String id = UnloadEngine.canonicalScan(ids.optString(j, ""));
                if (!id.isEmpty()) expectedIds.add(id);
            }
            CodeRecord record = new CodeRecord(code, boxes, cbm, cbmPerBox, weight,
                    description, warehouse, identityMode, expectedIds);
            records.add(record);
        }
        if (records.isEmpty()) throw new IllegalArgumentException("El archivo no contiene códigos válidos");

        // Los identificadores externos no consecutivos no pueden asignarse caja-a-caja antes del escaneo.
        // Para esos códigos el plan debe reservar una definitiva homogénea dinámica (pie o TR de contingencia).
        for (CodeRecord record : records) {
            if (record.isDynamicIdentity() && !out.directCodes.contains(record.code)) {
                throw new IllegalArgumentException("Plan v3 inválido: " + record.code
                        + " usa identidad externa no consecutiva y debe figurar como código dinámico/directo");
            }
        }

        String calculated = recordSignature(records, v3);
        if (out.recordSignature.isEmpty() || !out.recordSignature.equals(calculated)) {
            throw new IllegalArgumentException("La firma del Packing List no coincide; genere nuevamente el archivo PDA");
        }
        out.records = records;
        return out;
    }

    private static String recordSignature(List<CodeRecord> records, boolean mixedIdentity) throws Exception {
        ArrayList<String> lines = new ArrayList<>();
        for (CodeRecord record : records) {
            StringBuilder line = new StringBuilder()
                    .append(UnloadEngine.canonicalScan(record.code)).append(':').append(record.boxes);
            if (mixedIdentity) {
                line.append(':').append(record.identityMode).append(':');
                for (int i = 0; i < record.expectedBoxIds.size(); i++) {
                    if (i > 0) line.append(',');
                    line.append(record.expectedBoxIds.get(i));
                }
            }
            line.append('\n');
            lines.add(line.toString());
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
