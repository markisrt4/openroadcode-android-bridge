#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

WORKFLOW="Build Android APK"
ARTIFACT="openroadcode-android-bridge-debug"
TMP_ROOT="${PREFIX:-/data/data/com.termux/files/usr}/tmp"
DOWNLOAD_DIR="$TMP_ROOT/openroadcode-android-bridge-apk"

fail() {
    echo "ERROR: $*" >&2
    exit 1
}

for cmd in git gh termux-open; do
    command -v "$cmd" >/dev/null 2>&1 || fail "Required command '$cmd' is not installed."
done

git rev-parse --is-inside-work-tree >/dev/null 2>&1 || fail "Run this from inside the openroadcode-android-bridge repository."

BRANCH="$(git branch --show-current)"
[[ -n "$BRANCH" ]] || fail "Detached HEAD is not supported. Switch to the branch you want to test first."

HEAD_SHA="$(git rev-parse HEAD)"
REPO="$(gh repo view --json nameWithOwner --jq '.nameWithOwner')"

echo "Repository : $REPO"
echo "Branch     : $BRANCH"
echo "Commit     : ${HEAD_SHA:0:12}"
echo

echo "Looking for a GitHub Actions build for this commit..."
RUN_JSON="$(gh run list \
    --repo "$REPO" \
    --workflow "$WORKFLOW" \
    --branch "$BRANCH" \
    --commit "$HEAD_SHA" \
    --limit 1 \
    --json databaseId,status,conclusion,displayTitle,headSha)"

RUN_ID="$(printf '%s' "$RUN_JSON" | gh api --method GET /rate_limit >/dev/null 2>&1; printf '%s' "$RUN_JSON" | python -c 'import json,sys; d=json.load(sys.stdin); print(d[0]["databaseId"] if d else "")')"

if [[ -z "$RUN_ID" ]]; then
    echo "No build exists yet for ${HEAD_SHA:0:12}."
    echo "Falling back to the newest successful build on branch '$BRANCH'."
    RUN_JSON="$(gh run list \
        --repo "$REPO" \
        --workflow "$WORKFLOW" \
        --branch "$BRANCH" \
        --status success \
        --limit 1 \
        --json databaseId,status,conclusion,displayTitle,headSha)"
    RUN_ID="$(printf '%s' "$RUN_JSON" | python -c 'import json,sys; d=json.load(sys.stdin); print(d[0]["databaseId"] if d else "")')"
    [[ -n "$RUN_ID" ]] || fail "No successful '$WORKFLOW' run found for branch '$BRANCH'."
else
    STATUS="$(printf '%s' "$RUN_JSON" | python -c 'import json,sys; print(json.load(sys.stdin)[0]["status"])')"
    CONCLUSION="$(printf '%s' "$RUN_JSON" | python -c 'import json,sys; print(json.load(sys.stdin)[0].get("conclusion") or "")')"

    if [[ "$STATUS" != "completed" ]]; then
        echo "Build #$RUN_ID is still running. Waiting for it..."
        gh run watch "$RUN_ID" --repo "$REPO" --exit-status
    elif [[ "$CONCLUSION" != "success" ]]; then
        fail "Build #$RUN_ID completed with conclusion '$CONCLUSION'."
    fi
fi

RUN_INFO="$(gh run view "$RUN_ID" --repo "$REPO" --json displayTitle,headSha,conclusion,url)"
RUN_TITLE="$(printf '%s' "$RUN_INFO" | python -c 'import json,sys; print(json.load(sys.stdin)["displayTitle"])')"
RUN_SHA="$(printf '%s' "$RUN_INFO" | python -c 'import json,sys; print(json.load(sys.stdin)["headSha"])')"
RUN_URL="$(printf '%s' "$RUN_INFO" | python -c 'import json,sys; print(json.load(sys.stdin)["url"])')"

echo
echo "Using build: $RUN_TITLE"
echo "Build SHA  : ${RUN_SHA:0:12}"
echo "Actions    : $RUN_URL"

if [[ "$RUN_SHA" != "$HEAD_SHA" ]]; then
    echo
    echo "WARNING: This APK is from ${RUN_SHA:0:12}, not your current commit ${HEAD_SHA:0:12}."
fi

rm -rf "$DOWNLOAD_DIR"
mkdir -p "$DOWNLOAD_DIR"

echo
echo "Downloading APK artifact..."
gh run download "$RUN_ID" \
    --repo "$REPO" \
    --name "$ARTIFACT" \
    --dir "$DOWNLOAD_DIR"

APK="$(find "$DOWNLOAD_DIR" -type f -name '*.apk' -print -quit)"
[[ -n "$APK" ]] || fail "Artifact '$ARTIFACT' did not contain an APK."

echo "APK        : $APK"
echo "Opening Android package installer..."
termux-open "$APK"

echo
echo "Package installer launched. Tap Update/Install on the Android prompt."
