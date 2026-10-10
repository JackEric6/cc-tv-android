package com.github.tvbox.osc.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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
    public void extractsUnmarkedEpisodeSuffixWithoutConfusingYearsQualityOrDecimals() {
        assertEquals(7, DanmuEpisodeMatcher.episodeNumber("007"));
        assertEquals(1, DanmuEpisodeMatcher.episodeNumber("Winter Hunter (2026) - 01 - 1080P"));
        assertEquals(1, DanmuEpisodeMatcher.episodeNumber("Winter Hunter 01"));
        assertEquals(-1, DanmuEpisodeMatcher.episodeNumber("Winter Hunter 2026"));
        assertEquals(-1, DanmuEpisodeMatcher.episodeNumber("Winter Hunter 9.2"));
        assertEquals(-1, DanmuEpisodeMatcher.episodeNumber("Winter Hunter 1080"));
        assertEquals(-1, DanmuEpisodeMatcher.episodeNumber(""));
    }

    @Test
    public void candidateRankingAcceptsUnmarkedEpisodeAndRejectsWrongEpisodeAndSeason() {
        assertEquals(120, DanmuEpisodeMatcher.matchScore("冬城猎凶", "第01集", "冬城猎凶 01"));
        assertEquals(-1, DanmuEpisodeMatcher.matchScore("冬城猎凶", "第01集", "冬城猎凶 02"));
        assertEquals(-1, DanmuEpisodeMatcher.matchScore("冬城猎凶", "第2季第01集", "冬城猎凶 S01E01"));
        assertEquals(-1, DanmuEpisodeMatcher.matchScore("冬城猎凶", "第2季第01集", "冬城猎凶 01"));
        assertEquals(130, DanmuEpisodeMatcher.matchScore("冬城猎凶", "第2季第01集", "冬城猎凶 S02E01"));
        assertFalse(DanmuEpisodeMatcher.matchesSeriesTitle("冬城猎凶", "冬日猎人 第1集"));
        assertTrue(DanmuEpisodeMatcher.matchesSeriesTitle("冬城猎凶", "冬城猎凶 第1集"));
    }

    @Test
    public void matchesNonNumericEpisodeNamesOnlyByNormalizedEpisodeText() {
        assertEquals(100, DanmuEpisodeMatcher.matchScore("某电影", "正片", "某电影 正片"));
        assertEquals(-1, DanmuEpisodeMatcher.matchScore("某电影", "正片", "某电影 花絮"));
    }
}
