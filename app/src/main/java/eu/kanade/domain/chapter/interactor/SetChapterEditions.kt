package eu.kanade.domain.chapter.interactor

import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.chapter.model.ChapterEdition

// Azit -->
/** Saves the chapters counted as something else, and removes the edition of the chapters in [cleared]. */
class SetChapterEditions(
    private val handler: DatabaseHandler,
) {

    suspend fun await(editions: List<ChapterEdition>, cleared: Collection<Long> = emptyList()) {
        if (editions.isEmpty() && cleared.isEmpty()) return
        handler.await(inTransaction = true) {
            if (cleared.isNotEmpty()) chapter_editionsQueries.remove(cleared)
            editions.forEach { chapter_editionsQueries.upsert(it.chapterId, it.isVolume, it.start, it.end) }
        }
    }
}
// Azit <--
