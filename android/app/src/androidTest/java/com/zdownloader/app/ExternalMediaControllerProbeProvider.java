package com.zdownloader.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import androidx.annotation.Nullable;

/** Separate test-APK UID simulates an ordinary wearable/companion MediaController client. */
public final class ExternalMediaControllerProbeProvider extends ContentProvider {
    private MediaController controller;

    @Override public boolean onCreate() {return true;}

    @Override public synchronized Bundle call(String method,String arg,@Nullable Bundle extras) {
        switch(method) {
            case "uid":
                Bundle uid=new Bundle();
                uid.putInt("uid",android.os.Process.myUid());
                return uid;
            case "connect":
                Bundle connected=new Bundle();
                connected.putBoolean("connected",connect(extras));
                return connected;
            case "pause":
                requireController().getTransportControls().pause();
                return new Bundle();
            case "play":
                requireController().getTransportControls().play();
                return new Bundle();
            case "release":
                releaseController();
                return new Bundle();
            case "modernPermissions":
                return modernPermissions(extras);
            default:
                throw new IllegalArgumentException("Unsupported probe command: "+method);
        }
    }
    private Bundle modernPermissions(Bundle extras) {
        String target=extras.getString("package");
        if(!"com.zdownloader.app.candidate0965".equals(target)) throw new SecurityException("Candidate only");
        try {
            // AGP omits target-provided dependencies from the test APK. Read only
            // installed APK code; execution still has this separate test UID.
            String targetApk=getContext().getPackageManager().getApplicationInfo(target,0).sourceDir;
            String testApk=getContext().getApplicationInfo().sourceDir;
            ClassLoader loader=new dalvik.system.PathClassLoader(
                targetApk+java.io.File.pathSeparator+testApk,ClassLoader.getSystemClassLoader());
            Class<?> probe=Class.forName("com.zdownloader.app.ExternalMedia3ControllerProbe",true,loader);
            return (Bundle)probe.getMethod("run",android.content.Context.class,String.class).invoke(null,getContext(),target);
        } catch(ReflectiveOperationException|android.content.pm.PackageManager.NameNotFoundException error) {
            throw new IllegalStateException("Controller permission probe failed",error);
        }
    }

    private MediaController requireController() {
        if(controller==null) throw new IllegalStateException("MediaController is not connected");
        return controller;
    }

    @SuppressWarnings("deprecation")
    private boolean connect(Bundle extras) {
        if(controller!=null) return true;
        MediaSession.Token token=extras==null?null:extras.getParcelable("session_token");
        if(token==null) throw new IllegalArgumentException("Missing platform media-session token");
        android.content.Context context=getContext();
        if(context==null) throw new IllegalStateException("Provider context is unavailable");
        controller=new MediaController(context,token);
        return true;
    }

    private void releaseController() {
        controller=null;
    }

    @Nullable @Override public Cursor query(Uri uri,String[] projection,String selection,String[] selectionArgs,String sortOrder) {
        throw new UnsupportedOperationException("Query is not supported");
    }
    @Nullable @Override public String getType(Uri uri) {return null;}
    @Nullable @Override public Uri insert(Uri uri,ContentValues values) {throw new UnsupportedOperationException("Insert is not supported");}
    @Override public int delete(Uri uri,String selection,String[] selectionArgs) {throw new UnsupportedOperationException("Delete is not supported");}
    @Override public int update(Uri uri,ContentValues values,String selection,String[] selectionArgs) {throw new UnsupportedOperationException("Update is not supported");}
    @Override public synchronized void shutdown() {releaseController();super.shutdown();}
}
