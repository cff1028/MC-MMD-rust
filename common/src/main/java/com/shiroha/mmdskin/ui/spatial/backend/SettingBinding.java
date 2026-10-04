package com.shiroha.mmdskin.ui.spatial.backend;

import com.shiroha.mmdskin.ui.spatial.lumen.model.Setting;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** A real setting endpoint. Presentation never writes a config file or guesses a runtime value. */
record SettingBinding(Setting schema, Supplier<Object> read, Consumer<Object> write,
                      Supplier<String> display, BooleanSupplier enabled, Runnable reset) {
    SettingBinding(Setting schema, Supplier<Object> read, Consumer<Object> write) {
        this(schema, read, write, () -> String.valueOf(read.get()), () -> true,
                () -> write.accept(schema.defaultValue()));
    }
}
