package tachiyomi.domain.track.service

import kotlin.math.ceil

// Azit -->
/**
 * The chapter count to give a tracker that doesn't know it, when [chapterRead] reaches the last chapter of a series
 * that is over ([seriesFinished]): with it, the tracker marks the entry completed. Null otherwise (ongoing series,
 * not the last chapter, or no chapter numbers).
 *
 * [chapterNumbers] are the series' chapter numbers as trackers see them (chapter editions applied).
 */
fun finishedSeriesTotal(seriesFinished: Boolean, chapterNumbers: List<Double>, chapterRead: Double): Long? {
    if (!seriesFinished) return null
    val last = chapterNumbers.filter { it >= 0 }.maxOrNull() ?: return null
    if (last <= 0 || chapterRead < last) return null
    return ceil(last).toLong()
}

/** [totalChapters] as a tracker should get it: kept when known, else [finishedTotal] (null: still unknown). */
fun totalWithFinished(totalChapters: Long, finishedTotal: Long?): Long {
    return if (totalChapters <= 0 && finishedTotal != null) finishedTotal else totalChapters
}
// Azit <--
