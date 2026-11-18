package com.zdownloader.app;

import android.content.*;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;

/** Test-only provider. No automatic grants: external Activity must forward a real temporary grant. */
public class ExternalAudioFixtureProvider extends ContentProvider {
    public boolean onCreate() {return true;}
    private File file(Uri uri) throws FileNotFoundException {
        String name=uri.getLastPathSegment();
        if(name==null||!name.matches("[A-Za-z0-9._-]+")) throw new FileNotFoundException();
        return new File(new File(getContext().getCacheDir(),"external-audio"),name);
    }
    public String getType(Uri uri) {
        String name=uri.getLastPathSegment();
        if(name==null)return "application/octet-stream";
        if(name.endsWith(".mp3"))return "audio/mpeg";
        if(name.endsWith(".m4a"))return "audio/mp4";
        if(name.endsWith(".flac"))return "audio/flac";
        if(name.endsWith(".wav"))return "audio/wav";
        if(name.endsWith(".mp4"))return "video/mp4";
        return "application/octet-stream";
    }
    public ParcelFileDescriptor openFile(Uri uri,String mode) throws FileNotFoundException {
        if(!mode.equals("r"))throw new FileNotFoundException();
        return ParcelFileDescriptor.open(file(uri),ParcelFileDescriptor.MODE_READ_ONLY);
    }
    public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort) {
        if(uri.getLastPathSegment()!=null&&uri.getLastPathSegment().startsWith("slow-qa-")) {
            try {Thread.sleep(3000);} catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}
        }
        String[] columns=projection==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:projection;
        MatrixCursor cursor=new MatrixCursor(columns);
        Object[] row=new Object[columns.length];
        for(int i=0;i<columns.length;i++) {
            if(columns[i].equals(OpenableColumns.DISPLAY_NAME))row[i]="External audio fixture."+uri.getLastPathSegment().substring(uri.getLastPathSegment().lastIndexOf('.')+1);
            if(columns[i].equals(OpenableColumns.SIZE))try{row[i]=file(uri).length();}catch(Exception ignored){}
        }
        cursor.addRow(row);return cursor;
    }
    public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}
    public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
    public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();}
}
