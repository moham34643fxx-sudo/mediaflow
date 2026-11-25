package com.zdownloader.app;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Isolated test-APK documents, never installed as a production provider. */
public class StatusFixtureProvider extends DocumentsProvider {
    public static final String AUTH="com.zdownloader.app.test.statusdocs";
    public static String authority(android.content.Context context) {
        return context.getPackageName()+".statusdocs";
    }
    public static final String WA="primary:Android/media/com.whatsapp/WhatsApp/Media/.Statuses";
    public static final String WB="primary:Android/media/com.whatsapp.w4b/WhatsApp Business/Media/.Statuses";
    public static final String WRONG="wrong-folder";
    private final ConcurrentHashMap<String,File> files=new ConcurrentHashMap<>();
    @Override public boolean onCreate() {
        File root=new File(getContext().getCacheDir(),"status-contract");
        files.put(WA,new File(root,"wa/.Statuses"));files.put(WB,new File(root,"wb/.Statuses"));files.put(WRONG,new File(root,"unrelated"));
        for(File file:files.values()) file.mkdirs();return true;
    }
    private File file(String id) throws FileNotFoundException {
        File file=files.get(id);if(file==null||!file.exists()) throw new FileNotFoundException("Missing fixture");return file;
    }
    private String[] columns(String[] p) {return p!=null?p:new String[]{"document_id","_display_name","mime_type","flags","_size","last_modified"};}
    private void row(MatrixCursor cursor,String id,File file) {
        MatrixCursor.RowBuilder row=cursor.newRow();boolean missing=file.getName().startsWith("unknown-");
        for(String c:cursor.getColumnNames()) {
            Object value=null;
            switch(c) {
                case "document_id":value=id;break;
                case "_display_name":value=file.getName();break;
                case "mime_type":value=file.isDirectory()?DocumentsContract.Document.MIME_TYPE_DIR:"application/octet-stream";break;
                case "flags":value=file.isDirectory()?DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE:DocumentsContract.Document.FLAG_SUPPORTS_WRITE|DocumentsContract.Document.FLAG_SUPPORTS_DELETE;break;
                case "_size":if(!missing)value=file.length();break;
                case "last_modified":if(!missing)value=file.lastModified();break;
            }
            row.add(value);
        }
    }
    @Override public Cursor queryRoots(String[] p) {return new MatrixCursor(p!=null?p:new String[]{"root_id","document_id","title","flags"});}
    @Override public Cursor queryDocument(String id,String[] p) throws FileNotFoundException {MatrixCursor cursor=new MatrixCursor(columns(p));row(cursor,id,file(id));return cursor;}
    @Override public Cursor queryChildDocuments(String parent,String[] p,String sort) throws FileNotFoundException {
        File root=file(parent);MatrixCursor cursor=new MatrixCursor(columns(p));
        for(Map.Entry<String,File> entry:files.entrySet()) if(entry.getValue().exists()&&root.equals(entry.getValue().getParentFile())) row(cursor,entry.getKey(),entry.getValue());
        return cursor;
    }
    @Override public String createDocument(String parent,String mime,String name) throws FileNotFoundException {
        if(name.contains("/")||name.contains("\\")||name.equals("..")) throw new FileNotFoundException("Invalid fixture name");
        File file=new File(file(parent),name);String id=parent+"/"+UUID.randomUUID();
        try {if(DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) file.mkdirs();else if(!file.createNewFile()) throw new IOException();}
        catch(IOException e) {throw new FileNotFoundException("Fixture creation failed");}
        files.put(id,file);return id;
    }
    @Override public void deleteDocument(String id) throws FileNotFoundException {File file=file(id);if(!file.delete()) throw new FileNotFoundException("Fixture delete failed");files.remove(id);}
    @Override public ParcelFileDescriptor openDocument(String id,String mode,CancellationSignal signal) throws FileNotFoundException {return ParcelFileDescriptor.open(file(id),ParcelFileDescriptor.parseMode(mode));}
    @Override public boolean isChildDocument(String parent,String id) {return id.startsWith(parent+"/");}
}
