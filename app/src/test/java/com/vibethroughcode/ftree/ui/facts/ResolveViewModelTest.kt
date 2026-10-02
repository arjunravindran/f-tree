package com.vibethroughcode.ftree.ui.facts

import com.vibethroughcode.ftree.data.Person
import com.vibethroughcode.ftree.kutumb.Bip340Scheme
import com.vibethroughcode.ftree.kutumb.InMemoryKutumbRepository
import com.vibethroughcode.ftree.kutumb.LocalIdentity
import com.vibethroughcode.ftree.kutumb.game.Fact
import com.vibethroughcode.ftree.kutumb.game.FactAnswer
import com.vibethroughcode.ftree.kutumb.game.RedemptionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ResolveViewModelTest {

    private val me = Person(id = "me", name = "Me")
    private val devika = Person(id = "devika", name = "Devika")
    private val asha = Person(id = "asha", name = "Asha")
    private val kutumb = InMemoryKutumbRepository()
    private var ids = 0

    @Before fun setMain() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun resetMain() = Dispatchers.resetMain()

    private suspend fun TestScope.viewModel(): ResolveViewModel {
        kutumb.setIdentity(LocalIdentity("me", Bip340Scheme.generateKeyPair()))
        return ResolveViewModel(kutumb, MutableStateFlow(listOf(me, devika, asha)), clock = { 77 }, newId = { "r${ids++}" })
            .also { it.state.launchIn(backgroundScope); runCurrent() }
    }

    private suspend fun guess(factId: String, id: String, by: String, text: String, at: Long) {
        kutumb.saveFact(Fact(factId, "me", "food", "food.pizza"))
        kutumb.saveAnswer(FactAnswer(id, factId, by, text, isSelfReported = by == "me", submittedAt = at))
    }

    private fun TestScope.current(vm: ResolveViewModel): ResolveUiState { runCurrent(); return vm.state.value }

    @Test
    fun theOldestQuestionAboutMeComesFirstWithEveryAnswerListed() = runTest {
        val vm = viewModel()
        guess("me/b", "b1", "asha", "Mushroom", 20)
        guess("me/a", "a1", "devika", "Pepperoni", 10)
        kutumb.saveAnswer(FactAnswer("a2", "me/a", "me", "Pepperoni", true, 15))

        val ui = current(vm)
        assertEquals("me/a", ui.fact?.id)
        assertEquals(listOf("a1", "a2"), ui.answers.map { it.answer.id })
        assertEquals(1, ui.remaining)
        assertTrue(ui.selected == null)
    }

    @Test
    fun confirmAwardsThePointsToWhoeverGaveTheChosenAnswerAndMovesToTheNextCard() = runTest {
        val vm = viewModel()
        guess("me/a", "a1", "devika", "Pepperoni", 10)
        guess("me/b", "b1", "asha", "Mushroom", 20)
        runCurrent()
        vm.select("a1"); runCurrent()
        vm.confirm(); runCurrent()

        val entry = kutumb.observeLedger().first().single()
        assertEquals("devika", entry.personId)
        assertEquals("me/a", entry.reasonFactId)
        assertEquals("me", kutumb.observeResolutions().first().single().resolvedByPersonId)
        assertEquals("me/b", current(vm).fact?.id)
    }

    @Test
    fun theFifthPointFromMeCreatesACoffeeIOweThem() = runTest {
        val vm = viewModel()
        repeat(5) { i ->
            guess("me/q$i", "g$i", "devika", "x", 10L + i)
            runCurrent()
            vm.select("g$i"); runCurrent()
            vm.confirm(); runCurrent()
            if (i < 4) assertTrue(kutumb.observeRedemptions().first().isEmpty())
        }
        val owed = kutumb.observeRedemptions().first().single()
        assertEquals("me", owed.fromPersonId)
        assertEquals("devika", owed.toPersonId)
        assertEquals(RedemptionStatus.OWED, owed.status)
        assertEquals("devika", current(vm).rewardFor?.id)
    }

    @Test
    fun pickingMyOwnAnswerEarnsPointsButNeverAnIOweMyselfReward() = runTest {
        val vm = viewModel()
        repeat(5) { i ->
            guess("me/q$i", "s$i", "me", "x", 10L + i)
            runCurrent()
            vm.select("s$i"); runCurrent()
            vm.confirm(); runCurrent()
        }
        assertEquals(5, kutumb.observeLedger().first().sumOf { it.points })
        assertTrue(kutumb.observeRedemptions().first().isEmpty())
        assertNull(current(vm).rewardFor)
    }

    @Test
    fun nothingHappensWithoutASelectionAndNobodyElseCanResolveMyFacts() = runTest {
        val vm = viewModel()
        guess("me/a", "a1", "devika", "x", 1)
        runCurrent()
        vm.confirm(); runCurrent()
        assertTrue(kutumb.observeResolutions().first().isEmpty())

        // A fact about somebody else is never on my card list.
        kutumb.saveFact(Fact("devika/z", "devika", "food", "food.pizza"))
        kutumb.saveAnswer(FactAnswer("z1", "devika/z", "asha", "x", false, 2))
        assertEquals("me/a", current(vm).fact?.id)
        assertEquals(0, current(vm).remaining)
    }
}
