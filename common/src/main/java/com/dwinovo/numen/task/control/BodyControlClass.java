package com.dwinovo.numen.task.control;

/**
 * Stable compatibility vocabulary for physical body-control requests.
 *
 * <p>The independent Momo kernel maps these values into its own control plane;
 * numen-api itself remains usable without that kernel because the default
 * policy permits every request.
 */
public enum BodyControlClass {
    CRITICAL_REFLEX(100),
    DEFENSIVE_REFLEX(90),
    MAINTENANCE_REFLEX(80),
    RECOVERY_REFLEX(70),
    DIRECTED_ACTION(60),
    AUTONOMOUS_ACTION(20),
    SOCIAL_POSTURE(10);

    private final int defaultPriority;

    BodyControlClass(int defaultPriority) {
        this.defaultPriority = defaultPriority;
    }

    public int defaultPriority() {
        return defaultPriority;
    }
}
