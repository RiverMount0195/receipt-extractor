# Telegram Webhook Integration — Design

## Context

`receipt-extractor` currently exposes `POST /api/ocr/extract`, a multipart
endpoint that OCRs a receipt image, detects `bankSource`/`amount`/`message`,
and appends a row to a Google Sheet (`OcrController`, `OcrService`,
`BankSourceDetector`/`AmountDetector`/`MessageDetector`, `SheetRowAppender`).

This feature adds a second entry point into that same pipeline: a Telegram
bot webhook. The user sends a photo of a receipt (optionally with a caption)
to the bot in a Telegram chat; the app extracts bank source, amount, and
message the same way `/api/ocr/extract` does, applies the caption as an
override for the message if present, appends the row to the sheet, and
replies in the chat with a confirmation.

The Telegram bot itself (webhook registration, bot token issuance) is
already configured outside this repo — this spec covers only the receiving
endpoint and the outbound Telegram API calls (fetching the photo, sending
replies).

## Architecture & data flow

New package `com.tung.receipt_extractor.telegram`, parallel to `ocr` and
`sheets`.

`POST /api/telegram-webhook` (Telegram calls this synchronously for every
update):

1. Controller reads the raw JSON request body and logs it at `info` level
   before doing anything else — this is the only place the raw Telegram
   payload is captured, useful for debugging webhook behavior.
2. Parses the body into a `TelegramUpdate` DTO (unknown fields ignored).
3. If there is no `message` on the update (e.g. `edited_message`,
   `channel_post`, or any other update type), return `200 OK` with no
   further action and no chat reply.
4. If the message's chat id does not match the configured
   `telegram.allowed-chat-id`, return `200 OK` with no further action and no
   chat reply.
5. If the message has no `photo`, reply in-chat with
   `"Please send a photo of your receipt."` and return `200 OK`.
6. Otherwise, pick the largest entry in `photo` (by width), call Telegram's
   `getFile` API to resolve a `file_path`, then download the image bytes
   from Telegram's file endpoint.
7. Run `ReceiptExtractionService.extract(bytes)` (see refactor below) to get
   `bankSource`, `amount`, and an OCR-derived `message`.
8. If the Telegram message has a non-blank `caption`, it overrides the
   OCR-derived `message` for this row.
9. Call `SheetRowAppender.appendRow(bankSource, amount, message)` (existing,
   unchanged, already best-effort/non-throwing).
10. Reply in-chat with a confirmation summarizing what was recorded.
11. Any exception raised in steps 6-10 is caught, logged, answered with a
    generic in-chat error message, and the endpoint still returns `200 OK`.

The webhook never returns a non-2xx status — every code path is caught and
answered with `200 OK`, so a transient failure never causes Telegram to
retry-storm the endpoint.

### Refactor: `ReceiptExtractionService`

The OCR-to-detections logic currently lives inline in
`OcrController.extractText` (bytes → `OcrService.extractText` →
`BankSourceDetector`/`AmountDetector`/`MessageDetector` → `OcrResponse`).
This is extracted into a new `ReceiptExtractionService` (in the `ocr`
package) with one method:

```java
OcrResponse extract(byte[] imageBytes) throws TesseractException, IOException
```

`OcrController` is updated to delegate to this service instead of wiring the
detectors itself, then continues to call `SheetRowAppender.appendRow` as it
does today. `TelegramWebhookController` calls the same service, then applies
the caption-override logic before calling `SheetRowAppender.appendRow`
itself. This avoids duplicating the detector-wiring logic across the two
controllers.

## Components

- **`TelegramUpdate`, `TelegramMessage`, `TelegramChat`, `TelegramPhotoSize`**
  — Jackson record DTOs for the incoming webhook JSON body.
  `@JsonIgnoreProperties(ignoreUnknown = true)` on each, with explicit
  `@JsonProperty` mappings for Telegram's snake_case fields (`file_id`,
  `chat`, `photo`, `caption`, `text`, `message`). Only the fields this
  feature needs are modeled; everything else in Telegram's schema is
  ignored.
- **`TelegramProperties`** — config record: `botToken`, `allowedChatId`,
  bound from `telegram.bot-token` / `telegram.allowed-chat-id` via Spring's
  relaxed binding (env vars `TELEGRAM_BOT_TOKEN` / `TELEGRAM_ALLOWED_CHAT_ID`)
  — same pattern as `SheetsProperties`. If either is unset, the app fails to
  start with a placeholder-resolution error, same as the existing Sheets
  properties; tests set dummy values via `@TestPropertySource`.
- **`TelegramClient`** — wraps a Spring `RestClient` bean (already available
  transitively via `spring-boot-starter-webmvc`; no new dependency) against
  Telegram's Bot API (`https://api.telegram.org/bot<token>/...`):
  - `String getFilePath(String fileId)` — calls `getFile`, returns the
    resolved `file_path`.
  - `byte[] downloadFile(String filePath)` — downloads from
    `https://api.telegram.org/file/bot<token>/<file_path>`.
  - `void sendMessage(long chatId, String text)` — calls `sendMessage`.
- **`TelegramWebhookController`** — the `POST /api/telegram-webhook`
  endpoint, orchestrating the flow above using `TelegramClient`,
  `ReceiptExtractionService`, and `SheetRowAppender`.
- **`ReceiptExtractionService`** (new, in `ocr` package) — described above.

No new response DTO for the webhook endpoint — it always returns an empty
`200 OK` body, since Telegram does not consume the response payload.

## Configuration

`application.yml` gains:

```yaml
telegram:
  bot-token: ${TELEGRAM_BOT_TOKEN}
  allowed-chat-id: ${TELEGRAM_ALLOWED_CHAT_ID}
```

Two new required environment variables, `TELEGRAM_BOT_TOKEN` and
`TELEGRAM_ALLOWED_CHAT_ID`, alongside the existing five `SHEETS_*` variables
— same "app fails to start until set" behavior, same
`deploy/sheets-env.yaml`-style file for Cloud Run (or that file is renamed/
extended to cover both integrations — left to the implementation plan).

## Reply messages

- Success: `"Recorded: <bankSource>, <amount>, <message>"`, substituting
  `"unknown"` for a null/blank `bankSource` or `message`, and `"unknown"` for
  a null `amount` — mirrors how `SheetRowAppender` already blanks nulls when
  writing the row.
- No photo in the message: `"Please send a photo of your receipt."`
- Any failure downloading the photo, running OCR, or otherwise processing
  the update: `"Sorry, couldn't process that image. Please try again."` —
  the full exception is logged server-side at `error` level; the chat
  message stays generic.
- Wrong/missing chat id, or an update with no `message` field: no reply is
  sent at all.

## Error handling

- `TelegramWebhookController` wraps the entire per-update processing (steps
  6-10 above) in a single try/catch. Any exception is logged at `error`
  level with the chat id and update id as context, answered with the
  generic failure reply, and swallowed — never propagated as a non-2xx
  response.
- `SheetRowAppender.appendRow` is unchanged and already swallows its own
  exceptions, so a Sheets outage never blocks the confirmation reply.
- A failure sending the Telegram reply itself (e.g. `sendMessage` call
  fails) is logged and swallowed — the webhook still returns `200 OK`.
- Malformed JSON in the request body (fails to parse into `TelegramUpdate`)
  is logged (the raw body was already logged per step 1) and answered with
  `200 OK`, no reply (there is no chat id to reply to).

## New dependencies (`build.gradle`)

None. `RestClient` and Jackson are already available transitively via
`spring-boot-starter-webmvc`.

## Testing

- **`ReceiptExtractionServiceTest`** (unit) — moves/adapts the existing
  detector-wiring assertions currently implicit in `OcrControllerTest` and
  `OcrServiceTest` onto the new service directly.
- **`OcrControllerTest`** (existing, updated) — asserts the controller
  delegates to `ReceiptExtractionService` and still calls
  `SheetRowAppender.appendRow` and returns `OcrResponse` exactly as before;
  behavior is unchanged, only the internal wiring moves.
- **`TelegramWebhookControllerTest`** (new, MockMvc) — with `TelegramClient`,
  `ReceiptExtractionService`, and `SheetRowAppender` mocked, covers: photo
  with caption (caption wins), photo without caption (OCR message used), no
  photo (error reply, no sheet append), wrong chat id (no reply, no
  processing), no `message` on the update (no reply), and an exception from
  `ReceiptExtractionService` or `TelegramClient` (generic error reply, `200
  OK` still returned).
- **`TelegramClientTest`** (new) — verifies request construction/response
  parsing for `getFilePath`, `downloadFile`, and `sendMessage` against a
  mocked HTTP layer (e.g. `RestClient` built on `MockRestServiceServer` or
  an equivalent test double).
- No test calls the real Telegram Bot API or requires a real bot token;
  `telegram.bot-token` / `telegram.allowed-chat-id` get dummy
  `@TestPropertySource` values, matching the existing `SHEETS_*` test setup.

## Out of scope (for this spec)

- Correlating a photo message with a separate, later text message — only a
  caption on the same message can override the extracted message.
- Any reply/interaction beyond the single confirmation or error message
  (e.g. `/start`, help text, inline buttons, editing a previous reply).
- Rate limiting or dedup of Telegram updates (e.g. Telegram redelivering an
  update after a slow response) — each update is processed independently.
- Multiple allow-listed chats/users — one configured chat id for the whole
  app.
- Any change to webhook registration, HTTPS certificate setup, or Cloud Run
  exposure/auth configuration — already handled outside this repo.
