package com.github.tvbox.osc.api;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DanmuEpisodeMatcher {
    private static final Pattern[] EPISODE_PATTERNS = {
            Pattern.compile("(?i)\\bS\\s*\\d+\\s*E\\s*0*(\\d+)\\b"),
            Pattern.compile("(?i)\\bEP(?:ISODE)?\\s*0*(\\d+)\\b"),
            Pattern.compile("(?:第\\s*\\d+\\s*季\\s*)?第?\\s*0*(\\d+)\\s*(?:集|话|回|期|話|화)"),
            Pattern.compile("^\\s*0*(\\d+)\\s*$")
    };

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
        return -1;
    }
}
