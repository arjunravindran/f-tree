package com.vibethroughcode.ftree.ui.facts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vibethroughcode.ftree.data.Person
import com.vibethroughcode.ftree.kutumb.KutumbRepository
import com.vibethroughcode.ftree.kutumb.game.Fact
import com.vibethroughcode.ftree.kutumb.game.FactAnswer
import com.vibethroughcode.ftree.kutumb.game.FactResolver
import com.vibethroughcode.ftree.kutumb.game.GameSync
import com.vibethroughcode.ftree.kutumb.game.ResolutionMessage
import com.vibethroughcode.ftree.kutumb.sync.NoSyncPublisher
import com.vibethroughcode.ftree.kutumb.sync.SyncPublisher
import com.vibethroughcode.ftree.kutumb.game.PointsLedger
import com.vibethroughcode.ftree.kutumb.game.ResolveResult
import com.vibethroughcode.ftree.kutumb.game.RewardRules
import com.vibethroughcode.ftree.kutumb.game.RewardType
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One answer on the card, with who gave it. */
data class AnswerLine(val answer: FactAnswer, val answerer: Person?)

data class ResolveUiState(
    val loaded: Boolean = false,
    /** The reader. Only they can resolve a fact about them, so with no identity there is no card. */
    val me: Person? = null,
    /** The oldest unresolved question about the reader; null when none is waiting. */
    val fact: Fact? = null,
    val answers: List<AnswerLine> = emptyList(),
    val selectedId: String? = null,
    /** How many more cards wait behind this one. */
    val remaining: Int = 0,
    /** What the selected answer's author has earned from the reader so far, and what a reward costs. */
    val earnedSoFar: Int = 0,
    val threshold: Int = ResolveViewModel.DEFAULT_THRESHOLD,
    /** Who has just earned a reward from the reader, until the screen has said so. */
    val rewardFor: Person? = null,
) {
    val selected: AnswerLine? get() = answers.firstOrNull { it.answer.id == selectedId }
}

class ResolveViewModel(
    private val kutumb: KutumbRepository,
    people: Flow<List<Person>>,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val publisher: SyncPublisher = NoSyncPublisher,
) : ViewModel() {

    private val selectedId = MutableStateFlow<String?>(null)
    private val rewardFor = MutableStateFlow<Person?>(null)

    private data class Records(
        val meId: String?,
        val facts: List<Fact>,
        val answers: List<FactAnswer>,
    )

    val state: StateFlow<ResolveUiState> = combine(
        combine(kutumb.observeIdentity(), kutumb.observeFacts(), kutumb.observeAnswers()) { i, f, a -> Records(i?.personId, f, a) },
        kutumb.observeResolutions(),
        kutumb.observeLedger(),
        people,
        combine(selectedId, rewardFor) { s, r -> s to r },
    ) { records, resolutions, ledger, everyone, (selected, reward) ->
        val byId = everyone.associateBy { it.id }
        val pending = records.meId?.let { FactResolver.pendingResolutions(it, records.facts, records.answers, resolutions) }.orEmpty()
        val (fact, answers) = pending.firstOrNull() ?: (null to emptyList())
        val lines = answers.map { AnswerLine(it, byId[it.answererId]) }
        val chosen = lines.firstOrNull { it.answer.id == selected }
        val owners = records.facts.associate { it.id to it.personId }
        ResolveUiState(
            loaded = true,
            me = records.meId?.let(byId::get),
            fact = fact,
            answers = lines,
            // A selection that belongs to a card already resolved is dropped with the card.
            selectedId = chosen?.answer?.id,
            remaining = (pending.size - 1).coerceAtLeast(0),
            earnedSoFar = if (chosen != null && records.meId != null) {
                PointsLedger(ledger).pointsEarnedFrom(chosen.answer.answererId, records.meId, owners)
            } else 0,
            rewardFor = reward,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ResolveUiState())

    fun select(answerId: String) { selectedId.value = answerId }

    fun rewardShown() { rewardFor.value = null }

    /**
     * "This one is right." Resolves through the core (owner only, once only), awards the answerer's
     * points, and - if that crosses a threshold - creates the reward the reader now owes them.
     */
    fun confirm() {
        val ui = state.value
        val me = ui.me ?: return
        val fact = ui.fact ?: return
        val choice = ui.selected ?: return
        viewModelScope.launch {
            val answers = kutumb.observeAnswers().first().filter { it.factId == fact.id }
            val existing = kutumb.observeResolutions().first().firstOrNull { it.factId == fact.id }
            val result = FactResolver.resolve(fact, answers, existing, choice.answer.id, me.id, clock())
            if (result !is ResolveResult.Resolved) return@launch
            kutumb.saveResolution(result.resolution, result.ledgerEntry)
            // Everyone who guessed learns which answer was right and who earned the points.
            publisher.publish(
                GameSync.resolutionEnvelope(ResolutionMessage(fact, choice.answer, result.resolution.pointsAwarded, result.ledgerEntry.awardedAt)),
                answers.map { it.answererId }.filter { it != me.id },
            )

            val earner = result.ledgerEntry.personId
            val facts = kutumb.observeFacts().first()
            val ledger = PointsLedger(kutumb.observeLedger().first())
            val made = RewardRules.redemptionsToCreate(
                ledger = ledger,
                earner = earner,
                owner = me.id,
                factOwners = facts.associate { it.id to it.personId },
                rewardType = DEFAULT_REWARD,
                threshold = DEFAULT_THRESHOLD,
                existing = kutumb.observeRedemptions().first(),
                newId = newId,
            )
            made.forEach { kutumb.saveRedemption(it) }
            if (made.isNotEmpty()) rewardFor.value = choice.answerer
            selectedId.value = null
        }
    }

    companion object {
        /**
         * Points before a reward is owed, and what kind. The spec makes both configurable per
         * relationship but there is no screen for that, so every pair gets these.
         */
        const val DEFAULT_THRESHOLD = 5
        val DEFAULT_REWARD = RewardType.COFFEE
    }
}
