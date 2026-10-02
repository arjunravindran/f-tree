package com.vibethroughcode.ftree.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vibethroughcode.ftree.FTreeApplication
import com.vibethroughcode.ftree.MainActivity
import com.vibethroughcode.ftree.data.Person
import com.vibethroughcode.ftree.kutumb.Bip340Scheme
import com.vibethroughcode.ftree.kutumb.LocalIdentity
import com.vibethroughcode.ftree.kutumb.trust.PairingMethod
import com.vibethroughcode.ftree.kutumb.trust.TrustedContact
import com.vibethroughcode.ftree.ui.facts.FactsAnswerTag
import com.vibethroughcode.ftree.ui.facts.FactsNeedIdentityTag
import com.vibethroughcode.ftree.ui.facts.FactsPointsTag
import com.vibethroughcode.ftree.ui.facts.FactsQuestionTag
import com.vibethroughcode.ftree.ui.facts.FactsSubmitTag
import com.vibethroughcode.ftree.ui.facts.FactsTabGuessTag
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NetworkFactsTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val app: FTreeApplication
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FTreeApplication

    private val kutumb get() = app.container.kutumbRepository
    private lateinit var me: Person
    private lateinit var kiran: Person

    @Before
    fun seed() {
        app.container.database.clearAllTables()
        runBlocking {
            kutumb.clearAll()
            me = app.container.familyRepository.addPerson(Person(name = "Yash Rao"))
            kiran = app.container.familyRepository.addPerson(Person(name = "Kiran Rao"))
        }
    }

    private fun identify() = runBlocking {
        kutumb.setIdentity(LocalIdentity(me.id, Bip340Scheme.generateKeyPair()))
        kutumb.saveContact(TrustedContact(kiran.id, Bip340Scheme.generateKeyPair().publicKey, pairedAt = 1, pairingMethod = PairingMethod.DIRECT))
    }

    private fun openFacts() {
        rule.onNodeWithTag(NavFactsTag).performClick()
    }

    @Test
    fun withoutSayingWhoYouAreThereIsNothingToAnswerAs() {
        openFacts()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag(FactsNeedIdentityTag).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun answeringAboutMyselfSavesItAndMovesOn() {
        identify()
        openFacts()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag(FactsQuestionTag).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag(FactsPointsTag).assertIsDisplayed()
        rule.onNodeWithTag(FactsSubmitTag).assertIsNotEnabled()

        rule.onNodeWithTag(FactsAnswerTag).performTextInput("Pepperoni")
        rule.onNodeWithTag(FactsSubmitTag).assertIsEnabled().performClick()

        rule.waitUntil(5_000) { runBlocking { kutumb.observeAnswers().first().isNotEmpty() } }
        val answer = runBlocking { kutumb.observeAnswers().first().single() }
        assertEquals("Pepperoni", answer.answerText)
        assertEquals(me.id, answer.answererId)
        assertEquals(true, answer.isSelfReported)
    }

    @Test
    fun guessingAboutARelativeIsKeptAsAGuessOnTheirFact() {
        identify()
        openFacts()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag(FactsTabGuessTag).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag(FactsTabGuessTag).performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag(FactsAnswerTag).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag(FactsAnswerTag).performTextInput("Mushroom")
        rule.onNodeWithTag(FactsSubmitTag).performClick()

        rule.waitUntil(5_000) { runBlocking { kutumb.observeAnswers().first().isNotEmpty() } }
        val answer = runBlocking { kutumb.observeAnswers().first().single() }
        assertFalse(answer.isSelfReported)
        assertEquals(kiran.id, runBlocking { kutumb.observeFacts().first().single().personId })
    }
}
