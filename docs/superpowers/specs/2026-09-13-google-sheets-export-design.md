# Google Sheets Export — Design

## Context

`receipt-extractor` currently extracts OCR text from a receipt image and
parses out `bankSource`, `amount`, and `message` (`OcrResponse`, built by
`OcrController`). This feature adds a side effect to that flow: after each
successful extraction, append a row summarizing the result to a Google
Sheet, so extracted transfers accumulate in a spreadsheet without any manual
copy-paste.

This is additive to the existing OCR flow, not a replacement or a new
endpoint — there is no separate "export" trigger. The credential used to
write to the Sheet is the user's own personal Google account (via an OAuth2
refresh token obtained once through Google's OAuth Playground), not a
service account.

## Architecture & components

New package `com.tung.receipt_extractor.sheets`, parallel to the existing
`ocr` package:

- **`SheetsConfig`** — a `@Configuration` class that builds a single `Sheets`
  API client bean at startup. Credentials are loaded via
  `UserCredentials.newBuilder()` (Google auth library) using a client ID,
  client secret, refresh token, and initial access token, all read from an
  external properties file (see "Credentials file" below) — mirrors how
  `TesseractConfig` builds one `ITesseract` bean from externalized config.
- **`SheetRowAppender`** — one method,
  `appendRow(String bankSource, Long amount, String message)`. Builds a row
  `[bankSource, amount, message, Instant.now()]` — matching the target
  sheet's actual column order (Bank source, Amount, Message, Timestamp) —
  and calls `Sheets.Spreadsheets.Values.append` against the configured
  spreadsheet ID and sheet/tab name. Takes the `Sheets` client as a
  constructor dependency (not a static/global), so it can be mocked in
  tests.
- No new response DTO — `OcrResponse` is unchanged.

## Credentials file

Unlike `ocr.tessdata-path`/`ocr.languages` (non-secret, live in
`application.properties`), everything needed to reach the Sheets API and
identify the target spreadsheet is treated as secret and must not be
committed:

- A new file, `config/sheets-credentials.properties`, holds `client-id`,
  `client-secret`, `refresh-token`, `access-token`, `spreadsheet-id`, and
  `sheet-name`. `access-token` seeds `UserCredentials` with an initial token
  (via `.setAccessToken(...)`) so it doesn't need an eager refresh call
  before the first use; the library auto-refreshes using the refresh token
  once that expires.
- `config/` is added to `.gitignore`.
- A checked-in `config/sheets-credentials.properties.example` template uses
  placeholder/dummy values for every key — including `spreadsheet-id` and
  `sheet-name` — documenting the required keys for onboarding without
  leaking the real ones.
- No default/fallback value for any of these six keys is hardcoded in any
  committed file (`application.properties`, Java source, etc.) — e.g. no
  `@Value("${sheets.spreadsheet-id:1_d5IR...}")`-style fallback. The only
  place the real values exist is the gitignored properties file.
- `application.properties` gets one new, non-secret property:
  `sheets.credentials-path`, defaulting to
  `config/sheets-credentials.properties` — same externalization pattern as
  `ocr.tessdata-path`. `SheetsConfig` reads this file directly (via
  `java.util.Properties`) at startup rather than binding it through Spring's
  own config-file mechanisms, since it is intentionally outside the
  `application.properties`/profile system.

## Obtaining the refresh token (setup step, not code)

Entirely a one-time, in-browser task — no code in this repo performs the
OAuth consent flow:

1. In Google Cloud Console, create an OAuth 2.0 Client ID (type "Web
   application") with redirect URI
   `https://developers.google.com/oauthplayground`.
2. In [Google's OAuth Playground](https://developers.google.com/oauthplayground),
   open settings and check "Use your own OAuth credentials," pasting the
   client ID/secret from step 1.
3. Authorize scope `https://www.googleapis.com/auth/spreadsheets`, signing in
   as the personal Google account that owns/edits the target Sheet.
4. Exchange the authorization code for tokens; copy the refresh token and
   access token shown.
5. Fill in `config/sheets-credentials.properties` with the client ID, client
   secret, refresh token, access token, target spreadsheet ID, and sheet/tab
   name.

This is called out as an explicit setup step in the implementation plan,
the same way tessdata model files are for OCR.

## Data flow

1. `OcrController.extractText` runs exactly as it does today through
   building `OcrResponse` (OCR + bank source + amount + message detection).
2. Immediately before returning, it calls
   `sheetRowAppender.appendRow(bankSource, amount, message)` synchronously.
3. The controller returns the `OcrResponse` with `200 OK` regardless of
   whether step 2 succeeded.

No async/queueing is introduced — call volume is low (one receipt at a
time), consistent with the existing synchronous OCR flow.

## Error handling

- `SheetRowAppender.appendRow` catches any exception raised by the Sheets
  API call (auth failure, network error, invalid spreadsheet ID, quota
  error, etc.) internally, logs it at `error` level including the
  spreadsheet ID and sheet name as context (useful for debugging failures),
  but never logs `client-id`, `client-secret`, `refresh-token`, or
  `access-token`, and returns normally.
- It never throws out to `OcrController`. The Sheets integration is
  best-effort/secondary — a Sheets outage or misconfiguration must never
  turn a successful OCR extraction into a failed API response.

## New dependencies (`build.gradle`)

- `com.google.apis:google-api-services-sheets` — generated Sheets API client.
- `com.google.auth:google-auth-library-oauth2-http` — `UserCredentials` and
  HTTP request initialization from a refresh token.

## Testing

- **`SheetRowAppenderTest`** (unit): construct with a mocked `Sheets` client
  (via its builder chain), call `appendRow`, and verify the request is built
  with a `ValueRange` containing the expected row values in order. A second
  test makes the mocked client throw and asserts `appendRow` swallows it
  (returns normally, no exception propagates).
- **`OcrControllerTest`** (existing, extended): with `SheetRowAppender`
  mocked to throw, assert the endpoint still returns `200 OK` with the
  correct `OcrResponse` body — proving the OCR response is unaffected by a
  Sheets failure.
- No test hits the real Google Sheets API or requires real credentials.

## Out of scope (for this spec)

- Any endpoint or trigger to append/export outside the automatic
  post-extraction hook (e.g., manual re-sync, bulk export of past receipts).
- Multiple target spreadsheets/sheets per request — one configured
  spreadsheet + tab for the whole app.
- Reading/updating existing rows — append-only.
- Retrying failed appends — a failure is logged and dropped.
- Token refresh-token rotation/expiry handling beyond what the Google auth
  library does automatically (refresh tokens obtained this way do not expire
  under normal use, only if revoked).
