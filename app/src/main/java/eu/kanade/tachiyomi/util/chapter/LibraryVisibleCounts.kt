package eu.kanade.tachiyomi.util.chapter

import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.library.model.LibraryManga

// Azit -->
/**
 * The library counts (total, read, bookmarked: the unread badge and the unread filter and sort) of a series that
 * hides its duplicate chapters, made from the chapters its list shows: the same rule as the series' chapter list
 * ([keepOneChapterEach]), so the badge always matches what is seen there.
 *
 * [chapters] are all the series' chapters; the ones hidden one by one ([hiddenIds]) and the ones of excluded
 * scanlators ([excludedScanlators]) are left out first, as the library's own counts do. A series that doesn't hide
 * duplicates is returned unchanged (its counts already match).
 */
fun LibraryManga.withVisibleCounts(
    chapters: List<Chapter>,
    hiddenIds: Set<Long>,
    excludedScanlators: Set<String>,
): LibraryManga {
    if (!manga.hideDuplicateChapters) return this
    val visible = chapters
        .filter { it.id !in hiddenIds && (it.scanlator == null || it.scanlator !in excludedScanlators) }
        .keepOneChapterEach(manga.duplicatesKeepLast) { it }
    return copy(
        totalChapters = visible.size.toLong(),
        readCount = visible.count { it.read }.toLong(),
        bookmarkCount = visible.count { it.bookmark }.toLong(),
        bookmarkReadCount = visible.count { it.bookmark && it.read }.toLong(),
    )
}
// Azit <--
