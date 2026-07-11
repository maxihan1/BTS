# FR-SL-04 — Slack Slash 명령어 (`/atlas ...`) — 스펙

> 날짜: 2026-07-11 · BC: slack-integration · PR #258
> ADR: [docs/decisions/2026-07-11-fr-sl-04-slash-command.md](../decisions/2026-07-11-fr-sl-04-slash-command.md)
> 우선순위: 높음 · 선행: FR-SL-01(봇 설치)·FR-SL-03(인바운드 서명검증·역매핑)

## 개요

Slack 사용자가 `/atlas <서브커맨드> [인자]`를 입력하면 BTS가 처리해 **ephemeral**(호출자만 보이는) 응답을 되돌린다. 4종 서브커맨드 — `help`·`view`·`search`·`create`. 모든 명령은 Slack user → BTS user 역매핑으로 해석한 사용자의 **권한으로** 실행된다(fail-closed).

## 사용자 시나리오 (Given-When-Then)

### S1. help — 사용법 안내
- **Given** 사용자가 Slack에서 `/atlas` 또는 `/atlas help` 입력
- **When** BTS가 요청을 서명 검증 후 수신
- **Then** 4종 서브커맨드 사용법을 ephemeral로 응답. 미매핑 사용자면 상단에 "계정 연결 필요" 안내 포함

### S2. view — 이슈 카드 조회
- **Given** 계정 연결된 사용자가 `/atlas view PROJ-123` 입력, PROJ-123을 볼 권한 있음
- **When** 수신·역매핑·`IssueUnfurlPort.getVisibleIssueCard(PROJ-123, viewerUserId)` 호출
- **Then** 이슈 요약 카드(키+제목·상태·우선순위·담당자)를 ephemeral로 응답
- **변형 S2a**. 볼 수 없음/존재하지 않음(카드 null) → "PROJ-123을 찾을 수 없거나 접근 권한이 없습니다" ephemeral (fail-closed — 존재/부재 구분 노출 안 함)

### S3. search — AQL 검색
- **Given** 연결된 사용자가 `/atlas search PROJ status = open` 입력
- **When** 수신·역매핑·`SlashIssueSearchPort.search("status = open", "PROJ", viewerUserId, page=0, size=N)` 호출 (search BC가 AQL 파싱 + visibility AND 결합)
- **Then** 상위 N건 결과 목록(키·제목·상태) 카드를 ephemeral로 응답. `total`과 "N/total 표시" 표기
- **변형 S3a**. 결과 0건 → "조건에 맞는 이슈가 없습니다" ephemeral
- **변형 S3b**. AQL 문법 오류 → "검색 문법 오류: <간단 사유>" ephemeral (search BC가 파싱 실패를 결과 타입으로 전달)
- **변형 S3c**. 프로젝트 키 누락(`/atlas search`만) → 사용법 오류 ephemeral

### S4. create — 이슈 생성
- **Given** 연결된 사용자가 `/atlas create PROJ 로그인 버튼이 동작 안 함` 입력, PROJ에 CREATE_ISSUE 권한 있음
- **When** 수신·역매핑·`IssueImportPort.importIssue(IssueImportCommand(projectKey="PROJ", requesterUserId=viewerUserId, summary="로그인 버튼이 동작 안 함"))`
- **Then** `Success(issueKey="PROJ-42")` → "이슈를 만들었습니다: PROJ-42 <링크>" ephemeral
- **변형 S4a**. 제목이 따옴표로 감싸짐(`create PROJ "여러 단어 제목"`) → 감싼 따옴표 제거 후 summary
- **변형 S4b**. `Failure(reasonCode)` 매핑 — `FORBIDDEN`→"생성 권한이 없습니다", `NOT_FOUND`→"프로젝트를 찾을 수 없습니다", `WORKFLOW_NOT_CONFIGURED`→"프로젝트에 워크플로우가 없습니다", `VALIDATION`→"제목이 비어 있습니다", 그 외→"이슈를 만들지 못했습니다"
- **변형 S4c**. 프로젝트 키/제목 누락 → 사용법 오류 ephemeral

### S5. 공통 실패 경로
- **S5a 미매핑 사용자**(view/search/create) → "먼저 Atlas 계정을 Slack에 연결해 주세요(<연결 안내>)" ephemeral. help는 예외(안내 자체가 목적)
- **S5b 서명 검증 실패**(위조·헤더 누락·재전송·secret 미설정) → 빈 401, 본문 없음(비밀값/원문/예외 미노출)
- **S5c 알 수 없는 서브커맨드**(`/atlas frobnicate`) → help 안내로 폴백

## 기능 요구사항 (FR)

- **FR-1** `POST /slack/commands`가 `application/x-www-form-urlencoded` 요청을 수신한다. `@RequestBody String rawBody`로 원문을 그대로 받아 서명 검증을 **먼저** 수행하고, 통과 후에만 form-decode 한다.
- **FR-2** 서명 검증은 `SlackSignatureVerifier.isValid(ts, sig, rawBody)`를 재사용한다(스킴 동일). 실패 시 빈 401.
- **FR-3** form 필드 파싱 — `command`·`text`·`user_id`·`team_id`·`channel_id`·`response_url`·`trigger_id`. 필수 필드 누락 시 방어적으로 빈 200(무시).
- **FR-4** `text`를 `SlashCommand`로 파싱한다. 첫 토큰 = 서브커맨드(대소문자 무시). 빈 `text`/미지원 서브커맨드 → `help` 폴백.
  - `view <KEY>` — 두 번째 토큰 = 이슈 키
  - `search <PROJ> <aql...>` — 두 번째 토큰 = 프로젝트 키, 나머지 = AQL 문자열
  - `create <PROJ> <title...>` — 두 번째 토큰 = 프로젝트 키, 나머지 = 제목(감싼 따옴표 있으면 제거)
- **FR-5** 모든 명령은 `findUserIdBySlackUserId(user_id, team_id)`로 BTS 사용자를 해석하고 그 권한으로 실행한다. 미매핑 시 help 외 명령은 계정 연결 안내(fail-closed).
- **FR-6** `view` = `IssueUnfurlPort.getVisibleIssueCard` 재사용. null → "찾을 수 없거나 권한 없음".
- **FR-7** `search` = 신규 `SlashIssueSearchPort.search(rawAql, projectKey, viewerUserId, page, size)`. search-export-import 어댑터가 `AqlLexer`+`AqlParser`로 파싱 후 `IssueSearchPort`에 위임. 파싱 실패는 결과 타입(`SlashSearchOutcome.SyntaxError`)으로 전달(예외 클래스명 매칭 금지).
- **FR-8** `create` = `IssueImportPort.importIssue` 재사용. `Success.issueKey` → 성공 메시지, `Failure.reasonCode` → 코드별 메시지.
- **FR-9** 3초 룰 — 컨트롤러는 **즉시 빈 200 ack**(본문 없음 — 사용자는 이어질 response_url 응답만 본다)하고, 명령 처리 + 응답 렌더 + `response_url` POST는 `@Async`로 수행한다. `@Async` 작업에는 SecurityContext가 없으므로(별도 스레드) 핸들러는 역매핑으로 해석한 `viewerUserId`를 cross-BC 포트에 **명시 파라미터로** 전달한다(SecurityContextHolder 미의존, FR-AT-02 async-safe 동형).
- **FR-9a** `command` 필드가 `/atlas`가 아니면(엔드포인트가 다른 명령을 수신) 방어적으로 help 폴백 또는 빈 200. 엔드포인트는 `/atlas` 전용을 전제한다.
- **FR-10** 응답은 `SlackResponseUrlClient`가 `response_url`에 `{"response_type":"ephemeral","blocks":[...]}` POST. 봇 토큰 불요.
- **FR-11** Block Kit 렌더 — `SlackBlockKitRenderer` 확장(help 텍스트·이슈 카드·검색 목록·생성 결과·오류 메시지 렌더 메서드).

## 비기능 요구사항 (NFR)

- **NFR-1 (3초)** 컨트롤러 ack p95 < 3s(실 처리는 async). Slack 재전송(`X-Slack-Retry-Num`)에도 멱등 — 재실행이 안전한 읽기이거나(view/search/help), create는 동일 요청 재전송이 드물고 best-effort(중복 생성 가능성은 수용, 후속 dedup 후보로 명시).
- **NFR-2 (비밀 미노출)** signing secret·rawBody·봇 토큰·예외 스택은 로그/응답에 절대 담지 않는다. 서명 실패는 빈 401.
- **NFR-3 (DoS)** `text` 길이 상한(예: 4000자), AQL 길이 상한(2000자, `SearchController` 일치), 검색 size 상한(예: 10건 — Slack 카드 가독성). 초과 시 거부/절단(명시).
- **NFR-4 (권한 fail-closed)** 모든 cross-BC 조회/쓰기는 매핑된 BTS 사용자 권한 경유. 권한 없으면 노출/생성 0. 미매핑=미인증 취급.
- **NFR-5 (BC 격리)** slack은 issue-tracking/search-export-import 내부 import 0. shared-kernel 포트만 참조(ArchUnit 강제).

## API 인터페이스

### 인바운드 — `POST /slack/commands`
- Content-Type: `application/x-www-form-urlencoded`
- Headers: `X-Slack-Request-Timestamp`, `X-Slack-Signature`
- Body(form): `command=/atlas&text=...&user_id=U..&team_id=T..&channel_id=C..&response_url=https%3A%2F%2F..&trigger_id=..`
- 응답: 즉시 200(빈 본문 또는 경량 ephemeral ack). 서명 실패 시 빈 401

### 아웃바운드 — `response_url` POST (지연 응답)
- `POST <response_url>` `Content-Type: application/json`
- Body: `{"response_type":"ephemeral","blocks":[<Block Kit>],"replace_original":false}`

## 데이터 모델 변경

**없음.** `user_slack_mapping`(V701)·역방향 인덱스(V702, FR-SL-03)를 그대로 재사용. 신규 마이그레이션 0.

## 신규/변경 코드 표면 (모듈별)

- **shared-kernel** (신규): `SlashIssueSearchPort` + `SlashSearchOutcome`(Success page | SyntaxError) + `SlashSearchHit`(또는 기존 `IssueSearchHit` 재사용)
- **search-export-import** (신규 어댑터): `SlashIssueSearchAdapter` — `AqlLexer`+`AqlParser` 파싱 → `IssueSearchPort` 위임, 파싱 실패는 `SyntaxError`로 매핑
- **slack-integration** (주): `SlackCommandsController`(`POST /slack/commands`)·`SlashCommandParser`+`SlashCommand` VO·`SlashCommandService`(@Async 오케스트레이션)·서브커맨드 핸들러(help/view/search/create)·`SlackResponseUrlClient`·`SlackBlockKitRenderer` 확장·test-boot SecurityConfig permitAll(`/slack/commands`)
- **issue-tracking**: 변경 0(IssueUnfurlPort·IssueImportPort 기존 어댑터 재사용)

**cross-BC 테스트 배선(plan 주의).** slack 모듈 test-boot에는 issue-tracking/search 어댑터가 없다. 따라서 slack 통합 테스트는 `IssueUnfurlPort`·`IssueImportPort`·`SlashIssueSearchPort` 3종을 test-double(`@TestConfiguration` fake)로 제공해야 한다(교훈 no-cross-bc-deployment-assembly·new-crossbc-dep-openapi-mockbean-regression). 실 파싱+위임 검증은 search-export-import 모듈의 `SlashIssueSearchAdapter` 테스트가 담당.

## 엣지 케이스

1. 미매핑 사용자 → help 외 계정 연결 안내(silent 아님 — slash는 명시 호출)
2. 알 수 없는 서브커맨드 / 빈 text → help 폴백
3. `view` 이슈 키 형식 오류/미존재/무권한 → "찾을 수 없거나 권한 없음"(fail-closed, 존재 여부 노출 안 함)
4. `search` AQL 문법 오류 → 결과 타입 SyntaxError → ephemeral 오류(예외 클래스명 매칭 금지, 교훈 crossbc-failure-classification-typed-not-name)
5. `search`/`create` 프로젝트 키 누락 → 사용법 오류
6. `search` 결과 0건 → "결과 없음"(빈 목록 정상)
7. `create` 따옴표 감싼 제목 → 따옴표 제거. 제목 공백 → VALIDATION 실패 메시지
8. `create` reasonCode 전부 매핑(FORBIDDEN/NOT_FOUND/TYPE_NOT_FOUND/WORKFLOW_NOT_CONFIGURED/VALIDATION/ADAPTER_UNAVAILABLE/UNKNOWN)
9. `response_url` POST 실패 → best-effort(로그만, 사용자에겐 이미 ack). 재시도 없음
10. Slack 재전송(3s 초과/네트워크) → ack 멱등. create 중복 생성 가능성은 수용(후속 dedup 후보)
11. 서명 재전송 윈도우(±5분) 밖 → 401
12. text 길이/AQL 길이/검색 size 상한 초과 → 거부 또는 절단
13. secret 미설정 부팅 → 빈 등록(부팅 통과), 검증 호출 시점 거부(fail-closed)

## 제약 조건

- BC 격리 — slack↔issue-tracking/search import 0(shared-kernel 포트만). ArchUnit 강제
- 신규 마이그레이션 0
- prod SecurityConfig 중앙 permitAll 등록은 배포 조립 후속(ADR D8). 이번 PR은 test-boot로만 검증
- TDD red→green 강제(test: 커밋이 feat: 앞)
- wave는 Gradle 모듈 컴파일 직렬화(shared-kernel → search/slack, 교훈 bts-plan-wave-gradle-module-compile)

## 측정 가능한 완료 기준

- [ ] `POST /slack/commands` 유효 서명 4종 명령 통합 테스트 green(help/view/search/create happy)
- [ ] 서명 실패 401·미매핑 안내·문법오류·무권한·결과0·따옴표제목·reasonCode 매핑 각 테스트 green
- [ ] `SlashIssueSearchPort` 어댑터가 AQL 파싱+visibility 위임(문법오류=SyntaxError) 테스트 green
- [ ] slack·shared-kernel·search-export-import 3모듈 컴파일 + 전체 테스트 green
- [ ] ktlint/detekt(--rerun-tasks) clean
- [ ] ArchUnit BC 격리 통과(slack이 issue/search 내부 import 0)
- [ ] verify-master-plan 통과(FR 카운트 불변 — D-step 완료라 총수 123 유지)
- [ ] E2E: 가짜 Slack slash 요청(유효 서명) → response_url mock 캡처로 4종 검증

## Brainstorming Check ✅

자체 적대적 gap 리뷰(결정 잠긴 백엔드 명세라 office-hours 대신 — 교훈 bts-spec-office-hours-mismatch). Maxi 결정 필요 gap 0. 스펙 보강 4건 반영: (1) ack=빈 200 확정, (2) command 비-atlas 폴백, (3) @Async SecurityContext 부재→viewerUserId 명시 전달, (4) cross-BC 3포트 test-double 배선. 남은 항목(create 중복생성 dedup·검색 페이지네이션·prod SecurityConfig 중앙등록)은 의도적 후속으로 명시.
