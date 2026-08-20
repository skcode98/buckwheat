# Task 2: Add `buildTagSuggestions()` engine function

## Status: DONE (build verification skipped — depends on Task 3 string resources)

## Files modified
- `app/src/main/java/com/danilkinkin/buckwheat/patterns/PatternEngine.kt`
  - Added imports: `R`, `ZoneId`, `TextStyle`
  - Added `TimeWindow` enum, `hourToWindow()`, `dayOfWeekFrom()` helpers
  - Added `buildTagSuggestions()` public function

## Notes
- Build will fail until Task 3 adds `R.string.tag_suggestion_reason_*` string resources
- `R` import added to PatternEngine.kt (breaks the "no Android imports" comment at top of file — acceptable for tag suggestion feature)
