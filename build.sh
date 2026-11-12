#!/usr/bin/env bash
# 手工构建 LSPosed 模块 APK（aapt2 + javac + d8 + apksigner，不依赖 Gradle / AGP）
#
# 用法：
#   bash build.sh
#
# 可覆盖的环境变量：
#   ANDROID_HOME / ANDROID_SDK_ROOT   Android SDK 路径（自动探测失败时指定）
#   JAVA_HOME                         JDK 路径（需 11+）
#   KS_PASS                           已有 keystore.jks 的密码（首次构建会自动生成）
#   KS_PATH                           keystore 路径，默认 ./keystore.jks
#   VERSION_NAME / VERSION_CODE       版本号，默认 1.2 / 3
set -e

ROOT="$(cd "$(dirname "$0")" && pwd)"
PROJ="$ROOT/XiaomiAICallAgent"
BUILD="$ROOT/build"

VERSION_NAME="${VERSION_NAME:-1.4}"
VERSION_CODE="${VERSION_CODE:-5}"
OUT="$ROOT/AICallAgent-$VERSION_NAME.apk"

# ---- 平台差异 ---------------------------------------------------------------
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) EXE=".exe"; BAT=".bat" ;;
  *)                    EXE="";    BAT="" ;;
esac

# aapt2/apksigner 不认 POSIX 路径，Windows 下转成 C:/ 形式
if command -v cygpath >/dev/null 2>&1; then
  W() { cygpath -m "$1"; }
else
  W() { printf '%s' "$1"; }
fi

# ---- 定位 Android SDK -------------------------------------------------------
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [ -z "$SDK" ] || [ ! -d "$SDK/build-tools" ]; then
  for cand in \
    "${LOCALAPPDATA:+$(cygpath -u "$LOCALAPPDATA" 2>/dev/null)/Android/Sdk}" \
    "$HOME/Android/Sdk" "$HOME/Library/Android/sdk" \
    "/d/PATH/Android/Sdk" "/c/Android/Sdk" "/opt/android-sdk" ; do
    if [ -n "$cand" ] && [ -d "$cand/build-tools" ]; then SDK="$cand"; break; fi
  done
fi
[ -d "$SDK/build-tools" ] || { echo "错误：找不到 Android SDK，请设置 ANDROID_HOME" >&2; exit 1; }

BT="$SDK/build-tools/$(ls -1 "$SDK/build-tools" | sort -V | tail -1)"
PLATFORM="$(ls -d "$SDK/platforms"/android-* 2>/dev/null | sort -V | tail -1)/android.jar"
[ -f "$PLATFORM" ] || { echo "错误：$SDK/platforms 下没有 android.jar" >&2; exit 1; }

# ---- 定位 JDK ---------------------------------------------------------------
if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/javac$EXE" ]; then
  JDKBIN="$JAVA_HOME/bin"
else
  JAVAC_PATH="$(command -v javac)" || { echo "错误：找不到 javac，请设置 JAVA_HOME" >&2; exit 1; }
  JDKBIN="$(dirname "$JAVAC_PATH")"
fi
JAVAC="$JDKBIN/javac$EXE"
JAR="$JDKBIN/jar$EXE"
KEYTOOL="$JDKBIN/keytool$EXE"

XPAPI="$ROOT/libs/api82.jar"
[ -f "$XPAPI" ] || { echo "错误：缺少 libs/api82.jar（Xposed API）" >&2; exit 1; }

echo "SDK    : $SDK"
echo "BT     : $BT"
echo "JDK    : $JDKBIN"

rm -rf "$BUILD"
mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/dex"

echo "==> 1/6 编译资源"
"$BT/aapt2$EXE" compile --dir "$(W "$PROJ/res")" -o "$(W "$BUILD/res.zip")"

echo "==> 2/6 链接资源"
# aapt2 的 --version-code/--version-name 会被 Manifest 里的静态声明覆盖，
# 所以先把 Manifest 里的版本号替换掉再交给 aapt2，保证下面的参数真的生效。
MANIFEST="$BUILD/AndroidManifest.xml"
sed -e "s/android:versionCode=\"[0-9]*\"/android:versionCode=\"$VERSION_CODE\"/" \
    -e "s/android:versionName=\"[^\"]*\"/android:versionName=\"$VERSION_NAME\"/" \
    "$PROJ/AndroidManifest.xml" > "$MANIFEST"
"$BT/aapt2$EXE" link \
  -o "$(W "$BUILD/unsigned.apk")" \
  -I "$(W "$PLATFORM")" \
  --manifest "$(W "$MANIFEST")" \
  -A "$(W "$PROJ/assets")" \
  --java "$(W "$BUILD/gen")" \
  --min-sdk-version 29 \
  --target-sdk-version 35 \
  --version-code "$VERSION_CODE" \
  --version-name "$VERSION_NAME" \
  "$(W "$BUILD/res.zip")"

echo "==> 3/6 编译 Java"
find "$PROJ/src" "$BUILD/gen" -name "*.java" > "$BUILD/sources.txt"
if [ -n "$EXE" ]; then
  sed -i "s|/c/|C:/|g; s|/d/|D:/|g" "$BUILD/sources.txt"
fi
"$JAVAC" -encoding UTF-8 -source 11 -target 11 \
  -classpath "$(W "$PLATFORM");$(W "$XPAPI")" \
  -d "$(W "$BUILD/classes")" \
  @"$(W "$BUILD/sources.txt")"

echo "==> 4/6 转 dex"
find "$BUILD/classes" -name "*.class" > "$BUILD/classes.txt"
if [ -n "$EXE" ]; then
  sed -i "s|/c/|C:/|g; s|/d/|D:/|g" "$BUILD/classes.txt"
fi
"$BT/d8$BAT" --release --lib "$(W "$PLATFORM")" --min-api 29 \
  --output "$(W "$BUILD/dex")" @"$(W "$BUILD/classes.txt")"

echo "==> 5/6 封装 dex"
cp "$BUILD/unsigned.apk" "$BUILD/unsigned2.apk"
"$JAR" uf "$BUILD/unsigned2.apk" -C "$(W "$BUILD/dex")" classes.dex

echo "==> 6/6 对齐与签名"
KS="${KS_PATH:-$ROOT/keystore.jks}"
KS_ALIAS="${KS_ALIAS:-aicall}"
if [ -z "$KS_PASS" ]; then
  if [ -f "$KS" ]; then
    echo "错误：$KS 已存在，请用环境变量提供密码：KS_PASS=你的密码 bash build.sh" >&2
    exit 1
  fi
  KS_PASS="aicallagent"   # 自动生成的开发用 keystore 默认密码
  "$KEYTOOL" -genkeypair -keystore "$(W "$KS")" -alias "$KS_ALIAS" -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -dname "CN=AICallAgent, OU=Dev, O=CherryJay, L=Zhejiang, C=CN"
  echo "已生成新 keystore：$KS（密码 $KS_PASS，请自行保管）"
fi

"$BT/zipalign$EXE" -f -p 4 "$(W "$BUILD/unsigned2.apk")" "$(W "$BUILD/aligned.apk")"
"$BT/apksigner$BAT" sign --ks "$(W "$KS")" --ks-key-alias "$KS_ALIAS" \
  --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --out "$(W "$OUT")" "$(W "$BUILD/aligned.apk")"

"$BT/apksigner$BAT" verify --print-certs "$(W "$OUT")" | head -3
ls -lh "$OUT"
echo "构建完成"
