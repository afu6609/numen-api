package com.dwinovo.numen.task;

import com.dwinovo.numen.task.control.BodyControlClass;
import com.dwinovo.numen.task.control.BodyControlPolicy;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionBrainControlSessionTest {

    @Test
    void onlyTheSameBodyActorAndSessionRenewsWithoutHandoff() {
        UUID bodyId = UUID.randomUUID();
        BodyControlPolicy.Request acquired =
                request(bodyId, "numen-chain:llm", "t1", 10L);

        assertTrue(CompanionBrain.sameControlSession(
                acquired, acquired.atTick(11L)));
        assertFalse(CompanionBrain.sameControlSession(
                acquired,
                request(bodyId, "numen-chain:llm", "t2", 11L)));
        assertFalse(CompanionBrain.sameControlSession(
                acquired,
                request(bodyId, "another-actor", "t1", 11L)));
        assertFalse(CompanionBrain.sameControlSession(
                acquired,
                request(UUID.randomUUID(), "numen-chain:llm", "t1", 11L)));
    }

    private static BodyControlPolicy.Request request(
            UUID bodyId,
            String actorId,
            String sessionId,
            long tick) {
        return new BodyControlPolicy.Request(
                bodyId,
                "Momo",
                actorId,
                sessionId,
                BodyControlClass.DIRECTED_ACTION,
                60,
                tick);
    }
}
