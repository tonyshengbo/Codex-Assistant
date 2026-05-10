# Aura Code

[English](README.md) | [中文](README.zh.md) | [日本語](README.ja.md) | [한국어](README.ko.md)

Aura Code is an IntelliJ IDEA plugin that turns Codex and Claude into one native IDE workflow. It combines multi-session chat, planning, approvals, file-aware execution, runtime management, and local tooling control so you can stay inside the editor instead of splitting work across terminal, browser, and IDE panes.

![Aura Code Preview](docs/img.png)
![Aura Code Preview](docs/img_1.png)

## Product Positioning

Aura Code is a dual-engine AI assistant for IntelliJ IDEA:

- Run Codex and Claude from one tool window
- Keep project-scoped sessions, history, and edited files inside the IDE
- Use local CLI-based workflows without losing approvals, context control, or diff review
- Manage runtimes, Skills, MCP servers, and token usage from the same workspace

## Beta Release

`1.0.0-beta.4` is currently distributed as a GitHub prerelease ZIP and a manually uploaded Marketplace build.

- Download the plugin ZIP from the GitHub Release assets, or build it locally with `./gradlew buildPlugin`
- Install it from `Settings -> Plugins -> Install Plugin from Disk...`
- The repository does not automate Marketplace publishing; use the generated ZIP for manual upload when needed

## Key Capabilities

- Unified Codex and Claude sessions inside one native `Aura Code` tool window
- Project-scoped multi-tab conversations with local persistence, remote resume, and history export
- Streaming responses, background execution awareness, and completion notifications
- `@` file mentions, file or image attachments, `#` saved agents, and `/` slash commands such as `/plan`, `/auto`, `/init`, `/new`, and `/tab`
- Plan mode, approval prompts, tool user input, and running-plan feedback in the conversation flow
- Edited-file aggregation with diff preview, open, accept, and revert actions
- Runtime setup for Codex CLI, Claude CLI, and optional Node support when required by the selected runtime
- Built-in CLI version visibility, update checks, and upgrade entry points
- Local Skills discovery, import, enable or disable, slash exposure, and uninstall
- MCP server management for `stdio` and streamable HTTP transports
- Historical token usage views by engine, range, and model
- Build-error handoff from IntelliJ Problems via `Ask Aura`
- Chinese, English, Japanese, and Korean UI support with theme and UI scaling controls

## Architecture Overview

The current plugin is structured around a dual-engine session pipeline rather than a single-runtime bridge.

- `provider/codex`, `provider/claude`, and `provider/runtime` handle engine-specific launches, protocol parsing, version checks, and environment resolution
- `session/kernel`, `session/normalizer`, and `session/projection` turn provider events into stable session state and UI projections
- `persistence/chat` stores project-local sessions, history, and token usage ledgers in SQLite
- `toolwindow/submission`, `toolwindow/conversation`, `toolwindow/execution`, `toolwindow/sessions`, `toolwindow/history`, and `toolwindow/settings` render the native Compose workflow
- `settings/skills` and `settings/mcp` manage local Skills and MCP server configuration
- `integration/build` and `integration/ide` connect IDE actions such as build-error handoff and contextual file requests

## Requirements

- macOS, Linux, or Windows capable of running IntelliJ IDEA
- JDK 17
- IntelliJ IDEA compatible with plugin `sinceBuild = 233`
- Local `codex` and/or `claude` executable available on `PATH`, or configured in `Settings -> Aura Code -> Runtime`
- Local `node` executable when the selected Codex runtime flow requires it

## Install For Local Use

1. Build the plugin ZIP:

```bash
./gradlew buildPlugin
```

2. Find the artifact in `build/distributions/`.
3. In IntelliJ IDEA, open `Settings -> Plugins -> Install Plugin from Disk...`.
4. Select the generated ZIP.
5. Open `Settings -> Aura Code -> Runtime` and verify the Codex CLI path, Claude CLI path, and optional Node path.

## Run In Development

Start a sandbox IDE with the plugin loaded:

```bash
./gradlew runIde
```

Useful development commands:

```bash
./gradlew test
./gradlew buildPlugin
./gradlew verifyPlugin
```

## Core Workflows

### Sessions And Engines

- Switch between Codex and Claude while keeping project-scoped session state inside the plugin
- Open multiple session tabs and let background runs continue when you change focus
- Resume previous work from local history and remote conversation identifiers when supported by the active engine

### Planning And Execution

- Use `Plan`, `Auto`, and approval-oriented flows directly from the composer
- Review running-plan status, plan revision prompts, and structured tool user input inside the same session timeline
- Keep execution decisions inside the IDE instead of dropping back to raw CLI output

### Context, Files, And History

- Follow the active editor file or selected text automatically
- Add manual file context, attachments, and saved-agent prompts when the task needs more control
- Review changed files, open diffs, copy message content, and export conversations to Markdown

### Runtime, Skills, And MCP

- Configure Codex CLI and Claude CLI independently from the Runtime settings page
- Track version status, updates, and upgrade actions for supported installation sources
- Manage local Skills and MCP servers without leaving IntelliJ IDEA
- Review historical token usage by engine, time range, and model

## Project Structure

```text
src/main/kotlin/com/auracode/assistant/
  actions/            IntelliJ entry points such as quick open and build-error handoff
  provider/           Codex, Claude, runtime, and provider-session integration
  session/            Session kernel, event normalization, and UI projection layers
  persistence/chat/   SQLite-backed session history and token usage storage
  toolwindow/         Compose UI for submission, conversation, execution, history, sessions, and settings
  settings/           Persistent plugin settings plus Skills and MCP support
  integration/        IDE-facing bridges for build problems and contextual file requests
  protocol/           Shared provider protocol models
src/test/kotlin/com/auracode/assistant/
  ...                 Unit tests for providers, services, stores, and workflow behavior
```

## Debugging Notes

If the plugin cannot launch a runtime:

- Verify `codex` and/or `claude` is executable
- Verify `node` is executable when configured for the selected Codex flow
- Use `Settings -> Aura Code -> Runtime` to validate executable paths
- Check IDE logs from `Help -> Show Log in Finder/Explorer`

If history or resume looks wrong:

- Confirm the active runtime is authenticated outside the plugin
- Verify the session is being resumed on the same engine
- Check remote history loading and local persistence separately

## Open Source Status

- The repository currently focuses on IntelliJ IDEA support
- GitHub prerelease ZIP distribution and local ZIP install are supported
- Marketplace delivery currently relies on manual ZIP upload rather than repo-managed publishing automation

## License

Aura Code is licensed under the Apache License 2.0. See the root `LICENSE` file for the full license text.
