package com.clipcut.ui.caption

import javax.inject.Inject
import javax.inject.Singleton

/**
 * A one-shot hand-off: another screen (Trend Radar) leaves an idea here and opens the Caption Generator, which picks it
 * up once as the starting text. Nothing is drafted or sent by this; the person decides whether to tap Enhance.
 */
@Singleton
class CaptionSeed @Inject constructor() {
    @Volatile private var text: String? = null

    fun put(idea: String) {
        text = idea.trim().takeIf { it.isNotEmpty() }
    }

    /** Returns the idea once, then forgets it. */
    fun take(): String? = text.also { text = null }
}
