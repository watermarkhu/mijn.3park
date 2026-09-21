# AGENTS.md — mijn.3park

Guidance for coding agents working in this repository. `mijn.3park` is an
unofficial Android client for **mijn.2park.nl**, a Dutch parking service. It
lets a user log in, pick a product, and turn parking on/off for a license
plate with a persistent notification, plus manage saved plates and top up the
prepaid balance.

---

## The 2Park web API

- Base URL: `https://mijn.2park.nl`
- Endpoint form: `POST {BASE}/gsmpark-app-www/json/{endpoint}.json`
- Body: `application/x-www-form-urlencoded`
- Auth: **session cookie** (`JSESSIONID`), set by the login call. Keep it in a
  cookie jar and reuse it. Sessions expire; re-login transparently on failure.
- Send `locale=nl_NL` on essentially every call. 2Park is a Dutch-only service,
  so `nl_NL` is hardcoded rather than derived from device locale. Server
  messages come back in Dutch.
- Recommended headers (mimic the browser): `Origin`, `Referer` = base URL,
  a generic `User-Agent`.

### Response envelope

Every response has a status envelope:

```json
{ "status": { "code": { "major": "OK", "minor": "SUCCESS" }, "message": "..." }, "data": { ... } }
```

- `major` is `OK` or `FAIL`. Always check it.
- `minor` is the meaningful sub-code. Known values:
  - `AUTHENTICATED` — successful login
  - `SUCCESS` — successful data call
  - `SESSION_TIMEOUT` — session expired, re-login
  - `INTERNAL_ERROR` — server-side failure (see gotcha below)

### Endpoints in use

| Endpoint | Params | Purpose |
|---|---|---|
| `check_credentials.json` | `email`, `password`, `locale` | Login → `minor=AUTHENTICATED` |
| `get_categories.json` | `locale` | List categories & products |
| `get_category_product_details.json` | `product_id`, `locale` | Members, fixed plate, active actions |
| `get_balance.json` | `product_id`, `locale` | Prepaid balance |
| `start_action.json` | `data` (JSON), `product_id`, `locale` | Start parking |
| `stop_action.json` | `action_id`, `product_id`, `locale` | Stop parking |
| `handle_favorite.json` | `data` (JSON), `product_id`, `locale` | Add/remove named plate |
| `get_upgrade_units.json` | `product_id`, `locale`, `startindex`, `stopindex` | Top-up amounts |
| `start_transaction.json` | `category_id`, `product_id`, `pay_amount`, `locale` | Begin top-up payment |
| `get_available_actions.json` | `product_id` | Count of allowed concurrent actions |
| `force_single_active_action_product.json` | `locale`, `product_id`, `mbr_ident` | Swap the single active plate (not used yet) |

Other endpoints exist in the web bundle (history, grants, APK register,
payment status, thresholds) but are not used by this app.

### Data shapes

- **Products** (`get_categories` → `data.categories[].cty_products[]`):
  - `pdt_id`, `pdt_name`, `pdt_is_blocked` (skip `"true"`), `pdt_options`
  - Category has `cty_id` (needed for top-up) and `cty_name`.
  - Default location lives in the `START` parameter group:
    `pdt_parameter_groups[] where pgp_label=="START"` →
    `pgp_parameters[] where prr_label=="LOCATION"` → `prr_default_value`.
- **Members / saved plates** (`get_category_product_details` → `data.pdt_members[]`):
  - `mbr_identifier` = plate, `mbr_active` (`YES`/`NO`)
  - `mbr_parameters[] where prr_label=="NICKNAME"` → nickname
  - `mbr_actions[] where atn_state=="ACTIVE"` → active action; params include
    `atn_id`, `TIMESTART`, `TIMEEND`, `LOCATION`, `LOC_CODE`.
- **Balance** (`get_balance` → `data.balance.ble_parameters[]`): `AMOUNT`,
  `CURRENCY_DESC`, `LAST_MODIFIED`.
- **Top-up units** (`get_upgrade_units` → `data.upgrade_units[].uut_parameters[]`):
  `PAY_AMOUNT` (e.g. `"10.00"`), `UPGRADE_AMOUNT`, `UPGRADE_DESCRIPTION`.

### Payloads that are JSON-in-a-form-field

`start_action` and `handle_favorite` take a `data` field whose value is a JSON
string:

```jsonc
// start_action
{"action":{"atn_parameters":[
  {"prr_label":"MBR_IDENT","prr_value":"33PBGF"},
  {"prr_label":"TIMESTART","prr_value":"dd-MM-yyyy HH:mm:ss"},
  {"prr_label":"TIMEEND","prr_value":"dd-MM-yyyy 23:59:59"},
  {"prr_label":"LOCATION","prr_value":"EVN_411L"}
]}}

// handle_favorite (edit = remove then add; there is no update action)
{"favorite":{"fav_parameters":[{"prr_label":"NICKNAME","prr_value":"Suzie"}],
  "action":"add","mbr_ident":"33PBGF"}}
```

Date/time format is `dd-MM-yyyy HH:mm:ss` (Dutch day-first), **not** ISO.

---

## Critical gotchas

- **Product IDs contain a literal `$`** (e.g. `EVNTKTK_411L$1013522`). In shell
  testing this must be single-quoted or `%24`-encoded, or the shell eats it and
  the server returns `INTERNAL_ERROR`. In app code it's just a string — no
  escaping needed in a form body. This `$` was the single biggest source of
  confusing `INTERNAL_ERROR`s during reverse engineering.

- **"Today" vs "future days" have different mechanics.** A plain `start_action`
  always ends at `23:59:59` of the current day. There is no native "park until I
  stop it" call. Open-ended parking is emulated in `ParkingService` by
  scheduling an exact alarm just after midnight that re-issues a fresh
  `start_action` for the new day, until the user stops.

- **Start/stop are not trusted blindly — verify.** After `start_action` /
  `stop_action`, re-fetch product details and confirm the member's active state
  (retry a few times with a short delay). The HA integration does this; the app
  mirrors it in `TwoParkApi.start`.

- **Fixed-plate (permit) products.** Products whose `pdt_options` contains
  `FLPN` are permits with a plate bound to the permit. The fixed plate is found
  under `pdt_identifications[].idn_members[]` where `mbr_type=="FLPN"`. It is
  considered "covered" whenever no `LPN` member of that identification is active
  (starting a different plate temporarily overrides/suspends the permit — that's
  what `force_single_active_action_product` is for). In the UI, permits have
  **no balance, no top-up, and no start/stop button**; they are effectively
  read-only and always-on.

- **Balance only exists for prepaid products.** Skip `get_balance` for permits.

- **Top-up handoff is a form POST, not a link.** `start_transaction` returns
  `forwarding_url` + `forwarding_method` + `parameters`; the website builds a
  hidden auto-submit form. To open the system browser (no WebView), perform the
  POST in-app without following redirects and hand the payment provider's
  redirect `Location` to the browser via `ACTION_VIEW`. iDEAL flows then
  deep-link into banking apps.

- **`UPGRADE_DESCRIPTION` is HTML-entity encoded** (e.g. `&#128;10,00` for
  `€10,00`). Prefer building the label from `PAY_AMOUNT`.

- **Plate normalization:** uppercase, strip `-` and spaces. Do this everywhere
  before comparing or sending.

---

## App architecture

Package: `dev.watermarkhu.mijn3park` (not `com.example.*`). Kotlin, coroutines,
OkHttp, Material 3. Build config in `app/module.toml` (this project uses a
`module.toml` scaffold, **not** a standard Gradle `build.gradle`).

- **`TwoParkApi`** — single shared instance (`TwoParkApi.instance`) so the
  activity and the service share one cookie/session. Owns an OkHttp client with
  an in-memory cookie jar (session cookie never persisted to disk), plus a
  `noRedirectClient` variant for the top-up redirect capture. All parsing lives
  here; it returns plain data classes (`Product`, `Member`, `ProductDetails`,
  `Balance`, `TopupForward`).
- **`Prefs`** — `EncryptedSharedPreferences` (AES256, Keystore-backed) holding
  credentials, selected/default product, saved plates, last balance, and the
  active parking session. Store name `mijn3park_secure`.
- **`LoginActivity`** — credentials → login → preselect first product.
- **`MainActivity`** — product dropdown (+ default-product star), Dutch plate
  input, plate chips (fixed plate ★, account favorites, local plates, `+` to
  add), status card with MD3 tonal states, start/stop, top-up menu, favorite
  add/edit/delete dialogs.
- **`ParkingService`** — foreground service with the ongoing notification;
  owns the midnight-renewal alarm and keeps `Prefs`/UI in sync via
  `onStateChanged`.

### Conventions

- UI strings live in `res/values/strings.xml`; Dutch in `res/values-nl/`. Mark
  locale-independent strings `translatable="false"`.
- MD3: rely on dynamic color (no hardcoded `colorPrimary` in the theme). Use
  color *roles* (`primaryContainer`, `error`, `surfaceContainerHighest`) for
  state, not literal colors. The Dutch plate (black-on-yellow) is a deliberate
  skeuomorphic exception.
- Minimum SDK is 23 (required by `EncryptedSharedPreferences`). `deleteSharedPreferences`
  is API 24+, so guard it. targetSdk 30 (exempt from exact-alarm and
  foregroundServiceType requirements — revisit both if the target is raised).

### Security posture (already applied)

- `allowBackup="false"`; credentials encrypted at rest.
- Least-privilege permissions (INTERNET, FOREGROUND_SERVICE, POST_NOTIFICATIONS).
- Non-launcher components are `exported="false"`; `PendingIntent`s are immutable.
- Host checks use `host == "2park.nl" || host.endsWith(".2park.nl")` (never a
  bare `endsWith("2park.nl")`, which matches lookalike domains).
- Not yet done: certificate pinning; R8/minification for release.

---

## Working with the live API

- There is no test/sandbox environment. `start_action`, `stop_action`,
  `handle_favorite`, and especially `start_transaction` mutate a **real
  account** (and top-up creates a real pending payment). Reverse-engineer
  read-only endpoints freely; be cautious with mutations.
- When testing with `curl`, single-quote or `%24`-encode the `$` in product IDs,
  and drive the flow login → categories → details in one session (the cookie
  jar must carry `JSESSIONID`).
- Never commit or log credentials. If credentials appear in a chat or scratch
  file, treat them as compromised and rotate.
