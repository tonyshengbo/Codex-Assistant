# Changelog

All notable changes to this project will be documented in this file.

## [1.0.0-beta.1] - 2026-04-07

### Added

- Japanese and Korean UI localization across the core plugin surfaces.
- An About settings page to make runtime and product information easier to discover.
- More IDE entry points, including selected-code and current-file actions that route context directly into Aura Code.
- AI-assisted commit message generation from the IntelliJ VCS workflow.

### Changed

- Improved the tool window coordination architecture by splitting event handling into more focused components.
- Hardened Codex protocol parsing and local environment detection for the app-server based runtime flow.
- Expanded the documented install path for this beta release to focus on GitHub-distributed ZIP artifacts.

### Fixed

- Added earlier Skiko library path initialization to reduce Windows startup issues in desktop rendering.
- Improved Gradle runtime classpath artifact ordering to make plugin packaging more predictable.

## [0.1.0-alpha.1] - 2026-03-31

### Added

- Initial public alpha release of Aura Code for IntelliJ IDEA.
- Native Aura Code tool window for in-IDE coding conversations.
- Project-scoped session management with local persistence and multi-tab support.
- Streaming chat responses with cancellation support.
- Remote conversation resume and history loading through the local Codex runtime.
- Plan mode and approval mode workflows inside the composer.
- Tool user input handling for structured follow-up questions during execution.
- Support for file mentions, manual context injection, and file or image attachments.
- Edited-file aggregation with diff preview, open, accept, and revert entry points.
- Local Skills discovery, import, enable or disable, uninstall, and slash integration.
- MCP server management for stdio and streamable HTTP transports.
- Build error handoff from the IntelliJ Problems view through `Ask Aura`.
- Background completion notifications for sessions that finish out of focus.
- Conversation export to Markdown.
- Local runtime environment detection for Codex and Node.
- Chinese and English UI support, plus follow-IDE, light, and dark theme modes.
