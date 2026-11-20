#!/bin/bash
# =============================================================================
#  构建前自检 —— 把「缺什么」一次性说清，不要跑到一半才炸。
#
#  用法：  bash build_check.sh
#          （在 源码/ 下跑，或任意位置跑都行，脚本自己定位）
#          R1_TOOLS=/path/to/tools bash build_check.sh   # 工具链放在别处
#
#  退出码：0 = 全齐，可以构建；1 = 有缺件
# =============================================================================
cd "$(dirname "$0")" || exit 1
SRC="$(pwd -W 2>/dev/null || pwd)"
TOOLS="${R1_TOOLS:-$SRC/tools}"
BAD=0

ok()   { printf '  [OK]   %s\n' "$*"; }
no()   { printf '  [MISS] %s\n' "$*"; BAD=1; }
warn() { printf '  [WARN] %s\n' "$*"; }
head2(){ printf '\n%s\n' "$*"; }

file_ok() { if [ -f "$1" ]; then ok "$2"; else no "$2   ->  $1"; fi; }
dir_ok()  { if [ -d "$1" ]; then ok "$2"; else no "$2   ->  $1"; fi; }

head2 "== 工具链（$TOOLS）=="

# --- JDK ---
JAVAC=""
if   [ -x "$TOOLS/jdk/bin/javac.exe" ]; then JAVAC="$TOOLS/jdk/bin/javac.exe"
elif [ -x "$TOOLS/jdk/bin/javac" ];     then JAVAC="$TOOLS/jdk/bin/javac"
elif command -v javac >/dev/null 2>&1;  then JAVAC="javac"
fi
if [ -z "$JAVAC" ]; then
    no "JDK 17（javac）->  $TOOLS/jdk/bin/  或 PATH"
else
    JV="$("$JAVAC" -version 2>&1 | head -1)"
    case "$JV" in
        *17*) ok "JDK 17：$JV" ;;
        *)    warn "JDK 版本不是 17：$JV —— 可能能编，但没验证过" ;;
    esac
fi

file_ok "$TOOLS/android.jar" "android.jar"
file_ok "$TOOLS/r8.jar"      "r8.jar"

SMALI_JARS="smali-2.5.2.jar baksmali-2.5.2.jar dexlib2-2.5.2.jar util-2.5.2.jar guava-25.1-android.jar jcommander-1.72.jar commons-cli-1.4.jar antlr-runtime-3.5.2.jar"
if [ -d "$TOOLS/smali" ]; then
    _miss=""
    for j in $SMALI_JARS; do
        [ -f "$TOOLS/smali/$j" ] || _miss="$_miss $j"
    done
    if [ -z "$_miss" ]; then
        ok "smali/baksmali 2.5.2（8 个 jar 齐）"
    else
        no "smali/ 缺：$_miss"
    fi
else
    no "smali/ 目录   ->  $TOOLS/smali/"
fi

# --- Python ---
if [ -z "${PY:-}" ]; then
    for c in python3 python py; do
        if command -v "$c" >/dev/null 2>&1; then PY="$c"; break; fi
    done
fi
if [ -z "${PY:-}" ]; then
    no "Python 3（进 PATH，或 PY=/path/to/python）"
else
    PV="$("$PY" -c 'import sys;print("%d.%d"%sys.version_info[:2])' 2>/dev/null)"
    if [ -z "$PV" ]; then
        no "Python 3 不可用：$PY"
    else
        ok "Python $PV（$PY）"
    fi
fi
command -v node >/dev/null 2>&1 && ok "node（可选，只用于 JS 语法自检）" || warn "没有 node —— 跳过 JS 语法自检，不影响构建"
command -v 7z   >/dev/null 2>&1 && ok "7z（可选，打包用）" || warn "没有 7z（可选）"

head2 "== device（设备端注入包）=="
D="$SRC/device"
dir_ok  "$D/v2src"              "v2src（Java 源码）"
dir_ok  "$D/uni_smali"          "uni_smali（原厂 smali 基线）"
file_ok "$D/ui/gen_java.py"     "ui/gen_java.py"
file_ok "$D/ui/ui.html"         "ui/ui.html"
dir_ok  "$D/javacp"             "javacp（编译期依赖）"
for j in okhttp.jar okio.jar orgjson.jar sherpa-onnx-classes.jar kotlin-stdlib.jar; do
    [ -f "$D/javacp/$j" ] || no "javacp/$j"
done
file_ok "$D/libr1opus.so"       "libr1opus.so"
file_ok "$D/keep_v23.pro"       "keep_v23.pro（R8 规则）"
file_ok "$D/Unisound.apk"       "Unisound.apk（原厂壳）"
file_ok "$D/janus_poc.py"       "janus_poc.py"
file_ok "$D/build_rel_V206.sh"  "build_rel_V206.sh"

head2 "== player（播放器端注入包）=="
P="$SRC/player"
dir_ok  "$P/plsrc"                  "plsrc（Java 源码）"
dir_ok  "$P/stub"                   "stub（编译期桩源码，不进 dex）"
dir_ok  "$P/smali_echo"             "smali_echo（原厂 smali 基线）"
dir_ok  "$P/smali_exo"              "smali_exo（ExoPlayer 补丁树）"
file_ok "$P/ui/gen_java.py"         "ui/gen_java.py"
file_ok "$P/ui/player.html"         "ui/player.html"
file_ok "$P/EchoService_system.apk" "EchoService_system.apk（原厂壳）"
file_ok "$P/janus_poc.py"           "janus_poc.py"
file_ok "$P/patch_smali.py"         "patch_smali.py"
file_ok "$P/_gen_assets.py"         "_gen_assets.py"
file_ok "$P/build_rel_V206.sh"      "build_rel_V206.sh"

head2 "== 结论 =="
if [ "$BAD" -eq 0 ]; then
    echo "  全部就绪。构建："
    echo "    cd device && bash build_rel_V206.sh    # -> XiaoZhi_Unisound_V2.0.6.apk"
    echo "    cd player && bash build_rel_V206.sh    # -> XiaoZhi_EchoService_V2.0.6.apk"
    exit 0
else
    echo "  有缺件，按上面的路径补齐（见 环境说明.md），或设 R1_TOOLS 指向工具链目录。"
    exit 1
fi
