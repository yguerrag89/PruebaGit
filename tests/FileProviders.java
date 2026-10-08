package com.ilubox.movimientosq9.tests;

import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.*;
import java.io.*;

/** Proveedores aislados de datos ficticios para probar las URI de Android. */
public final class FileProviders {
    private FileProviders() {}
    public static final class InventoryProvider extends android.content.ContentProvider {
        public boolean onCreate(){return true;}
        public String getType(Uri u){return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";}
        public Cursor query(Uri u,String[] p,String s,String[] a,String o){android.database.MatrixCursor c=new android.database.MatrixCursor(new String[]{android.provider.OpenableColumns.DISPLAY_NAME});c.addRow(new Object[]{"inventory_shared.xlsx"});return c;}
        public Uri insert(Uri u,android.content.ContentValues v){throw new UnsupportedOperationException();}public int delete(Uri u,String s,String[] a){return 0;}public int update(Uri u,android.content.ContentValues v,String s,String[] a){return 0;}
        public ParcelFileDescriptor openFile(Uri uri,String mode) throws FileNotFoundException{try{File f=new File(getContext().getFilesDir(),"inventory_shared.xlsx");try(InputStream in=getContext().getAssets().open("inventory_shared.xlsx");FileOutputStream out=new FileOutputStream(f)){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}return ParcelFileDescriptor.open(f,ParcelFileDescriptor.MODE_READ_ONLY);}catch(Exception e){throw new FileNotFoundException(e.toString());}}
    }

    public static final class ExportProvider extends android.provider.DocumentsProvider {
        private final java.util.Map<String,File> files=new java.util.HashMap<>();
        public boolean onCreate(){File root=new File(getContext().getFilesDir(),"documents");root.mkdirs();files.put("root",root);return true;}
        private File file(String id) throws FileNotFoundException{File f=files.get(id);if(f==null)throw new FileNotFoundException(id);return f;}
        public Cursor queryRoots(String[] projection){return new android.database.MatrixCursor(new String[]{android.provider.DocumentsContract.Root.COLUMN_ROOT_ID});}
        private android.database.MatrixCursor cursor(){return new android.database.MatrixCursor(new String[]{android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE,android.provider.DocumentsContract.Document.COLUMN_FLAGS});}
        private void add(android.database.MatrixCursor c,String id,File f){c.addRow(new Object[]{id,f.getName(),f.isDirectory()?android.provider.DocumentsContract.Document.MIME_TYPE_DIR:"application/octet-stream",f.isDirectory()?android.provider.DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE:android.provider.DocumentsContract.Document.FLAG_SUPPORTS_WRITE});}
        public Cursor queryDocument(String id,String[] projection) throws FileNotFoundException{android.database.MatrixCursor c=cursor();add(c,id,file(id));return c;}
        public Cursor queryChildDocuments(String parent,String[] projection,String sort) throws FileNotFoundException{android.database.MatrixCursor c=cursor();File p=file(parent);for(java.util.Map.Entry<String,File> e:files.entrySet())if(e.getValue().getParentFile().equals(p))add(c,e.getKey(),e.getValue());return c;}
        public String createDocument(String parent,String mime,String name) throws FileNotFoundException{try{String id=java.util.UUID.randomUUID().toString();File f=new File(file(parent),name);if(mime.equals(android.provider.DocumentsContract.Document.MIME_TYPE_DIR)){if(!f.mkdir())throw new IOException("No se creó carpeta");}else if(!f.createNewFile())throw new IOException("Archivo existente");files.put(id,f);return id;}catch(IOException e){throw new FileNotFoundException(e.toString());}}
        public boolean isChildDocument(String parent,String child){try{return file(child).getAbsolutePath().startsWith(file(parent).getAbsolutePath()+"/");}catch(FileNotFoundException e){return false;}}
        public ParcelFileDescriptor openDocument(String id,String mode,CancellationSignal signal) throws FileNotFoundException{return ParcelFileDescriptor.open(file(id),mode.contains("w")?ParcelFileDescriptor.MODE_WRITE_ONLY|ParcelFileDescriptor.MODE_TRUNCATE:ParcelFileDescriptor.MODE_READ_ONLY);}
    }
}
