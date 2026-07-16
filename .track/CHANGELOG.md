# Changelog

## [Unreleased]

### Changed
- Fresh fork from upstream/master @ `4b60102` — clean slate
- All prior work saved to `our-fixes` branch
- Restored CI build workflow (`.github/workflows/build.yml`)
- Added `.track/` directory with AGENTS.md, CHANGELOG.md, ARCHITECTURE.md, MEMORY.md, CACHE.md, CODE_FLOW.md

### Known State
- This is a simpler baseline than `our-fixes`:
  - 2 Room tables (transactions, storage) — no recurring, periods, categories yet
  - No notification system, no sync, no reminder
  - These will need to be re-built or ported from `our-fixes`

## Removed (from our-fixes to fresh fork)
- Removed all notification system code (NotificationScheduler, notification channels, AlarmManager)
- Removed recurring transactions (RecurringDao, RecurringRepository, RecurringReceiver)
- Removed period tracking (PeriodDao, Period entity)
- Removed category/tag system (CategoryDao, Category entity, TagCategory)
- Removed sync/export (SyncManager, SyncReceiver)
- Removed reminder system (ReminderReceiver)
- Removed all prior fix commits (saved to our-fixes branch)
