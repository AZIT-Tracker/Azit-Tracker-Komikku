package eu.kanade.domain.chapter.model

import eu.kanade.core.util.insertSeparators
import eu.kanade.domain.manga.model.downloadedFilter
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.ui.manga.ChapterList
import eu.kanade.tachiyomi.util.chapter.visibleChapters
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.firstNumber
import tachiyomi.domain.chapter.model.lastNumber
import tachiyomi.domain.chapter.model.missingBetween
import tachiyomi.domain.chapter.service.getChapterSort
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.applyFilter
import tachiyomi.source.local.isLocal
import kotlin.math.floor

/**
 * Applies the view filters to the list of chapters obtained from the database.
 * @return an observable of the list of chapters filtered and sorted.
 */
fun List<Chapter>.applyFilters(
    manga: Manga,
    downloadManager: DownloadManager,
    // SY -->
    mergedManga: Map<Long, Manga>,
    // SY <--
    // Azit -->
    hiddenChapterIds: Set<Long> = emptySet(),
    // Azit <--
): List<Chapter> {
    val isLocalManga = manga.isLocal()
    val unreadFilter = manga.unreadFilter
    val downloadedFilter = manga.downloadedFilter
    val bookmarkedFilter = manga.bookmarkedFilter

    // Azit -->
    val chapters = visibleChapters(manga, hiddenChapterIds) { it }
    // Azit <--

    return chapters.filter { chapter -> applyFilter(unreadFilter) { !chapter.read } }
        .filter { chapter -> applyFilter(bookmarkedFilter) { chapter.bookmark } }
        .filter { chapter ->
            // SY -->
            @Suppress("NAME_SHADOWING")
            val manga = mergedManga.getOrElse(chapter.mangaId) { manga }
            // SY <--
            applyFilter(downloadedFilter) {
                val downloaded = downloadManager.isChapterDownloaded(
                    chapter.name,
                    chapter.scanlator,
                    chapter.url,
                    // SY -->
                    manga.ogTitle,
                    // SY <--
                    manga.source,
                )
                downloaded || isLocalManga
            }
        }
        .sortedWith(getChapterSort(manga))
}

/**
 * Applies the view filters to the list of chapters obtained from the database.
 * @return an observable of the list of chapters filtered and sorted.
 */
fun List<ChapterList.Item>.applyFilters(manga: Manga): Sequence<ChapterList.Item> {
    val isLocalManga = manga.isLocal()
    val unreadFilter = manga.unreadFilter
    val downloadedFilter = manga.downloadedFilter
    val bookmarkedFilter = manga.bookmarkedFilter
    // Azit -->
    val chapters = visibleChapters(manga, filter { it.hidden }.mapTo(HashSet()) { it.id }) { it.chapter }
    return chapters.asSequence()
        // Azit <--
        .filter { (chapter) -> applyFilter(unreadFilter) { !chapter.read } }
        .filter { (chapter) -> applyFilter(bookmarkedFilter) { chapter.bookmark } }
        .filter { applyFilter(downloadedFilter) { it.isDownloaded || isLocalManga } }
        // Azit -->: by chapter number, an edited chapter sorts by the number given to it
        .sortedWith(itemSort(manga))
    // Azit <--
}

// Azit -->
/** The chapter list's order: by chapter number, an edited chapter (Chapter edition) sorts by its first number. */
fun List<ChapterList.Item>.itemSort(manga: Manga): Comparator<ChapterList.Item> {
    val editedNumbers = mapNotNull { item -> item.edition?.let { item.id to it.start } }.toMap()
    val sort = getChapterSort(manga, numberOf = { editedNumbers[it.id] ?: it.chapterNumber })
    return Comparator { item1, item2 -> sort(item1.chapter, item2.chapter) }
}
// Azit <--

// Azit -->
/** A chapter's own number when it means something, -1 (unknown) otherwise; an edited chapter always has one. */
private val ChapterList.Item.ownNumber: Double
    get() = if (edition != null || chapter.isRecognizedNumber) chapter.chapterNumber else -1.0

/**
 * The chapter list with "N missing chapters" rows, counted with the numbers given in Chapter edition (a volume
 * covers its whole range, so 39-43 then 44-49 leaves nothing missing). Between two chapters the gap is counted
 * whatever their order; the chapters missing before the lowest one show at its end of the list (the bottom when
 * [sortDescending]), only when the chapter there is the lowest.
 */
fun List<ChapterList.Item>.withMissingCounts(sortDescending: Boolean): List<ChapterList> {
    val lowest = map { it.edition.firstNumber(it.ownNumber) }.filter { it >= 0.0 }.minOrNull()
    return insertSeparators { before, after ->
        val missing = when {
            before != null && after != null -> missingBetween(
                before.edition.firstNumber(before.ownNumber),
                before.edition.lastNumber(before.ownNumber),
                after.edition.firstNumber(after.ownNumber),
                after.edition.lastNumber(after.ownNumber),
            )
            // The lowest chapter's end of the list: the bottom when sorted descending, the top otherwise.
            (if (sortDescending) after == null else before == null) -> {
                val edge = before ?: after
                val first = edge?.edition.firstNumber(edge?.ownNumber ?: -1.0)
                if (edge == null || lowest == null || first != lowest) 0 else (floor(first).toInt() - 1).coerceAtLeast(0)
            }
            else -> 0
        }
        missing.takeIf { it > 0 }?.let { ChapterList.MissingCount(id = "${before?.id}-${after?.id}", count = it) }
    }
}
// Azit <--
