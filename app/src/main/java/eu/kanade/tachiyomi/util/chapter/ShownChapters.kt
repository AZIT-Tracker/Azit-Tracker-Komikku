package eu.kanade.tachiyomi.util.chapter

import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

// Azit -->
/**
 * The ids of the chapters a series' list shows: [chapters] are the series' chapters without the excluded
 * scanlators (`applyFilter = true`), then the copies hidden one by one and the hidden duplicates go, the list's own
 * rule ([visibleChapters]). Downloads follow it so a series never holds a download its list doesn't show.
 */
fun List<Chapter>.shownChapterIds(manga: Manga, hiddenIds: Set<Long>): Set<Long> {
    return visibleChapters(manga, hiddenIds) { it }.mapTo(HashSet()) { it.id }
}

/**
 * The other copies of [chapter] (same series, same chapter number) that the list doesn't show ([shownIds]): a
 * duplicate hidden behind it, or a copy hidden one by one. They go with it when it is deleted after being read,
 * so no hidden download is left behind. A chapter without a number has no copies.
 */
fun List<Chapter>.hiddenCopiesOf(chapter: Chapter, shownIds: Set<Long>): List<Chapter> {
    if (chapter.chapterNumber < 0) return emptyList()
    return filter {
        it.id != chapter.id &&
            it.mangaId == chapter.mangaId &&
            it.chapterNumber == chapter.chapterNumber &&
            it.id !in shownIds
    }
}
// Azit <--
