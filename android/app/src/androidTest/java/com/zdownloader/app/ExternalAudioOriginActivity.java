package com.zdownloader.app;

import android.app.Activity;
import android.content.*;
import android.net.Uri;
import android.os.Bundle;
import java.io.*;

/** A separate application's file-manager-style ACTION_VIEW sender. */
public class ExternalAudioOriginActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        String targetPackage=getIntent().getStringExtra("targetPackage");
        if(targetPackage==null||targetPackage.isEmpty()){finish();return;}
        if(getIntent().getBooleanExtra("invalidRetention",false)) {
            startService(new Intent().setComponent(new ComponentName(targetPackage,"com.zdownloader.app.playback.MediaPlaybackService"))
                .setAction("com.zdownloader.app.playback.RETAIN_URI"));
            finish();return;
        }
        String name=getIntent().getStringExtra("name");
        if(name==null||!name.matches("[A-Za-z0-9._-]+")){finish();return;}
        Uri uri=Uri.parse("content://"+getPackageName()+".external-audio/"+name);
        if(getIntent().getBooleanExtra("revoke",false)){revokeUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);finish();return;}
        byte[] bytes=getIntent().getByteArrayExtra("bytes");
        if(bytes!=null)try {
            File directory=new File(getCacheDir(),"external-audio");directory.mkdirs();
            try(FileOutputStream out=new FileOutputStream(new File(directory,name))){out.write(bytes);}
        }catch(IOException error){throw new RuntimeException(error);}
        else if(getIntent().getData()!=null)try {
            Uri source=getIntent().getData();
            if(!"content".equals(source.getScheme()))throw new IllegalArgumentException("Fixture requires a granted content URI");
            File directory=new File(getCacheDir(),"external-audio");directory.mkdirs();
            try(InputStream input=getContentResolver().openInputStream(source);
                FileOutputStream out=new FileOutputStream(new File(directory,name))) {
                if(input==null)throw new FileNotFoundException("Fixture source unavailable");
                byte[] buffer=new byte[8192];int count;
                while((count=input.read(buffer))!=-1)out.write(buffer,0,count);
            }
        }catch(IOException error){throw new RuntimeException(error);}
        if(getIntent().getBooleanExtra("prepareOnly",false)){
            sendBroadcast(new Intent(targetPackage+".TEST_AUDIO_READY").setPackage(targetPackage).putExtra("name",name));
            finish();return;
        }
        Intent view=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,getIntent().getStringExtra("mime"))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        view.setClipData(ClipData.newRawUri("Audio fixture",uri));
        if(getIntent().getBooleanExtra("chooser",false))startActivity(Intent.createChooser(view,"Open audio with"));
        else {view.setPackage(targetPackage);startActivity(view);}
        finish();
    }
}
