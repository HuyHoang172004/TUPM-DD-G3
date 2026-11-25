package com.adaptivenews.app.data

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.adaptivenews.app.core.contract.ArticleSummary
import com.adaptivenews.app.core.contract.Mode
import com.adaptivenews.app.core.contract.Policy
import com.adaptivenews.app.core.contract.PolicyProvider
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NewsDatabaseTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun savedDetailUsesRoomWhileOfflineAndWithoutAnHttpCall() = runBlocking(Dispatchers.IO) {
        withRoom { db ->
            val fixture = FakeNewsRepository.fromAssets(context).detail(1)
            val offline = object : PolicyProvider {
                override val policy = MutableStateFlow(
                    Policy(
                        mode = Mode.ECO,
                        imageWidth = 360,
                        pageSize = 10,
                        memoryCacheBytes = 16L * 1024 * 1024,
                        prefetchCount = 0,
                        maxConcurrentRequests = 2,
                        animations = false,
                        syncMinutes = 60,
                        allowNetwork = false,
                        optimized = true,
                        reason = "offline_fixture",
                    ),
                )
            }
            val api = object : NewsApi {
                override suspend fun detail(id: Long) = error("Offline detail made an HTTP call")
                override suspend fun page(query: String?, cursor: String?, limit: Int) =
                    error("Offline feed made an HTTP call")
            }
            val repository = RemoteNewsRepository(api, db.articles(), offline, this)
            repository.setSaved(fixture, true)
            assertEquals(listOf(fixture), repository.saved().first())
            assertEquals(fixture, repository.detail(fixture.article.id))
            repository.setSaved(fixture, false)
            assertEquals(emptyList<Any>(), repository.saved().first())
        }
    }

    @Test
    fun savedQueryUsesCompositeIndexAndAvoidsAnExplicitSort() = runBlocking(Dispatchers.IO) {
        withRoom { room ->
            val db = room.openHelper.writableDatabase
            val dao = room.articles()
            room.runInTransaction {
                val start = Instant.parse("2026-01-01T00:00:00Z")
                for (i in 1L..512L) {
                    val summary = ArticleSummary(
                        i, "Fixture", "Summary", "Technology",
                        start.plusSeconds(i * 60).toString(), "/v1/images/$i",
                    )
                    db.execSQL(
                        "INSERT INTO saved_articles VALUES (?, ?, ?, ?, ?, ?, ?)",
                        arrayOf(i, summary.title, summary.summary, summary.category,
                            summary.publishedAt, summary.imagePath, "Body"),
                    )
                }
            }
            assertEquals(512, dao.observeSaved().first().size)
            val withIndex = plan(db)
            assertTrue(withIndex.contains("index_saved_articles_publishedAt_id", ignoreCase = true))
            val indexedNs = medianQueryNs(db)
            db.execSQL("DROP INDEX index_saved_articles_publishedAt_id")
            val withoutIndex = plan(db)
            assertTrue(withoutIndex.contains("TEMP B-TREE", ignoreCase = true))
            val unindexedNs = medianQueryNs(db)
            Log.i(
                "NewsDatabaseTest",
                "Saved query on in-memory test rows, median ns with index=$indexedNs " +
                    "without index=$unindexedNs",
            )
        }
    }

    private fun plan(db: SupportSQLiteDatabase): String =
        db.query("EXPLAIN QUERY PLAN $QUERY").use { cursor ->
            buildString {
                while (cursor.moveToNext()) appendLine(cursor.getString(3))
            }
        }

    private fun medianQueryNs(db: SupportSQLiteDatabase): Long {
        fun execute() {
            db.query(QUERY).use { cursor -> while (cursor.moveToNext()) cursor.getLong(0) }
        }
        repeat(5) { execute() }
        return List(31) {
            val start = SystemClock.elapsedRealtimeNanos()
            execute()
            SystemClock.elapsedRealtimeNanos() - start
        }.sorted()[15]
    }

    private inline fun <T> withRoom(block: (NewsDatabase) -> T): T {
        val database = Room.inMemoryDatabaseBuilder(context, NewsDatabase::class.java).build()
        return try {
            block(database)
        } finally {
            database.close()
        }
    }

    private companion object {
        const val QUERY = "SELECT * FROM saved_articles ORDER BY publishedAt DESC, id DESC"
    }
}
