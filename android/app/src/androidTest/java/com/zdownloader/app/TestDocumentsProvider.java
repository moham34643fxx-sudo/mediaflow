package com.zdownloader.app;

import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.FileNotFoundException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Filesystem-backed SAF contract fixture, isolated in the instrumentation APK. */
public class TestDocumentsProvider extends DocumentsProvider {
    private final ConcurrentHashMap<String, File> files = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> mimes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> queryDelays = new ConcurrentHashMap<>();
    private final java.util.Set<String> startedQueries = ConcurrentHashMap.newKeySet();
    private final java.util.Set<String> finishedQueries = ConcurrentHashMap.newKeySet();
    private boolean stableIds;
    private boolean refuseMutation;
    private boolean failMetadataAfterMutation;
    private String failedQuery;
    private final ConcurrentHashMap<String, String> createFailures = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,Integer> deletions=new ConcurrentHashMap<>();
    private File root() { File f = new File(getContext().getCacheDir(), "saf-contract"); f.mkdirs(); return f; }
    private File file(String id) throws FileNotFoundException { File f = "root".equals(id) ? root() : files.get(id); if(f == null) throw new FileNotFoundException(id); return f; }
    private void grant() {
        long token=android.os.Binder.clearCallingIdentity();
            try {if(isCandidate()) getContext().grantUriPermission("com.zdownloader.app.candidate0965", DocumentsContract.buildTreeDocumentUri(authority(), "root"), Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);}
        finally {android.os.Binder.restoreCallingIdentity(token);}
    }
    private String authority() {return getContext().getPackageName()+".documents";}
    private boolean isCandidate() {return getContext().getPackageName().equals("com.zdownloader.app.test");}
    @Override public boolean onCreate() {
        grant();
        return true;
    }
    @Override public android.os.Bundle call(String method, String arg, android.os.Bundle extras) {
        if("grant".equals(method)) {grant();return new android.os.Bundle();}
        if("grantTree".equals(method)) {long token=android.os.Binder.clearCallingIdentity();try {if(isCandidate()) getContext().grantUriPermission("com.zdownloader.app.candidate0965",DocumentsContract.buildTreeDocumentUri(authority(),arg),Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);} finally {android.os.Binder.restoreCallingIdentity(token);}return new android.os.Bundle();}
        if("revokeTree".equals(method)) {long token=android.os.Binder.clearCallingIdentity();try {getContext().revokeUriPermission(DocumentsContract.buildTreeDocumentUri(authority(),arg),Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);} finally {android.os.Binder.restoreCallingIdentity(token);}return new android.os.Bundle();}
        if("mutationMode".equals(method)) {stableIds=extras.getBoolean("stable");refuseMutation=extras.getBoolean("refuse");failMetadataAfterMutation=extras.getBoolean("failMetadata");failedQuery=null;return new android.os.Bundle();}
        if("failQuery".equals(method)) {failedQuery=arg;return new android.os.Bundle();}
        if("failAuditCreate".equals(method) && isCandidate() &&
                arg.matches("\\.audit-publication-[0-9a-f-]{36}\\.pending")) {
            createFailures.put(arg, extras.getString("failure"));
            return new android.os.Bundle();
        }
        if("deletions".equals(method)) {android.os.Bundle count=new android.os.Bundle();count.putInt("count",deletions.getOrDefault(arg,0));return count;}
        if("delayQuery".equals(method)) {queryDelays.put(arg,Math.min(5000,Math.max(1,extras.getLong("millis",2500))));startedQueries.remove(arg);finishedQueries.remove(arg);return new android.os.Bundle();}
        if("queryState".equals(method)) {android.os.Bundle state=new android.os.Bundle();state.putBoolean("started",startedQueries.contains(arg));state.putBoolean("finished",finishedQueries.contains(arg));return state;}
        if("clearQuery".equals(method)) {queryDelays.remove(arg);startedQueries.remove(arg);finishedQueries.remove(arg);return new android.os.Bundle();}
        return super.call(method,arg,extras);
    }
    @Override public Cursor queryRoots(String[] projection) { return new MatrixCursor(projection == null ? new String[]{"root_id", "document_id", "title", "flags"} : projection); }
    private void row(MatrixCursor cursor, String id, File f) {
        MatrixCursor.RowBuilder row = cursor.newRow();
        for(String column : cursor.getColumnNames()) {
            Object value = null;
            if(column.equals(DocumentsContract.Document.COLUMN_DOCUMENT_ID)) value = id;
            else if(column.equals(DocumentsContract.Document.COLUMN_DISPLAY_NAME)) value = f.getName();
            else if(column.equals(DocumentsContract.Document.COLUMN_MIME_TYPE)) value = f.isDirectory() ? DocumentsContract.Document.MIME_TYPE_DIR : mimes.getOrDefault(id,"video/mp4");
            else if(column.equals(DocumentsContract.Document.COLUMN_FLAGS)) value = f.isDirectory() ? DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE : DocumentsContract.Document.FLAG_SUPPORTS_WRITE | DocumentsContract.Document.FLAG_SUPPORTS_RENAME | DocumentsContract.Document.FLAG_SUPPORTS_DELETE | DocumentsContract.Document.FLAG_SUPPORTS_MOVE;
            else if(column.equals(DocumentsContract.Document.COLUMN_SIZE)) value = f.length();
            row.add(value);
        }
    }
    private String[] columns(String[] projection) { return projection == null ? new String[]{"document_id", "_display_name", "mime_type", "flags", "_size"} : projection; }
    @Override public Cursor queryDocument(String id, String[] projection) throws FileNotFoundException {
        if(id.equals(failedQuery)) {failedQuery=null;throw new FileNotFoundException("Injected metadata failure");}
        Long delay=queryDelays.remove(id);
        if(delay!=null) {startedQueries.add(id);try {Thread.sleep(delay);} catch(InterruptedException error) {Thread.currentThread().interrupt();} finally {finishedQueries.add(id);}}
        MatrixCursor cursor = new MatrixCursor(columns(projection)); row(cursor,id,file(id)); return cursor;
    }
    @Override public Cursor queryChildDocuments(String parent, String[] projection, String sort) throws FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(columns(projection)); File directory = file(parent);
        for(String id : files.keySet()) { File f = files.get(id); if(f != null && directory.equals(f.getParentFile())) row(cursor,id,f); }
        return cursor;
    }
    @Override public String createDocument(String parent, String mime, String name) throws FileNotFoundException {
        String failure = createFailures.remove(name);
        if (failure != null) switch (failure) {
            case "no-space": throw new FileNotFoundException("open failed: ENOSPC (No space left on device)");
            case "permission": throw new SecurityException("Synthetic permission denial");
            case "unavailable": throw new FileNotFoundException("open failed: ENOENT (No such file or directory)");
            case "write": throw new FileNotFoundException("open failed: EIO (I/O error)");
            default: throw new IllegalArgumentException("Unknown audit fixture failure");
        }
        String id = UUID.randomUUID().toString(); File f = new File(file(parent),name);
        try { if(!(DocumentsContract.Document.MIME_TYPE_DIR.equals(mime) ? f.mkdir() : f.createNewFile())) throw new FileNotFoundException(name); } catch(java.io.IOException e) { throw new FileNotFoundException(e.getMessage()); }
        files.put(id,f);mimes.put(id,mime); return id;
    }
    @Override public String renameDocument(String id, String name) throws FileNotFoundException {
        if(refuseMutation) throw new FileNotFoundException("Injected rename refusal");
        File old = file(id); File next = new File(old.getParentFile(),name);
        if(!old.renameTo(next)) throw new FileNotFoundException(name);
        files.remove(id); String fresh = stableIds?id:UUID.randomUUID().toString(); files.put(fresh,next);String mime=mimes.remove(id);if(mime!=null) mimes.put(fresh,mime);
        if(failMetadataAfterMutation) failedQuery=fresh;
        return fresh;
    }
    @Override public void deleteDocument(String id) throws FileNotFoundException {deletions.merge(id,1,Integer::sum);if(!file(id).delete()) throw new FileNotFoundException(id); files.remove(id);mimes.remove(id); }
    @Override public String moveDocument(String id,String source,String target) throws FileNotFoundException {
        if(refuseMutation) throw new FileNotFoundException("Injected move refusal");
        File old=file(id);if(!old.getParentFile().equals(file(source))) throw new FileNotFoundException("Wrong parent");File next=new File(file(target),old.getName());
        if(next.exists()||!old.renameTo(next)) throw new FileNotFoundException("Move failed");files.remove(id);String fresh=stableIds?id:UUID.randomUUID().toString();files.put(fresh,next);String mime=mimes.remove(id);if(mime!=null) mimes.put(fresh,mime);
        if(failMetadataAfterMutation) failedQuery=fresh;
        return fresh;
    }
    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal) throws FileNotFoundException { return ParcelFileDescriptor.open(file(id), ParcelFileDescriptor.parseMode(mode)); }
    @Override public boolean isChildDocument(String parent, String id) { try { return file(id).getCanonicalPath().startsWith(file(parent).getCanonicalPath()+File.separator); } catch(java.io.IOException e) { return false; } }
}
