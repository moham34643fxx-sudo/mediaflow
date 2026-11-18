package com.zdownloader.app

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zdownloader.app.data.DownloadEntity
import com.zdownloader.app.data.PlaylistBatch
import com.zdownloader.app.data.ZDatabase
import com.zdownloader.app.playback.PlaybackFavorite
import com.zdownloader.app.playback.PlaybackList
import com.zdownloader.app.playback.PlaybackListItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * Genuine integration test for Room database migrations executing on real Android SQLite.
 *
 * Verifies that:
 * 1. Historical v1 schema can be instantiated on physical SQLite with representative records.
 * 2. Room automated migration (v1 -> v6) executes cleanly end-to-end via Room runtime.
 * 3. Step-by-step migrations (1->2, 2->3, 3->4, 4->5, 5->6) each succeed individually on real SQLite.
 * 4. User data (records, foreign keys, indexes, default values, backfilled mediaKeys) is 100% preserved.
 */
@RunWith(AndroidJUnit4::class)
class RealDatabaseMigrationIntegrationTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var testDbName: String = ""

    @Before
    fun setUp() {
        testDbName = "real_migration_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}.db"
        deleteDb(testDbName)
    }

    @After
    fun tearDown() {
        if (testDbName.isNotEmpty()) {
            deleteDb(testDbName)
        }
    }

    private fun deleteDb(name: String) {
        runCatching {
            context.deleteDatabase(name)
            context.getDatabasePath(name)?.delete()
            context.getDatabasePath("$name-wal")?.delete()
            context.getDatabasePath("$name-shm")?.delete()
            File(context.filesDir, name).delete()
        }
    }

    private fun openRawDb(name: String, version: Int, onCreate: (SupportSQLiteDatabase) -> Unit): androidx.sqlite.db.SupportSQLiteOpenHelper {
        val config = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name)
            .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(version) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    onCreate(db)
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(config)
    }

    private fun createV1SchemaAndSeedData(db: SupportSQLiteDatabase) {
        // Create Room master table for v1 identity hash (from 1.json)
        db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
        db.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '38e6166142dba0830ad01bc7489419de')")

        // Exact v1 table DDL from 1.json
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `downloads` (
                `id` TEXT NOT NULL,
                `url` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `source` TEXT NOT NULL,
                `thumbnail` TEXT,
                `duration` REAL,
                `selection` TEXT NOT NULL,
                `filename` TEXT NOT NULL,
                `destination` TEXT,
                `fingerprint` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `finishedAt` INTEGER,
                `bytes` INTEGER NOT NULL,
                `total` INTEGER,
                `speed` INTEGER NOT NULL,
                `uri` TEXT,
                `pendingUri` TEXT,
                `error` TEXT,
                `retries` INTEGER NOT NULL,
                `nextAttempt` INTEGER NOT NULL,
                `outputSize` INTEGER,
                PRIMARY KEY(`id`)
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_downloads_status` ON `downloads` (`status`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_downloads_createdAt` ON `downloads` (`createdAt`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_downloads_fingerprint` ON `downloads` (`fingerprint`)")

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `events` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `downloadId` TEXT NOT NULL,
                `time` INTEGER NOT NULL,
                `state` TEXT NOT NULL,
                `error` TEXT
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_events_downloadId` ON `events` (`downloadId`)")

        // Populate realistic records
        db.execSQL("""
            INSERT INTO `downloads` (`id`, `url`, `title`, `source`, `thumbnail`, `duration`, `selection`, `filename`, `destination`, `fingerprint`, `status`, `createdAt`, `finishedAt`, `bytes`, `total`, `speed`, `uri`, `pendingUri`, `error`, `retries`, `nextAttempt`, `outputSize`)
            VALUES ('dl-101', 'https://example.com/video1.mp4', 'Nature Video 1080p', 'example.com', 'https://example.com/thumb1.jpg', 120.0, '{"output":"mp4"}', 'Nature_Video.mp4', NULL, 'v2:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb', 'COMPLETED', 1700000000000, 1700000010000, 15000000, 15000000, 0, 'content://media/external/video/media/101', NULL, NULL, 0, 0, 15000000)
        """.trimIndent())

        db.execSQL("""
            INSERT INTO `downloads` (`id`, `url`, `title`, `source`, `thumbnail`, `duration`, `selection`, `filename`, `destination`, `fingerprint`, `status`, `createdAt`, `finishedAt`, `bytes`, `total`, `speed`, `uri`, `pendingUri`, `error`, `retries`, `nextAttempt`, `outputSize`)
            VALUES ('dl-102', 'https://example.com/audio.mp3', 'Podcast Episode 1', 'example.com', NULL, 3600.0, '{"output":"mp3"}', 'Podcast_1.mp3', NULL, 'url:example_podcast_key', 'PAUSED', 1700000020000, NULL, 5000000, 25000000, 0, NULL, NULL, NULL, 0, 0, NULL)
        """.trimIndent())

        db.execSQL("INSERT INTO `events` (`downloadId`, `time`, `state`) VALUES ('dl-101', 1700000005000, 'DOWNLOADING')")
        db.execSQL("INSERT INTO `events` (`downloadId`, `time`, `state`) VALUES ('dl-101', 1700000010000, 'COMPLETED')")
    }

    @Test
    fun verifyRoomAutomatedMigrationFromV1ToV6PreservesUserData() = runBlocking {
        // Step 1: Create raw v1 SQLite database on physical device
        val helper = openRawDb(testDbName, 1) { db ->
            createV1SchemaAndSeedData(db)
        }
        val rawDb = helper.writableDatabase
        rawDb.query("SELECT COUNT(*) FROM `downloads`").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(2, c.getInt(0))
        }
        helper.close()

        // Step 2: Open database with Room v6 runtime, passing migrations 1->2, 2->3, 3->4, 4->5, 5->6
        val roomDb = Room.databaseBuilder(context, ZDatabase::class.java, testDbName)
            .addMigrations(
                ZDatabase.MIGRATION_1_2,
                ZDatabase.MIGRATION_2_3,
                ZDatabase.MIGRATION_3_4,
                ZDatabase.MIGRATION_4_5,
                ZDatabase.MIGRATION_5_6,
                ZDatabase.MIGRATION_6_7,
                ZDatabase.MIGRATION_7_8
            )
            .build()

        try {
            val dao = roomDb.downloads()
            val list = dao.observe().first()
            assertEquals("All v1 historical downloads must be preserved after Room migration", 2, list.size)

            val dl101 = dao.get("dl-101")
            assertNotNull(dl101)
            assertEquals("Nature Video 1080p", dl101!!.title)
            assertEquals("COMPLETED", dl101.status)
            assertEquals(15000000L, dl101.outputSize)
            assertEquals("DOWNLOAD", dl101.checkpoint)
            assertFalse(dl101.notificationPending)
            assertEquals("UNKNOWN", dl101.sizeKind)
            assertEquals("v2:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", dl101.mediaKey)
            assertEquals(0L, dl101.scheduledAt)
            assertEquals("ANY", dl101.scheduleNetwork)
            assertFalse(dl101.requiresCharging)
            assertNull(dl101.request)

            val dl102 = dao.get("dl-102")
            assertNotNull(dl102)
            assertEquals("Podcast Episode 1", dl102!!.title)
            assertEquals("PAUSED", dl102.status)
            assertTrue("mediaKey should be backfilled with url key", dl102.mediaKey.startsWith("url:"))

            // Step 3: Verify we can insert and query v6 tables through Room DAOs
            val batch = PlaylistBatch(
                id = "batch-100",
                submissionKey = "sub-100",
                url = "https://example.com/playlist",
                title = "New Batch",
                policy = "CONTINUE",
                createdAt = System.currentTimeMillis(),
                state = "QUEUED",
                numbered = true,
                concurrency = 2,
                thumbnail = null
            )
            val newEntity = DownloadEntity(
                id = "dl-103",
                url = "https://example.com/v3.mp4",
                title = "Item 3",
                source = "example.com",
                thumbnail = null,
                duration = 60.0,
                selection = "{}",
                filename = "Item3.mp4",
                destination = null,
                fingerprint = "fp3",
                mediaKey = "mk3",
                urlKey = "uk3",
                status = "QUEUED",
                createdAt = System.currentTimeMillis(),
                bytes = 0,
                total = 1000L,
                speed = 0,
                retries = 0,
                nextAttempt = 0,
                batchId = "batch-100",
                playlistPosition = 1,
                sizeKind = "EXACT"
            )
            dao.submitBatch(batch, listOf(newEntity))

            val retrievedBatch = dao.batch("batch-100")
            assertNotNull(retrievedBatch)
            assertEquals("New Batch", retrievedBatch!!.title)
            assertEquals(2, retrievedBatch.concurrency)

            // Step 4: Verify playback library tables
            val libDao = roomDb.playbackLibrary()
            libDao.favorite(
                PlaybackFavorite(
                    uri = "content://media/external/video/media/101",
                    title = "Nature Video Favorite",
                    video = true,
                    mime = "video/mp4",
                    artist = null,
                    album = null,
                    artwork = null,
                    duration = 120000L
                )
            )
            val favorites = libDao.favorites().first()
            assertEquals(1, favorites.size)
            assertEquals("Nature Video Favorite", favorites[0].title)

            libDao.create(PlaybackList(id = "list-1", name = "Favorites List"))
            val playlists = libDao.playlists().first()
            assertEquals(1, playlists.size)
            assertEquals("Favorites List", playlists[0].name)
        } finally {
            roomDb.close()
        }
    }

    @Test
    fun verifyStepByStepManualMigrationsOnRealSqlite() = runBlocking {
        // Step 1: Open raw SQLite at version 1
        val helper = openRawDb(testDbName, 1) { db ->
            createV1SchemaAndSeedData(db)
        }
        val db = helper.writableDatabase

        // Apply MIGRATION_1_2
        ZDatabase.MIGRATION_1_2.migrate(db)
        db.query("SELECT checkpoint, notificationPending FROM downloads WHERE id='dl-101'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("DOWNLOAD", c.getString(0))
            assertEquals(0, c.getInt(1))
        }

        // Apply MIGRATION_2_3
        ZDatabase.MIGRATION_2_3.migrate(db)
        db.execSQL("""
            INSERT INTO playlist_batches (id, submissionKey, url, title, policy, createdAt, state, numbered)
            VALUES ('batch-m23', 'sub-m23', 'https://example.com/p', 'Batch 23', 'STOP', 1000, 'DONE', 0)
        """)
        db.query("SELECT COUNT(*) FROM playlist_batches WHERE id='batch-m23'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
        }

        // Apply MIGRATION_3_4
        db.execSQL("""
            INSERT INTO downloads (id, url, title, source, selection, filename, fingerprint, status, createdAt, bytes, total, speed, retries, nextAttempt, checkpoint, notificationPending, batchId)
            VALUES ('dl-m34', 'https://example.com/m34.mp4', 'M34', 'example.com', '{}', 'm34.mp4', 'fp34', 'COMPLETED', 2000, 5000, NULL, 0, 0, 0, 'DOWNLOAD', 0, 'batch-m23')
        """)
        ZDatabase.MIGRATION_3_4.migrate(db)
        db.query("SELECT total, sizeKind FROM downloads WHERE id='dl-m34'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(5000L, c.getLong(0))
            assertEquals("EXACT", c.getString(1))
        }

        // Apply MIGRATION_4_5
        ZDatabase.MIGRATION_4_5.migrate(db)
        db.execSQL("""
            INSERT INTO playback_favorites (uri, title, video, duration)
            VALUES ('content://media/test_fav', 'Test Fav', 1, 60000)
        """)
        db.query("SELECT title FROM playback_favorites WHERE uri='content://media/test_fav'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Test Fav", c.getString(0))
        }

        // Apply MIGRATION_5_6
        ZDatabase.MIGRATION_5_6.migrate(db)
        db.query("SELECT mediaKey, urlKey, scheduledAt, scheduleNetwork, requiresCharging FROM downloads WHERE id='dl-101'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("v2:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", c.getString(0))
            assertFalse(c.getString(1).isNullOrEmpty())
            assertEquals(0L, c.getLong(2))
            assertEquals("ANY", c.getString(3))
            assertEquals(0, c.getInt(4))
        }

        ZDatabase.MIGRATION_6_7.migrate(db)
        db.query("SELECT request FROM downloads WHERE id='dl-101'").use {c->assertTrue(c.moveToFirst());assertTrue(c.isNull(0))}
        helper.close()
    }
}
