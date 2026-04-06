# Aura Code

[English](README.md) | [中文](README.zh.md) | [日本語](README.ja.md) | [한국어](README.ko.md)

Aura Code는 로컬 Codex 런타임을 IDE 안으로 직접 가져오는 IntelliJ IDEA 플러그인입니다. 채팅, 계획, 승인, Diff 리뷰, 로컬 도구 오케스트레이션을 하나의 프로젝트 범위 워크플로로 통합해 터미널, 브라우저, 편집기 사이를 오가는 부담을 줄입니다.

![Aura Code Preview](docs/img.png)
![Aura Code Preview](docs/img_1.png)

## 주요 기능

- IntelliJ IDEA 안에서 네이티브로 동작하는 `Aura Code` 도구 창
- 프로젝트 단위 채팅 세션의 로컬 저장과 원격 대화 재개 지원
- 백그라운드 실행을 인지하는 멀티탭 세션 워크플로
- 스트리밍 응답, 수동 취소, 재개 가능한 기록 로딩
- 컴포저에서 `@` 파일 멘션, 첨부, `#` 저장된 에이전트, `/` 슬래시 명령 지원
- Plan 모드, Approval 모드, 도구 입력, 실행 중인 계획 피드백
- 변경 파일 집계와 Diff 미리보기, 열기, 적용, 되돌리기 진입점 제공
- 로컬 `stdio` 및 원격 streamable HTTP 서버용 MCP 관리
- 로컬 Skills 검색, 가져오기, 활성화 / 비활성화, Slash 노출, 제거
- IntelliJ Problems 뷰의 `Ask Aura` 를 통한 빌드 오류 전달
- 세션이 포커스를 벗어난 상태에서 끝나면 IDE 알림 제공
- Markdown 대화 내보내기
- 중국어 / 영어 / 일본어 / 한국어 UI 및 라이트 / 다크 / IDE 따르기 테마

## 현재 제품 형태

Aura Code는 현재 IntelliJ IDEA를 대상으로 하며 로컬 Codex 설치를 기반으로 실행됩니다. 플러그인은 Codex app-server 흐름을 중심으로 구성되어 자체 프로젝트 로컬 상태를 유지하며, 런타임이 지원하는 경우 원격 대화 기록의 페이지 단위 로딩과 재개도 지원합니다.

현재 코드베이스에는 이미 다음 기반이 포함되어 있습니다.

- Compose 기반 도구 창 UI
- SQLite 기반 프로젝트 로컬 세션 저장소
- `codex` 와 `node` 에 대한 런타임 환경 감지
- 계획, 승인, 도구 호출, 파일 변경, 사용자 입력을 위한 구조화 이벤트 파싱
- 런타임, Saved Agents, Skills, MCP, 테마, 언어, 알림 설정 페이지

## 요구 사항

- IntelliJ IDEA와 로컬 Codex 런타임을 실행할 수 있는 macOS / Linux / Windows
- JDK 17
- 플러그인 `sinceBuild = 233` 와 호환되는 IntelliJ IDEA
- `PATH` 에 있는 로컬 `codex` 실행 파일 또는 `Settings -> Aura Code` 에서의 수동 설정
- Codex app-server 가 필요로 할 때 사용 가능한 로컬 `node` 실행 파일

## 로컬 설치

1. 플러그인 ZIP을 빌드합니다.

```bash
./gradlew buildPlugin
```

2. 산출물은 `build/distributions/` 에서 찾을 수 있습니다.
3. IntelliJ IDEA에서 `Settings -> Plugins -> Install Plugin from Disk...` 를 엽니다.
4. 생성된 ZIP을 선택합니다.
5. `Settings -> Aura Code` 를 열고 `Codex Runtime Path` 와 필요 시 `Node Path` 를 확인합니다.

## 개발 실행

플러그인이 로드된 샌드박스 IDE를 시작합니다.

```bash
./gradlew runIde
```

개발 중 유용한 명령:

```bash
./gradlew test
./gradlew buildPlugin
./gradlew verifyPlugin
```

## 사용 방법

1. `View -> Tool Windows -> Aura Code` 를 엽니다.
2. 처음 실행 시 런타임 설정을 확인합니다.
3. 컴포저에 작업을 입력하고 전송합니다.
4. `@` 로 컨텍스트 파일을 추가하고, 파일 / 이미지를 첨부하고, `#` 로 저장된 에이전트를 고르고, `/plan`, `/auto`, `/new` 같은 슬래시 명령을 사용할 수 있습니다.
5. 도구 창 안에서 타임라인 출력, 승인, 계획 프롬프트, 도구 입력 프롬프트, 편집 파일 Diff를 검토합니다.
6. 필요할 때 History에서 이전 세션을 다시 열거나 대화를 Markdown으로 내보낼 수 있습니다.

## 핵심 워크플로

### 채팅과 세션

- 세션은 프로젝트 단위로 분리되어 로컬 SQLite에 저장됩니다
- 도구 창은 여러 세션 탭을 지원합니다
- 탭을 바꿔도 백그라운드 세션은 계속 실행될 수 있습니다
- 포커스를 벗어난 세션이 완료되면 IntelliJ 알림을 띄울 수 있습니다

### 계획과 실행 제어

- 컴포저에서 `Auto` 와 `Approval` 두 실행 모드를 모두 제공합니다
- `Plan` 모드는 계획 생성, 수정 요청, 직접 실행을 지원합니다
- 구조화된 도구 입력 프롬프트로 IDE 안에서 실행을 일시 중지하고 답변을 받을 수 있습니다

### 컨텍스트와 파일 변경

- 자동 컨텍스트는 활성 편집기 파일과 선택 텍스트를 따릅니다
- 수동 파일 컨텍스트, 파일 mention, 첨부를 지원합니다
- 변경 파일은 채팅별로 집계되어 Diff / 열기 / 되돌리기 작업을 제공합니다

### Skills 와 MCP

- 표준 로컬 폴더에서 로컬 Skills를 검색할 수 있습니다
- Skills는 가져오기, 활성화, 비활성화, 열기, 위치 확인, 제거를 지원합니다
- MCP 서버는 JSON 관리, 서버별 활성화, 새로고침, 인증, 테스트를 지원합니다
- `stdio` 와 streamable HTTP 두 전송 방식을 모두 지원합니다

### 빌드 오류 분석

- IntelliJ Problems 뷰에 `Ask Aura` 작업이 제공됩니다
- 선택한 빌드 / 컴파일 오류를 파일 및 위치 정보와 함께 Aura Code로 직접 보낼 수 있습니다

## 프로젝트 구조

```text
src/main/kotlin/com/auracode/assistant/
  actions/         빠른 열기, 빌드 오류 전달 같은 IntelliJ 액션
  provider/        Codex provider, app-server 브리지, 엔진 통합
  service/         채팅 / 세션 오케스트레이션 및 런타임 서비스
  persistence/     SQLite 기반 로컬 세션 저장소
  toolwindow/      컴포저, 타임라인, 설정, 기록, 승인을 위한 Compose UI
  settings/        영속 설정, Skills, MCP, 저장된 Agents
  protocol/        통합 이벤트 모델과 파서 레이어
  integration/     빌드 오류 캡처 같은 IDE 통합 흐름
src/test/kotlin/com/auracode/assistant/
  ...              서비스, 프로토콜 파싱, UI 스토어, 핵심 흐름 단위 테스트
```

## 디버깅 메모

플러그인이 Codex와 통신하지 못하면:

- `codex` 가 실행 가능한지 확인
- 설정한 경우 `node` 도 실행 가능한지 확인
- `Settings -> Aura Code -> Test Environment` 사용
- `Help -> Show Log in Finder/Explorer` 에서 IDE 로그 확인

기록이나 재개가 올바르지 않다면:

- 런타임이 플러그인 외부에서 인증되었는지 확인
- 같은 세션을 재개하고 있는지 확인
- 원격 대화 기록 로딩과 로컬 세션 저장을 각각 분리해서 점검

## 오픈소스 상태

- 현재 저장소는 IntelliJ IDEA 지원에 초점을 맞추고 있습니다
- 로컬 ZIP 설치를 지원합니다
- Marketplace 서명 및 게시 흐름은 아직 이 저장소에 연결되지 않았습니다

## 라이선스

Aura Code는 Apache License 2.0에 따라 배포됩니다. 전체 라이선스 본문은 프로젝트 루트의 `LICENSE` 파일을 참고하세요.
