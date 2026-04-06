# Aura Code

[English](README.md) | [中文](README.zh.md) | [日本語](README.ja.md) | [한국어](README.ko.md)

Aura Code is an IntelliJ IDEA plugin that brings the local Codex runtime into the IDE. It is designed for developers who want chat, planning, approvals, diff review, and local tool orchestration in one project-scoped workflow instead of bouncing between terminal, browser, and editor.

![Aura Code Preview](docs/img.png)
![Aura Code Preview](docs/img_1.png)

## What It Does

- Native `Aura Code` tool window inside IntelliJ IDEA
- Project-scoped chat sessions with local persistence and remote conversation resume
- Multi-tab session workflow with background execution awareness
- Streaming responses, cancellation, and resumable history loading
- Composer support for `@` file mentions, attachments, `#` saved agents, and `/` slash commands
- Plan mode, approval mode, tool user input, and running-plan feedback
- Edited-file aggregation with diff preview, open, accept, and revert entry points
- MCP server management for local stdio and remote streamable HTTP servers
- Local Skills discovery, import, enable/disable, slash exposure, and uninstall
- Build error handoff from IntelliJ Problems view through `Ask Aura`
- Background completion notifications when a session finishes out of focus
- Conversation export to Markdown
- Chinese/English/Japanese/Korean UI support and light/dark/follow-IDE theme modes

## Current Product Shape

Aura Code currently targets IntelliJ IDEA and runs against a local Codex installation. The plugin is built around the Codex app-server flow and keeps its own local project state, while also supporting remote conversation history pagination and resume when the runtime provides it.

The current codebase already includes:

- A Compose-based tool window UI
- A SQLite-backed project-local session repository
- Codex runtime environment detection for both `codex` and `node`
- Structured event parsing for plans, approvals, tool calls, file changes, and user-input prompts
- Settings pages for runtime, saved agents, Skills, MCP, theme, language, and notification preferences

## Requirements

- macOS, Linux, or Windows capable of running IntelliJ IDEA and the local Codex runtime
- JDK 17
- IntelliJ IDEA compatible with plugin `sinceBuild = 233`
- Local `codex` executable available on `PATH`, or configured in `Settings -> Aura Code`
- Local `node` executable available when the Codex app-server requires it

## Install For Local Use

1. Build the plugin ZIP:

```bash
./gradlew buildPlugin
```

2. Find the artifact in `build/distributions/`.
3. In IntelliJ IDEA, open `Settings -> Plugins -> Install Plugin from Disk...`.
4. Select the generated ZIP.
5. Open `Settings -> Aura Code` and verify the `Codex Runtime Path` and optional `Node Path`.

## Run In Development

Start a sandbox IDE with the plugin loaded:

```bash
./gradlew runIde
```

Useful commands during development:

```bash
./gradlew test
./gradlew buildPlugin
./gradlew verifyPlugin
```

## How To Use

1. Open `View -> Tool Windows -> Aura Code`.
2. Check runtime settings if this is the first launch.
3. Start a prompt in the composer.
4. Add context with `@`, attach files/images, select saved agents with `#`, or use slash commands such as `/plan`, `/auto`, and `/new`.
5. Review timeline output, approvals, plan prompts, tool-input prompts, and edited-file diffs inside the tool window.
6. Reopen prior sessions from History or export a conversation as Markdown when needed.

## Key Workflows

### Chat And Sessions

- Sessions are project-scoped and persisted locally in SQLite
- The tool window supports multiple open session tabs
- Background sessions can continue running while you switch tabs
- Completed out-of-focus sessions can raise IntelliJ notifications

### Planning And Execution Control

- `Auto` and `Approval` execution modes are both exposed in the composer
- `Plan` mode can generate a plan, request revision, or execute directly
- Structured tool input prompts can pause a run and collect answers inside the IDE

### Context And File Changes

- Auto-context can follow the active editor file and selected text
- Manual file context, file mentions, and attachments are supported
- Changed files are collected per chat and surfaced with diff/open/revert actions

### Skills And MCP

- Local Skills can be discovered from standard local folders
- Skills can be imported, enabled, disabled, opened, revealed, and uninstalled
- MCP servers can be managed as JSON, enabled per server, refreshed, authenticated, and tested
- Both `stdio` transport and streamable HTTP transport are supported

### Build Error Triage

- IntelliJ Problems view exposes an `Ask Aura` action
- Selected build/compiler errors can be sent directly into Aura Code with file and position context

## Project Structure

```text
src/main/kotlin/com/auracode/assistant/
  actions/         IntelliJ actions such as quick open and build-error handoff
  provider/        Codex provider, app-server bridge, and engine integration
  service/         Chat/session orchestration and runtime services
  persistence/     SQLite-backed local session storage
  toolwindow/      Compose UI for composer, timeline, settings, history, approvals
  settings/        Persistent plugin settings, Skills, MCP, saved agents
  protocol/        Unified event models and parser layer
  integration/     IDE-integrated flows such as build error capture
src/test/kotlin/com/auracode/assistant/
  ...              Unit tests for services, protocol parsing, UI stores, and flows
```

## Debugging Notes

If the plugin cannot talk to Codex:

- Verify `codex` is executable
- Verify `node` is executable when configured
- Use `Settings -> Aura Code -> Test Environment`
- Check IDE logs from `Help -> Show Log in Finder/Explorer`

If history or resume looks wrong:

- Confirm the runtime is authenticated outside the plugin
- Verify the same session is being resumed
- Check remote conversation loading and local session persistence separately

## Open Source Status

- The repository is currently focused on IntelliJ IDEA support
- Local ZIP install is supported
- Marketplace signing and publishing are not yet wired into this repo

## License

Aura Code is licensed under the Apache License 2.0. See the root `LICENSE` file for the full license text.
