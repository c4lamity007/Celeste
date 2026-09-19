#!/usr/bin/env bash
set -euo pipefail

platform="${1:?Pass macos-arm64 or macos-x64}"
case "$platform" in
  macos-arm64|macos-x64) ;;
  *) printf 'Unsupported Mac platform: %s\n' "$platform" >&2; exit 1 ;;
esac

mkdir -p dist/Celeste.app/Contents/{MacOS,Resources}
install -m 755 native-verifier/target/release/CelesteLauncher dist/Celeste.app/Contents/MacOS/Celeste

/usr/libexec/PlistBuddy -c 'Clear dict' dist/Celeste.app/Contents/Info.plist
/usr/libexec/PlistBuddy -c 'Add :CFBundleExecutable string Celeste' dist/Celeste.app/Contents/Info.plist
/usr/libexec/PlistBuddy -c 'Add :CFBundleIdentifier string dev.c4lam.celeste.normal' dist/Celeste.app/Contents/Info.plist
/usr/libexec/PlistBuddy -c 'Add :CFBundleName string Celeste' dist/Celeste.app/Contents/Info.plist
/usr/libexec/PlistBuddy -c 'Add :CFBundleDisplayName string Celeste' dist/Celeste.app/Contents/Info.plist
/usr/libexec/PlistBuddy -c 'Add :CFBundlePackageType string APPL' dist/Celeste.app/Contents/Info.plist
/usr/libexec/PlistBuddy -c 'Add :CFBundleShortVersionString string 1.0.0' dist/Celeste.app/Contents/Info.plist
/usr/libexec/PlistBuddy -c 'Add :CFBundleVersion string 20260919.3' dist/Celeste.app/Contents/Info.plist
/usr/libexec/PlistBuddy -c 'Add :LSMinimumSystemVersion string 11.0' dist/Celeste.app/Contents/Info.plist
/usr/libexec/PlistBuddy -c 'Add :NSHighResolutionCapable bool true' dist/Celeste.app/Contents/Info.plist
/usr/libexec/PlistBuddy -c 'Add :CFBundleIconFile string Celeste.icns' dist/Celeste.app/Contents/Info.plist

mkdir -p dist/Celeste.iconset
for size in 16 32 128 256 512; do
  sips -s format png -z "$size" "$size" src/main/resources/assets/celeste/textures/gui/celeste-logo.png --out "dist/Celeste.iconset/icon_${size}x${size}.png" >/dev/null
  retina=$((size * 2))
  sips -s format png -z "$retina" "$retina" src/main/resources/assets/celeste/textures/gui/celeste-logo.png --out "dist/Celeste.iconset/icon_${size}x${size}@2x.png" >/dev/null
done
iconutil --convert icns --output dist/Celeste.app/Contents/Resources/Celeste.icns dist/Celeste.iconset
codesign --force --sign - --timestamp=none dist/Celeste.app
codesign --verify --deep --strict --verbose=2 dist/Celeste.app
file dist/Celeste.app/Contents/MacOS/Celeste
otool -L dist/Celeste.app/Contents/MacOS/Celeste

printf '%s\n' 'Celeste for macOS' 'Open Celeste.app, activate, and select the folder where Celeste.jar should be downloaded.' 'Move the downloaded JAR into your Fabric 1.21.11 mods folder.' 'This application is ad-hoc signed, not Apple-notarized. If macOS blocks it, approve Celeste in System Settings > Privacy & Security > Open Anyway.' 'Allow Keychain access when prompted so your activation can be saved.' > dist/README.txt
ditto -c -k --sequesterRsrc --keepParent dist/Celeste.app "dist/Celeste-${platform}.zip"
rm -r dist/Celeste.app dist/Celeste.iconset
