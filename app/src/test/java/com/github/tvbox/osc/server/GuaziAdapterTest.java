package com.github.tvbox.osc.server;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GuaziAdapterTest {

    @Test
    public void searchMatchesTitleAndIgnoresWhitespaceAndPunctuation() {
        assertTrue(GuaziAdapter.matchesSearchTitle("沉默的荣耀", "沉默的荣耀"));
        assertTrue(GuaziAdapter.matchesSearchTitle("沉默的荣耀", "《沉默 的 荣耀》（2025）"));
    }

    @Test
    public void searchRejectsUnrelatedOrMissingTitles() {
        assertFalse(GuaziAdapter.matchesSearchTitle("沉默的荣耀", "荣耀之外"));
        assertFalse(GuaziAdapter.matchesSearchTitle("沉默的荣耀", ""));
        assertFalse(GuaziAdapter.matchesSearchTitle("沉默的荣耀", null));
        assertFalse(GuaziAdapter.matchesSearchTitle("！！！", "任意影片"));
    }
}
