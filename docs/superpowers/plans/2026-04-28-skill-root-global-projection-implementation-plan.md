# Skill Root Global Projection Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Unify Claude and Codex skill management under the runtime adapter path, then add global skill-root import that projects skills into engine-visible directories using symlink or junction behavior.

**Architecture:** Keep runtime skill discovery as the single post-import truth. Aura only scans an imported root during the import action, validates duplicate names against engine target directories, and creates engine-specific projections into `~/.codex/skills/` and `~/.claude/skills/`. Settings, slash suggestions, and prompt submission continue to consume adapter-returned runtime skill records.

**Tech Stack:** Kotlin, JetBrains platform services, Compose Desktop UI, existing Aura toolwindow eventing, JUnit tests, Gradle test tasks

---

## File Map

**Create:**
- `src/main/kotlin/com/auracode/assistant/provider/claude/ClaudeSkillsManagementAdapter.kt`
- `src/main/kotlin/com/auracode/assistant/settings/skills/EngineSkillDirectoryResolver.kt`
- `src/main/kotlin/com/auracode/assistant/settings/skills/SkillRootScanner.kt`
- `src/main/kotlin/com/auracode/assistant/settings/skills/SkillProjectionManager.kt`
- `src/test/kotlin/com/auracode/assistant/provider/claude/ClaudeSkillsManagementAdapterTest.kt`
- `src/test/kotlin/com/auracode/assistant/settings/skills/SkillRootScannerTest.kt`
- `src/test/kotlin/com/auracode/assistant/settings/skills/SkillProjectionManagerTest.kt`

**Modify:**
- `src/main/kotlin/com/auracode/assistant/settings/skills/SkillsManagementAdapter.kt`
- `src/main/kotlin/com/auracode/assistant/settings/skills/EngineSkillsService.kt`
- `src/main/kotlin/com/auracode/assistant/settings/skills/LocalSkillCatalog.kt`
- `src/main/kotlin/com/auracode/assistant/toolwindow/eventing/ToolWindowEvents.kt`
- `src/main/kotlin/com/auracode/assistant/toolwindow/eventing/SettingsAndEnvironmentHandler.kt`
- `src/main/kotlin/com/auracode/assistant/toolwindow/settings/SkillsSettingsPage.kt`
- `src/main/kotlin/com/auracode/assistant/toolwindow/eventing/ToolWindowCoordinator.kt`
- `src/test/kotlin/com/auracode/assistant/settings/skills/SkillsRuntimeServiceTest.kt`
- `src/test/kotlin/com/auracode/assistant/toolwindow/eventing/ToolWindowCoordinatorSkillTest.kt`
- `src/main/resources/messages/AuraCodeBundle.properties`
- `src/main/resources/messages/AuraCodeBundle_zh.properties`
- `src/main/resources/messages/AuraCodeBundle_ja.properties`
- `src/main/resources/messages/AuraCodeBundle_ko.properties`

**Reference:**
- `docs/superpowers/specs/2026-04-28-skill-root-global-projection-design.md`
- `src/main/kotlin/com/auracode/assistant/provider/codex/CodexSkillsManagementAdapter.kt`
- `src/main/kotlin/com/auracode/assistant/provider/codex/CodexRuntimeClient.kt`
- `src/main/kotlin/com/auracode/assistant/settings/skills/LocalSkillInstallPolicy.kt`

---

## Chunk 1: Unify Runtime Skill Adapters

### Task 1: Add failing tests for Claude runtime adapter registration

**Files:**
- Create: `src/test/kotlin/com/auracode/assistant/provider/claude/ClaudeSkillsManagementAdapterTest.kt`
- Modify: `src/test/kotlin/com/auracode/assistant/settings/skills/SkillsRuntimeServiceTest.kt`

- [ ] **Step 1: Write the failing adapter discovery test**

```kotlin
@Test
fun `registry exposes claude adapter`() {
    val registry = SkillsManagementAdapterRegistry(
        adapters = mapOf("claude" to FakeClaudeAdapter()),
        defaultEngineId = "claude",
    )

    assertNotNull(registry.adapterFor("claude"))
}
```

- [ ] **Step 2: Write the failing Claude list-skills test**

```kotlin
@Test
fun `claude adapter scans user and plugin skill directories`() = runTest {
    val adapter = ClaudeSkillsManagementAdapter(
        settings = AgentSettingsService.State().toServiceForTests(),
        homeDir = tempDir,
    )

    assertEquals(listOf("alpha", "beta"), adapter.listRuntimeSkills(cwd = tempDir.toString()).map { it.name }.sorted())
}
```

- [ ] **Step 3: Run the focused tests to verify failure**

Run:
```bash
./gradlew test --tests "com.auracode.assistant.provider.claude.ClaudeSkillsManagementAdapterTest" --tests "com.auracode.assistant.settings.skills.SkillsRuntimeServiceTest"
```

Expected: FAIL because the Claude adapter does not exist and the registry only registers Codex.

- [ ] **Step 4: Commit the failing tests**

```bash
git add src/test/kotlin/com/auracode/assistant/provider/claude/ClaudeSkillsManagementAdapterTest.kt src/test/kotlin/com/auracode/assistant/settings/skills/SkillsRuntimeServiceTest.kt
git commit -m "test: cover claude skill adapter registration"
```

### Task 2: Implement Claude runtime adapter and registry wiring

**Files:**
- Create: `src/main/kotlin/com/auracode/assistant/provider/claude/ClaudeSkillsManagementAdapter.kt`
- Modify: `src/main/kotlin/com/auracode/assistant/settings/skills/SkillsManagementAdapter.kt`
- Modify: `src/main/kotlin/com/auracode/assistant/settings/skills/LocalSkillCatalog.kt`

- [ ] **Step 1: Add the minimal Claude adapter implementation**

Implementation requirements:
- Scan `~/.claude/skills/`
- Scan `~/.claude/plugins/cache/`
- Treat plugin cache as read-only source
- Return `RuntimeSkillRecord` entries using adapter-visible paths
- Add English comments to the class and public methods

- [ ] **Step 2: Extract shared parsing helpers from `LocalSkillCatalog`**

Implementation requirements:
- Reuse SKILL descriptor parsing instead of duplicating front-matter parsing
- Keep file responsibilities small; if needed, split parsing helpers out of `LocalSkillCatalog.kt`

- [ ] **Step 3: Register the Claude adapter in `SkillsManagementAdapterRegistry`**

Implementation requirements:
- Keep Codex registration unchanged
- Add Claude to the default constructor map

- [ ] **Step 4: Run the focused tests to verify pass**

Run:
```bash
./gradlew test --tests "com.auracode.assistant.provider.claude.ClaudeSkillsManagementAdapterTest" --tests "com.auracode.assistant.settings.skills.SkillsRuntimeServiceTest"
```

Expected: PASS

- [ ] **Step 5: Commit the adapter work**

```bash
git add src/main/kotlin/com/auracode/assistant/provider/claude/ClaudeSkillsManagementAdapter.kt src/main/kotlin/com/auracode/assistant/settings/skills/SkillsManagementAdapter.kt src/main/kotlin/com/auracode/assistant/settings/skills/LocalSkillCatalog.kt src/test/kotlin/com/auracode/assistant/provider/claude/ClaudeSkillsManagementAdapterTest.kt src/test/kotlin/com/auracode/assistant/settings/skills/SkillsRuntimeServiceTest.kt
git commit -m "feat: add claude runtime skill adapter"
```

## Chunk 2: Remove Local-Only Branching From EngineSkillsService

### Task 3: Add failing tests for unified engine skill routing

**Files:**
- Modify: `src/test/kotlin/com/auracode/assistant/toolwindow/eventing/ToolWindowCoordinatorSkillTest.kt`
- Modify: `src/test/kotlin/com/auracode/assistant/settings/skills/SkillsRuntimeServiceTest.kt`

- [ ] **Step 1: Add a failing test that Claude uses runtime-backed skills**

```kotlin
@Test
fun `claude skill loading uses runtime snapshot`() = runTest {
    val service = EngineSkillsService(
        settings = settings,
        runtimeService = fakeRuntimeServiceReturning("claude", listOf(runtimeSkill("alpha"))),
    )

    val snapshot = service.loadSkills(engineId = "claude", cwd = tempDir.toString(), forceReload = true)
    assertEquals(SkillManagementMode.RUNTIME, snapshot.managementMode)
}
```

- [ ] **Step 2: Add a failing coordinator test for Claude settings load**

```kotlin
@Test
fun `load skills publishes claude runtime skills`() {
    // Assert that the event payload uses runtime-backed records for Claude as well.
}
```

- [ ] **Step 3: Run the focused tests to verify failure**

Run:
```bash
./gradlew test --tests "com.auracode.assistant.settings.skills.SkillsRuntimeServiceTest" --tests "com.auracode.assistant.toolwindow.eventing.ToolWindowCoordinatorSkillTest"
```

Expected: FAIL because `EngineSkillsService` still branches to `LocalSkillCatalog` for Claude.

- [ ] **Step 4: Commit the failing tests**

```bash
git add src/test/kotlin/com/auracode/assistant/settings/skills/SkillsRuntimeServiceTest.kt src/test/kotlin/com/auracode/assistant/toolwindow/eventing/ToolWindowCoordinatorSkillTest.kt
git commit -m "test: cover unified engine skill routing"
```

### Task 4: Implement unified engine skill routing

**Files:**
- Modify: `src/main/kotlin/com/auracode/assistant/settings/skills/EngineSkillsService.kt`

- [ ] **Step 1: Remove the `codex vs claude` management split**

Implementation requirements:
- Route `loadSkills`, `setSkillEnabled`, `enabledSlashSkills`, and `findDisabledSkillMentions` through `SkillsRuntimeService`
- Keep comments in English
- Drop now-unused `SkillManagementMode.LOCAL` usages if the type can be simplified safely

- [ ] **Step 2: Remove now-dead `LocalSkillCatalog` dependency from `EngineSkillsService`**

Implementation requirements:
- Preserve compile safety
- Avoid broad unrelated refactors

- [ ] **Step 3: Run focused tests to verify pass**

Run:
```bash
./gradlew test --tests "com.auracode.assistant.settings.skills.SkillsRuntimeServiceTest" --tests "com.auracode.assistant.toolwindow.eventing.ToolWindowCoordinatorSkillTest"
```

Expected: PASS

- [ ] **Step 4: Commit the routing change**

```bash
git add src/main/kotlin/com/auracode/assistant/settings/skills/EngineSkillsService.kt src/test/kotlin/com/auracode/assistant/settings/skills/SkillsRuntimeServiceTest.kt src/test/kotlin/com/auracode/assistant/toolwindow/eventing/ToolWindowCoordinatorSkillTest.kt
git commit -m "refactor: unify engine skill loading through adapters"
```

## Chunk 3: Add Skill Root Import and Projection Infrastructure

### Task 5: Add failing tests for root scanning and duplicate detection

**Files:**
- Create: `src/test/kotlin/com/auracode/assistant/settings/skills/SkillRootScannerTest.kt`
- Create: `src/test/kotlin/com/auracode/assistant/settings/skills/SkillProjectionManagerTest.kt`

- [ ] **Step 1: Add a failing scanner test for nested skill discovery**

```kotlin
@Test
fun `scanner finds root and nested skills`() {
    val result = scanner.scan(root)
    assertEquals(setOf("alpha", "beta"), result.skills.map { it.name }.toSet())
}
```

- [ ] **Step 2: Add a failing projection-manager duplicate-name test**

```kotlin
@Test
fun `projection manager rejects duplicate skill names in target engine directory`() {
    val error = assertFailsWith<IllegalStateException> {
        manager.project(root = importedRoot, engines = listOf("codex"))
    }
    assertTrue(error.message!!.contains("already exists"))
}
```

- [ ] **Step 3: Run the focused tests to verify failure**

Run:
```bash
./gradlew test --tests "com.auracode.assistant.settings.skills.SkillRootScannerTest" --tests "com.auracode.assistant.settings.skills.SkillProjectionManagerTest"
```

Expected: FAIL because the scanner and projection manager do not exist yet.

- [ ] **Step 4: Commit the failing tests**

```bash
git add src/test/kotlin/com/auracode/assistant/settings/skills/SkillRootScannerTest.kt src/test/kotlin/com/auracode/assistant/settings/skills/SkillProjectionManagerTest.kt
git commit -m "test: cover skill root projection setup"
```

### Task 6: Implement root scanning and engine projection helpers

**Files:**
- Create: `src/main/kotlin/com/auracode/assistant/settings/skills/EngineSkillDirectoryResolver.kt`
- Create: `src/main/kotlin/com/auracode/assistant/settings/skills/SkillRootScanner.kt`
- Create: `src/main/kotlin/com/auracode/assistant/settings/skills/SkillProjectionManager.kt`

- [ ] **Step 1: Implement the engine directory resolver**

Implementation requirements:
- Codex target: `~/.codex/skills/`
- Claude write target: `~/.claude/skills/`
- Claude read-only extra source: `~/.claude/plugins/cache/`
- Resolve Windows home paths using the JVM user home directory

- [ ] **Step 2: Implement the root scanner**

Implementation requirements:
- Recursively locate `SKILL.md`
- Reuse shared descriptor parsing
- Return both valid skills and invalid-entry diagnostics for UI messaging

- [ ] **Step 3: Implement the projection manager**

Implementation requirements:
- Create symlinks on macOS/Linux
- Create directory junctions on Windows
- Check for duplicate names before creating anything
- Project into all supported engine directories during one import action

- [ ] **Step 4: Run focused tests to verify pass**

Run:
```bash
./gradlew test --tests "com.auracode.assistant.settings.skills.SkillRootScannerTest" --tests "com.auracode.assistant.settings.skills.SkillProjectionManagerTest"
```

Expected: PASS

- [ ] **Step 5: Commit the projection infrastructure**

```bash
git add src/main/kotlin/com/auracode/assistant/settings/skills/EngineSkillDirectoryResolver.kt src/main/kotlin/com/auracode/assistant/settings/skills/SkillRootScanner.kt src/main/kotlin/com/auracode/assistant/settings/skills/SkillProjectionManager.kt src/test/kotlin/com/auracode/assistant/settings/skills/SkillRootScannerTest.kt src/test/kotlin/com/auracode/assistant/settings/skills/SkillProjectionManagerTest.kt
git commit -m "feat: add skill root projection infrastructure"
```

## Chunk 4: Wire Import Flow Into Settings

### Task 7: Add failing tests for import intents and coordinator behavior

**Files:**
- Modify: `src/test/kotlin/com/auracode/assistant/toolwindow/eventing/ToolWindowCoordinatorSkillTest.kt`
- Modify: `src/main/kotlin/com/auracode/assistant/toolwindow/eventing/ToolWindowEvents.kt`

- [ ] **Step 1: Add a failing coordinator test for importing a skill root**

```kotlin
@Test
fun `import skill root projects skills for all engines and refreshes runtime list`() {
    // Assert import intent triggers projection manager and then reloads skills.
}
```

- [ ] **Step 2: Add import intents needed by the UI**

Implementation requirements:
- Add one intent for opening the directory picker
- Add one intent for handling selected root paths

- [ ] **Step 3: Run the focused tests to verify failure**

Run:
```bash
./gradlew test --tests "com.auracode.assistant.toolwindow.eventing.ToolWindowCoordinatorSkillTest"
```

Expected: FAIL because the import intents and handler path do not exist.

- [ ] **Step 4: Commit the failing tests**

```bash
git add src/test/kotlin/com/auracode/assistant/toolwindow/eventing/ToolWindowCoordinatorSkillTest.kt src/main/kotlin/com/auracode/assistant/toolwindow/eventing/ToolWindowEvents.kt
git commit -m "test: cover skill root import coordinator flow"
```

### Task 8: Implement import handling and settings UI entrypoint

**Files:**
- Modify: `src/main/kotlin/com/auracode/assistant/toolwindow/eventing/SettingsAndEnvironmentHandler.kt`
- Modify: `src/main/kotlin/com/auracode/assistant/toolwindow/eventing/ToolWindowCoordinator.kt`
- Modify: `src/main/kotlin/com/auracode/assistant/toolwindow/eventing/ToolWindowEvents.kt`
- Modify: `src/main/kotlin/com/auracode/assistant/toolwindow/settings/SkillsSettingsPage.kt`

- [ ] **Step 1: Add settings handler import flow**

Implementation requirements:
- Accept one selected root path
- Run `SkillRootScanner`
- Run `SkillProjectionManager`
- Refresh the unified runtime skill snapshot
- Publish localized success/failure messages

- [ ] **Step 2: Add the UI trigger for importing a local directory**

Implementation requirements:
- Reuse existing local chooser patterns already used in `ComposeToolWindowPanel`
- Keep the settings page flat; do not add grouped sections
- Add only the minimal new control needed in the header area

- [ ] **Step 3: Wire the intent dispatch in `ToolWindowCoordinator`**

Implementation requirements:
- Open a directory chooser
- Send selected path(s) back through the intent/event flow

- [ ] **Step 4: Add i18n keys for import flow messaging**

Implementation requirements:
- Update `AuraCodeBundle.properties`
- Update `AuraCodeBundle_zh.properties`
- Update `AuraCodeBundle_ja.properties`
- Update `AuraCodeBundle_ko.properties`

- [ ] **Step 5: Run focused tests to verify pass**

Run:
```bash
./gradlew test --tests "com.auracode.assistant.toolwindow.eventing.ToolWindowCoordinatorSkillTest"
```

Expected: PASS

- [ ] **Step 6: Commit the UI and handler wiring**

```bash
git add src/main/kotlin/com/auracode/assistant/toolwindow/eventing/SettingsAndEnvironmentHandler.kt src/main/kotlin/com/auracode/assistant/toolwindow/eventing/ToolWindowCoordinator.kt src/main/kotlin/com/auracode/assistant/toolwindow/eventing/ToolWindowEvents.kt src/main/kotlin/com/auracode/assistant/toolwindow/settings/SkillsSettingsPage.kt src/main/resources/messages/AuraCodeBundle.properties src/main/resources/messages/AuraCodeBundle_zh.properties src/main/resources/messages/AuraCodeBundle_ja.properties src/main/resources/messages/AuraCodeBundle_ko.properties src/test/kotlin/com/auracode/assistant/toolwindow/eventing/ToolWindowCoordinatorSkillTest.kt
git commit -m "feat: import and project local skill roots from settings"
```

## Chunk 5: Verify End-to-End Behavior

### Task 9: Add regression coverage for runtime-visible imported skills

**Files:**
- Modify: `src/test/kotlin/com/auracode/assistant/toolwindow/eventing/ToolWindowCoordinatorSkillTest.kt`
- Modify: `src/test/kotlin/com/auracode/assistant/provider/claude/ClaudeSkillsManagementAdapterTest.kt`

- [ ] **Step 1: Add a regression test for imported skills appearing in runtime-backed UI**

```kotlin
@Test
fun `imported projected skill appears in loaded skill snapshot`() {
    // Assert projected skill is returned by the adapter and rendered as runtime-managed.
}
```

- [ ] **Step 2: Add a regression test for Claude plugin cache remaining read-only**

```kotlin
@Test
fun `projection manager never writes into claude plugin cache`() {
    assertFalse(projectedPath.startsWith(pluginCachePath))
}
```

- [ ] **Step 3: Run the focused tests to verify pass**

Run:
```bash
./gradlew test --tests "com.auracode.assistant.toolwindow.eventing.ToolWindowCoordinatorSkillTest" --tests "com.auracode.assistant.provider.claude.ClaudeSkillsManagementAdapterTest"
```

Expected: PASS

- [ ] **Step 4: Commit the regression coverage**

```bash
git add src/test/kotlin/com/auracode/assistant/toolwindow/eventing/ToolWindowCoordinatorSkillTest.kt src/test/kotlin/com/auracode/assistant/provider/claude/ClaudeSkillsManagementAdapterTest.kt
git commit -m "test: cover projected runtime skills"
```

### Task 10: Run full verification

**Files:**
- Modify: none

- [ ] **Step 1: Run the skills-focused test suites**

Run:
```bash
./gradlew test --tests "com.auracode.assistant.settings.skills.*" --tests "com.auracode.assistant.provider.claude.ClaudeSkillsManagementAdapterTest" --tests "com.auracode.assistant.toolwindow.eventing.ToolWindowCoordinatorSkillTest"
```

Expected: PASS

- [ ] **Step 2: Run one broader toolwindow regression suite**

Run:
```bash
./gradlew test --tests "com.auracode.assistant.toolwindow.eventing.*"
```

Expected: PASS

- [ ] **Step 3: Inspect git diff for accidental churn**

Run:
```bash
git diff --stat HEAD~5..HEAD
```

Expected: Only skill-management, settings, i18n, and test files changed.

- [ ] **Step 4: Create the final implementation commit or squash sequence as desired**

```bash
git status
```

Expected: clean working tree after the planned commits.
