package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import kotlinx.coroutines.launch
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.asMangaCover
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.TabText
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Settings -> Tracking -> "Check unlinked entries": one tab per tracker you are logged in to, each listing the library
 * series not linked to it. Tap one to open its page and link it there; the lists are read again when you come back.
 */
class UnlinkedEntriesScreen : Screen() {

    /** For one tracker: the library series it does not track. */
    private data class Unlinked(val tracker: Tracker, val manga: List<Manga>)

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val trackerManager = remember { Injekt.get<TrackerManager>() }
        val sourceManager = remember { Injekt.get<SourceManager>() }

        // Read again every time the screen shows (after linking a series on its page, it leaves the list).
        val unlinked by produceState<List<Unlinked>?>(initialValue = null) {
            value = withIOContext {
                val library = Injekt.get<GetLibraryManga>().await().map { it.manga }.distinctBy { it.id }
                    .sortedBy { it.title.lowercase() }
                val tracks = Injekt.get<GetTracks>().await()
                val linked = tracks.groupBy({ it.trackerId }, { it.mangaId }).mapValues { (_, ids) -> ids.toSet() }
                trackerManager.loggedInTrackers().map { tracker ->
                    val ids = linked[tracker.id].orEmpty()
                    Unlinked(tracker, library.filter { it.id !in ids })
                }
            }
        }

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(KMR.strings.unlinked_entries_title),
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { contentPadding ->
            val lists = unlinked
            when {
                lists == null -> LoadingScreen(Modifier.padding(contentPadding))
                lists.isEmpty() -> EmptyScreen(
                    stringRes = KMR.strings.unlinked_entries_no_tracker,
                    modifier = Modifier.padding(contentPadding),
                )
                else -> {
                    val pagerState = rememberPagerState { lists.size }
                    val scope = rememberCoroutineScope()
                    Column(Modifier.padding(contentPadding)) {
                        PrimaryScrollableTabRow(selectedTabIndex = pagerState.currentPage, edgePadding = 0.dp) {
                            lists.forEachIndexed { index, list ->
                                Tab(
                                    selected = pagerState.currentPage == index,
                                    onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                                    text = { TabText(text = list.tracker.name, badgeCount = list.manga.size) },
                                )
                            }
                        }
                        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                            val list = lists[page]
                            if (list.manga.isEmpty()) {
                                EmptyScreen(stringResource(KMR.strings.unlinked_entries_none, list.tracker.name))
                            } else {
                                LazyColumn(Modifier.fillMaxSize()) {
                                    items(list.manga, key = { it.id }) { manga ->
                                        UnlinkedRow(
                                            manga = manga,
                                            sourceName = remember(manga.source) { sourceManager.getOrStub(manga.source).name },
                                            onClick = { navigator.push(MangaScreen(manga.id)) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UnlinkedRow(manga: Manga, sourceName: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MangaCover.Book(data = manga.asMangaCover(), modifier = Modifier.height(64.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = manga.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = sourceName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}
