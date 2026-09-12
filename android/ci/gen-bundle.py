#!/usr/bin/env python3
"""Pack the CI build output into release bundle artifacts.

  gen-bundle.py <abi> <libdir> <out-bundle.zip> [<pluginsdir> <out-plugins.zip>]

The bundle manifest schema mirrors com.pickle.patcher.lib.BundleManifest so the
patched APK injects exactly these payload entries. The bundle only ships the
native payload (AMXX core + metamod + module libs + client/menu libs) for a
single target ABI; addons (configs, plugins, gamedata) and the on-device
compiler are deliberately NOT embedded anymore: the patcher installs addons
separately from the amxx-addons.zip release asset, and the compiler stays
bundled inside the patcher app itself.
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

# ABI -> (android runtime lib suffix, AMXX module suffix, canonical bundle asset name)
# Module suffix: "amd64" upstream = 64-bit cells (LP64 ABIs), "arm" = ARM32.
ABI_MAP = {
    "arm64-v8a": ("arm64", "amd64", "amxx-bundle.zip"),
    "armeabi-v7a": ("armv7l", "arm", "amxx-bundle-armeabi-v7a.zip"),
}


def main():
    abi, libdir, bundle_out = sys.argv[1], sys.argv[2], sys.argv[3]
    plugins_dir, plugins_out = (sys.argv[4], sys.argv[5]) if len(sys.argv) > 4 else (None, None)

    if abi not in ABI_MAP:
        print(f"unsupported ABI: {abi} (expected {', '.join(ABI_MAP)})", file=sys.stderr)
        sys.exit(1)
    suffix, mod_suffix, _bundle_name = ABI_MAP[abi]

    abidir = f"lib/{abi}"

    entries = []
    core = os.path.join(libdir, "libamxmodx.so")
    assert os.path.exists(core), f"missing {core}"
    entries.append({
        "source": f"{abidir}/libamxmodx.so",
        "target": f"{abidir}/libamxmodx.so",
        "method": "STORED",
        "required": True,
        "description": "AMX Mod X core",
    })
    metamod = os.path.join(libdir, "libmetamod.so")
    if os.path.exists(metamod):
        entries.append({
            "source": f"{abidir}/libmetamod.so",
            "target": f"{abidir}/libyapb_android_{suffix}.so",
            "method": "STORED",
            "required": True,
            "description": "Metamod HL1 (as libyapb for -dll @yapb)",
        })
    # Actual YaPB bot .so — loaded by metamod via plugins.ini
    yapb_so = os.path.join(libdir, "libyapb.so")
    if os.path.exists(yapb_so):
        entries.append({
            "source": f"{abidir}/libyapb.so",
            "target": f"{abidir}/libyapb.so",
            "method": "STORED",
            "required": False,
            "description": "YaPB bot plugin",
        })
    # CS16Client client DLL with crash handler (vcs16/cl_dll)
    client_so = os.path.join(libdir, f"libclient_android_{suffix}.so")
    if os.path.exists(client_so):
        entries.append({
            "source": f"{abidir}/libclient_android_{suffix}.so",
            "target": f"{abidir}/libclient_android_{suffix}.so",
            "method": "STORED",
            "required": False,
            "description": "CS16Client client DLL (crash handler)",
        })
    # Text-based main menu (mainui_cpp -> libmenu_android_<arch>.so). Replaces
    # the stock menu so banner titles and menu buttons render as text.
    menu_so = os.path.join(libdir, f"libmenu_android_{suffix}.so")
    if os.path.exists(menu_so):
        entries.append({
            "source": f"{abidir}/libmenu_android_{suffix}.so",
            "target": f"{abidir}/libmenu_android_{suffix}.so",
            "method": "STORED",
            "required": False,
            "description": "CS16Client main menu (text banners/buttons)",
        })
    for mod in MODULES:
        modname = f"lib{mod}_amxx_{mod_suffix}.so"
        p = os.path.join(libdir, modname)
        if os.path.exists(p):
            entries.append({
                "source": f"{abidir}/{modname}",
                "target": f"{abidir}/{modname}",
                "method": "STORED",
                "required": True,
                "description": f"{mod} module",
            })
        else:
            print(f"WARN: missing module {mod}, skipping")

    manifest = {
        "version": VERSION,
        "game": "cs16client",
        "abi": abi,
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

    print(f"bundle: {bundle_out} ({os.path.getsize(bundle_out)} bytes, {len(entries)} entries, abi={abi})")

    if plugins_dir and plugins_out:
        with zipfile.ZipFile(plugins_out, "w", zipfile.ZIP_DEFLATED) as z:
            for name in sorted(os.listdir(plugins_dir)):
                if name.endswith(".amxx"):
                    z.write(os.path.join(plugins_dir, name), f"plugins/{name}")
        print(f"plugins: {plugins_out} ({os.path.getsize(plugins_out)} bytes)")


if __name__ == "__main__":
    main()