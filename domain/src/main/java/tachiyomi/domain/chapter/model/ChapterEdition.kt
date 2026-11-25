package tachiyomi.domain.chapter.model

import kotlin.math.floor
import kotlin.math.round

// Azit -->
/**
 * A chapter counted as something else (selection menu -> Chapter edition): another chapter number, or a volume
 * holding one chapter ([start]) or several ([start]-[end]). The chapter keeps its name; trackers get [trackNumber].
 */
data class ChapterEdition(
    val chapterId: Long,
    val isVolume: Boolean,
    val start: Double,
    val end: Double? = null,
) {
    /** What trackers receive when this chapter is read: always the last number given. */
    val trackNumber: Double get() = end ?: start

    /** "3", or "2-4" for a volume holding several chapters. */
    val numberText: String get() = formatNumber(start) + (end?.let { "-${formatNumber(it)}" } ?: "")

    companion object {
        private val NUMBER = Regex("""^\d+(?:[.,]\d+)?$""")
        private val RANGE = Regex("""^(\d+(?:[.,]\d+)?)\s*-\s*(\d+(?:[.,]\d+)?)$""")

        private fun number(text: String): Double? = text.replace(',', '.').toDoubleOrNull()

        /**
         * Reads what was typed: a chapter takes one number ("3"), a volume one or a range ("2" or "2-4").
         * Null when it can't be read (or a range ends before it starts).
         */
        fun parse(chapterId: Long, isVolume: Boolean, text: String): ChapterEdition? {
            val typed = text.trim()
            if (NUMBER.matches(typed)) {
                return ChapterEdition(chapterId, isVolume, number(typed) ?: return null)
            }
            if (!isVolume) return null
            val range = RANGE.matchEntire(typed) ?: return null
            val start = number(range.groupValues[1]) ?: return null
            val end = number(range.groupValues[2]) ?: return null
            return when {
                end < start -> null
                end == start -> ChapterEdition(chapterId, true, start)
                else -> ChapterEdition(chapterId, true, start, end)
            }
        }

        /** 3.0 -> "3", 10.5 -> "10.5". */
        fun formatNumber(value: Double): String {
            return if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
        }
    }
}

/** One chapter in the Chapter edition dialog: counted as a chapter or a volume, and the number typed. */
data class ChapterEditionRow(
    val chapterId: Long,
    val isVolume: Boolean = false,
    val text: String = "",
    /** Typed by hand (autofill leaves it alone). */
    val typed: Boolean = false,
    /** The chapter's own number (from the source), when it has one: autofill keeps its decimal part (10.5 -> 20.5). */
    val originalNumber: Double? = null,
) {
    val edition: ChapterEdition? get() = ChapterEdition.parse(chapterId, isVolume, text)

    /** Something typed that can't be read. */
    val isInvalid: Boolean get() = text.isNotBlank() && edition == null

    companion object {
        fun from(chapterId: Long, edition: ChapterEdition?, originalNumber: Double? = null): ChapterEditionRow {
            return ChapterEditionRow(
                chapterId = chapterId,
                isVolume = edition?.isVolume ?: false,
                text = edition?.numberText.orEmpty(),
                typed = edition != null,
                originalNumber = originalNumber?.takeIf { it >= 0 },
            )
        }
    }
}

/**
 * [text] typed in the row at [index]. When it is a chapter number, the chapters after it that weren't typed by hand
 * take the next numbers (3 -> 4, 5, 6...); volumes are skipped, a chapter typed by hand stops it. A chapter whose own
 * number has a decimal part (a part: 10.5, 71.2) keeps it, the whole number following the source's:
 * 10, 10.5, 11 with 20 typed -> 20, 20.5, 21; 71.1, 71.2, 72.1, 72.2 with 1 typed -> 1, 1.2, 2.1, 2.2.
 */
fun List<ChapterEditionRow>.typedAt(index: Int, text: String): List<ChapterEditionRow> {
    val rows = toMutableList()
    val row = rows.getOrNull(index) ?: return this
    rows[index] = row.copy(text = text, typed = text.isNotBlank())
    val edition = rows[index].edition
    if (row.isVolume || edition == null) return rows
    var last = edition.start
    var previousOwn = row.originalNumber
    for (i in index + 1 until rows.size) {
        val after = rows[i]
        if (after.isVolume) continue
        if (after.typed) break
        val own = after.originalNumber
        last = when {
            // No number of its own: one more than the previous (10.5 -> 11.5).
            own == null -> last + 1
            // A part (71.2, 72.1): its decimal part kept; the whole number goes up when the source's does (71 -> 72).
            own % 1.0 != 0.0 -> {
                val sameChapter = previousOwn != null && floor(own) == floor(previousOwn)
                ((if (sameChapter) floor(last) else floor(last) + 1) + (own - floor(own))).roundTo4()
            }
            // A whole chapter: the next whole number.
            else -> floor(last) + 1
        }
        previousOwn = own
        rows[i] = after.copy(text = ChapterEdition.formatNumber(last))
    }
    return rows
}

/** 5.1000000000000005 -> 5.1 (the decimal part of a chapter number, without floating point noise). */
private fun Double.roundTo4(): Double = round(this * 10_000) / 10_000

/** Every row counted as a volume ([volume]) or as a chapter. */
fun List<ChapterEditionRow>.allVolumes(volume: Boolean): List<ChapterEditionRow> = map { it.copy(isVolume = volume) }

/** What the dialog saves: the editions to keep, and the chapters left blank (their edition is removed). */
fun List<ChapterEditionRow>.toChanges(): Pair<List<ChapterEdition>, List<Long>> {
    val editions = mapNotNull { it.edition }
    val cleared = filter { it.text.isBlank() }.map { it.chapterId }
    return editions to cleared
}

/** The first number of a chapter: the one given in Chapter edition, or its own [chapterNumber]. */
fun ChapterEdition?.firstNumber(chapterNumber: Double): Double = this?.start ?: chapterNumber

/** The last number of a chapter: the last one given in Chapter edition (a volume's range end), or its own. */
fun ChapterEdition?.lastNumber(chapterNumber: Double): Double = this?.trackNumber ?: chapterNumber

/**
 * The whole chapter numbers a chapter covers: every number of an edited volume's range (2-4 -> 2, 3, 4), the number
 * given in Chapter edition, or its own [chapterNumber] (-1, unknown, when it has none).
 */
fun ChapterEdition?.coveredNumbers(chapterNumber: Double): List<Double> {
    if (this == null) return listOf(chapterNumber)
    val first = floor(start).toLong()
    val last = floor(trackNumber).toLong()
    return (first..last).map(Long::toDouble)
}

/**
 * Chapters missing between two chapters, in whichever order they come: the numbers after the lower one's last
 * number and before the higher one's first number. 0 when either number is unknown or they overlap.
 */
fun missingBetween(first1: Double, last1: Double, first2: Double, last2: Double): Int {
    if (first1 < 0.0 || first2 < 0.0) return 0
    val (lowerLast, higherFirst) = if (first1 <= first2) last1 to first2 else last2 to first1
    return (floor(higherFirst).toInt() - floor(lowerLast).toInt() - 1).coerceAtLeast(0)
}

/** The chapters as trackers see them: an edited chapter's number becomes the last number given. */
fun List<Chapter>.withEditedNumbers(editions: Map<Long, ChapterEdition>): List<Chapter> {
    if (editions.isEmpty()) return this
    return map { chapter -> editions[chapter.id]?.let { chapter.copy(chapterNumber = it.trackNumber) } ?: chapter }
}
// Azit <--
