package com.wiyuka.acceleratedrecoiling.natives.realtime.compat;

import com.wiyuka.acceleratedrecoiling.AcceleratedRecoiling;
import net.neoforged.fml.ModList;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.stream.Collectors;

public final class LithiumCompatibility { // TODO: unnecessary, delete this.
    private static final Set<String> MIXINS = loadMixins();

    private LithiumCompatibility() {
    }

    private static Set<String> loadMixins() {
        try (var input = LithiumCompatibility.class.getResourceAsStream("/acceleratedrecoiling/lithium-mixins.txt")) {
            if (input == null) {
                throw new IOException("Missing Lithium mixin rules");
            }

            return new String(input.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty())
                    .collect(Collectors.toUnmodifiableSet());
        } catch (IOException e) {
            AcceleratedRecoiling.LOGGER.warn("Could not read Lithium mixin rules; retaining collision fallback", e);
            return Set.of();
        }
    }

    private static boolean isLoaded() {
        var mods = ModList.get();
        return mods != null && mods.isLoaded("lithium");
    }

    public static boolean allows(String mixin) {
        return isLoaded() && MIXINS.contains(mixin);
    }

    public static String status() {
        if (!isLoaded()) {
            return "absent";
        }

        return MIXINS.isEmpty() ? "missing-rules" : "mixin-allowlist";
    }
}
