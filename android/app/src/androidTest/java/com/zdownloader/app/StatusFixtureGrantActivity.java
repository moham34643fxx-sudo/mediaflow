package com.zdownloader.app;

/** Test owner grants/revokes only its own synthetic document trees. */
public class StatusFixtureGrantActivity extends android.app.Activity {
    @Override public void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        String targetPackage=getIntent().getStringExtra("targetPackage");
        if(targetPackage==null||targetPackage.isEmpty()){finish();return;}
        for(String id:new String[]{StatusFixtureProvider.WA,StatusFixtureProvider.WB,StatusFixtureProvider.WRONG}) {
            android.net.Uri tree=android.provider.DocumentsContract.buildTreeDocumentUri(StatusFixtureProvider.authority(this),id);
            if(getIntent().getBooleanExtra("revoke",false)) revokeUriPermission(targetPackage,tree,3);
            else grantUriPermission(targetPackage,tree,android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION|android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION|android.content.Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        }
        finish();
    }
}
