# Cache — Quick Reference

## Build Commands
```powershell
# Full debug build
.\gradlew.bat assembleDebug

# Clean build
.\gradlew.bat clean assembleDebug

# Run tests
.\gradlew.bat testDebug
```

## Key File Paths
| Purpose | Path |
|---------|------|
| Main Activity | `app/.../MainActivity.kt` |
| App ViewModel | `app/.../data/AppViewModel.kt` |
| Spends ViewModel | `app/.../data/SpendsViewModel.kt` |
| Editor ViewModel | `app/.../editor/EditorViewModel.kt` |
| Main Repository | `app/.../di/SpendsRepository.kt` |
| Settings Repository | `app/.../di/SettingsRepository.kt` |
| Recurring Repository | `app/.../di/RecurringRepository.kt` |
| Room Database | `app/.../di/DatabaseModule.kt` |
| Hilt Module | `app/.../di/AppModule.kt` |
| Transaction DAO | `app/.../data/dao/TransactionDao.kt` |
| Recurring DAO | `app/.../data/dao/RecurringDao.kt` |
| Period DAO | `app/.../data/dao/PeriodDao.kt` |
| Keyboard | `app/.../keyboard/Keyboard.kt` |
| Budget Constructor | `app/.../wallet/BudgetConstructor.kt` |
| Wallet | `app/.../wallet/Wallet.kt` |
| Bottom Sheets | `app/.../home/BottomSheets.kt` |
| Notifications | `app/.../notifications/` |
| Recurring Receiver | `app/.../recurring/RecurringReceiver.kt` |
| Sync Receiver | `app/.../sync/SyncReceiver.kt` |
| Theme | `app/.../ui/Theme.kt` |
| Locale | `app/.../ui/Locale.kt` |
| Export CSV | `app/.../wallet/rememberExportCSV.kt` |
| AppModule (DI) | `app/.../di/AppModule.kt` |
| Manifest | `app/.../AndroidManifest.xml` |
| Gradle (app) | `app/build.gradle.kts` |
| Gradle (root) | `build.gradle.kts` |

## Git Workflow
```powershell
# Our fixes are on the 'our-fixes' branch
git checkout our-fixes

# Start fresh from upstream on master
git checkout master
git pull upstream master
git push origin master
```

## Gradle Config Notes
- Hilt version: 2.57
- AGP version: (check build.gradle.kts)
- Kotlin version: (check build.gradle.kts)
- Room version: (check build.gradle.kts)

## Common Imports
```kotlin
import androidx.lifecycle.asFlow          // LiveData → Flow
import androidx.lifecycle.liveData         // Flow → LiveData
import kotlinx.coroutines.flow.first       // Flow.first() for suspend
import kotlinx.coroutines.launch           // coroutineScope.launch
import kotlinx.coroutines.runBlocking      // AVOID — use suspend instead
```

## Hilt Entry Point for Receivers
```kotlin
@AndroidEntryPoint
class MyReceiver : BroadcastReceiver() {
    @Inject lateinit var myDao: MyDao

    override fun onReceive(context: Context, intent: Intent) { ... }
}
```
