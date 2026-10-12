package com.github.tvbox.osc.util

data class HomeSourceSelection(val key: String, val title: String)

object FeedbackSuggestionPolicy {
    fun selectionFor(input: String): HomeSourceSelection? = when (input.trim()) {
        "7701" -> HomeSourceSelection("guazi", "瓜子")
        "8801" -> HomeSourceSelection("xgzy", "西瓜")
        else -> null
    }
}
