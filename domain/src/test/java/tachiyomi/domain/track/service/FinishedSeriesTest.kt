package tachiyomi.domain.track.service

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

// Azit -->
@Execution(ExecutionMode.CONCURRENT)
class FinishedSeriesTest {

    private val chapters = listOf(1.0, 2.0, 3.0, 12.0)

    @Test
    fun `reading the last chapter of a finished series gives its chapter count`() {
        finishedSeriesTotal(seriesFinished = true, chapters, chapterRead = 12.0) shouldBe 12L
    }

    @Test
    fun `an ongoing series is never finished`() {
        finishedSeriesTotal(seriesFinished = false, chapters, chapterRead = 12.0) shouldBe null
    }

    @Test
    fun `a chapter before the last one is not the end`() {
        finishedSeriesTotal(seriesFinished = true, chapters, chapterRead = 3.0) shouldBe null
    }

    @Test
    fun `unknown chapter numbers are ignored, none at all gives nothing`() {
        finishedSeriesTotal(true, listOf(-1.0, 5.0), chapterRead = 5.0) shouldBe 5L
        finishedSeriesTotal(true, listOf(-1.0), chapterRead = 5.0) shouldBe null
        finishedSeriesTotal(true, emptyList(), chapterRead = 5.0) shouldBe null
    }

    @Test
    fun `a decimal last chapter counts up`() {
        finishedSeriesTotal(true, listOf(1.0, 10.5), chapterRead = 10.5) shouldBe 11L
    }

    @Test
    fun `a chapter count the tracker knows is kept`() {
        totalWithFinished(totalChapters = 50, finishedTotal = 12) shouldBe 50L
        totalWithFinished(totalChapters = 0, finishedTotal = 12) shouldBe 12L
        totalWithFinished(totalChapters = 0, finishedTotal = null) shouldBe 0L
    }
}
// Azit <--
