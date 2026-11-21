package eu.kanade.domain.chapter.interactor

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import tachiyomi.data.DatabaseHandler

// Azit -->
/** The ids of the chapters hidden one by one (selection menu -> Hide), every series together. */
class GetHiddenChapters(
    private val handler: DatabaseHandler,
) {

    suspend fun await(): Set<Long> {
        return handler.awaitList { hidden_chaptersQueries.getAll() }.toSet()
    }

    fun subscribe(): Flow<Set<Long>> {
        return handler.subscribeToList { hidden_chaptersQueries.getAll() }.map { it.toSet() }
    }
}
// Azit <--
