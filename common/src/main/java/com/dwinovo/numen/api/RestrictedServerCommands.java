package com.dwinovo.numen.api;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Parses the deliberately small command surface exposed to the private
 * dedicated-server brain.
 *
 * <p>This is a semantic allowlist rather than a command-name prefix check.
 * Every accepted command is rebuilt from validated tokens before it reaches
 * Brigadier, so selectors, alternate targets, nested commands, and extra
 * arguments cannot ride along.
 */
@Internal
public final class RestrictedServerCommands {

    private static final int MAX_COMMAND_LENGTH = 128;
    private static final Pattern PLAYER_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final Pattern COORDINATE = Pattern.compile(
            "(?:~(?:[-+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+))?|[-+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+))");
    private static final Set<String> TIMES =
            Set.of("day", "night", "noon", "midnight");
    private static final Set<String> WEATHERS =
            Set.of("clear", "rain", "thunder");
    private static final Set<String> GAME_MODES =
            Set.of("survival", "creative", "adventure", "spectator");
    private static final Set<String> DIFFICULTIES =
            Set.of("peaceful", "easy", "normal", "hard");

    private RestrictedServerCommands() {}

    public record Validation(boolean allowed, String command, String error) {
        private static Validation approve(String command) {
            return new Validation(true, command, "");
        }

        private static Validation reject(String error) {
            return new Validation(false, "", error);
        }
    }

    public static Validation validate(String rawCommand) {
        if (rawCommand == null) return Validation.reject("command is required");
        String clean = rawCommand.trim();
        if (clean.startsWith("/")) clean = clean.substring(1).trim();
        if (clean.isEmpty() || clean.length() > MAX_COMMAND_LENGTH
                || clean.indexOf('\n') >= 0 || clean.indexOf('\r') >= 0) {
            return Validation.reject("command must contain 1 to 128 characters");
        }

        String[] tokens = clean.split("\\s+");
        String root = tokens[0].toLowerCase(Locale.ROOT);
        return switch (root) {
            case "time" -> validateTime(tokens);
            case "weather" -> validateWeather(tokens);
            case "tp" -> validateTeleport(tokens);
            case "gamemode" -> validateGameMode(tokens);
            case "difficulty" -> validateDifficulty(tokens);
            default -> Validation.reject(
                    "only /time, /weather, /tp, /gamemode and /difficulty are allowed");
        };
    }

    private static Validation validateTime(String[] tokens) {
        if (tokens.length != 3) {
            return Validation.reject("use /time set <day|night|noon|midnight|0..24000> "
                    + "or /time add <0..24000>");
        }
        String operation = tokens[1].toLowerCase(Locale.ROOT);
        String value = tokens[2].toLowerCase(Locale.ROOT);
        if ("set".equals(operation) && (TIMES.contains(value) || boundedInt(value, 0, 24_000))) {
            return normalized(tokens, operation, value);
        }
        if ("add".equals(operation) && boundedInt(value, 0, 24_000)) {
            return normalized(tokens, operation, value);
        }
        return Validation.reject("that /time form is outside the allowlist");
    }

    private static Validation validateWeather(String[] tokens) {
        if (tokens.length < 2 || tokens.length > 3) {
            return Validation.reject("use /weather <clear|rain|thunder> [1..1000000]");
        }
        String weather = tokens[1].toLowerCase(Locale.ROOT);
        if (!WEATHERS.contains(weather)) {
            return Validation.reject("weather must be clear, rain or thunder");
        }
        if (tokens.length == 3 && !boundedInt(tokens[2], 1, 1_000_000)) {
            return Validation.reject("weather duration must be from 1 to 1000000 seconds");
        }
        return normalized(tokens, weather);
    }

    private static Validation validateTeleport(String[] tokens) {
        if (tokens.length == 2 && PLAYER_NAME.matcher(tokens[1]).matches()) {
            return Validation.approve("tp " + tokens[1]);
        }
        if (tokens.length == 4
                && COORDINATE.matcher(tokens[1]).matches()
                && COORDINATE.matcher(tokens[2]).matches()
                && COORDINATE.matcher(tokens[3]).matches()) {
            return Validation.approve(String.join(" ", tokens));
        }
        return Validation.reject(
                "/tp may only move Momo herself to one player name or to x y z coordinates");
    }

    private static Validation validateGameMode(String[] tokens) {
        if (tokens.length != 2) {
            return Validation.reject("/gamemode may only change Momo herself");
        }
        String mode = tokens[1].toLowerCase(Locale.ROOT);
        if (!GAME_MODES.contains(mode)) {
            return Validation.reject("unknown game mode");
        }
        return Validation.approve("gamemode " + mode);
    }

    private static Validation validateDifficulty(String[] tokens) {
        if (tokens.length != 2) {
            return Validation.reject("use /difficulty <peaceful|easy|normal|hard>");
        }
        String difficulty = tokens[1].toLowerCase(Locale.ROOT);
        if (!DIFFICULTIES.contains(difficulty)) {
            return Validation.reject("unknown difficulty");
        }
        return Validation.approve("difficulty " + difficulty);
    }

    private static Validation normalized(String[] tokens, String... replacements) {
        String[] normalized = Arrays.copyOf(tokens, tokens.length);
        normalized[0] = normalized[0].toLowerCase(Locale.ROOT);
        for (int i = 0; i < replacements.length; i++) normalized[i + 1] = replacements[i];
        return Validation.approve(String.join(" ", normalized));
    }

    private static boolean boundedInt(String raw, int min, int max) {
        if (!raw.matches("\\d{1,7}")) return false;
        try {
            int value = Integer.parseInt(raw);
            return value >= min && value <= max;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }
}
