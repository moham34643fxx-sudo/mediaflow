package com.zdownloader.app

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.domain.UrlPolicy
import com.zdownloader.app.domain.Selection
import com.zdownloader.app.domain.originalImage
import com.zdownloader.app.domain.videoOptions
import com.zdownloader.app.domain.DownloadPlan
import com.zdownloader.app.data.SettingsRepository
import com.zdownloader.app.engine.Transfer
import com.zdownloader.app.engine.TransferControl
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Reported real post through transfer, queue, image validation and publication. */
class ReportedInstagramLinkTest {
    @Test fun reportedPostMetadataHasOriginalImagesWithSanitizedFailureFacts()=runBlocking<Unit> {
        val instrument=InstrumentationRegistry.getInstrumentation()
        val app=instrument.targetContext.applicationContext as ZApplication
        check(app.repository.dao.observe().first().none {it.status !in setOf("COMPLETED","FAILED","CANCELLED","PAUSED")})
        val python=app.extractor.python()
        val fixture=python.getModule("types").callAttr("ModuleType","reported_instagram_diagnostic")
        fixture.put("request_id","reported-metadata-${UUID.randomUUID()}")
        python.builtins.callAttr("exec","""
import json, os, traceback
from urllib.parse import urlsplit
import zextractor as z, instagram_media as images
from network_errors import exception_facts
original_logger, original_classify = z.QuietLogger, z.classify
original_images, original_configure = images.image_formats, images.configure
messages, exceptions, frames, shapes, adapters = set(), [], [], [], []
def inspect_images(product, validate, dimensions=None):
    versions = product.get('image_versions2')
    candidates = versions.get('candidates') if isinstance(versions, dict) else None
    shapes.append({'media_type': product.get('media_type'), 'typename': product.get('__typename'),
        'has_image_versions2': isinstance(versions, dict),
        'candidate_count': len(candidates) if isinstance(candidates, list) else None,
        'candidate_fields': sorted(candidates[0]) if isinstance(candidates, list) and candidates and isinstance(candidates[0], dict) else [],
        'valid_dimensions': sum(images._dimension(candidate.get('width')) is not None and images._dimension(candidate.get('height')) is not None
            for candidate in candidates if isinstance(candidate, dict)) if isinstance(candidates, list) else 0,
        'extension_kinds': sorted({extension if extension in ('jpg', 'jpeg', 'png', 'webp', 'avif', 'heic', 'gif') else 'other'
            for candidate in candidates if isinstance(candidate, dict) and isinstance(candidate.get('url'), str)
            for extension in [urlsplit(candidate['url']).path.rsplit('.', 1)[-1].lower()]}) if isinstance(candidates, list) else []})
    formats = original_images(product, validate, dimensions) if dimensions is not None else original_images(product, validate)
    shapes[-1]['formats_returned'] = len(formats)
    return formats
def configure(engine, validate):
    original_configure(engine, validate)
    adapter = engine.get_info_extractor('Instagram')
    adapters.append({'module': type(adapter).__module__, 'class': type(adapter).__name__,
        'mro': [parent.__module__ + '.' + parent.__name__ for parent in type(adapter).__mro__],
        'image_hook_names': list(adapter._extract_product_media.__code__.co_names)})
def retain(message):
    for needle, code in (
        ('LSD token', 'LSD_TOKEN'), ('no video in this post', 'NO_VIDEO'),
        ('empty media response', 'EMPTY_MEDIA'), ('Requested format is not available', 'FORMAT_SELECTOR'),
        ('Only images are available', 'IMAGES_ONLY'), ('login', 'LOGIN'),
        ('HTTP Error 403', 'HTTP_403'), ('HTTP Error 429', 'HTTP_429')):
        if needle.lower() in str(message).lower(): messages.add(code)
class DiagnosticLogger(original_logger):
    def warning(self, message):
        retain(message)
        super().warning(message)
    def error(self, message):
        retain(message)
        super().error(message)
def classify(error):
    exceptions.extend(exception_facts(error))
    frames.extend({'file': os.path.basename(frame.filename), 'line': frame.lineno, 'function': frame.name}
        for frame in traceback.extract_tb(error.__traceback__)[-10:])
    return original_classify(error)
try:
    z.QuietLogger, z.classify = DiagnosticLogger, classify
    images.image_formats, images.configure = inspect_images, configure
    metadata = json.loads(z.analyze('https://www.instagram.com/p/De4f1OAou7a/', request_id))
    report = {key: metadata.get(key) for key in ('error', 'stage', 'diagnostic', 'timings_ms')}
    report.update(metadata_only=True, formats=len(metadata.get('formats') or []),
        original_images=sum(entry.get('mediaflow_image') == 'original' for entry in metadata.get('formats') or []),
        message_codes=sorted(messages), exception_facts=exceptions, frames=frames, media_shapes=shapes, adapters=adapters)
finally:
    z.QuietLogger, z.classify = original_logger, original_classify
    images.image_formats, images.configure = original_images, original_configure
""".trimIndent(),fixture.get("__dict__"))
        val report=JSONObject(python.getModule("json").callAttr("dumps",fixture.get("report")).toString())
        val home=File(app.filesDir,"reported-instagram/${UUID.randomUUID()}").apply {check(mkdirs())}
        val output=File(home,"metadata-diagnostic.json");output.writeText(report.toString(2))
        instrument.sendStatus(2,Bundle().apply {putString("reported_metadata_report",output.path)})
        assertTrue("Reported original-image metadata failed; sanitized evidence retained",report.isNull("error")&&report.getInt("original_images")>0)
    }

    @Test fun reportedPhotoPassesTheRealQueueWithOriginalByteIdentity()=runBlocking<Unit> {
        val instrument=InstrumentationRegistry.getInstrumentation()
        val app=instrument.targetContext.applicationContext as ZApplication
        val url="https://www.instagram.com/p/De4f1OAou7a/?exln=MTI4Y2d2cGhqdXd4eA=="
        val home=File(app.filesDir,"reported-instagram/${UUID.randomUUID()}").apply {check(!exists()&&mkdirs())}
        val report=JSONObject().put("passed",false).put("metadata_only",false).put("download_submitted",false)
            .put("url_shape_accepted",UrlPolicy.normalize(url).startsWith("https://www.instagram.com/p/De4f1OAou7a/"))
        check(app.repository.dao.observe().first().none {it.status !in setOf("COMPLETED","FAILED","CANCELLED","PAUSED")}) {"Do not disturb unrelated work"}
        val settings=SettingsRepository(app);val old=settings.current()
        var id:String?=null
        var failure:Throwable?=null
        try {
            val media=withTimeout(90_000) {app.extractor.analyze(url,forceExtractor=true,enrichOptional=false)}
            assertTrue("The reported post must remain a photo, not a fabricated video",media.videoOptions().isEmpty())
            val original=checkNotNull(media.originalImage()) {"No original photo source"}
            val reference=File(home,"reference.${original.ext}")
            withTimeout(90_000) {Transfer(app.extractor).stream(original,reference,"reported-reference-${UUID.randomUUID()}",TransferControl()) {_,_->}}
            val referenceSha=reference.inputStream().use(::hash)
            report.put("source_bytes",reference.length()).put("source_sha256",referenceSha)
                .put("source_width",original.width).put("source_height",original.height).put("formats",media.formats.size)
            settings.text("destination","");settings.boolean("completion_notifications",false)
            id=app.repository.create(media,Selection.image(media,original),"MediaFlow photo QA ${UUID.randomUUID()}",allowCompleted=true,plan=DownloadPlan())
            report.put("download_submitted",true)
            app.plannedDownloads.enqueue(id)
            withTimeout(180_000) {while(app.repository.dao.get(id)!!.status !in setOf("COMPLETED","FAILED","CANCELLED")) delay(100)}
            val completed=checkNotNull(app.repository.dao.get(id))
            report.put("queue_status",completed.status).put("queue_error",completed.error)
            assertEquals(completed.error,"COMPLETED",completed.status)
            val uri=android.net.Uri.parse(checkNotNull(completed.uri))
            assertEquals(app.storage.mime(original.ext,false),app.contentResolver.getType(uri))
            val publishedSha=app.contentResolver.openInputStream(uri)!!.use(::hash)
            report.put("published_sha256",publishedSha).put("output_bytes",completed.outputSize)
            assertEquals(referenceSha,publishedSha)
            assertEquals(reference.length(),completed.outputSize)
        } catch(error:Throwable) {failure=error}
        finally {
            try {
                id?.let {key->
                    app.repository.cancel(key);app.coordinator.stopAndAwait(key)
                    app.repository.dao.get(key)?.takeIf {it.status=="COMPLETED"}?.let {row->
                        check(row.title.startsWith("MediaFlow photo QA "));app.storage.deleteFile(row)
                    }
                    app.plannedDownloads.cancel(key);app.repository.remove(key)
                }
                settings.text("destination",old.destination.orEmpty());settings.boolean("completion_notifications",old.completionNotifications)
                home.listFiles()?.filter {it.name.startsWith("reference.")}?.forEach {check(it.delete())}
            } catch(cleanup:Throwable) {if(failure==null) failure=cleanup else failure!!.addSuppressed(cleanup)}
            report.put("passed",failure==null)
            failure?.let {report.put("failure",it.javaClass.simpleName+":"+it.message)}
            val output=File(home,"result.json");output.writeText(report.toString(2))
            instrument.sendStatus(2,Bundle().apply {putString("reported_instagram_report",output.path)})
        }
        failure?.let {throw it}
    }

    private fun hash(input:java.io.InputStream):String {
        val digest=java.security.MessageDigest.getInstance("SHA-256");val buffer=ByteArray(64*1024)
        while(true) {val count=input.read(buffer);if(count<0) break;digest.update(buffer,0,count)}
        return digest.digest().joinToString("") {"%02x".format(it)}
    }
}
