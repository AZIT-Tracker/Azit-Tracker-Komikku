package eu.kanade.tachiyomi.ui.browse.migration.search

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.browse.MigrateSearchScreen
import eu.kanade.presentation.browse.components.BulkFavoriteDialogs
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.browse.BulkFavoriteScreenModel
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.SearchScreenModel
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import mihon.feature.migration.dialog.MigrateMangaDialog
import mihon.feature.migration.list.MigrationListScreen

class MigrateSearchScreen(
    private val mangaId: Long,
    // KMK -->
    /** Reopened from the migration's ←: going back leaves the migration, to the entry it started from. */
    private val backToEntry: Boolean = false,
    // KMK <--
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow

        val screenModel = rememberScreenModel { MigrateSearchScreenModel(mangaId = mangaId) }
        val state by screenModel.state.collectAsState()

        // KMK -->
        val bulkFavoriteScreenModel = rememberScreenModel { BulkFavoriteScreenModel() }
        val bulkFavoriteState by bulkFavoriteScreenModel.state.collectAsState()

        // KMK -->
        BackHandler(enabled = backToEntry && !bulkFavoriteState.selectionMode) { leaveToEntry(navigator) }
        // KMK <--
        BackHandler(enabled = bulkFavoriteState.selectionMode) {
            bulkFavoriteScreenModel.backHandler()
        }
        // KMK <--

        MigrateSearchScreen(
            state = state,
            fromSourceId = state.from?.source,
            // KMK -->
            navigateUp = { if (backToEntry) leaveToEntry(navigator) else navigator.pop() },
            // KMK <--
            onChangeSearchQuery = screenModel::updateSearchQuery,
            onSearch = { screenModel.search() },
            getManga = { screenModel.getManga(it) },
            onChangeSearchFilter = screenModel::setSourceFilter,
            onToggleResults = screenModel::toggleFilterResults,
            onClickSource = { navigator.push(MigrateSourceSearchScreen(state.from!!, it.id, state.searchQuery)) },
            onClickItem = {
                // KMK -->
                if (bulkFavoriteState.selectionMode) {
                    bulkFavoriteScreenModel.toggleSelection(it)
                } else {
                    // KMK <--
                    val migrateListScreen = navigator.items
                        .filterIsInstance<MigrationListScreen>()
                        .lastOrNull()

                    if (migrateListScreen == null) {
                        screenModel.setMigrateDialog(mangaId, it)
                    } else {
                        migrateListScreen.addMatchOverride(current = mangaId, target = it.id)
                        // KMK -->
                        MigrateSearchScreenModel.keepResults(mangaId, state)
                        // KMK <--
                        navigator.popUntil { screen -> screen is MigrationListScreen }
                    }
                }
            },
            onLongClickItem = { navigator.push(MangaScreen(it.id, true)) },
            // KMK -->
            bulkFavoriteScreenModel = bulkFavoriteScreenModel,
            hasPinnedSources = screenModel.hasPinnedSources(),
            // KMK <--
        )

        when (val dialog = state.dialog) {
            is SearchScreenModel.Dialog.Migrate -> {
                MigrateMangaDialog(
                    current = dialog.current,
                    target = dialog.target,
                    // Initiated from the context of [dialog.current] so we show [dialog.target].
                    onClickTitle = { navigator.push(MangaScreen(dialog.target.id, true)) },
                    onDismissRequest = { screenModel.clearDialog() },
                    onComplete = {
                        if (navigator.lastItem is MangaScreen) {
                            val lastItem = navigator.lastItem
                            navigator.popUntil { navigator.items.contains(lastItem) }
                            navigator.push(MangaScreen(dialog.target.id))
                        } else {
                            navigator.replace(MangaScreen(dialog.target.id))
                        }
                    },
                )
            }
            else -> {}
        }

        // KMK -->
        // Bulk-favorite actions only
        BulkFavoriteDialogs(
            bulkFavoriteScreenModel = bulkFavoriteScreenModel,
            dialog = bulkFavoriteState.dialog,
        )
        // KMK <--
    }

    // KMK -->
    /** Out of the migration: back to the screen it was started from (the entry), the kept results forgotten. */
    private fun leaveToEntry(navigator: cafe.adriel.voyager.navigator.Navigator) {
        MigrateSearchScreenModel.forgetResults()
        navigator.popUntil { it !is MigrateSearchScreen && it !is MigrationListScreen }
    }
    // KMK <--
}
