package com.github.tvbox.osc.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import com.github.tvbox.osc.bean.Movie;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class DyttPlaybackLineFilterTest {
    @Test
    public void canonicalDyttLineWinsAndOnlyOneLineRemains() {
        Movie.Video.UrlBean.UrlInfo target = line("dyttm3u8");
        List<Movie.Video.UrlBean.UrlInfo> result = DyttPlaybackLineFilter.filter(
                "dyttzy", Arrays.asList(line("dytt"), target, line("dyyttm3u8")));

        assertEquals(1, result.size());
        assertSame(target, result.get(0));
    }

    @Test
    public void doubleYAliasIsUsedOnlyWhenCanonicalFlagIsMissing() {
        Movie.Video.UrlBean.UrlInfo alias = line("dyyttm3u8");
        List<Movie.Video.UrlBean.UrlInfo> result = DyttPlaybackLineFilter.filter(
                "dyttzy", Arrays.asList(line("dytt"), alias));

        assertEquals(1, result.size());
        assertSame(alias, result.get(0));
    }

    @Test
    public void otherSourcesAreNotFiltered() {
        List<Movie.Video.UrlBean.UrlInfo> lines = Arrays.asList(line("dyttm3u8"), line("m3u8"));

        assertSame(lines, DyttPlaybackLineFilter.filter("xgzy", lines));
    }

    private static Movie.Video.UrlBean.UrlInfo line(String flag) {
        Movie.Video.UrlBean.UrlInfo line = new Movie.Video.UrlBean.UrlInfo();
        line.flag = flag;
        return line;
    }
}
