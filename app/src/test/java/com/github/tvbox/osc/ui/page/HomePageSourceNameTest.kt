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
    fun keepsThreeSixtySourceNameIntact() {
        assertEquals("360", sourceDisplayName("三六", "360zy"))
        assertEquals("360", sourceDisplayName("360资源"))
    }

    @Test
    fun limitsSourceNamesToTwoCharacters() {
        assertEquals("超长", sourceDisplayName("超长名称资源站"))
        assertEquals("爱看", sourceDisplayName("爱看机器人"))
        assertEquals("瓜子", sourceDisplayName("瓜子影视", "guazi"))
        assertEquals("影视", sourceDisplayName(null))
    }

    @Test
    fun removesEmojiPrefixBeforeShortening() {
        assertEquals("比特", sourceDisplayName("🍄比特┃不卡"))
        assertEquals("糯米", sourceDisplayName("🍓糯米┃秒播"))
        assertEquals("厂长", sourceDisplayName("📔厂长┃JAR"))
        assertEquals("荐片", sourceDisplayName("🥝荐片┃JAR"))
    }
}
