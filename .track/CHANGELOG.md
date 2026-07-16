# Changelog

## [Unreleased]

### Changed
- Fresh fork from upstream/master @ `4b60102` — clean slate
- All prior work saved to `our-fixes` branch
- Restored CI build workflow (`.github/workflows/build.yml`)
- Added `.track/` directory with AGENTS.md, CHANGELOG.md, ARCHITECTURE.md, MEMORY.md, CACHE.md, CODE_FLOW.md
- Added session compaction fix plugin (`.opencode/plugins/compaction-fix.ts`) — preserves task state, decisions, and context across opencode session compactions
- Added `.track/.session-state.json` — auto-managed state snapshot for compaction recovery
- Updated `.track/AGENTS.md` section 8 with compaction recovery protocol
- Updated `.opencode/skills/buckwheat/SKILL.md` with compaction recovery section
- Added `.track/.session-state.json` to `.gitignore`
- Added tag management feature (persistent saved tags via Room `saved_tags` table):
  - New `SavedTag` entity + `SavedTagDao` for persistent tag storage
  - Database migration 5→6 creates `saved_tags` table
  - New `TagsManagementSheet` bottom sheet with scrollable CRUD list (add, edit, delete)
  - "Tag Management" option added to Settings page (opens via gear icon → Settings)
  - Saved tags merged with transaction-derived tags in `SpendsRepository.getAllTags()`
  - Tags survive budget resets (no longer lost when `transactionDao.deleteAll()` is called)
  - New string resources for tag management UI

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
