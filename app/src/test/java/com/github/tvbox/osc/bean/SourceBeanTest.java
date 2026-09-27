package com.github.tvbox.osc.bean;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;

import org.junit.Test;

public class SourceBeanTest {
    @Test
    public void indexesCmsWrappedApiByDecodedRemoteSource() {
        SourceBean source = new SourceBean();
        source.setApi("http://127.0.0.1:9978/cms?source=https%3A%2F%2Fexample.com%2Fapi");
        source.setBackupApi(Arrays.asList(" https://backup.example/api ", "", "https://backup.example/api"));
        source.setBackupDomain(Arrays.asList("https://mirror.example/", "https://mirror.example/"));

        assertEquals(Arrays.asList("https://backup.example/api"),
                SourceBean.getBackupApisForApi("https://example.com/api"));
        assertEquals(Arrays.asList("https://mirror.example/"),
                SourceBean.getBackupDomainsForApi("https://example.com/api"));
        assertEquals(Arrays.asList("https://backup.example/api"),
                SourceBean.getBackupApisForApi("http://127.0.0.1:9978/cms?source=https%3A%2F%2Fexample.com%2Fapi"));
    }
}
