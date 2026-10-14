#!/bin/bash
set -euo pipefail
cd -- "$(dirname -- "$0")"
mkdir -p "$PWD/Exports/Unsigned"
xcodebuild -project 'Huati.xcodeproj' -scheme 'Huati' -configuration Release -destination 'generic/platform=iOS' -derivedDataPath "$PWD/Exports/Unsigned/DerivedData" CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO CODE_SIGN_IDENTITY='' build
APP="$PWD/Exports/Unsigned/DerivedData/Build/Products/Release-iphoneos/Huati.app"
test -f "$APP/Huati"
/usr/bin/file "$APP/Huati" | /usr/bin/grep -q 'arm64'
STAGE="$(mktemp -d "$PWD/Exports/Unsigned/payload.XXXXXX")"
trap 'rm -rf -- "$STAGE"' EXIT
mkdir "$STAGE/Payload"
/usr/bin/ditto "$APP" "$STAGE/Payload/Huati.app"
/usr/bin/ditto -c -k --keepParent "$STAGE/Payload" "$PWD/Exports/Unsigned/Huati-v2.0.6-unsigned.ipa"
/usr/bin/python3 'tools/verify_ipa.py' "$PWD/Exports/Unsigned/Huati-v2.0.6-unsigned.ipa"
printf '%s\n' '已生成 Exports/Unsigned/Huati-v2.0.6-unsigned.ipa。此包尚未签名，请使用自己的签名工具安装。'
