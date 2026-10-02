package com.vibethroughcode.ftree.kutumb.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestionBankTest {
    @Test
    fun idsAreUniqueAndEveryQuestionHasACategory() {
        assertEquals(QuestionBank.all.size, QuestionBank.all.map { it.id }.toSet().size)
        assertTrue(QuestionBank.all.all { it.category.isNotBlank() && it.id.startsWith(it.category.take(4)) })
    }

    @Test
    fun theSpecsSevenCategoriesAreAllCovered() {
        assertEquals(
            setOf("food", "growing_up", "family_history", "traditions", "milestones", "story", "habits"),
            QuestionBank.all.map { it.category }.toSet(),
        )
    }

    @Test
    fun theSameQuestionAboutTheSamePersonIsTheSameFactEverywhere() {
        assertEquals(QuestionBank.factId("kiran", "food.pizza"), QuestionBank.factId("kiran", "food.pizza"))
        assertTrue(QuestionBank.factId("kiran", "food.pizza") != QuestionBank.factId("asha", "food.pizza"))
        assertNotNull(QuestionBank.byId("food.pizza"))
        assertNull(QuestionBank.byId("from.a.newer.release"))
    }

    @Test
    fun anOwnerIdCannotForgeAnotherFactsId() {
        runCatching { QuestionBank.factId("kiran/food.pizza", "x") }.let { assertTrue(it.isFailure) }
    }
}
