package com.dwinovo.numen.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalTaskEventsTest {

    @AfterEach
    void clearListeners() {
        ExternalTaskEvents.clearForTests();
    }

    @Test
    void subscriptionReceivesFutureEventsAndCanBeClosed() {
        List<ExternalTaskEvents.Event> received = new ArrayList<>();
        ExternalTaskEvents.Subscription subscription =
                ExternalTaskEvents.subscribe(received::add);
        ExternalTaskEvents.Event first = event("t1");
        ExternalTaskEvents.publish(first);
        subscription.close();
        ExternalTaskEvents.publish(event("t2"));

        assertEquals(List.of(first), received);
    }

    @Test
    void claimingSubscriptionSuppressesOnlyOwnedExternalBrainCompletion() {
        List<ExternalTaskEvents.Event> observed = new ArrayList<>();
        ExternalTaskEvents.subscribe(observed::add);
        ExternalTaskEvents.Subscription claiming =
                ExternalTaskEvents.subscribeClaiming(event -> event.taskId().equals("t-owned"));

        ExternalTaskEvents.Event owned = event("t-owned");
        ExternalTaskEvents.Event ordinary = event("t-other");

        assertTrue(ExternalTaskEvents.publishClaimable(owned));
        assertFalse(ExternalTaskEvents.publishClaimable(ordinary));
        assertEquals(List.of(owned, ordinary), observed);

        claiming.close();
        assertFalse(ExternalTaskEvents.publishClaimable(owned));
    }

    private static ExternalTaskEvents.Event event(String id) {
        return new ExternalTaskEvents.Event(
                UUID.randomUUID(), "momo", id, "build", "done", "ok", 42);
    }
}
