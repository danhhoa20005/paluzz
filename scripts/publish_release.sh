#!/usr/bin/env bash
set -euo pipefail

# Phát hành nhanh bản APK mới lên GitHub (danhhoa20005/paluzz) để mọi TV Box tự cập nhật OTA:
# Cách dùng: ./scripts/publish_release.sh 1.7.0 8 "Ghi chú bản cập nhật"
if [ "$#" -lt 2 ]; then
  echo "Cách dùng: $0 <versionName> <versionCode> [notes]"
  echo "Ví dụ:     $0 1.7.0 8 \"Cải thiện tốc độ quét LAN\""
  exit 1
fi

VER_NAME="$1"
VER_CODE="$2"
NOTES="${3:-Cập nhật phiên bản v${VER_NAME}}"
REPO="danhhoa20005/paluzz"
APK_NAME="ChamCong_DiemDanh_v${VER_NAME}.apk"

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

# 1. Cập nhật versionCode & versionName trong app/build.gradle.kts
sed -i '' -E "s/versionCode = [0-9]+/versionCode = ${VER_CODE}/" app/build.gradle.kts
sed -i '' -E "s/versionName = \"[^\"]+\"/versionName = \"${VER_NAME}\"/" app/build.gradle.kts

# 2. Chạy toàn bộ unit test & build bản Release siêu nhẹ R8 (~2 MB)
./gradlew testDebugUnitTest assembleRelease

APK_SRC="app/build/outputs/apk/release/app-release.apk"
SHA256="$(shasum -a 256 "$APK_SRC" | awk '{print $1}')"
APK_URL="https://github.com/${REPO}/releases/download/v${VER_NAME}/${APK_NAME}"

# 3. Cập nhật releases/version.json (TV Box đọc trực tiếp không dính rate-limit)
mkdir -p releases
cat > releases/version.json <<EOF
{
  "versionCode": ${VER_CODE},
  "versionName": "${VER_NAME}",
  "apkUrl": "${APK_URL}",
  "sha256": "${SHA256}",
  "notes": "${NOTES}"
}
EOF

# 4. Copy ra thư mục Downloads
cp -f "$APK_SRC" "$HOME/Downloads/${APK_NAME}"

# 5. Đẩy mã nguồn & tạo GitHub Release đính kèm file APK
git add app/build.gradle.kts releases/version.json
git commit -m "release: v${VER_NAME} (code ${VER_CODE})" || true
git push origin main

TMP_APK="/tmp/${APK_NAME}"
cp -f "$APK_SRC" "$TMP_APK"
gh release create "v${VER_NAME}" "$TMP_APK" \
  --repo "$REPO" \
  --title "v${VER_NAME}" \
  --notes "${NOTES}"
rm -f "$TMP_APK"

echo "Đã phát hành xong v${VER_NAME} lên https://github.com/${REPO}/releases/tag/v${VER_NAME}"
echo "SHA-256: ${SHA256}"
