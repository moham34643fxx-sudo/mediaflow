package com.zdownloader.app;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.content.UriPermission;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.FixMethodOrder;
import org.junit.runners.MethodSorters;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.Assert.*;

/** No app or Kotlin references: can inspect the old, differently R8-mapped APK. */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class UiUpgradePreservationTest {
    private Context context() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }
    private File snapshot() { return new File(context().getFilesDir(),"ui-upgrade-preservation.json"); }
    private byte[] read(File file) throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(FileInputStream input=new FileInputStream(file)) {byte[] buffer=new byte[8192];int count;while((count=input.read(buffer))!=-1) bytes.write(buffer,0,count);}
        return bytes.toByteArray();
    }
    private String digest(byte[] bytes) throws Exception {
        StringBuilder result=new StringBuilder();
        for(byte value:MessageDigest.getInstance("SHA-256").digest(bytes)) result.append(String.format(Locale.ROOT,"%02x",value));
        return result.toString();
    }
    private void partials(File folder, JSONArray rows) throws Exception {
        File[] files=folder.listFiles();if(files==null) return;Arrays.sort(files);
        for(File file:files) {
            if(file.isDirectory()) partials(file,rows);
            else if(file.getName().endsWith(".part")) rows.put(new JSONObject().put("path",file.getAbsolutePath()).put("sha256",digest(read(file))));
        }
    }
    private JSONObject state() throws Exception {
        JSONArray rows=new JSONArray();File path=context().getDatabasePath("z-downloads.db");
        if(path.exists()) try(SQLiteDatabase db=SQLiteDatabase.openDatabase(path.getPath(),null,SQLiteDatabase.OPEN_READONLY);
            Cursor cursor=db.rawQuery("SELECT id,url,title,selection,filename,destination,uri,status,createdAt,outputSize FROM downloads ORDER BY id",null)) {
            while(cursor.moveToNext()) {
                StringBuilder value=new StringBuilder();
                for(int i=0;i<cursor.getColumnCount();i++) value.append(cursor.getString(i)).append('|');
                rows.put(digest(value.toString().getBytes("UTF-8")));
            }
        }
        File prefs=new File(context().getFilesDir(),"datastore/settings.preferences_pb");
        JSONArray partials=new JSONArray();partials(new File(context().getFilesDir(),"transfers"),partials);
        ArrayList<String> permissions=new ArrayList<>();
        for(UriPermission permission:context().getContentResolver().getPersistedUriPermissions()) permissions.add(permission.getUri()+"|"+permission.isReadPermission()+"|"+permission.isWritePermission());
        Collections.sort(permissions);
        return new JSONObject().put("rows",rows).put("preferences_sha256",prefs.isFile()?digest(read(prefs)):"absent")
            .put("partials",partials).put("saf_permissions",new JSONArray(permissions));
    }
    @Test public void captureBeforeUiUpdate() throws Exception {
        snapshot().delete();try(FileOutputStream output=new FileOutputStream(snapshot())) {output.write(state().toString().getBytes("UTF-8"));}
    }
    @Test public void verifyAfterUiUpdate() throws Exception {
        JSONObject before=new JSONObject(new String(read(snapshot()),"UTF-8")),after=state();
        for(String key:new String[]{"rows","preferences_sha256","partials","saf_permissions"}) assertEquals(key,before.get(key).toString(),after.get(key).toString());
        String run=InstrumentationRegistry.getArguments().getString("evidenceRun","manual");
        File folder=new File(context().getExternalFilesDir(null),"advanced-audit/"+run);folder.mkdirs();
        try(FileOutputStream output=new FileOutputStream(new File(folder,"ui-upgrade-preservation.json"))) {output.write(after.put("preserved",true).toString(2).getBytes("UTF-8"));}
        assertTrue(snapshot().delete());
    }
}
