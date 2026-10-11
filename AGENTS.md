# Agent instructions

## Android APK build and local development workflow

- **Do not instruct the user to run `./gradlew`, `gradlew assembleDebug`, or `gradlew testDebugUnitTest` on the Termux host.** Android APK compilation and automated tests run in **GitHub Actions** using the **Build Android APK** workflow.
- Commit and push changes to the intended feature branch. Check the GitHub Actions build and test results for that exact commit; do not claim success until CI confirms it.
- For local device testing from Termux, use the repository's supported installer script, **`development/termux/install_latest_apk.sh`**, from within the bridge repository after fetching and checking out the intended branch:
  ```sh
  git fetch origin
  git switch <feature-branch>
  git pull --ff-only
  development/termux/install_latest_apk.sh
  ```
- The installer selects the **Build Android APK** run for the current branch **and HEAD commit**, waits for an in-progress run, refuses failed builds or missing runs, downloads the `openroadcode-android-bridge-debug` artifact, and opens Android's package installer for user confirmation.
- Never substitute an APK from a different or older commit when the current commit has no successful build. Do not bypass the script by proposing a host-side Gradle build.
- Use `gh run list` / `gh run view` when CI diagnostics are needed; for terminal output on Termux, follow the clipboard instructions below.

## Host-aware command output

When asking a user to share terminal output, adapt commands to the user's host.

- **Android / Termux:** Prefer piping output to `termux-clipboard-set` instead of asking the user to select or manually copy long terminal output. Show a command that captures **stdout and stderr** when diagnostics need both, for example:
  ```sh
  { git status --short; git diff --check; } 2>&1 | termux-clipboard-set
  ```
  Tell the user to paste the clipboard contents into the conversation afterward.
- Check that the Termux:API Android companion app and the `termux-api` package are installed if `termux-clipboard-set` is unavailable. Do not assume it is installed.
- If the user needs to **see** the output as well as copy it, use `2>&1 | tee /dev/stderr | termux-clipboard-set`; note that pipeline exit status can hide failures without `set -o pipefail`.
- On non-Android Linux/macOS/Windows hosts, do not suggest Termux-specific tools. Use ordinary output or a host-appropriate clipboard utility if available.
- Avoid copying tokens, passwords, private messages, or other sensitive material to the clipboard; warn before capturing potentially sensitive output.
- Never run commands on the user's device implicitly. Provide explicit commands for the user to execute.

## Merge safety

During an in-progress merge, do not suggest switching branches, resetting, or committing until conflicts have been resolved and verified. Preserve both sides' functionality, and run relevant tests before completing the merge.

## Port allocation

Before assigning network ports, read OpenRoadCode docs/ethernet_idd.md and search existing code in both repositories. Update the IDD and all affected clients with the implementation. Verify listener binding separately from the saved enabled preference.
