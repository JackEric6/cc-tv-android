package com.github.tvbox.osc.ui.page

import org.junit.Assert.assertEquals
import org.junit.Test

class HomePageSourceNameTest {

    @Test
    fun removesSourceSuffixes() {
        assertEquals("西瓜", sourceDisplayName("西瓜资源"))
        assertEquals("量子", sourceDisplayName("量子采集站"))
        assertEquals("虎牙", sourceDisplayName("虎牙资源采集网"))
        assertEquals("奇艺", sourceDisplayName("爱奇艺资源"))
        assertEquals("奇艺", sourceDisplayName("奇艺资源"))
    }

    @Test
    fun keepsMovieHeavenName() {
        assertEquals("天堂", sourceDisplayName("电影天堂资源"))
    }

    @Test
    fun limitsLongChineseNames() {
        assertEquals("超长名", sourceDisplayName("超长名称资源站"))
    }
}
