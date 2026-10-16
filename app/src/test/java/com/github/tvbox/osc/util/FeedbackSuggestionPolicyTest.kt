package com.github.tvbox.osc.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FeedbackSuggestionPolicyTest {

    @Test
    fun knownCodesSelectConfiguredHomeSources() {
        assertEquals(HomeSourceSelection("guazi", "瓜子"), FeedbackSuggestionPolicy.selectionFor("7701"))
        assertEquals(HomeSourceSelection("xgzy", "西瓜"), FeedbackSuggestionPolicy.selectionFor("8801"))
        assertNull(FeedbackSuggestionPolicy.selectionFor("9901"))
    }

    @Test
    fun selectionTrimsWhitespaceAndIgnoresUnknownFeedback() {
        assertEquals(HomeSourceSelection("xgzy", "西瓜"), FeedbackSuggestionPolicy.selectionFor(" 8801\n"))
        assertNull(FeedbackSuggestionPolicy.selectionFor("问题反馈"))
        assertNull(FeedbackSuggestionPolicy.selectionFor(""))
    }

    @Test
    fun adultFilterCodeIsOnlyTheTrimmedExactCommand() {
        assertEquals(true, FeedbackSuggestionPolicy.disablesAdultFilter(" 4848\n"))
        assertEquals(false, FeedbackSuggestionPolicy.disablesAdultFilter("48480"))
        assertEquals(false, FeedbackSuggestionPolicy.disablesAdultFilter("问题 4848"))
    }
}
