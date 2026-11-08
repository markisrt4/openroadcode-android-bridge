# Agent instructions

## Mandatory Android app versioning

- For every pull request that changes Android application code, resources, manifest, or packaged behavior, increment `versionCode` in `app/build.gradle` above the target branch's value. Android requires monotonically increasing version codes for APK upgrades.
- For every user-visible feature, bug fix, or behavior change, also update `baseVersion` in `app/build.gradle` using semantic versioning: PATCH for fixes, MINOR for backward-compatible features, MAJOR for breaking changes. Do not rely on the Git SHA suffix as a replacement for the base version.
- Documentation-only, CI-only, or agent-instruction-only pull requests may retain the version values. If a PR contains both app changes and documentation changes, the bump is mandatory.
- Before opening or updating a PR, compare both values against the intended base branch. Multiple commits in one PR need one appropriate bump; do not increment on every commit. Reconcile concurrent PRs so each merged version remains higher than the current base.
- Do not reset or decrease either version, reuse a published versionCode for a different release, or change the debug/release versioning convention without explicitly updating release tooling.
- The GitHub Actions version gate is authoritative. If it fails, update the Gradle version values and push; do not bypass the gate or merge with a failed check.

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

## Port allocation and dedicated service ownership

- Before introducing or changing a listener, inspect both repositories and the project-wide `OpenRoadCode/docs/ethernet_idd.md` registry. Record Companion-owned defaults in `config/ports.json` and update the project-wide IDD in the corresponding OpenRoadCode change.
- Every independent service must have a distinct registered TCP port; never opportunistically reuse an existing listener (including the 8000-8999 range). Prefer an unallocated port above 10000 when suitable, but validate collisions rather than assuming a high port is free.
- Use `127.0.0.1` by default; do not expose diagnostics on the LAN. Document any intentional remote access separately.
- Keep each listener independent of unrelated subsystem enablement. The network diagnostics listener uses `127.0.0.1:18766` and must remain available while the Companion host service runs, regardless of sensor/navigation state. The sensor bridge retains port `8766`.
- Update listener constants, clients, CLI wrappers, docs, and the registry together. CI runs `development/release/check_ports.py` to reject duplicate registrations and mismatched defaults. Do not bypass this check.
- Verify an actual bound listener with its `/health` endpoint; a saved enabled preference or visible diagnostics UI is not proof that the socket is listening.


## Codex execution and CI cost policy

- Do **not** run tests, lint, type checks, builds, quality gates, benchmarks, or validation scripts in the Codex cloud VM by default. This includes indirect invocations via Make, Gradle, npm, shell scripts, pre-commit hooks, or other wrappers.
- Write and update meaningful tests alongside code changes, but delegate their execution to GitHub Actions. For device-dependent verification, give the user exact commands for their Linux, Raspberry Pi, or Android/Termux environment.
- Read GitHub Actions logs, statuses, and downloadable reports to diagnose failures. Make targeted fixes and push them for CI to rerun. Do not rerun tests in the agent VM simply because CI fails.
- Do not execute tests locally even when an existing repository instruction says to run focused checks. This section overrides such instructions for Codex cloud sessions. Static review of source and diffs without running validation tools is allowed.
- Only run a check in the Codex VM if the user **explicitly authorizes that specific execution**. General requests such as 'fix it', 'verify', or 'make sure it works' are not authorization to consume Codex cloud execution for tests.
- Clearly report which checks CI actually ran, which checks are pending, and which require physical hardware. Never imply unexecuted tests passed.
- Do not install dependencies in the Codex VM solely to run checks, tests, builds, or other validation. Dependency installation needed for actual implementation must be justified separately.
- Prefer concise CI failure summaries, JUnit XML artifacts, and the failing job's relevant log excerpt over reading entire workflow logs repeatedly.
- Request or inspect the GitHub Actions result for the exact commit before declaring verification complete. Use the workflow's manually dispatched full gate before merge where configured; do not claim the full gate ran just because fast CI passed.
