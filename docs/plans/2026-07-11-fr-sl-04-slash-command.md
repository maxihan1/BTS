# FR-SL-04 — Slack Slash 명령어 (`/atlas ...`)

> slug: fr-sl-04-slash-command
> type: api
> agent: backend-engineer (+ security-engineer 권한 가드 검토)
> primary_bc: slack-integration
> 생성: 2026-07-11

## Brief

FR-SL-04 Slack Slash 명령어. Slack에서 `/atlas ...` 명령을 입력하면 BTS가 응답.
- 인바운드 slash command 엔드포인트 (`POST /slack/commands`)
- X-Slack-Signature 서명 검증 (FR-SL-03 패턴 재사용)
- Slack 사용자 → BTS 사용자 매핑 (user_slack_mapping 역매핑, V702 재사용)
- 권한 가드 (매핑된 BTS 사용자 권한으로 실행)
- 명령어. `/atlas search <aql>`, `/atlas create <title>` 등

classify: type=api, agent=backend-engineer, primary_bc=slack-integration

## 도메인 정리

- **BC**. slack-integration (`com.bts.slack`) — 인바운드 slash command 수신
- **영향 엔티티/포트**.
  - (신규) `SlashCommand` VO — 파싱된 `/atlas <sub> <args>` (도메인 개념, 영속화 없음)
  - (신규 cross-BC) shared-kernel `SlashIssueSearchPort` — raw AQL 문자열 검색 (search-export-import 어댑터가 파싱+`IssueSearchPort` 위임)
  - (재사용) `IssueUnfurlPort.getVisibleIssueCard` — `view <KEY>` 이슈 카드 (FR-SL-03 fail-closed 결합 포트)
  - (재사용) `IssueImportPort.importIssue` — `create` 이슈 생성 (CREATE_ISSUE 게이트+단일 tx)
  - (재사용) `SlackUserMappingRepository.findUserIdBySlackUserId` — Slack→BTS 역매핑 (FR-SL-03)
  - (재사용) `SlackSignatureVerifier` — 서명 스킴 동일, form-urlencoded raw body에 그대로 적용
- **새 용어**. "Slash 명령어"(Slack `/atlas` 명령), "서브커맨드"(help/view/search/create). glossary 추가 후보 (Maxi 확인).
- **Maxi 도메인 결정 (2026-07-11)**.
  1. 서브커맨드 4종 전부 — help / view `<KEY>` / search `<aql>` / create `<title>`
  2. search/create 프로젝트 스코프 = **인라인 인자 필수** (`/atlas search PROJ status=open`, `/atlas create PROJ "제목"`) — FR-SL-06 채널매핑 의존 회피
  3. 응답 노출 = **ephemeral** (호출자만)
- **기존 결정 충돌**. 없음. FR-SL-03 인바운드 서명검증·역매핑 기반을 재사용해 확장.
- **관련 ADR**. [docs/decisions/2026-07-11-fr-sl-04-slash-command.md](../decisions/2026-07-11-fr-sl-04-slash-command.md) (생성됨)

## 스펙

전체 스펙. [docs/specs/2026-07-11-fr-sl-04-slash-command.md](../specs/2026-07-11-fr-sl-04-slash-command.md)

핵심 시나리오.
- `POST /slack/commands`(form-urlencoded) 수신 → 서명검증(재사용) 선행 → form-decode → `/atlas <sub> <args>` 파싱
- 즉시 빈 200 ack → `@Async`로 처리+`response_url`에 ephemeral 응답 POST(봇토큰 불요)
- help(안내)·view(IssueUnfurlPort 재사용)·search(신규 SlashIssueSearchPort, search BC 파싱)·create(IssueImportPort 재사용)
- 모든 명령 = Slack→BTS 역매핑 사용자 권한(fail-closed). 미매핑 → 계정 연결 안내
- 신규 마이그레이션 0 · issue-tracking 변경 0 · prod SecurityConfig 중앙등록은 배포조립 후속

## Brainstorming Check

✅ 통과 (자체 적대적 gap 리뷰 — 결정 잠긴 백엔드 명세라 office-hours 대신). Maxi 결정 gap 0. 스펙 보강 4건 반영(ack=빈200·command폴백·@Async viewerUserId 명시전달·cross-BC 3포트 test-double). 의도적 후속(create dedup·검색 페이지네이션·prod SecurityConfig)은 명시.

## Plan

> 3모듈 영향. 컴파일 순서 = shared-kernel(T1) → search-export-import(T2)·slack(T3~T9). wave는 Gradle 모듈 컴파일 직렬화(교훈 bts-plan-wave-gradle-module-compile). issue-tracking 변경 0. 마이그레이션 0.

### Task 1. shared-kernel — `SlashIssueSearchPort` + `SlashSearchOutcome`

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/search/SlashIssueSearchPort.kt`, `backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/search/SlashSearchQuery.kt`, `backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/search/SlashSearchOutcome.kt`, `backend/modules/shared-kernel/src/test/kotlin/com/bts/shared/search/SlashIssueSearchPortTest.kt`]
- depends-on: []

**RED**. `SlashIssueSearchPortTest` — 익명 구현(어댑터 부재)의 `search(query)` default가 fail-safe로 `SlashSearchOutcome.Success(IssueSearchPage.empty(...))`를 반환. 클래스 없음으로 실패.

**GREEN**.
- `SlashSearchQuery` = 커맨드 객체(`rawAql, projectKey, viewerUserId, page, size`) — **위치 인자 다중 파라미터 대신 커맨드 객체(BTS 포트 관례, `IssueSearchQuery`/`IssueImportCommand` 선례)**. 후속 확장 시 시그니처 불변.
- `SlashSearchOutcome` = `sealed interface` — `Success(page: IssueSearchPage)` | `SyntaxError(reason: String)`. `IssueSearchPage`/`IssueSearchHit` 재사용(신규 hit 타입 없음).
- `SlashIssueSearchPort.search(query: SlashSearchQuery): SlashSearchOutcome` — default는 빈 페이지 Success(읽기 fail-safe, `IssueSearchPort` 선례). raw 문자열 → 파싱은 어댑터 책임.

**REFACTOR**. KDoc — 파싱 소유는 search BC, 문법오류는 예외 아닌 `SyntaxError` 결과 타입(cross-BC 클래스명 매칭 금지, 교훈 crossbc-failure-classification-typed-not-name). fail-safe 방향(읽기=빈결과) 명시.

**검증**. `./gradlew :backend:modules:shared-kernel:test --tests '*SlashIssueSearchPortTest'`

### Task 2. search-export-import — `SlashIssueSearchAdapter` (AQL 파싱 + 위임)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/search-export-import/src/main/kotlin/com/bts/search/slash/SlashIssueSearchAdapter.kt`, `backend/modules/search-export-import/src/test/kotlin/com/bts/search/slash/SlashIssueSearchAdapterTest.kt`]
- depends-on: [1]

**RED**. `SlashIssueSearchAdapterTest` — fake `IssueSearchPort`(고정 페이지 반환) + 실 `AqlLexer`/`AqlParser`. (a) 유효 AQL → `Success(page)`, (b) 문법 오류 AQL(`AqlSyntaxException`) → `SyntaxError(reason)`(예외 전파 아님), (c) AQL 길이 상한 초과 → SyntaxError/거부. 클래스 없음으로 실패.

**GREEN**. `SlashIssueSearchAdapter(@Component, implements SlashIssueSearchPort)`. `search(query: SlashSearchQuery)` → `AqlLexer(query.rawAql).tokenize()` → `AqlParser(...).parse()` → `IssueSearchQuery(query.projectKey, ast, sort, query.viewerUserId, query.page, query.size)` → `issueSearchPort.search(...)` → `Success`. `AqlSyntaxException` catch → `SyntaxError(e.message 요약)`. `SearchController` 파싱 흐름 재사용(중복 최소화 — 필요시 공용 파싱 헬퍼 추출).

**REFACTOR**. 파싱 로직이 `SearchController`와 겹치면 공용 `AqlQueryCompiler`로 추출 검토(과설계면 보류, KDoc로 중복 사유 명시). visibility 필터는 `IssueSearchPort` 구현체(issue-tracking) 책임임을 KDoc 명시.

**검증**. `./gradlew :backend:modules:search-export-import:test --tests '*SlashIssueSearchAdapterTest'`

### Task 3. slack — `SlashCommand` VO + `SlashCommandParser`

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/slack-integration/src/main/kotlin/com/bts/slack/command/SlashCommand.kt`, `backend/modules/slack-integration/src/main/kotlin/com/bts/slack/command/SlashCommandParser.kt`, `backend/modules/slack-integration/src/test/kotlin/com/bts/slack/command/SlashCommandParserTest.kt`]
- depends-on: []

**RED**. `SlashCommandParserTest` — `parse(text)` 케이스: 빈/`help`→Help, `view PROJ-1`→View(key), `search PROJ status=open`→Search(projectKey, aql), `create PROJ 여러 단어 제목`→Create(projectKey, title), `create PROJ "따옴표 제목"`→따옴표 제거, 미지원 서브커맨드→Help 폴백, `search`(프로젝트 누락)→UsageError. 클래스 없음으로 실패.

**GREEN**. `SlashCommand` = sealed(Help | View(issueKey) | Search(projectKey, aql) | Create(projectKey, title) | UsageError(reason)). `SlashCommandParser.parse(text): SlashCommand` — 첫 토큰=서브커맨드(대소문자 무시), 나머지 인자 분해. 감싼 따옴표 제거. text/aql/title 길이 상한(NFR-3).

**REFACTOR**. 상수 추출(서브커맨드 키워드·길이 상한). KDoc — 파싱은 순수(cross-BC 없음), 프로젝트 스코프 인라인 인자 필수(ADR D3).

**검증**. `./gradlew :backend:modules:slack-integration:test --tests '*SlashCommandParserTest'`

### Task 4. slack — `SlackResponseUrlClient` (response_url 지연 응답 POST)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/slack-integration/src/main/kotlin/com/bts/slack/message/SlackResponseUrlClient.kt`, `backend/modules/slack-integration/src/test/kotlin/com/bts/slack/message/SlackResponseUrlClientTest.kt`]
- depends-on: []

**RED**. `SlackResponseUrlClientTest` — 주어진 response_url + blocks로 `{"response_type":"ephemeral","blocks":[...],"replace_original":false}` JSON을 POST. POST 실패(네트워크/4xx)는 예외 전파 없이 best-effort(로그만, false 반환). 클래스 없음으로 실패.

**GREEN**. `SlackResponseUrlClient` — 봇 토큰 불요(response_url 자체가 인증 웹훅). 기존 아웃바운드 HTTP 패턴 확인(`SlackUnfurlClient`/`SlackMessageClient` — SDK vs RestClient) 후 단순 POST(RestClient 권장 — Slack API 메서드 아님). 실패는 catch→로그(민감정보 미노출)→best-effort.

**REFACTOR**. KDoc — response_url은 30분/5회 유효, 1회만 사용, best-effort UX(실패해도 사용자 재입력). 봇토큰 미사용 사유 명시.

**검증**. `./gradlew :backend:modules:slack-integration:test --tests '*SlackResponseUrlClientTest'`

### Task 5. slack — `SlackBlockKitRenderer` ephemeral 렌더 확장

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/slack-integration/src/main/kotlin/com/bts/slack/message/SlackBlockKitRenderer.kt`, `backend/modules/slack-integration/src/test/kotlin/com/bts/slack/message/SlackBlockKitRendererTest.kt`]
- depends-on: []

**RED**. `SlackBlockKitRendererTest`에 케이스 추가 — help 텍스트·이슈 카드(`IssueUnfurlView` 재사용)·검색 목록(`List<IssueSearchHit>` + total)·생성 결과(issueKey+링크)·오류 메시지 블록 구조 검증. 메서드 없음으로 실패.

**GREEN**. 기존 렌더러에 렌더 메서드 5종 추가(기존 unfurl 카드 메서드 옆). 기존 타입(`IssueUnfurlView`·`IssueSearchHit`) + 원시값 입력 → Block Kit blocks 반환. 신규 타입 의존 없음(T1 불요).

**REFACTOR**. 공통 블록 헬퍼(섹션/컨텍스트) 추출. KDoc — ephemeral 전용, 링크는 Atlas base URL 프로퍼티 사용(unfurl 카드 선례).

**검증**. `./gradlew :backend:modules:slack-integration:test --tests '*SlackBlockKitRendererTest'`

### Task 6. slack — 서브커맨드 핸들러 (help/view/search/create)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/slack-integration/src/main/kotlin/com/bts/slack/command/SlashCommandHandlers.kt`, `backend/modules/slack-integration/src/test/kotlin/com/bts/slack/command/SlashCommandHandlersTest.kt`]
- depends-on: [1, 3, 5]

**RED**. `SlashCommandHandlersTest` — mock `IssueUnfurlPort`/`SlashIssueSearchPort`/`IssueImportPort` + 실 렌더러. 각 핸들러: help→안내 블록, view(카드/ null→"찾을 수 없음"), search(Success→목록/ SyntaxError→오류/ 0건→"결과없음"), create(Success→"만들었습니다 KEY"/ Failure reasonCode별 메시지 전부). viewerUserId를 포트에 명시 전달 검증. 클래스 없음으로 실패.

**GREEN**. `SlashCommandHandlers` — `handle(command: SlashCommand, viewerUserId: UUID): List<LayoutBlock>`(또는 렌더된 blocks). 각 서브커맨드 분기:
- View → `issueUnfurlPort.getVisibleIssueCard(key, viewerUserId)` → 카드/미존재
- Search → `slashIssueSearchPort.search(SlashSearchQuery(aql, projectKey, viewerUserId, 0, MAX))` → Success/SyntaxError/빈
- Create → `issueImportPort.importIssue(IssueImportCommand(projectKey, viewerUserId, summary=title))` → Success.issueKey/Failure.reasonCode 매핑
- Help/UsageError → 안내
포트에 `viewerUserId` 명시 전달(SecurityContext 미의존, @Async 안전).

**REFACTOR**. reasonCode→메시지 맵 상수화. KDoc — 권한은 전부 포트(fail-closed)에 위임, slack은 판단 없음(ADR D6). 미매핑 처리는 T7 상위에서.

**검증**. `./gradlew :backend:modules:slack-integration:test --tests '*SlashCommandHandlersTest'`

### Task 7. slack — `SlashCommandService` (@Async 오케스트레이션 + 미매핑 처리)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/slack-integration/src/main/kotlin/com/bts/slack/command/SlashCommandService.kt`, `backend/modules/slack-integration/src/test/kotlin/com/bts/slack/command/SlashCommandServiceTest.kt`]
- depends-on: [3, 4, 6]

**RED**. `SlashCommandServiceTest` — mock `SlackUserMappingRepository`/handlers/`SlackResponseUrlClient`. (a) 매핑 사용자 → parse→handle→response_url POST, (b) 미매핑 사용자 → 계정연결 안내 블록을 response_url POST(help는 미매핑도 안내), (c) 파싱 UsageError → 사용법 오류 POST. 클래스 없음으로 실패.

**GREEN**. `SlashCommandService.process(text, slackUserId, teamId, responseUrl)` `@Async`(기존 `SlackAsyncConfig` 재사용). 흐름: `parser.parse(text)` → `findUserIdBySlackUserId(slackUserId, teamId)` → 미매핑(help 외) 안내 / 매핑 시 `handlers.handle(cmd, viewerUserId)` → `responseUrlClient.post(responseUrl, blocks)`. 전 과정 best-effort(예외는 로그, 이미 ack됨).

**REFACTOR**. KDoc — @Async 스레드엔 SecurityContext 없음, viewerUserId 명시 전달. 미매핑=친절 안내(unfurl silent와 대비, ADR D6).

**검증**. `./gradlew :backend:modules:slack-integration:test --tests '*SlashCommandServiceTest'`

### Task 8. slack — `SlackCommandsController` (`POST /slack/commands` 서명검증 + form-decode + ack)

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/slack-integration/src/main/kotlin/com/bts/slack/web/SlackCommandsController.kt`, `backend/modules/slack-integration/src/test/kotlin/com/bts/slack/web/SlackCommandsControllerTest.kt`]
- depends-on: [7]

**RED**. `SlackCommandsControllerTest`(MockMvc 슬라이스 또는 standalone) — (a) 유효 서명 → 즉시 빈 200 + service.process 위임(mock verify), (b) 서명 실패/헤더 누락/미설정 → 빈 401 서비스 미호출, (c) form-decode(`command`/`text`/`user_id`/`team_id`/`response_url`) 정확 추출, (d) 필수 필드 누락 → 방어적 빈 200. **[eng-review 격상] 테스트는 반드시 `contentType(APPLICATION_FORM_URLENCODED)`로 실제 form 바디를 전송해, verifier에 전달된 rawBody가 온전한 원문인지 캡처 검증**(빈 바디면 서명검증이 조용히 깨진다). 클래스 없음으로 실패.

**GREEN**. `SlackCommandsController` `@PostMapping("/slack/commands")`. **`@RequestBody String rawBody`만 받는다 — `@RequestParam`/`@ModelAttribute` 절대 병용 금지**(form 파싱이 바디를 소비하면 서명검증 깨짐, 함정). `@RequestBody String`가 form-urlencoded에서 빈 바디로 오면 **fallback: `HttpServletRequest.inputStream` 직접 read**(RED에서 온전 캡처 확인). rawBody 크기 상한 가드(DoS, NFR-3). `SlackSignatureVerifier.isValid(ts, sig, rawBody)` 선행 → 실패 401 → 통과 시 수동 form-decode(`URLDecoder`) → `service.process(...)` `@Async` 위임 → 즉시 빈 200 ack. `SlackEventsController` 패턴 동형.

**REFACTOR**. form-decode 헬퍼. KDoc — 원문 보존(EC8) + @RequestParam 병용 금지 함정 + ack 즉시(3초 룰) 명시.

**검증**. `./gradlew :backend:modules:slack-integration:test --tests '*SlackCommandsControllerTest'`

### Task 9. slack — test-boot SecurityConfig permitAll + 풀스택 E2E

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/slack-integration/src/test/kotlin/com/bts/slack/SlackTestSecurityConfig.kt`, `backend/modules/slack-integration/src/test/kotlin/com/bts/slack/StubIssueImportPort.kt`, `backend/modules/slack-integration/src/test/kotlin/com/bts/slack/StubSlashIssueSearchPort.kt`, `backend/modules/slack-integration/src/test/kotlin/com/bts/slack/command/SlackSlashCommandEndToEndTest.kt`]
- depends-on: [8]

**RED**. `SlackSlashCommandEndToEndTest`(`@SpringBootTest` + MockMvc + 실 서명 계산 + awaitility) — 4종 happy(help/view/search/create) + 서명실패 401 + 미매핑 안내 + AQL 문법오류 + create 무권한(FORBIDDEN) + 결과0. response_url mock(캡처)로 최종 ephemeral 블록 검증. `/slack/commands` permitAll 미설정이면 401/403으로 실패(RED).

**GREEN**. `SlackTestSecurityConfig`에 `/slack/commands` permitAll + CSRF-ignore 추가(`/slack/events` 옆). `StubIssueImportPort`(reasonCode 시드 가능)·`StubSlashIssueSearchPort`(Success/SyntaxError 시드 가능) test-double 추가(`StubIssueUnfurlPort` 재사용). E2E green.

**REFACTOR**. Stub 시드 헬퍼. KDoc — prod SecurityConfig 중앙 permitAll은 배포조립 후속(ADR D8, `/slack/events`·automation 웹훅과 동일 미결). 서명검증이 test-boot 배선과 무관하게 무조건 선행됨 명시.

**검증**. `./gradlew :backend:modules:slack-integration:test --tests '*SlackSlashCommandEndToEndTest'` + slack 모듈 전체 + `:backend:modules:shared-kernel:test` + `:backend:modules:search-export-import:test`

## Plan 메타

- task 수: 9
- 모듈: shared-kernel(T1) · search-export-import(T2) · slack-integration(T3~T9). issue-tracking 변경 0.
- 예상 wave: 약 5 (T1·T3·T4·T5 → T2·T6 → T7 → T8 → T9). 모듈 컴파일 직렬화로 실제 wave 조정은 bts-impl.
- TDD 강제: yes (test: 커밋이 feat: 앞)
- 마이그레이션: 0
- 보안 검토: T8(서명 게이트 엔드포인트)·T9(permitAll + fail-closed) = security-engineer
- 추가 검증: ktlint/detekt(--rerun-tasks), ArchUnit(slack이 issue/search 내부 import 0), verify-master-plan(FR 카운트 불변 123)

## 리뷰 결과

### plan-eng-review (2026-07-11) — 자체 적대적 리뷰 (api 저위험, autoplan 대체)
- ✅ BC 격리 — slack은 shared-kernel 포트(SlashIssueSearchPort/IssueUnfurlPort/IssueImportPort)만 참조, issue/search 내부 import 0. 검색 어댑터는 search BC(파서 소유)에 배치, slack은 gradle 의존 없음
- ✅ 권한 fail-closed — slack은 권한 판단 0, 전부 포트 위임(IssueImportPort CREATE_ISSUE 게이트·IssueUnfurlPort fail-closed·SlashIssueSearchPort visibility). actor=서명검증된 Slack user_id의 DB 역매핑 → 위조 불가
- ✅ 서명검증 재사용 — 스킴 동일, verify-before-decode 강제. ack 즉시(cross-BC 전에 200)로 3초 룰·재전송 최소화
- ✅ TDD 태스크 순서/wave — T1(shared-kernel) 선행, 파일 충돌 0(command/ 내 3파일 분리), 마이그레이션 0
- ⚠️ **개선 반영**: T8 form-body @RequestBody String 함정 → RED에서 실 form content-type으로 rawBody 온전 캡처 증명 + inputStream fallback + 크기 상한(DoS). 반영 완료
- BLOCKER: 없음

### plan-devex-review (2026-07-11) — 자체 리뷰 (api)
- ⚠️ **개선 반영**: `SlashIssueSearchPort`가 5개 위치 인자 → BTS 포트 관례(커맨드 객체, `IssueSearchQuery`/`IssueImportCommand` 선례) 위반 → `SlashSearchQuery` 커맨드 객체로 교정. T1/T2/T6 반영 완료
- ✅ Slack 계약은 고정(변경 여지 없음). 내부 신설 API 표면은 신규 포트뿐 → 커맨드 객체로 후속 확장(cross-project 등) 시그니처 불변
- ✅ help 서브커맨드로 명령 발견성(discoverability) 확보. 미매핑 사용자에도 친절 안내(silent 아님)
- BLOCKER: 없음

### 종합
- BLOCKER 0. 개선 2건 모두 plan에 반영 완료. security-engineer가 T8/T9 담당(서명 게이트·permitAll·fail-closed).
- 권장: 단일 worktree **직렬 dispatch**(최근 slack PR #252/#257 관례 — git race 회피, 교훈 참조).
