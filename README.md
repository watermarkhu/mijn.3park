# mijn.3park

An unofficial Android client for **[mijn.2park.nl](https://mijn.2park.nl)**, a
Dutch parking service. It lets you log in with your existing 2Park account,
pick a product, and turn parking on or off for a license plate, with a
persistent notification while parking is active.

## Features

- **Log in** with your mijn.2park.nl credentials (stored encrypted on-device).
- **Start / stop parking** for any plate, with an ongoing notification.
- **Park until you stop**: the app keeps parking active across midnight, or
  set an explicit end time.
- **Saved plates**: use plates saved on your 2Park account (with names), or
  remember your own locally; add, edit, and remove named plates in-app.
- **Permit (fixed-plate) products** are shown read-only and always-on.
- **Balance & top-up** for prepaid products (payment opens in your browser).
- **History & transactions**: browse past parking actions and balance changes.
- **Settings**: pick a default product and a light/dark/system theme.

## Tech

- Kotlin, coroutines, OkHttp, Material 3 (dynamic color).
- Talks directly to the undocumented mijn.2park.nl JSON endpoints (no backend
  of our own).
- Build config lives in `app/module.toml` (a `module.toml` scaffold, not a
  standard Gradle `build.gradle`).
- Minimum SDK 23.

See [`AGENTS.md`](AGENTS.md) for detailed notes on the 2Park API, its quirks,
and the app architecture.

## Disclaimer

> mijn.3park is an unofficial app and is not affiliated with mijn.2park.nl. It
> is a solo project, built because the official mijn.2park.nl interface is
> awkward to use. No data is sent to third parties. The app connects directly
> to 2Park, and your details are kept only on this device.
