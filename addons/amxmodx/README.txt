AMX Mod X for CS16Client (Android / arm64, 64-bit cell build)
=============================================================
This addons tree is the ready-to-run AMXX 1.10 installation that pairs with
the special APK build of CS16Client. Copy it into

    /storage/emulated/0/xash/cstrike/addons/

and relaunch the game. Config and data changes are picked up on restart;
changing the AMXX core or its modules requires an APK rebuild.

What lives where
----------------
addons/amxmodx/
  configs/    AMXX settings (plugins.ini = load list, modules.ini, users.ini,
              amxx.cfg ...)
  data/lang/  language dictionaries (stock AMXX dictionaries)
  modules/    AMXX C++ modules (aarch64). The 64-bit-cell core loads
              <name>_amxx_amd64.so from here (the arm32 build uses
              <name>_amxx_arm.so; see modules.ini). Only engine, fakemeta and
              cstrike are enabled in modules.ini; the rest are shipped but
              commented out.
  plugins/    compiled plugins (*.amxx, 64-bit cell bytecode)
  scripting/  dev compiler + sources. The game never reads this; see
              "Building plugins" below.
  logs/       written at runtime; safe to delete

addons/metamod/   metamod loader config only. The loader executable itself
                  comes from the APK; these two files tell it which library
                  to chain (AMXX core) and which gamedll to use.

What is inside the APK (not in this folder)
-------------------------------------------
The engine, the metamod loader and the AMXX core library (libamxmodx.so) are
packed in the APK's native lib directory. The engine resolves "@addons/..."
references against that directory ("loaded from APK path"). Emulated storage
is not executable, so no game-facing shared library can run from here.
Consequence: edit plugins/configs/data -> just edit this folder and relaunch.
Change the core/modules -> the APK must be rebuilt.

Commands
--------
amx_plugins          list loaded plugins (admin)

Building plugins (optional)
---------------------------
The 64-bit-cell compiler ships with this tree at scripting/amxxpc, so the
toolchain is part of the addons (it is not used at runtime; emulated storage
is noexec, so compilation happens on the dev machine). To build a plugin:
  - put the source in scripting/, includes in scripting/include/
  - run the amxxpc mirror from an executable path (e.g. termux) with
      amxxpc -i<scripting/include> -h<script.inc> <script.sma> -o"<out>.amxx"
  - drop the .amxx into plugins/ and add a line to configs/plugins.ini

hello.amxx in plugins/ is a minimal demo plugin you can build from
scripting/hello.sma to verify the toolchain.

History notes for this build
----------------------------
- 64-bit PAWN cells: .amxx has cellsize byte = 8. Stock 32-bit plugins are
  rejected.
- This fork ships no LoadLangFile/%L (plugin-side dictionaries only) and no
  CreateForward (use CreateMultiForward). Its has_flag() takes flag strings.
- Modules are named *_amxx_amd64.so (LP64 ABIs) / *_amxx_arm.so (arm32)
  and come from this tree at runtime.