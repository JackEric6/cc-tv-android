package com.github.tvbox.osc.bean;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

public class SourceBean {
    private String key;
    private String name;
    private String api;
    private int type;   // 0 xml 1 json 3 Spider
    private int searchable; // 是否可搜索
    private int quickSearch; // 是否可以快速搜索
    private int changeable = 1;
    private boolean disabled;
    private boolean hidden;
    private int filterable; // 是否可以站点选择
    private String playerUrl; // 站点解析Url
    private String ext; // 扩展数据
    private String jar; // 自定义jar
    private ArrayList<String> categories = null; // 分类&排序
    private int playerType; // 1 ijk 2 exo 10 mxplayer -1 以参数设置页面的为准
    private int timeout; // 站点播放信息获取超时，单位秒
    private String clickSelector; // 需要点击播放的嗅探站点selector   ddrk.me;#id
    private String style; // 展示风格
    private String icon; // 站点头像/logo
    private ArrayList<String> backupApi = new ArrayList<>();
    private ArrayList<String> backupDomain = new ArrayList<>();

    /**
     * CmsRequestProcess 只收到 source URL,而不是 SourceBean 本身。
     * 配置解析完成后把备用地址按 API 建立轻量索引,让本地 CMS 请求仍能拿到同一站点的备用链。
     */
    private static final Map<String, List<String>> BACKUP_API_INDEX = new ConcurrentHashMap<>();
    private static final Map<String, List<String>> BACKUP_DOMAIN_INDEX = new ConcurrentHashMap<>();

    private String safeString(String value) {
        return value == null ? "" : value;
    }

    public String getKey() {
        return safeString(key);
    }

    public void setKey(String key) {
        this.key = safeString(key);
    }

    public String getName() {
        return safeString(name);
    }

    public void setName(String name) {
        this.name = safeString(name);
    }

    public String getApi() {
        return safeString(api);
    }

    public void setApi(String api) {
        this.api = safeString(api);
        registerBackupAddresses();
    }

    public void setPlayerUrl(String playerUrl) {
        this.playerUrl = safeString(playerUrl);
    }

    public String getPlayerUrl() {
        return safeString(playerUrl);
    }

    public int getType() {
        return type;
    }

    public void setType(int type) {
        this.type = type;
    }

    public boolean isSearchable() {
        return searchable != 0 && !disabled && !hidden;
    }

    public void setSearchable(int searchable) {
        this.searchable = searchable;
    }

    public boolean isQuickSearch() {
        return quickSearch != 0 && !disabled && !hidden;
    }

    public void setQuickSearch(int quickSearch) {
        this.quickSearch = quickSearch;
    }

    public boolean isChangeable() {
        return changeable != 0 && !disabled && !hidden;
    }

    public void setChangeable(int changeable) {
        this.changeable = changeable;
    }

    public boolean isDisabled() {
        return disabled;
    }

    public void setDisabled(boolean disabled) {
        this.disabled = disabled;
    }

    public boolean isHidden() {
        return hidden;
    }

    public void setHidden(boolean hidden) {
        this.hidden = hidden;
    }

    public int getFilterable() {
        return filterable;
    }

    public void setFilterable(int filterable) {
        this.filterable = filterable;
    }

    public String getExt() {
        return safeString(ext);
    }

    public void setExt(String ext) {
        this.ext = safeString(ext);
    }

    public ArrayList<String> getCategories() {
        return categories;
    }

    public void setCategories(ArrayList<String> categories) {
        this.categories = categories;
    }

    public String getJar() {
        return safeString(jar);
    }

    public void setJar(String jar) {
        this.jar = safeString(jar);
    }

    public int getPlayerType() { return playerType; }

    public void setPlayerType(int playerType) { this.playerType = playerType; }

    public int getTimeout() { return timeout; }

    public void setTimeout(int timeout) { this.timeout = timeout; }

    public int getPlayTimeoutSeconds() {
        return timeout > 0 ? Math.max(5, Math.min(60, timeout)) : 15;
    }

    public String getClickSelector() { return safeString(clickSelector); }

    public void setClickSelector(String clickSelector) { this.clickSelector = safeString(clickSelector); }


    public String getStyle() { return safeString(style); }

    public void setStyle(String style) { this.style = safeString(style); }

    public String getIcon() { return safeString(icon); }

    public void setIcon(String icon) { this.icon = safeString(icon); }

    public ArrayList<String> getBackupApi() {
        return new ArrayList<>(backupApi);
    }

    public ArrayList<String> getBackupApis() {
        return getBackupApi();
    }

    public void setBackupApi(List<String> values) {
        this.backupApi = cleanAddressList(values);
        registerBackupAddresses();
    }

    public void setBackupApis(List<String> values) {
        setBackupApi(values);
    }

    public ArrayList<String> getBackupDomain() {
        return new ArrayList<>(backupDomain);
    }

    public ArrayList<String> getBackupDomains() {
        return getBackupDomain();
    }

    public void setBackupDomain(List<String> values) {
        this.backupDomain = cleanAddressList(values);
        registerBackupAddresses();
    }

    public void setBackupDomains(List<String> values) {
        setBackupDomain(values);
    }

    /** 返回指定 CMS API 对应的配置备用 API,不暴露可变内部列表。 */
    public static List<String> getBackupApisForApi(String api) {
        return lookup(BACKUP_API_INDEX, api);
    }

    /** 返回指定 CMS API 对应的备用站点域名,不暴露可变内部列表。 */
    public static List<String> getBackupDomainsForApi(String api) {
        return lookup(BACKUP_DOMAIN_INDEX, api);
    }

    private void registerBackupAddresses() {
        for (String apiKey : apiKeys(api)) {
            BACKUP_API_INDEX.put(apiKey, Collections.unmodifiableList(new ArrayList<>(backupApi)));
            BACKUP_DOMAIN_INDEX.put(apiKey, Collections.unmodifiableList(new ArrayList<>(backupDomain)));
        }
    }

    private static List<String> lookup(Map<String, List<String>> index, String api) {
        String key = normalizeApiKey(api);
        List<String> values = index.get(key);
        return values == null ? Collections.<String>emptyList() : new ArrayList<>(values);
    }

    private static ArrayList<String> cleanAddressList(List<String> values) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                if (value == null) continue;
                String address = value.trim();
                if (!address.isEmpty()) unique.add(address);
            }
        }
        return new ArrayList<>(unique);
    }

    private static String normalizeApiKey(String value) {
        if (value == null) return "";
        return value.trim();
    }

    /**
     * 本地 CMS 适配器使用 /cms?source=... 包装真实远程 API。索引同时登记包装地址和
     * source 解码后的地址,这样请求处理器只拿到真实远程地址时仍能找到配置备用链。
     */
    private static List<String> apiKeys(String value) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        String original = normalizeApiKey(value);
        if (original.isEmpty()) return new ArrayList<>(keys);
        keys.add(original);
        String wrappedSource = wrappedSource(original);
        if (!wrappedSource.isEmpty()) keys.add(wrappedSource);
        return new ArrayList<>(keys);
    }

    private static String wrappedSource(String value) {
        try {
            URI uri = URI.create(value);
            String path = uri.getPath();
            if (path == null || !path.endsWith("/cms")) return "";
            String query = uri.getRawQuery();
            if (query == null) return "";
            for (String pair : query.split("&")) {
                int separator = pair.indexOf('=');
                String name = separator < 0 ? pair : pair.substring(0, separator);
                if (!"source".equals(URLDecoder.decode(name, StandardCharsets.UTF_8.name()))) continue;
                String rawSource = separator < 0 ? "" : pair.substring(separator + 1);
                return normalizeApiKey(URLDecoder.decode(rawSource, StandardCharsets.UTF_8.name()));
            }
        } catch (Exception ignored) {
            // 无法解析的地址仍保留原始 api 索引,请求阶段再报告格式错误。
        }
        return "";
    }
}
