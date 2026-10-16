package com.github.tvbox.osc.util

data class HomeSourceSelection(val key: String, val title: String)

object FeedbackSuggestionPolicy {
    const val ADULT_FILTER_DISABLE_CODE = "4848"

    fun selectionFor(input: String): HomeSourceSelection? = when (input.trim()) {
        "7701" -> HomeSourceSelection("guazi", "瓜子")
        "8801" -> HomeSourceSelection("xgzy", "西瓜")
        else -> null
    }

    fun disablesAdultFilter(input: String): Boolean = input.trim() == ADULT_FILTER_DISABLE_CODE
}
