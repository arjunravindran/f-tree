package com.vibethroughcode.ftree.kutumb.game

/** One starter question. [id] is stable and never reused; the wording lives with whoever displays it. */
data class Question(val id: String, val category: String)

/**
 * The starter bank, kept to the light categories the spec lists. Only ids are here: the words are
 * translated by the app, and a [Fact] stores the id in its `prompt`, so a phone that does not know an
 * id (one from a newer release) still has something to show and nothing to crash on.
 */
object QuestionBank {
    val all: List<Question> = listOf(
        Question("food.pizza", "food"),
        Question("food.breakfast", "food"),
        Question("growing_up.first_pet", "growing_up"),
        Question("family_history.emigrated_from", "family_history"),
        Question("traditions.reunion_dish", "traditions"),
        Question("milestones.oldest_living", "milestones"),
        Question("story.got_lost", "story"),
        Question("habits.first_five_minutes", "habits"),
    )

    fun byId(id: String): Question? = all.firstOrNull { it.id == id }

    /**
     * The same question about the same person is the same fact on every phone, so answers that
     * arrive from different phones land on one card rather than on duplicates.
     */
    fun factId(ownerId: String, questionId: String): String {
        require(ownerId.isNotBlank() && '/' !in ownerId) { "bad owner id" }
        return "$ownerId/$questionId"
    }
}
