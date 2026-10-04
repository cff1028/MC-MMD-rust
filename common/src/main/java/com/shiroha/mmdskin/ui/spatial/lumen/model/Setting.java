package com.shiroha.mmdskin.ui.spatial.lumen.model;

import java.util.List;
import java.util.Objects;

/** Presentation-independent schema. Slider values retain the units of their source field. */
public record Setting(String id, String category, String section, String title, String description,
                      Kind kind, double min, double max, double step, Object defaultValue,
                      List<String> options, String source) {
    public enum Kind { TOGGLE, SLIDER, CHOICE, ACTION, TEXT }

    public Setting {
        Objects.requireNonNull(id); Objects.requireNonNull(category); Objects.requireNonNull(section);
        Objects.requireNonNull(title); Objects.requireNonNull(description); Objects.requireNonNull(kind);
        Objects.requireNonNull(defaultValue); Objects.requireNonNull(source);
        options = List.copyOf(options);
        if (id.isBlank() || source.isBlank()) throw new IllegalArgumentException("Setting needs an ID and source");
        switch (kind) {
            case ACTION, TEXT -> {
                if (!(defaultValue instanceof String)) throw new IllegalArgumentException(id + ": string default required");
            }
            case TOGGLE -> {
                if (!(defaultValue instanceof Boolean)) throw new IllegalArgumentException(id + ": boolean default required");
            }
            case SLIDER -> {
                if (!Double.isFinite(min) || !Double.isFinite(max) || min >= max
                        || !Double.isFinite(step) || step <= 0 || !(defaultValue instanceof Number n)
                        || !Double.isFinite(n.doubleValue()) || n.doubleValue() < min || n.doubleValue() > max)
                    throw new IllegalArgumentException(id + ": invalid slider domain/default");
                defaultValue = ((Number) defaultValue).doubleValue();
            }
            case CHOICE -> {
                if (options.size() < 2 || !options.contains(defaultValue)
                        || options.stream().distinct().count() != options.size())
                    throw new IllegalArgumentException(id + ": invalid choice domain/default");
            }
        }
    }

    /** Wrong types are rejected; finite numbers clamp and snap to the declared increment. */
    public Object normalize(Object value) {
        return switch (kind) {
            case ACTION, TEXT -> {
                if (!(value instanceof String)) throw new IllegalArgumentException(id + ": expected text");
                yield value;
            }
            case TOGGLE -> {
                if (!(value instanceof Boolean)) throw new IllegalArgumentException(id + ": expected boolean");
                yield value;
            }
            case CHOICE -> {
                if (!(value instanceof String) || !options.contains(value))
                    throw new IllegalArgumentException(id + ": unknown choice " + value);
                yield value;
            }
            case SLIDER -> {
                if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue()))
                    throw new IllegalArgumentException(id + ": expected finite number");
                double bounded = Math.max(min, Math.min(max, n.doubleValue()));
                // Keep exact source defaults (some are finer than a convenient slider increment).
                if (Double.compare(bounded, ((Number) defaultValue).doubleValue()) == 0) yield bounded;
                double snapped = min + Math.round((bounded - min) / step) * step;
                yield Math.max(min, Math.min(max, Math.rint(snapped * 1e9) / 1e9));
            }
        };
    }
}
