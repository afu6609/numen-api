package com.dwinovo.numen.entity;

import com.dwinovo.numen.network.payload.NumenDeathPayload;
import com.dwinovo.numen.network.payload.NumenEventPayload;
import com.dwinovo.numen.network.payload.NumenRespawnPayload;
import com.dwinovo.numen.network.payload.CompanionListPayload;
import com.dwinovo.numen.platform.Services;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Coordinates companion lifecycle on top of {@link CompanionFactory} (body
 * spawn/despawn) and {@link CompanionRegistry} (the persistent index). The body
 * persists as a player {@code .dat}; the registry remembers it exists so it can
 * be recreated when its owner returns, a tool call arrives, or the dedicated
 * server's respawn timer elapses.
 */
@com.dwinovo.numen.api.Internal
public final class Companions {

    /** Ticks a dead companion stays down before respawning at its owner (~30 s). */
    private static final long RESPAWN_DELAY_TICKS = 30 * 20;

    private Companions() {}

    /**
     * Summon the owner's companion called {@code name}. IDEMPOTENT per (owner, name): if one already
     * exists it is reused — already live → returned as-is; dormant → brought back. Only a name with no
     * existing companion mints a fresh one. (The old "fresh random UUID every summon" minted same-name
     * duplicates that all respawned on login — that's the duplicate-companion bug.)
     */
    public static NumenPlayer summon(MinecraftServer server, UUID ownerUuid, String name,
                                      ServerLevel level, Vec3 pos) {
        return summon(server, ownerUuid, name, level, pos, null);
    }

    /** As {@link #summon(MinecraftServer, UUID, String, ServerLevel, Vec3)} with an optional
     *  borrowed skin (Mojang 签名的 textures,见 {@link MojangSkins})。重复召唤携带皮肤 =
     *  换肤:注册表更新后,休眠体这次重建就生效,活体等下次重建。 */
    public static NumenPlayer summon(MinecraftServer server, UUID ownerUuid, String name,
                                      ServerLevel level, Vec3 pos, MojangSkins.Skin skin) {
        CompanionRegistry reg = CompanionRegistry.get(server);
        UUID existing = findByOwnerName(server, ownerUuid, name);
        if (existing != null) {
            if (skin != null) {
                CompanionRegistry.Entry e = reg.find(existing);
                if (e != null) reg.put(existing, e.withSkin(skin.value(), skin.signature()));
                // 换肤必须重建身体才看得见(GameProfile 只在构造时注入):活体先落盘
                // 休眠,紧接着的 respawn 立即以带新皮肤的档案重建,位置物品全保留。
                NumenPlayer live = NumenPlayer.findByUuid(server, existing);
                if (live != null) {
                    CompanionFactory.despawn(server, live);
                }
            }
            NumenPlayer body = respawn(server, existing);
            if (body != null) return body;
            reg.remove(existing);   // stale entry (no .dat) — replace it
        }
        UUID companionUuid = UUID.randomUUID();
        Vec3 safe = SafeSpawn.findNear(level, pos);
        if (safe != null) pos = safe;   // no safe spot around → keep the summoner's own position
        // 先入册再造体:CompanionFactory.spawn 从注册表读皮肤,所有出生路径共用一个注入点。
        CompanionRegistry.Entry fresh = new CompanionRegistry.Entry(
                name, ownerUuid, level.dimension(), net.minecraft.core.BlockPos.containing(pos));
        if (skin != null) fresh = fresh.withSkin(skin.value(), skin.signature());
        reg.put(companionUuid, fresh);
        NumenPlayer body = CompanionFactory.spawn(server, companionUuid, name, ownerUuid, level, pos);
        reg.put(companionUuid, fresh.movedTo(level.dimension(), body.blockPosition()));
        return body;
    }

    /** Companion UUID of the owner's companion named {@code name}, or null if none. */
    private static UUID findByOwnerName(MinecraftServer server, UUID ownerUuid, String name) {
        for (Map.Entry<UUID, CompanionRegistry.Entry> e : CompanionRegistry.get(server).ownedBy(ownerUuid)) {
            if (e.getValue().name().equals(name)) return e.getKey();
        }
        return null;
    }

    /**
     * Bring a dormant companion back from its catalog entry + {@code .dat}
     * (position/inventory restored from disk). Returns the already-live body if
     * it is spawned, or {@code null} if it is unknown to the registry.
     */
    public static NumenPlayer respawn(MinecraftServer server, UUID companionUuid) {
        NumenPlayer live = NumenPlayer.findByUuid(server, companionUuid);
        if (live != null) return live;
        CompanionRegistry.Entry entry = CompanionRegistry.get(server).find(companionUuid);
        if (entry == null) return null;
        if (entry.diedAt() > 0L) {
            long now = server.overworld().getGameTime();
            if (!respawnDelayElapsed(now, entry.diedAt())) {
                return null;
            }
            ServerPlayer owner = server.getPlayerList().getPlayer(entry.owner());
            if (owner != null) {
                return respawnDead(server, companionUuid, entry, owner)
                        ? NumenPlayer.findByUuid(server, companionUuid)
                        : null;
            }
            return respawnDeadHeadless(server, companionUuid, entry);
        }
        // A body can exceptionally remain in a ServerLevel after falling out of
        // PlayerList (for example across a fake-connection/login lifecycle edge).
        // It still renders, but the task scheduler only ticks list-resident
        // companions. Spawning straight over it creates the worst possible
        // split-brain: tools drive one body while the player sees another.
        // Save/remove the orphan first, then restore exactly one canonical body.
        NumenPlayer orphan = NumenPlayer.findWorldBodyByUuid(server, companionUuid);
        if (orphan != null) {
            com.dwinovo.numen.Constants.LOG.warn(
                    "[numen-companion] reconciling world-only body {} ({}) before respawn",
                    orphan.getName().getString(), companionUuid);
            CompanionFactory.despawn(server, orphan);
        }
        ServerLevel level = server.getLevel(entry.dimension());
        if (level == null) level = server.overworld();
        // pos=null: keep the position restored from the .dat.
        return CompanionFactory.spawn(server, companionUuid, entry.name(), entry.owner(), level, null);
    }

    /**
     * Recover a registered dead companion at one exact, pre-authorized safe
     * cell while preserving its identity and persisted player data.
     *
     * <p>This is a narrow server-administration seam for bounded test
     * harnesses. It never creates or replaces a registry entry: the UUID,
     * name, owner and skin all come from the existing entry, while
     * {@link CompanionFactory#spawn} restores the same player {@code .dat}
     * (including inventory) before the explicit position is applied. It also
     * refuses a live/non-dead companion, so callers cannot repurpose it as a
     * general teleport or summon primitive.
     *
     * <p>The caller is responsible for authorizing and bounding
     * {@code targetFeet}. This method independently requires the exact cell to
     * be loaded and safe; unlike normal respawn it never searches outside that
     * target.
     *
     * @throws IllegalArgumentException when the UUID is unknown or the target
     *                                  is not an exact safe standing cell
     * @throws IllegalStateException when the companion is not marked dead
     */
    public static NumenPlayer recoverDeadAt(
            MinecraftServer server,
            UUID companionUuid,
            ServerLevel level,
            BlockPos targetFeet) {
        if (server == null) throw new IllegalArgumentException("server is required");
        if (companionUuid == null) {
            throw new IllegalArgumentException("companion UUID is required");
        }
        if (level == null) throw new IllegalArgumentException("target level is required");
        if (targetFeet == null) {
            throw new IllegalArgumentException("target feet position is required");
        }

        CompanionRegistry registry = CompanionRegistry.get(server);
        CompanionRegistry.Entry entry = registry.find(companionUuid);
        if (entry == null) {
            throw new IllegalArgumentException(
                    "companion " + companionUuid + " is not registered");
        }
        if (entry.diedAt() <= 0L) {
            throw new IllegalStateException(
                    "companion '" + entry.name()
                            + "' is not marked dead; refusing forced recovery");
        }
        if (!level.hasChunkAt(targetFeet)) {
            throw new IllegalArgumentException(
                    "recovery target chunk is not loaded: " + targetFeet);
        }
        if (!SafeSpawn.isSafe(level, targetFeet)) {
            throw new IllegalArgumentException(
                    "recovery target is not a safe standing cell: " + targetFeet);
        }

        removeDeadBodyIfPresent(server, companionUuid);
        NumenPlayer body = CompanionFactory.spawn(
                server,
                companionUuid,
                entry.name(),
                entry.owner(),
                level,
                Vec3.atBottomCenterOf(targetFeet));
        body.setHealth(body.getMaxHealth());
        body.clearFire();
        registry.put(
                companionUuid,
                entry.movedTo(level.dimension(), body.blockPosition()));
        registry.markAlive(companionUuid);

        ServerPlayer owner = server.getPlayerList().getPlayer(entry.owner());
        if (owner != null) {
            syncRosterToOwner(server, owner);
            Services.NETWORK.sendToPlayer(
                    owner,
                    new NumenRespawnPayload(companionUuid, entry.deathCause()));
        }
        com.dwinovo.numen.Constants.LOG.info(
                "[numen-companion] explicit dead recovery {} ({}) at {}",
                entry.name(), companionUuid, body.blockPosition());
        return body;
    }

    /** When an owner logs in, bring back every companion of theirs. Live bodies
     *  are restored immediately; dead bodies still pass through
     *  {@link #respawn}, so logging in cannot bypass the death cooldown. */
    public static void respawnAllOwnedBy(MinecraftServer server, UUID ownerUuid) {
        for (Map.Entry<UUID, CompanionRegistry.Entry> e : CompanionRegistry.get(server).ownedBy(ownerUuid)) {
            respawn(server, e.getKey());
        }
    }

    /**
     * A companion just DIED (detected in {@link NumenPlayer#tick}). The death itself is left fully
     * vanilla — drops / a grave mod / keepInventory all run because it's a real ServerPlayer death.
     * We only: stop the brain (the owner's loop suspends on {@link NumenDeathPayload}, resolving the
     * in-flight tool call with the death cause), heal the body so its saved {@code .dat} is whole, and
     * queue a timed respawn near the owner, or near its recorded death cell when
     * running headlessly. The corpse is removed AFTER this tick (a fake player
     * isn't auto-removed on death — it would sit at 0 HP forever waiting for a
     * respawn packet that never comes).
     */
    public static void onDeath(NumenPlayer body) {
        MinecraftServer server = body.level().getServer();
        if (server == null) return;
        UUID uuid = body.getUUID();
        String cause = body.getCombatTracker().getDeathMessage().getString();
        if (cause == null || cause.isBlank()) cause = "未知原因";
        CompanionLifecycle.fireDeath(body);   // no result shipped — the death payload drives the client
        ServerPlayer owner = body.resolveOwnerPlayer();
        if (owner != null) {   // immediate, same-session (carries the respawn delay for the client countdown)
            Services.NETWORK.sendToPlayer(owner, new NumenDeathPayload(uuid, cause, RESPAWN_DELAY_TICKS * 50L));
        }
        // Persist the death (cause + game-time) in the world-saved registry so it survives a logout during
        // the respawn window — without this, a relog lost the pending state and the body silently respawned
        // "alive" with an empty inventory and no idea it had died.
        CompanionRegistry registry = CompanionRegistry.get(server);
        CompanionRegistry.Entry previous = registry.find(uuid);
        if (previous != null) {
            registry.put(uuid, previous.movedTo(
                    ((ServerLevel) body.level()).dimension(), body.blockPosition()));
        }
        registry.markDead(uuid, cause, server.overworld().getGameTime());
        body.setHealth(body.getMaxHealth());             // saved .dat is a healthy body for the respawn
        server.execute(() -> CompanionFactory.despawn(server, body));   // remove the corpse safely after the tick
    }

    /**
     * Bring back any companion whose post-death timer has elapsed. With an
     * online owner it returns safely beside them; otherwise the dedicated-server
     * brain recovers it near the recorded death cell. Called each tick.
     */
    public static void tickRespawns(MinecraftServer server) {
        long now = server.overworld().getGameTime();
        for (Map.Entry<UUID, CompanionRegistry.Entry> e : CompanionRegistry.get(server).pendingDead()) {
            CompanionRegistry.Entry entry = e.getValue();
            if (now - entry.diedAt() < RESPAWN_DELAY_TICKS) continue;
            ServerPlayer owner = server.getPlayerList().getPlayer(entry.owner());
            if (owner != null) {
                // No safe spot right now → quietly retry next tick until the owner reaches open
                // space (the maid/vanilla-pet convention: never nag about a transient squeeze).
                respawnDead(server, e.getKey(), entry, owner);
            } else {
                // Dedicated-server brains keep companions alive without a human
                // client. After the same cooldown, recover near the recorded death
                // cell instead of letting status/tool calls resurrect immediately.
                respawnDeadHeadless(server, e.getKey(), entry);
            }
        }
    }

    static boolean respawnDelayElapsed(long now, long diedAt) {
        return diedAt <= 0L || now - diedAt >= RESPAWN_DELAY_TICKS;
    }

    /** Respawn a dead companion at its owner, clear the death state, and tell the brain it died + why
     *  (the cause rides the respawn payload, so it works even after a logout cleared the client's memory).
     *  Returns false when no safe landing spot exists near the owner right now (tight tunnel, crawling,
     *  deep water) — the death state stays pending and the ticker retries until the owner reaches open
     *  space. Spawning anyway wedged the body into blocks: suffocate → die → respawn into the same spot,
     *  a death loop until the owner happened to move. */
    private static boolean respawnDead(MinecraftServer server, UUID uuid, CompanionRegistry.Entry entry,
                                       ServerPlayer owner) {
        ServerLevel level = (ServerLevel) owner.level();
        Vec3 pos = SafeSpawn.findNear(level, owner.position());
        if (pos == null && owner.onGround() && SafeSpawn.hasStandingRoom(level, owner.position())) {
            pos = owner.position();   // non-full-block floor (slab/carpet): the owner's own spot fits
        }
        if (pos == null) return false;
        removeDeadBodyIfPresent(server, uuid);
        NumenPlayer body = CompanionFactory.spawn(server, uuid, entry.name(), entry.owner(), level, pos);
        body.setHealth(body.getMaxHealth());
        body.clearFire();
        CompanionRegistry.get(server).markAlive(uuid);
        syncRosterToOwner(server, owner);
        Services.NETWORK.sendToPlayer(owner, new NumenRespawnPayload(uuid, entry.deathCause()));
        return true;
    }

    /**
     * Dedicated-server recovery when no human owner is online. The death cell is
     * refreshed in the registry by {@link #onDeath}; preflight a safe standing
     * position around it before creating the body, so a tight block never causes
     * a spawn/suffocate/despawn loop.
     */
    private static NumenPlayer respawnDeadHeadless(
            MinecraftServer server, UUID uuid, CompanionRegistry.Entry entry) {
        ServerLevel level = server.getLevel(entry.dimension());
        if (level == null) level = server.overworld();
        Vec3 origin = Vec3.atBottomCenterOf(entry.pos());
        Vec3 pos = SafeSpawn.findNear(level, origin);
        if (pos == null) return null;
        removeDeadBodyIfPresent(server, uuid);
        NumenPlayer body = CompanionFactory.spawn(
                server, uuid, entry.name(), entry.owner(), level, pos);
        body.setHealth(body.getMaxHealth());
        body.clearFire();
        CompanionRegistry.get(server).markAlive(uuid);
        com.dwinovo.numen.Constants.LOG.info(
                "[numen-companion] headless respawn {} ({}) at {}",
                entry.name(), uuid, body.blockPosition());
        return body;
    }

    /** Defensive cleanup if the healed corpse missed its scheduled end-of-tick removal. */
    private static void removeDeadBodyIfPresent(MinecraftServer server, UUID uuid) {
        NumenPlayer corpse = NumenPlayer.findWorldBodyByUuid(server, uuid);
        if (corpse != null) {
            CompanionFactory.despawn(server, corpse);
        }
    }

    /**
     * Push a snapshot of the owner's companions <em>currently live in the world</em>
     * (UUID + name) to their client, so the G panel reflects what actually exists
     * rather than a persisted list. The server is the only place that can answer
     * "which in-world players are NumenPlayers owned by you" — owner is a
     * server-side field — so it does the detection and ships the result. The
     * client treats each push as a complete replacement. Call after any change to
     * the live set (login-respawn, summon, despawn, death).
     */
    public static void syncRosterToOwner(MinecraftServer server, ServerPlayer owner) {
        List<CompanionListPayload.Entry> list = new ArrayList<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p instanceof NumenPlayer a && a.isOwnedByPlayer(owner.getUUID())) {
                list.add(new CompanionListPayload.Entry(a.getUUID(), a.getName().getString()));
            }
        }
        Services.NETWORK.sendToPlayer(owner, new CompanionListPayload(list));
    }

    /**
     * Push an async world {@code <event>} to the companion's brain (it runs on the owner's client).
     * Consumption timing is the client inbox's business — it routes by the brain's state at arrival
     * (mid-turn → next boundary; background task running → immediate turn; idle → wait and ride).
     * No-op if the owner is offline (no client to receive it).
     *
     * <p>{@code principal} means "a live HUMAN is speaking through this event" — bridge mods relaying
     * danmaku / QQ messages set it true and get owner-grade treatment (opens a turn even from full
     * idle). World/body events (task wind-downs, dimension changes, body narrative) always pass
     * false: they are facts, and facts don't get to decide their own urgency.
     */
    public static void emitEvent(NumenPlayer body, String xml, boolean principal) {
        ServerPlayer owner = body.resolveOwnerPlayer();
        if (owner != null) {
            Services.NETWORK.sendToPlayer(owner, new NumenEventPayload(body.getUUID(), xml, principal));
        }
    }

    /**
     * The companion crossed into a new dimension — on its OWN (it travels where it likes, not tied to
     * the owner). Tell its brain, ambient: it rides along on the next owner-driven turn rather than
     * spending a fresh LLM call just to note the move. Called from each loader's dimension-change hook.
     */
    public static void onDimensionChanged(NumenPlayer body) {
        String dim = body.level().dimension().location().toString();
        com.dwinovo.numen.event.GameEvents.emit(body,
                com.dwinovo.numen.event.GameEvents.Kind.DIMENSION_CHANGE,
                java.util.Map.of("to", dim),
                "你进入了 " + dim + "。留意这个维度的环境和危险。");
    }

    /** Save the companion to its {@code .dat} and remove it from the world (dormancy). */
    public static void dormant(MinecraftServer server, NumenPlayer body) {
        // Refresh the respawn hint before the body leaves.
        CompanionRegistry reg = CompanionRegistry.get(server);
        CompanionRegistry.Entry prev = reg.find(body.getUUID());
        if (prev != null) {
            reg.put(body.getUUID(),
                    prev.movedTo(((ServerLevel) body.level()).dimension(), body.blockPosition()));
        }
        CompanionFactory.despawn(server, body);
    }

    /** Permanently forget a companion (death / dismissal): despawn + drop the index entry. */
    public static void dismiss(MinecraftServer server, NumenPlayer body) {
        UUID uuid = body.getUUID();
        CompanionFactory.despawn(server, body);
        CompanionRegistry.get(server).remove(uuid);
    }

    /**
     * Permanently dismiss EVERY companion of {@code ownerUuid} named {@code name} — gone for good, it
     * will NOT come back on login. Removes both live bodies and registry entries, so it also cleans up
     * any same-name duplicates that the old non-idempotent summon left behind. Returns how many it
     * dismissed. (The {@code .dat} files orphan harmlessly — with no registry entry nothing respawns
     * them.)
     */
    public static int dismissByName(MinecraftServer server, UUID ownerUuid, String name) {
        CompanionRegistry reg = CompanionRegistry.get(server);
        List<UUID> ids = new ArrayList<>();
        for (Map.Entry<UUID, CompanionRegistry.Entry> e : reg.ownedBy(ownerUuid)) {
            if (e.getValue().name().equals(name)) ids.add(e.getKey());
        }
        // Defensive: also catch a live body of that name somehow missing from the registry.
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p instanceof NumenPlayer a && a.isOwnedByPlayer(ownerUuid)
                    && a.getName().getString().equals(name) && !ids.contains(a.getUUID())) {
                ids.add(a.getUUID());
            }
        }
        for (UUID id : ids) {
            NumenPlayer live = NumenPlayer.findByUuid(server, id);
            if (live != null) CompanionFactory.despawn(server, live);
            reg.remove(id);
        }
        return ids.size();
    }
}
