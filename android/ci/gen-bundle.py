#!/usr/bin/env python3
"""Pack the CI build output into release bundle artifacts.

  gen-bundle.py <libdir> <out-bundle.zip> [<pluginsdir> <out-plugins.zip> [<addons-dir>]]

The bundle manifest schema mirrors com.pickle.patcher.lib.BundleManifest so the
patched APK injects exactly these payload entries. When <addons-dir> is given
(addons/ checkout from the amxx-addons branch), configs, gamedata and stock
plugins are folded into the bundle as DEFLATED entries, making the patched APK
self-contained (works from a vanilla CS16Client APK, not only a pre-AMXX'd one).
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
    addons_dir = sys.argv[5] if len(sys.argv) > 5 else None

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

    # Freshly compiled 64-bit plugins (from build-out/plugins) land in the bundle
    # under amxmodx/plugins/{name}, injected as addons/amxmodx/plugins/{name}.
    if plugins_dir and os.path.isdir(plugins_dir):
        for name in sorted(os.listdir(plugins_dir)):
            if name.endswith(".amxx"):
                entries.append({
                    "source": f"amxmodx/plugins/{name}",
                    "target": f"addons/amxmodx/plugins/{name}",
                    "method": "DEFLATED",
                    "required": True,
                    "description": f"plugin {name}",
                })

    # On-device compiler (arm64): the amxxpc driver plus the libpc300 kernel it
    # dlopens at runtime. Layed out under build-out/compiler/ by build-amxx.sh.
    compiler_dir = os.path.join(
        os.path.dirname(os.path.dirname(libdir)), "compiler")
    for cname in ("amxxpc", "amxxpc32.so"):
        p = os.path.join(compiler_dir, cname)
        if os.path.isfile(p):
            entries.append({
                "source": f"compiler/{cname}",
                "target": f"compiler/{cname}",
                "method": "STORED",
                "required": True,
                "description": f"on-device compiler {cname}",
            })
        else:
            print(f"WARN: missing embedded compiler {cname}, skipping")

    # Whole addons/ tree (configs, gamedata, stock plugins): the addons checkout
    # root IS the addons/ dir, so rel paths are bundle sources and the APK target
    # is "addons/<rel>".
    addon_sources = set()
    if addons_dir and os.path.isdir(addons_dir):
        for root, dirs, files in os.walk(addons_dir):
            dirs.sort()
            for f in sorted(files):
                full = os.path.join(root, f)
                rel = os.path.relpath(full, addons_dir).replace(os.sep, "/")
                entries.append({
                    "source": rel,
                    "target": f"addons/{rel}",
                    "method": "DEFLATED",
                    "required": False,
                    "description": "addon file",
                })
                addon_sources.add(rel)

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
            if e["source"] in addon_sources:
                z.write(os.path.join(addons_dir, e["source"]), e["source"])
            elif e["source"].startswith("amxmodx/plugins/"):
                z.write(os.path.join(plugins_dir or "", os.path.basename(e["source"])), e["source"])
            elif e["source"].startswith("compiler/"):
                z.write(os.path.join(compiler_dir, os.path.basename(e["source"])), e["source"])
            else:
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