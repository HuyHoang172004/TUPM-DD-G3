package com.adaptivenews.app.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import com.adaptivenews.app.core.contract.ArticleDetail
import com.adaptivenews.app.core.contract.ArticleSummary
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "saved_articles",
    indices = [Index(value = ["publishedAt", "id"])],
)
data class SavedArticleRow(
    @PrimaryKey val id: Long,
    val title: String,
    val summary: String,
    val category: String,
    val publishedAt: String,
    val imagePath: String,
    val body: String,
) {
    fun toDomain() = ArticleDetail(
        ArticleSummary(id, title, summary, category, publishedAt, imagePath), body,
    )

    companion object {
        fun from(article: ArticleDetail): SavedArticleRow = with(article.article) {
            SavedArticleRow(id, title, summary, category, publishedAt, imagePath, article.body)
        }
    }
}

@Entity(tableName = "feed_batch")
data class FeedRow(
    @PrimaryKey val id: Long,
    val title: String,
    val summary: String,
    val category: String,
    val publishedAt: String,
    val imagePath: String,
) {
    fun toDomain() = ArticleSummary(id, title, summary, category, publishedAt, imagePath)

    companion object {
        fun from(article: ArticleSummary) = with(article) {
            FeedRow(id, title, summary, category, publishedAt, imagePath)
        }
    }
}

@Dao
abstract class ArticleDao {
    @Query("SELECT * FROM saved_articles ORDER BY publishedAt DESC, id DESC")
    abstract fun observeSaved(): Flow<List<SavedArticleRow>>

    @Query("SELECT * FROM saved_articles WHERE id = :id")
    abstract suspend fun savedById(id: Long): SavedArticleRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun save(row: SavedArticleRow)

    @Query("DELETE FROM saved_articles WHERE id = :id")
    abstract suspend fun removeSaved(id: Long)

    @Query("SELECT * FROM feed_batch ORDER BY id ASC LIMIT :limit")
    abstract suspend fun firstBatch(limit: Int): List<FeedRow>

    @Query("DELETE FROM feed_batch")
    protected abstract suspend fun clearBatch()

    @Insert
    protected abstract suspend fun insertBatch(rows: List<FeedRow>)

    @Transaction
    open suspend fun replaceBatch(rows: List<FeedRow>) {
        clearBatch()
        insertBatch(rows)
    }
}

@Database(entities = [SavedArticleRow::class, FeedRow::class], version = 1, exportSchema = true)
abstract class NewsDatabase : RoomDatabase() {
    abstract fun articles(): ArticleDao
}
