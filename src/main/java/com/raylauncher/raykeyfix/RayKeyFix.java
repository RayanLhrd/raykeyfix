// SPDX-License-Identifier: GPL-3.0-only
package com.raylauncher.raykeyfix;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Workaround for Minecraft 1.21.0-1.21.4's `client_loaded_correctly` keybind
 * reset bug (Mojang issue, NeoForge PR #2049 fixed it only in 1.21.5+).
 *
 * Wiring:
 *   - FMLClientSetupEvent (after all mods have registered their KeyMappings) →
 *     {@link KeybindRestorer#restoreFromBackup()} reads
 *     `options.txt.raykeyfix-backup` and applies the saved values to the
 *     in-memory KeyMappings, then calls Minecraft.options.save() to flush the
 *     corrected state back to options.txt.
 *   - JVM shutdown hook (registered in the same event) →
 *     {@link KeybindRestorer#updateBackup()} copies options.txt to the backup so
 *     any in-game keybind changes the user made during the session survive into
 *     the next launch.
 *
 * Why memory-level, not file-level: MC keeps GameOptions in memory after the
 * initial load and never re-reads options.txt at runtime. Restoring just the
 * file (which the original Claude session proposed) wouldn't fix the in-game
 * state — the Options screen reads from memory. We have to write to the
 * KeyMapping instances directly, which is what this mod does.
 */
@Mod(value = RayKeyFix.MODID, dist = Dist.CLIENT)
public class RayKeyFix {
    public static final String MODID = "raykeyfix";
    public static final Logger LOG = LoggerFactory.getLogger("RayKeyFix");

    public RayKeyFix(IEventBus modBus) {
        modBus.addListener(this::onClientSetup);
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        // Cache the game directory now (while Minecraft.getInstance() is healthy);
        // the JVM shutdown hook below runs late in teardown where calling MC APIs
        // can be flaky, so we resolve the path eagerly.
        KeybindRestorer.setGameDir(Minecraft.getInstance().gameDirectory.toPath());

        // enqueueWork defers the restore to MC's main thread once all client
        // setup events have run, ensuring every mod has finished registering
        // its KeyMappings before we try to look them up by name.
        event.enqueueWork(() -> {
            try {
                KeybindRestorer.restoreFromBackup();
            } catch (Exception e) {
                LOG.error("RayKeyFix: keybind restore failed", e);
            }
        });

        // Shutdown hook to snapshot the final state. If the user changed any
        // keybinds in-game, MC has written them to options.txt by now; we just
        // mirror that file into the backup so next launch starts from there.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                KeybindRestorer.updateBackup();
            } catch (Exception ignored) {
                // Best-effort: if the JVM is already partially torn down, we
                // can't do much. The backup is also refreshed at end of
                // restoreFromBackup() so a missed shutdown isn't fatal.
            }
        }, "raykeyfix-shutdown"));
    }
}
