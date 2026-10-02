package com.vibethroughcode.ftree.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vibethroughcode.ftree.FTreeApplication
import com.vibethroughcode.ftree.MainActivity
import com.vibethroughcode.ftree.data.Person
import com.vibethroughcode.ftree.kutumb.Bip340Scheme
import com.vibethroughcode.ftree.kutumb.LocalIdentity
import com.vibethroughcode.ftree.kutumb.game.Fact
import com.vibethroughcode.ftree.kutumb.game.FactAnswer
import com.vibethroughcode.ftree.ui.facts.FactsPendingTag
import com.vibethroughcode.ftree.ui.facts.ResolveConfirmTag
import com.vibethroughcode.ftree.ui.facts.ResolveDoneTag
import com.vibethroughcode.ftree.ui.facts.ResolveWinnerTag
import com.vibethroughcode.ftree.ui.facts.resolveAnswerTag
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NetworkResolveTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val app: FTreeApplication
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FTreeApplication

    private val kutumb get() = app.container.kutumbRepository
    private lateinit var me: Person
    private lateinit var devika: Person

    @Before
    fun seed() {
        app.container.database.clearAllTables()
        runBlocking {
            kutumb.clearAll()
            me = app.container.familyRepository.addPerson(Person(name = "Kiran Rao"))
            devika = app.container.familyRepository.addPerson(Person(name = "Devika Nair"))
            kutumb.setIdentity(LocalIdentity(me.id, Bip340Scheme.generateKeyPair()))
            kutumb.saveFact(Fact("${me.id}/food.pizza", me.id, "food", "food.pizza"))
            kutumb.saveAnswer(FactAnswer("a1", "${me.id}/food.pizza", devika.id, "Pepperoni", false, 10))
            kutumb.saveAnswer(FactAnswer("a2", "${me.id}/food.pizza", me.id, "Margherita", true, 20))
        }
    }

    private fun openResolveFromFacts() {
        // The question may already have come up by itself, if the app finished opening after the
        // seeding above; otherwise it is one tap from the Facts tab.
        rule.waitUntil(10_000) {
            rule.onAllNodesWithTag(NavFactsTag).fetchSemanticsNodes().isNotEmpty() ||
                rule.onAllNodesWithTag(resolveAnswerTag("a1")).fetchSemanticsNodes().isNotEmpty()
        }
        if (rule.onAllNodesWithTag(resolveAnswerTag("a1")).fetchSemanticsNodes().isEmpty()) {
            rule.onNodeWithTag(NavFactsTag).performClick()
            rule.waitUntil(5_000) { rule.onAllNodesWithTag(FactsPendingTag).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag(FactsPendingTag).performClick()
        }
        rule.waitUntil(5_000) { rule.onAllNodesWithTag(resolveAnswerTag("a1")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun ownerPicksTheRightAnswerAndTheAnswererGetsThePoint() {
        openResolveFromFacts()
        rule.onNodeWithTag(ResolveConfirmTag).assertIsNotEnabled()
        rule.onNodeWithTag(resolveAnswerTag("a2")).assertIsDisplayed()

        rule.onNodeWithTag(resolveAnswerTag("a1")).performClick()
        rule.onNodeWithTag(ResolveWinnerTag).assertIsDisplayed()
        rule.onNodeWithTag(ResolveConfirmTag).assertIsEnabled().performClick()

        rule.waitUntil(5_000) { rule.onAllNodesWithTag(ResolveDoneTag).fetchSemanticsNodes().isNotEmpty() }
        val entry = runBlocking { kutumb.observeLedger().first().single() }
        assertEquals(devika.id, entry.personId)
        assertEquals(1, entry.points)
    }

    @Test
    fun aQuestionWaitingForTheOwnerComesUpWhenTheAppOpens() {
        rule.activityRule.scenario.recreate()
        rule.waitUntil(10_000) { rule.onAllNodesWithTag(resolveAnswerTag("a1")).fetchSemanticsNodes().isNotEmpty() }
    }
}
