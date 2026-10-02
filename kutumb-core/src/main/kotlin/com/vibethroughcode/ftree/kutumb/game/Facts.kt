package com.vibethroughcode.ftree.kutumb.game

/** A question about one person. [personId] is the fact's owner: the one who knows the answer. */
data class Fact(val id: String, val personId: String, val category: String, val prompt: String) {
    init {
        require(id.isNotBlank() && personId.isNotBlank()) { "fact id and owner are required" }
    }
}

/**
 * One answer to a fact. [id] is not in the schema table but [FactResolution.winningAnswerId] has to
 * point at something. [isSelfReported] is derived rather than stored, so it cannot disagree with
 * who answered.
 */
data class FactAnswer(
    val id: String,
    val factId: String,
    val answererId: String,
    val answerText: String,
    val isSelfReported: Boolean,
    val submittedAt: Long,
) {
    init {
        require(id.isNotBlank() && factId.isNotBlank() && answererId.isNotBlank()) { "answer ids are required" }
        require(answerText.isNotBlank()) { "an answer cannot be blank" }
    }
}

data class FactResolution(
    val factId: String,
    val winningAnswerId: String,
    /** Always the fact's owner. */
    val resolvedByPersonId: String,
    val pointsAwarded: Int,
)

/** One row of the points ledger: [personId] earned [points] because of [reasonFactId]. */
data class LedgerEntry(val personId: String, val points: Int, val reasonFactId: String, val awardedAt: Long) {
    init {
        require(points > 0) { "a ledger entry awards at least one point" }
    }
}
