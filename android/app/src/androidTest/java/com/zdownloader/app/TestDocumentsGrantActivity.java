package com.zdownloader.app;

/** Visible test-only bootstrap; gives the target a real URI grant from the owner. */
public class TestDocumentsGrantActivity extends android.app.Activity {
    @Override public void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        if(!getPackageName().equals("com.zdownloader.app.test")) {finish();return;}
        android.net.Uri tree=android.provider.DocumentsContract.buildTreeDocumentUri(getPackageName()+".documents", "root");
        if(getIntent().getBooleanExtra("auditRevoke", false)) {
            // Only the isolated test provider, never a user's real SAF tree.
            revokeUriPermission("com.zdownloader.app.candidate0965", tree, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION | android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } else {
            grantUriPermission("com.zdownloader.app.candidate0965", tree, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION | android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION | android.content.Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        }
        finish();
    }
}
