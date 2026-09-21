# Privacy Policy

mijn.3park is an unofficial, open-source Android client for the Dutch parking
service **mijn.2park.nl**. It is a solo, non-commercial project and is **not
affiliated with mijn.2park.nl**.

This policy explains what data the app handles and where it goes. The short
version: the app talks directly to 2Park (and, when you top up, to your
payment provider) and stores everything else on your device. The developer
runs no servers and receives none of your data.

## Who is responsible

The app is maintained by the developer of the `watermarkhu/mijn.3park`
project. There is no company, backend service, or third-party infrastructure
operated by the developer.

## Data stored on your device

The app keeps the following on your device only:

- **Account credentials** (your mijn.2park.nl email and password), stored
  encrypted at rest using Android's Keystore-backed `EncryptedSharedPreferences`
  (AES-256).
- **App state**: your selected and default product, locally remembered license
  plates, your last known account balance, the current parking session, and
  your theme preference.

This data never leaves your device except as described below, and is not
included in Android cloud backups (`allowBackup` is disabled).

## Data sent to mijn.2park.nl

Because the app is a client for 2Park, it sends the following **directly to
mijn.2park.nl** over HTTPS, on your behalf and at your request:

- Your email and password, to log in.
- License plates and parking start/stop requests.
- Saved-plate (favorite) changes.
- Balance and top-up requests.

Your use of the 2Park service is governed by **2Park's own privacy policy and
terms**. This app has no control over how 2Park processes that data.

## Payments (top-up)

When you top up your balance, the app asks 2Park to start a payment and then
opens the resulting payment link in your **system browser**. The payment itself
is handled by 2Park and its payment provider (for example, an iDEAL/banking
flow). The app does not see, collect, or store any payment card, bank, or
transaction details.

## What the app does NOT do

- No analytics, telemetry, tracking, or advertising SDKs.
- No data sent to the developer or to any third party other than 2Park and,
  for payments, 2Park's payment provider.
- No location collection, no contacts access, no device identifiers collected.

## Permissions

- **Internet** — to communicate with mijn.2park.nl.
- **Foreground service / notifications** — to show the ongoing notification and
  keep parking active while a session is running.
- **Exact alarm** — to renew "park until I stop" sessions across midnight.

None of these are used to collect personal data.

## Data retention and deletion

- Logging out clears your stored credentials and app state.
- Uninstalling the app removes all locally stored data.
- To remove data held by 2Park (account, saved plates, history), use the
  mijn.2park.nl website or contact 2Park directly; the app cannot delete it for
  you.

## Children

The app is not directed at children and collects no data for advertising or
profiling.

## Changes to this policy

This policy may be updated as the app changes. Updates will be published in
this file in the project repository, with a new "Last updated" date.

## Contact

Questions about this policy can be raised as an issue in the
`watermarkhu/mijn.3park` repository.
