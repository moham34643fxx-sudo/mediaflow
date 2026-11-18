package com.zdownloader.app;

import android.app.Application;
import android.app.Instrumentation;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;

/** Test-APK-only read-only snapshot, before application startup recovery executes. */
public class UserFailureSnapshotInstrumentation extends Instrumentation {
    private Bundle args;
    private File folder;
    private Throwable snapshotError;
    private final java.util.concurrent.CountDownLatch captured=new java.util.concurrent.CountDownLatch(1);
    @Override public void onCreate(Bundle arguments) {super.onCreate(arguments);args=arguments;start();}
    private void copy(File source,File target) throws Exception {
        if(!source.isFile()) return;
        target.getParentFile().mkdirs();
        // Do not copy private 0600 permission bits onto an external diagnostic
        // copy: some OEMs then prevent adb from retrieving the preserved evidence.
        try(java.io.InputStream input=Files.newInputStream(source.toPath());java.io.OutputStream output=Files.newOutputStream(target.toPath())) {
            byte[] buffer=new byte[16384];int count;
            while((count=input.read(buffer))!=-1) output.write(buffer,0,count);
        }
    }
    @Override public void callApplicationOnCreate(Application app) {
        try {
            String label=getArgumentsLabel();
            folder=new File(app.getExternalFilesDir(null),"user-failure-snapshots/"+label);
            if(folder.exists()) throw new IllegalStateException("Never overwrite an existing user failure snapshot");
            folder.mkdirs();
            File db=app.getDatabasePath("z-downloads.db");
            for(String suffix:new String[]{"","-wal","-shm"}) copy(new File(db.getPath()+suffix),new File(folder,"database/z-downloads.db"+suffix));
            for(String name:new String[]{"network-diagnostics.jsonl","network-diagnostics.previous.jsonl","datastore/settings.preferences_pb"})
                copy(new File(app.getFilesDir(),name),new File(folder,"files/"+name));
            File[] journals=new File(app.getFilesDir(),"publications").listFiles();
            if(journals!=null) for(File file:journals) if(file.isFile()) copy(file,new File(folder,"publications/"+file.getName()));
            Files.write(new File(folder,"snapshot-scope.json").toPath(),new JSONObject()
                 .put("before_application_on_create",true).put("application_startup_skipped",Boolean.parseBoolean(args.getString("skipAppStartup","false"))).put("original_database_opened_for_writing",false)
                .put("original_jobs_retried_or_removed",false).put("captured_at",System.currentTimeMillis())
                .toString(2).getBytes(StandardCharsets.UTF_8));
        } catch(Throwable error) {snapshotError=error;}
        try {if(!Boolean.parseBoolean(args.getString("skipAppStartup","false"))) super.callApplicationOnCreate(app);} finally {captured.countDown();}
    }
    private String getArgumentsLabel() {
        String value=args==null?"missing":args.getString("evidenceRun","missing");
        if(!value.matches("[a-zA-Z0-9_-]{1,100}")) throw new IllegalArgumentException("Invalid evidence label");
        return value;
    }
    private JSONObject row(Cursor cursor) throws Exception {
        JSONObject object=new JSONObject();
        for(int i=0;i<cursor.getColumnCount();i++) {
            Object value;
            switch(cursor.getType(i)) {
                case Cursor.FIELD_TYPE_NULL:value=JSONObject.NULL;break;
                case Cursor.FIELD_TYPE_INTEGER:value=cursor.getLong(i);break;
                case Cursor.FIELD_TYPE_FLOAT:value=cursor.getDouble(i);break;
                case Cursor.FIELD_TYPE_BLOB:value=android.util.Base64.encodeToString(cursor.getBlob(i),android.util.Base64.NO_WRAP);break;
                default:value=cursor.getString(i);
            }
            object.put(cursor.getColumnName(i),value);
        }
        return object;
    }
    @Override public void onStart() {
        Bundle result=new Bundle();
        try {
            if(!captured.await(30,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Application snapshot never completed");
            if(snapshotError!=null) throw new IllegalStateException("Snapshot capture failed",snapshotError);
            // Query the copy only. Do not invoke Room, queue, storage reconciliation or retry APIs.
            SQLiteDatabase db=SQLiteDatabase.openDatabase(new File(folder,"database/z-downloads.db").getPath(),null,
                SQLiteDatabase.OPEN_READONLY|SQLiteDatabase.NO_LOCALIZED_COLLATORS);
            try {
                JSONArray failed=new JSONArray();JSONArray all=new JSONArray();
                try(Cursor rows=db.rawQuery("SELECT * FROM downloads ORDER BY createdAt DESC",null)) {
                    while(rows.moveToNext()) {
                        JSONObject download=row(rows);JSONArray events=new JSONArray();
                        try(Cursor history=db.rawQuery("SELECT * FROM events WHERE downloadId=? ORDER BY id",new String[]{download.getString("id")})) {
                            while(history.moveToNext()) events.put(row(history));
                        }
                        download.put("events",events);all.put(download);
                        if("FAILED".equals(download.optString("status"))) failed.put(download);
                    }
                }
                Files.write(new File(folder,"failed-jobs.json").toPath(),failed.toString(2).getBytes(StandardCharsets.UTF_8));
                Files.write(new File(folder,"all-jobs.json").toPath(),all.toString(2).getBytes(StandardCharsets.UTF_8));
                JSONArray batches=new JSONArray();
                try(Cursor rows=db.rawQuery("SELECT * FROM playlist_batches ORDER BY createdAt DESC",null)) {while(rows.moveToNext()) batches.put(row(rows));}
                Files.write(new File(folder,"playlist-batches.json").toPath(),batches.toString(2).getBytes(StandardCharsets.UTF_8));
                result.putInt("failed_jobs_captured",failed.length());
            } finally {db.close();}
            result.putString("snapshot",folder.getAbsolutePath());finish(0,result);
        } catch(Throwable error) {result.putString("failure",error.toString());finish(1,result);}
    }
}
