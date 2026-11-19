# Screenshot spend import

Import spend records from receipt/payment screenshots with on-device OCR, optional AI parsing (same backend flow as voice), and per-record review before commit.

## Flow

1. **Entry:** Settings → *Import from screenshot* (`SCREENSHOT_IMPORT_SHEET`, `ScreenshotImportSheet`).
2. **Input:** Pick image (`ActivityResultContracts.GetContent`) or capture photo (`TakePicturePreview`).
3. **OCR:** On-device ML Kit Text Recognition (`com.google.mlkit:text-recognition`, `recognizeTextFromUri` / `recognizeTextFromBitmap` in `screenshotimport/ScreenshotSpendImport.kt`). No image upload required for text extraction.
4. **Parse:** Normalized OCR text is parsed to spend drafts:
   - **AI path (when configured):** `callAi(context, systemPrompt, userPrompt)` from `ai/AiBackend.kt` using the same provider URL/key/model and master AI toggle as voice (`Settings → Voice AI` / AI intelligence). Response parsed with `keyboard/VoiceAi.parseVoiceAiContents` (JSON array/object contract compatible with voice: `{amount, comment, date}`).
   - **Offline fallback:** `keyboard/VoiceInputParser.parseVoiceInputs(text)` when AI is `NotConfigured`, on `Failure`, or when AI returns no usable records. Drafts are built/validated via `util/numberExtensions.parseAmountToBigDecimal` (positive amounts only).
5. **Review (1-by-1, no auto-commit):** User can edit amount/comment/date, **Skip**, **Delete**, **Add manually**, navigate **Next**, and explicitly **Add this record**. Each committed record is added individually via `SpendsViewModel.addSpent(Transaction(type=SPENT,...))` (same commit primitive as editor/voice). Nothing is bulk-imported on parse.

## Notes

- AI/offline semantics mirror voice: configured AI attempted through shared router; fallback to offline parser on not-configured/failure/empty parse; AI errors surfaced in UI.
- Amounts normalized with `parseAmountToBigDecimal`; non-positive/invalid amounts cannot be committed.
- Dates accept AI/relative formats through existing voice parsing (`parseVoiceAiDate`) and can be adjusted in review.
- Works with receipts, payment app screenshots, and statements; totals/balances may be filtered by prompt but user must review before commit.
- Existing voice, CSV import/export, backup, and other flows are unchanged.

## Key files

- `app/src/main/java/.../screenshotimport/ScreenshotSpendImport.kt` — OCR, prompts, AI/offline parsing, draft mapping.
- `app/src/main/java/.../screenshotimport/ScreenshotImportSheet.kt` — pick/capture, progress/errors, review UI.
- `app/src/main/java/.../home/BottomSheets.kt` — sheet registration.
- `app/src/main/java/.../settings/Settings.kt` — entry point.
- `app/src/main/res/values/strings.xml` — UI strings.
- `gradle/libs.versions.toml`, `app/build.gradle.kts` — ML Kit dependency.
- Tests: `app/src/test/java/.../screenshotimport/ScreenshotSpendImportTest.kt` (pure helpers) plus existing `keyboard`/`ai` parser tests. Run `./gradlew.bat :app:assembleDebug` and `./gradlew.bat testDebugUnitTest`.
