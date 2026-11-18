package com.zdownloader.app

import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.data.SettingsRepository
import com.zdownloader.app.status.*
import org.json.JSONObject
import org.junit.Test
import java.io.File

/** Inspect grant state only; never open or enumerate actual status content. */
class StatusGrantAuditTest {
    @Test fun recordExistingStatusPermissionWithoutReadingPersonalMedia() {
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val permissions=StatusPermissions(app,SettingsRepository(app))
        val configured=permissions.configured(StatusSource.WHATSAPP)
        val granted=app.contentResolver.persistedUriPermissions.count {grant->
            grant.isReadPermission&&grant.uri.authority=="com.android.externalstorage.documents"&&runCatching {
                StatusRules.containsDocument(DocumentsContract.getTreeDocumentId(grant.uri),"primary:Android/media/com.whatsapp/WhatsApp/Media/.Statuses")
            }.getOrDefault(false)
        }
        val out=File(app.getExternalFilesDir(null),"advanced-audit/${InstrumentationRegistry.getArguments().getString("evidenceRun","manual")}").apply {mkdirs()}
        File(out,"status-grant-preservation.json").writeText(JSONObject().put("configured",configured!=null)
            .put("configured_uri_readable",configured?.let(permissions::readable) ?: false).put("suitable_persisted_read_grants",granted)
            .put("personal_media_read",false).toString(2))
    }
}
