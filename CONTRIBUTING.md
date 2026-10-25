# Contributing to mijn.3park

Thanks for taking the time to contribute. This is a small, solo-maintained
Android app, so keeping changes focused and easy to review matters more than
process.

## Branch model

```
feature branches ──► dev ──► main
```

- **`main` is the release branch.** It only moves forward by merging `dev`.
- **`dev` is the integration branch.** Everything lands here first.
- **All pull requests must target `dev`.** PRs opened against `main` are closed
  or retargeted by a maintainer, no matter how small the change.
- Do not push directly to `dev` or `main`; open a PR instead.
- Dependency updates (Renovate) also target `dev` and are auto-merged once CI
  is green.

### The `dev` → `main` cycle

A draft `dev` → `main` PR is always kept open while `dev` collects changes.
Merging it publishes a release, and a workflow then recreates `dev` from
`main` and opens a fresh draft promotion PR for the next cycle. You do not
need to create or reopen that PR yourself — just leave it in draft until the
release is ready.

If the `dev` branch is ever missing, run the **Dev cycle** workflow manually
(`workflow_dispatch`) from `main`; it recreates `dev` and the promotion PR.

The cycle runs as the **release GitHub App** (workflow environment `release`,
configured with `APP_CLIENT_ID` / `APP_PRIVATE_KEY`), not as the built-in
Actions token. That is why its pushes and the promotion PR can trigger other
workflows, and why the App — rather than "GitHub Actions" — must be allowed to
push `dev` if that branch is protected.

## Development setup

1. Install **JDK 17 or newer** (CI uses Temurin) and the Android SDK.
2. Create an untracked `local.properties` with `sdk.dir=…`, or set
   `ANDROID_HOME`.
3. `compileSdk` is read from `app/build.gradle.kts`. From API 37 on the
   platform package is minor-versioned (`platforms;android-37.2`), not
   `platforms;android-37`.

Run the same checks CI runs before you open a PR:

```bash
./gradlew :app:lintDebug --stacktrace
./gradlew :app:testDebugUnitTest --stacktrace
./gradlew :app:assembleDebug --stacktrace
```

Lint is strict: `abortOnError` and `warningsAsErrors` are enabled, so a new
warning fails the build. Fix the warning; only add an `app/lint-baseline.xml`
entry when the finding is unavoidable, and say why in the PR.

Unit tests are JUnit 4 and live in `app/src/test/kotlin/`. Pure logic (see
`Planning` and `Formats`) is testable without Android — prefer extracting new
logic into that shape over writing instrumentation tests.

## Code conventions

- Kotlin, coroutines, OkHttp, Material 3 with dynamic color. Package names stay
  under `com.watermarkhu.mijn3park`.
- User-facing strings live in `res/values/strings.xml`, with Dutch in
  `res/values-nl/`. Mark locale-independent strings `translatable="false"`.
- Use Material 3 color *roles* (`primaryContainer`, `error`,
  `surfaceContainerHighest`) for state, never literal colors. The Dutch plate
  (black on yellow) is the one deliberate exception.
- Never commit credentials, tokens, keystores, or API output containing them.
  If a secret leaks into a PR or log, treat it as compromised and report it
  privately.

`AGENTS.md` documents the 2Park API quirks (the literal `$` in product IDs, the
`dd-MM-yyyy HH:mm:ss` format, same-day-only planned sessions, plate
normalization). Read it before touching `TwoParkApi`, `ParkingService`, or
`Planning`.

## Pull requests

- Target `dev`, keep the change focused, and avoid unrelated refactors or
  formatting churn in the same PR.
- Explain **what** changed and **why**; include before/after screenshots for UI
  changes.
- Keep commit messages short and readable. PR titles and labels feed the
  semantic-release tooling, so `feat:`, `fix:`, and `chore:` prefixes are
  appreciated.
- Draft PRs are welcome for early feedback. The `Qualify` workflow (lint,
  unit tests, debug build) runs for PRs to both `dev` and `main`.
- A maintainer (CODEOWNERS: `@watermarkhu`) reviews before merge.

## Reporting bugs and security issues

Open an issue with the app version, Android version, and the steps to
reproduce. For anything security-sensitive — leaked credentials, payment or
session handling — do not open a public issue; contact the maintainer
privately first.

## License

By contributing you agree that your changes are licensed under the terms of
the repository's [LICENSE](LICENSE).
