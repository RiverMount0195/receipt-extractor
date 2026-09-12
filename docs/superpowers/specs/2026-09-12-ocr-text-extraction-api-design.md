# OCR Text Extraction API — Design

## Context

`receipt-extractor` is a freshly scaffolded Spring Boot app with no business
logic yet. The first feature to build is the foundation the rest of the
project depends on: given an image, extract all the text it contains,
including Vietnamese, and expose that over an HTTP API. This does not yet
parse receipts into structured fields (merchant, total, date, etc.) — it is
strictly "image in, raw extracted text out." Structured receipt parsing is a
follow-on feature built on top of this once raw extraction works.

OCR is performed with [Tesseract](https://github.com/tesseract-ocr/tesseract)
via the [Tess4J](http://tess4j.sourceforge.net/) Java binding, per explicit
request.

## Architecture & components

- **`OcrController`** — exposes `POST /api/ocr/extract`. Accepts a multipart
  file upload, validates it, delegates to `OcrService`, and returns the
  result as JSON.
- **`OcrService`** — wraps a Tess4J `ITesseract` instance. Takes image bytes
  in, returns the extracted text as a `String`. Contains no HTTP concerns.
- **`TesseractConfig`** — a `@Configuration` class that builds a single
  `ITesseract` bean at startup, configured with:
  - `setDatapath(...)` pointing at the tessdata directory
  - `setLanguage("vie+eng")` so both Vietnamese and English are recognized
    in the same pass (receipts often mix Vietnamese text with English brand
    names and numerals)

  Both the tessdata path and the language string are externalized to
  `application.properties` (`ocr.tessdata-path`, `ocr.languages`) with
  sensible defaults, so they can be changed without recompiling.
- **`OcrResponse`** — response DTO: `{ "text": "<extracted text>" }`.

## Data flow

1. Client sends `POST /api/ocr/extract` with a multipart field (`file`)
   containing a JPEG or PNG image.
2. `OcrController` validates the upload:
   - file is present and non-empty
   - content-type is `image/jpeg` or `image/png`
3. Controller passes the file's bytes to `OcrService.extractText(byte[])`.
4. `OcrService` writes the bytes to a temporary file (Tess4J's `doOCR`
   requires a `File`), calls `tesseract.doOCR(file)`, and deletes the temp
   file in a `finally` block regardless of outcome.
5. Controller wraps the returned string in an `OcrResponse` and returns
   `200 OK`.

## Error handling

- Missing or empty file → `400 Bad Request`, `{"error": "file is required"}`.
- Unsupported content-type → `400 Bad Request`,
  `{"error": "unsupported file type"}`.
- Tesseract failure (`TesseractException`, corrupt/unreadable image, etc.) →
  caught (in the controller or a `@ControllerAdvice`), logged server-side
  with the full stack trace, and returned to the client as
  `500 Internal Server Error` with a generic error body — internal details
  are not leaked to the client.

## Testing

- **`OcrServiceTest`** (unit): a small sample image fixture with known text
  (including at least one Vietnamese word/phrase) checked into
  `src/test/resources`. Assert the extracted text *contains* expected
  substrings rather than an exact match, since OCR output has minor
  whitespace/formatting variance.
- **`OcrControllerTest`** (`@WebMvcTest`/`MockMvc`): upload the same fixture
  via multipart, assert `200 OK` and that the JSON `text` field contains the
  expected substring. A second test uploads a non-image file and asserts
  `400 Bad Request`.

## Setup dependency (not code)

Tesseract requires real trained-data model files (`eng.traineddata`,
`vie.traineddata`) to recognize each language — these are binary ML model
files, not something producible by writing code. They must be downloaded
from the official
[tesseract-ocr/tessdata](https://github.com/tesseract-ocr/tessdata)
repository and placed under `src/main/resources/tessdata/` before the OCR
feature will work end-to-end. This is called out explicitly as a setup step
in the implementation plan rather than left implicit.

## Out of scope (for this spec)

- Structured receipt field extraction (merchant, line items, totals, dates).
- Async/queued processing — synchronous request/response is sufficient at
  current scale (single small image per request).
- Authentication/authorization on the endpoint.
- Support for PDF or other non-image input formats.
