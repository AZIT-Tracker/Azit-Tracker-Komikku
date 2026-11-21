package eu.kanade.tachiyomi.util.chapter

import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.service.withoutHiddenChapters
import tachiyomi.domain.manga.model.Manga

/**
 * Returns a copy of the list with duplicate chapters removed
 */
fun List<Chapter>.removeDuplicates(currentChapter: Chapter): List<Chapter> {
    return groupBy { it.chapterNumber }
        .map { (_, chapters) ->
            chapters.find { it.id == currentChapter.id }
                ?: chapters.find { it.scanlator == currentChapter.scanlator }
                ?: chapters.first()
        }
}

// Azit -->
/**
 * Keeps one chapter per chapter number, for the "Hide duplicate chapters" filter: 12.4 and 12.5 are different
 * chapters, two 12.5 are duplicates. Chapters without a number (negative) are always kept. The copy kept is the
 * first uploaded one (a copy added later stays hidden), or the last uploaded one with [keepLast]. Chapters without
 * an upload date use the date they were found. The list order is unchanged.
 */
fun <T> List<T>.keepOneChapterEach(keepLast: Boolean, chapterOf: (T) -> Chapter): List<T> {
    if (size < 2) return this
    val firstUploaded = compareBy<Chapter> { chapter -> chapter.dateUpload.takeIf { it > 0 } ?: chapter.dateFetch }
        .thenBy { it.dateFetch }
        .thenBy { it.id }
    val order = if (keepLast) firstUploaded.reversed() else firstUploaded
    val kept = HashSet<Long>()
    map(chapterOf)
        .filter { it.chapterNumber >= 0 }
        .groupBy { it.chapterNumber }
        .values
        .forEach { copies -> kept += copies.minWith(order).id }
    return filter { chapterOf(it).let { chapter -> chapter.chapterNumber < 0 || chapter.id in kept } }
}
// Azit <--

// Azit -->
/**
 * The chapters a series' list shows before the other filters: the ones hidden one by one go first (a hidden copy
 * never stands for its duplicates), then one copy per chapter number with "Hide duplicate chapters".
 */
fun <T> List<T>.visibleChapters(manga: Manga, hiddenIds: Set<Long>, chapterOf: (T) -> Chapter): List<T> {
    val shown = withoutHiddenChapters(hiddenIds, manga.showHiddenChapters) { chapterOf(it).id }
    return if (manga.hideDuplicateChapters) shown.keepOneChapterEach(manga.duplicatesKeepLast, chapterOf) else shown
}
// Azit <--
