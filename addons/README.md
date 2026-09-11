CS16Client - addons (AMX Mod X for the arm64 / 64-bit-cell APK build)
====================================================================
This repository branch contains ONLY the runtime "addons" tree that goes
together with the special CS16Client APK build. The APK itself is not part
of this branch: it carries the engine, the metamod loader and the AMXX core
(libamxmodx.so) in its native lib directory.

Install
-------
Copy the "addons" folder into the game directory your build reads, e.g.:

    addons/ -> /storage/emulated/0/xash/cstrike/addons/

Then relaunch the game. See addons/amxmodx/README.txt for the layout.

Native code vs config
---------------------
Everything that must execute (engine, metamod, AMXX core) lives inside the
APK; the engine resolves "@addons/..." references against the APK's lib/arm64
directory. This tree supplies everything data/config driven:
  - addons/metamod/   metamod config (config.ini + plugins.ini) that tells
                      the APK-side loader to chain AMXX
  - addons/amxmodx/   AMXX 1.10 configs, language dictionaries, modules
                      (*_amxx_amd64.so; *_amxx_arm.so on arm32), plugins,
                      scripting/amxxpc compiler, and runtime logs dir

Editing this tree never needs an APK rebuild; swapping the AMXX core or
modules does.