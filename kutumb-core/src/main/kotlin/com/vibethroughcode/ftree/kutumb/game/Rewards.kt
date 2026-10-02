package com.vibethroughcode.ftree.kutumb.game

enum class RewardType { BEER, COFFEE, ICE_CREAM, CUSTOM }

enum class RedemptionStatus { OWED, REDEEMED }

/** "[fromPersonId] owes [toPersonId] a [rewardType]", earned at [pointsThreshold] points. */
data class RewardRedemption(
    val id: String,
    val fromPersonId: String,
    val toPersonId: String,
    val rewardType: RewardType,
    val pointsThreshold: Int,
    val status: RedemptionStatus = RedemptionStatus.OWED,
    val redeemedAt: Long? = null,
) {
    init {
        require(id.isNotBlank()) { "id is blank" }
        require(fromPersonId != toPersonId) { "nobody owes themselves a reward" }
        require(pointsThreshold > 0) { "threshold must be positive" }
        require((status == RedemptionStatus.REDEEMED) == (redeemedAt != null)) { "redeemedAt is set exactly when redeemed" }
    }

    /** Either side marks it settled once it happens in person. Settling twice is a no-op. */
    fun settle(byPersonId: String, at: Long): RewardRedemption {
        require(byPersonId == fromPersonId || byPersonId == toPersonId) { "only the two people involved can settle it" }
        return if (status == RedemptionStatus.REDEEMED) this else copy(status = RedemptionStatus.REDEEMED, redeemedAt = at)
    }
}

/** The append-only points record. Balances are derived from it, never stored. */
class PointsLedger(entries: List<LedgerEntry> = emptyList()) {
    val entries: List<LedgerEntry> = entries.toList()

    fun plus(entry: LedgerEntry) = PointsLedger(entries + entry)

    fun balance(personId: String): Int = entries.filter { it.personId == personId }.sumOf { it.points }

    /**
     * What [earner] has earned from [owner]'s facts: the points that count toward a reward owed by
     * the owner. [factOwners] maps fact id to the person it is about.
     */
    fun pointsEarnedFrom(earner: String, owner: String, factOwners: Map<String, String>): Int =
        entries.filter { it.personId == earner && factOwners[it.reasonFactId] == owner }.sumOf { it.points }
}

object RewardRules {
    /**
     * How many *new* rewards [points] has earned: one per full [threshold], minus those already
     * created for this pair and reward (owed or redeemed alike; settling one does not re-earn it).
     */
    fun newlyEarned(points: Int, threshold: Int, alreadyCreated: Int): Int {
        require(threshold > 0) { "threshold must be positive" }
        require(points >= 0 && alreadyCreated >= 0) { "negative count" }
        return maxOf(0, points / threshold - alreadyCreated)
    }

    /** Points still to go until the next reward. Exactly on a threshold means a full threshold ahead. */
    fun pointsToNext(points: Int, threshold: Int): Int {
        require(threshold > 0 && points >= 0) { "bad input" }
        return threshold - points % threshold
    }

    /**
     * Rewards to create now for the pair (owner owes earner). Ids come from [newId] so the caller
     * controls their generation. Nothing is owed to oneself, so an owner's points on their own
     * facts (a self-report that won) never create a reward.
     */
    fun redemptionsToCreate(
        ledger: PointsLedger,
        earner: String,
        owner: String,
        factOwners: Map<String, String>,
        rewardType: RewardType,
        threshold: Int,
        existing: List<RewardRedemption>,
        newId: () -> String,
    ): List<RewardRedemption> {
        if (earner == owner) return emptyList()
        val created = existing.count { it.fromPersonId == owner && it.toPersonId == earner && it.rewardType == rewardType }
        val count = newlyEarned(ledger.pointsEarnedFrom(earner, owner, factOwners), threshold, created)
        return List(count) { RewardRedemption(newId(), owner, earner, rewardType, threshold) }
    }
}
