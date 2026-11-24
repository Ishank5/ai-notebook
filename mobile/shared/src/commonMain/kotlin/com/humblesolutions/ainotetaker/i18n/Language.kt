package com.humblesolutions.ainotetaker.i18n

/**
 * The three languages the product speaks.
 *
 * THE CHOICE COMES BEFORE SIGN-IN, not after. Somebody who reads only Punjabi
 * cannot be asked to find a language setting written in English — so the
 * picker is the first thing on the sign-in screen, and the choice survives
 * sign-out on the device.
 */
enum class Language(val code: String, val label: String) {
    ENGLISH("en", "English"),
    HINDI("hi", "हिन्दी"),
    PUNJABI("pa", "ਪੰਜਾਬੀ");

    companion object {
        val default = ENGLISH
        fun fromCode(code: String?): Language =
            entries.firstOrNull { it.code == code } ?: default
    }
}
