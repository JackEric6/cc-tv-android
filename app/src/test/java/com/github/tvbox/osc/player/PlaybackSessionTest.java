package com.github.tvbox.osc.player;

import static org.junit.Assert.assertEquals;

import com.github.tvbox.osc.bean.VodInfo;

import org.junit.Test;

public class PlaybackSessionTest {

    @Test
    public void sessionSourceWinsOverReusableMetadataSource() {
        VodInfo vod = new VodInfo();
        vod.id = "movie-1";
        vod.sourceKey = "metadata-source";
        vod.playFlag = "line-a";
        vod.playIndex = 2;

        PlaybackSession session = new PlaybackSession(vod, "play-source", false);

        assertEquals("play-source", session.sourceKey());
        assertEquals("play-source", vod.sourceKey);
        assertEquals("play-source|movie-1|line-a|2", session.playbackKey());
    }

    @Test
    public void sessionFallsBackToVodSourceWhenExplicitSourceIsMissing() {
        VodInfo vod = new VodInfo();
        vod.id = "movie-2";
        vod.sourceKey = "play-source";

        PlaybackSession session = new PlaybackSession(vod, "  ", false);

        assertEquals("play-source", session.sourceKey());
        assertEquals("play-source", vod.sourceKey);
    }
}
