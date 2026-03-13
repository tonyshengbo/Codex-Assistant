# Codex Assistant (English)

For Chinese documentation, see: [README.zh.md](README.zh.md)

## Overview

Codex Assistant is an IntelliJ IDEA plugin that provides a unified Agent workflow over pluggable engines. The current Codex integration uses the local Codex CLI, including native multi-turn continuation through the CLI session `thread_id`.

## Implemented Features

- Unified Tool Window (`Codex Chat`)
- Engine selector driven by provider registry (current engine: `codex`)
- Streaming response + cancel
- Native multi-turn continuation within each saved session
- Action type: `CHAT`
- Context input: current file + manually attached files
- Project-local session persistence
- Settings page for local Codex CLI path

## Requirements

- IntelliJ IDEA 2023.3+
- JDK 17
- Local `codex` CLI available on `PATH`, or configured in `Settings -> Tools -> Codex Assistant`

## Build and Install

1. Build the plugin:
```bash
./gradlew buildPlugin
```
2. Find the ZIP in `build/distributions/`.
3. Install ZIP in IntelliJ IDEA.
4. Configure the Codex CLI path in `Settings -> Tools -> Codex Assistant` if `codex` is not already on `PATH`.

## Usage

1. Open `View -> Tool Windows -> Codex Chat`
2. Select engine
3. Enter prompt and send
4. Optional: attach extra context files
5. Continue within the same saved session to reuse native conversation state

## Debugging Guide

### 1) Local plugin debugging (recommended)

```bash
./gradlew runIde
```

- This launches a sandbox IntelliJ instance.
- Open any project in sandbox and test the Tool Window flows.
- Best for UI behavior, streaming, command confirmation, and diff apply debugging.

### 2) Build failure diagnostics

```bash
./gradlew buildPlugin --stacktrace
./gradlew buildPlugin --info
```

Check:
- IntelliJ platform/plugin compatibility in `build.gradle.kts`
- Local JDK version (must be 17)
- Network access to JetBrains Maven repositories

### 3) Codex CLI diagnostics

Verify in plugin settings:
- Codex CLI path is correct
- `codex exec --help` works in your local environment
- The CLI account/session is already authenticated outside the plugin

### 4) Runtime issue diagnostics

In sandbox IntelliJ, open logs:
- `Help -> Show Log in Finder/Explorer`
- Focus on stack traces, `Codex cli raw:` lines, and `Codex cli summary:` lines

### 5) Session/state checks

- Reopen project and verify session restore.
- Switch engine and verify provider change takes effect.
- Cancel during streaming and verify request termination.
- Ask a follow-up in the same session and verify the plugin reuses the stored CLI `thread_id`.

## Safety Model

- Session history is scoped to the current saved session only.
- Native continuation is driven by the stored Codex CLI session `thread_id` in that session.

## Current Scope

- Current release targets IntelliJ IDEA.
- Multi-JetBrains IDE support is planned for later versions.
