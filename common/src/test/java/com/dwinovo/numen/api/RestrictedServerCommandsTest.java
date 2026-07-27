package com.dwinovo.numen.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RestrictedServerCommandsTest {

    @Test
    void acceptsOnlyNarrowSelfAndWorldForms() {
        assertAllowed("/time set day", "time set day");
        assertAllowed("weather thunder 60", "weather thunder 60");
        assertAllowed("/tp Haa258", "tp Haa258");
        assertAllowed("/tp ~ ~1 -20.5", "tp ~ ~1 -20.5");
        assertAllowed("/gamemode survival", "gamemode survival");
        assertAllowed("/difficulty hard", "difficulty hard");
    }

    @Test
    void rejectsTargetsSelectorsAndNestedOrDestructiveCommands() {
        assertRejected("/gamemode creative Haa258");
        assertRejected("/tp Haa258 momo");
        assertRejected("/tp @a");
        assertRejected("/execute as Haa258 run stop");
        assertRejected("/time set day\nstop");
        assertRejected("/weather clear 0");
        assertRejected("/op momo");
        assertRejected("/fill 0 0 0 1 1 1 air");
    }

    private static void assertAllowed(String raw, String normalized) {
        RestrictedServerCommands.Validation result =
                RestrictedServerCommands.validate(raw);
        assertTrue(result.allowed(), result.error());
        assertEquals(normalized, result.command());
    }

    private static void assertRejected(String raw) {
        assertFalse(RestrictedServerCommands.validate(raw).allowed());
    }
}
