package eu.kanade.domain.chapter.interactor

import tachiyomi.data.DatabaseHandler

// Azit -->
/** Hides chapters of a series' chapter list one by one, or shows them again. */
class SetChaptersHidden(
    private val handler: DatabaseHandler,
) {

    suspend fun await(chapterIds: Collection<Long>, hidden: Boolean) {
        if (chapterIds.isEmpty()) return
        handler.await(inTransaction = true) {
            if (hidden) {
                chapterIds.forEach { hidden_chaptersQueries.insert(it) }
            } else {
                hidden_chaptersQueries.remove(chapterIds)
            }
        }
    }
}
// Azit <--
