package com.dwinovo.numen.mcp.server;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.ToolRegistry;
import com.dwinovo.numen.api.ServerBrainConfiguration;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A minimal MCP (Model Context Protocol) server, hand-rolled as JSON-RPC 2.0
 * over the JDK's built-in HTTP server — no third-party MCP SDK, no Reactor. It
 * implements exactly the surface an external agent needs to drive companions:
 * {@code initialize}, {@code tools/list}, {@code tools/call}, {@code ping}, and
 * the {@code notifications/initialized} handshake.
 *
 * <h2>Transport</h2>
 * Binds a loopback HTTP endpoint at {@code /mcp}. Claude Desktop can't dial an
 * HTTP MCP server directly, so the user points it at this endpoint through the
 * {@code mcp-remote} stdio bridge; every JSON-RPC frame arrives here as an HTTP
 * POST and its response goes straight back in the HTTP body.
 *
 * <h2>Tool surface</h2>
 * Every engine tool (from {@link ToolRegistry}, minus the config's hidden set)
 * is advertised with an extra {@code companion} argument, and calls route
 * through a side-neutral {@link CompanionControl}. The same protocol can
 * therefore drive an owner-client actuator or a headless dedicated-server
 * actuator without loading client classes on the physical server.
 */
public final class McpServer {

    private static final String PROTOCOL_VERSION = "2025-06-18";
    private static final String SERVER_VERSION = "0.1.0";
    /** Roster / acquire / release are fast; only tool actions use the config timeout. */
    private static final int CONTROL_TIMEOUT_SECONDS = 10;
    private static final int MAX_COMMAND_RECEIPTS = 256;

    /**
     * Sent to the connecting agent in the {@code initialize} handshake (MCP's
     * {@code instructions} field) — what Numen is and how to drive it, so any
     * client gets the essentials without a separately-installed skill.
     */
    private static final String INSTRUCTIONS = """
            Numen companions are AI-controlled, player-like characters inside a live Minecraft game. \
            Through this server you take control of a companion's body and play the game as it — perceive, \
            move, mine, build, craft, fight. You are the brain; the companion is your hands and eyes. Its \
            built-in AI stays idle unless its owner speaks to it, so you drive the body directly — there is \
            no 'take control' handshake.

            Loop: (1) list_companions to see who is live — create_companion by name to summon a new one, \
            delete_companion to dismiss one for good; (2) perceive with get_self_status / scan_blocks / \
            scan_nearby_entities; (3) act with move_to / auto_mine / place_block / craft / equip_item / \
            hunt / etc. Action tools return a task_id at once — poll task_status until the body is idle, \
            then perceive to confirm. Every action tool takes a 'companion' argument (name or id), so each \
            call targets one companion; just drive it, there is no take-control step.

            On a dedicated server, poll_server_events drains recent human player chat, trusted server-console \
            chat, external task lifecycle, and trusted administrator test instructions in FIFO order. Decide \
            whether each chat should be \
            ignored, answered, or turned into game actions. Use send_chat to \
            answer as a named companion; keep replies natural and concise, and never answer every message \
            merely because it was observed.

            Rules: survival mode — the tools do only what a real player can (mine to get stone; there is no \
            give or setblock). You are blind between calls, so perceive before and after acting. Action \
            tools return only when the task finishes or times out. You can acquire several companions and \
            drive them in parallel. Modded blocks, items, and GUIs (Create, AE2, Mekanism) work natively.""";

    private final McpConfig config;
    private final CompanionControl control;
    private final Gson gson = new Gson();
    private final CommandDeduplicator commandDeduplicator =
            new CommandDeduplicator(MAX_COMMAND_RECEIPTS);
    private HttpServer http;

    public McpServer(McpConfig config, CompanionControl control) {
        this.config = config;
        this.control = control;
    }

    public void start() throws IOException {
        http = HttpServer.create(new InetSocketAddress(config.host(), config.port()), 0);
        http.createContext("/mcp", this::handle);
        http.setExecutor(Executors.newFixedThreadPool(8, r -> {
            Thread t = new Thread(r, "numen-mcp-http");
            t.setDaemon(true);
            return t;
        }));
        http.start();
    }

    public void stop() {
        if (http != null) http.stop(0);
    }

    // ---- HTTP layer ----

    private void handle(HttpExchange ex) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                respond(ex, 405, "");
                return;
            }
            if (!authorized(ex)) {
                respond(ex, 401, "");
                return;
            }
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonElement parsed;
            try {
                parsed = JsonParser.parseString(body);
            } catch (RuntimeException parseErr) {
                respondJson(ex, errorResponse(null, -32700, "parse error: " + parseErr.getMessage()));
                return;
            }

            if (parsed.isJsonArray()) {   // JSON-RPC batch
                JsonArray out = new JsonArray();
                for (JsonElement el : parsed.getAsJsonArray()) {
                    JsonObject r = dispatch(el.getAsJsonObject());
                    if (r != null) out.add(r);
                }
                if (out.isEmpty()) respond(ex, 202, "");
                else respondJson(ex, out);
            } else {
                JsonObject r = dispatch(parsed.getAsJsonObject());
                if (r == null) respond(ex, 202, "");   // notification: no response
                else respondJson(ex, r);
            }
        } catch (RuntimeException fatal) {
            Constants.LOG.warn("[numen-mcp] request failed: {}", fatal.toString());
            try { respond(ex, 500, ""); } catch (IOException ignored) {}
        } finally {
            ex.close();
        }
    }

    private boolean authorized(HttpExchange ex) {
        if (config.token().isBlank()) return true;
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth != null && auth.equals("Bearer " + config.token())) return true;
        String query = ex.getRequestURI().getQuery();
        return query != null && query.contains("token=" + config.token());
    }

    private void respondJson(HttpExchange ex, JsonElement json) throws IOException {
        byte[] bytes = gson.toJson(json).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    // ---- JSON-RPC dispatch ----

    /** @return the response object, or null for a notification (no id → no reply). */
    private JsonObject dispatch(JsonObject req) {
        JsonElement id = req.get("id");
        String method = req.has("method") ? req.get("method").getAsString() : "";

        // Notifications carry no id and expect no response.
        if (id == null || id.isJsonNull()) {
            return null;
        }

        try {
            return switch (method) {
                case "initialize" -> okResponse(id, initializeResult(req));
                case "ping" -> okResponse(id, new JsonObject());
                case "tools/list" -> okResponse(id, toolsListResult());
                case "tools/call" -> okResponse(id, toolsCallResult(req));
                default -> errorResponse(id, -32601, "method not found: " + method);
            };
        } catch (RuntimeException ex) {
            return errorResponse(id, -32603, "internal error: " + ex.getMessage());
        }
    }

    private JsonObject initializeResult(JsonObject req) {
        String clientProto = PROTOCOL_VERSION;
        if (req.has("params") && req.get("params").isJsonObject()) {
            JsonObject p = req.getAsJsonObject("params");
            if (p.has("protocolVersion")) clientProto = p.get("protocolVersion").getAsString();
        }
        JsonObject result = new JsonObject();
        result.addProperty("protocolVersion", clientProto);
        JsonObject caps = new JsonObject();
        caps.add("tools", new JsonObject());
        result.add("capabilities", caps);
        JsonObject info = new JsonObject();
        info.addProperty("name", "numen-mcp");
        info.addProperty("version", SERVER_VERSION);
        result.add("serverInfo", info);
        result.addProperty("instructions", INSTRUCTIONS);
        return result;
    }

    // ---- tools/list ----

    private JsonObject toolsListResult() {
        JsonArray tools = new JsonArray();

        tools.add(toolDef("list_companions",
                "List the owner's live Minecraft companions (name + id). Call this first to see who you can drive.",
                objectSchema(null, false)));
        if (control.supportsLifecycleChanges()) {
            tools.add(toolDef("create_companion",
                    "Summon a new companion into the world by name (3–16 letters, digits, or underscore). It arrives "
                            + "in a moment; call list_companions to confirm, then drive it directly — no 'take control' step.",
                    requiredStringSchema("name",
                            "The new companion's name (3–16 letters, digits, or underscore).")));
            tools.add(toolDef("delete_companion",
                    "Permanently dismiss a companion — it drops its inventory and is gone for good. Takes its name or id.",
                    requiredStringSchema("companion",
                            "Which companion to dismiss — its name or id (see list_companions).")));
        }
        if (control.supportsServerChat()) {
            tools.add(toolDef("poll_server_events",
                    "Drain up to 64 recent dedicated-server events in FIFO order. Emits player_chat "
                            + "and external background task_finished lifecycle events, plus trusted "
                            + "administrator test_instruction and brain_config_request events published "
                            + "by server mods.",
                    pollEventsSchema()));
            tools.add(toolDef("report_brain_config_state",
                    "Internal server-brain control transport. Report the authoritative active model, "
                            + "reasoning effort, revision, and supported catalog after processing a "
                            + "brain_config_request. This tool never changes configuration itself.",
                    brainConfigReportSchema()));
            tools.add(toolDef("send_chat",
                    "Send a short chat line from a companion. Vanilla clients see it in ordinary <name> text form.",
                    sendChatSchema()));
        }
        if (control.supportsServerCommands()) {
            tools.add(toolDef("run_command",
                    "Run one prevalidated private-server command as an operator companion. "
                            + "The server enforces a semantic allowlist.",
                    runCommandSchema()));
        }

        for (NumenTool tool : ToolRegistry.all()) {
            if (config.isHidden(tool.name())) continue;
            tools.add(toolDef(tool.name(), tool.description(), withCompanion(tool.parameterSchema())));
        }

        JsonObject result = new JsonObject();
        result.add("tools", tools);
        return result;
    }

    private JsonObject toolDef(String name, String description, JsonObject inputSchema) {
        JsonObject t = new JsonObject();
        t.addProperty("name", name);
        t.addProperty("description", description);
        t.add("inputSchema", inputSchema);
        return t;
    }

    /** An object schema with just an optional required {@code companion} string. */
    private JsonObject objectSchema(String companionKey, boolean required) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();
        if (companionKey != null) props.add(companionKey, companionProp());
        schema.add("properties", props);
        if (companionKey != null && required) {
            JsonArray req = new JsonArray();
            req.add(companionKey);
            schema.add("required", req);
        }
        return schema;
    }

    /** An object schema with a single required string property (key + its description). */
    private JsonObject requiredStringSchema(String key, String description) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();
        JsonObject prop = new JsonObject();
        prop.addProperty("type", "string");
        prop.addProperty("description", description);
        props.add(key, prop);
        schema.add("properties", props);
        JsonArray req = new JsonArray();
        req.add(key);
        schema.add("required", req);
        return schema;
    }

    private JsonObject pollEventsSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();
        JsonObject limit = new JsonObject();
        limit.addProperty("type", "integer");
        limit.addProperty("minimum", 1);
        limit.addProperty("maximum", 64);
        limit.addProperty("default", 16);
        limit.addProperty("description", "Maximum number of queued events to drain.");
        props.add("limit", limit);
        schema.add("properties", props);
        return schema;
    }

    private JsonObject sendChatSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();
        props.add("companion", companionProp());
        JsonObject message = new JsonObject();
        message.addProperty("type", "string");
        message.addProperty("minLength", 1);
        message.addProperty("maxLength", 256);
        message.addProperty("description", "The natural, concise chat line to send.");
        props.add("message", message);
        schema.add("properties", props);
        JsonArray required = new JsonArray();
        required.add("companion");
        required.add("message");
        schema.add("required", required);
        return schema;
    }

    private JsonObject brainConfigReportSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();

        JsonObject requestId = new JsonObject();
        requestId.addProperty("type", "string");
        requestId.addProperty("maxLength", 128);
        requestId.addProperty(
                "description",
                "Opaque requestId from brain_config_request; omit only for a startup state report.");
        props.add("request_id", requestId);

        JsonObject success = new JsonObject();
        success.addProperty("type", "boolean");
        props.add("success", success);

        JsonObject applied = new JsonObject();
        applied.addProperty("type", "boolean");
        applied.addProperty(
                "description",
                "True only after the complete reported model/reasoning pair is active.");
        props.add("applied", applied);

        JsonObject error = new JsonObject();
        error.addProperty("type", "string");
        error.addProperty("maxLength", ServerBrainConfiguration.MAX_ERROR_LENGTH);
        props.add("error", error);

        JsonObject requester = new JsonObject();
        requester.addProperty("type", "object");
        requester.addProperty(
                "description",
                "Diagnostic echo of the request requester; request_id remains authoritative.");
        props.add("requester", requester);

        JsonObject current = new JsonObject();
        current.addProperty("type", "object");
        JsonObject currentProps = new JsonObject();
        currentProps.add("model", boundedStringSchema(
                ServerBrainConfiguration.MAX_MODEL_LENGTH));
        currentProps.add("reasoning", boundedStringSchema(
                ServerBrainConfiguration.MAX_REASONING_LENGTH));
        currentProps.add("revision", boundedStringSchema(
                ServerBrainConfiguration.MAX_REVISION_LENGTH));
        current.add("properties", currentProps);
        current.add("required", stringArray("model", "reasoning", "revision"));
        props.add("current", current);

        JsonObject catalogEntry = new JsonObject();
        catalogEntry.addProperty("type", "object");
        JsonObject catalogEntryProps = new JsonObject();
        catalogEntryProps.add("model", boundedStringSchema(
                ServerBrainConfiguration.MAX_MODEL_LENGTH));
        JsonObject reasoning = new JsonObject();
        reasoning.addProperty("type", "array");
        reasoning.addProperty("minItems", 1);
        reasoning.addProperty(
                "maxItems",
                ServerBrainConfiguration.MAX_REASONING_PER_MODEL);
        reasoning.add("items", boundedStringSchema(
                ServerBrainConfiguration.MAX_REASONING_LENGTH));
        catalogEntryProps.add("reasoning", reasoning);
        catalogEntry.add("properties", catalogEntryProps);
        catalogEntry.add("required", stringArray("model", "reasoning"));

        JsonObject catalog = new JsonObject();
        catalog.addProperty("type", "array");
        catalog.addProperty(
                "maxItems",
                ServerBrainConfiguration.MAX_CATALOG_ENTRIES);
        catalog.add("items", catalogEntry);
        props.add("catalog", catalog);

        schema.add("properties", props);
        schema.add(
                "required",
                stringArray("success", "applied", "current", "catalog"));
        return schema;
    }

    private JsonObject boundedStringSchema(int maxLength) {
        JsonObject value = new JsonObject();
        value.addProperty("type", "string");
        value.addProperty("minLength", 1);
        value.addProperty("maxLength", maxLength);
        return value;
    }

    private JsonArray stringArray(String... values) {
        JsonArray array = new JsonArray();
        for (String value : values) array.add(value);
        return array;
    }

    private JsonObject runCommandSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();
        props.add("companion", companionProp());
        JsonObject command = new JsonObject();
        command.addProperty("type", "string");
        command.addProperty("minLength", 1);
        command.addProperty("maxLength", 128);
        command.addProperty(
                "description",
                "A command allowed by the dedicated-server semantic whitelist.");
        props.add("command", command);
        JsonObject requestId = boundedStringSchema(160);
        requestId.addProperty(
                "description",
                "Stable idempotency key for this server event. Reusing it never runs the command twice.");
        props.add("request_id", requestId);
        schema.add("properties", props);
        JsonArray required = new JsonArray();
        required.add("companion");
        required.add("command");
        required.add("request_id");
        schema.add("required", required);
        return schema;
    }

    /** An engine tool's own schema, with a required {@code companion} argument injected. */
    private JsonObject withCompanion(java.util.Map<String, Object> parameterSchema) {
        JsonObject schema = parameterSchema == null
                ? new JsonObject()
                : gson.toJsonTree(parameterSchema).getAsJsonObject();
        schema.addProperty("type", "object");
        JsonObject props = schema.has("properties") && schema.get("properties").isJsonObject()
                ? schema.getAsJsonObject("properties") : new JsonObject();
        props.add("companion", companionProp());
        schema.add("properties", props);
        JsonArray required = schema.has("required") && schema.get("required").isJsonArray()
                ? schema.getAsJsonArray("required") : new JsonArray();
        boolean has = false;
        for (JsonElement e : required) if ("companion".equals(e.getAsString())) has = true;
        if (!has) required.add("companion");
        schema.add("required", required);
        return schema;
    }

    private JsonObject companionProp() {
        JsonObject p = new JsonObject();
        p.addProperty("type", "string");
        p.addProperty("description", "Which companion to act with — its name or id (see list_companions).");
        return p;
    }

    // ---- tools/call ----

    private JsonObject toolsCallResult(JsonObject req) {
        JsonObject params = req.has("params") && req.get("params").isJsonObject()
                ? req.getAsJsonObject("params") : new JsonObject();
        String name = params.has("name") ? params.get("name").getAsString() : "";
        JsonObject args = params.has("arguments") && params.get("arguments").isJsonObject()
                ? params.getAsJsonObject("arguments") : new JsonObject();

        try {
            return switch (name) {
                case "list_companions" -> content(listCompanions(), false);
                case "create_companion" -> handleCreate(args);
                case "delete_companion" -> handleDelete(args);
                case "poll_server_events" -> handlePollServerEvents(args);
                case "report_brain_config_state" ->
                        handleReportBrainConfigState(args);
                case "send_chat" -> handleSendChat(args);
                case "run_command" -> handleRunCommand(args);
                default -> handleToolInvoke(name, args);
            };
        } catch (TimeoutException te) {
            return content("timed out waiting for the action to finish", true);
        } catch (Exception ex) {
            return content("call failed: " + ex.getMessage(), true);
        }
    }

    private String listCompanions() throws Exception {
        List<CompanionControl.Companion> list = control.companions()
                .get(CONTROL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (list.isEmpty()) {
            return "No companions are registered in this world. Summon one in-game first.";
        }
        StringBuilder sb = new StringBuilder("Registered companions:\n");
        for (CompanionControl.Companion c : list) {
            sb.append("- ").append(c.name())
                    .append("  (id: ").append(c.uuid())
                    .append(", ").append(c.live() ? "live" : "dormant")
                    .append(")\n");
        }
        return sb.toString().stripTrailing();
    }

    private JsonObject handleCreate(JsonObject args) throws Exception {
        String name = args.has("name") && !args.get("name").isJsonNull()
                ? args.get("name").getAsString().trim() : "";
        if (name.isEmpty()) {
            return content("create_companion needs a 'name' (3–16 letters, digits, or underscore)", true);
        }
        boolean ok = control.create(name).get(CONTROL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!ok) {
            return content("could not summon — is the game in a world?", true);
        }
        return content("summoning '" + name + "' — it arrives in a moment; call list_companions to confirm, "
                + "then drive it directly (no acquire needed)", false);
    }

    private JsonObject handleDelete(JsonObject args) throws Exception {
        UUID target = resolveCompanion(args);
        if (target == null) {
            return content("no such companion — call list_companions to see valid names/ids", true);
        }
        boolean ok = control.delete(target).get(CONTROL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return content(ok ? "dismissed " + target + " — it dropped its inventory and is gone for good"
                : "could not dismiss " + target, !ok);
    }

    private JsonObject handlePollServerEvents(JsonObject args) {
        if (!control.supportsServerChat()) {
            return content("server events are unavailable in client-hosted MCP mode", true);
        }
        int limit = args.has("limit") && !args.get("limit").isJsonNull()
                ? args.get("limit").getAsInt() : 16;
        return content(gson.toJson(control.pollServerEvents(limit)), false);
    }

    private JsonObject handleReportBrainConfigState(JsonObject args) {
        if (!control.supportsServerChat()) {
            return content(
                    "brain configuration reports are unavailable in client-hosted MCP mode",
                    true);
        }
        if (!args.has("success")
                || args.get("success").isJsonNull()
                || !args.get("success").isJsonPrimitive()
                || !args.getAsJsonPrimitive("success").isBoolean()) {
            throw new IllegalArgumentException(
                    "report_brain_config_state needs boolean 'success'");
        }
        boolean success = args.get("success").getAsBoolean();
        if (!args.has("applied")
                || args.get("applied").isJsonNull()
                || !args.get("applied").isJsonPrimitive()
                || !args.getAsJsonPrimitive("applied").isBoolean()) {
            throw new IllegalArgumentException(
                    "report_brain_config_state needs boolean 'applied'");
        }
        boolean applied = args.get("applied").getAsBoolean();
        String requestId = optionalString(
                args, "request_id", 128);
        String error = optionalString(
                args, "error", ServerBrainConfiguration.MAX_ERROR_LENGTH);

        JsonObject current = requiredObject(args, "current");
        String model = requiredString(
                current,
                "model",
                ServerBrainConfiguration.MAX_MODEL_LENGTH);
        String reasoning = requiredString(
                current,
                "reasoning",
                ServerBrainConfiguration.MAX_REASONING_LENGTH);
        String revision = requiredString(
                current,
                "revision",
                ServerBrainConfiguration.MAX_REVISION_LENGTH);

        if (!args.has("catalog") || !args.get("catalog").isJsonArray()) {
            throw new IllegalArgumentException(
                    "report_brain_config_state needs array 'catalog'");
        }
        JsonArray catalogJson = args.getAsJsonArray("catalog");
        if (catalogJson.size() > ServerBrainConfiguration.MAX_CATALOG_ENTRIES) {
            throw new IllegalArgumentException(
                    "catalog exceeds "
                            + ServerBrainConfiguration.MAX_CATALOG_ENTRIES
                            + " entries");
        }
        List<ServerBrainConfiguration.CatalogEntry> catalog =
                new ArrayList<>(catalogJson.size());
        for (JsonElement item : catalogJson) {
            if (!item.isJsonObject()) {
                throw new IllegalArgumentException(
                        "each catalog entry must be an object");
            }
            JsonObject entry = item.getAsJsonObject();
            String entryModel = requiredString(
                    entry,
                    "model",
                    ServerBrainConfiguration.MAX_MODEL_LENGTH);
            if (!entry.has("reasoning")
                    || !entry.get("reasoning").isJsonArray()) {
                throw new IllegalArgumentException(
                        "each catalog entry needs array 'reasoning'");
            }
            JsonArray effortsJson = entry.getAsJsonArray("reasoning");
            if (effortsJson.size()
                    > ServerBrainConfiguration.MAX_REASONING_PER_MODEL) {
                throw new IllegalArgumentException(
                        "catalog reasoning exceeds "
                                + ServerBrainConfiguration
                                        .MAX_REASONING_PER_MODEL
                                + " entries");
            }
            List<String> efforts = new ArrayList<>(effortsJson.size());
            for (JsonElement effort : effortsJson) {
                if (!effort.isJsonPrimitive()
                        || !effort.getAsJsonPrimitive().isString()) {
                    throw new IllegalArgumentException(
                            "catalog reasoning values must be strings");
                }
                efforts.add(effort.getAsString());
            }
            catalog.add(new ServerBrainConfiguration.CatalogEntry(
                    entryModel, efforts));
        }

        ServerBrainConfiguration.Report report =
                ServerBrainConfiguration.acceptReport(
                        requestId,
                        success,
                        applied,
                        error,
                        model,
                        reasoning,
                        revision,
                        catalog);
        JsonObject accepted = new JsonObject();
        accepted.addProperty("accepted", true);
        if (report.requestId() != null) {
            accepted.addProperty("request_id", report.requestId());
            accepted.addProperty(
                    "matched_requester",
                    report.requester() != null);
        }
        return content(gson.toJson(accepted), false);
    }

    private JsonObject requiredObject(JsonObject parent, String key) {
        if (!parent.has(key) || !parent.get(key).isJsonObject()) {
            throw new IllegalArgumentException(
                    "report_brain_config_state needs object '" + key + "'");
        }
        return parent.getAsJsonObject(key);
    }

    private String requiredString(
            JsonObject object,
            String key,
            int maxLength) {
        String value = optionalString(object, key, maxLength);
        if (value == null) {
            throw new IllegalArgumentException(
                    "report_brain_config_state needs non-empty '" + key + "'");
        }
        return value;
    }

    private String optionalString(
            JsonObject object,
            String key,
            int maxLength) {
        if (!object.has(key) || object.get(key).isJsonNull()) return null;
        JsonElement element = object.get(key);
        if (!element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("'" + key + "' must be a string");
        }
        String value = element.getAsString().trim();
        if (value.isEmpty()) return null;
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(
                    "'" + key + "' exceeds " + maxLength + " characters");
        }
        return value;
    }

    private JsonObject handleSendChat(JsonObject args) throws Exception {
        if (!control.supportsServerChat()) {
            return content("server chat is unavailable in client-hosted MCP mode", true);
        }
        UUID target = resolveCompanion(args);
        if (target == null) {
            return content("send_chat needs a valid 'companion' from list_companions", true);
        }
        String message = args.has("message") && !args.get("message").isJsonNull()
                ? args.get("message").getAsString().trim() : "";
        if (message.isEmpty() || message.length() > 256) {
            return content("send_chat needs a non-empty 'message' of at most 256 characters", true);
        }
        boolean ok = control.sendChat(target, message)
                .get(CONTROL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return content(ok ? "sent" : "could not send chat from that companion", !ok);
    }

    private JsonObject handleRunCommand(JsonObject args) throws Exception {
        if (!control.supportsServerCommands()) {
            return content("server commands are unavailable in client-hosted MCP mode", true);
        }
        UUID target = resolveCompanion(args);
        if (target == null) {
            return content("run_command needs a valid 'companion' from list_companions", true);
        }
        String command = args.has("command") && !args.get("command").isJsonNull()
                ? args.get("command").getAsString().trim() : "";
        if (command.isEmpty() || command.length() > 128) {
            return content("run_command needs a command of at most 128 characters", true);
        }
        String requestId = args.has("request_id") && !args.get("request_id").isJsonNull()
                ? args.get("request_id").getAsString().trim() : "";
        if (requestId.isEmpty() || requestId.length() > 160) {
            return content("run_command needs a stable 'request_id' of at most 160 characters", true);
        }
        CommandDeduplicator.Outcome outcome = commandDeduplicator.execute(
                requestId,
                target,
                command,
                () -> control.runRestrictedCommand(target, command)
                        .get(CONTROL_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        return content(
                (outcome.duplicate() ? "already " : "") + "executed " + outcome.result(),
                false);
    }

    /**
     * Keeps operator commands exactly-once within one Minecraft JVM session.
     * Failed or timed-out executions remain UNKNOWN and are never replayed:
     * duplicating a command is more dangerous than asking the operator to
     * inspect an uncertain outcome.
     */
    static final class CommandDeduplicator {
        @FunctionalInterface
        interface Operation {
            String run() throws Exception;
        }

        record Outcome(String result, boolean duplicate) {}

        private enum State {
            IN_PROGRESS,
            SUCCEEDED,
            UNKNOWN
        }

        private record Receipt(UUID target, String command, State state, String result) {}

        private final int maxReceipts;
        private final LinkedHashMap<String, Receipt> receipts = new LinkedHashMap<>();

        CommandDeduplicator(int maxReceipts) {
            if (maxReceipts < 1) {
                throw new IllegalArgumentException("maxReceipts must be positive");
            }
            this.maxReceipts = maxReceipts;
        }

        synchronized Outcome execute(
                String requestId,
                UUID target,
                String command,
                Operation operation) throws Exception {
            Receipt existing = receipts.get(requestId);
            if (existing != null) {
                if (!existing.target().equals(target) || !existing.command().equals(command)) {
                    throw new IllegalArgumentException(
                            "request_id was already used for a different command");
                }
                if (existing.state() == State.SUCCEEDED) {
                    return new Outcome(existing.result(), true);
                }
                throw new IllegalStateException(
                        "previous command outcome is unknown; refusing to replay request_id");
            }

            makeRoom();
            receipts.put(
                    requestId,
                    new Receipt(target, command, State.IN_PROGRESS, ""));
            try {
                String result = operation.run();
                receipts.put(
                        requestId,
                        new Receipt(target, command, State.SUCCEEDED, result));
                return new Outcome(result, false);
            } catch (Exception error) {
                receipts.put(
                        requestId,
                        new Receipt(target, command, State.UNKNOWN, ""));
                throw error;
            }
        }

        private void makeRoom() {
            if (receipts.size() < maxReceipts) {
                return;
            }
            Iterator<Map.Entry<String, Receipt>> iterator =
                    receipts.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, Receipt> entry = iterator.next();
                if (entry.getValue().state() == State.SUCCEEDED) {
                    iterator.remove();
                    return;
                }
            }
            throw new IllegalStateException(
                    "command receipt capacity is full of uncertain outcomes");
        }
    }

    private JsonObject handleToolInvoke(String toolName, JsonObject args) throws Exception {
        UUID target = resolveCompanion(args);
        if (target == null) {
            return content("this tool needs a 'companion' argument (a name or id from list_companions)", true);
        }
        JsonObject toolArgs = args.deepCopy();
        toolArgs.remove("companion");
        String result = control.invoke(target, toolName, toolArgs.toString())
                .get(config.callTimeoutSeconds(), TimeUnit.SECONDS);
        boolean isError = false;
        try {
            JsonObject r = JsonParser.parseString(result).getAsJsonObject();
            isError = r.has("success") && !r.get("success").getAsBoolean();
        } catch (RuntimeException ignored) {
            // non-JSON result — treat as plain text, not an error
        }
        return content(result, isError);
    }

    /** Resolve the {@code companion} argument (name or UUID) to a live companion's UUID, or null. */
    private UUID resolveCompanion(JsonObject args) throws Exception {
        if (!args.has("companion") || args.get("companion").isJsonNull()) return null;
        String raw = args.get("companion").getAsString().trim();
        if (raw.isEmpty()) return null;
        List<CompanionControl.Companion> list = control.companions()
                .get(CONTROL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        // exact id match first
        for (CompanionControl.Companion c : list) {
            if (c.uuid().toString().equalsIgnoreCase(raw)) return c.uuid();
        }
        // then exact name, then case-insensitive name
        for (CompanionControl.Companion c : list) {
            if (c.name().equals(raw)) return c.uuid();
        }
        for (CompanionControl.Companion c : list) {
            if (c.name().equalsIgnoreCase(raw)) return c.uuid();
        }
        return null;
    }

    // ---- result envelopes ----

    private JsonObject content(String text, boolean isError) {
        JsonObject item = new JsonObject();
        item.addProperty("type", "text");
        item.addProperty("text", text);
        JsonArray arr = new JsonArray();
        arr.add(item);
        JsonObject result = new JsonObject();
        result.add("content", arr);
        result.addProperty("isError", isError);
        return result;
    }

    private JsonObject okResponse(JsonElement id, JsonObject result) {
        JsonObject r = new JsonObject();
        r.addProperty("jsonrpc", "2.0");
        r.add("id", id);
        r.add("result", result);
        return r;
    }

    private JsonObject errorResponse(JsonElement id, int code, String message) {
        JsonObject err = new JsonObject();
        err.addProperty("code", code);
        err.addProperty("message", message);
        JsonObject r = new JsonObject();
        r.addProperty("jsonrpc", "2.0");
        r.add("id", id == null ? JsonNull() : id);
        r.add("error", err);
        return r;
    }

    private static JsonElement JsonNull() {
        return com.google.gson.JsonNull.INSTANCE;
    }
}
