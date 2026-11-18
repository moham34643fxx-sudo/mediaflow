package com.zdownloader.app

import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.data.*
import com.zdownloader.app.domain.*
import com.zdownloader.app.engine.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Explicit before/after install -r pair; excluded from ordinary suites. */
class UpgradeContractTest {
    private val app get()=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZApplication
    private val snapshot get()=File(app.filesDir,"upgrade-contract-${InstrumentationRegistry.getArguments().getString("evidenceRun","manual")}.json")
    private val syntheticFixtureId=Regex("""upgrade-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}(-(PAUSED|QUEUED))?""")
    private fun digest(value:ByteArray)=java.security.MessageDigest.getInstance("SHA-256").digest(value).joinToString("") {"%02x".format(it)}
    private suspend fun cleanupStaleSyntheticFixtures() {
        // A previously interrupted QA run may leave its explicitly synthetic
        // COMPLETED/QUEUED/PAUSED rows. The reserved example.com URL and full UUID
        // ID are checked before removing the fixture so real downloads are never swept.
        val stale=app.repository.dao.observe().first().filter {it.url=="https://example.com/upgrade"&&syntheticFixtureId.matches(it.id)}
        for(row in stale) {
            if(row.status=="COMPLETED") {
                app.storage.deleteFile(row)
                app.storage.removeTemporary(row.id)
                continue
            }
            app.repository.cancel(row.id)
            app.coordinator.stopAndAwait(row.id)
            app.storage.cleanupPending(row.id)
            app.storage.removeTemporary(row.id)
            app.repository.dao.removeHistory(row.id)
        }
    }
    private suspend fun state():JSONObject {
        val rows=app.repository.dao.observe().first()
        val records=JSONArray()
        rows.sortedBy {it.id}.forEach {row->
            records.put(JSONObject().put("id",row.id).put("durable",digest(listOf(row.url,row.title,row.selection,row.filename,row.destination,row.uri,row.status,row.createdAt,row.outputSize).joinToString("|").toByteArray())))
        }
        val partials=JSONArray()
        File(app.filesDir,"transfers").walkTopDown().filter {it.isFile && it.name.endsWith(".part")}.sortedBy {it.path}.forEach {partials.put(JSONObject().put("path",it.relativeTo(app.filesDir).path).put("hash",digest(it.readBytes())))}
        val permissions=app.contentResolver.persistedUriPermissions.map {"${it.uri}|${it.isReadPermission}|${it.isWritePermission}"}.sorted()
        val preferences=File(app.filesDir,"datastore/settings.preferences_pb")
        return JSONObject().put("rows",records).put("settings",SettingsRepository(app).current().toString()).put("permissions",JSONArray(permissions)).put("partials",partials)
            .put("settings_file_sha256",if(preferences.isFile) digest(preferences.readBytes()) else "absent")
    }
    @Test fun captureBeforeUpdate()=runBlocking {
        cleanupStaleSyntheticFixtures()
        // A persisted update job may briefly run the idle queue after reinstall.
        // Wait for quiescence instead of racing it; a genuinely busy queue still fails.
        withTimeoutOrNull(60000) { while(app.coordinator.running) delay(250) }
        assertFalse("Do not update while a user transfer is active",app.coordinator.running)
        assertFalse("A previous upgrade snapshot must be verified first",snapshot.exists())
        val ids=JSONArray()
        val id="upgrade-${java.util.UUID.randomUUID()}"
        val dir=app.storage.temporary(id)
        val wav=File(dir,"source.wav")
        val size=44100*2
        val header=java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {put("RIFF".toByteArray());putInt(size+36);put("WAVEfmt ".toByteArray());putInt(16);putShort(1);putShort(1);putInt(44100);putInt(88200);putShort(2);putShort(16);put("data".toByteArray());putInt(size)}.array()
        wav.writeBytes(header+ByteArray(size))
        val format=Format("fixture","wav",null,null,"none","pcm_s16le",wav.length(),null,"https","{}")
        val selection=Selection(format,audioOnly=true,output="m4a",convertAudio=true,duration=1.0)
        val output=File(dir,"saved.m4a")
        Processor().process(wav,null,output,selection,TransferControl(),1.0,"ترقية محفوظة")
        val completed=DownloadEntity(id,"https://example.com/upgrade","ترقية محفوظة","Upgrade test",null,1.0,selection.json(),"upgrade-${id}.m4a",null,id)
        // A queued synthetic row in the live DAO can be claimed by an already
        // scheduled RecoveryWorker between insert/claim/commit. Publish against
        // an isolated database, then insert only the final completed snapshot.
        val isolated=androidx.room.Room.inMemoryDatabaseBuilder(app,ZDatabase::class.java).build()
        try {
            val dao=isolated.downloads()
            dao.insert(completed);dao.claim(id);dao.processing(id)
            StorageRepository(app,dao).commit(completed,output,selection,TransferControl())
            app.repository.dao.insert(dao.get(id)!!.copy(notificationPending=false));ids.put(id)
        } finally {isolated.close()}
        for(status in listOf("PAUSED","QUEUED")) {
            val fixture="$id-$status";ids.put(fixture)
            // An update snapshot is not executable test work. A live persistent
            // recovery worker must not claim this deliberately offline fixture.
            app.repository.dao.insert(completed.copy(id=fixture,fingerprint=fixture,status=status,uri=null,nextAttempt=Long.MAX_VALUE))
            if(status=="PAUSED") File(app.storage.temporary(fixture),"primary.m4a.part").writeBytes(output.readBytes().let {it.copyOf(it.size/2)})
        }
        val value=state().put("fixtures",ids)
        snapshot.writeText(value.toString())
    }
    @Test fun verifyAfterUpdate()=runBlocking {
        val before=JSONObject(snapshot.readText())
        val after=state()
        for(key in listOf("rows","permissions","partials")) assertEquals("Upgrade changed $key",before.get(key).toString(),after.get(key).toString())
        if(before.has("settings_file_sha256")) assertEquals("Stored preference bytes changed",before.getString("settings_file_sha256"),after.getString("settings_file_sha256"))
        val beforeSettings=before.getString("settings")
        val afterSettings=after.getString("settings")
        val preferences=File(app.filesDir,"datastore/settings.preferences_pb")
        val noStoredTemplate=!preferences.isFile || !preferences.readText(Charsets.UTF_8).contains("template")
        val defaultBrandOnly=noStoredTemplate && beforeSettings.replace("template={title} - Z Downloader,","template={title} - MediaFlow,")
            .replace("template={title} - Zad Downloader,","template={title} - MediaFlow,")==afterSettings
        assertTrue("Upgrade changed user settings",beforeSettings==afterSettings || defaultBrandOnly)
        val fixtures=before.getJSONArray("fixtures")
        for(i in 0 until fixtures.length()) {
            val id=fixtures.getString(i);val row=app.repository.dao.get(id)!!
            if(row.status=="COMPLETED") app.storage.deleteFile(row)
            else {app.repository.cancel(id);app.coordinator.stopAndAwait(id);app.storage.cleanupPending(id)}
            app.storage.removeTemporary(id)
            app.repository.dao.removeHistory(id)
        }
        File(app.getExternalFilesDir(null),"upgrade-preservation.json").writeText(JSONObject().put("preserved",true).put("unsetDefaultTemplateRebranded",beforeSettings!=afterSettings&&defaultBrandOnly).put("existingAndFixtureRows",before.getJSONArray("rows").length()).put("persistedSafPermissions",before.getJSONArray("permissions").length()).put("partialFiles",before.getJSONArray("partials").length()).toString(2))
        assertTrue("Remove only this run's completed upgrade fixture",snapshot.delete()||!snapshot.exists())
        Unit
    }
}
