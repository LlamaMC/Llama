package com.llamamc.llama.command;

import com.llamamc.llamaapi.command.ArgumentType;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

enum ArgumentKind {
    TEXT(String.class, "text"),
    INT(int.class, "an integer"),
    LONG(long.class, "a long integer"),
    DOUBLE(double.class, "a finite decimal number"),
    ID(UUID.class, "a UUID"),
    DURATION(Duration.class, "a duration such as 30s, 5m, 2h or 1d");

    private static final Pattern DURATION_PATTERN = Pattern.compile("(\\d+)([smhd])");

    private final Class<?> javaType;
    private final String description;

    ArgumentKind(Class<?> javaType, String description) {
        this.javaType = javaType;
        this.description = description;
    }

    static Optional<ArgumentKind> fromJavaType(Class<?> type) {
        for (var kind : values()) {
            if (kind.javaType == type) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }

    static ArgumentKind fromArgumentType(ArgumentType type) {
        return switch (type) {
            case STRING, PLAYER_NAME -> TEXT;
            case INTEGER -> INT;
            case LONG -> LONG;
            case DOUBLE -> DOUBLE;
            case UUID -> ID;
            case DURATION -> DURATION;
        };
    }

    String description() {
        return description;
    }

    Object parse(String text) {
        return switch (this) {
            case TEXT -> text;
            case INT -> Integer.parseInt(text);
            case LONG -> Long.parseLong(text);
            case DOUBLE -> finite(Double.parseDouble(text));
            case ID -> UUID.fromString(text);
            case DURATION -> duration(text);
        };
    }

    private static double finite(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("Value is not finite");
        }
        return value;
    }

    private static Duration duration(String text) {
        var matcher = DURATION_PATTERN.matcher(text);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Not a duration: " + text);
        }
        var amount = Long.parseLong(matcher.group(1));
        return switch (matcher.group(2)) {
            case "s" -> Duration.ofSeconds(amount);
            case "m" -> Duration.ofMinutes(amount);
            case "h" -> Duration.ofHours(amount);
            case "d" -> Duration.ofDays(amount);
            default -> throw new IllegalArgumentException("Not a duration: " + text);
        };
    }
}
