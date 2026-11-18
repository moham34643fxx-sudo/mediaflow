package com.zdownloader.app;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.graphics.Color;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.test.platform.app.InstrumentationRegistry;

/** A standalone test-APK caller; intentionally has no target-app/Kotlin runtime dependency. */
public class ShareOriginActivity extends Activity {
    private Button directButton;
    private boolean autoShared;
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if(getIntent().getBooleanExtra("finishFixture",false)) {finishAndRemoveTask();return;}
        autoShared = savedInstanceState != null && savedInstanceState.getBoolean("autoShared",false);
        String supplied = getIntent().getStringExtra("fixtureUrl");
        final String url = supplied == null ? "https://www.w3schools.com/html/mov_bbb.mp4" : supplied;
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(36,72,36,36);
        layout.setBackgroundColor(Color.rgb(27,54,83));
        TextView title = new TextView(this);
        title.setText("Origin application\nThis screen must remain behind ZD");
        title.setTextSize(24);
        title.setTextColor(Color.WHITE);
        layout.addView(title);
        TextView link = new TextView(this);
        link.setText(url);
        link.setLines(3);
        link.setTextColor(Color.LTGRAY);
        link.setPadding(0,24,0,24);
        layout.addView(link);
        Button direct = new Button(this);
        directButton = direct;
        direct.setText("Share directly to ZD");
        String targetPackage = InstrumentationRegistry.getInstrumentation().getTargetContext().getPackageName();
        direct.setOnClickListener(view -> startActivity(new Intent(Intent.ACTION_SEND)
            .setType("text/plain").putExtra(Intent.EXTRA_TEXT,"Watch this media: " + url)
            .setComponent(new ComponentName(targetPackage,ShareReceiverActivity.class.getName()))));
        layout.addView(direct);
        Button chooser = new Button(this);
        chooser.setText("Open Android share chooser");
        chooser.setOnClickListener(view -> startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND)
            .setType("text/plain").putExtra(Intent.EXTRA_TEXT,"Watch this media: " + url),"Android share verification")));
        layout.addView(chooser);
        setContentView(layout);
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);setIntent(intent);
        if(intent.getBooleanExtra("finishFixture",false)) finishAndRemoveTask();
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if(focused && !autoShared && getIntent().getBooleanExtra("autoShare",false) && directButton != null) {
            autoShared = true;
            directButton.post(() -> directButton.performClick());
        }
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("autoShared",autoShared);
        super.onSaveInstanceState(state);
    }
}
