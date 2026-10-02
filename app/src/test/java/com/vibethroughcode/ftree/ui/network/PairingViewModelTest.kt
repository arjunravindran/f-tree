package com.vibethroughcode.ftree.ui.network

import com.vibethroughcode.ftree.data.Person
import com.vibethroughcode.ftree.kutumb.Bip340Scheme
import com.vibethroughcode.ftree.kutumb.InMemoryKutumbRepository
import com.vibethroughcode.ftree.kutumb.LocalIdentity
import com.vibethroughcode.ftree.kutumb.PairingFlow
import com.vibethroughcode.ftree.kutumb.PairingState
import com.vibethroughcode.ftree.kutumb.trust.PairingMethod
import com.vibethroughcode.ftree.nearby.wire.QrLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PairingViewModelTest {

    private class FakeFlow : PairingFlow {
        val mutable = MutableStateFlow<PairingState>(PairingState.Idle)
        override val state: StateFlow<PairingState> = mutable
        var started = 0
        var cancelled = 0
        var confirmed: Boolean? = null
        override fun start() { started++ }
        override fun joinByLink(link: QrLink) = Unit
        override fun confirmCode(matched: Boolean) { confirmed = matched }
        override fun cancel() { cancelled++ }
    }

    private val me = Person(id = "me", name = "Me")
    private val devika = Person(id = "devika", name = "Devika")
    private val kiran = Person(id = "kiran", name = "Kiran")
    private val peerKey = Bip340Scheme.generateKeyPair().publicKey

    private val flow = FakeFlow()
    private val kutumb = InMemoryKutumbRepository()

    @Before fun setMain() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun resetMain() = Dispatchers.resetMain()

    private suspend fun viewModel(vararg people: Person): PairingViewModel {
        kutumb.setIdentity(LocalIdentity("me", Bip340Scheme.generateKeyPair()))
        return PairingViewModel(flow, MutableStateFlow(listOf(me) + people), kutumb, clock = { 99 })
    }

    @Test
    fun openingTheScreenStartsShowingThisPhone() = runTest {
        viewModel(devika)
        assertEquals(1, flow.started)
    }

    @Test
    fun theReaderAndPeopleAlreadyTrustedAreNotOfferedAsWhoTheyMet() = runTest {
        val vm = viewModel(devika, kiran)
        val job = vm.state.launchIn(backgroundScope)
        flow.mutable.value = PairingState.Verified("Pixel", peerKey)
        vm.choosePerson("devika")
        // Devika is now trusted, so only Kiran is left to choose.
        assertEquals(listOf("kiran"), vm.state.first { it.saved }.candidates.map { it.id })
        job.cancel()
    }

    @Test
    fun choosingWhoTheyAreSavesThemAsMetInPersonWithTheVerifiedKey() = runTest {
        val vm = viewModel(devika)
        vm.state.launchIn(backgroundScope)
        flow.mutable.value = PairingState.Verified("Pixel", peerKey)

        vm.choosePerson("devika")

        val saved = kutumb.observeContacts().first().single()
        assertEquals("devika", saved.personId)
        assertEquals(peerKey, saved.pubKeyCurrent)
        assertEquals(PairingMethod.DIRECT, saved.pairingMethod)
        assertEquals(99L, saved.pairedAt)
        assertTrue(vm.state.first { it.saved }.saved)
        assertEquals(1, flow.cancelled)
    }

    @Test
    fun nothingIsSavedUntilTheCodesWereVerified() = runTest {
        val vm = viewModel(devika)
        vm.state.launchIn(backgroundScope)
        flow.mutable.value = PairingState.ConfirmCode("123456", "Pixel")

        vm.choosePerson("devika")

        assertTrue(kutumb.observeContacts().first().isEmpty())
        assertFalse(vm.state.value.saved)
    }

    @Test
    fun aKeyAlreadyTrustedForSomeoneElseIsNotSavedTwice() = runTest {
        val vm = viewModel(devika, kiran)
        vm.state.launchIn(backgroundScope)
        flow.mutable.value = PairingState.Verified("Pixel", peerKey)
        vm.choosePerson("devika")

        flow.mutable.value = PairingState.Verified("Pixel", peerKey)
        vm.choosePerson("kiran")

        assertEquals(listOf("devika"), kutumb.observeContacts().first().map { it.personId })
    }

    @Test
    fun theReadersAnswerAboutTheCodeGoesToTheFlow() = runTest {
        val vm = viewModel(devika)
        vm.confirmCode(false)
        assertEquals(false, flow.confirmed)
    }
}
