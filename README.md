# CS16 Meta Patcher

Repacks the **CS16Client (Xash3D)** Android APK to bundle **AMX Mod X** (64-bit-cell build),
**Metamod-P** and a full addons layout, then re-signs it. No AMXX server-side install needed;
the patched APK is self-contained.

Only **arm64-v8a** (`aarch64`) is supported — this is the platform Xash3D uses on Android and the
only ABI the ported metamod/AMXX targets. arm32/x86 are out of scope by design.

## Why 64-bit cells

Official AMXX ships **32-bit cells** (`PAWN_CELL_SIZE=32`). On aarch64 a pointer is 8 bytes and
does not fit in a 4-byte cell: `amx_BrowseRelocate` stores relocated function pointers into code
cells, so `sizeof(cell)` must equal `sizeof(void*)`. Everything here therefore builds with
**64-bit cells**: core, every module, the compiler and all shipped plugins. 32-bit `.amxx` files
are rejected cleanly at load time.

## Repository layout

```
android/
  app/                  patcher APK (Jetpack Compose UI, pick+patch+sign flow)
  app/src/main/assets/  embedded bundle.zip (offline fallback, populated by CI)
  patcherlib/           pure-JVM patching core: bundle manifest, ZipRepacker,
                        apksig signing, CLI
  ci/
    build-amxx.sh       fetches upstream AMXX master + applies patches/,
                        cross-compiles core/modules/metamod/pcre/pawncc (NDK)
    gen-bundle.py       packs build output into release bundles (bundle.json)
  hlsdk/                vendored Half-Life SDK headers the AMXX build needs
  plugins-src/          sample .sma compiled during the AMXX build
  debug/                keystore used to re-sign patched APKs
patches/                in-order patches applied on top of upstream AMXX master
  amxmodx-CDetour-cell.diff     CDetour cell typedef (cell_t32/cell_t64)
  amxmodx-64bit-cell-casts.diff explicit cell casts in file.cpp
  amxmodx-memtools-dlfcn.diff   dlfcn.h include for MemoryUtils on Linux
  amxmodx-amtl-64bit.diff       two-argument Min/Max in amtl (submodule)
  amxmodx-pawncc-64bit.patch    amxxpc.cpp Compile64 + sc1.c BinReloc drop
  amxmodx-android-load-CModule.patch  Android module loader (dlopen)
  amxmodx-android-load-modules.patch  modules.cpp Linux dlopen path
  metamod-p-aarch64.patch       port of metamod-p to aarch64 Android
```

The `amxx-addons` branch holds the shipped addons tree (configs, gamedata, stock plugins) — the
CI checks it out and folds it into the bundle.

## What a patched APK contains

The bundle manifest (`bundle.json`) drives the patcher:

- `lib/arm64-v8a/libamxmodx.so`, `libmetamod.so`, 11 module libraries
  (`lib<cstrike|csx|engine|fakemeta|fun|geoip|json|nvault|regex|sockets|sqlite>_amxx_amd64.so`) —
  written **STORED** and **16 KB-aligned** (Android 15+ / 16 KB-page devices).
- `addons/**` — full AMXX config tree (configs, gamedata, plugins) written DEFLATED, so the game
  loadout appears out of the box without manual file installs.

The patcher prunes only those exact 13 libraries plus `META-INF/` from the picked APK and re-signs
it with the bundled debug keystore. All other entries (engine `libxash*.so`, resources, assets)
are copied through untouched and re-compressed per their original method.

## Building (CI)

`.github/workflows/build-and-release.yml`:
1. `build-amxx` — clone upstream `alliedmodders/amxmodx` master (with the `public/amtl` submodule),
   apply `patch -p1` in order, compile with NDK r25c: AMXX core + 11 modules, metamod-p aarch64,
   static pcre, and a host `pawncc` (64-bit) used to compile `.amxx` plugins from `.sma`.
2. `amxx-bundle` — `gen-bundle.py` produces `amxx-bundle.zip` (libraries + addons + bundle.json),
   `amxx-plugins.zip`, `amxx-addons.zip`; uploaded as a GitHub release (`amxx-bundle*.zip`).
3. `app-apk` — embeds `amxx-bundle.zip` into the app assets (offline fallback) and assembles +
   signs the patcher APK.

`PatcherViewModel` fetches the newest release bundle at runtime; if online download fails the
embedded bundle is used, so patching always works.

## Known issues / status

- Local builds are verified through the full native build; the Android patcher and release
  artifacts are exercised in CI. **On-device runtime validation is still pending**.
- Only the `addons/metamod` + `addons/amxmodx` configs shipped on the `amxx-addons` branch are
  injected; user modifications made inside an already-patched APK may be overwritten on repatch.
- 32-bit `.amxx` plugins are intentionally rejected by the 64-bit core.
- arm32/x86 APKs are refused by the patcher with a clear error.