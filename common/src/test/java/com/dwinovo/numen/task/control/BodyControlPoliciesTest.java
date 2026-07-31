package com.dwinovo.numen.task.control;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BodyControlPoliciesTest {

    @Test
    void closingOlderRegistrationDoesNotRemoveReplacement() {
        BodyControlPolicy first = new BodyControlPolicy() {
            @Override
            public Decision mayStart(Request request) {
                return Decision.deny("first");
            }
        };
        BodyControlPolicy second = new BodyControlPolicy() {
            @Override
            public Decision mayStart(Request request) {
                return Decision.deny("second");
            }
        };
        BodyControlPolicy.Request request = request("session");

        BodyControlPolicies.Registration firstRegistration =
                BodyControlPolicies.install(first);
        BodyControlPolicies.Registration secondRegistration =
                BodyControlPolicies.install(second);
        try {
            firstRegistration.close();
            assertEquals(
                    "second",
                    BodyControlPolicies.mayStart(request).reason());
        } finally {
            secondRegistration.close();
            firstRegistration.close();
        }

        assertTrue(BodyControlPolicies.mayStart(request).granted());
    }

    @Test
    void delegatesTheCompleteSessionRequest() {
        AtomicReference<BodyControlPolicy.Request> probed =
                new AtomicReference<>();
        AtomicReference<BodyControlPolicy.Request> started =
                new AtomicReference<>();
        AtomicReference<BodyControlPolicy.Request> acquired =
                new AtomicReference<>();
        AtomicReference<BodyControlPolicy.Request> owned =
                new AtomicReference<>();
        BodyControlPolicy policy = new BodyControlPolicy() {
            @Override
            public Decision mayProbe(Request request) {
                probed.set(request);
                return Decision.deny("probe");
            }

            @Override
            public Decision mayStart(Request request) {
                started.set(request);
                return Decision.deny("start");
            }

            @Override
            public Decision acquire(Request request) {
                acquired.set(request);
                return Decision.deny("acquire");
            }

            @Override
            public boolean owns(Request request) {
                owned.set(request);
                return false;
            }
        };
        BodyControlPolicy.Request request = request("task-t42");

        try (BodyControlPolicies.Registration ignored =
                     BodyControlPolicies.install(policy)) {
            assertEquals(
                    "probe",
                    BodyControlPolicies.mayProbe(request).reason());
            assertEquals(
                    "start",
                    BodyControlPolicies.mayStart(request).reason());
            assertEquals(
                    "acquire",
                    BodyControlPolicies.acquire(request).reason());
            assertFalse(BodyControlPolicies.owns(request));
        }

        assertSame(request, probed.get());
        assertSame(request, started.get());
        assertSame(request, acquired.get());
        assertSame(request, owned.get());
        assertEquals("task-t42", acquired.get().sessionId());
    }

    @Test
    void delegatesSessionReleaseAndBodyClear() {
        AtomicReference<BodyControlPolicy.Request> released =
                new AtomicReference<>();
        AtomicReference<UUID> cleared = new AtomicReference<>();
        BodyControlPolicy policy = new BodyControlPolicy() {
            @Override
            public void release(Request request) {
                released.set(request);
            }

            @Override
            public void clear(UUID bodyId) {
                cleared.set(bodyId);
            }
        };
        BodyControlPolicy.Request request = request("task-t7");

        try (BodyControlPolicies.Registration ignored =
                     BodyControlPolicies.install(policy)) {
            BodyControlPolicies.release(request);
            BodyControlPolicies.clear(request.bodyId());
        }

        assertSame(request, released.get());
        assertEquals(request.bodyId(), cleared.get());
    }

    @Test
    void actorOnlyRequestAndReleaseRemainCompatible() {
        BodyControlPolicy.Request request = new BodyControlPolicy.Request(
                UUID.randomUUID(),
                "Momo",
                "legacy-actor",
                BodyControlClass.DIRECTED_ACTION,
                60,
                100L);
        AtomicReference<UUID> releasedBody = new AtomicReference<>();
        AtomicReference<String> releasedActor = new AtomicReference<>();
        BodyControlPolicy legacyPolicy = new BodyControlPolicy() {
            @Override
            public void release(UUID bodyId, String actorId) {
                releasedBody.set(bodyId);
                releasedActor.set(actorId);
            }
        };

        assertEquals("legacy-actor", request.sessionId());
        legacyPolicy.release(request);
        assertEquals(request.bodyId(), releasedBody.get());
        assertEquals(request.actorId(), releasedActor.get());
    }

    @Test
    void atTickPreservesTheExactControlSession() {
        BodyControlPolicy.Request acquired = request("task-t99");
        BodyControlPolicy.Request current =
                acquired.atTick(acquired.serverTick() + 17);

        assertEquals(acquired.bodyId(), current.bodyId());
        assertEquals(acquired.bodyName(), current.bodyName());
        assertEquals(acquired.actorId(), current.actorId());
        assertEquals(acquired.sessionId(), current.sessionId());
        assertEquals(acquired.controlClass(), current.controlClass());
        assertEquals(acquired.priority(), current.priority());
        assertEquals(acquired.serverTick() + 17, current.serverTick());
    }

    private static BodyControlPolicy.Request request(String sessionId) {
        return new BodyControlPolicy.Request(
                UUID.randomUUID(),
                "Momo",
                "numen-chain:llm",
                sessionId,
                BodyControlClass.DIRECTED_ACTION,
                60,
                100L);
    }
}
