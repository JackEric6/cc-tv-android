package com.github.tvbox.osc.api;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Locale;

public final class DanmuEpisodeMatcher {
    private static final Pattern[] EPISODE_PATTERNS = {
            Pattern.compile("(?i)\\bS\\s*\\d+\\s*E\\s*0*(\\d+)\\b"),
            Pattern.compile("(?i)\\bEP(?:ISODE)?\\s*0*(\\d+)\\b"),
            Pattern.compile("(?:第\\s*\\d+\\s*季\\s*)?第?\\s*0*(\\d+)\\s*(?:集|话|回|期|話|화)"),
            Pattern.compile("^\\s*0*(\\d+)\\s*$")
    };
    private static final Pattern UNMARKED_EPISODE_PATTERN =
            Pattern.compile("(?:^|[\\s._#-])0*(\\d{1,3})(?=$|[\\s._#-])");

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
            try {
                int number = Integer.parseInt(matcher.group(1));
                if (number > 0 && (number < 1900 || number > 2099)) lastEpisode = number;
            } catch (NumberFormatException ignored) {
                // Ignore oversized tokens and continue with later episode-like tokens.
            }
        }
        return lastEpisode;
    }

    /** Ranks exact episode matches first and rejects candidates with a conflicting known episode. */
    public static int matchScore(String seriesTitle, String requestedEpisode, String candidateTitle) {
        if (requestedEpisode == null || requestedEpisode.trim().isEmpty()
                || candidateTitle == null || candidateTitle.trim().isEmpty()) return -1;
        int requestedNumber = episodeNumber(requestedEpisode);
        int candidateNumber = episodeNumber(candidateTitle);
        int score;
        if (requestedNumber > 0) {
            if (candidateNumber > 0) {
                if (candidateNumber != requestedNumber) return -1;
                score = 100;
            } else if (normalize(candidateTitle).contains(normalize(requestedEpisode))) {
                score = 70;
            } else {
                return -1;
            }
        } else if (normalize(candidateTitle).contains(normalize(requestedEpisode))) {
            score = 80;
        } else {
            return -1;
        }
        String normalizedTitle = normalize(seriesTitle);
        if (!normalizedTitle.isEmpty() && normalize(candidateTitle).contains(normalizedTitle)) {
            score += 20;
        }
        return score;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }
}
