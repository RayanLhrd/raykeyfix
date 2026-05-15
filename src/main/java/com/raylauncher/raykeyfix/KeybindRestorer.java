// SPDX-License-Identifier: GPL-3.0-only
package com.raylauncher.raykeyfix;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.extensions.IKeyMappingExtension;
import net.neoforged.neoforge.client.settings.KeyModifier;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Filesystem-level keybind snapshot + in-memory restore.
 *
 * The backup file lives at `<gameDir>/options.txt.raykeyfix-backup`. It's a
 * verbatim copy of options.txt taken at the end of every session (and after
 * every successful restore). On the next launch, after MC has finished its
 * buggy startup write but BEFORE the user opens the Options screen, we walk
 * the backup line-by-line and re-apply the saved key codes to the in-memory
 * {@link KeyMapping} instances via {@link KeyMapping#setKey(InputConstants.Key)}.
 *
 * The class is package-private and stateless apart from the cached {@link #gameDir}
 * — there's no instance state to leak and no scheduler running, all work happens
 * synchronously when the public methods are called.
 */
final class KeybindRestorer {
    private static final String BACKUP_FILENAME = "options.txt.raykeyfix-backup";
    private static final String OPTIONS_FILENAME = "options.txt";
    private static final String KEY_PREFIX = "key_";

    private static volatile Path gameDir;

    static void setGameDir(Path dir) {
        gameDir = dir;
    }

    /**
     * Apply the keybind values from {@code options.txt.raykeyfix-backup} (if it
     * exists) to the in-memory {@link KeyMapping}s. On the very first launch the
     * backup doesn't exist yet — we seed it from the current options.txt so
     * subsequent sessions have something to restore from. The seed happens
     * post-bug, so first-session custom keybinds are still lost (the user hasn't
     * configured anything yet on a fresh install), but every session after that
     * survives clean.
     */
    static void restoreFromBackup() throws IOException {
        Path dir = gameDir;
        if (dir == null) {
            RayKeyFix.LOG.warn("RayKeyFix: gameDir not set, skipping restore");
            return;
        }
        Path backup = dir.resolve(BACKUP_FILENAME);
        Path optionsTxt = dir.resolve(OPTIONS_FILENAME);

        if (!Files.exists(backup)) {
            if (Files.exists(optionsTxt)) {
                Files.copy(optionsTxt, backup);
                RayKeyFix.LOG.info("RayKeyFix: seeded backup from current options.txt (first run)");
            }
            return;
        }

        Map<String, String> backedUp = parseBackup(backup);
        if (backedUp.isEmpty()) {
            RayKeyFix.LOG.info("RayKeyFix: backup has no key_* lines, nothing to restore");
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.options == null) {
            RayKeyFix.LOG.warn("RayKeyFix: Minecraft options not ready, skipping restore");
            return;
        }

        int restored = 0;
        int skipped = 0;
        KeyMapping[] mappings = mc.options.keyMappings;
        for (KeyMapping km : mappings) {
            String desired = backedUp.get(km.getName());
            if (desired == null) continue;
            try {
                // NeoForge extends MC's keybind format with an optional ":MODIFIER" suffix
                // (CONTROL / SHIFT / ALT / NONE) — mods like JEI, Jade, etc. use this to bind
                // chord combos like Ctrl+O for "toggle JEI overlay". The vanilla
                // InputConstants.getKey() doesn't know about modifiers and throws on the
                // suffix, so we split it off and apply both via NeoForge's
                // IKeyMappingExtension API.
                String keyId = desired;
                KeyModifier modifier = KeyModifier.NONE;
                int lastColon = desired.lastIndexOf(':');
                if (lastColon > 0) {
                    String suffix = desired.substring(lastColon + 1);
                    try {
                        modifier = KeyModifier.valueOf(suffix);
                        keyId = desired.substring(0, lastColon);
                    } catch (IllegalArgumentException ignored) {
                        // Not a modifier — treat the whole string as the key id and let
                        // InputConstants.getKey() decide whether it's a valid binding.
                    }
                }
                InputConstants.Key newKey = InputConstants.getKey(keyId);
                IKeyMappingExtension ext = (IKeyMappingExtension) (Object) km;
                if (!km.getKey().equals(newKey) || ext.getKeyModifier() != modifier) {
                    ext.setKeyModifierAndCode(modifier, newKey);
                    restored++;
                }
            } catch (Exception e) {
                skipped++;
                RayKeyFix.LOG.warn("RayKeyFix: could not apply key '{}' to mapping '{}': {}",
                        desired, km.getName(), e.getMessage());
            }
        }

        if (restored > 0) {
            // Flush the corrected in-memory state to options.txt so the file
            // matches what MC has live. Then refresh the backup with the
            // canonical state so future sessions don't drift.
            mc.options.save();
            Files.copy(optionsTxt, backup, StandardCopyOption.REPLACE_EXISTING);
            RayKeyFix.LOG.info("RayKeyFix: restored {} keybind(s) from backup ({} skipped)",
                    restored, skipped);
        } else {
            RayKeyFix.LOG.info("RayKeyFix: no keybinds needed restoring (state already matches backup)");
        }
    }

    /**
     * Copy the current options.txt over the backup. Called from the JVM shutdown
     * hook so that any in-game keybind tweaks the user made during the session
     * are captured before the game exits. Best-effort: silently no-ops if either
     * file is missing or the game dir was never resolved.
     */
    static void updateBackup() throws IOException {
        Path dir = gameDir;
        if (dir == null) return;
        Path backup = dir.resolve(BACKUP_FILENAME);
        Path optionsTxt = dir.resolve(OPTIONS_FILENAME);
        if (Files.exists(optionsTxt)) {
            Files.copy(optionsTxt, backup, StandardCopyOption.REPLACE_EXISTING);
            RayKeyFix.LOG.info("RayKeyFix: backup refreshed on shutdown");
        }
    }

    private static Map<String, String> parseBackup(Path backup) throws IOException {
        Map<String, String> out = new HashMap<>();
        List<String> lines = Files.readAllLines(backup, StandardCharsets.UTF_8);
        for (String line : lines) {
            if (!line.startsWith(KEY_PREFIX)) continue;
            int sep = line.indexOf(':');
            if (sep <= KEY_PREFIX.length()) continue;
            // Strip the "key_" prefix so the lookup key matches KeyMapping.getName()
            // which is the inner "key.something" form (e.g. "key.crawl").
            String keyMappingName = line.substring(KEY_PREFIX.length(), sep).trim();
            String keyId = line.substring(sep + 1).trim();
            if (keyMappingName.isEmpty() || keyId.isEmpty()) continue;
            out.put(keyMappingName, keyId);
        }
        return out;
    }

    private KeybindRestorer() {}
}
