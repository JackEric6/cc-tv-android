package com.github.tvbox.osc.util;

import com.github.tvbox.osc.bean.Movie;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class DyttPlaybackLineFilter {
    private static final String SOURCE_KEY = "dyttzy";

    private DyttPlaybackLineFilter() {
    }

    public static List<Movie.Video.UrlBean.UrlInfo> filter(
            String sourceKey, List<Movie.Video.UrlBean.UrlInfo> lines) {
        if (!SOURCE_KEY.equals(sourceKey) || lines == null) return lines;

        List<Movie.Video.UrlBean.UrlInfo> filtered = new ArrayList<>();
        Movie.Video.UrlBean.UrlInfo alias = null;
        for (Movie.Video.UrlBean.UrlInfo line : lines) {
            if (line == null) continue;
            String flag = normalize(line.flag);
            if ("dyttm3u8".equals(flag)) {
                filtered.add(line);
                return filtered;
            }
            if (alias == null && "dyyttm3u8".equals(flag)) alias = line;
        }
        if (alias != null) filtered.add(alias);
        return filtered;
    }

    private static String normalize(String flag) {
        return flag == null ? "" : flag.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
