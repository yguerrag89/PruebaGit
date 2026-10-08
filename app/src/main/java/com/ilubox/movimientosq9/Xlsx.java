package com.ilubox.movimientosq9;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import android.util.Xml;
import org.xmlpull.v1.XmlPullParser;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/** Lectura secuencial XLSX y escritura de texto explícito, sin fórmulas. */
public final class Xlsx {
    private Xlsx() {}
    private static final String NS="http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    public interface Rows { boolean wants(String column); void row(int number,Map<String,String> cells) throws Exception; }
    public interface Sink { void row(Object... values) throws Exception; }
    public interface Source { void write(Sink sink) throws Exception; }
    public static final class Sheet {
        final String title;final String[] headers;final Source source;
        public Sheet(String title,String[] headers,Source source){this.title=title;this.headers=headers;this.source=source;}
    }
    private static int next(XmlPullParser p) throws Exception {
        int t=p.nextToken();if(t==XmlPullParser.DOCDECL)throw new IOException("El Excel contiene una declaración XML no admitida.");return t;
    }
    private static XmlPullParser parser(InputStream in) throws Exception {
        XmlPullParser p=Xml.newPullParser();p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES,true);p.setInput(in,null);return p;
    }
    private static String text(XmlPullParser p) throws Exception {
        int depth=p.getDepth();StringBuilder b=new StringBuilder();
        while(next(p)!=XmlPullParser.END_DOCUMENT){int t=p.getEventType();if(t==XmlPullParser.END_TAG&&p.getDepth()==depth)break;if(t==XmlPullParser.TEXT||t==XmlPullParser.CDSECT||t==XmlPullParser.ENTITY_REF){if(p.getText()!=null)b.append(p.getText());if(b.length()>32767)throw new IOException("Texto Excel demasiado largo.");}}
        return b.toString();
    }
    private static String sheetPath(ZipFile z) throws Exception {
        String rid="";
        try(InputStream in=z.getInputStream(required(z,"xl/workbook.xml"))){XmlPullParser p=parser(in);while(next(p)!=XmlPullParser.END_DOCUMENT)if(p.getEventType()==XmlPullParser.START_TAG&&p.getName().equals("sheet")){rid=p.getAttributeValue("http://schemas.openxmlformats.org/officeDocument/2006/relationships","id");break;}}
        if(rid==null||rid.isEmpty())throw new IOException("El Excel no contiene hojas.");
        try(InputStream in=z.getInputStream(required(z,"xl/_rels/workbook.xml.rels"))){XmlPullParser p=parser(in);while(next(p)!=XmlPullParser.END_DOCUMENT)if(p.getEventType()==XmlPullParser.START_TAG&&p.getName().equals("Relationship")&&rid.equals(p.getAttributeValue(null,"Id"))){String target=p.getAttributeValue(null,"Target");if("External".equals(p.getAttributeValue(null,"TargetMode"))||target==null||target.contains("..")||target.contains("\\")||target.contains(":"))break;String result=target.startsWith("/")?target.substring(1):"xl/"+target;required(z,result);return result;}}
        throw new IOException("No se pudo localizar la hoja de inventario.");
    }
    private static ZipEntry required(ZipFile z,String name) throws IOException {ZipEntry e=z.getEntry(name);if(e==null)throw new IOException("El archivo no es un Excel .xlsx válido: falta "+name);return e;}
    public static void read(File file,File temp,Rows rows,NativeClient.Progress progress) throws Exception {
        SQLiteDatabase strings=null;
        try(ZipFile z=new ZipFile(file)){
            long bytes=0;int entries=0;Set<String> names=new HashSet<>();Enumeration<? extends ZipEntry> it=z.entries();
            while(it.hasMoreElements()){ZipEntry e=it.nextElement();if(!names.add(e.getName())||++entries>10000||e.getSize()<0)throw new IOException("Estructura Excel inválida.");bytes+=e.getSize();if(bytes>1500000000L)throw new IOException("El Excel es demasiado grande al descomprimir.");}
            strings=SQLiteDatabase.openOrCreateDatabase(temp,null);strings.execSQL("PRAGMA journal_mode=OFF");strings.execSQL("CREATE TABLE strings(id INTEGER PRIMARY KEY,value TEXT)");
            ZipEntry ss=z.getEntry("xl/sharedStrings.xml");
            if(ss!=null){progress.report("Leyendo textos del Excel…");strings.beginTransaction();
                try(SQLiteStatement insert=strings.compileStatement("INSERT INTO strings VALUES(?,?)");InputStream in=z.getInputStream(ss)){
                    XmlPullParser p=parser(in);int index=0;StringBuilder value=null;boolean phonetic=false;
                    while(next(p)!=XmlPullParser.END_DOCUMENT){int t=p.getEventType();String name=p.getName();
                        if(t==XmlPullParser.START_TAG&&"si".equals(name))value=new StringBuilder();
                        else if(t==XmlPullParser.START_TAG&&"rPh".equals(name))phonetic=true;
                        else if(t==XmlPullParser.END_TAG&&"rPh".equals(name))phonetic=false;
                        else if(t==XmlPullParser.START_TAG&&"t".equals(name)&&value!=null&&!phonetic){value.append(text(p));if(value.length()>32767)throw new IOException("Texto Excel demasiado largo.");}
                        else if(t==XmlPullParser.END_TAG&&"si".equals(name)){if(value==null||index>=2000000)throw new IOException("Tabla de textos Excel inválida.");insert.bindLong(1,index++);insert.bindString(2,value.toString());insert.executeInsert();value=null;if(index%10000==0)progress.report("Textos leídos: "+index);}
                    }strings.setTransactionSuccessful();
                }finally{strings.endTransaction();}
            }
            LinkedHashMap<Integer,String> cache=new LinkedHashMap<Integer,String>(256,0.75f,true){protected boolean removeEldestEntry(Map.Entry<Integer,String> e){return size()>256;}};
            try(InputStream in=z.getInputStream(required(z,sheetPath(z)))){
                XmlPullParser p=parser(in);Map<String,String> cells=null;int row=0;String col="",type="",value="";boolean wanted=false,formula=false;
                while(next(p)!=XmlPullParser.END_DOCUMENT){int t=p.getEventType();String name=p.getName();
                    if(t==XmlPullParser.START_TAG&&"row".equals(name)){row++;if(row>2000001)throw new IOException("Demasiadas filas de inventario.");cells=new LinkedHashMap<>();}
                    else if(t==XmlPullParser.START_TAG&&"c".equals(name)){String address=p.getAttributeValue(null,"r");if(address==null||!address.matches("[A-Z]{1,3}[0-9]+"))throw new IOException("Referencia de celda inválida.");col=address.replaceAll("[0-9]","");type=p.getAttributeValue(null,"t");wanted=rows.wants(col);value="";formula=false;}
                    else if(t==XmlPullParser.START_TAG&&"f".equals(name)&&wanted)formula=true;
                    else if(t==XmlPullParser.START_TAG&&"v".equals(name)&&wanted)value=text(p);
                    else if(t==XmlPullParser.START_TAG&&"t".equals(name)&&wanted&&"inlineStr".equals(type))value+=text(p);
                    else if(t==XmlPullParser.END_TAG&&"c".equals(name)&&wanted){
                        if(formula)throw new IOException("Fila "+row+": una columna de validación contiene fórmula. Usa el BoxInventory original del WMS.");
                        if("s".equals(type)){int id;try{id=Integer.parseInt(value);}catch(Exception e){throw new IOException("Referencia de texto Excel inválida.");}String v=cache.get(id);if(v==null){try(Cursor c=strings.rawQuery("SELECT value FROM strings WHERE id=?",new String[]{String.valueOf(id)})){if(!c.moveToFirst())throw new IOException("Referencia de texto Excel inexistente.");v=c.getString(0);}cache.put(id,v);}value=v;}
                        if(cells==null||cells.put(col,value)!=null)throw new IOException("Celda duplicada o fuera de fila.");
                    }else if(t==XmlPullParser.END_TAG&&"row".equals(name)){rows.row(row,cells);cells=null;}
                }
            }
        }catch(ZipException e){throw new IOException("Selecciona el archivo BoxInventory en formato Excel .xlsx.",e);}
        finally{if(strings!=null)strings.close();temp.delete();new File(temp+"-journal").delete();}
    }
    public static String xml(String raw){StringBuilder s=new StringBuilder();if(raw==null)return "";for(int i=0;i<raw.length();i++){char c=raw.charAt(i);if(c<32&&c!='\n'&&c!='\r'&&c!='\t')c=' ';switch(c){case '&':s.append("&amp;");break;case '<':s.append("&lt;");break;case '>':s.append("&gt;");break;case '"':s.append("&quot;");break;default:s.append(c);}}return s.toString();}
    private static String column(int n){String s="";while(n>0){n--;s=(char)('A'+n%26)+s;n/=26;}return s;}
    private static void entry(ZipOutputStream z,String name,String text) throws Exception {z.putNextEntry(new ZipEntry(name));z.write(text.getBytes(StandardCharsets.UTF_8));z.closeEntry();}
    public static void write(File file,List<Sheet> sheets) throws Exception {
        try(FileOutputStream f=new FileOutputStream(file);ZipOutputStream z=new ZipOutputStream(f)){
            StringBuilder types=new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>");
            StringBuilder wb=new StringBuilder("<workbook xmlns=\""+NS+"\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>"),rels=new StringBuilder("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"style\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>");
            for(int n=1;n<=sheets.size();n++){types.append("<Override PartName=\"/xl/worksheets/sheet").append(n).append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");wb.append("<sheet name=\"").append(xml(sheets.get(n-1).title)).append("\" sheetId=\"").append(n).append("\" r:id=\"r").append(n).append("\"/>");rels.append("<Relationship Id=\"r").append(n).append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet").append(n).append(".xml\"/>");}
            entry(z,"[Content_Types].xml",types+"</Types>");entry(z,"_rels/.rels","<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"book\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>");entry(z,"xl/workbook.xml",wb+"</sheets></workbook>");entry(z,"xl/_rels/workbook.xml.rels",rels+"</Relationships>");
            entry(z,"xl/styles.xml","<styleSheet xmlns=\""+NS+"\"><fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Arial\"/></font><font><b/><sz val=\"11\"/><color rgb=\"FFFFFFFF\"/><name val=\"Arial\"/></font></fonts><fills count=\"3\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill><fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF143B50\"/><bgColor indexed=\"64\"/></patternFill></fill></fills><borders count=\"1\"><border/></borders><cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs><cellXfs count=\"2\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"><alignment vertical=\"top\" wrapText=\"1\"/></xf><xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"0\" xfId=\"0\" applyFill=\"1\" applyFont=\"1\"><alignment vertical=\"center\" wrapText=\"1\"/></xf></cellXfs><cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles></styleSheet>");
            for(int n=1;n<=sheets.size();n++){
                Sheet sheet=sheets.get(n-1);z.putNextEntry(new ZipEntry("xl/worksheets/sheet"+n+".xml"));Writer w=new OutputStreamWriter(z,StandardCharsets.UTF_8);
                w.write("<worksheet xmlns=\""+NS+"\"><sheetViews><sheetView showGridLines=\"0\" workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews><cols>");for(int c=1;c<=sheet.headers.length;c++)w.write("<col min=\""+c+"\" max=\""+c+"\" width=\""+(c==1?30:24)+"\" customWidth=\"1\"/>");w.write("</cols><sheetData>");
                final int[] count={0};Sink sink=values->{int row=++count[0];if(row>1048576)throw new IOException("El reporte supera las filas permitidas por Excel.");w.write("<row r=\""+row+"\""+(row==1?" ht=\"34\" customHeight=\"1\"":"")+">");for(int c=0;c<values.length;c++){Object v=values[c];w.write("<c r=\""+column(c+1)+row+"\" s=\""+(row==1?1:0)+"\"");if(v instanceof Number){w.write("><v>"+v+"</v></c>");}else {String text=v==null?"":String.valueOf(v);if(text.length()>32767)throw new IOException("Una celda supera el límite de texto de Excel.");w.write(" t=\"inlineStr\"><is><t xml:space=\"preserve\">"+xml(text)+"</t></is></c>");}}w.write("</row>");};
                sink.row((Object[])sheet.headers);sheet.source.write(sink);w.write("</sheetData><autoFilter ref=\"A1:"+column(sheet.headers.length)+count[0]+"\"/></worksheet>");w.flush();z.closeEntry();
            }z.finish();z.flush();f.getFD().sync();
        }
    }
}
