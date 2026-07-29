package com.dwinovo.numen.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

    private static ExternalTaskEvents.Event event(String id) {
        return new ExternalTaskEvents.Event(
                UUID.randomUUID(), "momo", id, "build", "done", "ok", 42);
    }
}
