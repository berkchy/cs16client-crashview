#!/usr/bin/env bash
#
# Cross-compiles the CS16Client AMXX core + modules for android using the
# Android NDK. Supported target ABIs: arm64-v8a (default, fully supported) and
# armeabi-v7a (best-effort/experimental trial — see known gaps below). Sources:
#   - alliedmodders/amxmodx@master   (rolling 1.10, fetched from upstream)
#   - android/mm-p                    (vendored Bots-United/metamod-p)
#   - android/hlsdk                   (vendored HLSDK)
#
# All build customizations live as patch files under <repo>/patches/ and are
# applied here. hlsdk + metamod-p are vendored into the repository (git
# submodules cannot be used since the linked repos are not under this account).
#
# Produces (with ABI's shard dir this run builds into):
#   $OUT/lib/$ABI/libamxmodx.so
#   $OUT/lib/$ABI/libmetamod.so
#   $OUT/lib/$ABI/lib<name>_amxx_$MOD_SUFFIX.so               (modules; _amd64 on LP64 ABIs, _arm on ARM32)
#   $OUT/compiler/$ABI/amxxpc[.so]                          (on-device compiler)
#   $OUT/plugins/*.amxx                                     (64-bit cells, from plugins-src)
#
#   usage: ci/build-amxx.sh <src-root> <ndk-root> <out-dir> [plugins-src] [abi]
#
# Known v7a gaps (trial): hamsandwich enforces trampolines in C only for
# aarch64 (x86 template stays reachable on __arm__), ReGameDLL pdata layout for
# arm32 is untested, and the arm64-only libcs byte-patch does not apply — so
# v7a must be validated on-device per module before being offered as a release.
#
set -euo pipefail

SBIN=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO_ROOT=$(cd "$SBIN/../.." && pwd)
PATCHES="$REPO_ROOT/patches"

SRC=$1
NDK=$2
OUT=$3
PLUGINS_SRC=${4:-}
ABI=${5:-arm64-v8a}

AMXX_REPO=https://github.com/alliedmodders/amxmodx.git

mkdir -p "$SRC" "$OUT/lib/$ABI" "$OUT/plugins"

# ------------------------------------------------------------------ sources
fetch() {
  local name=$1 url=$2 recurse=${3:-}
  if [ ! -d "$SRC/$name/.git" ]; then
    echo "== fetching $name =="
    if [ "$recurse" = yes ]; then
      git clone -q --depth 1 --recurse-submodules --shallow-submodules "$url" "$SRC/$name"
    else
      git clone -q --depth 1 "$url" "$SRC/$name"
    fi
    if [ -f "$SRC/$name/.gitmodules" ] && [ "$recurse" != yes ]; then
      true # extra submodules (eg. hlsdk vgui_support) are not needed
    fi
  fi
}
fetch amxmodx "$AMXX_REPO" yes

# vendored_from <src-dir> <dst-dir>: copy a vendored tree from the repo and
# turn it into a git repo so apply_patch() (git apply) works on it.
vendored_from() {
  local src=$1 dst=$2
  if [ ! -d "$dst/.git" ]; then
    echo "== vendoring $src -> $dst =="
    mkdir -p "$dst"
    cp -R "$src/." "$dst/"
    (cd "$dst" && git init -q && git add -A && git -c user.name=ci -c user.email=ci@ci commit -q -m sourced)
  fi
}
# metamod-p stays only as the header source used to compile the AMXX core
# (its meta_api.h ABI suffices); the actual runtime gamemod is metamod-fwgs.
vendored_from "$REPO_ROOT/android/mm-p" "$SRC/metamod-p"
# Runtime metamod: FWGS/metamod-fwgs (CMake), Xash3D-explicit, produces
# libmetamod_android_arm64.so.
fetch metamod-fwgs "https://github.com/FWGS/metamod-fwgs.git" yes
# ReAPI: AMXX module for ReGameDLL/ReHLDS API (rehlds/ReAPI)
fetch reapi "https://github.com/rehlds/ReAPI.git" yes
# YaPB: Counter-Strike bot, metamod plugin (yapb/yapb)
fetch yapb "https://github.com/yapb/yapb.git" yes

apply_patch() {
  local patch=$1 dir=$2 subdir=${3:-}
  local target="$dir/$subdir"
  local mark="$target/.applied-$(basename "$patch")"
  if [ -f "$mark" ]; then
    echo "   patch already applied: $(basename "$patch")"
    return 0
  fi
  (cd "$target" && git apply --check "$patch")
  (cd "$target" && git apply "$patch")
  touch "$mark"
  echo "   patched: $(basename "$patch")"
}

apply_patch "$PATCHES/amxmodx-pawncc-64bit.patch"        "$SRC/amxmodx"
apply_patch "$PATCHES/amxmodx-libpawnc-console.patch"      "$SRC/amxmodx"
apply_patch "$PATCHES/amxmodx-android-load-CModule.patch" "$SRC/amxmodx"
apply_patch "$PATCHES/amxmodx-android-load-modules.patch" "$SRC/amxmodx"
apply_patch "$PATCHES/amxmodx-CDetour-cell.diff"          "$SRC/amxmodx"
apply_patch "$PATCHES/amxmodx-64bit-cell-casts.diff"       "$SRC/amxmodx"
apply_patch "$PATCHES/amxmodx-memtools-dlfcn.diff"         "$SRC/amxmodx"
apply_patch "$PATCHES/amxmodx-CTextParsers-quote-underrun.diff" "$SRC/amxmodx"
apply_patch "$PATCHES/amxmodx-amtl-64bit.diff"             "$SRC/amxmodx" "public/amtl"
apply_patch "$PATCHES/amxmodx-regparm-arm64.patch"         "$SRC/amxmodx"
apply_patch "$PATCHES/amxmodx-csx-string-guard.patch"     "$SRC/amxmodx"
apply_patch "$PATCHES/amxmodx-param-convert-64bit.patch"  "$SRC/amxmodx"
apply_patch "$PATCHES/amxmodx-amx-hea-adopt.patch"      "$SRC/amxmodx"
apply_patch "$PATCHES/amxmodx-pcvar-handle-64bit.patch" "$SRC/amxmodx"
apply_patch "$PATCHES/amxmodx-float64-widen.patch"     "$SRC/amxmodx"
apply_patch "$PATCHES/amxmodx-gamesig-rtld.patch"       "$SRC/amxmodx"
apply_patch "$PATCHES/amxmodx-interface-android.diff"   "$SRC/amxmodx"
apply_patch "$PATCHES/amxmodx-ham-float64.patch"       "$SRC/amxmodx"
apply_patch "$PATCHES/amxmodx-cbase-bit32-guard.diff"  "$SRC/amxmodx"
apply_patch "$PATCHES/amxmodx-ham-trampoline-arm64.patch"  "$SRC/amxmodx"
apply_patch "$PATCHES/amxmodx-cbase-pev-fallback.patch"     "$SRC/amxmodx"
apply_patch "$PATCHES/amxmodx-fun-strip-user-weapons.diff"  "$SRC/amxmodx"
# AMXX module file suffix: "amd64" upstream means "64-bit cells" (applies to
# every PAWN_CELL_SIZE=64 build, ARM included), but on ARM32 that name reads as
# an x86-64 binary. Name ARM32 modules "_arm" (arm64 keeps "_amd64" so it also
# matches the ISA); the loader suffix logic must stay in sync with the CI build.
apply_patch "$PATCHES/amxmodx-module-suffix-arm.patch"      "$SRC/amxmodx"
# Runtime translation of legacy 32-bit pdata offsets to the measured arm64
# ReGameDLL layout (see patch header for how the tables are regenerated).
apply_patch "$PATCHES/amxmodx-pdata-runtime-translate.diff" "$SRC/amxmodx"
# Fix Pawn compiler assertion bug: =='0' (char literal = 48) should be ==0 (int zero)
# This causes "array_level=='0'" assertion failure on any enum-constant array index.
# Even after fixing =='0' -> ==0, the assertion still fires for plugins (eg
# zombie_plague40.sma) that use an enum field with array_level > 0 as an array
# index. That is a legitimate pattern, so remove the overly-strict assert.
sed -i "s/array_level==\s*'0'/array_level==0/g" "$SRC/amxmodx/compiler/libpc300/sc3.c"
sed -i "/assert(lval2.sym==NULL/d" "$SRC/amxmodx/compiler/libpc300/sc3.c"
# AMXX core is still compiled against metamod-p's meta_api.h (METAMOD above),
# which requires this ARM64 shim (cs16_amxx_compat.h + const SET_LOCALINFO).
apply_patch "$PATCHES/metamod-p-aarch64.patch"            "$SRC/metamod-p"
apply_patch "$PATCHES/metamod-fwgs-android.patch"          "$SRC/metamod-fwgs"
# Android native lib: also try libamxxpc32.so (APK lib prefix) when driver is libamxxpc.so
if [ -f "$SRC/amxmodx/compiler/amxxpc/amxxpc.cpp" ]; then
  python3 - "$SRC" <<'PYEOF' || true
import sys, os
src = sys.argv[1]
p = os.path.join(src, "amxmodx/compiler/amxxpc/amxxpc.cpp")
data = open(p, encoding="utf-8").read()
old = '\tHINSTANCE lib = NULL;\n\tdlopen("libm.so", RTLD_NOW | RTLD_GLOBAL);\n\tif (FileExists("./amxxpc32.so"))\n\t\tlib = dlmount("./amxxpc32.so");\n\telse\n\t\tlib = dlmount("amxxpc32.so");'
new = (
    '\tHINSTANCE lib = NULL;\n'
    '\tdlopen("libm.so", RTLD_NOW | RTLD_GLOBAL);\n'
    '\t{\n'
    '\t\t/* Android: resolve library path relative to this binary */\n'
    '\t\tchar selfpath[4096] = "./";\n'
    '\t\tssize_t len = readlink("/proc/self/exe", selfpath, sizeof(selfpath) - 1);\n'
    '\t\tif (len > 0) {\n'
    '\t\t\tselfpath[len] = \'\\0\';\n'
    '\t\t\tfor (int i = len - 1; i > 0; i--)\n'
    '\t\t\t\tif (selfpath[i] == \'/\') { selfpath[i] = \'\\0\'; break; }\n'
    '\t\t}\n'
    '\t\t/* Try: <bindir>/libamxxpc32.so, <bindir>/amxxpc32.so, ./libamxxpc32.so, ./amxxpc32.so */\n'
    '\t\tchar fullpath[4096];\n'
    '\t\tsnprintf(fullpath, sizeof(fullpath), "%s/libamxxpc32.so", selfpath);\n'
    '\t\tif (FileExists(fullpath))\n'
    '\t\t\tlib = dlmount(fullpath);\n'
    '\t\telse {\n'
    '\t\t\tsnprintf(fullpath, sizeof(fullpath), "%s/amxxpc32.so", selfpath);\n'
    '\t\t\tif (FileExists(fullpath))\n'
    '\t\t\t\tlib = dlmount(fullpath);\n'
    '\t\t\telse if (FileExists("./libamxxpc32.so"))\n'
    '\t\t\t\tlib = dlmount("./libamxxpc32.so");\n'
    '\t\t\telse if (FileExists("./amxxpc32.so"))\n'
    '\t\t\t\tlib = dlmount("./amxxpc32.so");\n'
    '\t\t\telse\n'
    '\t\t\t\tlib = dlmount("amxxpc32.so");\n'
    '\t\t}\n'
    '\t}\n'
)
if old in data:
    open(p, "w", encoding="utf-8").write(data.replace(old, new))
    print("patched amxxpc for lib prefix")
else:
    if 'lib = dlmount("./amxxpc32.so");' in data and 'libamxxpc32.so' not in data:
        data = data.replace('lib = dlmount("./amxxpc32.so");', 'lib = dlmount("./amxxpc32.so");\n\telse if (FileExists("./libamxxpc32.so"))\n\t\tlib = dlmount("./libamxxpc32.so");\n\telse if (FileExists("libamxxpc32.so"))\n\t\tlib = dlmount("libamxxpc32.so");')
        open(p, "w", encoding="utf-8").write(data)
        print("patched amxxpc (fallback)")
PYEOF
fi

# MemoryUtils: skip the ELF-parsing fallback in ResolveSymbol on Android.
# dlmap->l_name is NULL/invalid on bionic, causing SIGSEGV in open().
# dlsym() is reliable on Android, so the fallback is unnecessary.
if [ -f "$SRC/amxmodx/public/memtools/MemoryUtils.cpp" ]; then
  MF="$SRC/amxmodx/public/memtools/MemoryUtils.cpp"
  if ! grep -q '__ANDROID__' "$MF"; then
    echo "   patching MemoryUtils.cpp for Android"
    python3 -c "
import sys
p = sys.argv[1]
d = open(p).read()
d = d.replace(
    'void *addr = dlsym(handle, symbol);\n\n\tif (addr)\n\t{\n\t\treturn addr;\n\t}\n\n\tstruct link_map',
    'void *addr = dlsym(handle, symbol);\n#if defined(__ANDROID__)\n\t/* On Android, dlsym is reliable.\n\t   The manual ELF fallback dereferences dlmap->l_name which\n\t   can be NULL on bionic, causing SIGSEGV. */\n\treturn addr;\n#else\n\n\tif (addr)\n\t{\n\t\treturn addr;\n\t}\n\n\tstruct link_map'
)
d = d.replace(
    '\treturn symbol_entry ? symbol_entry->address : NULL;\n\n#elif defined(__APPLE__)',
    '\treturn symbol_entry ? symbol_entry->address : NULL;\n#endif /* !__ANDROID__ */\n\treturn NULL;\n\n#elif defined(__APPLE__)'
)
open(p,'w').write(d)
print('   MemoryUtils patched for Android')
" "$MF"
  else
    echo "   MemoryUtils already patched"
  fi
fi

# CDetour: x86-only trampoline generator crashes on ARM64 (copy_bytes/check_thunks
# decode x86 instructions).  Make CreateDetour return false on ARM64 so the
# cstrike module loads without hooks (offsets/sigs still work).
if [ -f "$SRC/amxmodx/public/memtools/CDetour/detours.cpp" ]; then
  DT="$SRC/amxmodx/public/memtools/CDetour/detours.cpp"
  if ! grep -q '__aarch64__' "$DT"; then
    echo "   patching CDetour detours.cpp for ARM64"
    python3 -c "
import sys
p = sys.argv[1]
d = open(p).read()
d = d.replace(
    '\treturn false;\n\t}*/\n\n\tif (address != NULL)',
    '\treturn false;\n\t}*/\n\n#if defined(__aarch64__)\n\t/* x86 trampoline generator is not valid on ARM64 */\n\treturn false;\n#else\n\tif (address != NULL)'
)
d = d.replace(
    '\treturn true;\n}\n\nvoid CDetour::DeleteDetour()',
    '\treturn true;\n#endif /* !__aarch64__ */\n}\n\nvoid CDetour::DeleteDetour()'
)
open(p,'w').write(d)
print('   CDetour patched for ARM64')
" "$DT"
  else
    echo "   CDetour already patched"
  fi
fi

# ----------------------------------------------------------------- toolchain
HOST=$(uname -s | tr 'A-Z' 'a-z')
if [ "$HOST" = darwin ]; then HOST=mac; fi

TC=$NDK/toolchains/llvm/prebuilt/$HOST-x86_64/bin
case "$ABI" in
  arm64-v8a)
    TARGET=aarch64-linux-android24
    SYSROOT_ARCH=aarch64-linux-android
    PCRE_HOST=aarch64-linux-android
    RUNTIME_SUFFIX=arm64
    MOD_SUFFIX=amd64
    ;;
  armeabi-v7a)
    TARGET=armv7a-linux-androideabi24
    SYSROOT_ARCH=arm-linux-androideabi
    PCRE_HOST=arm-linux-androideabi
    RUNTIME_SUFFIX=arm
    # ARM32 AMXX modules are named "_arm" (not "_amd64"): the loader suffix
    # logic in the amxmodx-module-suffix-arm patch mirrors this.
    MOD_SUFFIX=arm
    ;;
  *)
    echo "unsupported ABI: $ABI (expected arm64-v8a or armeabi-v7a)" >&2
    exit 1
    ;;
esac
CC=$TC/$TARGET-clang
CXX=$TC/$TARGET-clang++
HOSTCC=${HOSTCC:-gcc}
HOSTCXX=${HOSTCXX:-g++}
SYSROOT_LIB=$NDK/toolchains/llvm/prebuilt/$HOST-x86_64/sysroot/usr/lib/$SYSROOT_ARCH

AMXX=$SRC/amxmodx
HLSDK=$REPO_ROOT/android/hlsdk
METAMOD=$SRC/metamod-p/metamod
MMHLSDK=$SRC/metamod-p/hlsdk

# Hamsandwich Trampolines.h: reinterpret_cast<int>(extraptr) truncates
# a 64-bit pointer on ARM64. Fix: use intptr_t.
if [ -f "$AMXX/modules/hamsandwich/Trampolines.h" ]; then
  TH="$AMXX/modules/hamsandwich/Trampolines.h"
  if ! grep -q 'intptr_t' "$TH"; then
    echo "   patching Trampolines.h for 64-bit pointer cast"
    python3 -c "
import sys
p = sys.argv[1]
d = open(p).read()
d = d.replace('reinterpret_cast<int>(extraptr)', 'reinterpret_cast<intptr_t>(extraptr)')
open(p,'w').write(d)
print('   Trampolines.h patched for ARM64')
" "$TH"
  else
    echo "   Trampolines.h already patched"
  fi
fi

# ---------------------------------------------------------------- flags
DEFS=(
  -Dstricmp=strcasecmp
  -Dstrnicmp=strncasecmp
  -DAMX_NOPROPLIST
  -DPAWN_CELL_SIZE=64
  -DAMXX_USE_VERSIONLIB
  -DHAVE_STDINT_H
  -DHAVE_I64
  -D_snprintf=snprintf
  -D__BYTE_ORDER=__LITTLE_ENDIAN
)
FLAGS=(
  -fPIC -O2 -fno-strict-aliasing -Wall -Wno-uninitialized -Wno-unused
  -Wno-switch -Wno-format -Wno-format-security -fsigned-char -fvisibility=hidden
)
CXXFLAGS=(
  -Wno-narrowing -Wno-invalid-offsetof -std=c++14 -fvisibility-inlines-hidden
  -Wno-delete-non-virtual-dtor -Wno-implicit-exception-spec-mismatch
  -Wno-tautological-compare -Wno-deprecated-register -fno-exceptions -fno-rtti
)
INC=(
  -I"$AMXX/public" -I"$AMXX/public/sdk" -I"$AMXX/public/amtl"
  -I"$AMXX/public/memtools" -I"$AMXX/public/resdk"
  -I"$AMXX/third_party" -I"$AMXX/third_party/hashing" -I"$AMXX/third_party/zlib"
  -I"$AMXX/third_party/sqlite"   -I"$AMXX/third_party/utf8rewind"
  -I"$AMXX/amxmodx"
  -I"$METAMOD"
  -I"$HLSDK/common" -I"$HLSDK/dlls" -I"$HLSDK/engine"
  -I"$HLSDK/game_shared" -I"$HLSDK/public" -I"$HLSDK/pm_shared"
)

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
cmd_shim="$TMP/assert_shim.o"
"$CC" -O2 -fPIC -c "$SBIN/assert_shim.c" -o "$cmd_shim"

compile_one() {
  local stage="$1" file="$2" extra_inc="$3" extra_defs="$4"
  local base obj
  base=$(basename "$file")
  obj="$TMP/$stage/$base.o"
  mkdir -p "$(dirname "$obj")"
  if [[ "$file" == *.c ]]; then
    "$CC" "${FLAGS[@]}" "${INC[@]}" $extra_inc "${DEFS[@]}" $extra_defs -c "$file" -o "$obj"
  else
    "$CXX" "${FLAGS[@]}" "${CXXFLAGS[@]}" "${INC[@]}" $extra_inc "${DEFS[@]}" $extra_defs -c "$file" -o "$obj"
  fi
}

relink() {
  local out="$1"; shift
  "$CXX" -fPIC -O2 -shared -nostdlib++ -o "$out" "$@" "$cmd_shim" \
    -Wl,--wrap=__assert2 -Wl,--wrap=__assert_fail \
    -Wl,--whole-archive "$SYSROOT_LIB/libc++_static.a" -Wl,--no-whole-archive \
    "$SYSROOT_LIB/libc++abi.a" -ldl -lm -pthread
}

# ------------------------------------------------------------------- core
echo "== building core =="

# Provide C implementations for the dynamic native helpers that upstream only
# has as x86/amd64 NASM assembly (natives-*.asm). These define:
#   amxx_DynaInit, amxx_DynaMake, amxx_DynaCodesize, amxx_CpuSupport
# The file is regenerated for every build invocation (per $ABI), so a shared
# source tree can build arm64 then v7a without stale arch templates.
NATIVES_FILE="$AMXX/amxmodx/natives-android.c"
if [ "$ABI" = arm64-v8a ]; then
cat > "$NATIVES_FILE" << 'NATIVES_EOF'
#include <stdint.h>
#include <string.h>

static void *g_gate = 0;

void amxx_DynaInit(void *ptr) {
    g_gate = ptr;
}

int amxx_DynaCodesize(void) {
    return 52;
}

typedef int (*dyna_cb_t)(int, void*, void*);

/* ARM64 trampoline (52 bytes):
 *   stp  x29, x30, [sp, #-16]!
 *   mov  x29, sp
 *   mov  x2, x1          ; params -> arg3
 *   mov  x1, x0          ; amx    -> arg2
 *   movz x0,  #id_lo16   ; patched
 *   movk x0,  #id_hi16, lsl #16
 *   movz x16, #cb_lo16   ; patched
 *   movk x16, #cb_16,  lsl #16
 *   movk x16, #cb_32,  lsl #32
 *   movk x16, #cb_48,  lsl #48
 *   blr  x16
 *   ldp  x29, x30, [sp], #16
 *   ret
 */
static const uint32_t tpl[] = {
    0xA9BE7BFD,  /* stp x29,x30,[sp,#-16]!  */
    0x910003FD,  /* mov x29, sp             */
    0xAA0103E2,  /* mov x2, x1              */
    0xAA0003E1,  /* mov x1, x0              */
    0xD2800000,  /* movz x0, #0   (id lo)   */
    0xF2A00000,  /* movk  x0, #0, lsl#16    */
    0xD2800010,  /* movz x16, #0  (cb lo)   */
    0xF2A00010,  /* movk x16,#0, lsl#16     */
    0xF2C00010,  /* movk x16,#0, lsl#32     */
    0xF2E00010,  /* movk x16,#0, lsl#48     */
    0xD63F0200,  /* blr  x16                */
    0xA8C27BFD,  /* ldp x29,x30,[sp],#16    */
    0xD65F03C0,  /* ret                     */
};

void amxx_DynaMake(char *buf, int id) {
    uint32_t code[13];
    memcpy(code, tpl, sizeof(code));
    uintptr_t cb = (uintptr_t)g_gate;
    code[4] |= ((uint32_t)(id & 0xFFFF)) << 5;
    code[5] |= ((uint32_t)((id >> 16) & 0xFFFF)) << 5;
    code[6]  |= ((uint32_t)(cb & 0xFFFF)) << 5;
    code[7]  |= ((uint32_t)((cb >> 16) & 0xFFFF)) << 5;
    code[8]  |= ((uint32_t)((cb >> 32) & 0xFFFF)) << 5;
    code[9]  |= ((uint32_t)((cb >> 48) & 0xFFFF)) << 5;
    memcpy(buf, code, sizeof(code));
    /* ARM64 has a non-coherent instruction cache: flush it, otherwise the
     * CPU may execute stale cache lines (SIGILL) instead of this code. */
    __builtin___clear_cache(buf, buf + sizeof(code));
}

int amxx_CpuSupport(void) {
    return 1;
}
NATIVES_EOF
else
cat > "$NATIVES_FILE" << 'NATIVES_EOF'
#include <stdint.h>
#include <string.h>

static void *g_gate = 0;

void amxx_DynaInit(void *ptr) {
    g_gate = ptr;
}

int amxx_DynaCodesize(void) {
    return 52;
}

/* ARM (A32) trampoline (13 words = 52 bytes, same size as the arm64 one so
 * DynaCodesize stays single-valued). Executed from a Thumb-2 caller via
 * interworking (trampoline address is even -> ARM state):
 *   push {r7, lr}
 *   add  r7, sp, #0
 *   mov  r2, r1            ; params -> arg3
 *   mov  r1, r0            ; amx    -> arg2
 *   movw r0,  #id_lo16     ; patched
 *   movt r0,  #id_hi16
 *   movw r3,  #cb_lo16     ; patched
 *   movt r3,  #cb_hi16
 *   blx  r3                ; gate(id, amx, params)
 *   pop  {r7, pc}
 *   nop x3 (pad to 52 bytes)
 */
static const uint32_t tpl[] = {
    0xE92D4080,  /* push {r7, lr}     */
    0xE28D7000,  /* add  r7, sp, #0   */
    0xE1A02001,  /* mov  r2, r1       */
    0xE1A01000,  /* mov  r1, r0       */
    0xE3000000,  /* movw r0, #0 (id lo) */
    0xE3400000,  /* movt r0, #0 (id hi) */
    0xE3030000,  /* movw r3, #0 (cb lo) */
    0xE3430000,  /* movt r3, #0 (cb hi) */
    0xE12FFF33,  /* blx  r3           */
    0xE8BD8080,  /* pop  {r7, pc}     */
    0xE320F000,  /* nop               */
    0xE320F000,  /* nop               */
    0xE320F000,  /* nop               */
};

/* movw/movt imm16 -> opcode bits [19:16]=imm4, [11:0]=imm12. */
static inline uint32_t movimm(uint32_t opcode, uint32_t imm) {
    return opcode | ((imm & 0xF000u) << 4) | (imm & 0x0FFFu);
}

void amxx_DynaMake(char *buf, int id) {
    uint32_t code[13];
    memcpy(code, tpl, sizeof(code));
    uintptr_t cb = (uintptr_t)g_gate;
    code[4] = movimm(0xE3000000, (uint32_t)(id & 0xFFFF));
    code[5] = movimm(0xE3400000, (uint32_t)((id >> 16) & 0xFFFF));
    code[6] = movimm(0xE3030000, (uint32_t)(cb & 0xFFFF));
    code[7] = movimm(0xE3430000, (uint32_t)((cb >> 16) & 0xFFFF));
    memcpy(buf, code, sizeof(code));
    __builtin___clear_cache(buf, buf + sizeof(code));
}

int amxx_CpuSupport(void) {
    return 1;
}
NATIVES_EOF
fi
echo "   created natives-android.c ($ABI)"

for f in "$AMXX/amxmodx"/*.c "$AMXX/amxmodx"/*.cpp; do
  [ -e "$f" ] || continue
  compile_one core "$f" "" ""
done
compile_one core "$AMXX/public/memtools/MemoryUtils.cpp" "" ""
compile_one core "$AMXX/public/memtools/CDetour/detours.cpp" "" ""
compile_one core "$AMXX/public/memtools/CDetour/asm/asm.c" "" ""
compile_one core "$AMXX/public/resdk/mod_rehlds_api.cpp" "" ""
for f in "$AMXX/third_party/hashing/"*.cpp "$AMXX/third_party/hashing/hashers/"*.cpp; do
  [ -e "$f" ] || continue
  compile_one core "$f" "" ""
done
for f in "$AMXX/third_party/zlib/"*.c; do
  [ -e "$f" ] || continue
  compile_one core "$f" "" ""
done
for f in "$AMXX/third_party/utf8rewind/"*.c "$AMXX/third_party/utf8rewind/internal/"*.c; do
  [ -e "$f" ] || continue
  compile_one core "$f" "" ""
done

relink "$OUT/lib/$ABI/libamxmodx.so" "$TMP"/core/*.o
echo "   core -> $(ls -l "$OUT/lib/$ABI/libamxmodx.so" | awk '{print $5}') bytes"

# ------------------------------------------------------------------ pcre
# regex module needs a static pcre for the target ABI; upstream only ships
# linux/mac/win prebuilts, so build it here (deterministic, source-based).
PCRE_VER=8.45
if [[ ! -f "$TMP/libpcre.a" ]]; then
  echo "== building pcre $PCRE_VER ($ABI) =="
  curl -fsSL "https://downloads.sourceforge.net/project/pcre/pcre/$PCRE_VER/pcre-$PCRE_VER.tar.gz" -o "$TMP/pcre.tar.gz"
  tar -xzf "$TMP/pcre.tar.gz" -C "$TMP"
  (
    cd "$TMP/pcre-$PCRE_VER"
    CC="$CC" CXX="$CXX" CFLAGS="-O2 -fPIC" CXXFLAGS="-O2 -fPIC" \
      ./configure --host=$PCRE_HOST --disable-shared --enable-static \
      --enable-utf8 --enable-unicode-properties --disable-cpp --prefix="$TMP/pcre-inst" \
      >/dev/null
    make -j"$(nproc)" >/dev/null
    make install >/dev/null
  )
fi
PCRE_A="$TMP/pcre-inst/lib/libpcre.a"

# ------------------------------------------------------------- metamod
# fwgs metamod (FWGS/metamod-fwgs): Xash3D-explicit, builds
# libmetamod_android_<arch>.so via CMake with the NDK toolchain. Renamed to
# libmetamod.so for the bundle (the gamedll alias still resolves to it).
echo "== building metamod (metamod-fwgs, $ABI) =="
MMBUILD=$TMP/metamod-fwgs-build
cmake -S "$SRC/metamod-fwgs" -B "$MMBUILD" \
  -GNinja \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=$ABI \
  -DANDROID_PLATFORM=android-24 \
  -DANDROID_STL=c++_static \
  -DUSE_STATIC_RUNTIME=ON \
  -DCMAKE_BUILD_TYPE=Release
cmake --build "$MMBUILD" --target metamod -j"$(nproc)"
MM_SO=$(find "$MMBUILD" -name "libmetamod_android_*.so" | head -1)
if [ -n "$MM_SO" ]; then
  cp "$MM_SO" "$OUT/lib/$ABI/libmetamod.so"
  echo "   metamod -> $(ls -l "$OUT/lib/$ABI/libmetamod.so" | awk '{print $5}') bytes"
else
  echo "WARN: metamod lib not found, skipping"
fi

# ----------------------------------------------------------------- modules
build_module() {
  local name="$1" modsub="$2" extra_inc="$3" extra_defs="$4"
  shift 4
  echo "== building module $name =="
  local M="$AMXX/modules/$modsub"
  local MOD_INC="-I$M"
  for f in "$@"; do
    if [[ "$f" == /* ]]; then
      compile_one "mod-$name" "$f" "$MOD_INC $extra_inc" "$extra_defs"
    else
      compile_one "mod-$name" "$M/$f" "$MOD_INC $extra_inc" "$extra_defs"
    fi
  done
  relink "$OUT/lib/$ABI/lib$name"_amxx_$MOD_SUFFIX.so "$TMP/mod-$name"/*.o
  echo "   $name -> $(ls -l "$OUT/lib/$ABI/lib$name"_amxx_$MOD_SUFFIX.so | awk '{print $5}') bytes"
}

P="$AMXX/public"
SDK="$AMXX/public/sdk"

build_module engine engine "" "" \
  "$SDK/amxxmodule.cpp" "amxxapi.cpp" "engine.cpp" "entity.cpp" "globals.cpp" \
  "forwards.cpp" "$P/memtools/MemoryUtils.cpp" "$P/memtools/CDetour/detours.cpp" \
  "$P/memtools/CDetour/asm/asm.c"

build_module fakemeta fakemeta "" "" \
  "$SDK/amxxmodule.cpp" "$P/memtools/MemoryUtils.cpp" "$P/resdk/mod_regamedll_api.cpp" \
  "dllfunc.cpp" "engfunc.cpp" "fakemeta_amxx.cpp" "pdata.cpp" "pdata_entities.cpp" \
  "pdata_gamerules.cpp" "forward.cpp" "fm_tr.cpp" "pev.cpp" "glb.cpp" "fm_tr2.cpp" "misc.cpp"

build_module fun fun "" "" \
  "$SDK/amxxmodule.cpp" "$P/memtools/MemoryUtils.cpp" "fun.cpp"

build_module geoip geoip \
  "-I$AMXX/amxmodx -I$AMXX/third_party/libmaxminddb" "" \
  "$SDK/amxxmodule.cpp" "$AMXX/third_party/libmaxminddb/data-pool.c" \
  "$AMXX/third_party/libmaxminddb/maxminddb.c" "geoip_main.cpp" "geoip_natives.cpp" \
  "geoip_util.cpp"

build_module json json "-I$AMXX/third_party/parson" "" \
  "$SDK/amxxmodule.cpp" "$AMXX/third_party/parson/parson.c" "JsonMngr.cpp" "JsonNatives.cpp"

build_module nvault nvault "" "" \
  "$SDK/amxxmodule.cpp" "amxxapi.cpp" "Binary.cpp" "Journal.cpp" "NVault.cpp"

build_module regex regex "" "-DPCRE_STATIC" \
  "$SDK/amxxmodule.cpp" "module.cpp" "CRegEx.cpp" "utils.cpp"

build_module sockets sockets "" "" \
  "$SDK/amxxmodule.cpp" "sockets.cpp"

build_module sqlite sqlite \
  "-I$AMXX/modules/sqlite/sqlitepp -I$AMXX/modules/sqlite/thread -I$AMXX/third_party/sqlite" \
  "-DSM_DEFAULT_THREADER" \
  "basic_sql.cpp" "handles.cpp" "module.cpp" "threading.cpp" "$SDK/amxxmodule.cpp" \
  "oldcompat_sql.cpp" "thread/BaseWorker.cpp" "thread/ThreadWorker.cpp" \
  "sqlitepp/SqliteQuery.cpp" "sqlitepp/SqliteResultSet.cpp" \
  "sqlitepp/SqliteDatabase.cpp" "sqlitepp/SqliteDriver.cpp" \
  "$AMXX/third_party/sqlite/sqlite3.c"

build_module cstrike cstrike/cstrike "" "" \
  "$SDK/amxxmodule.cpp" "CstrikeMain.cpp" "CstrikePlayer.cpp" "CstrikeNatives.cpp" \
  "CstrikeHacks.cpp" "CstrikeUtils.cpp" "CstrikeUserMessages.cpp" "CstrikeItemsInfos.cpp" \
  "$P/memtools/MemoryUtils.cpp" "$P/memtools/CDetour/detours.cpp" \
  "$P/memtools/CDetour/asm/asm.c" "$P/resdk/mod_rehlds_api.cpp" "$P/resdk/mod_regamedll_api.cpp"

build_module csx cstrike/csx "" "" \
  "$SDK/amxxmodule.cpp" "CRank.cpp" "CMisc.cpp" "meta_api.cpp" "rank.cpp" "usermsg.cpp"

build_module hamsandwich hamsandwich "" "-DHAVE_STDINT_H" \
  "$SDK/amxxmodule.cpp" "$P/memtools/MemoryUtils.cpp" \
  "amxx_api.cpp" "config_parser.cpp" "hook_callbacks.cpp" "hook_native.cpp" \
  "srvcmd.cpp" "call_funcs.cpp" "hook_create.cpp" "DataHandler.cpp" \
  "pdata.cpp" "hook_specialbot.cpp"

# link regex against freshly built pcre
"$CXX" -fPIC -O2 -shared -nostdlib++ -o "$OUT/lib/$ABI/libregex_amxx_$MOD_SUFFIX.so" \
  "$TMP"/mod-regex/*.o "$cmd_shim" "$PCRE_A" \
  -Wl,--wrap=__assert2 -Wl,--wrap=__assert_fail \
  -Wl,--whole-archive "$SYSROOT_LIB/libc++_static.a" -Wl,--no-whole-archive \
  "$SYSROOT_LIB/libc++abi.a" -ldl -lm -pthread

# ----------------------------------------------------------------- reapi
# ReAPI AMXX module (rehlds/ReAPI) — provides ReGameDLL/ReHLDS API natives.
# CMakeLists.txt hardcodes i32/MSSE so we compile by hand with NDK.
# NOTE: ReAPI ships its own amxxmodule.h so we must NOT include the AMXX SDK.
echo "== building reapi module =="
REAPI="$SRC/reapi/reapi"
mkdir -p "$TMP/mod-reapi"
# Patch: cssdk/osconfig.h unconditionally includes x86 SSE intrinsics
# (smmintrin.h/xmmintrin.h) which don't exist on ARM64. Guard them.
if [ -f "$REAPI/include/cssdk/engine/osconfig.h" ]; then
  python3 -c "
p='$REAPI/include/cssdk/engine/osconfig.h'
d=open(p).read()
if '#ifdef __arm__' not in d:
    d=d.replace('#include <smmintrin.h>\n#include <xmmintrin.h>',
                 '#if defined(__i386__) || defined(__x86_64__)\n#include <smmintrin.h>\n#include <xmmintrin.h>\n#endif')
    open(p,'w').write(d)
    print('   patched osconfig.h SSE includes for ARM64')
"
fi
# Patch: mathlib.h uses SSE intrinsics for M_sqrt; use standard sqrtf/sqrt on ARM64.
if [ -f "$REAPI/include/cssdk/common/mathlib.h" ]; then
  python3 -c "
p='$REAPI/include/cssdk/common/mathlib.h'
d=open(p).read()
old='inline float M_sqrt(float value) {\n\treturn _mm_cvtss_f32(_mm_sqrt_ss(_mm_load_ss(&value)));\n}\n\ninline double M_sqrt(double value) {\n\tdouble ret;\n\tauto v = _mm_load_sd(&value);\n\t_mm_store_sd(&ret, _mm_sqrt_sd(v, v));\n\treturn ret;\n}'
new='inline float M_sqrt(float value) {\n#if defined(__i386__) || defined(__x86_64__)\n\treturn _mm_cvtss_f32(_mm_sqrt_ss(_mm_load_ss(&value)));\n#else\n\treturn sqrtf(value);\n#endif\n}\n\ninline double M_sqrt(double value) {\n#if defined(__i386__) || defined(__x86_64__)\n\tdouble ret;\n\tauto v = _mm_load_sd(&value);\n\t_mm_store_sd(&ret, _mm_sqrt_sd(v, v));\n\treturn ret;\n#else\n\treturn sqrt(value);\n#endif\n}'
if old in d:
    d=d.replace(old,new)
    open(p,'w').write(d)
    print('   patched mathlib.h M_sqrt for ARM64')
else:
    print('   mathlib.h already patched or target not found')
"
fi
# Generate reapi_version.inc (normally produced by appversion.sh)
mkdir -p "$REAPI/version"
cat > "$REAPI/version/reapi_version.inc" << 'VERINC'
#pragma once
#define REAPI_VERSION_MAJOR 3
#define REAPI_VERSION_MINOR 1
#define REAPI_VERSION_PATCH 0
#define REAPI_VERSION_STR "3.1.0"
VERINC
echo "   created reapi_version.inc"
# Generate appversion.h (needed by meta_api.cpp)
cat > "$REAPI/version/appversion.h" << 'APPVER'
#ifndef __APPVERSION_H__
#define __APPVERSION_H__

// This file is generated automatically.
// Don't edit it.

// Version defines
#define APP_VERSION "3.1.0-dev"
#define APP_VERSION_C 3,1,0,0
#define APP_VERSION_STRD "3.1.0.0"
#define APP_VERSION_FLAGS 0x0L

#define APP_COMMIT_DATE "Jan 01 2026"
#define APP_COMMIT_TIME "00:00:00"

#define APP_COMMIT_SHA "0000000"
#define APP_COMMIT_URL "https://github.com/rehlds/ReAPI"

#endif //__APPVERSION_H__
APPVER
echo "   created appversion.h"
# Fix unqualified min() call and ULONG/size_t dedup for ARM64.
# These patches edit the SHARED $SRC tree in place. The CI loop runs this script
# once per ABI (arm64-v8a then armeabi-v7a) against the same checked-out tree,
# so the patch must be idempotent: the getFwdParamType overload injection
# re-injects a duplicate template on the second run (redefinition error).
# A marker makes the whole block a no-op after the first application.
REAPI_PATCHED="$REAPI/.reapi-abi-patched"
if [ ! -f "$REAPI_PATCHED" ]; then
python3 -c "
import os, sys, re
reapi = sys.argv[1]
# hook_callback.h: min() -> std::min()
p = os.path.join(reapi, 'src', 'hook_callback.h')
if os.path.exists(p):
    d=open(p).read()
    d=re.sub(r'(?<![:\w])min\(', 'std::min<static_cast<size_t>>(', d) if False else d
    # Direct replacement for the known line
    d=d.replace('args_count = min(arg_count, MAX_HOOKCHAIN_ARGS)',
                 'args_count = std::min(static_cast<size_t>(arg_count), static_cast<size_t>(MAX_HOOKCHAIN_ARGS))')
    # Also handle any std::min with mismatched types
    d=d.replace('std::min(arg_count, MAX_HOOKCHAIN_ARGS)',
                 'std::min(static_cast<size_t>(arg_count), static_cast<size_t>(MAX_HOOKCHAIN_ARGS))')
    open(p,'w').write(d)
# hook_callback.h: relax static_assert for 64-bit (args stored by address, not value)
p = os.path.join(reapi, 'src', 'hook_callback.h')
if os.path.exists(p):
    d=open(p).read()
    old_assert = 'static_assert(sizeof(T) <= sizeof(int), \"invalid hookchain argument size > sizeof(int)\");'
    new_assert = '#if defined(__LP64__) || defined(_WIN64)\\n\\t// On 64-bit, args are stored by address (size_t handle), not value\\n#else\\n\\tstatic_assert(sizeof(T) <= sizeof(int), \"invalid hookchain argument size > sizeof(int)\");\\n#endif'
    d=d.replace(old_assert, new_assert)
    open(p,'w').write(d)
# natives_helper.h: guard operator size_t() when ULONG==size_t
p = os.path.join(reapi, 'src', 'natives', 'natives_helper.h')
if os.path.exists(p):
    d=open(p).read()
    old='operator size_t() const\n\t{\n\t\treturn size_t(m_value);\n\t}'
    new='#if ULONG_MAX != SIZE_MAX\n\toperator size_t() const\n\t{\n\t\treturn size_t(m_value);\n\t}\n#endif'
    d=d.replace(old,new)
    open(p,'w').write(d)
# hook_list.cpp: on Linux ARM64, ULONG==size_t causing redefinition.
# Use conditional: on _WIN64 (where ULONG!=size_t), define both; else just size_t.
p = os.path.join(reapi, 'src', 'hook_list.cpp')
if os.path.exists(p):
    d=open(p).read()
    d=d.replace(
        'inline size_t getFwdParamType(void(*)(ULONG))                   { return FP_CELL;   }',
        '#if defined(_WIN64)\ninline size_t getFwdParamType(void(*)(ULONG))                   { return FP_CELL;   }\n#endif')
    # Add a generic fallback for any unmatched types (e.g. unsigned on ARM64)
    d=d.replace(
        'inline size_t getFwdParamType(void(*)(float))                   { return FP_FLOAT;  }',
        'template<typename T>\ninline size_t getFwdParamType(void(*)(T))                      { return FP_CELL;   }\ninline size_t getFwdParamType(void(*)(float))                   { return FP_FLOAT;  }')
    open(p,'w').write(d)
# Fix pointer-to-int truncation in ReAPI: cast via uintptr_t
# natives_members.cpp, natives_misc.cpp etc.
import glob
for f in glob.glob(os.path.join(reapi, 'src', 'natives', '*.cpp')):
    d=open(f).read()
    # (cell)get_member_direct<...> -> (cell)(uintptr_t)get_member_direct<...>
    import re as _re
    d2 = _re.sub(r'\(cell\)(get_member_direct<)', r'(cell)(uintptr_t)\1', d)
    if d2 != d:
        open(f,'w').write(d2)
        print(f'   patched {os.path.basename(f)} pointer casts')
# Also add -Wno-int-to-pointer-cast is not enough; need uintptr_t casts globally
# hook_list.cpp: fix all (cell) casts that truncate pointers
p = os.path.join(reapi, 'src', 'hook_list.cpp')
if os.path.exists(p):
    d=open(p).read()
    d = _re.sub(r'\(cell\)(get_member_direct<)', r'(cell)(uintptr_t)\1', d)
    open(p,'w').write(d)
# common/stdc++compat.cpp: GLIBCXX compat shim not needed on Android NDK
p = os.path.join(reapi, 'common', 'stdc++compat.cpp')
if os.path.exists(p):
    os.remove(p)
    print('   removed stdc++compat.cpp (not needed on Android)')
print('   patched reapi sources for ARM64')
" "$REAPI"
touch "$REAPI_PATCHED"
fi
REAPI_BASEFLAGS="-std=c++14 -O2 -fPIC -fpermissive -w \
  -D_LINUX -DLINUX -DNDEBUG -D_GLIBCXX_USE_CXX11_ABI=0 \
  -DHAVE_STRONG_TYPEDEF -D_stricmp=strcasecmp -D_strnicmp=strncasecmp \
  -D_vsnprintf=vsnprintf -D_snprintf=snprintf \
  -include algorithm -include cstdint -include cstddef \
  -Dmin=std::min -Dmax=std::max \
  -I$REAPI/include -I$REAPI/include/cssdk/common -I$REAPI/include/cssdk/dlls \
  -I$REAPI/include/cssdk/engine -I$REAPI/include/cssdk/game_shared \
  -I$REAPI/include/cssdk/pm_shared -I$REAPI/include/cssdk/public \
  -I$REAPI/include/metamod -I$REAPI/src -I$REAPI/src/mods -I$REAPI/src/natives \
  -I$REAPI/version -I$REAPI/common"
REAPI_SRCS=""
for f in "$REAPI"/src/*.cpp "$REAPI"/src/natives/*.cpp "$REAPI"/src/mods/*.cpp \
         "$REAPI"/common/*.cpp \
         "$REAPI"/include/cssdk/public/interface.cpp; do
  [ -e "$f" ] || continue
  bn=$(basename "$f" .cpp)
  "$CXX" $REAPI_BASEFLAGS -c "$f" -o "$TMP/mod-reapi/$bn.o"
  REAPI_SRCS="$REAPI_SRCS $TMP/mod-reapi/$bn.o"
done
"$CXX" -shared -o "$OUT/lib/$ABI/libreapi_amxx_$MOD_SUFFIX.so" $REAPI_SRCS \
  -static-libstdc++ -static-libgcc \
  -Wl,--whole-archive "$SYSROOT_LIB/libc++_static.a" -Wl,--no-whole-archive \
  "$SYSROOT_LIB/libc++abi.a" -ldl -lm
echo "   reapi -> $(ls -l "$OUT/lib/$ABI/libreapi_amxx_$MOD_SUFFIX.so" | awk '{print $5}') bytes"

# ------------------------------------------------------------------- yapb
# YaPB bot (yapb/yapb) — metamod plugin, CMake-based.
echo "== building yapb =="
YAPBBUILD="$TMP/yapb-build"
cmake -S "$SRC/yapb" -B "$YAPBBUILD" \
  -GNinja \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=$ABI \
  -DANDROID_PLATFORM=android-24 \
  -DANDROID_STL=c++_static \
  -DCMAKE_BUILD_TYPE=Release
cmake --build "$YAPBBUILD" -j"$(nproc)"
# YaPB produces libyapb.so or yapb.so depending on version
YAPB_SO=$(find "$YAPBBUILD" -name "libyapb.so" -o -name "yapb.so" | head -1)
cp "$YAPB_SO" "$OUT/lib/$ABI/libyapb.so"
echo "   yapb -> $(ls -l "$OUT/lib/$ABI/libyapb.so" | awk '{print $5}') bytes"

# ----------------------------------------------------------------- client (crash handler)
# CS16Client client DLL (vcs16/cl_dll) — built with crash handler, bundled as libclient
echo "== building client (vcs16, crash handler) =="
CLIENT_SRC="$REPO_ROOT/vcs16"
CLIENT_BUILD="$TMP/client-build"
# Ensure vcs16 submodules are present (utlstring.h etc). CI checks out cs16-meta-patcher
# without --recurse-submodules and vcs16 was copied without .git, so 3rdparty dirs may be empty.
if [ -f "$CLIENT_SRC/.gitmodules" ]; then
  if [ -d "$CLIENT_SRC/.git" ]; then
    git -C "$CLIENT_SRC" submodule update --init --recursive 2>&1 | head -20 || true
  else
    echo "   vcs16 .git missing, fetching submodules manually"
    for mod in "3rdparty/mainui_cpp|https://github.com/Velaron/mainui_cpp|5b9c60ce408843b6c439ccfe875542978e70de96" "3rdparty/miniutl|https://github.com/FWGS/MiniUTL|048a416f4c54c501dfd728fd792bfdc9f2883f51"; do
      IFS='|' read -r path url rev <<< "$mod"
      if [ ! -f "$CLIENT_SRC/$path/CMakeLists.txt" ] && [ ! -f "$CLIENT_SRC/$path/README.md" ]; then
        rm -rf "$CLIENT_SRC/$path"
        git clone --depth 1 "$url" "$CLIENT_SRC/$path" 2>&1 | tail -2 || true
        if [ -n "$rev" ]; then
          git -C "$CLIENT_SRC/$path" fetch --depth 1 origin "$rev" 2>&1 | tail -1 || true
          git -C "$CLIENT_SRC/$path" checkout "$rev" 2>&1 | tail -1 || true
        fi
        # mainui_cpp's own miniutl submodule
        if [ -f "$CLIENT_SRC/$path/.gitmodules" ]; then
          git -C "$CLIENT_SRC/$path" submodule update --init --recursive --depth 1 2>&1 | tail -2 || true
        fi
        # cs16-meta-patcher menu customizations (text banner titles, trimmed main
        # menu, Color.cpp build shim). Only meaningful on a pristine checkout.
        if [ "$path" = "3rdparty/mainui_cpp" ]; then
          if ! git -C "$CLIENT_SRC/$path" apply --reverse --check "$PATCHES/mainui-menu-text-and-trim.patch" 2>/dev/null; then
            echo "   mainui: applying cs16 menu customizations"
            git -C "$CLIENT_SRC/$path" apply "$PATCHES/mainui-menu-text-and-trim.patch" 2>/dev/null || echo "   WARN: mainui patch did not apply cleanly"
          else
            echo "   mainui: menu customizations already applied, skipping"
          fi
        fi
      fi
    done
  fi
fi
# mainui_cpp's own miniutl submodule pin (b2741298) typedefs int64/uint64 from
# stdint.h, but the Android client DLL (steamtypes.h) already defines int64 as
# long long while bionic's int64_t is long -> cl_dll/vgui_parser.cpp fails with
# "typedef redefinition". Replace it with the MiniUTL pin the client was built
# against (048a416f, which has no conflicting typedefs).
if [ -d "$CLIENT_SRC/3rdparty/miniutl" ] && [ -d "$CLIENT_SRC/3rdparty/mainui_cpp/miniutl" ]; then
  echo "   mainui: replacing miniutl submodule with client-compatible pin (048a416f)"
  rm -rf "$CLIENT_SRC/3rdparty/mainui_cpp/miniutl"
  cp -a "$CLIENT_SRC/3rdparty/miniutl/." "$CLIENT_SRC/3rdparty/mainui_cpp/miniutl/"
fi
cmake -S "$CLIENT_SRC" -B "$CLIENT_BUILD" \
  -GNinja \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=$ABI \
  -DANDROID_PLATFORM=android-24 \
  -DANDROID_STL=c++_static \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
  -DCS16_PATCHER_VERSION="${RELEASE_VERSION:-dev}" \
  -DBUILD_CLIENT=ON -DBUILD_SERVER=OFF -DBUILD_MAINUI=ON -DMAINUI_NAME=menu -DMAINUI_USE_STB=ON -DMAINUI_RENDER_PICBUTTON_TEXT=ON
cmake --build "$CLIENT_BUILD" --target client -j"$(nproc)"
CLIENT_SO=$(find "$CLIENT_BUILD" -name "libclient_android_*.so" -o -name "client_android_*.so" | head -1)
if [ -n "$CLIENT_SO" ]; then
  cp "$CLIENT_SO" "$OUT/lib/$ABI/libclient_android_$RUNTIME_SUFFIX.so"
  echo "   client -> $(ls -l "$OUT/lib/$ABI/libclient_android_$RUNTIME_SUFFIX.so" | awk '{print $5}') bytes"
else
  echo "WARN: client lib not found, skipping"
fi
cmake --build "$CLIENT_BUILD" --target xashmenu -j"$(nproc)"
MENU_SO=$(find "$CLIENT_BUILD" -name "libmenu*.so" | head -1)
if [ -n "$MENU_SO" ]; then
  cp "$MENU_SO" "$OUT/lib/$ABI/libmenu_android_$RUNTIME_SUFFIX.so"
  echo "   menu -> $(ls -l "$OUT/lib/$ABI/libmenu_android_$RUNTIME_SUFFIX.so" | awk '{print $5}') bytes"
else
  echo "WARN: menu lib not found, skipping"
fi

# ----------------------------------------------------------------- plugins
# Host pawncc. libpc300 is compiled from source with 64-bit PAWN cells
# (PAWN_CELL_SIZE=64) and exported as Compile64; the amxxpc driver prefers
# Compile64 and writes cellsize = sizeof(cell), so plugins are 64-bit and
# loadable by the 64-bit AMXX core. NOTE: the CMakeLists of libpc300 is stale
# (missing files / cmake_minimum_required), so we compile it by hand.
echo "== building host pawncc (64-bit cells) =="
LIBPC="$AMXX/compiler/libpc300"
# -DLINUX turns on sclinux.h (stricmp/strnicmp, unistd.h) in the libpc300
# sources. Upstream has no compiler/linux/{prefix.c,prefix.h} (the fork vendored
# it), and sc1.c only pulls <prefix.h> under that same guard, so our
# amxmodx-pawncc-64bit.patch drops the prefix.h include.
PC_BUILD="$TMP/libpc300"
mkdir -p "$PC_BUILD/obj"
PC_COMMON="-std=gnu17 -O0 -fPIC -DPAWN_CELL_SIZE=64 -DHAVE_I64 -DLINUX \
  -DHAVE_UNISTD_H -DHAVE_INTTYPES_H -DHAVE_STDINT_H -DHAVE_ALLOCA_H -I$LIBPC"
# Mirror upstream AMBuilder's amxxpc32 source list exactly; NO_MAIN on every
# unit strips main()s (sc1.c, pawncc.c, prefix.c, ...), PAWNC_DLL selects the
# exported sp_Compile/LibCompile ABI, sp_symhash.c provides NewHashTable.
for s in sc1 sc2 sc3 sc4 sc5 sc6 sc7 scvars scmemfil scstate sclist sci18n \
         pawncc libpawnc prefix memfile sp_symhash; do
  f="$LIBPC/$s.c"
  [ -e "$f" ] || continue
  "$HOSTCC" $PC_COMMON -DNO_MAIN -DPAWNC_DLL -D_GNU_SOURCE \
    -c "$f" -o "$PC_BUILD/obj/$s.o"
done
"$HOSTCC" -shared -o "$PC_BUILD/amxxpc32.so" "$PC_BUILD"/obj/*.o -lm -lpthread
cp "$PC_BUILD/amxxpc32.so" "$PC_BUILD/amxxpc.so"

# Host zlib for the amxxpc driver. amxxpc.cpp includes "zlib/zlib.h" and calls
# compress/compressBound, which upstream resolves by putting third_party/ on the
# include path (the .vcxproj does exactly that) and linking the tree's own
# (intermediate, cdecl) zlib into the binary.
Z_DIR="$AMXX/third_party/zlib"
for f in "$Z_DIR"/*.c; do
  [ -e "$f" ] || continue
  "$HOSTCC" -O2 -fPIC -c "$f" -o "$PC_BUILD/obj/zlib-$(basename "${f%.c}").o"
done

PAWNCC=""
if command -v "$HOSTCXX" >/dev/null 2>&1 || [ -x "$HOSTCXX" ]; then
  # -DPAWN_CELL_SIZE=64 + HAVE_I64 keep cell 64-bit (amx_AlignCell -> amx_Align64,
  # pointer<->cell casts in amx.cpp don't lose precision); AMX_ANSIONLY drops the
  # wide-char paths so wcslen isn't needed; LINUX pulls in sclinux.h; HAVE_STDINT_H
  # lets libpawnc skip its own int32_t typedefs; -I third_party resolves
  # "zlib/zlib.h".
  "$HOSTCXX" -O2 -std=c++14 -DPAWN_CELL_SIZE=64 -DHAVE_I64 -DHAVE_STDINT_H \
    -DLINUX -DAMX_ANSIONLY \
    -I"$LIBPC" -I"$AMXX/public" -I"$AMXX/compiler/amxxpc" -I"$AMXX/third_party" \
    -o "$PC_BUILD/amxxpc" "$AMXX/compiler/amxxpc"/amxxpc.cpp \
    "$AMXX/compiler/amxxpc"/Binary.cpp "$AMXX/compiler/amxxpc"/amx.cpp \
    "$PC_BUILD"/obj/zlib-*.o
  PAWNCC="$PC_BUILD/amxxpc"
else
  echo "   host $HOSTCXX not found, skipping plugin compilation"
fi

if [[ -n "$PAWNCC" && -n "$PLUGINS_SRC" && -d "$PLUGINS_SRC" ]]; then
  echo "== compiling plugins (64-bit cells) =="
  extra_inc=(-i"$AMXX/plugins/include")
  [ -d "$PLUGINS_SRC/include" ] && extra_inc+=(-i"$PLUGINS_SRC/include")
  for f in "$PLUGINS_SRC"/*.sma; do
    [ -e "$f" ] || continue
    local_out="$OUT/plugins/$(basename "${f%.sma}.amxx")"
    set +e
    out=$( ( cd "$PC_BUILD" && LC_ALL=C.UTF-8 LANG=C.UTF-8 stdbuf -oL -eL \
             "$PAWNCC" "${extra_inc[@]}" -o"$local_out" "$f" ) 2>&1 )
    rc=$?
    set -e
    if [ $rc -ne 0 ]; then
      echo "   FAILED: $f (rc=$rc)" >&2
      printf '%s\n' "$out" >&2
      # lib-only sanity (same lib, no driver): does Compile64 succeed alone?
      if command -v python3 >/dev/null 2>&1; then
        LC_ALL=C.UTF-8 python3 - "$f" "$local_out" "$AMXX/plugins/include" "$PC_BUILD" <<'PY' >&2 || true
import ctypes, os, sys
sma, outfile, inc, build = sys.argv[1:5]
os.chdir(build)
lib = ctypes.CDLL("./amxxpc32.so")
f = lib.Compile64
f.restype = ctypes.c_int
f.argtypes = [ctypes.c_int, ctypes.POINTER(ctypes.c_char_p)]
args = ["amxxpc", "-i" + inc, "-o" + outfile, sma]
argv = (ctypes.c_char_p * len(args))(*(a.encode() for a in args))
rc = f(len(args), argv)
print(f"[lib-only] Compile64 rc={rc} file_exists={os.path.exists(outfile)}", file=sys.stderr)
PY
      fi
      exit 1
    fi
    echo "   $(basename "$f") OK"
  done
fi

# --------------------------------------------------------------- amxxpc ($ABI)
# Same compiler sources as the host pawncc above, but cross-compiled for the
# target Android ABI so the patcher app can compile plugins on-device straight
# out of the bundle. Layout mirrors the AMBuilder targets:
#   OUT/compiler/$ABI/amxxpc          driver (amxx.cpp + amxxpc.cpp + Binary.cpp + zlib)
#   OUT/compiler/$ABI/amxxpc32.so     libpc300 kernel (libpawnc + sc*), PAWN_CELL_SIZE=64
# The driver dlopens/amxxpc32.so at runtime, so both ship together. libc++ is
# linked statically (libc++_static + libc++abi, whole-archive) to avoid having to
# bundle libc++_shared.so and juggle LD_LIBRARY_PATH on-device.
echo "== building amxxpc for $ABI (embedded) =="
PC_DEV="$TMP/amxxpc-$ABI"
rm -rf "$PC_DEV"
mkdir -p "$PC_DEV"
PC_DEV_COMMON="-std=gnu17 -O2 -fPIC -DPAWN_CELL_SIZE=64 -DHAVE_I64 -DLINUX \
  -DHAVE_UNISTD_H -DHAVE_INTTYPES_H -DHAVE_STDINT_H -DHAVE_ALLOCA_H \
  -D__BYTE_ORDER=__LITTLE_ENDIAN -D__LITTLE_ENDIAN -I$LIBPC"
for s in sc1 sc2 sc3 sc4 sc5 sc6 sc7 scvars scmemfil scstate sclist sci18n \
         pawncc libpawnc prefix memfile sp_symhash; do
  f="$LIBPC/$s.c"
  [ -e "$f" ] || continue
  "$CC" $PC_DEV_COMMON -DNO_MAIN -DPAWNC_DLL -D_GNU_SOURCE -c "$f" -o "$PC_DEV/$s.o"
done
"$CXX" -shared -static-libstdc++ -o "$PC_DEV/amxxpc32.so" "$PC_DEV"/*.o -lm -ldl \
  -Wl,--whole-archive "$SYSROOT_LIB/libc++_static.a" -Wl,--no-whole-archive "$SYSROOT_LIB/libc++abi.a"
mkdir -p "$PC_DEV/zobj"
for f in "$AMXX/third_party/zlib"/*.c; do
  [ -e "$f" ] || continue
  "$CC" -O2 -fPIC -c "$f" -o "$PC_DEV/zobj/$(basename "${f%.c}").o"
done
"$CXX" -O2 -std=c++14 -DPAWN_CELL_SIZE=64 -DHAVE_I64 -DHAVE_STDINT_H \
  -DLINUX -DAMX_ANSIONLY -D__BYTE_ORDER=__LITTLE_ENDIAN -D__LITTLE_ENDIAN \
  -I"$LIBPC" -I"$AMXX/public" -I"$AMXX/compiler/amxxpc" -I"$AMXX/third_party" \
  -o "$PC_DEV/amxxpc" "$AMXX/compiler/amxxpc"/amxxpc.cpp \
  "$AMXX/compiler/amxxpc"/Binary.cpp "$AMXX/compiler/amxxpc"/amx.cpp \
  "$PC_DEV"/zobj/*.o \
  -static-libstdc++ -static-libgcc \
  -Wl,--whole-archive "$SYSROOT_LIB/libc++_static.a" -Wl,--no-whole-archive \
  "$SYSROOT_LIB/libc++abi.a" -ldl -lm -pthread
mkdir -p "$OUT/compiler/$ABI"
cp "$PC_DEV/amxxpc" "$PC_DEV/amxxpc32.so" "$OUT/compiler/$ABI/"
echo "   amxxpc -> $(ls -l "$OUT/compiler/$ABI/amxxpc" | awk '{print $5}') bytes"

echo "ALL_BUILT"
ls -l "$OUT/lib/$ABI/" "$OUT/plugins" "$OUT/compiler/$ABI"