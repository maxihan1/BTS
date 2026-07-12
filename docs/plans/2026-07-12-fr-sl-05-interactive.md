# FR-SL-05 인터랙티브 메시지 (버튼/메뉴)

> slug: fr-sl-05-interactive
> type: feature
> agent: backend-engineer (+ security-engineer D4)
> primary_bc: slack-integration
> 생성: 2026-07-12

## Brief

**사용자 원문**. "fr-sl-05 진행하자" — FR-SL-05 인터랙티브 (Slack 메시지 버튼/셀렉트 등 인터랙티브 컴포넌트 처리).

**classify 교정** (진실 출처 = 이 plan, cache 아님 — 멀티세션 충돌로 cache 신뢰 불가).
- classify 원 판정. `type=ui / agent=frontend-engineer / primary_bc=issue-tracking` — "컴포넌트" 단어를 React UI로 오판.
- 교정. `type=feature / agent=backend-engineer (+security-engineer D4) / primary_bc=slack-integration`.
- 근거. `docs/plan/product/slack-integration.md §3.3` D1~D7 전부 backend/db/security/qa 책임, **D6 프론트 UI = 해당 없음**. Slack Block Kit 버튼은 Slack이 렌더 → React UI 없음. FR-SL-03 D6에 동일 오분류 교정 선례 기록됨.

**스코프 요지** (SDD 9.3.5 + product §3.3).
- 알림 메시지(Slack DM)에 액션 버튼 포함. 예. `[상세보기] [완료로 표시] [코멘트 추가]`.
- 사용자가 Slack에서 버튼 클릭 → Slack이 Interactivity Request URL로 `block_actions` payload POST.
- 백엔드. 서명검증 → payload 파싱 → Slack 사용자 → BTS 사용자 해석 → 권한 가드 → 액션 수행(상태 전이/담당자 변경/댓글) → Slack 메시지 갱신(response_url / chat.update).
- 선행. FR-SL-04(§3.2). 인바운드 서명검증기·ack200+@Async 패턴 재사용.

**D 단계 (product §3.3)**.
- D1 도메인 — InteractiveAction (backend-engineer)
- D2 명세 — 상태 전이, 담당자 변경 등 (backend-engineer)
- D3 데이터 모델 — (활용) (db-engineer)
- D4 백엔드 — block_actions handler + 권한 가드 + 응답 갱신 (backend-engineer + security-engineer)
- D5 백엔드 테스트 (backend-engineer)
- D6 프론트 UI — (해당 없음)
- D7 E2E (qa-engineer)

**멀티세션 주의**. 동시 세션 = FR-AT-02 D6 (automation 프론트, `.worktrees/fr-at-02-d6-d7-ui`). 모듈·레이어 분리로 충돌 위험 낮으나, 머지 직전 Flyway V번호 + git 브랜치 재확인 필수.

## 도메인 정리

- **BC**. slack-integration (`com.bts.slack`). 이슈 변경은 shared-kernel cross-BC 쓰기 포트 위임(BC 격리 유지).
- **영향 엔티티/자산**.
  - 신규. `SlackInteractionPayload`(block_actions / view_submission 파싱 VO), `InteractiveAction`(도메인 개념 — 완료전이/담당자변경/댓글), `SlackInteractionsController`(`POST /slack/interactions`).
  - 재사용(전수 실재 검증 — phantom 0). `SlackSignatureVerifier`(서명검증), `SlackUserMappingRepository.findUserIdBySlackUserId`(역매핑 V702), `SlackResponseUrlClient`(response_url 아웃바운드), `SlackBlockKitRenderer`(버튼 렌더 확장), `IssueMutationPort.assign/addComment`(FR-AT-02), `IssueTransitionPort.transition`(FR-BD-01 보드).
  - 결정 대기. `SlackInteractionLog`(버튼 액션 감사 — domain 노트는 신규 엔티티 명시 / product D3는 "(활용)". 스펙에서 확정).
- **액션↔포트 매핑** (신규 cross-BC 쓰기 포트 불필요).
  - 완료로 표시 → `IssueTransitionPort.transition(BoardTransitionCommand)`. `expectedVersion`(OCC)·`resolutionId`(DONE 카테고리) 필요 → 현재 이슈 버전/상태 선조회 필요(스펙 결정).
  - 담당자 변경 → `IssueMutationPort.assign(AssignCommand)`. FR-SL-05 = IssueMutationPort **2번째 소비자**.
  - 코멘트 추가 → `IssueMutationPort.addComment(AddCommentCommand)`. 텍스트 입력은 Slack 모달(`view_submission`)로 받을지 스펙 결정.
  - 상세보기 → URL 버튼(BTS 웹 링크). 백엔드 콜백 없음.
- **새 용어**. 인터랙티브 액션(Interactive Action — block_actions), 모달 제출(view_submission) — 스코프 확정 후 glossary 반영 검토.
- **기존 결정 충돌**. 없음. FR-SL-03(Events)·FR-SL-04(Slash)가 인바운드 서명검증·ack200+@Async→response_url 기반 확립 → FR-SL-05 자연 확장. FR-AT-02 IssueMutationPort 재사용(2번째 소비자).
- **관련 ADR**. 선행 [2026-07-11-fr-sl-04-slash-command.md](../decisions/2026-07-11-fr-sl-04-slash-command.md)(인바운드 패턴) · [2026-07-11-fr-sl-03-slack-unfurl.md](../decisions/2026-07-11-fr-sl-03-slack-unfurl.md)(서명검증·역매핑) · FR-AT-02(IssueMutationPort). 신규 ADR `2026-07-12-fr-sl-05-interactive.md`는 bts-spec에서 생성.
- **grill-with-docs 축약 사유**. 도메인 언어/BC 경계가 SL-03/04로 이미 확립, 재사용 포트 전수 실재 검증 완료, 남은 것은 스코프/설계 결정(모달·감사·완료전이 버전) → bts-spec office-hours의 구체 선택지로 위임.

## 스펙

전체 스펙. [docs/specs/2026-07-12-fr-sl-05-interactive.md](../specs/2026-07-12-fr-sl-05-interactive.md)

**Maxi 스코프 결정(2026-07-12)**. 풀세트(완료+담당자+상세보기+**코멘트 모달**) · 완료=**resolution 선택 모달** · **V703 감사 테이블 신설**.

핵심 시나리오 요약.
- `POST /slack/interactions` — block_actions(버튼/셀렉트) + view_submission(모달 제출) 2종. 서명검증 선행 → 역매핑 → cross-BC 쓰기 포트(권한 fail-closed) → 원본 메시지 갱신.
- 완료로 표시 → views.open(resolution 모달, 동기 3초) → 제출 → `IssueTransitionPort.transition`.
- 담당자 변경 → Slack `users_select`(후보 포트 우회) → 역매핑 → `IssueMutationPort.assign`.
- 코멘트 추가 → views.open(모달) → 제출 → `IssueMutationPort.addComment`.

신규. `POST /slack/interactions` 컨트롤러·payload 파서(2종)·모달 빌더(2)·`SlackMessageClient` views.open/chat.update 확장·`SlackBlockKitRenderer` actions 블록·**V703 slack_interaction_log**·**신규 읽기 포트 `IssueCompletionOptionsPort`**(version+done전이+resolution) + issue-tracking 어댑터.

## Brainstorming Check

✅ 통과 (포트 조사 중 sanity check 수행 — 발견 5건 스펙 반영).
- trigger_id 3초 만료 → 모달 오픈은 동기 views.open, 그 외는 ack200+@Async (SL-04와 다른 핵심 제약).
- 모달 제출 지연 시 OCC 충돌 → private_metadata에 expectedVersion 박제 + 충돌 시 ephemeral 재시도(form-occ-409 선례).
- 담당자 후보: `ProjectMembershipPort`는 방향 반대 → Slack 네이티브 `users_select`로 후보 포트 회피.
- resolution 목록 포트 부재 → 신규 `IssueCompletionOptionsPort`(version+done전이+resolution 결합 읽기, fail-closed).
- 상세보기 url 버튼 payload는 no-op 200(dispatch 경고 방지).

## Plan

> **PR1 스코프 (Maxi 2 PR 분할 확정)**. 인바운드 인터랙티브 인프라 전체 + 완료로 표시(resolution 모달) + 상세보기(url). 담당자 변경·코멘트 모달은 PR2 후속.
> **모듈**. shared-kernel(T1) → issue-tracking(T2) + slack-integration(T3~T10). writing-plans 직접 작성(도메인/포트 깊이 — 재사용 포트·인바운드 패턴 SL-04 확립).

### Task 1. IssueCompletionOptionsPort + VO (shared-kernel 신규 읽기 포트)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/issue/IssueCompletionOptionsPort.kt`, `backend/modules/shared-kernel/src/test/kotlin/com/bts/shared/issue/IssueCompletionOptionsPortTest.kt`]
- depends-on: []

**RED**. `IssueCompletionOptionsPortTest` — VO(`IssueCompletionOptions{version:Long, doneTransitions:List<DoneTransition(toStateKey,label)>, resolutions:List<ResolutionOption(id:UUID,label)>}`) 구성 + fail-closed 계약(default 구현 없음, nullable 반환) 문서화. `SharedKernelBoundaryArchTest`가 신규 타입 원시/shared VO만 사용하는지 검증(BC 도메인 타입 누출 0).
**GREEN**. `interface IssueCompletionOptionsPort { fun getCompletionOptions(issueKey: String, viewerUserId: UUID): IssueCompletionOptions? }` + VO data class. IssueUnfurlPort/IssueTransitionPort와 동일 fail-closed KDoc.
**REFACTOR**. KDoc 재사용 포트 참조(IssueTransitionPort와 결합 근거 — 완료 모달 1회 조회로 version+전이+resolution).
**검증**. `./gradlew :modules:shared-kernel:test :modules:shared-kernel:detektMain`

### Task 2. issue-tracking IssueCompletionOptionsAdapter (@Component, 포트 구현)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issuetracking/crossbc/IssueCompletionOptionsAdapter.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issuetracking/crossbc/IssueCompletionOptionsAdapterTest.kt`]
- depends-on: [1]

**RED**. Testcontainers 통합 — 이슈 시드 후 `getCompletionOptions(key, viewer)`가 현재 version + DONE 카테고리 전이 후보(WorkflowStateCatalog) + resolution 목록 반환. viewer 미가시 → null(fail-closed). BROWSE/visibility 게이트 상속([[crossbc-issue-read-needs-browse-gate]] — 스킴 없는 프로젝트 fail-open 차단, hasPermission(BROWSE,Project) 직접).
**GREEN**. 어댑터가 issue-tracking 내부 서비스(이슈 조회·워크플로우 카탈로그·resolution 조회)에 위임. version=이슈 OCC 버전, doneTransitions=현 상태에서 가능한 DONE 카테고리 전이, resolutions=워크플로우/프로젝트 resolution.
**REFACTOR**. visibility 슬롯 capture로 동일 viewerUserId 증명(vacuous 회피). resolution 불요 워크플로우 → 빈 목록(E6).
**검증**. `./gradlew :modules:issue-tracking:test --tests '*IssueCompletionOptionsAdapterTest*'`

### Task 3. V703 slack_interaction_log 마이그레이션 + Repository

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/slack-integration/src/main/resources/db/migration/slack-integration/V703__slack_interaction_log.sql`, `backend/db/init_codegen.sql`, `backend/modules/slack-integration/src/main/kotlin/com/bts/slack/interaction/SlackInteractionLogRepository.kt`, `backend/modules/slack-integration/src/main/kotlin/com/bts/slack/interaction/JdbcSlackInteractionLogRepository.kt`, `backend/modules/slack-integration/src/test/kotlin/com/bts/slack/interaction/JdbcSlackInteractionLogRepositoryTest.kt`]
- depends-on: []

**RED**. Testcontainers — insert(team_id, slack_user_id, bts_user_id?, action_type, issue_key?, outcome) + 조회. outcome enum(SUCCESS/UNMAPPED/PERMISSION_DENIED/CONFLICT/ERROR).
**GREEN**. V703 DDL(스펙 §데이터 모델) + JdbcTemplate repo(record 메서드). `init_codegen.sql` 미러([[jooq-init-codegen-mirror]]).
**REFACTOR**. created_at DESC 인덱스. best-effort record(로그 실패가 액션 안 막음 — 단 호출자가 예외 삼키지 않도록 catch 범위 최소).
**검증**. `./gradlew :modules:slack-integration:test --tests '*SlackInteractionLogRepositoryTest*'`
**주의**. V703 번호·Flyway 경로 머지 직전 재확인([[migration-vnumber-concurrent-branch-collision]] — 동시 FR-AT-02 세션).

### Task 4. SlackInteractionPayload 모델 + 파서 (block_actions + view_submission)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/slack-integration/src/main/kotlin/com/bts/slack/interaction/SlackInteractionPayload.kt`, `backend/modules/slack-integration/src/main/kotlin/com/bts/slack/interaction/SlackInteractionPayloadParser.kt`, `backend/modules/slack-integration/src/test/kotlin/com/bts/slack/interaction/SlackInteractionPayloadParserTest.kt`]
- depends-on: []

**RED**. block_actions JSON → `BlockActions(userId, teamId, triggerId, responseUrl, channel, messageTs, actions:List<Action(actionId,value)>)`. view_submission JSON → `ViewSubmission(userId, teamId, callbackId, privateMetadata, stateValues)`. 알 수 없는 type → `Unknown`.
**GREEN**. sealed `SlackInteractionPayload` + Jackson 파서(slack 내부 ObjectMapper — shared-kernel 아님). 필드 누락 방어(nullable → 안전 기본).
**REFACTOR**. private_metadata는 JSON 문자열(issueKey·expectedVersion·toStateKey·channel·ts 박제) — 파서는 raw 문자열만, 해석은 서비스.
**검증**. `./gradlew :modules:slack-integration:test --tests '*SlackInteractionPayloadParserTest*'`

### Task 5. SlackMessageClient 확장 — views.open + chat.update

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/slack-integration/src/main/kotlin/com/bts/slack/message/SlackMessageClient.kt`, `backend/modules/slack-integration/src/test/kotlin/com/bts/slack/message/SlackMessageClientTest.kt`]
- depends-on: []

**RED**. `openModal(botToken, triggerId, viewJson)` → MethodsClient.viewsOpen 호출. `updateMessage(botToken, channel, ts, blocksJson)` → chatUpdate 호출. 봇 토큰 요청에만 실림·로그/예외/반환 미노출(N4, 기존 postDirectMessage 선례).
**GREEN**. MethodsClient viewsOpen/chatUpdate 래핑. 기존 `postDirectMessage` 옆 추가.
**REFACTOR**. 실패 결과 타입 일관(성공/실패, 토큰 미포함).
**검증**. `./gradlew :modules:slack-integration:test --tests '*SlackMessageClientTest*'`

### Task 6. SlackBlockKitRenderer 확장 — DM actions 블록 + resolution 모달 빌더

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/slack-integration/src/main/kotlin/com/bts/slack/message/SlackBlockKitRenderer.kt`, `backend/modules/slack-integration/src/main/kotlin/com/bts/slack/interaction/SlackModalBuilder.kt`, `backend/modules/slack-integration/src/test/kotlin/com/bts/slack/message/SlackBlockKitRendererInteractiveTest.kt`, `backend/modules/slack-integration/src/test/kotlin/com/bts/slack/interaction/SlackModalBuilderTest.kt`]
- depends-on: [1]

**RED**. 렌더러가 FR-SL-02 담당자 배정 DM에 actions 블록 추가 — `[상세보기](url 버튼)` + `[완료로 표시](button, action_id=atlas_complete, value=issueKey)`. (담당자 select·코멘트 button은 PR2 — 이번 미포함). 모달 빌더가 `IssueCompletionOptions`로 resolution 모달 view JSON 생성(callback_id=atlas_complete_modal, resolution static_select, done 전이 선택[다중 시], private_metadata 박제). resolution 빈 목록 → 섹션 생략(E6).
**GREEN**. Block Kit JSON 조립. 상세보기 url = BTS 웹 이슈 URL(설정 baseUrl + issueKey).
**REFACTOR**. action_id/callback_id 상수화. 기존 unfurl 카드 렌더와 분리(회귀 0).
**검증**. `./gradlew :modules:slack-integration:test --tests '*SlackBlockKitRendererInteractiveTest*' --tests '*SlackModalBuilderTest*'`

### Task 7. SlackInteractionService — 오케스트레이션 (완료 flow·라우팅·V703 로깅)

**메타**.
- agent: `security-engineer`  # 권한 게이트·역매핑·fail-closed 중심 (D4)
- files: [`backend/modules/slack-integration/src/main/kotlin/com/bts/slack/interaction/SlackInteractionService.kt`, `backend/modules/slack-integration/src/test/kotlin/com/bts/slack/interaction/SlackInteractionServiceTest.kt`]
- depends-on: [1, 3, 4, 5, 6]

**RED** (mockk로 재사용 포트 mock).
- block_actions `atlas_complete` → 역매핑 성공 → `IssueCompletionOptionsPort.getCompletionOptions` → `SlackMessageClient.openModal`(동기, trigger_id) 호출 검증. V703 기록 안 함(모달 오픈은 액션 아님) 또는 VIEW.
- view_submission `atlas_complete_modal` → 역매핑 → `IssueTransitionPort.transition(actorUserId, issueKey, toStateKey, expectedVersion, resolutionId)` → `chat.update` → V703 SUCCESS.
- 미연결(역매핑 null) → ephemeral 안내 + V703 UNMAPPED. 이슈 변경 0.
- 무권한(transition이 권한 예외 throw) → ephemeral + V703 PERMISSION_DENIED. (best-effort catch에 권한예외 포함 금지 — [[best-effort-loop-permission-exception-nonprod-mask]])
- OCC 충돌(transition OCC 예외) → ephemeral 재시도 + V703 CONFLICT.
- url `atlas_view` → no-op.
**GREEN**. 라우팅 + 역매핑(SlackUserMappingRepository) + SlackInstall 봇토큰 조회 + 포트 위임 + private_metadata 파싱/박제. actor=역매핑 결과만(위조 차단 C3).
**REFACTOR**. outcome→ephemeral 메시지 매핑 상수화. 실패분류는 타입 예외([[crossbc-failure-classification-typed-not-name]]).
**검증**. `./gradlew :modules:slack-integration:test --tests '*SlackInteractionServiceTest*'`

### Task 8. SlackInteractionsController — POST /slack/interactions (서명검증·form-decode·ack)

**메타**.
- agent: `security-engineer`  # 서명검증·permitAll (인증 대체)
- files: [`backend/modules/slack-integration/src/main/kotlin/com/bts/slack/web/SlackInteractionsController.kt`, `backend/modules/slack-integration/src/main/kotlin/com/bts/slack/config/SlackSecurityConfig.kt`, `backend/modules/slack-integration/src/test/kotlin/com/bts/slack/web/SlackInteractionsControllerTest.kt`]
- depends-on: [7]

**RED** (TestRestTemplate 실서블릿 — MockMvc 우회 가짜그린 방지 [[multipart-default-limit-app-policy-false-green]]).
- 유효 서명 + form-urlencoded `payload=<json>` → 서비스 dispatch + 200. block_actions(모달 오픈)은 동기 200 ack. view_submission은 `{}`(모달 닫기).
- 잘못된/누락 서명 → 401(빈, 미노출 N1).
- `@RequestBody String rawBody`만(@RequestParam 병용 금지 [[fr-sl-04]]).
**GREEN**. 서명검증(SlackSignatureVerifier) 선행 → form-decode → `payload` 추출 → 파서 → 서비스. `@Async`는 서비스 내부(전이/댓글)만, 모달 오픈은 동기.
**REFACTOR**. SlackSecurityConfig test-boot permitAll `/slack/interactions` + CSRF-ignore(C2, SL-04 D8 동형 — 중앙 등록은 배포 조립 후속).
**검증**. `./gradlew :modules:slack-integration:test --tests '*SlackInteractionsControllerTest*'`

### Task 9. Full-boot @MockBean 결선 + prod 조립 부팅 검증

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/slack-integration/src/test/kotlin/com/bts/slack/` (full-boot 컨텍스트 test 설정들 — grep로 확정)]
- depends-on: [8]

**RED**. slack full-boot(@SpringBootTest) 컨텍스트가 신규 `IssueCompletionOptionsPort` 미결선 → NoSuchBeanDefinitionException([[new-crossbc-dep-openapi-mockbean-regression]]). whoami/OpenApi 등 로드 슬라이스 전수 확인([[whoami-slice-mock-skipci-masking]]).
**GREEN**. 해당 full-boot test에 `@MockBean IssueCompletionOptionsPort` 추가.
**REFACTOR**. `:modules:app` prod 조립이 issue-tracking `IssueCompletionOptionsAdapter`(@Component)를 스캔·결선하는지 확인 → `./gradlew :modules:app:test` 부팅 검증([[prod-assembly-boot-verification-required]] — 머지 전 rebase+:modules:app:test).
**검증**. `./gradlew :modules:slack-integration:test :modules:app:test`

### Task 10. E2E — SlackInteractionEndToEndTest (완료 왕복·미연결·무권한·충돌·401)

**메타**.
- agent: `qa-engineer`
- files: [`backend/modules/slack-integration/src/test/kotlin/com/bts/slack/SlackInteractionEndToEndTest.kt`]
- depends-on: [8]

**RED/GREEN**. 유효 서명 생성 → (1) 완료 block_actions → views.open mock 호출 확인 (2) 완료 modal view_submission → transition + chat.update mock + V703 SUCCESS (3) 미연결 → ephemeral + UNMAPPED (4) 무권한 → ephemeral + PERMISSION_DENIED (5) 서명 실패 → 401. Slack SDK(views.open/chat.update) mock. identity-access prod+RANDOM_PORT 부팅 레시피([[identity-access-prod-randomport-boot-recipe]]) 참조.
**검증**. `./gradlew :modules:slack-integration:test --tests '*SlackInteractionEndToEndTest*'`

## Plan 메타

- task 수: 10 (PR1). PR2 후속(담당자 users_select + 코멘트 모달) ~5 task.
- 모듈: shared-kernel(T1) → issue-tracking(T2) + slack(T3~T10). cross-module 컴파일 직렬화([[bts-plan-wave-gradle-module-compile]]).
- 예상 wave: W0=T1 → W1=T2·T3·T4·T5·T6(병렬, files 무충돌) → W2=T7 → W3=T8 → W4=T9·T10.
- TDD 강제: yes. 추가 검증: ktlint/detekt(모듈 baseline)·:modules:app 부팅.
- 공유 파일 주의: T5(SlackMessageClient.kt)·T6(SlackBlockKitRenderer.kt)는 별 파일(무충돌). V703은 T3 단독.

## 리뷰 결과 (← /bts-review-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
