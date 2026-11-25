package com.zdownloader.app;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/** Test-APK-owned UI driver. Killing ZD cannot invoke target-instrumentation cleanup. */
public class ExternalDriverInstrumentation extends Instrumentation {
    private Bundle arguments;
    @Override public void onCreate(Bundle args) {super.onCreate(args);arguments=args;start();}
    private String shell(String command) throws Exception {
        try (java.io.InputStream input=new ParcelFileDescriptor.AutoCloseInputStream(getUiAutomation().executeShellCommand(command))) {
            java.io.ByteArrayOutputStream output=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[8192];int count;
            while((count=input.read(buffer))!=-1) output.write(buffer,0,count);
            return new String(output.toByteArray(),StandardCharsets.UTF_8);
        }
    }
    private String targetPackage() {return getTargetContext().getPackageName();}
    private AccessibilityNodeInfo clickable(AccessibilityNodeInfo node) {
        for(int index=0;node!=null&&index<8;index++,node=node.getParent()) if(node.isClickable()) return node;
        return null;
    }
    private void matching(AccessibilityNodeInfo node,String text,List<AccessibilityNodeInfo> result) {
        if(node==null) return;
        if(node.getText()!=null&&node.getText().toString().contains(text)||node.getContentDescription()!=null&&node.getContentDescription().toString().contains(text)) result.add(node);
        for(int index=0;index<node.getChildCount();index++) matching(node.getChild(index),text,result);
    }
    private boolean argumentBoolean(String name,boolean fallback) {
        String value=arguments.getString(name);return value==null?fallback:Boolean.parseBoolean(value);
    }
    private boolean click(String text,boolean bottom) throws Exception {
        AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();
        if(root==null||!targetPackage().contentEquals(root.getPackageName())) return false;
        AccessibilityNodeInfo best=null;int y=-1;
        List<AccessibilityNodeInfo> matches=new ArrayList<>();matching(root,text,matches);
        for(AccessibilityNodeInfo match:matches) {
            if(!bottom) for(AccessibilityNodeInfo item=match;item!=null;item=item.getParent())
                if(item.isCheckable()&&item.isChecked()) return true;
            AccessibilityNodeInfo candidate=clickable(match);if(candidate==null||!candidate.isEnabled()||!candidate.isVisibleToUser()) continue;
            Rect bounds=new Rect();candidate.getBoundsInScreen(bounds);
            if(best==null||bottom&&bounds.centerY()>y) {best=candidate;y=bounds.centerY();}
        }
        if(best==null) return false;
        // A sheet can still be moving after its text first becomes visible.
        // Accessibility actions address the node, not a stale animated position.
        if(best.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        Rect bounds=new Rect();best.getBoundsInScreen(bounds);shell("input tap "+bounds.centerX()+" "+bounds.centerY());return true;
    }
    private boolean selected(String text) {
        AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();
        if(root==null||!targetPackage().contentEquals(root.getPackageName())) return false;
        List<AccessibilityNodeInfo> matches=new ArrayList<>();matching(root,text,matches);
        for(AccessibilityNodeInfo match:matches) for(AccessibilityNodeInfo item=match;item!=null;item=item.getParent())
            if(item.isCheckable()&&item.isChecked()||item.isSelected()) return true;
        return false;
    }
    private AccessibilityNodeInfo scrollable(AccessibilityNodeInfo node) {
        if(node==null) return null;
        if(node.isScrollable()) return node;
        for(int i=0;i<node.getChildCount();i++) {AccessibilityNodeInfo found=scrollable(node.getChild(i));if(found!=null) return found;}
        return null;
    }
    @Override public void onStart() {
        File folder=new File(getTargetContext().getExternalFilesDir(null),"external-driver/"+arguments.getString("evidenceRun","manual"));folder.mkdirs();
        JSONObject report=new JSONObject();
        try {
            if("independent-network-probe".equals(arguments.getString("scenario"))) {
                String before=shell("pidof "+targetPackage()).trim();
                org.json.JSONArray samples=new org.json.JSONArray();
                String[] urls={"https://speed.cloudflare.com/__down?bytes=1048576",
                    "https://storage.googleapis.com/shaka-demo-assets/angel-one/audio_en_2c_128k_aac.mp4"};
                for(String source:urls) {
                    JSONObject sample=new JSONObject().put("host",new java.net.URL(source).getHost());
                    long start=SystemClock.elapsedRealtime(),bytes=0,first=0;
                    java.net.HttpURLConnection connection=(java.net.HttpURLConnection)new java.net.URL(source).openConnection();
                    try {
                        connection.setConnectTimeout(5000);connection.setReadTimeout(5000);
                        connection.setRequestProperty("Accept-Encoding","identity");
                        sample.put("http_status",connection.getResponseCode());
                        if(connection.getResponseCode()!=200) throw new java.io.IOException("HTTP "+connection.getResponseCode());
                        try(java.io.InputStream input=connection.getInputStream()) {
                            byte[] buffer=new byte[65536];int count;
                            while(bytes<1048576&&(count=input.read(buffer,0,(int)Math.min(buffer.length,1048576-bytes)))!=-1) {
                                if(first==0) first=SystemClock.elapsedRealtime();
                                bytes+=count;
                                if(SystemClock.elapsedRealtime()-start>15000) break;
                            }
                        }
                    } catch(Exception error) {sample.put("error",error.getClass().getSimpleName());}
                    finally {connection.disconnect();}
                    long end=SystemClock.elapsedRealtime();
                    samples.put(sample.put("bytes",bytes).put("total_ms",end-start)
                        .put("body_bytes_per_second",first==0?0:bytes*1000/Math.max(1,end-first)));
                }
                String after=shell("pidof "+targetPackage()).trim();
                report.put("target_pid_before",before).put("target_pid_after",after).put("samples",samples)
                    .put("scope","Independent test UID; no target instrumentation, pause, restart, or preference change. Other domains and per-app VPN routing may differ.");
                Files.write(new File(folder,"independent-network-probe.json").toPath(),report.toString(2).getBytes(StandardCharsets.UTF_8));
                boolean measured=false;
                for(int i=0;i<samples.length();i++) if(samples.getJSONObject(i).optLong("bytes")>0) measured=true;
                finish(before.equals(after)&&measured?0:1,new Bundle());return;
            }
            if("autonomous-download".equals(arguments.getString("scenario"))) {
                runAutonomousDownload(folder,report);
                finish(0,new Bundle());return;
            }
            if("external-open-recreation".equals(arguments.getString("scenario"))) {
                runExternalOpenRecreation(folder,report);
                finish(0,new Bundle());return;
            }
            String url=arguments.getString("url");if(url==null||!url.startsWith("https://")) throw new IllegalArgumentException("HTTPS fixture required");
            android.accessibilityservice.AccessibilityServiceInfo info=getUiAutomation().getServiceInfo();
            info.flags|=android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;getUiAutomation().setServiceInfo(info);
            startActivitySync(new Intent(getTargetContext(),ShareOriginActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK).putExtra("fixtureUrl",url).putExtra("autoShare",true));
            long deadline=SystemClock.elapsedRealtime()+120000;
            while(!click("1080p",false)) {if(SystemClock.elapsedRealtime()>deadline) throw new AssertionError("1080p choice unavailable");SystemClock.sleep(100);}
            SystemClock.sleep(300);
            while(!click("تنزيل",true)&&!click("Download",true)) {if(SystemClock.elapsedRealtime()>deadline) throw new AssertionError("Download CTA unavailable");SystemClock.sleep(100);}
            while(true) {
                AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();
                if(root!=null&&"com.zdownloader.app.test".contentEquals(root.getPackageName())) break;
                if(SystemClock.elapsedRealtime()>deadline) throw new AssertionError("Share sheet did not return to caller");SystemClock.sleep(100);
            }
            report.put("state","submitted").put("source",url).put("target_instrumented",false).put("caller_returned",true);
            Files.write(new File(folder,"submitted.json").toPath(),report.toString(2).getBytes(StandardCharsets.UTF_8));
            while(!new File(folder,"finish").exists()) SystemClock.sleep(500);
            finish(0,new Bundle());
        } catch(Throwable error) {
            try {captureForeground(folder,"failure-ui");} catch(Exception ignored) {}
            try {report.put("state","failed").put("error",error.toString());Files.write(new File(folder,"failure.json").toPath(),report.toString(2).getBytes(StandardCharsets.UTF_8));} catch(Exception ignored) {}
            Bundle result=new Bundle();result.putString("failure",error.toString());finish(1,result);
        }
    }
    /** Owns only the external caller. ZD can die and restart under Android's jobs. */
    private void runAutonomousDownload(File folder,JSONObject report) throws Exception {
        String url=arguments.getString("url");
        if(url==null||!url.startsWith("https://")) throw new IllegalArgumentException("HTTPS test URL required");
        long startedSeconds=System.currentTimeMillis()/1000;
        shell("input keyevent KEYCODE_WAKEUP");shell("wm dismiss-keyguard");
        android.accessibilityservice.AccessibilityServiceInfo info=getUiAutomation().getServiceInfo();
        info.flags|=android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        getUiAutomation().setServiceInfo(info);
        startActivitySync(new Intent(getTargetContext(),ShareOriginActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK).putExtra("fixtureUrl",url).putExtra("autoShare",true));
        long deadline=SystemClock.elapsedRealtime()+120000;
        boolean audioOnly=argumentBoolean("audioOnly",false);
        String audioOutput=arguments.getString("audioOutput","original");
        String height=arguments.getString("policyHeight","720");
        String arabicHeight=height.replace('0','٠').replace('1','١').replace('2','٢').replace('3','٣').replace('4','٤').replace('5','٥').replace('6','٦').replace('7','٧').replace('8','٨').replace('9','٩');
        String label="";
        while(true) {
            AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();
            String[] candidates=audioOnly?("mp3".equals(audioOutput)?new String[]{"MP3"}:new String[]{"Original audio","الصوت الأصلي"}):
                new String[]{"Up to "+height+"p","حتى "+height+"p","حتى "+arabicHeight+"p"};
            boolean attempted=false;
            for(String candidate:candidates) {
                if(selected(candidate)) {label=candidate;break;}
                attempted=click(candidate,false)||attempted;
            }
            if(!label.isEmpty()) break;
            AccessibilityNodeInfo list=scrollable(root);if(!attempted&&list!=null) list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
            if(SystemClock.elapsedRealtime()>deadline) throw new AssertionError("Requested quality preference unavailable: "+height);
            SystemClock.sleep(100);
        }
        while(!click("تنزيل",true)&&!click("Download",true)) {
            if(SystemClock.elapsedRealtime()>deadline) throw new AssertionError("Download CTA unavailable");SystemClock.sleep(100);
        }
        while(true) {
            AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();
            if(root!=null&&"com.zdownloader.app.test".contentEquals(root.getPackageName())) break;
            if(SystemClock.elapsedRealtime()>deadline) throw new AssertionError("Request was not durably accepted");SystemClock.sleep(100);
        }
        String beforePid=shell("pidof "+targetPackage()).trim();
        report.put("target_instrumented",false).put("pid_before",beforePid).put("quality_preference",label).put("policy_height",audioOnly?"0":height)
            .put("audio_only",audioOnly).put("audio_output",audioOutput).put("preference_selected",true).put("accepted",true);
        shell("am start -W -n com.zdownloader.app.test/com.zdownloader.app.ShareOriginActivity -f 0x34000000 --ez finishFixture true");
        boolean foreground=argumentBoolean("foreground",false);
        report.put("foreground_requested",foreground).put("main_activity_opened",foreground);
        if(foreground) {
            shell("am start -W -n "+targetPackage()+"/.MainActivity");
            long navigationDeadline=SystemClock.elapsedRealtime()+10000;
            while(!click("التنزيلات",true)&&!click("Downloads",true)) {
                if(SystemClock.elapsedRealtime()>navigationDeadline) break;
                SystemClock.sleep(100);
            }
            captureForeground(folder,"foreground-start");
        }
        // Inject a process crash, never force-stop the package (which Android
        // intentionally excludes from automatic recovery until user launch).
        boolean crash=argumentBoolean("crash",true);
        report.put("process_crash_injected",crash);
        if(crash) report.put("crash_command",shell("am crash --user 0 "+targetPackage()));
        if(!foreground) shell("input keyevent KEYCODE_SLEEP");
        deadline=SystemClock.elapsedRealtime()+Long.parseLong(arguments.getString("observationSeconds","600"))*1000;
        long lastCapture=0;
        String where="(owner_package_name='"+targetPackage()+"')AND(date_added>="+startedSeconds+")AND(is_pending=0)";
        while(true) {
            if(foreground&&SystemClock.elapsedRealtime()-lastCapture>5000) {
                captureForeground(folder,"foreground-latest");lastCapture=SystemClock.elapsedRealtime();
            }
            String collection=audioOnly?"content://media/external/audio/media":"content://media/external/video/media";
            String rows=shell("content query --uri "+collection+" --projection _id:_display_name:_size:is_pending --where "+where);
            java.util.regex.Matcher matcher=java.util.regex.Pattern.compile("_id=(\\d+).*?_size=(\\d+)").matcher(rows);
            if(matcher.find()&&Long.parseLong(matcher.group(2))>0) {
                report.put("published_uri",collection+"/"+matcher.group(1))
                    .put("published_bytes",Long.parseLong(matcher.group(2))).put("publication_query",rows);
                break;
            }
            if(SystemClock.elapsedRealtime()>deadline) throw new AssertionError("No autonomous publication after process crash; last query: "+rows);
            SystemClock.sleep(1000);
        }
        String afterPid=shell("pidof "+targetPackage()).trim();
        if(beforePid.isEmpty()||crash&&beforePid.equals(afterPid)) throw new AssertionError("Distinct restarted target process not verified");
        report.put("pid_after",afterPid).put("manual_resume",false).put("main_activity_opened",foreground)
            .put("screen_interactive_at_publication",getTargetContext().getSystemService(android.os.PowerManager.class).isInteractive())
            .put("state","passed");
        Files.write(new File(folder,"autonomous-process.json").toPath(),report.toString(2).getBytes(StandardCharsets.UTF_8));
    }
    private void captureForeground(File folder,String name) throws Exception {
        android.graphics.Bitmap image=getUiAutomation().takeScreenshot();
        if(image!=null) {
            try(java.io.FileOutputStream output=new java.io.FileOutputStream(new File(folder,name+".png"))) {
                image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output);
            } finally {image.recycle();}
        }
        List<String> texts=new ArrayList<>();foregroundTexts(getUiAutomation().getRootInActiveWindow(),texts);
        Files.write(new File(folder,name+".json").toPath(),new org.json.JSONArray(texts).toString(2).getBytes(StandardCharsets.UTF_8));
    }
    private void foregroundTexts(AccessibilityNodeInfo node,List<String> values) {
        if(node==null) return;
        if(node.getText()!=null) values.add(node.getText().toString());
        if(node.getContentDescription()!=null) values.add(node.getContentDescription().toString());
        for(int index=0;index<node.getChildCount();index++) foregroundTexts(node.getChild(index),values);
    }
    private byte[] wav() {
        int samples=44100*3;ByteBuffer data=ByteBuffer.allocate(44+samples*2).order(ByteOrder.LITTLE_ENDIAN);
        data.put("RIFF".getBytes(StandardCharsets.US_ASCII));data.putInt(samples*2+36);data.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII));
        data.putInt(16);data.putShort((short)1);data.putShort((short)1);data.putInt(44100);data.putInt(88200);data.putShort((short)2);data.putShort((short)16);
        data.put("data".getBytes(StandardCharsets.US_ASCII));data.putInt(samples*2);
        for(int index=0;index<samples;index++) data.putShort((short)(Math.sin(index*2*Math.PI*440/44100)*1800));
        return data.array();
    }
    private Bundle provider(String method,String id,Bundle extras) {
        return getTargetContext().getContentResolver().call(Uri.parse("content://com.zdownloader.app.test.documents"),method,id,extras);
    }
    private boolean playingMediaSession(String uri) {
        getUiAutomation().adoptShellPermissionIdentity("android.permission.MEDIA_CONTENT_CONTROL");
        try {
            MediaSessionManager manager=getTargetContext().getSystemService(MediaSessionManager.class);
            for(MediaController controller:manager.getActiveSessions(null)) {
                if(!targetPackage().equals(controller.getPackageName())) continue;
                MediaMetadata metadata=controller.getMetadata();
                if(metadata==null) continue;
                String mediaId=metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
                String title=metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
                android.media.session.PlaybackState state=controller.getPlaybackState();
                if((uri.equals(mediaId)||title!=null&&title.startsWith("recreation-"))&&state!=null&&state.getState()==android.media.session.PlaybackState.STATE_PLAYING) return true;
            }
            return false;
        } finally {getUiAutomation().dropShellPermissionIdentity();}
    }
    private void runExternalOpenRecreation(File folder,JSONObject report) throws Exception {
        Uri tree=DocumentsContract.buildTreeDocumentUri("com.zdownloader.app.test.documents","root");
        Uri root=DocumentsContract.buildDocumentUriUsingTree(tree,"root");
        Uri audio=DocumentsContract.createDocument(getTargetContext().getContentResolver(),root,"audio/wav","recreation-"+SystemClock.elapsedRealtime()+".wav");
        if(audio==null) throw new IllegalStateException("Test WAV document creation failed");
        String id=DocumentsContract.getDocumentId(audio);
        try {
            try(java.io.OutputStream output=getTargetContext().getContentResolver().openOutputStream(audio)) {output.write(wav());}
            boolean committedBeforeKill=argumentBoolean("commitBeforeKill",false);
            Bundle delay=new Bundle();delay.putLong("millis",committedBeforeKill?750:6000);provider("delayQuery",id,delay);
            Intent view=new Intent(Intent.ACTION_VIEW).setComponent(new android.content.ComponentName(targetPackage(),"com.zdownloader.app.playback.PlayerActivity"))
                .setDataAndType(audio,"audio/wav").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_GRANT_READ_URI_PERMISSION);
            view.setClipData(android.content.ClipData.newRawUri("Audio recovery fixture",audio));
            getTargetContext().startActivity(view);
            long deadline=SystemClock.elapsedRealtime()+10000;
            while(!provider("queryState",id,null).getBoolean("started")&&SystemClock.elapsedRealtime()<deadline) SystemClock.sleep(50);
            if(!provider("queryState",id,null).getBoolean("started")) throw new AssertionError("External open never reached delayed provider query");
            boolean sessionWasPlaying=false;
            if(committedBeforeKill) {
                deadline=SystemClock.elapsedRealtime()+12000;
                while(!provider("queryState",id,null).getBoolean("finished")&&SystemClock.elapsedRealtime()<deadline) SystemClock.sleep(50);
                if(!provider("queryState",id,null).getBoolean("finished")) throw new AssertionError("Delayed provider resolution did not finish before commit check");
                deadline=SystemClock.elapsedRealtime()+12000;
                while(SystemClock.elapsedRealtime()<deadline) {
                    if(playingMediaSession(audio.toString())) {sessionWasPlaying=true;break;}
                    SystemClock.sleep(100);
                }
                if(!sessionWasPlaying) throw new AssertionError("External request did not commit to an active MediaSession before process death");
            }
            String pidBefore=shell("pidof "+targetPackage()).trim();
            shell("input keyevent KEYCODE_HOME");SystemClock.sleep(300);shell("am crash "+targetPackage());
            deadline=SystemClock.elapsedRealtime()+5000;
            while(!shell("pidof "+targetPackage()).trim().isEmpty()&&SystemClock.elapsedRealtime()<deadline) SystemClock.sleep(50);
            String pidAfterKill=shell("pidof "+targetPackage()).trim();
            if(pidBefore.isEmpty()||!pidAfterKill.isEmpty()) throw new AssertionError("Target process was not confirmed dead before recreation: "+pidBefore+" -> "+pidAfterKill);
            delay.putLong("millis",3000);provider("delayQuery",id,delay);
            Intent resume=new Intent().setComponent(new android.content.ComponentName(targetPackage(),"com.zdownloader.app.playback.PlayerActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getTargetContext().startActivity(resume);
            deadline=SystemClock.elapsedRealtime()+8000;
            while(shell("pidof "+targetPackage()).trim().isEmpty()&&SystemClock.elapsedRealtime()<deadline) SystemClock.sleep(100);
            String pidAfterRestore=shell("pidof "+targetPackage()).trim();
            deadline=SystemClock.elapsedRealtime()+5000;boolean replayed=false;
            while(SystemClock.elapsedRealtime()<deadline) {
                Bundle state=provider("queryState",id,null);
                if(state.getBoolean("started")) {replayed=true;break;}
                SystemClock.sleep(50);
            }
            // Restoring the already committed queue legitimately re-queries metadata.
            // That query is not evidence of replaying ACTION_VIEW. A replay would also
            // issue prepare/play, whereas queue restoration stays paused.
            boolean playingAfterRestore=false;
            if(committedBeforeKill) {
                long playbackDeadline=SystemClock.elapsedRealtime()+5000;
                while(SystemClock.elapsedRealtime()<playbackDeadline) {
                    if(playingMediaSession(audio.toString())) {playingAfterRestore=true;break;}
                    SystemClock.sleep(50);
                }
            }
            boolean requestReplayed=committedBeforeKill?playingAfterRestore:replayed;
            report.put("scenario","external-open-process-recreation").put("uri",audio.toString())
                .put("first_resolution_started",true).put("process_pid_before",pidBefore).put("process_pid_after_kill",pidAfterKill)
                .put("process_kill_confirmed",true).put("commit_before_process_death",committedBeforeKill)
                .put("session_playing_before_process_death",sessionWasPlaying).put("resume_activity_requested",true)
                .put("pid_after_restore",pidAfterRestore).put("metadata_queried_after_restore",replayed)
                .put("session_playing_after_restore",playingAfterRestore).put("external_request_replayed",requestReplayed)
                .put("expected_replay",argumentBoolean("expectReplay",false));
            Files.write(new File(folder,"external-open-recreation.json").toPath(),report.toString(2).getBytes(StandardCharsets.UTF_8));
            if(requestReplayed!=argumentBoolean("expectReplay",false)) throw new AssertionError("Unexpected replay state: "+report);
        } finally {
            try {provider("clearQuery",id,null);} catch(Exception ignored) {}
            try {shell("am force-stop "+targetPackage());} catch(Exception ignored) {}
            try {DocumentsContract.deleteDocument(getTargetContext().getContentResolver(),audio);} catch(Exception ignored) {}
        }
    }
}
