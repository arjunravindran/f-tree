package com.vibethroughcode.ftree.kutumb.game

sealed interface SubmitResult {
    data class Added(val answers: List<FactAnswer>) : SubmitResult

    /** The same answer id again (a redelivered sync event); nothing changed. */
    data object Duplicate : SubmitResult

    data class Rejected(val reason: String) : SubmitResult
}

sealed interface ResolveResult {
    data class Resolved(val resolution: FactResolution, val ledgerEntry: LedgerEntry) : ResolveResult
    data class Rejected(val reason: String) : ResolveResult
}

/**
 * The conflict rule, as specified:
 *  - every answer is kept; a self-report and any number of guesses all persist, none overwritten;
 *  - the most recent answer is the fact's default display value;
 *  - the owner picks the correct one, and no timeout ever picks for them;
 *  - whoever submitted the chosen answer is awarded points.
 *
 * A wrong guess costs nothing. Whether it should is an open question, so it is not modelled.
 */
object FactResolver {
    /** Points for a correct answer. Not specified, so a parameter, not a rule. */
    const val DEFAULT_POINTS = 1

    fun submit(fact: Fact, existing: List<FactAnswer>, answer: FactAnswer): SubmitResult {
        if (answer.factId != fact.id) return SubmitResult.Rejected("answer is for another fact")
        if (answer.isSelfReported != (answer.answererId == fact.personId)) {
            return SubmitResult.Rejected("isSelfReported must be true exactly when the owner answers")
        }
        if (existing.any { it.id == answer.id }) return SubmitResult.Duplicate
        return SubmitResult.Added(existing + answer)
    }

    /**
     * What to show by default: the latest answer. Ties on time (clocks on different phones) break
     * on id, so every device picks the same one.
     */
    fun displayAnswer(answers: List<FactAnswer>): FactAnswer? =
        answers.maxWithOrNull(compareBy({ it.submittedAt }, { it.id }))

    /**
     * The owner picks [winningAnswerId]. Only the owner may, only among answers that exist, and
     * only once: a resolution is final, so the points it awarded cannot be double-counted by a
     * second pick.
     */
    fun resolve(
        fact: Fact,
        answers: List<FactAnswer>,
        existing: FactResolution?,
        winningAnswerId: String,
        resolvedByPersonId: String,
        resolvedAt: Long,
        points: Int = DEFAULT_POINTS,
    ): ResolveResult {
        if (resolvedByPersonId != fact.personId) return ResolveResult.Rejected("only the fact's owner resolves it")
        if (existing != null) return ResolveResult.Rejected("already resolved")
        if (points <= 0) return ResolveResult.Rejected("points must be positive")
        val winner = answers.firstOrNull { it.id == winningAnswerId && it.factId == fact.id }
            ?: return ResolveResult.Rejected("no such answer for this fact")
        return ResolveResult.Resolved(
            FactResolution(fact.id, winner.id, fact.personId, points),
            LedgerEntry(winner.answererId, points, fact.id, resolvedAt),
        )
    }

    /**
     * The resolution cards waiting for [ownerId] the next time they open the app: their facts that
     * have answers and no resolution. Oldest question first. No timeout drops one off the list.
     */
    fun pendingResolutions(
        ownerId: String,
        facts: List<Fact>,
        answers: List<FactAnswer>,
        resolutions: List<FactResolution>,
    ): List<Pair<Fact, List<FactAnswer>>> {
        val resolved = resolutions.map { it.factId }.toSet()
        val byFact = answers.groupBy { it.factId }
        return facts
            .filter { it.personId == ownerId && it.id !in resolved && byFact[it.id].orEmpty().isNotEmpty() }
            .map { fact -> fact to byFact.getValue(fact.id).sortedWith(compareBy({ it.submittedAt }, { it.id })) }
            .sortedBy { (_, list) -> list.first().submittedAt }
    }
}
