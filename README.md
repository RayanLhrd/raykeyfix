# RayKeyFix

Workaround for Minecraft 1.21.0–1.21.4's `client_loaded_correctly` keybind reset bug.

## The bug

In MC 1.21, Mojang added a `client_loaded_correctly` flag stored in `options.txt` instead of a separate file. At startup, Minecraft rewrites `options.txt` before mods have finished registering their `KeyMapping`s — wiping the saved values for modded keybinds. By the time the flag flips back to `true` at end of load, the modded `KeyMapping`s have been registered with their default values and the user's custom binds are gone.

Sources:
- [LexManos confirming the bug (Forge issue #10531)](https://github.com/MinecraftForge/MinecraftForge/issues/10531)
- [NeoForge PR #2049 fixing it](https://github.com/neoforged/NeoForge/pull/2049) — merged only for NeoForge 21.5 / MC 1.21.5, not backported.

## What RayKeyFix does

A 100-line client-side mod that runs at `FMLClientSetupEvent` (after every other mod has registered its keybinds) and again on JVM shutdown:

- **First launch**: snapshots `options.txt` → `options.txt.raykeyfix-backup`. No keybinds restored yet (the player hasn't customised anything).
- **Subsequent launches**: reads the backup, walks every `key_X:key.Y` line, finds the matching in-memory `KeyMapping` and calls `setKey(...)`. Then calls `Minecraft.getInstance().options.save()` to flush the corrected state back to `options.txt`.
- **On shutdown**: copies the current `options.txt` over the backup so any in-game keybind changes the user made during the session survive into the next launch.

The fix is in **memory**, not just on disk. The Options screen reads from memory, so the player sees their custom binds restored before they even open the menu.

## Compatibility

- Minecraft **1.21.1**
- NeoForge **21.1.x** (built against 21.1.228)
- Client-side only — does not load on dedicated servers.

## Install

Drop the latest `raykeyfix-X.Y.Z.jar` from [releases](https://github.com/RayanLhrd/raykeyfix/releases/latest) into your modpack's `mods/` folder. Ships bundled with [RayLauncher](https://github.com/RayanLhrd/RayLauncher) modpacks.

## License

GPL-3.0-only.
