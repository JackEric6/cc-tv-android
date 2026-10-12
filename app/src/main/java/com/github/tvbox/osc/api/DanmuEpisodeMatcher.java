package com.github.tvbox.osc.api;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DanmuEpisodeMatcher {
    private static final Pattern SEASON_PATTERN = Pattern.compile(
            "(?i)\\b(?:S|SEASON)\\s*0*(\\d+)(?:\\s*E\\s*0*\\d+)?\\b");
    private static final Pattern CHINESE_SEASON_PATTERN = Pattern.compile("第\\s*0*(\\d+)\\s*季");
    private static final Pattern[] EPISODE_PATTERNS = {
            Pattern.compile("(?i)\\bS\\s*\\d+\\s*E\\s*0*(\\d+)\\b"),
            Pattern.compile("(?i)\\bEP(?:ISODE)?\\s*0*(\\d+)\\b"),
            Pattern.compile("(?:第\\s*\\d+\\s*季\\s*)?第?\\s*0*(\\d+)\\s*(?:集|话|回|期|話|화)"),
            Pattern.compile("^\\s*0*(\\d+)\\s*$")
    };
    private static final Pattern UNMARKED_EPISODE_PATTERN = Pattern.compile(
            "(?:^|[\\s._#\\-/\\[\\]()])0*(\\d{1,3})(?=$|[\\s._#\\-/\\[\\]()])");
    private static final Pattern MOVIE_PROMO_PATTERN = Pattern.compile("(?i)\\bpv\\b");
    private DanmuEpisodeMatcher() {
    }

    public static int episodeNumber(String text) {
        if (text == null || text.trim().isEmpty()) return -1;
        for (Pattern pattern : EPISODE_PATTERNS) {
            Matcher matcher = pattern.matcher(text);
            if (!matcher.find()) continue;
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ignored) {
                return -1;
            }
        }
        Matcher matcher = UNMARKED_EPISODE_PATTERN.matcher(text);
        int lastEpisode = -1;
        while (matcher.find()) {
            int start = matcher.start(1);
            int end = matcher.end(1);
            if (isDecimalPart(text, start, end)) continue;
            try {
                int number = Integer.parseInt(matcher.group(1));
                if (number > 0 && (number < 1900 || number > 2099) && !isVideoQuality(number)) {
                    lastEpisode = number;
                }
            } catch (NumberFormatException ignored) {
                // Ignore oversized values and continue with later episode-like tokens.
            }
        }
        return lastEpisode;
    }

    public static int seasonNumber(String text) {
        if (text == null || text.trim().isEmpty()) return -1;
        Matcher matcher = SEASON_PATTERN.matcher(text);
        if (matcher.find()) return parseNumber(matcher.group(1));
        matcher = CHINESE_SEASON_PATTERN.matcher(text);
        return matcher.find() ? parseNumber(matcher.group(1)) : -1;
    }

    /** Ranks matching candidates; a negative score means the episode cannot be verified. */
    public static int matchScore(String seriesTitle, String requestedEpisode, String candidateTitle) {
        if (requestedEpisode == null || requestedEpisode.trim().isEmpty()
                || candidateTitle == null || candidateTitle.trim().isEmpty()) return -1;

        int requestedNumber = episodeNumber(requestedEpisode);
        int candidateNumber = episodeNumber(candidateTitle);
        boolean matchesTitle = matchesSeriesTitle(seriesTitle, candidateTitle);
        boolean movieCandidate = isMovieCandidate(candidateTitle);
        if (movieCandidate && isMovieFragment(candidateTitle)) return -1;
        int score;
        if (requestedNumber > 0) {
            if (candidateNumber == requestedNumber) {
                score = 100;
            } else if (movieCandidate && matchesTitle) {
                score = 60;
            } else {
                return -1;
            }
        } else if (normalize(candidateTitle).contains(normalize(requestedEpisode))) {
            score = 80;
        } else if (movieCandidate && matchesTitle) {
            score = 60;
        } else {
            return -1;
        }

        int requestedSeason = seasonNumber(requestedEpisode);
        int candidateSeason = seasonNumber(candidateTitle);
        if (requestedSeason > 0) {
            if (requestedSeason != candidateSeason) return -1;
            score += 10;
        }
        if (matchesTitle) {
            score += 20;
        }
        return score;
    }

    public static boolean matchesSeriesTitle(String seriesTitle, String candidateTitle) {
        String normalizedTitle = normalize(seriesTitle);
        return !normalizedTitle.isEmpty() && normalize(candidateTitle).contains(normalizedTitle);
    }

    private static boolean isDecimalPart(String text, int start, int end) {
        return (start >= 2 && text.charAt(start - 1) == '.' && Character.isDigit(text.charAt(start - 2)))
                || (end + 1 < text.length() && text.charAt(end) == '.' && Character.isDigit(text.charAt(end + 1)));
    }

    private static boolean isVideoQuality(int number) {
        return number == 360 || number == 480 || number == 720 || number == 1080 || number == 1440;
    }

    private static boolean isMovieCandidate(String title) {
        String value = title.toLowerCase(java.util.Locale.ROOT);
        return value.contains("电影") || value.contains("剧场版")
                || value.contains("movie") || value.contains("film");
    }

    private static boolean isMovieFragment(String title) {
        String value = title.toLowerCase(java.util.Locale.ROOT);
        return value.contains("片段") || value.contains("预告") || value.contains("花絮")
                || value.contains("宣传片") || value.contains("trailer")
                || value.contains("preview") || value.contains("clip")
                || MOVIE_PROMO_PATTERN.matcher(value).find();
    }

    private static int parseNumber(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(java.util.Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }
}
