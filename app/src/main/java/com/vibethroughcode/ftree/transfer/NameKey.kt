package com.vibethroughcode.ftree.transfer

import java.text.Normalizer
import java.util.Locale

/**
 * A name reduced to what two spellings of the same person have in common.
 *
 * Accents, capitalisation, punctuation and stray spacing all differ between people typing the same
 * name into two different phones, and none of those differences mean it is a different person.
 *
 * Only the Latin combining accents (U+0300 to U+036F, what NFD leaves behind for "é") are stripped.
 * Every other mark is kept as part of the word, because in scripts such as Devanagari the vowel
 * signs are marks and are the difference between names: stripping them made "राम" and "रामा" the
 * same key (#113). A mark is not punctuation, so it must never be replaced with a space either.
 * Deliberately conservative: it normalises *form*, never content. "Raj Kumar" and "R. Kumar" stay
 * different, because guessing they are the same is how a merge quietly destroys someone's data.
 */
fun nameKey(name: String?): String? {
    val trimmed = name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val withoutAccents = Normalizer.normalize(trimmed, Normalizer.Form.NFD)
        .replace(Regex("[\\u0300-\\u036F]+"), "")
    return withoutAccents
        .lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}\\p{M}]+"), " ")
        .trim()
        .takeIf { it.isNotEmpty() }
}
