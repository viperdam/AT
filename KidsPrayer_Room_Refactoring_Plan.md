# Refactoring Plan: SharedPreferences to Room Database - KidsPrayer App

**Overall Goal:** Replace all `SharedPreferences` usage with a Room database to provide a robust, scalable, and modern data persistence layer, accommodating existing features and the future prayer reward system.

**General Strategies to Minimize Build Errors & Manage Complexity:**
*   **Incremental Changes:** Implement and test small parts of the plan at a time.
*   **Version Control:** Commit frequently after each successful step or feature refactor. Use branches for significant phases.
*   **Compile Often:** After creating new files or making significant changes, compile to catch errors early.
*   **Bottom-Up for Core DB:** Implement Entities -> DAOs -> Database -> Repository -> DI Modules first. This core should compile before refactoring application logic.
*   **One Feature at a Time:** When refactoring existing SharedPreferences usage, pick one settings group or one feature (e.g., LockScreen settings), refactor it completely to use Room, test it thoroughly, then move to the next.
*   **Interfaces & Stubs:** Define interfaces (DAOs, Repository) early. Mock implementations can be used for testing business logic before full DB integration.
*   **Dependency Management:** Ensure Room, coroutine, and Hilt (if used) dependencies are correctly added and versions are compatible. Check the official Android documentation for the latest stable versions.
*   **Careful Nullability:** Be mindful of data coming from SharedPreferences (which might have defaults if keys are missing or values are not explicitly set) and how it maps to potentially non-null fields in Room entities. Define clear default strategies.
*   **Contextual Awareness for Services/Receivers:** Services and BroadcastReceivers might require careful handling of context and coroutine scopes for database operations.

---

**Phase 1: Preparation & Design**

*   [ ] **1.1. Finalize Room Adoption Decision:**
    *   [ ] Confirm Room is the chosen solution for all persistent data (settings and reward system).
    *   **Affected Files:** None directly. This is a planning step.
    *   **New Files:** None.
    *   **Build/Integration Notes:** This decision influences the entire plan's direction.

*   [ ] **1.2. Identify All SharedPreferences:**
    *   [ ] Thoroughly list every `SharedPreferences` file name currently used (e.g., from grep results: `location_receiver_prefs`, `prayer_receiver_prefs`, `KidsPrayerPrefs`, `ad_settings_prefs`, `prayer_prefs`, `prayer_settings`, `prayer_scheduler_prefs`, `prayer_worker_prefs`, `lock_screen_prefs`, `prayer_validator_prefs`, etc.).
    *   [ ] For each preference file, meticulously list all keys, their current data types (String, Boolean, Int, Float, Long, Set<String>), and their default values if any are implied in the code.
    *   **Affected Files:** This step requires reading through potentially many existing Kotlin files in `app/src/main/java/com/viperdam/kidsprayer/` (especially in `service/`, `util/`, `ui/`, `receivers/`, `PrayerApp.kt`, `viewmodels/`, etc.).
    *   **New Files:** Create a temporary document (e.g., `SharedPreferences_Audit.md` or a spreadsheet) to track findings.
    *   **Build/Integration Notes:** Accuracy here is critical for designing entities and the migration process. Missing a key or misidentifying a type can lead to data loss or runtime errors.

*   [ ] **1.3. Design Room Database Schema:**
    *   **1.3.1. Settings Data (Option A - Specific Entities Recommended):**
        *   [ ] Define `AdSettingsEntity` (e.g., `id (PK, autoGenerate=true)`, `adsEnabled: Boolean`, `lastAdShownTimestamp: Long`). Location: `app/src/main/java/com/viperdam/kidsprayer/data/database/entity/AdSettingsEntity.kt`
        *   [ ] Define `LockScreenSettingsEntity` (e.g., `id (PK)`, `lockScreenEnabled: Boolean`, `pin: String?`, `autoLockTimeMinutes: Int`). Location: `app/src/main/java/com/viperdam/kidsprayer/data/database/entity/LockScreenSettingsEntity.kt`
        *   [ ] Define `PrayerCalculationSettingsEntity` (e.g., `id (PK)`, `calculationMethod: String`, `asrJuridicMethod: String`, `latitude: Double`, `longitude: Double`, `manualLocationName: String?`). Location: `app/src/main/java/com/viperdam/kidsprayer/data/database/entity/PrayerCalculationSettingsEntity.kt`
        *   [ ] Define `PrayerNotificationSettingsEntity` (e.g., `prayerName: String (PK)` (Fajr, Dhuhr, etc.), `notificationEnabled: Boolean`, `soundUri: String?`, `preNotificationOffsetMinutes: Int`). Location: `app/src/main/java/com/viperdam/kidsprayer/data/database/entity/PrayerNotificationSettingsEntity.kt`
        *   [ ] Define `GeneralAppSettingsEntity` (e.g., `id (PK)`, `isFirstLaunch: Boolean`, `appTheme: String`, `language: String`). Location: `app/src/main/java/com/viperdam/kidsprayer/data/database/entity/GeneralAppSettingsEntity.kt`
        *   [ ] *Add other specific entities as needed based on the audit in 1.2.*
    *   **1.3.2. Prayer Reward System Data:**
        *   [ ] Define `ChildProfileEntity` (e.g., `childId: Long (PK, autoGenerate=true)`, `name: String`, `avatarUri: String?`, `totalPoints: Int`). Location: `app/src/main/java/com/viperdam/kidsprayer/data/database/entity/ChildProfileEntity.kt`
        *   [ ] Define `PrayerLogEntity` (e.g., `logId: Long (PK, autoGenerate=true)`, `childIdFk: Long`, `prayerName: String`, `dateCompleted: Long (timestamp)`, `timeCompleted: String` (e.g., "HH:mm"), `status: String` (e.g., "COMPLETED", "MISSED"), `pointsAwarded: Int`). Indices on `childIdFk` and `dateCompleted`. Location: `app/src/main/java/com/viperdam/kidsprayer/data/database/entity/PrayerLogEntity.kt`
        *   [ ] Define `RewardEntity` (e.g., `rewardId: Long (PK, autoGenerate=true)`, `name: String`, `description: String`, `pointCost: Int`, `imageUrl: String?`, `isActive: Boolean`). Location: `app/src/main/java/com/viperdam/kidsprayer/data/database/entity/RewardEntity.kt`
        *   [ ] Define `RedeemedRewardEntity` (e.g., `redemptionId: Long (PK, autoGenerate=true)`, `childIdFk: Long`, `rewardIdFk: Long`, `dateRedeemed: Long (timestamp)`, `pointsSpent: Int`). Indices on `childIdFk`, `rewardIdFk`. Location: `app/src/main/java/com/viperdam/kidsprayer/data/database/entity/RedeemedRewardEntity.kt`
    *   **1.3.3. MediaPipe Related Data (If any persistent beyond simple flags):**
        *   [ ] Example: `MediaPipeConfigEntity` (e.g., `id (PK)`, `preferredCamera: String`, `confidenceThreshold: Float`). If just simple flags, include in `GeneralAppSettingsEntity` or a new specific settings entity. Location: `app/src/main/java/com/viperdam/kidsprayer/data/database/entity/MediaPipeConfigEntity.kt`
    *   **1.3.4. Relationships & Indices:**
        *   [ ] Define foreign keys (e.g., in `PrayerLogEntity` for `childIdFk` referencing `ChildProfileEntity.childId`).
        *   [ ] Identify columns that will be frequently queried and add `@Index` annotations to entities for them.
    *   **Affected Files:** None directly from design. Output is the schema definition.
    *   **New Files:** Conceptual definitions for Kotlin data classes for entities.
    *   **Build/Integration Notes:** This schema is the blueprint for your database. Think about data types, nullability, primary keys, and potential future needs.

*   [ ] **1.4. Define Data Access Objects (DAOs):**
    *   [ ] For each entity (or logical group), define a DAO interface.
    *   [ ] Example DAO for settings: `GeneralAppSettingsDao`, `LockScreenSettingsDao`, `PrayerNotificationSettingsDao` etc.
    *   [ ] Example DAO for reward system: `ChildProfileDao`, `PrayerLogDao`, `RewardDao`, `RedeemedRewardDao`.
    *   [ ] List all required CRUD methods (e.g., `insert(entity)`, `update(entity)`, `delete(entity)`, `getById(id): Flow<Entity?>`, `getAll(): Flow<List<Entity>>`).
    *   [ ] List specific query methods (e.g., `PrayerLogDao.getPrayerLogsForChild(childId: Long, startDate: Long, endDate: Long): Flow<List<PrayerLogEntity>>`, `ChildProfileDao.updatePoints(childId: Long, newPoints: Int)`).
    *   **Affected Files:** None directly from design.
    *   **New Files:** Conceptual definitions for Kotlin interfaces for DAOs.
    *   **Build/Integration Notes:** DAO methods will be annotated with Room annotations (`@Insert`, `@Query`, etc.) in Phase 2. Plan for `Flow` return types for reactive updates.

*   [ ] **1.5. Define Room Database Class:**
    *   [ ] Create the abstract class extending `RoomDatabase` (e.g., `KidsPrayerDatabase`).
    *   [ ] Declare abstract methods for each DAO.
    *   [ ] Define the database version (start with `1`).
    *   [ ] List all entities in the `@Database` annotation.
    *   **Affected Files:** None directly from design.
    *   **New Files:** Conceptual definition for `KidsPrayerDatabase.kt`.
    *   **Build/Integration Notes:** This class ties all components of the database together.

*   [ ] **1.6. Dependency Injection Strategy (Hilt Recommended):**
    *   [ ] Plan Hilt modules for providing `Context`, `KidsPrayerDatabase`, DAOs, and Repositories.
    *   **Affected Files:** `app/build.gradle.kts` (add Hilt plugin and dependencies), `gradle/libs.versions.toml` (if using version catalog).
    *   **New Files:** Conceptual definitions for Hilt modules (e.g., `DatabaseModule.kt`, `RepositoryModule.kt`) in a `di` package.
    *   **Build/Integration Notes:** Hilt simplifies providing dependencies throughout the app.

*   [ ] **1.7. Create a Repository Layer:**
    *   [ ] Plan repository interfaces and implementations (e.g., `SettingsRepository`, `RewardRepository`).
    *   [ ] Repositories will inject DAOs and provide a clean API for ViewModels/UseCases.
    *   [ ] Define methods in the repository (e.g., `suspend fun saveLockScreenPin(pin: String)`, `fun getPrayerLogs(childId: Long): Flow<List<PrayerLog>>`).
    *   **Affected Files:** None directly from design.
    *   **New Files:** Conceptual definitions for repository interfaces and classes.
    *   **Build/Integration Notes:** This layer abstracts data source details from the rest of the app.

*   [ ] **1.8. Plan SharedPreferences to Room Data Migration:**
    *   [ ] Outline a one-time migration utility/service (e.g., `SharedPreferencesToRoomMigrator`).
    *   [ ] This utility will read all data from existing `SharedPreferences` files (identified in 1.2).
    *   [ ] It will then map and insert this data into the appropriate Room entities using the DAOs.
    *   [ ] Decide trigger: e.g., in `PrayerApp.onCreate()` check a "migration_v1_complete" flag. If not set, run migration.
    *   [ ] The flag itself could be stored in a very simple new SharedPreferences file or a dedicated small table in Room, or even Jetpack DataStore Preferences if you want to introduce it just for this flag. For simplicity, a new single-value SharedPreferences might be easiest just for this one-time flag.
    *   **Affected Files:** Conceptual for migration utility. `PrayerApp.kt` for triggering.
    *   **New Files:** Conceptual for `SharedPreferencesToRoomMigrator.kt`.
    *   **Build/Integration Notes:** Migration must be robust to handle missing keys or unexpected data. Perform in a background thread.

---

**Phase 2: Core Room Implementation**

*   [ ] **2.1. Add Dependencies:**
    *   [ ] Add Room (`room-runtime`, `room-ktx`) and Room compiler (`room-compiler` using `ksp` preferred, or `kapt`) dependencies.
    *   [ ] Add Hilt dependencies (`hilt-android`, `hilt-compiler` using `ksp` or `kapt`).
    *   [ ] Add Coroutines (`kotlinx-coroutines-core`, `kotlinx-coroutines-android`).
    *   **Affected Files:** `app/build.gradle.kts`, `gradle/libs.versions.toml` (if using version catalog).
    *   **New Files:** None.
    *   **Build/Integration Notes:** Sync Gradle. Ensure plugin versions for `ksp` are correct if used.

*   [ ] **2.2. Implement Entities:**
    *   [ ] Create Kotlin data classes as designed in 1.3.x (e.g., `AdSettingsEntity.kt`, `ChildProfileEntity.kt`, etc.) in `app/src/main/java/com/viperdam/kidsprayer/data/database/entity/`.
    *   **Affected Files:** None.
    *   **New Files:** Entity Kotlin files.
    *   **Build/Integration Notes:** Annotate with `@Entity`, `@PrimaryKey`, `@ColumnInfo`, `@ForeignKey`, `@Index`. Compile frequently.

*   [ ] **2.3. Implement DAOs:**
    *   [ ] Create Kotlin interfaces as designed in 1.4 (e.g., `SettingsDao.kt`, `RewardSystemDao.kt`) in `app/src/main/java/com/viperdam/kidsprayer/data/database/dao/`.
    *   **Affected Files:** None.
    *   **New Files:** DAO Kotlin interface files.
    *   **Build/Integration Notes:** Annotate with `@Dao` and methods with `@Insert`, `@Query`, etc. Room validates SQL queries at compile time. Use `suspend` for one-shot operations, `Flow` for observable queries.

*   [ ] **2.4. Implement Database Class:**
    *   [ ] Create `KidsPrayerDatabase.kt` as designed in 1.5 in `app/src/main/java/com/viperdam/kidsprayer/data/database/`.
    *   [ ] Annotate with `@Database(entities = [...], version = 1)`.
    *   [ ] Include abstract DAO getters. Implement singleton pattern for instance creation.
    *   **Affected Files:** None.
    *   **New Files:** `KidsPrayerDatabase.kt`.
    *   **Build/Integration Notes:** List all entity classes in the `@Database` annotation.

*   [ ] **2.5. Implement Repository Layer:**
    *   [ ] Create interfaces (e.g., `SettingsRepository.kt`) and implementations (e.g. `SettingsRepositoryImpl.kt`) as designed in 1.7 in `app/src/main/java/com/viperdam/kidsprayer/data/repository/`.
    *   [ ] Implementations will inject DAOs.
    *   **Affected Files:** None.
    *   **New Files:** Repository Kotlin interface and class files.
    *   **Build/Integration Notes:** This layer uses DAOs to interact with the database.

*   [ ] **2.6. Setup Dependency Injection (Hilt):**
    *   [ ] Create Hilt modules (e.g., `DatabaseModule.kt` to provide `Context`, `KidsPrayerDatabase`, DAOs; `RepositoryModule.kt` to bind repository interfaces to implementations) in `app/src/main/java/com/viperdam/kidsprayer/di/`.
    *   [ ] Annotate `PrayerApp.kt` with `@HiltAndroidApp`.
    *   **Affected Files:** `PrayerApp.kt`.
    *   **New Files:** Hilt module Kotlin files.
    *   **Build/Integration Notes:** Use `@Provides` for database and DAOs, `@Binds` for repository interfaces. Use `@Singleton` appropriately.

*   [ ] **2.7. Initial Unit Tests for DAOs and Repository:**
    *   [ ] Write unit tests for each DAO method using an in-memory Room database (`Room.inMemoryDatabaseBuilder`).
    *   [ ] Write unit tests for Repository methods, mocking DAOs or using the in-memory DB.
    *   **Affected Files:** None.
    *   **New Files:** Test classes in `app/src/test/java/com/viperdam/kidsprayer/data/database/` and `app/src/test/java/com/viperdam/kidsprayer/data/repository/`.
    *   **Build/Integration Notes:** Essential for verifying database logic before UI integration.

---

**Phase 3: Data Migration Implementation**

*   [ ] **3.1. Implement `SharedPreferencesToRoomMigrator.kt`:**
    *   [ ] Create this utility in `app/src/main/java/com/viperdam/kidsprayer/data/migration/`.
    *   [ ] Inject DAOs (or the entire `KidsPrayerDatabase` instance) and `Context`.
    *   [ ] Create private methods for each old SharedPreferences file: `migratePrayerPrefs(context, settingsDao)`, `migrateLockScreenPrefs(context, lockScreenDao)`, etc.
    *   [ ] Inside each method, read all keys from the specific SharedPreferences file.
    *   [ ] Map the read data to the corresponding Room entities.
    *   [ ] Use DAOs to insert data. Wrap insertions for a single SP file in a transaction if multiple entities are involved.
    *   **Affected Files:** None.
    *   **New Files:** `SharedPreferencesToRoomMigrator.kt`.
    *   **Build/Integration Notes:** Handle `try-catch` for SharedPreferences access. Log extensively during migration for debugging. Ensure migration happens on a background thread.

*   [ ] **3.2. Implement Migration Trigger in `PrayerApp.kt`:**
    *   [ ] In `onCreate()`:
        *   [ ] Check a flag (e.g., `val migrationPrefs = getSharedPreferences("app_migration_flags", Context.MODE_PRIVATE)`).
        *   [ ] `if (!migrationPrefs.getBoolean("v1_room_migration_complete", false)) { ... }`
        *   [ ] Inside the if block, launch a coroutine (e.g., `CoroutineScope(Dispatchers.IO).launch`) to execute `SharedPreferencesToRoomMigrator.runMigration()`.
        *   [ ] After successful migration (migrator returns success), set the flag: `migrationPrefs.edit().putBoolean("v1_room_migration_complete", true).apply()`.
    *   **Affected Files:** `PrayerApp.kt`.
    *   **New Files:** None.
    *   **Build/Integration Notes:** Ensure the coroutine scope used for migration is appropriate and doesn't leak. The migration flag SharedPreferences should be distinct from any app data SPs.

*   [ ] **3.3. Test Migration Thoroughly:**
    *   [ ] Manually populate old SharedPreferences files (e.g., using a debug build or ADB).
    *   [ ] Run the app and verify:
        *   Data is correctly migrated to Room tables (use Database Inspector).
        *   The "migration complete" flag is set.
        *   Migration does not run again on subsequent launches.
        *   App functions correctly with migrated data (initial check).
    *   **Affected Files:** Test setup might involve temporarily modifying code or using debug tools.
    *   **New Files:** Potentially test utility scripts or notes.
    *   **Build/Integration Notes:** This is a critical step. Data loss for existing users must be avoided.

---

**Phase 4: Refactor Application Logic (Iterative per Feature)**

*   **For each feature (e.g., Lock Screen, Ad Settings, Prayer Time Display, MediaPipe-related settings):**
    *   [ ] **4.1. Identify SharedPreferences Access Points:**
        *   [ ] Grep for `getSharedPreferences("specific_pref_name", ...)` in related files (ViewModels, Activities, Fragments, Services, Receivers like `LockScreenViewModel.kt`, `LockScreenActivity.kt`, `AdManager.kt`, `PrayerTimesViewModel.kt`, `PrayerReceiver.kt`).
    *   [ ] **4.2. Update ViewModels/UseCases:**
        *   [ ] Inject the relevant Repository (e.g., `SettingsRepository`, `LockScreenRepository`) using Hilt (`@HiltViewModel`, `@Inject constructor`).
        *   [ ] Replace `sharedPreferences.getXxx()` with repository calls (e.g., `settingsRepository.getLockScreenEnabledFlow().collect { ... }`).
        *   [ ] Replace `sharedPreferences.edit().putXxx().apply()` with `suspend` repository calls (e.g., `viewModelScope.launch { settingsRepository.setLockScreenEnabled(true) }`).
    *   **Affected Files:** Specific ViewModel (e.g., `LockScreenViewModel.kt`), UseCase, or manager classes related to the feature.
    *   **New Files:** Unlikely, mostly modifications.
    *   **Build/Integration Notes:** Ensure coroutine scopes are handled correctly.
    *   [ ] **4.3. Update UI (Activities/Fragments/Composables):**
        *   [ ] Observe `StateFlow` or `Flow` from ViewModels to update UI state.
        *   [ ] Trigger ViewModel methods for user actions that change settings.
    *   **Affected Files:** Specific Activity/Fragment (e.g., `LockScreenActivity.kt`, `SettingsFragment.kt`) or Composable functions.
    *   **New Files:** Unlikely.
    *   **Build/Integration Notes:** UI should react to data changes from the database via Flows.
    *   [ ] **4.4. Update Services/BroadcastReceivers:**
        *   [ ] For components not having `viewModelScope` (like Services, BroadcastReceivers), if they need to access data:
            *   Inject Repository/DAOs (Hilt can help with `@AndroidEntryPoint` on Services, but for Receivers, it's more complex; often better to delegate work to a Service or WorkManager job that *can* use DI).
            *   Use a custom coroutine scope or `GlobalScope` (with caution, ensure proper lifecycle management) for short-lived operations, or use `runBlocking` if absolutely necessary for synchronous SharedPreferences replacement (less ideal). For ongoing observation, they might need to be refactored to use WorkManager or a bound service.
    *   **Affected Files:** Files like `PrayerReceiver.kt`, `LockScreenMonitorService.kt`, `AdhanService.kt`.
    *   **New Files:** Unlikely.
    *   **Build/Integration Notes:** This can be challenging. Prioritize moving logic to ViewModels or use cases where possible. For background tasks, consider WorkManager which supports DI.
    *   [ ] **4.5. Remove Old SharedPreferences Code for the Feature:**
        *   [ ] Once the feature is fully migrated and tested with Room, delete the old `getSharedPreferences` and `edit()` calls for that specific feature's preferences.
    *   **Affected Files:** Same files as in 4.1-4.4.
    *   **New Files:** None.
    *   **Build/Integration Notes:** Delete carefully. Don't remove migration code yet.
    *   [ ] **4.6. Test Feature End-to-End:**
        *   [ ] Test saving, retrieving, and UI updates for the refactored feature.
    *   **Affected Files:** None directly.
    *   **New Files:** Potentially UI test classes in `app/src/androidTest/`.
    *   **Build/Integration Notes:** Ensure behavior is identical or improved.

---

**Phase 5: Implement Reward System**

*   [ ] **5.1. Develop Reward System Logic (ViewModels/UseCases):**
    *   [ ] Create ViewModels (e.g., `RewardViewModel.kt`, `PrayerLogViewModel.kt`) in `app/src/main/java/com/viperdam/kidsprayer/ui/reward/` or similar.
    *   [ ] Implement logic for logging prayers, calculating/awarding points, redeeming rewards, viewing history, etc., using the `RewardRepository` (which uses `ChildProfileDao`, `PrayerLogDao`, etc.).
    *   **Affected Files:** None.
    *   **New Files:** ViewModel/UseCase Kotlin files for the reward system.
    *   **Build/Integration Notes:** All data persistence for this new feature will go through the Room setup.

*   [ ] **5.2. Develop UI for Reward System:**
    *   [ ] Create Activities/Fragments or Composable screens for displaying prayer logs, point balances, reward catalogs, redemption history.
    *   **Affected Files:** None.
    *   **New Files:** XML layouts or Composable Kotlin files in `app/src/main/java/com/viperdam/kidsprayer/ui/reward/` and corresponding resource files in `app/src/main/res/`.
    *   **Build/Integration Notes:** UI observes data from `RewardViewModel`s.

*   [ ] **5.3. Test Reward System Thoroughly:**
    *   [ ] End-to-end testing of all reward system functionalities.
    *   **Affected Files:** None directly.
    *   **New Files:** UI test classes for the reward system in `app/src/androidTest/`.
    *   **Build/Integration Notes:** Test edge cases like insufficient points, redeeming multiple rewards, etc.

---

**Phase 6: Testing & Cleanup**

*   [ ] **6.1. Full Regression Testing:**
    *   [ ] Manually test every feature of the app.
    *   [ ] Run all automated tests (unit and instrumented).
    *   **Affected Files:** None directly.
    *   **New Files:** Potentially more test cases.
    *   **Build/Integration Notes:** Critical to catch any unintended side effects of the refactoring.

*   [ ] **6.2. Performance Testing:**
    *   [ ] Use Android Studio Profiler to check database query times, UI responsiveness.
    *   **Affected Files:** None directly.
    *   **New Files:** None.
    *   **Build/Integration Notes:** Ensure no database operations are blocking the main thread. Optimize queries if needed.

*   [ ] **6.3. Code Review:**
    *   [ ] Review all new and modified code for correctness, style, and best practices.
    *   **Affected Files:** All changed files.
    *   **New Files:** None.
    *   **Build/Integration Notes:** A fresh pair of eyes can catch issues.

*   [ ] **6.4. Consider Removing SharedPreferences Migration Code (Future - Long Term):**
    *   [ ] After several app versions and confidence that most users have migrated, the `SharedPreferencesToRoomMigrator` and its trigger in `PrayerApp.kt` can be removed. The simple "migration_v1_room_migration_complete" SharedPreferences file can also be ignored/deleted from new installs.
    *   **Affected Files:** `SharedPreferencesToRoomMigrator.kt`, `PrayerApp.kt`.
    *   **New Files:** None.
    *   **Build/Integration Notes:** This is a long-term step to reduce code size. Do not rush this.

*   [ ] **6.5. Update Documentation (if any):**
    *   [ ] Update any internal project documentation regarding data persistence.
    *   **Affected Files:** Project documentation files.
    *   **New Files:** None.
    *   **Build/Integration Notes:** Keep docs current.

---

**Phase 7: Release**

*   [ ] **7.1. Monitor Release:**
    *   [ ] Closely monitor Firebase Crashlytics, Google Play Console ANRs & Crashes for any new issues, especially related to database operations or data integrity.
    *   [ ] Monitor user feedback.
    *   **Affected Files:** None directly.
    *   **New Files:** None.
    *   **Build/Integration Notes:** Be prepared for hotfixes if critical issues arise.

*   [ ] **7.2. Prepare for Schema Migrations (Future):**
    *   [ ] If you need to change the database schema (add/remove tables/columns, change types) in a future release, you MUST implement a `androidx.room.migration.Migration` class and add it to your database builder (`.addMigrations(MIGRATION_1_2, MIGRATION_2_3)`).
    *   **Affected Files:** `KidsPrayerDatabase.kt` (increment version, add migration), new `Migration_X_Y.kt` files.
    *   **New Files:** Migration class files.
    *   **Build/Integration Notes:** Test schema migrations thoroughly to prevent data loss for users updating the app. Room will crash if a schema mismatch occurs without a valid migration path.

--- 