package com.zdownloader.app.status

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import com.zdownloader.app.data.SettingsRepository
import org.json.JSONArray

internal fun newlyPersistedGrantFlags(before:Int,after:Int,requested:Int):Int=after and requested and before.inv()

internal data class PersistedGrantSnapshot(val uri:Uri,val read:Boolean,val write:Boolean)
internal interface PersistableGrantAccess {
    fun persisted():List<PersistedGrantSnapshot>
    fun take(uri:Uri,flags:Int)
    fun release(uri:Uri,flags:Int)
}
private class ResolverPersistableGrantAccess(private val resolver:android.content.ContentResolver):PersistableGrantAccess {
    override fun persisted():List<PersistedGrantSnapshot> = resolver.persistedUriPermissions.map {PersistedGrantSnapshot(it.uri,it.isReadPermission,it.isWritePermission)}
    override fun take(uri:Uri,flags:Int) {resolver.takePersistableUriPermission(uri,flags)}
    override fun release(uri:Uri,flags:Int) {resolver.releasePersistableUriPermission(uri,flags)}
}

/** Keep the last known-good value visible when SharedPreferences cannot commit a replacement. */
internal class RecoverableStringPreferences(
    private val read: (String) -> String?,
    private val write: (String, String?) -> Boolean
) {
    private val lock = Any()
    private val rollback = mutableMapOf<String, String?>()

    fun get(key: String): String? = synchronized(lock) {
        if (rollback.containsKey(key)) rollback[key] else read(key)
    }

    fun put(key: String, value: String?): Boolean = synchronized(lock) {
        val previous = if (rollback.containsKey(key)) rollback[key] else read(key)
        if (runCatching { write(key, value) }.getOrDefault(false)) {
            rollback.remove(key)
            true
        } else {
            // commit() may have changed the in-memory preference map before its disk write failed.
            // Attempt to restore it, and keep an in-process last-known-good override if that also fails.
            if (runCatching { write(key, previous) }.getOrDefault(false)) rollback.remove(key)
            else rollback[key] = previous
            false
        }
    }
}

@Singleton class StatusPermissions @Inject constructor(@ApplicationContext private val context:Context,private val settings:SettingsRepository) {
    private val mutations=Mutex()
    private data class GrantTake(val accepted:Boolean,val newFlags:Int)
    private val prefs=context.getSharedPreferences("status_saver_sources",Context.MODE_PRIVATE)
    private var grantAccess:PersistableGrantAccess=ResolverPersistableGrantAccess(context.contentResolver)
    internal constructor(context:Context,settings:SettingsRepository,grantAccess:PersistableGrantAccess):this(context,settings) {this.grantAccess=grantAccess}
    @Volatile private var sessionImportOverride:List<Uri>?=null
    private val values = RecoverableStringPreferences(
        read = { key -> prefs.getString(key, null) },
        write = { key, value ->
            val editor = prefs.edit()
            if (value == null) editor.remove(key) else editor.putString(key, value)
            editor.commit()
        }
    )
    fun configured(source:StatusSource):Uri?=values.get(source.key)?.let(Uri::parse)
    fun installed(source:StatusSource):Boolean=source.packageName?.let {name->runCatching {context.packageManager.getPackageInfo(name,0);true}.getOrDefault(false)} ?: false
    fun initial(source:StatusSource):Uri? {
        configured(source)?.let {return it}
        val externalStorage=runCatching {android.os.Environment.getExternalStorageDirectory()}.getOrNull()
        val documentId=StatusDirectories.initialDocumentId(source) {path->
            externalStorage!=null&&java.io.File(externalStorage,path).isDirectory
        } ?: return null
        return DocumentsContract.buildDocumentUri("com.android.externalstorage.documents",documentId)
    }
    fun readable(uri:Uri):Boolean {
        if(uri.scheme!="content") return false
        if(uri.authority=="${context.packageName}.status.sources") return runCatching {
            context.contentResolver.openFileDescriptor(uri,"r")?.use {true} ?: false
        }.getOrDefault(false)
        return context.checkUriPermission(uri,android.os.Process.myPid(),android.os.Process.myUid(),Intent.FLAG_GRANT_READ_URI_PERMISSION)==PackageManager.PERMISSION_GRANTED
    }
    fun requireReadable(uri:Uri) {if(!readable(uri)) throw StatusException(StatusProblem.ACCESS_REVOKED)}
    private fun persistedFlags(uri:Uri):Int=grantAccess.persisted()
        .filter {it.uri==uri}.fold(0) {flags,permission->flags or
            (if(permission.read) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
            (if(permission.write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)}
    private fun takePersistable(uri:Uri,flags:Int):GrantTake {
        val before=persistedFlags(uri)
        return try {
            grantAccess.take(uri,flags)
            val after=persistedFlags(uri)
            GrantTake(true,newlyPersistedGrantFlags(before,after,flags))
        } catch(_:SecurityException) {GrantTake(false,0)}
    }
    fun sourceIsSessionOnly(source:StatusSource):Boolean=configured(source)?.let {uri->
        runCatching {(persistedFlags(uri) and Intent.FLAG_GRANT_READ_URI_PERMISSION)==0}.getOrDefault(true)
    } ?: false
    private fun resolve(source:StatusSource,tree:Uri):DocumentFile {
        if(tree.scheme!="content"||!DocumentsContract.isTreeUri(tree)) throw StatusException(StatusProblem.WRONG_FOLDER)
        requireReadable(tree)
        val id=DocumentsContract.getTreeDocumentId(tree)
        val parts=id.substringAfter(':').split('/')
        if(source==StatusSource.WHATSAPP&&(parts.contains("com.whatsapp.w4b")||parts.contains("WhatsApp Business")) ||
            source==StatusSource.BUSINESS&&(parts.contains("com.whatsapp")||parts.contains("WhatsApp"))) throw StatusException(StatusProblem.WRONG_SOURCE)
        val root=DocumentFile.fromTreeUri(context,tree) ?: throw StatusException(StatusProblem.WRONG_FOLDER)
        // No recursive search through chats/documents or the phone. A directly
        // selected status directory supports migrated/account-specific layouts.
        val folder=when(root.name) {
            ".Statuses","Statuses"->root
            else->{
                val found=root.findFile(".Statuses") ?: root.findFile("Statuses")
                if(found!=null) found
                else {
                    val volume=id.substringBefore(':',"primary")
                    val candidates=StatusDirectories.relativePaths(source).map {"$volume:$it"}
                    val match=candidates.firstOrNull {candidateId->
                        if(!StatusRules.containsDocument(id,candidateId)) return@firstOrNull false
                        val candidateUri=DocumentsContract.buildDocumentUriUsingTree(tree,candidateId)
                        runCatching {
                            context.contentResolver.query(candidateUri,arrayOf(
                                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                                DocumentsContract.Document.COLUMN_MIME_TYPE
                            ),null,null,null)?.use {cursor->
                                cursor.moveToFirst()&&cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE))==DocumentsContract.Document.MIME_TYPE_DIR
                            } ?: false
                        }.getOrDefault(false)
                    }
                    if(match!=null) DocumentFile.fromTreeUri(context,DocumentsContract.buildDocumentUriUsingTree(tree,match)) ?: throw StatusException(StatusProblem.FOLDER_UNAVAILABLE)
                    else if(root.name=="Media"||parts.lastOrNull()=="Media"||id.endsWith("/Media")) root
                    else throw StatusException(if(parts.any {it.equals("WhatsApp",true)||it.equals("com.whatsapp",true)||it.equals("WhatsApp Business",true)||it.equals("com.whatsapp.w4b",true)}) StatusProblem.FOLDER_UNAVAILABLE else StatusProblem.WRONG_FOLDER)
                }
            }
        }
        if(!folder.isDirectory) throw StatusException(StatusProblem.FOLDER_UNAVAILABLE)
        // Query explicitly: DocumentFile convenience methods can hide provider errors.
        context.contentResolver.query(folder.uri,arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),null,null,null)?.use {
            if(!it.moveToFirst()) throw StatusException(StatusProblem.FOLDER_UNAVAILABLE)
        } ?: throw StatusException(StatusProblem.FOLDER_UNAVAILABLE)
        return folder
    }
    suspend fun folder(source:StatusSource):DocumentFile=withContext(Dispatchers.IO) {
        val uri=mutations.withLock {configured(source) ?: discover(source)} ?: throw StatusException(StatusProblem.ACCESS_REQUIRED)
        try {resolve(source,uri)} catch(_:SecurityException) {throw StatusException(StatusProblem.ACCESS_REVOKED)}
    }
    internal sealed interface Location {
        data class Granted(val folder:DocumentFile):Location
        data class Direct(val directory:StatusDirectories.Readable):Location
    }
    internal suspend fun location(source:StatusSource):Location=withContext(Dispatchers.IO) {
        try {Location.Granted(folder(source))}
        catch(error:StatusException) {
            if(error.problem !in setOf(StatusProblem.ACCESS_REQUIRED,StatusProblem.ACCESS_REVOKED,StatusProblem.FOLDER_UNAVAILABLE)) throw error
            val directory=StatusDirectories.readable(android.os.Environment.getExternalStorageDirectory(),source) ?: throw error
            Location.Direct(directory)
        }
    }
    internal fun directUri(file:java.io.File):Uri=androidx.core.content.FileProvider.getUriForFile(context,"${context.packageName}.status.sources",file)
    /** Reuse existing user-granted access by querying only the exact known status
     * location. Never enumerate an ancestor tree or ask for another permission. */
    private fun discover(source:StatusSource):Uri? {
        val paths=StatusDirectories.relativePaths(source)
        val roots=StatusDirectories.mediaRoots(source)
        if(paths.isEmpty()&&roots.isEmpty()) return null
        for(grant in context.contentResolver.persistedUriPermissions) {
            val tree=grant.uri
            if(!grant.isReadPermission||tree.authority!="com.android.externalstorage.documents"||!DocumentsContract.isTreeUri(tree)) continue
            val ancestor=runCatching {DocumentsContract.getTreeDocumentId(tree)}.getOrNull() ?: continue
            val volume=ancestor.substringBefore(':',"primary")
            val mediaCandidates=roots.map {"$volume:$it"}
            if(ancestor in mediaCandidates) {
                try {
                    resolve(source,tree)
                    if(values.put(source.key,tree.toString())) return tree
                } catch(_:Exception) {}
            }
            val candidates=paths.map {"$volume:$it"}
            for(document in candidates) {
                if(!StatusRules.containsDocument(ancestor,document)) continue
                val candidate=DocumentsContract.buildDocumentUriUsingTree(tree,document)
                try {
                    resolve(source,candidate)
                    if(values.put(source.key,candidate.toString())) return candidate
                } catch(_:Exception) { /* Try another exact known location. */ }
            }
        }
        return null
    }
    suspend fun choose(source:StatusSource,uri:Uri):Boolean=withContext(Dispatchers.IO) {mutations.withLock {
        try {
            resolve(source,uri)
            val take=takePersistable(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val old=configured(source)
            if (!values.put(source.key,uri.toString())) {
                if(take.newFlags!=0) withContext(NonCancellable) {releaseIfUnused(uri,take.newFlags)}
                throw StatusException(StatusProblem.SAVE_FAILED)
            }
            if(old!=null&&old!=uri) releaseIfUnused(old)
            take.accepted
        } catch(_:SecurityException) {throw StatusException(StatusProblem.ACCESS_REVOKED)}
    }}
    fun importedFiles():List<Uri> = sessionImportOverride ?: runCatching {
        val list=JSONArray(values.get("import_files") ?: "[]");(0 until minOf(list.length(),StatusRules.MAX_ITEMS)).map {Uri.parse(list.getString(it))}
    }.getOrDefault(emptyList())
    fun importsAreSessionOnly():Boolean {
        val session=sessionImportOverride ?: return false
        val durable=runCatching {
            val list=JSONArray(values.get("import_files") ?: "[]");(0 until minOf(list.length(),StatusRules.MAX_ITEMS)).map {Uri.parse(list.getString(it))}
        }.getOrDefault(emptyList())
        return session.distinct()!=durable.distinct()
    }
    suspend fun replaceImports(uris:List<Uri>):Boolean=withContext(Dispatchers.IO) {mutations.withLock {
        val old=importedFiles()
        val persistent=mutableListOf<Uri>()
        val newlyGranted=mutableMapOf<Uri,Int>()
        var committed=false
        try {
            for(uri in uris.distinct()) {
                currentCoroutineContext().ensureActive()
                requireReadable(uri)
                // Some providers offer only an Activity-lifetime grant. Keep those URIs
                // in ViewModel state for this session, but never persist them as durable.
                val take=takePersistable(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if(take.newFlags!=0) newlyGranted[uri]=(newlyGranted[uri] ?: 0) or take.newFlags
                if(take.accepted) persistent+=uri
            }
            val encoded=JSONArray(persistent.map {it.toString()}).toString()
            if(!values.put("import_files",encoded)) throw StatusException(StatusProblem.SAVE_FAILED)
            committed=true
            sessionImportOverride=uris.distinct()
            withContext(NonCancellable) {for(uri in old.filter {it !in uris}) releaseIfUnused(uri)}
            persistent.size!=uris.distinct().size
        } catch(error:CancellationException) {
            if(!committed) withContext(NonCancellable) {newlyGranted.forEach {(uri,flags)->runCatching {releaseIfUnused(uri,flags)}}}
            throw error
        } catch(error:Exception) {
            if(!committed) withContext(NonCancellable) {newlyGranted.forEach {(uri,flags)->runCatching {releaseIfUnused(uri,flags)}}}
            throw error
        }
    }}
    suspend fun rememberImports(uris:List<Uri>)=withContext(Dispatchers.IO) {mutations.withLock {
        val old=importedFiles()
        if (!values.put("import_files",JSONArray(uris.map {it.toString()}).toString())) throw StatusException(StatusProblem.SAVE_FAILED)
        sessionImportOverride=uris.distinct()
        withContext(NonCancellable) {for(uri in old.filter {it !in uris}) releaseIfUnused(uri)}
    }}
    internal suspend fun hasOtherPermissionOwner(uri:Uri):Boolean {
        val statusOwners=StatusSource.entries.filter {it!=StatusSource.IMPORTS}.mapNotNull(::configured)+importedFiles()
        if(statusOwners.any {com.zdownloader.app.data.MediaFiles.permissionCovers(uri,it)}) return true
        if(com.zdownloader.app.data.MediaFiles.referencesFolderPermission(context,uri)) return true

        val currentDestination=try {settings.current().destination} catch(_:Exception) {return true}
        if(currentDestination?.let {runCatching {com.zdownloader.app.data.MediaFiles.permissionCovers(uri,Uri.parse(it))}.getOrDefault(true)}==true) return true

        val app=context.applicationContext as? com.zdownloader.app.ZApplication ?: return true
        val destinations=try {app.database.downloads().referencedDestinations()} catch(_:Exception) {return true}
        if(destinations.any {stored->runCatching {com.zdownloader.app.data.MediaFiles.permissionCovers(uri,Uri.parse(stored))}.getOrDefault(true)}) return true
        if(com.zdownloader.app.playback.PlaybackGrantReferences.needed(context,uri)) return true
        return false
    }
    private suspend fun releaseIfUnused(uri:Uri,requestedFlags:Int=Intent.FLAG_GRANT_READ_URI_PERMISSION) {
        try {
            val flags=persistedFlags(uri) and requestedFlags
            if(flags==0||hasOtherPermissionOwner(uri)) return
            grantAccess.release(uri,flags)
        } catch(error:CancellationException) {throw error}
        catch(_:Exception) {
            // Releasing is optional cleanup after a committed setting change. If ownership
            // or provider state is uncertain, retain the application grant.
        }
    }
    suspend fun forget(source:StatusSource)=withContext(Dispatchers.IO) {mutations.withLock {
        val old=configured(source)
        if (!values.put(source.key,null)) throw StatusException(StatusProblem.SAVE_FAILED)
        withContext(NonCancellable) {old?.let {releaseIfUnused(it)}};Unit
    }}
}
