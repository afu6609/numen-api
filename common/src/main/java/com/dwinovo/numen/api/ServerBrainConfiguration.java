package com.dwinovo.numen.api;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.mcp.server.ServerBrainEvents;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Trusted, server-wide control plane for the external dedicated-server brain.
 *
 * <p>Commands publish a request into {@code poll_server_events}; the Forge
 * process does not mutate or guess the sidecar's runtime configuration. The
 * sidecar applies the request and calls {@code report_brain_config_state}. Only
 * that acknowledgement updates the authoritative cached state and notifies the
 * original requester.
 */
public final class ServerBrainConfiguration {

    public static final int MAX_MODEL_LENGTH = 128;
    public static final int MAX_REASONING_LENGTH = 32;
    public static final int MAX_REVISION_LENGTH = 128;
    public static final int MAX_ERROR_LENGTH = 512;
    public static final int MAX_CATALOG_ENTRIES = 128;
    public static final int MAX_REASONING_PER_MODEL = 16;
    public static final long REQUEST_TTL_MILLIS = 30_000L;
    public static final long ACK_GRACE_MILLIS = 30_000L;

    private static final int MAX_PENDING_REQUESTS = 256;
    private static final Object LOCK = new Object();
    private static final LinkedHashMap<String, PendingRequest> PENDING =
            new LinkedHashMap<>();
    private static final CopyOnWriteArrayList<Consumer<Report>> LISTENERS =
            new CopyOnWriteArrayList<>();

    private static volatile Snapshot snapshot;
    private static volatile Report lastReport;
    private static volatile LongSupplier clock =
            System::currentTimeMillis;

    private ServerBrainConfiguration() {}

    /** Operation names used on the JSON wire. */
    public enum Action {
        GET("get"),
        LIST("list"),
        SET("set");

        private final String wireName;

        Action(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }
    }

    /** Stable requester identity; command-source objects never cross threads. */
    public record Requester(String kind, UUID uuid, String name) {
        public Requester {
            kind = requiredBounded(kind, "requester kind", 16);
            if (!kind.equals("player") && !kind.equals("console")) {
                throw new IllegalArgumentException(
                        "requester kind must be player or console");
            }
            if (kind.equals("player") && uuid == null) {
                throw new IllegalArgumentException(
                        "player requester needs a uuid");
            }
            name = requiredBounded(name, "requester name", 64);
        }

        public static Requester player(UUID uuid, String name) {
            return new Requester("player", uuid, name);
        }

        public static Requester console(String name) {
            return new Requester("console", null, name);
        }

        Map<String, Object> wireData() {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("kind", kind);
            data.put("name", name);
            if (uuid != null) data.put("uuid", uuid.toString());
            return Map.copyOf(data);
        }
    }

    /** One model and the reasoning strengths the running brain says it accepts. */
    public record CatalogEntry(String model, List<String> reasoning) {
        public CatalogEntry {
            model = requiredBounded(model, "catalog model", MAX_MODEL_LENGTH);
            if (reasoning == null || reasoning.isEmpty()) {
                throw new IllegalArgumentException(
                        "catalog reasoning must not be empty");
            }
            if (reasoning.size() > MAX_REASONING_PER_MODEL) {
                throw new IllegalArgumentException(
                        "catalog reasoning exceeds "
                                + MAX_REASONING_PER_MODEL + " entries");
            }
            List<String> clean = new ArrayList<>(reasoning.size());
            for (String value : reasoning) {
                String item = requiredBounded(
                        value, "catalog reasoning", MAX_REASONING_LENGTH);
                if (!clean.contains(item)) clean.add(item);
            }
            reasoning = List.copyOf(clean);
        }
    }

    /** Last complete state explicitly reported by the external brain. */
    public record Snapshot(
            String model,
            String reasoning,
            String revision,
            List<CatalogEntry> catalog,
            long reportedAtEpochMillis) {
        public Snapshot {
            model = requiredBounded(model, "current model", MAX_MODEL_LENGTH);
            reasoning = requiredBounded(
                    reasoning, "current reasoning", MAX_REASONING_LENGTH);
            revision = requiredBounded(
                    revision, "current revision", MAX_REVISION_LENGTH);
            if (catalog == null || catalog.size() > MAX_CATALOG_ENTRIES) {
                throw new IllegalArgumentException(
                        "catalog exceeds " + MAX_CATALOG_ENTRIES + " entries");
            }
            catalog = List.copyOf(catalog);
        }
    }

    /**
     * A report accepted from the sidecar. {@link #requester} is resolved from
     * the server's pending-request map, never trusted from MCP input.
     */
    public record Report(
            String requestId,
            Action action,
            boolean success,
            boolean applied,
            String error,
            Snapshot snapshot,
            Requester requester) {}

    private record PendingRequest(
            Action action,
            String model,
            String reasoning,
            Requester requester,
            long expiresAtEpochMillis,
            long ackDeadlineEpochMillis) {}

    /**
     * Publish a query or an atomic model/reasoning update.
     *
     * @return an opaque request id used to correlate the later acknowledgement
     */
    public static String request(
            Action action,
            String model,
            String reasoning,
            Requester requester,
            long gameTime) {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(requester, "requester");
        expireRequests();

        String cleanModel = optionalBounded(
                model, "model", MAX_MODEL_LENGTH);
        String cleanReasoning = optionalBounded(
                reasoning, "reasoning", MAX_REASONING_LENGTH);
        if (cleanReasoning != null) {
            cleanReasoning = cleanReasoning.toLowerCase(Locale.ROOT);
        }
        if (action == Action.SET
                && (cleanModel == null || cleanReasoning == null)) {
            throw new IllegalArgumentException(
                    "set requires both model and reasoning");
        }
        if (action != Action.SET
                && (cleanModel != null || cleanReasoning != null)) {
            throw new IllegalArgumentException(
                    action.wireName() + " does not accept model or reasoning");
        }

        String requestId = "cfg-" + UUID.randomUUID().toString()
                .replace("-", "");
        long now = nowMillis();
        long expiresAtEpochMillis = now + REQUEST_TTL_MILLIS;
        long ackDeadlineEpochMillis =
                expiresAtEpochMillis + ACK_GRACE_MILLIS;
        synchronized (LOCK) {
            if (PENDING.size() >= MAX_PENDING_REQUESTS) {
                throw new IllegalStateException(
                        "too many brain configuration requests are awaiting acknowledgement");
            }
            PENDING.put(
                    requestId,
                    new PendingRequest(
                            action,
                            cleanModel,
                            cleanReasoning,
                            requester,
                            expiresAtEpochMillis,
                            ackDeadlineEpochMillis));
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("requestId", requestId);
        data.put("action", action.wireName());
        if (cleanModel != null) data.put("model", cleanModel);
        if (cleanReasoning != null) data.put("reasoning", cleanReasoning);
        data.put("requester", requester.wireData());
        data.put("expiresAtEpochMillis", expiresAtEpochMillis);
        try {
            ServerBrainEvents.publishBrainConfigRequest(
                    requestId, Map.copyOf(data), gameTime);
        } catch (RuntimeException ex) {
            synchronized (LOCK) {
                PENDING.remove(requestId);
            }
            throw ex;
        }
        return requestId;
    }

    public static Snapshot snapshot() {
        return snapshot;
    }

    public static Report lastReport() {
        return lastReport;
    }

    public static int pendingCount() {
        expireRequests();
        synchronized (LOCK) {
            return PENDING.size();
        }
    }

    /**
     * Expire requests which can no longer be safely applied. Forge calls this
     * periodically so an offline sidecar cannot leave requesters waiting or
     * eventually exhaust the bounded pending map.
     *
     * @return number of requests expired by this call
     */
    public static int expireRequests() {
        long now = nowMillis();
        List<Report> expired = new ArrayList<>();
        synchronized (LOCK) {
            Iterator<Map.Entry<String, PendingRequest>> iterator =
                    PENDING.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, PendingRequest> entry = iterator.next();
                PendingRequest pending = entry.getValue();
                if (now <= pending.ackDeadlineEpochMillis()) continue;
                iterator.remove();
                expired.add(new Report(
                        entry.getKey(),
                        pending.action(),
                        false,
                        false,
                        "server-brain did not acknowledge the request before it expired",
                        snapshot,
                        pending.requester()));
            }
        }
        for (Report report : expired) publishReport(report);
        return expired.size();
    }

    /** Register a lightweight listener; MCP worker threads invoke it. */
    public static void addReportListener(Consumer<Report> listener) {
        LISTENERS.add(Objects.requireNonNull(listener, "listener"));
    }

    /**
     * Accept one already validated report from the loopback MCP control tool.
     * This method is transport plumbing; server mods should only publish
     * requests and observe reports.
     */
    @Internal
    public static Report acceptReport(
            String requestId,
            boolean success,
            boolean applied,
            String error,
            String model,
            String reasoning,
            String revision,
            List<CatalogEntry> catalog) {
        String cleanRequestId = optionalBounded(
                requestId, "request_id", 128);
        String cleanError = optionalBounded(error, "error", MAX_ERROR_LENGTH);
        if (!success && cleanError == null) {
            cleanError = "server-brain rejected the request";
        }

        long reportedRevision = positiveRevision(revision);
        Snapshot reportedState = new Snapshot(
                model,
                reasoning,
                Long.toString(reportedRevision),
                catalog,
                nowMillis());
        Snapshot currentState;
        long currentRevision;
        boolean staleRevision;
        boolean conflictingRevision;
        PendingRequest pending;
        synchronized (LOCK) {
            currentState = snapshot;
            currentRevision = currentState == null
                    ? 0L
                    : positiveRevision(currentState.revision());
            staleRevision =
                    currentState != null
                            && reportedRevision < currentRevision;
            conflictingRevision =
                    currentState != null
                            && reportedRevision == currentRevision
                            && (!reportedState.model().equals(
                                            currentState.model())
                                    || !reportedState.reasoning().equals(
                                            currentState.reasoning()));
            if (!staleRevision && !conflictingRevision) {
                snapshot = reportedState;
            }
            pending = cleanRequestId == null
                    ? null
                    : PENDING.remove(cleanRequestId);
        }
        boolean effectiveSuccess =
                success
                        && applied
                        && !staleRevision
                        && !conflictingRevision;
        String effectiveError = cleanError;
        if (staleRevision) {
            effectiveError = "server-brain reported stale revision "
                    + reportedRevision + " while revision "
                    + currentRevision + " is already active";
        } else if (conflictingRevision) {
            effectiveError = "server-brain reused revision "
                    + reportedRevision
                    + " for a different active model/reasoning pair";
        }
        if (pending != null
                && pending.action() == Action.SET
                && effectiveSuccess) {
            if (!pending.model().equals(reportedState.model())
                    || !pending.reasoning().equals(reportedState.reasoning())) {
                effectiveSuccess = false;
                effectiveError =
                        "server-brain acknowledged a different active model/reasoning pair";
            }
        }
        if (!effectiveSuccess && effectiveError == null) {
            effectiveError = success
                    ? "server-brain did not apply the request"
                    : "server-brain rejected the request";
        }
        Report report = new Report(
                cleanRequestId,
                pending == null ? null : pending.action(),
                effectiveSuccess,
                applied,
                effectiveError == null ? "" : effectiveError,
                staleRevision || conflictingRevision
                        ? currentState
                        : reportedState,
                pending == null ? null : pending.requester());
        publishReport(report);
        return report;
    }

    static void clearForTests() {
        synchronized (LOCK) {
            PENDING.clear();
        }
        snapshot = null;
        lastReport = null;
        LISTENERS.clear();
        clock = System::currentTimeMillis;
    }

    static void setClockForTests(LongSupplier testClock) {
        clock = Objects.requireNonNull(testClock, "testClock");
    }

    private static String requiredBounded(
            String value,
            String label,
            int maxLength) {
        String clean = optionalBounded(value, label, maxLength);
        if (clean == null) {
            throw new IllegalArgumentException(label + " is required");
        }
        return clean;
    }

    private static String optionalBounded(
            String value,
            String label,
            int maxLength) {
        if (value == null) return null;
        String clean = value.trim();
        if (clean.isEmpty()) return null;
        if (clean.length() > maxLength) {
            throw new IllegalArgumentException(
                    label + " exceeds " + maxLength + " characters");
        }
        return clean;
    }

    private static long positiveRevision(String revision) {
        String clean = requiredBounded(
                revision, "current revision", MAX_REVISION_LENGTH);
        try {
            long parsed = Long.parseLong(clean);
            if (parsed <= 0L) {
                throw new IllegalArgumentException(
                        "current revision must be a positive integer");
            }
            return parsed;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(
                    "current revision must be a positive integer", ex);
        }
    }

    private static void publishReport(Report report) {
        lastReport = report;
        for (Consumer<Report> listener : LISTENERS) {
            try {
                listener.accept(report);
            } catch (RuntimeException ex) {
                Constants.LOG.warn(
                        "[numen-api] brain configuration report listener failed",
                        ex);
            }
        }
    }

    private static long nowMillis() {
        return clock.getAsLong();
    }
}
