# CS16Client [![Build Status](https://github.com/Velaron/cs16-client/actions/workflows/build.yml/badge.svg)](https://github.com/Velaron/cs16-client/actions) <img align="right" width="128" height="128" src="https://github.com/Velaron/cs16-client/raw/main/android/app/src/main/ic_launcher-playstore.png" alt="CS16Client" />
Reverse-engineered Counter Strike 1.6 client, designed for mobile platforms and other officially non-supported platforms.

## Donate
[![Boosty.to](https://img.shields.io/badge/Boosty-F15F2C?logo=boosty&logoColor=fff&style=for-the-badge)](https://boosty.to/velaron)

[Support me](https://boosty.to/velaron) on Boosty.to, if you like my work and would like to support further development goals, like reverse-engineering other great mods.

Important contributors:
* [a1batross](https://github.com/a1batross), initial project creator and maintainer.
* [jeefo](https://github.com/jeefo), the creator of [YaPB](https://github.com/yapb/yapb).
* The people behind [ReGameDLL_CS](https://github.com/rehlds/ReGameDLL_CS) project.
* [Vladislav4KZ](https://github.com/Vladislav4KZ), bug-tester and maintainer.
* [SNMetamorph](https://github.com/SNMetamorph), author of the PSVita port.
* [Alprnn357](https://github.com/Alprnn357), touch menus maintainer.
* [wh1tesh1t](https://github.com/wh1tesh1t), [pwd491](https://github.com/pwd491), [Elinsrc](https://github.com/Elinsrc), [xiaodo1337](https://github.com/xiaodo1337), [nekonomicon](https://github.com/nekonomicon), [lewa-j](https://github.com/lewa-j) and others for minor contributions.

## Download
You can download a build at the `Releases` section, or use these links for common platforms:
* [Android](https://github.com/Velaron/cs16-client/releases/download/continuous/CS16Client-Android.apk)
* [Linux](https://github.com/Velaron/cs16-client/releases/download/continuous/CS16Client-Linux-i386.tar.gz)
* [Windows](https://github.com/Velaron/cs16-client/releases/download/continuous/CS16Client-Windows-X86.zip)
* [PS Vita](https://github.com/Velaron/cs16-client/releases/download/continuous/CS16Client-PSVita.zip)
* [macOS (arm64)](https://github.com/Velaron/cs16-client/releases/download/continuous/CS16Client-macOS-arm64.zip) - not tested
* [macOS (x86_64)](https://github.com/Velaron/cs16-client/releases/download/continuous/CS16Client-macOS-x86_64.zip) - not tested

[Other platforms...](https://github.com/Velaron/cs16-client/releases/tag/continuous)

## Installation
To run CS16Client you need the [latest developer build of Xash3D FWGS](https://github.com/FWGS/xash3d-fwgs/releases/tag/continuous).
You have to own the [game on Steam](https://store.steampowered.com/app/10/CounterStrike//) and copy `valve` and `cstrike` folders into your Xash3D FWGS directory.
After that, just install the APK and run.

## Configuration (CVars)
| CVar                       | Default            | Min  | Max  | Description                                                                                 |
|----------------------------|--------------------|------|------|---------------------------------------------------------------------------------------------|
| hud_color                  | "255 160 0"        | -    | -    | HUD color in RGB.                                                                           |
| cl_quakeguns               | 0                  | 0    | 1    | Draw centered weapons.                                                                      |
| cl_weaponlag               | 0                  | 0.0  | -    | Enable weapon lag/sway.                                                                     |
| xhair_additive             | 0                  | 0    | 1    | Makes the crosshair additive.                                                               |
| xhair_color                | "0 255 0 255"      | -    | -    | Crosshair's color (RGBA).                                                                   |
| xhair_dot                  | 0                  | 0    | 1    | Enables crosshair dot.                                                                      |
| xhair_dynamic_move         | 1                  | 0    | 1    | Jumping, crouching and moving will affect the dynamic crosshair (like cl_dynamiccrosshair). |
| xhair_dynamic_scale        | 0                  | 0    | -    | Scale of the dynamic crosshair movement.                                                    |
| xhair_gap_useweaponvalue   | 0                  | 0    | 1    | Makes the crosshair gap scale depend on the active weapon.                                  |
| xhair_enable               | 0                  | 0    | 1    | Enables enhanced crosshair.                                                                 |
| xhair_gap                  | 0                  | 0    | 15   | Space between crosshair's lines.                                                            |
| xhair_pad                  | 0                  | 0    | -    | Border around crosshair.                                                                    |
| xhair_size                 | 4                  | 0    | -    | Crosshair size.                                                                             |
| xhair_t                    | 0                  | 0    | 1    | Enables T-shaped crosshair.                                                                 |
| xhair_thick                | 0                  | 0    | -    | Crosshair thickness.                                                                        |
| cl_smoothfov               | 0.25               | 0    | -    | Zoom transition time in seconds (smoothstep easing, 0 = disabled).                          |
| cl_scoreboard_anim         | 1                  | 0    | 1    | Smoothly animates the scoreboard slide-in.                                                  |
| cl_chat_smooth             | 1                  | 0    | 1    | Smoothly animates chat message appearance/scrolling.                                        |
| hud_saytext_anim_time      | 0.20               | 0.01 | 1.0  | Chat line fade/slide duration (seconds, requires cl_chat_smooth 1).                         |
| hud_saytext_x_offset       | 12                 | -    | -    | Horizontal offset of chat lines in pixels.                                                  |
| hud_saytext_y_offset       | -15                | -    | -    | Vertical offset of chat lines in pixels.                                                    |
| cl_killfeed_smooth         | 1                  | 0    | 1    | Smoothly animates killfeed entries (fade + slide).                                          |
| hud_deathnotice_time       | 6                  | -    | -    | How long a killfeed entry is shown (seconds).                                               |
| hud_deathnotice_x          | 4                  | 0    | -    | Right padding of killfeed rows in pixels.                                                   |
| hud_deathnotice_y          | 0                  | -    | -    | Vertical offset of the killfeed stack.                                                      |
| hud_deathnotice_row_gap    | 24                 | 12   | 48   | Vertical spacing between killfeed rows.                                                     |
| hud_deathnotice_bg_alpha   | 96                 | 0    | 255  | Killfeed background panel alpha.                                                            |
| hud_deathnotice_bg_softness| 100                | 0    | 100  | Killfeed background corner radius / softness (scoreboard-style rounded corners).           |
| hud_deathnotice_anim_time  | 0.18               | 0.01 | 1.0  | Killfeed fade/slide duration (seconds, requires cl_killfeed_smooth 1).                      |
| cl_killsound               | 0                  | 0    | -    | Play a sound when the local player gets a kill.                                             |
| cl_killsound_path          | "buttons/bell1.wav" | -    | -    | Sound to play on kill (cl_killsound 1).                                                     |
| cl_viewmodel_sway          | 0.5                | 0    | -    | Viewmodel sway amount (recoil/aim drift).                                                   |
| cl_viewmodel_movebob       | 0.3                | 0    | -    | Viewmodel movement bob amount (weapon wag while moving).                                    |
| cl_spec_ui_color           | "255 140 0"        | -    | -    | Spectator HUD UI color (RGB).                                                               |
| cl_spec_bar_alpha          | 153                | 0    | 255  | Spectator HUD bar alpha (0-255).                                                            |
| cl_scrollview_glide        | 1                  | 0    | 1    | Smooth eased glide of scroll views (0 to disable, 1 to enable).                             |

## Building
Clone the source code:
```shell
git clone https://github.com/Velaron/cs16-client --recursive
```

### Using CMakePresets.json
```shell
cmake --preset <preset-name>
cmake --build build
cmake --install build --prefix <path-to-your-installation>
```

### Windows
```shell
cmake -A Win32 -S . -B build
cmake --build build --config Release
cmake --install build --prefix <path-to-your-installation>
```
### Linux and macOS
```shell
cmake -S . -B build
cmake --build build --config Release
cmake --install build --prefix <path-to-your-installation>
```
### Android
```shell
cd android
./gradlew assembleRelease
```
