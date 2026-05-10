# Aura Code

[English](README.md) | [中文](README.zh.md) | [日本語](README.ja.md) | [한국어](README.ko.md)

Aura Code는 Codex와 Claude를 하나의 네이티브 IDE 워크플로로 묶는 IntelliJ IDEA 플러그인입니다. 멀티 세션 대화, 계획, 승인, 파일 문맥 기반 실행, 런타임 관리, 로컬 도구 제어를 하나의 작업 공간에 모아 터미널, 브라우저, IDE 사이를 오가는 부담을 줄입니다.

![Aura Code Preview](docs/img.png)
![Aura Code Preview](docs/img_1.png)

## 제품 포지셔닝

Aura Code는 IntelliJ IDEA를 위한 듀얼 엔진 AI 어시스턴트입니다.

- 같은 도구 창에서 Codex와 Claude를 함께 사용
- 프로젝트 범위의 세션, 기록, 편집 파일을 IDE 안에 유지
- 로컬 CLI 워크플로의 제어력을 유지하면서 승인, 문맥 제어, Diff 리뷰를 통합
- 같은 작업 공간에서 런타임, Skills, MCP 서버, Token 사용량을 관리

## 베타 배포

`1.0.0-beta.4` 는 현재 GitHub prerelease ZIP과 수동 업로드된 Marketplace 빌드로 배포됩니다.

- GitHub Release의 ZIP을 다운로드하거나 `./gradlew buildPlugin` 으로 로컬 빌드
- `Settings -> Plugins -> Install Plugin from Disk...` 에서 설치
- 현재 저장소는 Marketplace 게시를 자동화하지 않으므로 필요할 때 생성된 ZIP을 수동 업로드해야 합니다

## 핵심 기능

- 네이티브 `Aura Code` 도구 창 안에서 Codex와 Claude 세션을 통합 관리
- 로컬 저장, 원격 재개, 기록 내보내기를 지원하는 프로젝트 범위 멀티탭 대화
- 스트리밍 응답, 백그라운드 실행 인지, 완료 알림
- `@` 파일 멘션, 파일 / 이미지 첨부, `#` 저장된 에이전트, `/plan`, `/auto`, `/init`, `/new`, `/tab` 같은 슬래시 명령
- 대화 흐름 안의 Plan 모드, 승인 프롬프트, 도구 입력, 실행 중 계획 피드백
- 변경 파일 집계와 Diff 미리보기, 열기, 적용, 되돌리기
- Codex CLI, Claude CLI, 필요 시 Node를 각각 관리하는 런타임 설정
- CLI 버전 표시, 업데이트 확인, 업그레이드 진입점
- 로컬 Skills 검색, 가져오기, 활성화 / 비활성화, Slash 노출, 제거
- `stdio` 및 streamable HTTP 전송을 위한 MCP 서버 관리
- 엔진, 기간, 모델 기준의 Token 사용 기록 조회
- IntelliJ Problems 의 `Ask Aura` 를 통한 빌드 오류 전달
- 중국어, 영어, 일본어, 한국어 UI 및 테마 / UI 스케일 설정

## 아키텍처 개요

현재 플러그인은 단일 런타임 브리지보다 듀얼 엔진 세션 파이프라인을 중심으로 구성됩니다.

- `provider/codex`, `provider/claude`, `provider/runtime` 이 엔진 실행, 프로토콜 파싱, 버전 확인, 환경 해석을 담당
- `session/kernel`, `session/normalizer`, `session/projection` 이 Provider 이벤트를 안정적인 세션 상태와 UI 프로젝션으로 변환
- `persistence/chat` 이 SQLite 기반의 프로젝트 로컬 대화 기록과 Token ledger 를 저장
- `toolwindow/submission`, `toolwindow/conversation`, `toolwindow/execution`, `toolwindow/sessions`, `toolwindow/history`, `toolwindow/settings` 이 Compose 기반 네이티브 UI 를 구성
- `settings/skills` 와 `settings/mcp` 가 로컬 Skills 와 MCP 서버 설정을 관리
- `integration/build` 와 `integration/ide` 가 빌드 오류 전달과 IDE 문맥 연동을 제공

## 요구 사항

- IntelliJ IDEA를 실행할 수 있는 macOS / Linux / Windows
- JDK 17
- 플러그인 `sinceBuild = 233` 와 호환되는 IntelliJ IDEA
- `PATH` 에 있거나 `Settings -> Aura Code -> Runtime` 에 설정된 `codex` 및 / 또는 `claude`
- 선택한 Codex 런타임 흐름에서 필요할 때 사용할 수 있는 `node`

## 로컬 설치

1. 플러그인 ZIP을 빌드합니다.

```bash
./gradlew buildPlugin
```

2. 산출물은 `build/distributions/` 에 있습니다.
3. IntelliJ IDEA에서 `Settings -> Plugins -> Install Plugin from Disk...` 를 엽니다.
4. 생성된 ZIP을 선택합니다.
5. `Settings -> Aura Code -> Runtime` 을 열고 Codex CLI, Claude CLI, 필요 시 Node 경로를 확인합니다.

## 개발 실행

플러그인이 로드된 샌드박스 IDE를 시작합니다.

```bash
./gradlew runIde
```

자주 쓰는 명령:

```bash
./gradlew test
./gradlew buildPlugin
./gradlew verifyPlugin
```

## 핵심 워크플로

### 세션과 엔진

- 플러그인 안에서 Codex와 Claude를 전환하면서 프로젝트 범위 세션 상태를 유지
- 여러 세션 탭을 열고 포커스를 바꿔도 백그라운드 실행을 계속 유지
- 활성 엔진이 지원하면 로컬 기록과 원격 대화 식별자에서 이전 작업을 재개

### 계획과 실행

- 컴포저에서 `Plan`, `Auto`, 승인 중심 흐름을 직접 사용
- 실행 중 계획, 계획 수정 프롬프트, 구조화된 도구 입력을 같은 타임라인에서 확인
- 원시 CLI 출력으로 돌아가지 않고 IDE 안에서 실행 결정을 유지

### 문맥, 파일, 기록

- 활성 편집기 파일과 선택 텍스트를 자동으로 추적
- 더 강한 제어가 필요하면 수동 파일 문맥, 첨부, 저장된 에이전트를 추가
- 변경 파일, Diff, 메시지 복사, Markdown 내보내기를 하나의 흐름에서 처리

### 런타임, Skills, MCP

- Runtime 설정 페이지에서 Codex CLI와 Claude CLI를 각각 관리
- 버전 상태, 업데이트 확인, 지원되는 설치 원본의 업그레이드 동작을 확인
- IntelliJ IDEA를 벗어나지 않고 로컬 Skills 와 MCP 서버를 관리
- 엔진, 기간, 모델별 Token 사용 기록을 검토

## 프로젝트 구조

```text
src/main/kotlin/com/auracode/assistant/
  actions/            빠른 열기와 빌드 오류 전달 같은 IntelliJ 진입점
  provider/           Codex, Claude, runtime, provider-session 통합
  session/            세션 커널, 이벤트 정규화, UI 프로젝션 계층
  persistence/chat/   SQLite 기반 대화 기록과 Token 사용 저장
  toolwindow/         입력, 대화, 실행, 기록, 세션 탭, 설정용 Compose UI
  settings/           영구 설정과 Skills / MCP 지원
  integration/        빌드 오류와 IDE 문맥 연동
  protocol/           공용 Provider 프로토콜 모델
src/test/kotlin/com/auracode/assistant/
  ...                 Provider, 서비스, Store, 워크플로 동작 테스트
```

## 디버깅 참고

런타임을 시작할 수 없다면:

- `codex` 및 / 또는 `claude` 가 실행 가능한지 확인
- 현재 Codex 흐름이 `node` 를 사용한다면 해당 실행 가능 여부도 확인
- `Settings -> Aura Code -> Runtime` 에서 실행 경로 설정을 점검
- `Help -> Show Log in Finder/Explorer` 에서 IDE 로그를 확인

기록이나 재개가 올바르지 않다면:

- 현재 런타임이 플러그인 밖에서 인증되었는지 확인
- 같은 엔진에서 세션을 재개하고 있는지 확인
- 원격 기록 로딩과 로컬 영속화를 분리해서 확인

## 오픈소스 현황

- 현재 저장소는 IntelliJ IDEA 지원에 집중하고 있습니다
- GitHub prerelease ZIP 배포와 로컬 ZIP 설치를 지원합니다
- Marketplace 배포는 현재도 생성된 ZIP의 수동 업로드를 전제로 하며 저장소 내 자동 게시 흐름과는 연결되어 있지 않습니다

## 라이선스

Aura Code는 Apache License 2.0에 따라 배포됩니다. 전체 라이선스 본문은 프로젝트 루트의 `LICENSE` 파일을 참고하세요.
