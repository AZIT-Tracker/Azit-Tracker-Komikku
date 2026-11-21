package eu.kanade.domain.chapter.interactor

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterEdition
import tachiyomi.domain.chapter.model.withEditedNumbers

// Azit -->
/** The chapters of a series counted as something else (selection menu -> Chapter edition), by chapter id. */
class GetChapterEditions(
    private val handler: DatabaseHandler,
) {

    suspend fun await(mangaId: Long): Map<Long, ChapterEdition> {
        return handler.awaitList { chapter_editionsQueries.getByMangaId(mangaId, ::toEdition) }
            .associateBy { it.chapterId }
    }

    fun subscribe(mangaId: Long): Flow<Map<Long, ChapterEdition>> {
        return handler.subscribeToList { chapter_editionsQueries.getByMangaId(mangaId, ::toEdition) }
            .map { editions -> editions.associateBy { it.chapterId } }
    }

    /** [chapters] of [mangaId] as trackers see them: an edited chapter's number is the last number given. */
    suspend fun applyTo(mangaId: Long, chapters: List<Chapter>): List<Chapter> {
        return chapters.withEditedNumbers(await(mangaId))
    }

    private fun toEdition(chapterId: Long, isVolume: Boolean, start: Double, end: Double?): ChapterEdition {
        return ChapterEdition(chapterId, isVolume, start, end)
    }
}
// Azit <--
