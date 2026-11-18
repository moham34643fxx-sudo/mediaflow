package com.zdownloader.app.data

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

class DatabaseMigrationTest {

    private class FakeSqliteDatabase : InvocationHandler {
        val executedStatements = mutableListOf<String>()
        val queryLogs = mutableListOf<String>()

        override fun invoke(proxy: Any?, method: Method, args: Array<out Any>?): Any? {
            when (method.name) {
                "execSQL" -> {
                    val sql = args?.get(0) as? String ?: ""
                    executedStatements.add(sql)
                    return null
                }
                "query" -> {
                    val sql = args?.get(0) as? String ?: ""
                    queryLogs.add(sql)
                    // Return empty cursor proxy
                    return createFakeCursor()
                }
                "isOpen" -> return true
                "isWriteAheadLoggingEnabled" -> return false
                "getVersion" -> return 1
                "hashCode" -> return System.identityHashCode(this)
                "equals" -> return proxy === args?.get(0)
                "toString" -> return "FakeSqliteDatabase"
                else -> return defaultValueFor(method.returnType)
            }
        }

        private fun createFakeCursor(): Cursor {
            val handler = InvocationHandler { _, cursorMethod, _ ->
                when (cursorMethod.name) {
                    "moveToNext" -> false
                    "close" -> null
                    "isClosed" -> true
                    "getCount" -> 0
                    "hashCode" -> System.identityHashCode(this)
                    else -> defaultValueFor(cursorMethod.returnType)
                }
            }
            return Proxy.newProxyInstance(
                Cursor::class.java.classLoader,
                arrayOf(Cursor::class.java),
                handler
            ) as Cursor
        }

        private fun defaultValueFor(type: Class<*>): Any? = when (type) {
            Boolean::class.javaPrimitiveType -> false
            Int::class.javaPrimitiveType -> 0
            Long::class.javaPrimitiveType -> 0L
            Float::class.javaPrimitiveType -> 0f
            Double::class.javaPrimitiveType -> 0.0
            Short::class.javaPrimitiveType -> 0.toShort()
            Byte::class.javaPrimitiveType -> 0.toByte()
            Char::class.javaPrimitiveType -> ' '
            else -> null
        }
    }

    private fun createDbProxy(): Pair<SupportSQLiteDatabase, FakeSqliteDatabase> {
        val handler = FakeSqliteDatabase()
        val db = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
            handler
        ) as SupportSQLiteDatabase
        return Pair(db, handler)
    }

    @Test
    fun migration1To2AddsCheckpointAndNotificationPendingColumns() {
        val (db, handler) = createDbProxy()
        ZDatabase.MIGRATION_1_2.migrate(db)

        assertTrue(handler.executedStatements.any { it.contains("ALTER TABLE downloads ADD COLUMN checkpoint") })
        assertTrue(handler.executedStatements.any { it.contains("ALTER TABLE downloads ADD COLUMN notificationPending") })
        assertEquals(2, handler.executedStatements.size)
    }

    @Test
    fun migration2To3CreatesPlaylistBatchesAndAddsBatchColumns() {
        val (db, handler) = createDbProxy()
        ZDatabase.MIGRATION_2_3.migrate(db)

        assertTrue(handler.executedStatements.any { it.contains("CREATE TABLE IF NOT EXISTS playlist_batches") })
        assertTrue(handler.executedStatements.any { it.contains("CREATE UNIQUE INDEX IF NOT EXISTS index_playlist_batches_submissionKey") })
        assertTrue(handler.executedStatements.any { it.contains("ALTER TABLE downloads ADD COLUMN batchId") })
        assertTrue(handler.executedStatements.any { it.contains("ALTER TABLE downloads ADD COLUMN playlistPosition") })
        assertTrue(handler.executedStatements.any { it.contains("ALTER TABLE downloads ADD COLUMN relativeFolder") })
        assertTrue(handler.executedStatements.any { it.contains("ALTER TABLE downloads ADD COLUMN priority") })
        assertTrue(handler.executedStatements.any { it.contains("CREATE INDEX IF NOT EXISTS index_downloads_batchId") })
        assertEquals(7, handler.executedStatements.size)
    }

    @Test
    fun migration3To4AddsSizeKindAndBatchMetadata() {
        val (db, handler) = createDbProxy()
        ZDatabase.MIGRATION_3_4.migrate(db)

        assertTrue(handler.executedStatements.any { it.contains("ALTER TABLE downloads ADD COLUMN sizeKind") })
        assertTrue(handler.executedStatements.any { it.contains("ALTER TABLE playlist_batches ADD COLUMN concurrency") })
        assertTrue(handler.executedStatements.any { it.contains("ALTER TABLE playlist_batches ADD COLUMN thumbnail") })
        assertTrue(handler.executedStatements.any { it.contains("UPDATE downloads SET total=bytes,sizeKind='EXACT'") })
        assertEquals(4, handler.executedStatements.size)
    }

    @Test
    fun migration4To5CreatesPlaybackLibraryTables() {
        val (db, handler) = createDbProxy()
        ZDatabase.MIGRATION_4_5.migrate(db)

        assertTrue(handler.executedStatements.any { it.contains("CREATE TABLE IF NOT EXISTS playback_favorites") })
        assertTrue(handler.executedStatements.any { it.contains("CREATE TABLE IF NOT EXISTS playback_lists") })
        assertTrue(handler.executedStatements.any { it.contains("CREATE TABLE IF NOT EXISTS playback_list_items") })
        assertTrue(handler.executedStatements.any { it.contains("CREATE INDEX IF NOT EXISTS index_playback_list_items_playlistId_position") })
        assertEquals(4, handler.executedStatements.size)
    }

    @Test
    fun migration5To6AddsSchedulingAndIdentityKeys() {
        val (db, handler) = createDbProxy()
        ZDatabase.MIGRATION_5_6.migrate(db)

        assertTrue(handler.executedStatements.any { it.contains("ALTER TABLE downloads ADD COLUMN mediaKey") })
        assertTrue(handler.executedStatements.any { it.contains("ALTER TABLE downloads ADD COLUMN urlKey") })
        assertTrue(handler.executedStatements.any { it.contains("ALTER TABLE downloads ADD COLUMN scheduledAt") })
        assertTrue(handler.executedStatements.any { it.contains("ALTER TABLE downloads ADD COLUMN scheduleNetwork") })
        assertTrue(handler.executedStatements.any { it.contains("ALTER TABLE downloads ADD COLUMN requiresCharging") })
        assertTrue(handler.executedStatements.any { it.contains("CREATE INDEX IF NOT EXISTS index_downloads_mediaKey") })
        assertTrue(handler.executedStatements.any { it.contains("CREATE INDEX IF NOT EXISTS index_downloads_urlKey") })
        assertTrue(handler.queryLogs.any { it.contains("SELECT id,url,fingerprint FROM downloads") })
    }
}
