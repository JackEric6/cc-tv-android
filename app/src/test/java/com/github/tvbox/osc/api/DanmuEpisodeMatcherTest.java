package com.github.tvbox.osc.api;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class DanmuEpisodeMatcherTest {
    @Test
    public void extractsEpisodeFromChineseResultWithDuplicateTrailingNumber() {
        assertEquals(1, DanmuEpisodeMatcher.episodeNumber("冬城猎凶(2026) 第1集 冬城猎凶 01"));
    }

    @Test
    public void extractsEpisodeFromSeasonEpisodeAndEpisodePrefixFormats() {
        assertEquals(2, DanmuEpisodeMatcher.episodeNumber("S01E02"));
        assertEquals(3, DanmuEpisodeMatcher.episodeNumber("第2季第03集"));
        assertEquals(12, DanmuEpisodeMatcher.episodeNumber("Episode 12"));
    }

    @Test
    public void onlyTreatsUnmarkedNumbersAsEpisodesWhenTheWholeTextIsNumeric() {
        assertEquals(7, DanmuEpisodeMatcher.episodeNumber("007"));
        assertEquals(-1, DanmuEpisodeMatcher.episodeNumber("Winter Hunter 2026"));
        assertEquals(-1, DanmuEpisodeMatcher.episodeNumber(""));
    }
}
