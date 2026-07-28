package com.dwinovo.numen.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionsTest {

    @Test
    void serverToolsCannotBypassTheThirtySecondDeathDelay() {
        long diedAt = 1_000L;
        assertFalse(Companions.respawnDelayElapsed(diedAt + 599L, diedAt));
        assertTrue(Companions.respawnDelayElapsed(diedAt + 600L, diedAt));
    }
}
