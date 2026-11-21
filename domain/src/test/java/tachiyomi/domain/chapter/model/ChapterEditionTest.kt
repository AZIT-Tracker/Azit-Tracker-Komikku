package tachiyomi.domain.chapter.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

// Azit -->
@Execution(ExecutionMode.CONCURRENT)
class ChapterEditionTest {

    private fun rows(count: Int) = (1..count).map { ChapterEditionRow(chapterId = it.toLong()) }

    private val List<ChapterEditionRow>.texts get() = map { it.text }

    // Reading what was typed

    @Test
    fun `a chapter takes one number`() {
        ChapterEdition.parse(1, isVolume = false, text = "3") shouldBe ChapterEdition(1, false, 3.0)
        ChapterEdition.parse(1, isVolume = false, text = " 10.5 ") shouldBe ChapterEdition(1, false, 10.5)
        ChapterEdition.parse(1, isVolume = false, text = "10,5") shouldBe ChapterEdition(1, false, 10.5)
    }

    @Test
    fun `a chapter can't be a range or text`() {
        ChapterEdition.parse(1, isVolume = false, text = "2-4") shouldBe null
        ChapterEdition.parse(1, isVolume = false, text = "abc") shouldBe null
        ChapterEdition.parse(1, isVolume = false, text = "") shouldBe null
        ChapterEdition.parse(1, isVolume = false, text = "-3") shouldBe null
    }

    @Test
    fun `a volume takes one number or a range`() {
        ChapterEdition.parse(1, isVolume = true, text = "2") shouldBe ChapterEdition(1, true, 2.0)
        ChapterEdition.parse(1, isVolume = true, text = "2-4") shouldBe ChapterEdition(1, true, 2.0, 4.0)
        ChapterEdition.parse(1, isVolume = true, text = "2 - 4") shouldBe ChapterEdition(1, true, 2.0, 4.0)
    }

    @Test
    fun `a range ending before it starts is refused, the same number twice is one number`() {
        ChapterEdition.parse(1, isVolume = true, text = "4-2") shouldBe null
        ChapterEdition.parse(1, isVolume = true, text = "3-3") shouldBe ChapterEdition(1, true, 3.0)
    }

    // What trackers get

    @Test
    fun `trackers always get the last number given`() {
        ChapterEdition(1, false, 7.0).trackNumber shouldBe 7.0
        ChapterEdition(1, true, 2.0).trackNumber shouldBe 2.0
        ChapterEdition(1, true, 2.0, 4.0).trackNumber shouldBe 4.0
    }

    @Test
    fun `numbers are written back without a useless decimal`() {
        ChapterEdition(1, true, 2.0, 4.0).numberText shouldBe "2-4"
        ChapterEdition(1, false, 10.5).numberText shouldBe "10.5"
        ChapterEdition(1, false, 3.0).numberText shouldBe "3"
    }

    @Test
    fun `edited chapters take their last number, the others keep theirs`() {
        val chapters = (1..3).map { Chapter.create().copy(id = it.toLong(), chapterNumber = it.toDouble(), name = "Ch $it") }
        val edited = chapters.withEditedNumbers(mapOf(2L to ChapterEdition(2, true, 5.0, 8.0)))

        edited.map { it.chapterNumber } shouldBe listOf(1.0, 8.0, 3.0)
        edited.map { it.name } shouldBe listOf("Ch 1", "Ch 2", "Ch 3")
    }

    // The dialog: autofill

    @Test
    fun `a chapter number fills the chapters after it`() {
        rows(5).typedAt(0, "3").texts shouldBe listOf("3", "4", "5", "6", "7")
    }

    @Test
    fun `the chapters before the one typed are left alone`() {
        rows(4).typedAt(2, "10").texts shouldBe listOf("", "", "10", "11")
    }

    @Test
    fun `typing again in the middle refills what follows`() {
        rows(5).typedAt(0, "3").typedAt(2, "20").texts shouldBe listOf("3", "4", "20", "21", "22")
    }

    @Test
    fun `volumes are skipped by the autofill`() {
        val withVolume = rows(4).toMutableList().also { it[1] = it[1].copy(isVolume = true) }
        withVolume.typedAt(0, "3").texts shouldBe listOf("3", "", "4", "5")
    }

    @Test
    fun `a chapter typed by hand stops the autofill`() {
        rows(5).typedAt(3, "50").typedAt(0, "1").texts shouldBe listOf("1", "2", "3", "50", "51")
    }

    @Test
    fun `a volume number fills nothing`() {
        val volumes = rows(3).allVolumes(true)
        volumes.typedAt(0, "1-3").texts shouldBe listOf("1-3", "", "")
    }

    @Test
    fun `something that can't be read fills nothing and is flagged`() {
        val typed = rows(3).typedAt(0, "abc")
        typed.texts shouldBe listOf("abc", "", "")
        typed[0].isInvalid shouldBe true
    }

    @Test
    fun `decimal chapters fill with the next numbers`() {
        rows(3).typedAt(0, "10.5").texts shouldBe listOf("10.5", "11.5", "12.5")
    }

    // The dialog: all volumes, saving

    @Test
    fun `the top option makes them all volumes or all chapters`() {
        rows(3).allVolumes(true).all { it.isVolume } shouldBe true
        rows(3).allVolumes(true).allVolumes(false).none { it.isVolume } shouldBe true
    }

    @Test
    fun `saving keeps what was given and clears what was left blank`() {
        val typed = rows(3).typedAt(0, "3").toMutableList()
        typed[2] = typed[2].copy(text = "")

        val (editions, cleared) = typed.toChanges()

        editions shouldBe listOf(ChapterEdition(1, false, 3.0), ChapterEdition(2, false, 4.0))
        cleared shouldBe listOf(3L)
    }

    @Test
    fun `an existing edition opens filled in`() {
        val row = ChapterEditionRow.from(7, ChapterEdition(7, true, 2.0, 4.0))

        row.isVolume shouldBe true
        row.text shouldBe "2-4"
        row.typed shouldBe true
        ChapterEditionRow.from(8, null) shouldBe ChapterEditionRow(chapterId = 8)
    }
}
// Azit <--
