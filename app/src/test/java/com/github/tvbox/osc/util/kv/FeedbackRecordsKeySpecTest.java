package com.github.tvbox.osc.util.kv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.github.tvbox.osc.util.HawkConfig;
import com.google.gson.reflect.TypeToken;

import org.junit.Test;

import java.lang.reflect.Type;
import java.util.ArrayList;

public class FeedbackRecordsKeySpecTest {
    @Test
    public void feedbackRecordsAreRegisteredAsStringLists() {
        Type registered = new KVKeySpec().typeOf(HawkConfig.FEEDBACK_RECORDS);
        Type expected = new TypeToken<ArrayList<String>>() { }.getType();

        assertNotNull(registered);
        assertEquals(expected, registered);
    }
}
