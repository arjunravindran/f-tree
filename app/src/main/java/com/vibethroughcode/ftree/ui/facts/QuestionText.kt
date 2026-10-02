package com.vibethroughcode.ftree.ui.facts

import androidx.annotation.StringRes
import com.vibethroughcode.ftree.R

/**
 * The words for the question bank. The core only knows ids; a question id this build has no words
 * for (one from a newer release, arriving over sync) is shown as [R.string.q_unknown] rather than as
 * a raw id.
 */
object QuestionText {
    /** "What's your go-to pizza topping?" */
    @StringRes
    fun self(id: String): Int = when (id) {
        "food.pizza" -> R.string.q_food_pizza_self
        "food.breakfast" -> R.string.q_food_breakfast_self
        "growing_up.first_pet" -> R.string.q_growing_up_first_pet_self
        "family_history.emigrated_from" -> R.string.q_family_history_emigrated_from_self
        "traditions.reunion_dish" -> R.string.q_traditions_reunion_dish_self
        "milestones.oldest_living" -> R.string.q_milestones_oldest_living_self
        "story.got_lost" -> R.string.q_story_got_lost_self
        "habits.first_five_minutes" -> R.string.q_habits_first_five_minutes_self
        else -> R.string.q_unknown
    }

    /** "What's Kiran's go-to pizza topping?" - takes the person's name as its one argument. */
    @StringRes
    fun about(id: String): Int = when (id) {
        "food.pizza" -> R.string.q_food_pizza_about
        "food.breakfast" -> R.string.q_food_breakfast_about
        "growing_up.first_pet" -> R.string.q_growing_up_first_pet_about
        "family_history.emigrated_from" -> R.string.q_family_history_emigrated_from_about
        "traditions.reunion_dish" -> R.string.q_traditions_reunion_dish_about
        "milestones.oldest_living" -> R.string.q_milestones_oldest_living_about
        "story.got_lost" -> R.string.q_story_got_lost_about
        "habits.first_five_minutes" -> R.string.q_habits_first_five_minutes_about
        else -> R.string.q_unknown
    }

    @StringRes
    fun category(category: String): Int = when (category) {
        "food" -> R.string.facts_cat_food
        "growing_up" -> R.string.facts_cat_growing_up
        "family_history" -> R.string.facts_cat_family_history
        "traditions" -> R.string.facts_cat_traditions
        "milestones" -> R.string.facts_cat_milestones
        "story" -> R.string.facts_cat_story
        "habits" -> R.string.facts_cat_habits
        else -> R.string.facts_title
    }
}
