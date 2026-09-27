package com.github.tvbox.osc.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.github.tvbox.osc.bean.AbsSortJson;
import com.github.tvbox.osc.bean.MovieSort;
import com.google.gson.Gson;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class DefaultConfigSortTest {
    @Test
    public void absSortJsonPreservesParentId() {
        AbsSortJson json = new Gson().fromJson(
                "{\"class\":[{\"type_id\":20,\"type_name\":\"电影片\",\"type_pid\":0},"
                        + "{\"type_id\":21,\"type_name\":\"动作片\",\"type_pid\":20}]}",
                AbsSortJson.class);
        List<MovieSort.SortData> sorts = json.toAbsSortXml().classes.sortList;
        assertEquals("0", sorts.get(0).parentId);
        assertEquals("20", sorts.get(1).parentId);
    }

    @Test
    public void explicitParentChildrenHideParentsAndKeepIds() {
        List<MovieSort.SortData> input = new ArrayList<>(Arrays.asList(
                item("20", "电影片", "0"), item("21", "动作片", "20"),
                item("30", "电视剧", "0"), item("31", "国产剧", "30"),
                item("8", "伦理片", "0")));
        List<MovieSort.SortData> result = DefaultConfig.adjustSort(null, input, true);
        assertEquals("my0", result.get(0).id);
        assertEquals("31", result.get(1).id);
        assertEquals("21", result.get(2).id);
        assertEquals("8", result.get(3).id);
        assertFalse(ids(result).contains("20"));
        assertFalse(ids(result).contains("30"));
    }

    @Test
    public void flatCategoriesFilterOnlyObviousParentsAndSortEthicsLast() {
        List<MovieSort.SortData> input = new ArrayList<>(Arrays.asList(
                item("1", "电影", null), item("2", "电视剧", null),
                item("3", "喜剧片", null), item("4", "国产剧", null),
                item("5", "伦理片", null)));
        List<MovieSort.SortData> result = DefaultConfig.adjustSort(null, input, false);
        assertEquals(Arrays.asList("国产剧", "喜剧片", "伦理片"), names(result));
        assertEquals("4", result.get(0).id);
        assertTrue(result.get(2).name.contains("伦理"));
    }

    @Test
    public void flatParentOnlyCategoriesAreHidden() {
        List<MovieSort.SortData> input = new ArrayList<>(Arrays.asList(
                item("1", "电影", null), item("2", "电视剧", null)));
        assertTrue(DefaultConfig.adjustSort(null, input, false).isEmpty());
    }

    @Test
    public void fixedOrderCoversRequestedCategoriesAndEthicsLast() {
        List<MovieSort.SortData> input = new ArrayList<>(Arrays.asList(
                item("14", "伦理片", null), item("13", "未知分类", null),
                item("12", "短剧", null), item("11", "体育", null),
                item("10", "综艺节目", null), item("9", "动画", null),
                item("8", "纪录片", null), item("7", "战争片", null),
                item("6", "剧情片", null), item("5", "恐怖片", null),
                item("4", "科幻片", null), item("3", "动作片", null),
                item("2", "爱情片", null), item("1", "喜剧片", null),
                item("0", "国产剧", null)));
        assertEquals(Arrays.asList(
                "国产剧", "喜剧片", "爱情片", "动作片", "科幻片", "恐怖片",
                "剧情片", "战争片", "纪录片", "动画", "综艺节目", "体育", "短剧",
                "未知分类", "伦理片"), names(DefaultConfig.adjustSort(null, input, false)));
    }

    @Test
    public void rootMarkersAndWhitespaceDoNotDisableParentFiltering() {
        List<MovieSort.SortData> input = new ArrayList<>(Arrays.asList(
                item(" 20 ", "电影", " null "), item("21", "动作片", " 20 "),
                item("30", "电视剧", "   "), item("31", "国产剧", "30")));
        List<MovieSort.SortData> result = DefaultConfig.adjustSort(null, input, false);
        assertEquals(Arrays.asList("国产剧", "动作片"), names(result));
        assertFalse(ids(result).contains(" 20 "));
        assertFalse(ids(result).contains("30"));
    }

    private static MovieSort.SortData item(String id, String name, String parentId) {
        MovieSort.SortData item = new MovieSort.SortData(id, name);
        item.parentId = parentId;
        return item;
    }

    private static List<String> ids(List<MovieSort.SortData> data) {
        List<String> result = new ArrayList<>();
        for (MovieSort.SortData item : data) result.add(item.id);
        return result;
    }

    private static List<String> names(List<MovieSort.SortData> data) {
        List<String> result = new ArrayList<>();
        for (MovieSort.SortData item : data) result.add(item.name);
        return result;
    }
}
