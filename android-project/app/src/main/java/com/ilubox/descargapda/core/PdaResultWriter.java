package com.ilubox.descargapda.core;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** Contrato v4: prueba física y temporal WMS pertenecen a la T, no al viaje ni al espacio reutilizable. */
public final class PdaResultWriter {
    private PdaResultWriter() {}

    public static final class AcceptedScan {
        public final String raw, barcode, code, time;
        public final int boxNumber;
        public AcceptedScan(String raw, String barcode, String code, int boxNumber, String time) {
            this.raw = raw; this.barcode = barcode; this.code = code;
            this.boxNumber = boxNumber; this.time = time;
        }
    }

    public static String recordSignature(UnloadEngine engine) throws Exception {
        ArrayList<String> codes = new ArrayList<>(engine.records.keySet());
        Collections.sort(codes);
        StringBuilder canonical = new StringBuilder();
        for (String code : codes) canonical.append(code.trim().toUpperCase(Locale.ROOT))
                .append(':').append(engine.records.get(code).boxes).append('\n');
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) hex.append(String.format(Locale.ROOT, "%02x", b & 255));
        return hex.toString();
    }

    public static void write(OutputStream output, UnloadEngine engine, List<AcceptedScan> scans) throws Exception {
        if (!engine.isTransferMode() && !engine.isManualMode()) {
            throw new IllegalStateException("El resultado WMS v4 requiere modo TRASLADO o MANUAL ASISTIDA.");
        }
        if (engine.isManualMode()) {
            for (UnloadEngine.FinalPalletView pallet : engine.finalPalletViews()) {
                if (pallet.scanned > 0 && !pallet.validated) {
                    throw new IllegalStateException(pallet.label
                            + " sigue abierta o sin revisión física. Cierre y valide todas las tarimas antes de exportar a Windows.");
                }
            }
        }
        for (String pallet : engine.validatedFinalPallets) {
            if (!WmsTemporaryLocation.isCanonical(engine.wmsTemporaryForPallet(pallet)))
                throw new IllegalStateException("La sesión contiene una verificación anterior sin temporal WMS (" + pallet
                        + "). No se inventará una ubicación. Conserve los reportes y use la versión original para esa sesión.");
        }
        HashSet<String> seen = new HashSet<>();
        for (AcceptedScan scan : scans) {
            UnloadEngine.ScanMeta meta = engine.scannedUniqueBarcodes.get(scan.barcode);
            if (meta == null || !seen.add(scan.barcode) || !meta.code.equals(scan.code) || meta.boxNumber != scan.boxNumber
                    || !engine.finalPalletForBarcode.containsKey(scan.barcode)) {
                throw new IllegalStateException("Historial y estado de cajas no coinciden; no se exportó un resultado incompleto.");
            }
        }
        if (seen.size() != engine.acceptedBoxCount()) throw new IllegalStateException("Faltan cajas en el historial persistido. Revise la sesión antes de exportar.");
        SimpleDateFormat stamp = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
        stamp.setTimeZone(TimeZone.getTimeZone("UTC"));
        int[] progress = engine.progress();
        StringBuilder body = new StringBuilder(Math.max(4096, scans.size() * 550));
        body.append("{\n  \"schema\":\"ilubox.pda.result.v4\",\n  \"version\":4,")
                .append("\n  \"container_id\":").append(json(engine.containerId))
                .append(",\n  \"record_signature\":").append(json(recordSignature(engine)))
                .append(",\n  \"exported_at\":").append(json(stamp.format(new Date())))
                .append(",\n  \"engine_version\":").append(json(UnloadEngine.ENGINE_VERSION))
                .append(",\n  \"transfer_plan_strategy\":").append(json(engine.transferPlanStrategy()))
                .append(",\n  \"exceptional_pair_codes\":").append(engine.exceptionalPairCodeCount())
                .append(",\n  \"verification_model\":\"FINAL_PALLET_WMS_TEMPORARY\"")
                .append(",\n  \"wms_location_validation\":\"FORMAT_ONLY\"")
                .append(",\n  \"individual_sequence\":{\"prefix\":\"U\",\"start\":1,\"consecutive\":true,\"padding\":3}")
                .append(",\n  \"progress\":{\"received\":").append(progress[0])
                .append(",\"expected\":").append(progress[1])
                .append(",\"in_final\":").append(engine.inFinalBoxCount())
                .append(",\"wms_eligible\":").append(engine.wmsEligibleBoxCount()).append("}")
                .append(",\n  \"active_transfer\":").append(json(engine.currentTransferPallet()))
                .append(",\n  \"transfers\":[\n");
        List<String> transfers = engine.transferLabels();
        for (int i = 0; i < transfers.size(); i++) {
            String transfer = transfers.get(i);
            body.append("    {\"id\":").append(json(transfer))
                    .append(",\"closed\":").append(engine.isTransferClosed(transfer))
                    .append(",\"boxes\":").append(engine.transferBoxCount(transfer))
                    .append(",\"verified_boxes\":").append(engine.transferVerifiedBoxCount(transfer))
                    .append(",\"status\":").append(json(engine.transferStatus(transfer))).append('}');
            if (i + 1 < transfers.size()) body.append(',');
            body.append('\n');
        }
        body.append("  ],\n  \"pallets\":[\n");
        List<UnloadEngine.FinalPalletView> pallets = engine.finalPalletViews();
        for (int i = 0; i < pallets.size(); i++) {
            UnloadEngine.FinalPalletView pallet = pallets.get(i);
            UnloadEngine.PalletVerification proof = engine.verificationForPallet(pallet.label);
            body.append("    {\"id\":").append(json(pallet.label))
                    .append(",\"formation\":").append(json(pallet.direct ? "PIE" : "TENDIDO"))
                    .append(",\"physical_position\":").append(json(pallet.physicalPosition))
                    .append(",\"wms_temporary_location\":").append(json(engine.wmsTemporaryForPallet(pallet.label)))
                    .append(",\"status\":").append(json(pallet.status))
                    .append(",\"expected\":").append(pallet.expected)
                    .append(",\"original_expected\":").append(pallet.originalExpected)
                    .append(",\"scanned\":").append(pallet.scanned)
                    .append(",\"in_final\":").append(pallet.received)
                    .append(",\"validated\":").append(pallet.validated)
                    .append(",\"retired\":").append(pallet.retired)
                    .append(",\"closure_reason\":").append(json(pallet.closureReason))
                    .append(",\"planned_cbm\":").append(String.format(Locale.ROOT, "%.6f", pallet.plannedCbm))
                    .append(",\"planned_weight_kg\":").append(String.format(Locale.ROOT, "%.3f", pallet.plannedWeight))
                    .append(",\"rack_suggestion\":").append(json(pallet.rackSuggestion))
                    .append(",\"remark_required\":").append(pallet.remarkRequired)
                    .append(",\"split_code_count\":").append(pallet.splitCodeCount)
                    .append(",\"verification_method\":").append(json(proof == null ? "" : proof.method))
                    .append(",\"verified_by\":").append(json(proof == null ? "" : proof.responsible))
                    .append(",\"verified_at\":").append(json(proof == null ? "" : proof.time))
                    .append(",\"verified_boxes\":").append(proof == null ? 0 : proof.boxes).append('}');
            if (i + 1 < pallets.size()) body.append(',');
            body.append('\n');
        }
        body.append("  ],\n  \"accepted_events\":[\n");
        for (int i = 0; i < scans.size(); i++) {
            AcceptedScan scan = scans.get(i);
            String pallet = engine.finalPalletForBarcode.get(scan.barcode);
            String transfer = engine.transferForBarcode.get(scan.barcode);
            boolean direct = engine.directFinalCodes.contains(scan.code) || engine.isManualFinalPallet(pallet);
            body.append("    {\"raw_scan\":").append(json(scan.raw))
                    .append(",\"barcode\":").append(json(scan.barcode))
                    .append(",\"code\":").append(json(scan.code))
                    .append(",\"box_number\":").append(scan.boxNumber)
                    .append(",\"final_pallet\":").append(json(pallet))
                    .append(",\"physical_position\":").append(json(engine.physicalPositionForPallet(pallet)))
                    .append(",\"wms_temporary_location\":").append(json(engine.wmsTemporaryForPallet(pallet)))
                    .append(",\"transfer_pallet\":").append(json(transfer))
                    .append(",\"direct_to_final\":").append(direct)
                    .append(",\"transfer_closed\":").append(!direct && engine.isTransferClosed(transfer))
                    .append(",\"physical_state\":").append(json(engine.boxPhysicalState(scan.barcode)))
                    .append(",\"final_pallet_validated\":").append(engine.validatedFinalPallets.contains(pallet))
                    .append(",\"wms_eligible\":").append(engine.isBoxWmsEligible(scan.barcode))
                    .append(",\"scanned_at\":").append(json(scan.time)).append('}');
            if (i + 1 < scans.size()) body.append(',');
            body.append('\n');
        }
        body.append("  ]\n}\n");
        output.write(body.toString().getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private static String json(String value) {
        String text = value == null ? "" : value;
        StringBuilder out = new StringBuilder().append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) out.append(String.format(Locale.ROOT, "\\u%04x", (int)c));
                    else out.append(c);
            }
        }
        return out.append('"').toString();
    }
}
