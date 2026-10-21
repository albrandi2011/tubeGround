#!/bin/bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
TOOLS="$ROOT/.build-tools"
SDK="$TOOLS/android-sdk"
# Definisci sempre una versione predefinita anche se lo script viene copiato/modificato.
: "${GRADLE_VERSION:=8.13}"
GRADLE_HOME="${GRADLE_HOME:-$TOOLS/gradle-$GRADLE_VERSION}"
mkdir -p "$TOOLS" "$ROOT/dist"

say() { printf '\n[TubeGround] %s\n' "$*"; }
die() { say "ERRORE: $*"; read -r -p 'Premi Invio per chiudere...' _ || true; exit 1; }
need_cmd() { command -v "$1" >/dev/null 2>&1 || die "Manca il comando '$1'."; }

[[ "$(uname -s)" == "Darwin" ]] || die "Questo script è pensato per macOS."
need_cmd curl
need_cmd unzip
need_cmd tar
need_cmd python3

ARCH="$(uname -m)"
case "$ARCH" in
  arm64) SDK_ARCH="arm64"; JDK_ARCH="aarch64" ;;
  x86_64) SDK_ARCH="x86_64"; JDK_ARCH="x64" ;;
  *) die "Architettura Mac non supportata: $ARCH" ;;
esac

# Find a suitable installed JDK first. Otherwise cache Temurin JDK 21 in this project.
JAVA_HOME_LOCAL=""
if [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/java" ]]; then
  JAVA_HOME_LOCAL="$JAVA_HOME"
elif [[ -x /usr/libexec/java_home ]]; then
  JAVA_HOME_LOCAL="$(/usr/libexec/java_home -v 21 2>/dev/null || /usr/libexec/java_home -v 17 2>/dev/null || true)"
fi
if [[ -n "$JAVA_HOME_LOCAL" ]] && "$JAVA_HOME_LOCAL/bin/java" -version 2>&1 | grep -Eq 'version "(17|18|19|2[0-9])'; then
  say "Uso il JDK già installato: $JAVA_HOME_LOCAL"
else
  JDK_DIR="$TOOLS/temurin-jdk-21"
  if [[ ! -x "$JDK_DIR/Contents/Home/bin/java" ]]; then
    # Ricalcola l'architettura nel punto d'uso: evita errori con copie dello
    # script in cui ARCH/JDK_ARCH non risultano valorizzate (set -u).
    ARCH="${ARCH:-$(uname -m)}"
    case "$ARCH" in
      arm64) JDK_ARCH="aarch64" ;;
      x86_64) JDK_ARCH="x64" ;;
      *) die "Architettura Mac non supportata per il JDK: $ARCH" ;;
    esac
    say "Scarico Temurin JDK 21 per macOS ${ARCH:-$(uname -m)}…"
    JDK_ARCHIVE="$TOOLS/temurin21.tar.gz"
    curl -L --fail --retry 3 "https://api.adoptium.net/v3/binary/latest/21/ga/mac/$JDK_ARCH/jdk/hotspot/normal/eclipse" -o "$JDK_ARCHIVE" || die "Download del JDK fallito."
    mkdir -p "$TOOLS/temurin-extract"
    tar -xzf "$JDK_ARCHIVE" -C "$TOOLS/temurin-extract"
    FOUND_JAVA="$(find "$TOOLS/temurin-extract" -path '*/Contents/Home/bin/java' -print -quit)"
    [[ -n "$FOUND_JAVA" ]] || die "Non trovo java nel pacchetto JDK scaricato."
    FOUND_HOME="${FOUND_JAVA%/bin/java}"
    mkdir -p "$JDK_DIR/Contents"
    rm -rf "$JDK_DIR/Contents/Home"
    cp -R "$FOUND_HOME" "$JDK_DIR/Contents/Home"
    rm -rf "$TOOLS/temurin-extract" "$JDK_ARCHIVE"
  fi
  JAVA_HOME_LOCAL="$JDK_DIR/Contents/Home"
fi
export JAVA_HOME="$JAVA_HOME_LOCAL"
export PATH="$JAVA_HOME/bin:$PATH"
say "Java selezionato: $(java -version 2>&1 | head -n 1)"

# Download Android command-line tools without installing Android Studio.
SDKMANAGER="$SDK/cmdline-tools/latest/bin/sdkmanager"
if [[ ! -x "$SDKMANAGER" ]]; then
  say "Scarico Android Command-line Tools ufficiali…"
  CMD_URL="https://dl.google.com/android/repository/commandlinetools-mac_${SDK_ARCH}-15859902_latest.zip"
  CMD_ZIP="$TOOLS/android-cmdline-tools.zip"
  CMD_EXTRACT="$TOOLS/android-cmdline-extract"
  curl -L --fail --retry 3 "$CMD_URL" -o "$CMD_ZIP" || die "Download degli Android Command-line Tools fallito."
  rm -rf "$CMD_EXTRACT"
  mkdir -p "$CMD_EXTRACT" "$SDK/cmdline-tools/latest"
  unzip -q "$CMD_ZIP" -d "$CMD_EXTRACT"
  [[ -d "$CMD_EXTRACT/cmdline-tools" ]] || die "Il pacchetto Android SDK scaricato ha un formato inatteso."
  cp -R "$CMD_EXTRACT/cmdline-tools/." "$SDK/cmdline-tools/latest/"
  rm -rf "$CMD_EXTRACT" "$CMD_ZIP"
fi

export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"
export PATH="$SDK/platform-tools:$SDK/cmdline-tools/latest/bin:$PATH"

say "Accetto le licenze SDK e preparo Android API 36 / Build Tools 36.0.0…"
(yes || true) | "$SDKMANAGER" --sdk_root="$SDK" --licenses >/dev/null 2>&1 || true
"$SDKMANAGER" --sdk_root="$SDK" "platform-tools" "platforms;android-36" "build-tools;36.0.0" || die "Installazione dei pacchetti Android SDK fallita."

if [[ ! -x "$GRADLE_HOME/bin/gradle" ]]; then
  say "Scarico Gradle ${GRADLE_VERSION:-8.13}…"
  GRADLE_ZIP="$TOOLS/gradle-${GRADLE_VERSION:-8.13}-bin.zip"
  curl -L --fail --retry 3 "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION:-8.13}-bin.zip" -o "$GRADLE_ZIP" || die "Download Gradle fallito."
  unzip -q -o "$GRADLE_ZIP" -d "$TOOLS"
  rm -f "$GRADLE_ZIP"
fi

say "Compilo TubeGround per dispositivi Android ARM64. La prima compilazione scarica anche le librerie necessarie."
cd "$ROOT"
"$GRADLE_HOME/bin/gradle" --no-daemon --stacktrace :app:assembleDebug || die "Build non riuscita: cerca il primo errore nel testo del terminale."

APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
[[ -f "$APK" ]] || die "Gradle ha terminato ma l'APK non è nel percorso atteso."
cp "$APK" "$ROOT/dist/TubeGround-debug.apk"
shasum -a 256 "$ROOT/dist/TubeGround-debug.apk" > "$ROOT/dist/TubeGround-debug.sha256"
say "Build completata."
say "APK: $ROOT/dist/TubeGround-debug.apk"
say "Hash: $(awk '{print $1}' "$ROOT/dist/TubeGround-debug.sha256")"
open "$ROOT/dist" >/dev/null 2>&1 || true
read -r -p 'Premi Invio per chiudere...' _ || true
