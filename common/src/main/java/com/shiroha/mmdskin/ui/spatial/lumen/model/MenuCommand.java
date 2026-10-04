package com.shiroha.mmdskin.ui.spatial.lumen.model;

import java.util.Map;
import java.util.Objects;

/** Intent emitted by a menu. A future game adapter routes it on the Minecraft client thread. */
public record MenuCommand(String type, String target, Map<String, Object> arguments) {
    public MenuCommand {
        Objects.requireNonNull(type); Objects.requireNonNull(target);
        if (type.isBlank()) throw new IllegalArgumentException("Command type must not be blank");
        arguments = Map.copyOf(arguments);
    }
    public MenuCommand(String type, String target) { this(type, target, Map.of()); }
}
