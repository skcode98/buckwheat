# SECURITY.md — Security Policy & Agent Rules for Buckwheat

> **This file is binding on every agent and every change to this repo.**
> Read it alongside `.track/AGENTS.md`. It was created after a full security
> review on 2026-08-10. Any new code must not regress these rules, and any
> agent discovering a violation must flag it (and fix it if it is safe to do).

---

## 1. Data That Leaves the Device

The ONLY network calls the app may make are:

1. **Voice AI / category AI** — the user's spend *description text* is sent to a
   user-configured OpenAI-compatible provider. Default: `https://openrouter.ai`
   (US provider). This happens **only** when the user has entered their own API
   key in Settings and uses the voice/recording or category feature. The API key
   itself is never transmitted anywhere by the app — it is used only to call the
   provider the user chose.

There is **no** analytics, crash-reporting, telemetry, advertising, or any other
outbound endpoint. There is no server owned by this project.

## 2. Hardcoded Links (FORBIDDEN)

- **Do NOT add any hardcoded URLs, email addresses, or external destinations**
  to app code, resources, or manifests. In 2026-08-10 the original author's
  links (`danilkinkin.com`, `buckwheat.app`, GitHub issues, the crash-report
  email) were removed by user request.
- `openInBrowser` / `sendEmail` utilities were removed. If a link is ever needed
  again it must come from a user-controlled source (e.g. a settings field), never
  a hardcoded constant.
- The only permitted hardcoded URL is the default Voice AI provider URL
  (`DEFAULT_VOICE_AI_PROVIDER_URL` in `di/SettingsRepository.kt`), which is
  replaceable by the user.

## 3. Secrets

- **Voice AI API key** (`voiceAiApiKeyStoreKey` in `settingsDataStore`) is a
  credential. It is:
  - excluded from the app's own backup/export (`asBackupMap()` skips it);
  - excluded from Android cloud backup + device transfer via
    `res/xml/backup_rules.xml` and `res/xml/data_extraction_rules.xml`
    (exclude `file: datastore/settings.preferences_pb`);
  - never logged.
- Do NOT put any secret in `strings.xml`, build files, or committed files. Never
  commit a real API key.
- If a new secret is introduced, follow the same pattern: store in settings
  DataStore, exclude from all backups, never log.

## 4. Permissions & Manifest Rules

- Only the current permissions are allowed: `INTERNET`, `RECORD_AUDIO`,
  `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE`,
  `FOREGROUND_SERVICE_MICROPHONE`. Do not add new permissions without a
  user-facing feature that needs them and a strong justification.
- `android:exported` must be `false` unless the OS requires otherwise
  (appwidget receivers, launcher activity, BOOT_COMPLETED receiver). Never
  export a receiver/activity that doesn't need to be.
- `android:allowBackup="true"` + `fullBackupContent`/`dataExtractionRules`:
  any new sensitive file must be added to both `res/xml/backup_rules.xml` and
  `res/xml/data_extraction_rules.xml`.
- Cleartext HTTP is blocked by default on targetSdk 36 — keep it that way. Do
  not add a `networkSecurityConfig` that permits cleartext.

## 5. Locale / Content Rules

- `values-ru/strings.xml` (and all other `values-*` files) are **valid UTF-8
  with proper Cyrillic/unicode text**. They are NOT corrupted. Earlier notes
  claiming "mojibake / corruption repo-wide" were a false alarm caused by
  reading UTF-8 through PowerShell 5.1's ANSI console. Verify encodings by
  reading raw bytes or decoding as UTF-8 explicitly — never trust garbled
  console output.
- Do not delete or alter localization content for "suspicion" reasons. Russian,
  Ukrainian, Belarusian, etc. are legitimate languages of the app's users.
- When adding new strings, add EN to `res/values/strings.xml` and leave other
  locales to fall back. Never embed literal `"` in a `<string>` value (aapt2
  strips it) — use `\"` or `&quot;`.

## 6. Package & Identity

- The application id / package `com.danilkinkin.buckwheat` is the app's
  identity (Play listing, existing users, deep links). Renaming it is a breaking
  change and is **not** a security fix — do not do it unless the user explicitly
  asks for a full package rename.

## 7. What To Do On A Security Review

1. Search all runtime strings for `http://` / `https://` (allow only the Voice AI
   default) and for hardcoded emails.
2. Review `AndroidManifest.xml` permissions and `exported` flags.
3. Review `res/xml/backup_rules.xml` + `data_extraction_rules.xml` cover every
   sensitive file.
4. Confirm no analytics/ads/tracking SDKs in `app/build.gradle.kts`.
5. Check nothing logs secrets or reads them into logs.
6. Confirm crash logs (CrashLogger) write locally only (Downloads / SharedPrefs),
   with no network destination.

## 8. Golden Pipeline

Every change still runs the golden pipeline before commit:
`gradlew.bat :app:spotlessApply :app:testDebugUnitTest :app:assembleDebug`
(green = all unit tests + APK assembles). Security edits are no exception.
