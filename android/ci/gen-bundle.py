#!/usr/bin/env python3
"""Pack the CI build output into release bundle artifacts.

  gen-bundle.py <libdir> <out-bundle.zip> [<pluginsdir> <out-plugins.zip>]

The bundle manifest schema mirrors com.pickle.patcher.lib.BundleManifest so the
patched APK injects exactly these payload entries. The bundle only ships the
native payload (AMXX core + metamod + module libs + client/menu libs). Addons
(configs, plugins, gamedata) and the on-device compiler are deliberately NOT
embedded anymore: the patcher installs addons separately from the
amxx-addons.zip release asset, and the compiler stays bundled inside the
patcher app itself.
"""
import json
import os
import sys
import zipfile

VERSION = os.environ.get("RELEASE_VERSION", "1.10.0-dev")

MODULES = [
    "cstrike", "csx", "engine", "fakemeta", "fun", "geoip",
    "hamsandwich", "json", "nvault", "reapi", "regex", "sockets", "sqlite",
]


def main():
    libdir, bundle_out = sys.argv[1], sys.argv[2]
    plugins_dir, plugins_out = (sys.argv[3], sys.argv[4]) if len(sys.argv) > 3 else (None, None)

    entries = []
    core = os.path.join(libdir, "libamxmodx.so")
    assert os.path.exists(core), f"missing {core}"
    entries.append({
        "source": "lib/arm64-v8a/libamxmodx.so",
        "target": "lib/arm64-v8a/libamxmodx.so",
        "method": "STORED",
        "required": True,
        "description": "AMX Mod X core",
    })
    metamod = os.path.join(libdir, "libmetamod.so")
    if os.path.exists(metamod):
        entries.append({
            "source": "lib/arm64-v8a/libmetamod.so",
            "target": "lib/arm64-v8a/libmetamod.so",
            "method": "STORED",
            "required": True,
            "description": "Metamod HL1",
        })
        # Xash3D Android resolves `-dll @yapb` (hardcoded in classes.dex /
        # MainActivity argv) to lib/arm64-v8a/libyapb_android_arm64.so and loads it
        # as the game DLL. Shipping metamod under that same name makes the patched
        # APK run metamod (and therefore amxmodx) as the gamedll instead of YaPB,
        # without having to rewrite the dex. Content equals libmetamod.so.
        entries.append({
            "source": "lib/arm64-v8a/libmetamod.so",
            "target": "lib/arm64-v8a/libyapb_android_arm64.so",
            "method": "STORED",
            "required": True,
            "description": "Metamod as gamedll (masks libyapb_android_arm64.so)",
        })
    # Actual YaPB bot .so — loaded by metamod via plugins.ini
    yapb_so = os.path.join(libdir, "libyapb.so")
    if os.path.exists(yapb_so):
        entries.append({
            "source": "lib/arm64-v8a/libyapb.so",
            "target": "lib/arm64-v8a/libyapb.so",
            "method": "STORED",
            "required": False,
            "description": "YaPB bot plugin",
        })
    # CS16Client client DLL with crash handler (vcs16/cl_dll)
    client_so = os.path.join(libdir, "libclient_android_arm64.so")
    if os.path.exists(client_so):
        entries.append({
            "source": "lib/arm64-v8a/libclient_android_arm64.so",
            "target": "lib/arm64-v8a/libclient_android_arm64.so",
            "method": "STORED",
            "required": False,
            "description": "CS16Client client DLL (crash handler)",
        })
    # Text-based main menu (mainui_cpp -> libmenu_android_arm64.so). Replaces the
    # stock menu so banner titles and menu buttons render as text.
    menu_so = os.path.join(libdir, "libmenu_android_arm64.so")
    if os.path.exists(menu_so):
        entries.append({
            "source": "lib/arm64-v8a/libmenu_android_arm64.so",
            "target": "lib/arm64-v8a/libmenu_android_arm64.so",
            "method": "STORED",
            "required": False,
            "description": "CS16Client main menu (text banners/buttons)",
        })
    for mod in MODULES:
        p = os.path.join(libdir, f"lib{mod}_amxx_amd64.so")
        if os.path.exists(p):
            entries.append({
                "source": f"lib/arm64-v8a/lib{mod}_amxx_amd64.so",
                "target": f"lib/arm64-v8a/lib{mod}_amxx_amd64.so",
                "method": "STORED",
                "required": True,
                "description": f"{mod} module",
            })
        else:
            print(f"WARN: missing module {mod}, skipping")

    manifest = {
        "version": VERSION,
        "game": "cs16client",
        "entries": entries,
    }

    with zipfile.ZipFile(bundle_out, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("bundle.json", json.dumps(manifest, ensure_ascii=False, separators=(",", ":")))
        seen_sources = set()
        for e in entries:
            if e["source"] in seen_sources:
                continue
            seen_sources.add(e["source"])
            z.write(os.path.join(libdir, os.path.basename(e["source"])), e["source"])

    print(f"bundle: {bundle_out} ({os.path.getsize(bundle_out)} bytes, {len(entries)} entries)")

    if plugins_dir and plugins_out:
        with zipfile.ZipFile(plugins_out, "w", zipfile.ZIP_DEFLATED) as z:
            for name in sorted(os.listdir(plugins_dir)):
                if name.endswith(".amxx"):
                    z.write(os.path.join(plugins_dir, name), f"plugins/{name}")
        print(f"plugins: {plugins_out} ({os.path.getsize(plugins_out)} bytes)")


if __name__ == "__main__":
    main()