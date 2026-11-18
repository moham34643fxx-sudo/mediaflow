package com.zdownloader.app.status

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/** Feature-owned index/journal. The existing download Room schema is untouched. */
@Singleton class StatusStore:SQLiteOpenHelper {
    @Inject constructor(@ApplicationContext context:Context):super(context,"status-saver.db",null,1)
    internal constructor(context:Context,databaseName:String):super(context,databaseName,null,1)
    private fun inserted(rowId:Long) {if(rowId==-1L) throw android.database.SQLException("Status index write failed")}
    data class Saved(val hash:String,val uri:String,val mime:String,val bytes:Long,val gallery:Boolean)
    override fun onCreate(db:SQLiteDatabase) {
        db.execSQL("CREATE TABLE saved(hash TEXT PRIMARY KEY, uri TEXT NOT NULL, mime TEXT NOT NULL, bytes INTEGER NOT NULL, gallery INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE source_map(source_key TEXT PRIMARY KEY, hash TEXT NOT NULL)")
        db.execSQL("CREATE TABLE pending(id TEXT PRIMARY KEY, payload TEXT NOT NULL)")
    }
    override fun onUpgrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int) {error("Unsupported status index version")}
    fun saved(hash:String):Saved?=readableDatabase.rawQuery("SELECT hash,uri,mime,bytes,gallery FROM saved WHERE hash=?",arrayOf(hash)).use {
        if(it.moveToFirst()) Saved(it.getString(0),it.getString(1),it.getString(2),it.getLong(3),it.getInt(4)==1) else null
    }
    fun forSource(key:String):Saved? {
        val hash=readableDatabase.rawQuery("SELECT hash FROM source_map WHERE source_key=?",arrayOf(key)).use {if(it.moveToFirst()) it.getString(0) else null}
        return hash?.let(::saved)
    }
    fun remember(key:String,saved:Saved) {
        val db=writableDatabase;db.beginTransaction()
        try {
            inserted(db.insertWithOnConflict("saved",null,ContentValues().apply {put("hash",saved.hash);put("uri",saved.uri);put("mime",saved.mime);put("bytes",saved.bytes);put("gallery",if(saved.gallery) 1 else 0)},SQLiteDatabase.CONFLICT_REPLACE))
            inserted(db.insertWithOnConflict("source_map",null,ContentValues().apply {put("source_key",key);put("hash",saved.hash)},SQLiteDatabase.CONFLICT_REPLACE))
            db.setTransactionSuccessful()
        } finally {db.endTransaction()}
    }
    fun pending(id:String,value:JSONObject) {inserted(writableDatabase.insertWithOnConflict("pending",null,ContentValues().apply {put("id",id);put("payload",value.toString())},SQLiteDatabase.CONFLICT_REPLACE))}
    fun pending():List<Pair<String,JSONObject>> = readableDatabase.rawQuery("SELECT id,payload FROM pending",null).use {cursor->
        buildList {while(cursor.moveToNext()) add(cursor.getString(0) to JSONObject(cursor.getString(1)))}
    }
    fun clearPending(id:String) {writableDatabase.delete("pending","id=?",arrayOf(id))}
}
