# Multi-Engine Render Kernel Design

**Date:** 2026-04-25

**Status:** Draft approved in conversation, pending written-spec review

## Goal

Rebuild Aura's multi-engine rendering architecture so that:

- engine-specific output formats are parsed outside the UI layer
- live streaming, history replay, and background sessions all use one state pipeline
- the tool window UI consumes projections only and no longer interprets runtime semantics
- adding a new engine requires localized changes in engine adapters and semantic normalization, not UI-wide changes
- package names and module boundaries reflect business responsibilities instead of screen placement

This design is intentionally strong and one-way. The target branch should switch to the new architecture completely and remove the old rendering chain before merge.

## Current Problems

The current codebase already supports Codex and Claude, but multi-engine growth is blocked by four structural issues:

1. The same runtime information is translated multiple times through different chains.
2. Semantic interpretation still happens close to the timeline UI.
3. Background sessions cache UI store replicas instead of owning session-level state.
4. UI packages are named by placement or widget shape rather than feature responsibility.

The most visible symptom is unstable command and text rendering:

- titles are inferred from free-form command text
- file changes can fall back to body-string parsing
- message, tool, and command summaries are partly derived in UI-adjacent code
- different engines produce different raw formats, so the timeline quality drifts by engine

This means the current architecture does not have a reliable semantic boundary.

## Design Principles

### 1. One Session, One Kernel

Each chat session must own exactly one runtime kernel and one authoritative session state.

No parallel translation pipelines should exist for:

- live events
- replayed history
- cached background sessions
- restored sessions after switching tabs

### 2. UI Never Guesses Semantics

UI code may format and render, but it must not infer business meaning from raw command strings, opaque ids, or provider-specific payload fragments.

Accepted UI responsibilities:

- markdown rendering
- text selection
- expansion and collapse behavior
- icon, badge, and color presentation
- click handling for already-structured targets

Rejected UI responsibilities:

- guessing whether a command means "read file"
- guessing which file a tool call touched
- parsing fallback file-change lines from text blobs
- deriving engine-specific activity semantics from display text

### 3. Parse Early, Normalize Once

Provider-specific parsing should happen inside each engine package.

Cross-engine unification should happen once in a normalization layer.

After normalization, the rest of the product should work with shared domain events and session state only.

### 4. Projection Is Read-Only

Projection code converts domain state into UI-facing presentation models.

Projection is not a second reducer tree and not an alternative source of truth.

### 5. Packages Must Describe Responsibilities

UI package names must describe feature responsibilities, not layout regions.

Examples of allowed naming:

- `sessions`
- `conversation`
- `submission`
- `execution`
- `history`
- `settings`

Examples of rejected naming:

- `header`
- `drawer`
- `timeline`
- `composer`
- `status`

## Final Architecture

The final architecture is split into five layers.

### 1. Engine Layer

The engine layer owns raw transport and engine-specific parsing.

Responsibilities:

- launch runtime processes or protocol clients
- receive raw engine output
- parse raw engine output into engine semantic records
- expose engine capabilities and metadata
- submit engine-specific control actions such as approval and tool-input responses

The engine layer must not know how the tool window UI renders conversation content.

### 2. Session Normalization Layer

This layer converts engine semantic records into shared session domain events.

Responsibilities:

- unify Codex and Claude concepts into one domain vocabulary
- normalize command kinds, tool kinds, and file-change structures
- preserve structured information instead of flattening back into generic text

This layer is the semantic boundary between provider differences and product behavior.

### 3. Session Kernel Layer

This is the new runtime core and the only state authority for one session.

Responsibilities:

- accept commands
- apply domain events
- maintain session state
- coordinate live runs, approvals, tool user input, plan updates, history replay, and session switching

Every active or background session should own one kernel instance.

### 4. Projection Layer

This layer produces read-only presentation models from session state.

Responsibilities:

- derive conversation list items
- derive submission controls
- derive execution status and interaction cards
- derive session navigation indicators
- derive history and settings projections
- resolve localized UI copy from structured state

Projection code may choose wording and visual presentation, but it may not reinterpret raw engine syntax.

### 5. UI Layer

The UI layer becomes a thin consumer of projections and a producer of user intents.

Responsibilities:

- render projection models
- dispatch user intents
- host shell layout and overlays

The UI layer must not own session truth and must not parse engine output.

## Final Directory Structure

```text
src/main/kotlin/com/auracode/assistant/
  provider/
    engine/
      EngineCapabilities.kt
      EngineDescriptorRegistry.kt
    claude/
      raw/
      semantic/
      gateway/
    codex/
      raw/
      semantic/
      gateway/

  session/
    kernel/
      SessionKernel.kt
      SessionKernelManager.kt
      SessionCommand.kt
      SessionDomainEvent.kt
      SessionState.kt
      SessionReducer.kt
      SessionRuntimeRegistry.kt
    normalizer/
      EngineSemanticEventMapper.kt
      CommandSemanticClassifier.kt
      ToolSemanticClassifier.kt
      FileChangeSemanticParser.kt
    projection/
      SessionProjection.kt
      sessions/
      conversation/
      submission/
      execution/
      history/
      settings/

  toolwindow/
    bootstrap/
    shell/
    sessions/
    conversation/
    submission/
    execution/
    history/
    settings/
    shared/
```

## Domain Model

The new design replaces weak text-first rendering contracts with structured domain events.

### Engine Semantic Records

Each engine package should expose engine-local semantic records before cross-engine normalization.

Examples:

- `AssistantMessageSemanticRecord`
- `ReasoningSemanticRecord`
- `CommandExecutionSemanticRecord`
- `ToolInvocationSemanticRecord`
- `FileMutationSemanticRecord`
- `ApprovalRequestSemanticRecord`
- `ToolUserInputSemanticRecord`
- `RunningPlanSemanticRecord`
- `SubagentSemanticRecord`

These records may reflect engine-specific shape, but they must already be more structured than raw strings.

### Session Domain Events

After normalization, the product works with shared domain events only.

Representative event families:

- `SessionDomainEvent.MessageAppended`
- `SessionDomainEvent.ReasoningUpdated`
- `SessionDomainEvent.CommandUpdated`
- `SessionDomainEvent.ToolUpdated`
- `SessionDomainEvent.FileChangesUpdated`
- `SessionDomainEvent.ApprovalRequested`
- `SessionDomainEvent.ToolUserInputRequested`
- `SessionDomainEvent.ToolUserInputResolved`
- `SessionDomainEvent.RunningPlanUpdated`
- `SessionDomainEvent.PlanCompletionReady`
- `SessionDomainEvent.SubagentsUpdated`
- `SessionDomainEvent.TurnStarted`
- `SessionDomainEvent.TurnCompleted`
- `SessionDomainEvent.ThreadStarted`
- `SessionDomainEvent.SessionErrorRaised`
- `SessionDomainEvent.EngineSwitched`

### Structured Activity Fields

The following information must become explicit fields instead of display-time guesses:

- command kind
- tool kind
- message format
- activity target file or directory
- file change kind
- engine identity
- provider-native status
- normalized status
- display-safe summaries

Example command kinds:

- `READ_FILE`
- `WRITE_FILE`
- `SEARCH_FILES`
- `LIST_FILES`
- `RUN_TEST`
- `RUN_BUILD`
- `RUN_GIT`
- `RUN_SHELL`
- `UNKNOWN`

Example tool kinds:

- `MCP_CALL`
- `WEB_SEARCH`
- `PATCH_APPLY`
- `PLAN_UPDATE`
- `USER_INPUT`
- `UNKNOWN`

Example message formats:

- `PLAIN_TEXT`
- `MARKDOWN`
- `CODE_BLOCK`

## Semantic Parsing Boundary

This design explicitly fixes the current rendering-quality problem by moving semantic parsing out of UI-adjacent code.

### Parsing Moves Out Of UI

The following current behaviors must leave the UI-side timeline chain:

- title inference from command text
- target file inference from command text
- body fallback assembly from generic `text`, `command`, `filePath`, or `id`
- fallback file-change parsing from display body strings
- collapsed summary generation that depends on reinterpreting opaque activity text

### New Parsing Path

The new parsing pipeline becomes:

1. raw engine output
2. engine semantic extraction
3. shared normalization
4. session domain events
5. projection formatting
6. UI rendering

This is the key architecture guarantee:

`The UI never receives a provider payload that still requires semantic guessing.`

## Session Kernel

### Kernel Ownership

Each session owns:

- one session id
- one kernel instance
- one session state
- one active engine binding
- one command queue
- one live runtime binding when a run is active

Background tabs should keep their kernel and state alive without cloning UI stores.

### Commands

The session kernel should accept strongly typed commands such as:

- `SubmitPrompt`
- `ReplayHistory`
- `LoadOlderHistory`
- `SubmitApprovalDecision`
- `SubmitToolUserInput`
- `RequestEngineSwitch`
- `CancelRun`
- `OpenRemoteConversation`
- `DeleteSession`
- `RestoreSession`

### State

`SessionState` should include:

- session identity
- engine binding
- live run status
- current thread and turn references
- conversation activity state
- submission draft state
- execution interaction state
- edited file state
- usage state
- subagent state
- history paging state
- localized display metadata keys where appropriate

The state should be feature-oriented, not area-store-oriented.

## Projection Model

Projection should be split by feature domain, not by widget name.

### Session Navigation Projection

Owns:

- session tabs
- unread and attention markers
- active session indicators
- engine badge for each session

### Conversation Projection

Owns:

- conversation render items
- message cards
- activity cards
- file-change cards
- expansion defaults
- load-older affordances

### Submission Projection

Owns:

- input draft
- context chips
- attachment chips
- engine and model selectors
- slash and mention data
- pending submission queue

### Execution Projection

Owns:

- run status
- approval prompts
- tool-input prompts
- running plan data
- plan completion actions
- toasts

### History Projection

Owns:

- remote conversation lists
- search state
- pagination state
- export affordances

### Settings Projection

Owns:

- runtime settings
- skills settings
- MCP settings
- appearance and language preferences

## UI Package Renaming

The old package naming should be removed because it describes placement instead of business purpose.

### Final UI Package Names

```text
toolwindow/
  shell/
  sessions/
  conversation/
  submission/
  execution/
  history/
  settings/
  shared/
```

### Mapping From Current Packages

- `header` -> `sessions`
- `session` -> `sessions`
- `timeline` -> `conversation`
- `composer` -> split into `submission` and `execution`
- `approval` -> `execution`
- `toolinput` -> `execution`
- `status` -> `execution`
- `plan` -> `execution`
- history parts under `drawer` -> `history`
- settings parts under `drawer/settings` -> `settings`
- drawer shell containers -> `shell`

### Naming Rule

Package names must answer:

`What feature does this code support?`

They must not answer:

`Where is this code drawn on screen?`

## Migration Strategy

This branch should migrate in strong phases, but the merge result must contain one architecture only.

### Phase 1: Establish New Cross-Layer Contracts

Create:

- `SessionDomainEvent`
- `SessionState`
- `SessionKernel`
- `SessionProjection`

Do not start with visual rewrites. Start by replacing the cross-layer contract.

### Phase 2: Move Semantic Parsing Out Of UI

Create:

- `provider/claude/semantic/*`
- `provider/codex/semantic/*`
- `session/normalizer/*`

Remove semantic interpretation from timeline-side code.

This phase is mandatory before feature-package renaming, because it fixes the core rendering-quality issue.

### Phase 3: Introduce Kernel-Driven Session Ownership

Create:

- `SessionKernelManager`
- `SessionRuntimeRegistry`
- feature-level command controllers around the kernel

Replace:

- event-broadcast coordination
- background UI store cloning
- session-scoped event dispatchers

### Phase 4: Replace Area Stores With Projection Consumers

UI entry points must move from:

- store subscriptions by screen area

to:

- feature projections generated from session state

### Phase 5: Rename UI Packages By Feature

Once projections are stable, move UI code into:

- `shell`
- `sessions`
- `conversation`
- `submission`
- `execution`
- `history`
- `settings`

### Phase 6: Delete Old Rendering Chain

Before merge, delete old paths so the branch lands with one architecture.

Required deletion targets:

- `conversation/translation/*`
- old timeline mutation and reducer chain
- old area-store hierarchy
- old background session UI cache and scoped dispatcher
- old duplicate engine presentation helpers
- old placement-driven package groupings once moved

## Explicit Deletion Targets

The following old structures should not survive the final merge:

- `src/main/kotlin/com/auracode/assistant/conversation/translation/*`
- `toolwindow/timeline/TimelineNodeMapper.kt`
- `toolwindow/timeline/TimelineNodeReducer.kt`
- timeline mutation models used only by the old rendering chain
- `toolwindow/eventing/SessionScopedEventDispatcher.kt`
- `toolwindow/eventing/SessionUiStateCache.kt`
- old area-store contracts that exist only to fan events into region-local reducers
- `toolwindow/EngineUiPresentation.kt`

Any old UI package that still reflects placement rather than business responsibility should also be removed after migration.

## History And Live Consistency Rule

Live streaming, history replay, and background restoration must converge to the same state shape.

This means:

- replaying historic events must reproduce the same conversation state as live accumulation
- background sessions should evolve through kernels, not UI caches
- switching tabs must restore projections from kernel state, not from duplicated area stores

## Internationalization Rule

Because UI copy remains multilingual in Aura, localization must be resolved in the projection layer rather than buried inside engine parsing.

Rules:

- engine and semantic parsing produces structured meaning, not localized final sentences
- projection chooses localized labels, summaries, and badges
- UI components consume already-localized projection values or localization keys

This keeps i18n independent from provider parsing logic.

## Risks

### 1. Big-Bang Cutover Risk

Because the target branch deletes old pipelines before merge, the branch can destabilize quickly if kernel and projection work is incomplete.

Mitigation:

- keep migration phases disciplined
- use replay-based tests to verify equivalence before deleting old code

### 2. Semantic Misclassification Risk

Some Claude flows may still require textual extraction where structured fields do not exist.

Mitigation:

- keep engine-specific semantic extractors local to each provider
- mark low-confidence classifications explicitly instead of silently pretending they are exact

### 3. Projection Drift Risk

If projection starts doing semantic reinterpretation again, the architecture will regress.

Mitigation:

- treat projection as formatting only
- review for any parsing logic reintroduced in UI-facing code

## Test Strategy

The new architecture should be validated with five test families.

### 1. Engine Semantic Extraction Tests

For each engine, verify that representative raw outputs become the expected semantic records.

### 2. Normalization Tests

Verify that Codex and Claude semantic records that mean the same thing become the same session domain events.

### 3. Replay Consistency Tests

Verify that:

- live event accumulation
- initial history restore
- older-message prepend

all converge to the same final session state shape.

### 4. Projection Tests

Verify that identical session states produce stable:

- conversation titles
- collapsed summaries
- badges
- clickable targets
- execution prompts

### 5. Feature Flow Tests

Verify end-to-end behavior for:

- session switching
- prompt submission
- engine switching
- approval workflows
- tool user input
- running plan and plan completion
- history open and export

## Non-Goals

This design does not attempt to:

- redesign the visual appearance of the tool window
- introduce a new interaction model for the user-facing product in this phase
- guarantee complete semantic perfection for every possible free-form shell command
- keep the old rendering chain alive as a fallback after merge

## Success Criteria

The redesign is successful when:

- command, tool, and file-change rendering quality no longer depends on UI-side guessing
- Codex and Claude differences are isolated to engine and normalization layers
- live, history, and background sessions use one kernel-driven state pipeline
- feature packages are named by responsibility instead of placement
- adding a third engine does not require timeline- or composer-specific parsing work
- the old translation and area-store rendering chains are fully removed from the final merged branch
