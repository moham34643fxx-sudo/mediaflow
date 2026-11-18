package com.zdownloader.app;

/** Test owner grants/revokes only its own synthetic document trees. */
public class StatusFixtureGrantActivity extends android.app.Activity {
    @Override public void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        for(String id:new String[]{StatusFixtureProvider.WA,StatusFixtureProvider.WB,StatusFixtureProvider.WRONG}) {
            android.net.Uri tree=android.provider.DocumentsContract.buildTreeDocumentUri(StatusFixtureProvider.AUTH,id);
            if(getIntent().getBooleanExtra("revoke",false)) revokeUriPermission("com.zdownloader.app",tree,3);
            else grantUriPermission("com.zdownloader.app",tree,android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION|android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION|android.content.Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        }
        finish();
    }
}
