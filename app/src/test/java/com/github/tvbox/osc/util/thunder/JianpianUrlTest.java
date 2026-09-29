package com.github.tvbox.osc.util.thunder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class JianpianUrlTest {
    @Test
    public void recognizesJianpianFtpP2pAddress() {
        assertTrue(Jianpian.isP2pSourceUrl(
                "ftp://a.gbl.114s.com:20320/4549/少帅第43集.mp4"));
    }

    @Test
    public void downloadUrlUsesP2pBridgeWithoutChangingEpisodeIdentity() {
        String play = "http://127.0.0.1:9978/jianpian/play.m3u8"
                + "?work_id=4549&episode_key=e43&episode_index=42&line_key=p2p";
        String download = Jianpian.downloadUrlFor(play);
        assertTrue(Jianpian.isDownloadBridgeUrl(download));
        assertTrue(download.contains("work_id=4549"));
        assertTrue(download.contains("episode_key=e43"));
        assertEquals("http://127.0.0.1:9978/jianpian/p2p.m3u8"
                        + "?work_id=4549&episode_key=e43&episode_index=42&line_key=p2p",
                download);
    }
}
