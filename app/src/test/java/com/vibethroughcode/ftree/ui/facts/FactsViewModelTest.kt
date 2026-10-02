package com.vibethroughcode.ftree.ui.facts

import com.vibethroughcode.ftree.data.Person
import com.vibethroughcode.ftree.kutumb.Bip340Scheme
import com.vibethroughcode.ftree.kutumb.InMemoryKutumbRepository
import com.vibethroughcode.ftree.kutumb.LocalIdentity
import com.vibethroughcode.ftree.kutumb.game.LedgerEntry
import com.vibethroughcode.ftree.kutumb.game.QuestionBank
import com.vibethroughcode.ftree.kutumb.trust.PairingMethod
import com.vibethroughcode.ftree.kutumb.trust.TrustedContact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FactsViewModelTest {

    private val me = Person(id = "me", name = "Me")
    private val kiran = Person(id = "kiran", name = "Kiran")
    private val kutumb = InMemoryKutumbRepository()
    private var ids = 0

    @Before fun setMain() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun resetMain() = Dispatchers.resetMain()

    private suspend fun TestScope.viewModel(): FactsViewModel {
        kutumb.setIdentity(LocalIdentity("me", Bip340Scheme.generateKeyPair()))
        kutumb.saveContact(TrustedContact("kiran", Bip340Scheme.generateKeyPair().publicKey, pairedAt = 1, pairingMethod = PairingMethod.DIRECT))
        return FactsViewModel(kutumb, MutableStateFlow(listOf(me, kiran)), clock = { 50 }, newId = { "a${ids++}" })
            .also { it.state.launchIn(backgroundScope) }
    }

    /** The state once everything queued on the test scheduler has run. */
    private fun TestScope.current(vm: FactsViewModel): FactsUiState {
        runCurrent()
        return vm.state.value
    }

    @Test
    fun answeringAboutMyselfRecordsASelfReportOnMyOwnFact() = runTest {
        val vm = viewModel()
        runCurrent()
        vm.onAnswerChange("  Pepperoni "); runCurrent()
        runCurrent()
        vm.submit(); runCurrent()

        val answer = kutumb.observeAnswers().first().single()
        assertEquals("Pepperoni", answer.answerText)
        assertTrue(answer.isSelfReported)
        assertEquals("me", answer.answererId)
        val fact = kutumb.observeFacts().first().single()
        assertEquals("me", fact.personId)
        assertEquals(QuestionBank.factId("me", QuestionBank.all[0].id), fact.id)
    }

    @Test
    fun guessingAboutKiranIsAGuessOnKiransFact() = runTest {
        val vm = viewModel()
        vm.setMode(FactsMode.GUESS); runCurrent()
        assertEquals("kiran", current(vm).subject?.id)
        vm.onAnswerChange("Mushroom"); runCurrent()
        vm.submit(); runCurrent()

        val answer = kutumb.observeAnswers().first().single()
        assertFalse(answer.isSelfReported)
        assertEquals("me", answer.answererId)
        assertEquals("kiran", kutumb.observeFacts().first().single().personId)
    }

    @Test
    fun aSecondAnswerToTheSameFactIsKeptAlongsideTheFirst() = runTest {
        val vm = viewModel()
        vm.setMode(FactsMode.GUESS); runCurrent()
        vm.onAnswerChange("Mushroom"); vm.submit(); runCurrent()
        // Back to the same question about the same person.
        repeat(QuestionBank.all.size - 1) { vm.nextQuestion() }
        vm.onAnswerChange("Pepperoni"); vm.submit(); runCurrent()

        assertEquals(listOf("Mushroom", "Pepperoni"), kutumb.observeAnswers().first().map { it.answerText })
        assertEquals(1, kutumb.observeFacts().first().size)
    }

    @Test
    fun aBlankAnswerIsNotSubmitted() = runTest {
        val vm = viewModel()
        vm.onAnswerChange("   "); runCurrent()
        assertFalse(current(vm).canSubmit)
        vm.submit(); runCurrent()
        assertTrue(kutumb.observeAnswers().first().isEmpty())
    }

    @Test
    fun submittingMovesOnToTheNextQuestionAndSaysSo() = runTest {
        val vm = viewModel()
        val before = current(vm).question
        vm.onAnswerChange("x"); vm.submit(); runCurrent()
        assertTrue(current(vm).question != before)
        assertTrue(current(vm).justSaved)
        assertEquals("", current(vm).answer)
        vm.savedShown(); runCurrent()
        assertFalse(current(vm).justSaved)
    }

    @Test
    fun pointsAreTheReadersLedgerBalanceAndPendingCardsCountTheirOwnFacts() = runTest {
        val vm = viewModel()
        kutumb.saveResolution(
            com.vibethroughcode.ftree.kutumb.game.FactResolution("kiran/food.pizza", "x", "kiran", 3),
            LedgerEntry("me", 3, "kiran/food.pizza", 1),
        )
        assertEquals(3, current(vm).points)

        // Somebody guesses something about me: it waits for me to pick.
        kutumb.saveFact(com.vibethroughcode.ftree.kutumb.game.Fact("me/food.pizza", "me", "food", "food.pizza"))
        kutumb.saveAnswer(
            com.vibethroughcode.ftree.kutumb.game.FactAnswer("g1", "me/food.pizza", "kiran", "Mushroom", false, 5)
        )
        assertEquals(1, current(vm).pendingCount)
    }

    @Test
    fun withoutAnIdentityThereIsNobodyToAnswerAs() = runTest {
        val vm = FactsViewModel(InMemoryKutumbRepository(), MutableStateFlow(listOf(me)))
        vm.state.launchIn(backgroundScope)
        vm.onAnswerChange("x"); runCurrent()
        vm.submit(); runCurrent()
        assertTrue(current(vm).me == null && !current(vm).canSubmit)
    }
}
