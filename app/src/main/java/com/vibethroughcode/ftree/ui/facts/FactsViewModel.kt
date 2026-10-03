package com.vibethroughcode.ftree.ui.facts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vibethroughcode.ftree.data.Person
import com.vibethroughcode.ftree.kutumb.KutumbRepository
import com.vibethroughcode.ftree.kutumb.game.Fact
import com.vibethroughcode.ftree.kutumb.game.FactAnswer
import com.vibethroughcode.ftree.kutumb.game.FactResolver
import com.vibethroughcode.ftree.kutumb.game.GameSync
import com.vibethroughcode.ftree.kutumb.sync.NoSyncPublisher
import com.vibethroughcode.ftree.kutumb.sync.SyncPublisher
import com.vibethroughcode.ftree.kutumb.game.PointsLedger
import com.vibethroughcode.ftree.kutumb.game.Question
import com.vibethroughcode.ftree.kutumb.game.QuestionBank
import com.vibethroughcode.ftree.kutumb.game.SubmitResult
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class FactsMode { SELF, GUESS }

data class FactsUiState(
    val loaded: Boolean = false,
    /** Null until the reader has said who they are on the Network tab. */
    val me: Person? = null,
    val points: Int = 0,
    val mode: FactsMode = FactsMode.SELF,
    /** Relatives this phone trusts: the only people a guess can be about. */
    val relatives: List<Person> = emptyList(),
    val subject: Person? = null,
    val question: Question = QuestionBank.all.first(),
    val answer: String = "",
    /** Answers about the reader that are still waiting for them to pick the right one. */
    val pendingCount: Int = 0,
    /** Set when an answer was just saved, until the screen has said so. */
    val justSaved: Boolean = false,
) {
    /** Whose fact the answer would be about. */
    val owner: Person? get() = if (mode == FactsMode.SELF) me else subject
    val canSubmit: Boolean get() = owner != null && answer.isNotBlank()
}

class FactsViewModel(
    private val kutumb: KutumbRepository,
    people: Flow<List<Person>>,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val publisher: SyncPublisher = NoSyncPublisher,
) : ViewModel() {

    private val mode = MutableStateFlow(FactsMode.SELF)
    private val subjectId = MutableStateFlow<String?>(null)
    private val questionIndex = MutableStateFlow(0)
    private val answer = MutableStateFlow("")
    private val justSaved = MutableStateFlow(false)

    private data class Inputs(val mode: FactsMode, val subjectId: String?, val index: Int, val answer: String, val saved: Boolean)

    private val inputs = combine(mode, subjectId, questionIndex, answer, justSaved, ::Inputs)

    val state: StateFlow<FactsUiState> = combine(
        kutumb.observeIdentity(), people, kutumb.observeContacts(), kutumb.observeFacts(), kutumb.observeAnswers(),
    ) { identity, everyone, contacts, facts, answers -> Tuple(identity?.personId, everyone, contacts.map { it.personId }, facts, answers) }
        .combine(kutumb.observeResolutions()) { t, resolutions -> t to resolutions }
        .combine(kutumb.observeLedger()) { (t, resolutions), ledger -> Triple(t, resolutions, ledger) }
        .combine(inputs) { (t, resolutions, ledger), input ->
            val byId = t.everyone.associateBy { it.id }
            val me = t.meId?.let(byId::get)
            val relatives = t.contactIds.mapNotNull(byId::get).sortedBy { it.name.orEmpty().lowercase() }
            FactsUiState(
                loaded = true,
                me = me,
                points = me?.let { PointsLedger(ledger).balance(it.id) } ?: 0,
                mode = input.mode,
                relatives = relatives,
                // Default to the first relative, so choosing "guess" is one tap from a question.
                subject = byId[input.subjectId]?.takeIf { s -> relatives.any { it.id == s.id } } ?: relatives.firstOrNull(),
                question = QuestionBank.all[input.index.mod(QuestionBank.all.size)],
                answer = input.answer,
                pendingCount = me?.let { FactResolver.pendingResolutions(it.id, t.facts, t.answers, resolutions).size } ?: 0,
                justSaved = input.saved,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FactsUiState())

    private data class Tuple(
        val meId: String?,
        val everyone: List<Person>,
        val contactIds: List<String>,
        val facts: List<Fact>,
        val answers: List<FactAnswer>,
    )

    fun setMode(value: FactsMode) { mode.value = value }
    fun chooseSubject(personId: String) { subjectId.value = personId }
    fun onAnswerChange(value: String) { answer.value = value }
    fun nextQuestion() { questionIndex.value += 1 }
    fun savedShown() { justSaved.value = false }

    /**
     * Records the answer, about the reader or about a relative, through the core's rule (which
     * checks the self-report flag and refuses a repeat). The fact is created the first time anyone
     * answers it, under an id every phone derives the same way.
     */
    fun submit() {
        val ui = state.value
        val me = ui.me ?: return
        val owner = ui.owner ?: return
        if (ui.answer.isBlank()) return
        viewModelScope.launch {
            val factId = QuestionBank.factId(owner.id, ui.question.id)
            val fact = kutumb.observeFacts().first().firstOrNull { it.id == factId }
                ?: Fact(factId, owner.id, ui.question.category, ui.question.id).also { kutumb.saveFact(it) }
            val existing = kutumb.observeAnswers().first().filter { it.factId == factId }
            val answer = FactAnswer(
                id = newId(),
                factId = factId,
                answererId = me.id,
                answerText = ui.answer.trim(),
                isSelfReported = owner.id == me.id,
                submittedAt = clock(),
            )
            if (FactResolver.submit(fact, existing, answer) is SubmitResult.Added) {
                kutumb.saveAnswer(answer)
                // A guess goes to the person it is about; their own answer about themselves goes to no one.
                if (owner.id != me.id) publisher.publish(GameSync.answerEnvelope(fact, answer), listOf(owner.id))
                this@FactsViewModel.answer.value = ""
                questionIndex.value += 1
                justSaved.value = true
            }
        }
    }
}
