#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

WORKFLOW="Build Android APK"
ARTIFACT="openroadcode-android-bridge-debug"
APK_MIME="application/vnd.android.package-archive"
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
RUN_ID="$(gh run list \
    --repo "$REPO" \
    --workflow "$WORKFLOW" \
    --branch "$BRANCH" \
    --commit "$HEAD_SHA" \
    --limit 1 \
    --json databaseId \
    --jq '.[0].databaseId // empty')"

if [[ -z "$RUN_ID" ]]; then
    echo "No build exists yet for ${HEAD_SHA:0:12}."
    echo "Falling back to the newest successful build on branch '$BRANCH'."
    RUN_ID="$(gh run list \
        --repo "$REPO" \
        --workflow "$WORKFLOW" \
        --branch "$BRANCH" \
        --status success \
        --limit 1 \
        --json databaseId \
        --jq '.[0].databaseId // empty')"
    [[ -n "$RUN_ID" ]] || fail "No successful '$WORKFLOW' run found for branch '$BRANCH'."
else
    STATUS="$(gh run view "$RUN_ID" --repo "$REPO" --json status --jq '.status')"
    CONCLUSION="$(gh run view "$RUN_ID" --repo "$REPO" --json conclusion --jq '.conclusion // ""')"

    if [[ "$STATUS" != "completed" ]]; then
        echo "Build #$RUN_ID is still running. Waiting for it..."
        gh run watch "$RUN_ID" --repo "$REPO" --exit-status
    elif [[ "$CONCLUSION" != "success" ]]; then
        fail "Build #$RUN_ID completed with conclusion '$CONCLUSION'."
    fi
fi

RUN_TITLE="$(gh run view "$RUN_ID" --repo "$REPO" --json displayTitle --jq '.displayTitle')"
RUN_SHA="$(gh run view "$RUN_ID" --repo "$REPO" --json headSha --jq '.headSha')"
RUN_URL="$(gh run view "$RUN_ID" --repo "$REPO" --json url --jq '.url')"

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
termux-open --view --content-type "$APK_MIME" "$APK"

echo
echo "Android package installer should now be open. Tap Install/Update to continue."
