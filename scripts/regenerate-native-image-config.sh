#!/usr/bin/env bash
set -euo pipefail

GRAAL_HOME="$HOME/.sdkman/candidates/java/25.3.4+1.r25-graalce"
if [ ! -x "$GRAAL_HOME/bin/java" ]; then
  echo "GraalVM CE 25 not found at $GRAAL_HOME" >&2
  echo "Install it with: sdk install java 25.3.4+1.r25-graalce" >&2
  echo "(answer 'n' when asked to set it as default)" >&2
  exit 1
fi

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

CONFIG_OUTPUT_DIR="$(mktemp -d)"
TARGET_DIR="$REPO_ROOT/src/main/resources/META-INF/native-image/com.tung/receipt-extractor"

echo "Building bootJar (with AOT processing)..."
./gradlew bootJar --no-daemon -q

export SHEETS_CLIENT_ID=dummy SHEETS_CLIENT_SECRET=dummy SHEETS_REFRESH_TOKEN=dummy
export SHEETS_SPREADSHEET_ID=dummy SHEETS_SHEET_NAME=dummy
export TELEGRAM_BOT_TOKEN=dummy TELEGRAM_ALLOWED_CHAT_ID=12345 TELEGRAM_WEBHOOK_SECRET_TOKEN=dummy
export OCR_TESSDATA_PATH="$REPO_ROOT/src/main/resources/tessdata"

JAR_PATH=$(ls "$REPO_ROOT"/build/libs/*.jar | grep -v -- '-plain\.jar$' | head -1)

echo "Starting app under native-image-agent..."
"$GRAAL_HOME/bin/java" -agentlib:native-image-agent=config-output-dir="$CONFIG_OUTPUT_DIR" \
  -jar "$JAR_PATH" > /tmp/regen-native-config-app.log 2>&1 &
APP_PID=$!

echo "Waiting for app to start..."
for i in $(seq 1 30); do
  if curl -s -o /dev/null http://localhost:8080/api/ocr/extract; then
    break
  fi
  sleep 1
done
sleep 3

echo "Exercising OCR endpoint..."
curl -s -f -F "file=@$REPO_ROOT/src/test/resources/ocr/sample-receipt.png;type=image/png" \
  http://localhost:8080/api/ocr/extract > /tmp/regen-native-config-response.json

echo "Stopping app..."
kill -TERM "$APP_PID"
wait "$APP_PID" 2>/dev/null || true

mkdir -p "$TARGET_DIR"
cp "$CONFIG_OUTPUT_DIR/reachability-metadata.json" "$TARGET_DIR/reachability-metadata.json"

echo "Updated $TARGET_DIR/reachability-metadata.json"
echo "OCR response was:"
cat /tmp/regen-native-config-response.json
echo
