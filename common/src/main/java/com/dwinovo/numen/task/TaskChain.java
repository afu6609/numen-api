package com.dwinovo.numen.task;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.control.BodyControlClass;

/**
 * One competing behavior for a companion body: the
 * scheduler ({@link CompanionBrain}) holds an ordered list of chains and, each
 * server tick, ticks ONLY the highest-priority active one. A chain that is
 * dormant returns {@link Float#NEGATIVE_INFINITY} from {@link #getPriority}; a
 * survival chain (hunger, threat, stuck, fall) spikes above the base LLM-task
 * priority exactly when its condition fires, preempting the LLM's task, then drops
 * back so the task resumes.
 *
 * <p>Survival chains are AUTONOMOUS: they carry no {@code toolCallId} and emit no
 * {@code TaskResult} — they just take the body for a while. Only {@link
 * LlmTaskChain} (the base-priority chain running the owner's tool-call task)
 * produces a result, and it does so exactly once, on its own task's termination.
 */
public interface TaskChain {

    /** Base priority of the LLM task chain; survival chains spike ABOVE this to preempt. */
    float LLM_BASE_PRIORITY = 0.0f;

    /** How much this chain wants control right now. {@link Float#NEGATIVE_INFINITY} = dormant. */
    float getPriority(NumenPlayer companion);

    /** Advance this chain by one server tick (only called when it is the winner). */
    void tick(NumenPlayer companion);

    /** Called on the tick this chain LOSES control to a higher-priority one. */
    void onInterrupt(NumenPlayer companion);

    /**
     * Called on the edge where this chain regains control after another owner.
     * Most reflexes need no special work; task chains use it to restore a
     * suspended state machine without replaying {@code start()}.
     */
    default void onResume(NumenPlayer companion) {}

    /** Stable short name for logging / the debug overlay. */
    String name();

    /**
     * Whole-body control class used by an optional external arbiter.
     * Third-party chains default to autonomous, the safest classification when
     * a supervised server has not explicitly reviewed them.
     */
    default BodyControlClass controlClass() {
        return BodyControlClass.AUTONOMOUS_ACTION;
    }

    /** Stable actor identity for lease renewal and release. */
    default String controlActorId() {
        return "numen-chain:" + name();
    }

    /**
     * Identity of the current control session owned by this actor.
     *
     * <p>Long-lived chains default to one session per actor. Task-backed chains
     * override this so a late release or renewal from an older task cannot be
     * mistaken for the current task.
     */
    default String controlSessionId() {
        return controlActorId();
    }

    /** Priority is independent of the chain's historical float bid. */
    default int controlPriority() {
        return controlClass().defaultPriority();
    }
}
