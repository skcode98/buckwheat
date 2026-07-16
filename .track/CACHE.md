# Cache — Quick Reference

## Build Commands
```powershell
# Quick compile check (faster than full build)
.\gradlew.bat :app:compileDebugKotlin

# Full debug build (always run this after changes)
.\gradlew.bat assembleDebug

# Clean build
.\gradlew.bat clean assembleDebug

# Run tests
.\gradlew.bat testDebug

# Lint
.\gradlew.bat lintDebug

# Spotless format check
.\gradlew.bat spotlessCheck
```

## Key File Paths (Upstream Master)
| Purpose | Path |
|---------|------|
| Main Activity | `app/.../MainActivity.kt` |
| App ViewModel | `app/.../data/AppViewModel.kt` |
| Spends ViewModel | `app/.../data/SpendsViewModel.kt` |
| Editor ViewModel | `app/.../editor/EditorViewModel.kt` |
| Main Repository | `app/.../di/SpendsRepository.kt` |
| Settings Repository | `app/.../di/SettingsRepository.kt` |
| Room Database | `app/.../di/DatabaseModule.kt` |
| Hilt Module | `app/.../di/AppModule.kt` |
| Transaction DAO | `app/.../data/dao/TransactionDao.kt` |
| Storage DAO | `app/.../data/dao/StorageDao.kt` |
| Keyboard | `app/.../keyboard/Keyboard.kt` |
| Voice Input Parser | `app/.../keyboard/VoiceInputParser.kt` |
| Budget Constructor | `app/.../wallet/BudgetConstructor.kt` |
| Wallet | `app/.../wallet/Wallet.kt` |
| Bottom Sheets | `app/.../home/BottomSheets.kt` |
| Theme | `app/.../ui/Theme.kt` |
| Locale | `app/.../ui/Locale.kt` |
| Export CSV | `app/.../wallet/rememberExportCSV.kt` |
| Manifest | `app/.../AndroidManifest.xml` |
| Gradle (app) | `app/build.gradle.kts` |
| Gradle (root) | `build.gradle.kts` |

## Git Workflow
```powershell
# Our fixes are on the 'our-fixes' branch (origin)
git checkout our-fixes    # to access saved work

# Fresh start on master
git checkout master
git pull upstream master
git push origin master
```

## App Versions
| Library | Version |
|---------|---------|
| Kotlin | 2.2.0 |
| AGP | 8.11.1 |
| Hilt | 2.57 |
| Room | 2.7.2 |
| Compose BOM | 1.8.3 |
| Material3 | 1.3.2 |
| DataStore | 1.1.7 |
| Min SDK | 29 |
| Target SDK | 36 |
| Compile SDK | 36 |
| App Version | 4.8.0 (versionCode 29) |

## Common Imports
```kotlin
import androidx.lifecycle.asFlow            // LiveData → Flow
import androidx.lifecycle.asLiveData         // Flow → LiveData
import androidx.lifecycle.livedata            // build LiveData from Flow
import kotlinx.coroutines.flow.first         // Flow.first() (suspend)
import kotlinx.coroutines.flow.map           // Flow.map
import kotlinx.coroutines.launch             // coroutineScope.launch
import androidx.compose.runtime.livedata.observeAsState  // Compose observation
```

## Key DataStore Keys (budgetDataStore)
- `budget` — current budget (String)
- `spent` — total spent (String)
- `dailyBudget` — daily budget (String)
- `spentFromDailyBudget` — spent from daily budget (String)
- `startPeriodDate` — period start (Long ms)
- `finishPeriodDate` — period finish (Long ms)
- `finishPeriodActualDate` — actual finish (Long ms, set when budget finished early)
- `lastChangeDailyBudgetDate` — last daily budget update (Long ms)
- `currency` — currency code (String)
- `restedBudgetDistributionMethod` — overspend handling (String)
- `hideOverspendingWarn` — overspend warning flag (Boolean)
- `knownTags` — pipe-separated known tags (String)

## Key DataStore Keys (settingsDataStore)
- `theme` — ThemeMode name (String)
- `locale` — locale code (String)
- `TUTOR_*` — tutorial stage booleans
- `autoBackupInterval` — backup interval (Int)
