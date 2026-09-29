package com.vibethroughcode.ftree.ui

import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vibethroughcode.ftree.FTreeApplication
import com.vibethroughcode.ftree.MainActivity
import com.vibethroughcode.ftree.data.Person
import com.vibethroughcode.ftree.data.RelativeKind
import com.vibethroughcode.ftree.ui.book.BookFeaturedCancelTag
import com.vibethroughcode.ftree.ui.book.BookFeaturedListTag
import com.vibethroughcode.ftree.ui.book.BookFeaturedResetTag
import com.vibethroughcode.ftree.ui.book.BookFeaturedRowTag
import com.vibethroughcode.ftree.ui.book.BookFeaturedSearchTag
import com.vibethroughcode.ftree.ui.book.BookLivingDatesTag
import com.vibethroughcode.ftree.ui.book.BookNotesTag
import com.vibethroughcode.ftree.ui.book.BookPageLabelTag
import com.vibethroughcode.ftree.ui.book.BookPreviewTag
import com.vibethroughcode.ftree.ui.book.BookScreenTag
import com.vibethroughcode.ftree.ui.book.BookShareTag
import com.vibethroughcode.ftree.ui.book.BookTitleFieldTag
import com.vibethroughcode.ftree.ui.tree.TreeBookFromTag
import com.vibethroughcode.ftree.ui.tree.TreeBookTag
import com.vibethroughcode.ftree.ui.tree.TreeModeCompactTag
import com.vibethroughcode.ftree.ui.tree.CompactFocusTag
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The family book from the reader's side: the chart's book button, the screen, and a share.
 *
 * The share sheet itself belongs to the system and is not driven here; what is asserted is what
 * this app is responsible for - a PDF with the family's own name, written where the share sheet is
 * handed it, and a chooser asked to send it.
 */
@RunWith(AndroidJUnit4::class)
class BookFlowTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app: FTreeApplication get() = instrumentation.targetContext.applicationContext as FTreeApplication
    private var monitor: Instrumentation.ActivityMonitor? = null

    @Before
    fun seed() {
        app.container.database.clearAllTables()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Build your family tree").fetchSemanticsNodes().isNotEmpty() }
        val repository = app.container.familyRepository
        val shyam = Person(name = "Shyam Sharma", birthDate = "1930", deathDate = "2001")
        val kamla = Person(name = "Kamla Sharma", birthDate = "1934", deathDate = "2010")
        val vinod = Person(name = "Vinod Sharma", birthDate = "1962-07-09")
        val sunita = Person(name = "सुनीता शर्मा", birthDate = "1965")
        val aarav = Person(name = "Aarav Sharma", birthDate = "1990-04-17")
        runBlocking {
            listOf(shyam, kamla, vinod, sunita, aarav).forEach { repository.addPerson(it) }
            repository.addRelative(vinod.id, shyam.id, RelativeKind.PARENT)
            repository.addRelative(vinod.id, kamla.id, RelativeKind.PARENT)
            repository.addRelative(shyam.id, kamla.id, RelativeKind.SPOUSE)
            repository.addRelative(vinod.id, sunita.id, RelativeKind.SPOUSE)
            repository.addRelative(aarav.id, vinod.id, RelativeKind.PARENT)
            repository.addRelative(aarav.id, sunita.id, RelativeKind.PARENT)
        }
        // Catch the share chooser so it doesn't cover the next test, and so its arrival can be seen.
        monitor = instrumentation.addMonitor(IntentFilter(Intent.ACTION_CHOOSER), null, true)
    }

    @After
    fun removeMonitor() {
        monitor?.let(instrumentation::removeMonitor)
    }

    private fun waitForBook() {
        rule.waitUntil(30_000) { rule.onAllNodesWithTag(BookPageLabelTag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun theChartOpensTheBookAndSharesAPdfNamedForTheFamily() {
        rule.waitUntil(10_000) { rule.onAllNodesWithTag(TreeBookTag).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag(TreeBookTag).performClick()
        rule.onNodeWithTag(BookScreenTag).assertExists()
        waitForBook()
        rule.onNodeWithTag(BookPreviewTag).assertExists()
        rule.onNode(hasTestTag(BookTitleFieldTag) and hasText("The Sharma Family", substring = true)).assertExists()
        rule.onNodeWithText("Page 1 of", substring = true, useUnmergedTree = true).assertExists()

        rule.onNodeWithTag(BookShareTag).assertIsEnabled().performClick()
        rule.waitUntil(30_000) { (monitor?.hits ?: 0) > 0 }
        val shared = File(app.cacheDir, "shared").listFiles().orEmpty()
        assertTrue("shared ${shared.map { it.name }}", shared.any { it.name == "The Sharma Family Book.pdf" && it.length() > 10_000 })
    }

    @Test
    fun aPersonsSheetOffersABookOfTheirBranch() {
        // The compact view is the one a test can tap a person in; the chart is a canvas.
        rule.waitUntil(10_000) { rule.onAllNodesWithTag(TreeModeCompactTag).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag(TreeModeCompactTag).performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithTag(CompactFocusTag).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag(CompactFocusTag).performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag(TreeBookFromTag).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag(TreeBookFromTag).performClick()
        waitForBook()
        rule.onNodeWithText("'s branch", substring = true).assertExists()
        rule.onNodeWithText("Everyone").assertExists()
    }

    @Test
    fun livingDatesAreOffUntilTheReaderTurnsThemOn() {
        rule.waitUntil(10_000) { rule.onAllNodesWithTag(TreeBookTag).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag(TreeBookTag).performClick()
        waitForBook()
        rule.onNodeWithTag(BookLivingDatesTag).performScrollTo().assertIsOff().performClick()
        rule.onNodeWithTag(BookLivingDatesTag).assertIsOn()
    }

    @Test
    fun includingNotesIsOffUntilTheReaderTurnsItOn() {
        rule.waitUntil(10_000) { rule.onAllNodesWithTag(TreeBookTag).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag(TreeBookTag).performClick()
        waitForBook()
        rule.onNodeWithTag(BookNotesTag).performScrollTo().assertIsOff().performClick()
        rule.onNodeWithTag(BookNotesTag).assertIsOn()
    }

    /**
     * The shared `PersonPicker` (#248), reached from "Whose story": picking somebody replaces the
     * row's own wording with their name and offers a way back, and reset returns to the composer's
     * own choice rather than leaving nobody picked.
     *
     * The row merges its own semantics (one TalkBack announcement, not "avatar" then "name" then
     * "opens a picker" as three) so its tag and its text sit on the *same* semantics node once
     * something is chosen, not on a text node somewhere underneath it - unlike a picker's own list
     * rows, which stay separate nodes under `BookFeaturedListTag`.
     */
    @Test
    fun whoseStoryCanBePickedAndReset() {
        rule.waitUntil(10_000) { rule.onAllNodesWithTag(TreeBookTag).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag(TreeBookTag).performClick()
        waitForBook()

        rule.onNodeWithTag(BookFeaturedRowTag).performScrollTo().performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag(BookFeaturedSearchTag).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag(BookFeaturedSearchTag).performTextInput("Aarav")
        val row = hasText("Aarav Sharma") and hasAnyAncestor(hasTestTag(BookFeaturedListTag))
        rule.waitUntil(5_000) { rule.onAllNodes(row).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(row).performClick()

        val chosen = hasTestTag(BookFeaturedRowTag) and hasText("Aarav Sharma")
        rule.waitUntil(5_000) { rule.onAllNodes(chosen).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag(BookFeaturedResetTag).assertExists()

        rule.onNodeWithTag(BookFeaturedResetTag).performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(chosen).fetchSemanticsNodes().isEmpty() }
        rule.onAllNodesWithTag(BookFeaturedResetTag).assertCountEquals(0)
    }

    /**
     * Cancelling the picker without choosing anybody leaves the row exactly as it was.
     *
     * Dismissed here through the picker's own close button rather than the back gesture:
     * `ModalBottomSheet` opens in its own window, and on the emulator Espresso's `pressBack()` can
     * race that window's entrance animation for focus (`RootViewWithoutFocusException`) even after
     * the sheet's content is in the semantics tree. The close button reaches the same
     * `onCancel` this screen wires to the back gesture in production, without that race.
     */
    @Test
    fun cancellingWhoseStoryLeavesTheChoiceAlone() {
        rule.waitUntil(10_000) { rule.onAllNodesWithTag(TreeBookTag).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag(TreeBookTag).performClick()
        waitForBook()

        rule.onNodeWithTag(BookFeaturedRowTag).performScrollTo().performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag(BookFeaturedSearchTag).fetchSemanticsNodes().isNotEmpty() }

        rule.onNodeWithTag(BookFeaturedCancelTag).performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag(BookFeaturedSearchTag).fetchSemanticsNodes().isEmpty() }
        rule.onAllNodesWithTag(BookFeaturedResetTag).assertCountEquals(0)
    }

    /*
     * The flow that held the "Heirloom doesn't feature one person yet." hint on a device lived here
     * until v0.11.0-beta.2, when Heirloom became a download (#214) and no format-1 template shipped
     * for it to run against. The decision behind the hint is not untested: `BookOptionsTest` holds
     * `featuresOnePerson` to the real `heirloom.json`, read from `site/`, which is where that answer
     * is actually made. Restore this test if a format-1 template ever ships again.
     */

    /**
     * #260: the other half of that, on the template that actually ships the storybook.
     *
     * Until #259 swapped it in, no shipped template answered `featuresOnePerson`, so the only thing
     * this flow could be held to was the hint. Diwali is format 2 now, so the hint must be *gone*
     * there and the picker must really choose somebody - which is the difference between "Whose
     * story" being a live control and being an explained no-op.
     *
     * Diwali is selected by name rather than relied on as the default: it is the featured template
     * only inside its own season (2026-10-18 to 11-15, `catalog.json`), and this test has to pass
     * on either side of that date.
     */
    @Test
    fun theStorybookReallyFeaturesOnePerson() {
        rule.waitUntil(10_000) { rule.onAllNodesWithTag(TreeBookTag).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag(TreeBookTag).performClick()
        waitForBook()

        rule.onNodeWithText("Diwali").performClick()
        waitForBook()
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText("Heirloom doesn't feature one person yet.").fetchSemanticsNodes().isEmpty()
        }

        rule.onNodeWithTag(BookFeaturedRowTag).performScrollTo().performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag(BookFeaturedSearchTag).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag(BookFeaturedSearchTag).performTextInput("Aarav")
        val row = hasText("Aarav Sharma") and hasAnyAncestor(hasTestTag(BookFeaturedListTag))
        rule.waitUntil(5_000) { rule.onAllNodes(row).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(row).performClick()

        val chosen = hasTestTag(BookFeaturedRowTag) and hasText("Aarav Sharma")
        rule.waitUntil(5_000) { rule.onAllNodes(chosen).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag(BookFeaturedResetTag).assertExists()
    }
}
