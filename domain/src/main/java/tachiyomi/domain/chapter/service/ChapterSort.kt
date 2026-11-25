package tachiyomi.domain.chapter.service

import tachiyomi.core.common.util.lang.compareToWithCollator
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

fun getChapterSort(
    manga: Manga,
    sortDescending: Boolean = manga.sortDescending(),
    // Azit -->
    /** The number "by chapter number" sorts on: an edited chapter (Chapter edition) uses the number given to it. */
    numberOf: (Chapter) -> Double = { it.chapterNumber },
    // Azit <--
): (
    Chapter,
    Chapter,
) -> Int {
    return when (manga.sorting) {
        Manga.CHAPTER_SORTING_SOURCE -> when (sortDescending) {
            true -> { c1, c2 -> c1.sourceOrder.compareTo(c2.sourceOrder) }
            false -> { c1, c2 -> c2.sourceOrder.compareTo(c1.sourceOrder) }
        }
        Manga.CHAPTER_SORTING_NUMBER -> when (sortDescending) {
            // Azit -->
            true -> { c1, c2 -> numberOf(c2).compareTo(numberOf(c1)) }
            false -> { c1, c2 -> numberOf(c1).compareTo(numberOf(c2)) }
            // Azit <--
        }
        Manga.CHAPTER_SORTING_UPLOAD_DATE -> when (sortDescending) {
            true -> { c1, c2 -> c2.dateUpload.compareTo(c1.dateUpload) }
            false -> { c1, c2 -> c1.dateUpload.compareTo(c2.dateUpload) }
        }
        Manga.CHAPTER_SORTING_ALPHABET -> when (sortDescending) {
            true -> { c1, c2 -> c2.name.compareToWithCollator(c1.name) }
            false -> { c1, c2 -> c1.name.compareToWithCollator(c2.name) }
        }
        else -> throw NotImplementedError("Invalid chapter sorting method: ${manga.sorting}")
    }
}
