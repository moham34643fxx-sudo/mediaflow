package com.zdownloader.app;

import android.content.ComponentName;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionCommand;
import androidx.media3.session.SessionResult;
import androidx.media3.session.SessionToken;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Loaded with the installed target's libraries, never its UID or private data. */
public final class ExternalMedia3ControllerProbe {
    public static Bundle run(Context context,String target) {
        if(!"com.zdownloader.app.candidate0965".equals(target)) throw new SecurityException("Candidate only");
        Handler main=new Handler(Looper.getMainLooper());
        CompletableFuture<Bundle> response=new CompletableFuture<>();
        main.post(()->{
            SessionToken token=new SessionToken(context,new ComponentName(target,"com.zdownloader.app.playback.MediaPlaybackService"));
            ListenableFuture<MediaController> pending=new MediaController.Builder(context,token).buildAsync();
            pending.addListener(()->{
                try {checkPermissions(pending.get(),response,main);}
                catch(Exception error) {response.completeExceptionally(error);}
            },main::post);
        });
        try {return response.get(15,TimeUnit.SECONDS);}
        catch(Exception error) {throw new IllegalStateException("Controller permission probe failed",error);}
    }

    private static void checkPermissions(MediaController player,CompletableFuture<Bundle> response,Handler main) {
        Bundle report=new Bundle();
        report.putInt("uid",android.os.Process.myUid());
        for(int command:new int[]{Player.COMMAND_PLAY_PAUSE,Player.COMMAND_GET_METADATA,
            Player.COMMAND_SET_PLAYLIST_METADATA,Player.COMMAND_SET_MEDIA_ITEM,Player.COMMAND_CHANGE_MEDIA_ITEMS,
            Player.COMMAND_SET_VOLUME,Player.COMMAND_RELEASE})
            report.putBoolean("command_"+command,player.isCommandAvailable(command));
        ListenableFuture<SessionResult> custom=player.sendCustomCommand(
            new SessionCommand("mediaflow.playback.stop_audio",Bundle.EMPTY),Bundle.EMPTY);
        custom.addListener(()->{
            try {
                report.putInt("custom_result",custom.get().resultCode);
                player.release();
                report.putBoolean("released_connection",!player.isConnected());
                response.complete(report);
            } catch(Exception error) {
                player.release();
                response.completeExceptionally(error);
            }
        },main::post);
    }
}
