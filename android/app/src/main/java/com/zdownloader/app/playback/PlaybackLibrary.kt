package com.zdownloader.app.playback

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName="playback_favorites")
data class PlaybackFavorite(@PrimaryKey val uri:String,val title:String,val video:Boolean,val mime:String?,val artist:String?,val album:String?,val artwork:String?,val duration:Long) {
    internal fun media()=PlaybackMedia(uri,title,video,mime,artist,album,artwork,duration)
}
@Entity(tableName="playback_lists")
data class PlaybackList(@PrimaryKey val id:String,val name:String,val createdAt:Long=System.currentTimeMillis())
@Entity(tableName="playback_list_items",primaryKeys=["playlistId","uri"],
    foreignKeys=[ForeignKey(entity=PlaybackList::class,parentColumns=["id"],childColumns=["playlistId"],onDelete=ForeignKey.CASCADE)],
    indices=[Index(value=["playlistId","position"])])
data class PlaybackListItem(val playlistId:String,val uri:String,val position:Int,val title:String,val video:Boolean,val mime:String?,val artist:String?,val album:String?,val artwork:String?,val duration:Long) {
    internal fun media()=PlaybackMedia(uri,title,video,mime,artist,album,artwork,duration)
}
data class PlaybackListSummary(val id:String,val name:String,val createdAt:Long,val items:Int)

@Dao abstract class PlaybackLibraryDao {
    @Query("SELECT * FROM playback_favorites ORDER BY title COLLATE NOCASE") abstract fun favorites():Flow<List<PlaybackFavorite>>
    @Query("SELECT uri FROM playback_favorites UNION SELECT uri FROM playback_list_items") abstract suspend fun referencedUris():List<String>
    @Insert(onConflict=OnConflictStrategy.REPLACE) abstract suspend fun favorite(row:PlaybackFavorite)
    @Query("DELETE FROM playback_favorites WHERE uri=:uri") abstract suspend fun unfavorite(uri:String)
    @Query("SELECT p.*,COUNT(i.uri) AS items FROM playback_lists p LEFT JOIN playback_list_items i ON p.id=i.playlistId GROUP BY p.id ORDER BY p.createdAt DESC") abstract fun playlists():Flow<List<PlaybackListSummary>>
    @Insert abstract suspend fun create(list:PlaybackList)
    @Query("UPDATE playback_lists SET name=:name WHERE id=:id") abstract suspend fun rename(id:String,name:String)
    @Query("DELETE FROM playback_lists WHERE id=:id") abstract suspend fun delete(id:String)
    @Query("SELECT * FROM playback_list_items WHERE playlistId=:id ORDER BY position") abstract fun items(id:String):Flow<List<PlaybackListItem>>
    @Query("SELECT COALESCE(MAX(position),-1)+1 FROM playback_list_items WHERE playlistId=:id") abstract suspend fun next(id:String):Int
    @Query("SELECT COUNT(*) FROM playback_list_items WHERE playlistId=:id") abstract suspend fun count(id:String):Int
    @Query("SELECT * FROM playback_list_items WHERE playlistId=:id ORDER BY position") abstract suspend fun ordered(id:String):List<PlaybackListItem>
    @Query("UPDATE playback_list_items SET position=:position WHERE playlistId=:id AND uri=:uri") abstract suspend fun position(id:String,uri:String,position:Int)
    @Insert(onConflict=OnConflictStrategy.IGNORE) abstract suspend fun insert(row:PlaybackListItem)
    @Query("DELETE FROM playback_list_items WHERE playlistId=:id AND uri=:uri") abstract suspend fun remove(id:String,uri:String)
    @Query("DELETE FROM playback_list_items WHERE uri=:uri") abstract suspend fun removeFile(uri:String)
    @Query("UPDATE OR IGNORE playback_list_items SET uri=:newUri,title=:name WHERE uri=:old") abstract suspend fun relinkItems(old:String,newUri:String,name:String)
    @Query("UPDATE OR IGNORE playback_favorites SET uri=:newUri,title=:name WHERE uri=:old") abstract suspend fun relinkFavorite(old:String,newUri:String,name:String)
    @Transaction open suspend fun fileDeleted(uri:String) {unfavorite(uri);removeFile(uri)}
    @Transaction open suspend fun fileRelinked(old:String,newUri:String,name:String) {relinkItems(old,newUri,name);relinkFavorite(old,newUri,name);if(old!=newUri) fileDeleted(old)}
    @Transaction internal open suspend fun append(id:String,media:PlaybackMedia) {
        appendAll(id,listOf(media))
    }
    @Transaction internal open suspend fun appendAll(id:String,media:List<PlaybackMedia>) {
        val existing=ordered(id);val known=existing.map {it.uri}.toSet()
        val additions=media.distinctBy {it.uri}.filter {it.uri !in known}
        require(existing.size+additions.size<=LocalPlaybackPolicy.MAX_QUEUE)
        val start=next(id)
        additions.forEachIndexed {index,item->insert(PlaybackListItem(id,item.uri,start+index,item.title,item.video,item.mime,item.artist,item.album,item.artwork,item.duration))}
    }
    @Transaction internal open suspend fun createWithItems(list:PlaybackList,media:List<PlaybackMedia>) {
        create(list)
        appendAll(list.id,media)
    }
    @Transaction open suspend fun move(id:String,uri:String,direction:Int) {
        val rows=ordered(id).toMutableList();val index=rows.indexOfFirst {it.uri==uri};val target=index+direction
        if(index<0||target !in rows.indices) return
        val item=rows.removeAt(index);rows.add(target,item)
        rows.forEachIndexed {i,row->position(id,row.uri,i)}
    }
}
