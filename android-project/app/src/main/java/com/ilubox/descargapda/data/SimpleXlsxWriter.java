package com.ilubox.descargapda.data;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Escritor XLSX mínimo sin dependencias externas. Usa inline strings y un estilo
 * sencillo para encabezados. Suficiente para reportes operativos y plantillas WMS.
 */
public final class SimpleXlsxWriter {
    private SimpleXlsxWriter() {}

    public static class Sheet {
        public final String name;
        public final List<List<Object>> rows = new ArrayList<>();
        public int headerRows = 1;

        public Sheet(String name) { this.name = safeSheetName(name); }
        public Sheet add(Object... values) {
            ArrayList<Object> row = new ArrayList<>();
            if (values != null) for (Object v : values) row.add(v);
            rows.add(row);
            return this;
        }
    }

    public static void write(OutputStream out, List<Sheet> sheets) throws Exception {
        if (sheets == null || sheets.isEmpty()) throw new IllegalArgumentException("Sin hojas para exportar");
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            put(zip, "[Content_Types].xml", contentTypes(sheets.size()));
            put(zip, "_rels/.rels", rootRels());
            put(zip, "xl/workbook.xml", workbookXml(sheets));
            put(zip, "xl/_rels/workbook.xml.rels", workbookRels(sheets.size()));
            put(zip, "xl/styles.xml", stylesXml());
            for (int i = 0; i < sheets.size(); i++) {
                put(zip, "xl/worksheets/sheet" + (i + 1) + ".xml", sheetXml(sheets.get(i)));
            }
            zip.finish();
        }
    }

    private static void put(ZipOutputStream zip, String path, String content) throws Exception {
        zip.putNextEntry(new ZipEntry(path));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String contentTypes(int n) {
        StringBuilder s = new StringBuilder();
        s.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
         .append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
         .append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
         .append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
         .append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>")
         .append("<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>");
        for (int i=1;i<=n;i++) s.append("<Override PartName=\"/xl/worksheets/sheet").append(i)
                .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
        return s.append("</Types>").toString();
    }

    private static String rootRels() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"+
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"+
                "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"+
                "</Relationships>";
    }

    private static String workbookXml(List<Sheet> sheets) {
        StringBuilder s=new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>");
        for(int i=0;i<sheets.size();i++) s.append("<sheet name=\"").append(xml(sheets.get(i).name)).append("\" sheetId=\"").append(i+1).append("\" r:id=\"rId").append(i+1).append("\"/>");
        return s.append("</sheets></workbook>").toString();
    }

    private static String workbookRels(int n) {
        StringBuilder s=new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
        for(int i=1;i<=n;i++) s.append("<Relationship Id=\"rId").append(i).append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet").append(i).append(".xml\"/>");
        s.append("<Relationship Id=\"rId").append(n+1).append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>");
        return s.append("</Relationships>").toString();
    }

    private static String stylesXml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"+
                "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"+
                "<fonts count=\"2\"><font><sz val=\"10\"/><name val=\"Calibri\"/></font><font><b/><color rgb=\"FFFFFFFF\"/><sz val=\"10\"/><name val=\"Calibri\"/></font></fonts>"+
                "<fills count=\"3\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill><fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF1F4E78\"/><bgColor indexed=\"64\"/></patternFill></fill></fills>"+
                "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>"+
                "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"+
                "<cellXfs count=\"2\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/><xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\" applyAlignment=\"1\"><alignment horizontal=\"center\" vertical=\"center\" wrapText=\"1\"/></xf></cellXfs>"+
                "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>"+
                "</styleSheet>";
    }

    private static String sheetXml(Sheet sheet) {
        StringBuilder s=new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews><sheetFormatPr defaultRowHeight=\"15\"/><sheetData>");
        for(int r=0;r<sheet.rows.size();r++) {
            List<Object> row=sheet.rows.get(r);
            s.append("<row r=\"").append(r+1).append("\">");
            for(int c=0;c<row.size();c++) {
                Object v=row.get(c); if(v==null) continue;
                String ref=col(c+1)+(r+1);
                int style = r < sheet.headerRows ? 1 : 0;
                if(v instanceof Number) {
                    s.append("<c r=\"").append(ref).append("\" s=\"").append(style).append("\"><v>")
                     .append(String.format(Locale.ROOT,"%s",v)).append("</v></c>");
                } else if(v instanceof Boolean) {
                    s.append("<c r=\"").append(ref).append("\" s=\"").append(style).append("\" t=\"b\"><v>")
                     .append(((Boolean)v)?"1":"0").append("</v></c>");
                } else {
                    s.append("<c r=\"").append(ref).append("\" s=\"").append(style).append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                     .append(xml(String.valueOf(v))).append("</t></is></c>");
                }
            }
            s.append("</row>");
        }
        return s.append("</sheetData></worksheet>").toString();
    }

    private static String col(int n) {
        StringBuilder s=new StringBuilder();
        while(n>0){ int r=(n-1)%26; s.insert(0,(char)('A'+r)); n=(n-1)/26; }
        return s.toString();
    }

    private static String xml(String s) {
        if(s==null) return "";
        return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;");
    }

    private static String safeSheetName(String x) {
        if(x==null || x.trim().isEmpty()) return "Hoja";
        String s=x.replaceAll("[\\\\/:?*\\[\\]]","_").trim();
        return s.length()>31 ? s.substring(0,31) : s;
    }
}
