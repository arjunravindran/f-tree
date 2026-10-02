package com.vibethroughcode.ftree.kutumb.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GameTest {
    private val fact = Fact("f1", "mom", "Food", "What's my go-to pizza topping?")

    private fun ans(id: String, by: String, text: String, at: Long, factId: String = "f1") =
        FactAnswer(id, factId, by, text, isSelfReported = by == "mom", submittedAt = at)

    private fun rejects(block: () -> Unit) {
        try { block(); fail("expected IllegalArgumentException") } catch (_: IllegalArgumentException) {}
    }

    private fun resolve(answers: List<FactAnswer>, win: String, by: String = "mom", existing: FactResolution? = null) =
        FactResolver.resolve(fact, answers, existing, win, by, resolvedAt = 99)

    /* ------------------------------------------------------------------ keep every answer */

    @Test
    fun `every answer is kept and none overwritten`() {
        val a = ans("a1", "mom", "mushroom", 1)
        val b = ans("a2", "kid", "pepperoni", 2)
        val c = ans("a3", "kid", "olives", 3) // same person, same fact, second guess
        var list = emptyList<FactAnswer>()
        for (x in listOf(a, b, c)) list = (FactResolver.submit(fact, list, x) as SubmitResult.Added).answers
        assertEquals(listOf(a, b, c), list)
    }

    @Test
    fun `a redelivered answer is a no-op`() {
        val a = ans("a1", "mom", "mushroom", 1)
        assertEquals(SubmitResult.Duplicate, FactResolver.submit(fact, listOf(a), a))
    }

    @Test
    fun `answers for another fact or with a wrong self-report flag are refused`() {
        assertTrue(FactResolver.submit(fact, emptyList(), ans("a1", "kid", "x", 1, factId = "f2")) is SubmitResult.Rejected)
        val lie = FactAnswer("a2", "f1", "kid", "x", isSelfReported = true, submittedAt = 1)
        assertTrue(FactResolver.submit(fact, emptyList(), lie) is SubmitResult.Rejected)
        val hidden = FactAnswer("a3", "f1", "mom", "x", isSelfReported = false, submittedAt = 1)
        assertTrue(FactResolver.submit(fact, emptyList(), hidden) is SubmitResult.Rejected)
    }

    @Test
    fun `a blank answer is not an answer`() {
        rejects { ans("a1", "kid", "  ", 1) }
    }

    /* ------------------------------------------------------------------ default display */

    @Test
    fun `the latest answer is the default display, whatever order they arrived in`() {
        val old = ans("a1", "mom", "mushroom", 1)
        val new = ans("a2", "kid", "pepperoni", 5)
        assertEquals(new, FactResolver.displayAnswer(listOf(new, old)))
        assertEquals(new, FactResolver.displayAnswer(listOf(old, new)))
        assertNull(FactResolver.displayAnswer(emptyList()))
    }

    @Test
    fun `a tie on time breaks on id so every phone agrees`() {
        val x = ans("a1", "kid", "x", 5)
        val y = ans("a2", "dad", "y", 5)
        assertEquals(FactResolver.displayAnswer(listOf(x, y)), FactResolver.displayAnswer(listOf(y, x)))
    }

    @Test
    fun `display is not the resolution - a resolved fact still shows the latest answer`() {
        val right = ans("a1", "mom", "mushroom", 1)
        val later = ans("a2", "kid", "pepperoni", 5)
        assertTrue(resolve(listOf(right, later), "a1") is ResolveResult.Resolved)
        assertEquals(later, FactResolver.displayAnswer(listOf(right, later)))
    }

    /* ------------------------------------------------------------------ owner resolves */

    @Test
    fun `the owner picks and the answerer of the winning answer gets the points`() {
        val answers = listOf(ans("a1", "mom", "mushroom", 1), ans("a2", "kid", "pepperoni", 2), ans("a3", "dad", "mushroom", 3))
        val r = resolve(answers, "a3") as ResolveResult.Resolved
        assertEquals(FactResolution("f1", "a3", "mom", 1), r.resolution)
        assertEquals(LedgerEntry("dad", 1, "f1", 99), r.ledgerEntry)
    }

    @Test
    fun `the owner who picks their own self-report earns the points too`() {
        val r = resolve(listOf(ans("a1", "mom", "mushroom", 1)), "a1") as ResolveResult.Resolved
        assertEquals("mom", r.ledgerEntry.personId)
    }

    @Test
    fun `only the owner resolves, only a real answer, and only once`() {
        val answers = listOf(ans("a1", "mom", "mushroom", 1), ans("a2", "kid", "pepperoni", 2))
        assertTrue(resolve(answers, "a2", by = "kid") is ResolveResult.Rejected)
        assertTrue(resolve(answers, "nope") is ResolveResult.Rejected)
        val first = (resolve(answers, "a1") as ResolveResult.Resolved).resolution
        assertTrue(resolve(answers, "a2", existing = first) is ResolveResult.Rejected)
        assertTrue(FactResolver.resolve(fact, answers, null, "a1", "mom", 1, points = 0) is ResolveResult.Rejected)
    }

    @Test
    fun `an answer to a different fact cannot win this one`() {
        val other = ans("x1", "kid", "z", 1, factId = "f2")
        assertTrue(resolve(listOf(other), "x1") is ResolveResult.Rejected)
    }

    @Test
    fun `the owner's pending cards list unresolved facts with answers, oldest first, with no timeout`() {
        val f2 = Fact("f2", "mom", "Growing up", "First pet?")
        val f3 = Fact("f3", "mom", "Food", "Dish at reunions?")
        val dad = Fact("f4", "dad", "Food", "x")
        val answers = listOf(
            ans("a1", "kid", "pepperoni", 50),
            ans("a2", "kid", "rex", 10, factId = "f2"),
            ans("a3", "kid", "y", 1, factId = "f4"),
        )
        val resolved = listOf(FactResolution("f1", "a1", "mom", 1))

        assertEquals(listOf("f2"), FactResolver.pendingResolutions("mom", listOf(fact, f2, f3, dad), answers, resolved).map { it.first.id })

        val open = FactResolver.pendingResolutions("mom", listOf(fact, f2, f3), answers, emptyList())
        assertEquals(listOf("f2", "f1"), open.map { it.first.id }) // f2 asked first; f3 has no answers
        assertEquals(listOf("a2"), open.first().second.map { it.id })
    }

    /* ------------------------------------------------------------------ points & rewards */

    private val owners = mapOf("f1" to "mom", "f2" to "mom", "f3" to "dad")

    private fun ledger() = PointsLedger(listOf(
        LedgerEntry("kid", 1, "f1", 1),
        LedgerEntry("kid", 2, "f2", 2),
        LedgerEntry("kid", 5, "f3", 3),
        LedgerEntry("dad", 1, "f1", 4),
    ))

    @Test
    fun `balances and per-pair points are sums of the ledger`() {
        val l = ledger()
        assertEquals(8, l.balance("kid"))
        assertEquals(3, l.pointsEarnedFrom("kid", "mom", owners))
        assertEquals(5, l.pointsEarnedFrom("kid", "dad", owners))
        assertEquals(0, l.pointsEarnedFrom("mom", "kid", owners))
        assertEquals(0, PointsLedger().balance("anyone"))
    }

    @Test
    fun `a ledger entry must award a positive amount`() {
        rejects { LedgerEntry("kid", 0, "f1", 1) }
    }

    @Test
    fun `one reward per full threshold, and already-created ones are not earned again`() {
        assertEquals(0, RewardRules.newlyEarned(4, 5, 0))
        assertEquals(1, RewardRules.newlyEarned(5, 5, 0))
        assertEquals(2, RewardRules.newlyEarned(12, 5, 0))
        assertEquals(1, RewardRules.newlyEarned(12, 5, 1))
        assertEquals(0, RewardRules.newlyEarned(12, 5, 2))
        assertEquals("never negative", 0, RewardRules.newlyEarned(3, 5, 4))
        rejects { RewardRules.newlyEarned(5, 0, 0) }
    }

    @Test
    fun `points to the next reward counts a full threshold ahead when exactly on one`() {
        assertEquals(5, RewardRules.pointsToNext(0, 5))
        assertEquals(2, RewardRules.pointsToNext(3, 5))
        assertEquals(5, RewardRules.pointsToNext(5, 5))
        assertEquals(1, RewardRules.pointsToNext(9, 5))
    }

    @Test
    fun `crossing the threshold makes the owner owe the earner, and settled ones still count as created`() {
        var n = 0
        val make = { "r${++n}" }
        val first = RewardRules.redemptionsToCreate(ledger(), "kid", "dad", owners, RewardType.BEER, 5, emptyList(), make)
        assertEquals(1, first.size)
        assertEquals("dad", first[0].fromPersonId)
        assertEquals("kid", first[0].toPersonId)

        val settled = first[0].settle("kid", 10)
        assertEquals(0, RewardRules.redemptionsToCreate(ledger(), "kid", "dad", owners, RewardType.BEER, 5, listOf(settled), make).size)

        // Below threshold with mom (3 points of 5): nothing owed yet.
        assertTrue(RewardRules.redemptionsToCreate(ledger(), "kid", "mom", owners, RewardType.COFFEE, 5, emptyList(), make).isEmpty())
        // A lower per-relationship threshold earns more.
        assertEquals(3, RewardRules.redemptionsToCreate(ledger(), "kid", "mom", owners, RewardType.ICE_CREAM, 1, emptyList(), make).size)
        // Another reward type is counted separately.
        assertEquals(1, RewardRules.redemptionsToCreate(ledger(), "kid", "dad", owners, RewardType.COFFEE, 5, listOf(settled), make).size)
    }

    @Test
    fun `either side can settle a redemption, a stranger cannot, and settling twice changes nothing`() {
        val r = RewardRedemption("r1", "dad", "kid", RewardType.BEER, 5)
        assertEquals(RedemptionStatus.OWED, r.status)
        val byPayer = r.settle("dad", 7)
        assertEquals(RedemptionStatus.REDEEMED, byPayer.status)
        assertEquals(7L, byPayer.redeemedAt)
        assertEquals(byPayer, byPayer.settle("kid", 99))
        assertEquals(RedemptionStatus.REDEEMED, r.settle("kid", 7).status)
        rejects { r.settle("mom", 7) }
    }

    @Test
    fun `a redemption keeps its invariants`() {
        rejects { RewardRedemption("r1", "dad", "dad", RewardType.BEER, 5) }
        rejects { RewardRedemption("r1", "dad", "kid", RewardType.BEER, 0) }
        rejects { RewardRedemption("r1", "dad", "kid", RewardType.BEER, 5, RedemptionStatus.REDEEMED, null) }
        rejects { RewardRedemption("r1", "dad", "kid", RewardType.BEER, 5, RedemptionStatus.OWED, 3) }
    }

    @Test
    fun `an owner's points on their own facts never create a reward they owe themselves`() {
        val own = PointsLedger(listOf(LedgerEntry("dad", 5, "f1", 1)))
        val made = RewardRules.redemptionsToCreate(own, "dad", "dad", mapOf("f1" to "dad"), RewardType.BEER, 5, emptyList()) { "r" }
        assertTrue(made.isEmpty())
    }
}
