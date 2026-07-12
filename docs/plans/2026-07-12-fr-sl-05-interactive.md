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

## Plan (v2 — 리뷰 BLOCKER 반영)

> **PR1 스코프 (Maxi 2 PR 분할 확정)**. 인바운드 인터랙티브 인프라 전체 + 완료로 표시(resolution 모달, **동기**) + 상세보기(url). 담당자 변경·코멘트 모달은 PR2 후속.
> **모듈**. shared-kernel(T1·T2) → issue-tracking(T2 어댑터·T3) + slack-integration(T4~T11).
> **v2 변경(리뷰 2건 BLOCKER)**. ① transition 실패분류 타입 예외 신설(T2). ② 완료 제출 **동기 transition + response_action** (view_submission엔 response_url 없음). ③ test-boot 스텁 결선(T9, 형제 E2E 회귀 차단). ④ 완료 버튼 렌더 eventType 게이팅(T7). ⑤ 본문 크기 상한(T10). ⑥ toStateKey는 state_values(박제 아님).

### Task 1. IssueCompletionOptionsPort + VO (shared-kernel 신규 읽기 포트)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/issue/IssueCompletionOptionsPort.kt`, `backend/modules/shared-kernel/src/test/kotlin/com/bts/shared/issue/IssueCompletionOptionsPortTest.kt`]
- depends-on: []

**RED**. `IssueCompletionOptionsPortTest` — VO(`IssueCompletionOptions{version:Long, doneTransitions:List<DoneTransition(toStateKey,label)>, resolutions:List<ResolutionOption(id:UUID,label)>}`) 구성 + fail-closed 계약(default 없음, nullable) 문서화. `SharedKernelBoundaryArchTest`가 신규 타입 원시/shared VO만 사용하는지 검증.
**GREEN**. `interface IssueCompletionOptionsPort { fun getCompletionOptions(issueKey: String, viewerUserId: UUID): IssueCompletionOptions? }` + VO. IssueUnfurlPort 동형 fail-closed KDoc.
**REFACTOR**. 결합 조회 근거 KDoc(완료 모달 1회 조회로 version+전이+resolution — 나누면 소비 BC가 게이트 스킵 위험).
**검증**. `./gradlew :modules:shared-kernel:test :modules:shared-kernel:detektMain`

### Task 2. transition 실패분류 타입 예외 (shared-kernel) + IssueTransitionAdapter 번역 (리뷰 BLOCKER-1)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/board/IssueTransitionExceptions.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issuetracking/crossbc/IssueTransitionAdapter.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issuetracking/crossbc/IssueTransitionAdapterExceptionTranslationTest.kt`]
- depends-on: []

**RED**. `IssueTransitionAdapter`가 무권한 → `IssueTransitionPermissionDeniedException`, OCC 충돌 → `IssueOptimisticLockException`(shared-kernel 신규)로 **번역해 던지는지** 검증. 기존 도메인 예외 verbatim 전파 금지. (기존 agile-planning 소비자 generic catch — 하위호환 확인.)
**GREEN**. shared-kernel에 두 예외 추가(`IssueTransitionPort` KDoc 계약 예외로 명시). 어댑터가 `IssueAccessDeniedException`/`IssueVersionConflictException` catch→번역(`IssueMutationPermissionDeniedException` 선례 동형).
**REFACTOR**. 예외 클래스명 문자열 매칭 금지([[crossbc-failure-classification-typed-not-name]]) — 소비자가 타입으로만 분류.
**검증**. `./gradlew :modules:shared-kernel:test :modules:issue-tracking:test --tests '*IssueTransitionAdapterExceptionTranslationTest*'`

### Task 3. issue-tracking IssueCompletionOptionsAdapter (@Component, 포트 구현)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issuetracking/crossbc/IssueCompletionOptionsAdapter.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issuetracking/crossbc/IssueCompletionOptionsAdapterTest.kt`]
- depends-on: [1]

**RED**. Testcontainers — 이슈 시드 후 `getCompletionOptions(key, viewer)`가 version + DONE 카테고리 전이 후보 + resolution 목록 반환. viewer 미가시 → null. BROWSE/visibility 게이트 상속([[crossbc-issue-read-needs-browse-gate]]).
**GREEN**. 이슈 조회·워크플로우 카탈로그·resolution 조회 위임. version=OCC 버전, doneTransitions=현 상태에서 가능한 DONE 전이, resolutions=워크플로우/프로젝트 resolution.
**REFACTOR**. visibility 슬롯 capture(vacuous 회피). resolution 불요 워크플로우 → 빈 목록(E6).
**검증**. `./gradlew :modules:issue-tracking:test --tests '*IssueCompletionOptionsAdapterTest*'`

### Task 4. V703 slack_interaction_log 마이그레이션 + Repository

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/slack-integration/src/main/resources/db/migration/slack-integration/V703__slack_interaction_log.sql`, `backend/db/init_codegen.sql`, `backend/modules/slack-integration/src/main/kotlin/com/bts/slack/interaction/SlackInteractionLogRepository.kt`, `backend/modules/slack-integration/src/main/kotlin/com/bts/slack/interaction/JdbcSlackInteractionLogRepository.kt`, `backend/modules/slack-integration/src/test/kotlin/com/bts/slack/interaction/JdbcSlackInteractionLogRepositoryTest.kt`]
- depends-on: []

**RED**. Testcontainers — insert(team_id, slack_user_id, bts_user_id?, action_type, issue_key?, outcome) + 조회. outcome(SUCCESS/UNMAPPED/PERMISSION_DENIED/CONFLICT/ERROR).
**GREEN**. V703 DDL(스펙 §데이터 모델) + JdbcTemplate repo. `init_codegen.sql` 미러([[jooq-init-codegen-mirror]]).
**REFACTOR**. created_at DESC 인덱스. PERMISSION_DENIED 기록 실패 시 WARN 별도 흔적(F11 — N3 우선순위).
**검증**. `./gradlew :modules:slack-integration:test --tests '*SlackInteractionLogRepositoryTest*'`
**주의**. V703 번호·Flyway 경로 머지 직전 재확인([[migration-vnumber-concurrent-branch-collision]] — 동시 FR-AT-02 세션).

### Task 5. SlackInteractionPayload 모델 + 파서 (block_actions + view_submission)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/slack-integration/src/main/kotlin/com/bts/slack/interaction/SlackInteractionPayload.kt`, `backend/modules/slack-integration/src/main/kotlin/com/bts/slack/interaction/SlackInteractionPayloadParser.kt`, `backend/modules/slack-integration/src/test/kotlin/com/bts/slack/interaction/SlackInteractionPayloadParserTest.kt`]
- depends-on: []

**RED**. block_actions JSON → `BlockActions(userId, teamId, triggerId, responseUrl, channel, messageTs, actions:List<Action(actionId,value)>)`. view_submission JSON → `ViewSubmission(userId, teamId, callbackId, privateMetadata, stateValues:Map)`. 알 수 없는 type → `Unknown`.
**GREEN**. sealed `SlackInteractionPayload` + Jackson 파서(slack 내부 ObjectMapper). 필드 누락 방어.
**REFACTOR**. private_metadata = JSON 문자열(issueKey·expectedVersion·channel·ts만 — **toStateKey 제외**, 리뷰 CONCERN). toStateKey는 stateValues에서. 파서는 raw만, 해석은 서비스.
**검증**. `./gradlew :modules:slack-integration:test --tests '*SlackInteractionPayloadParserTest*'`

### Task 6. SlackMessageClient 확장 — views.open + chat.update

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/slack-integration/src/main/kotlin/com/bts/slack/message/SlackMessageClient.kt`, `backend/modules/slack-integration/src/test/kotlin/com/bts/slack/message/SlackMessageClientTest.kt`]
- depends-on: []

**RED**. `openModal(botToken, triggerId, viewJson)` → viewsOpen. `updateMessage(botToken, channel, ts, blocksJson)` → chatUpdate. 봇 토큰 요청에만·로그/예외/반환 미노출(N4, postDirectMessage 선례).
**GREEN**. MethodsClient viewsOpen/chatUpdate 래핑.
**REFACTOR**. 실패 결과 타입 일관(토큰 미포함).
**검증**. `./gradlew :modules:slack-integration:test --tests '*SlackMessageClientTest*'`

### Task 7. SlackBlockKitRenderer/ModalBuilder 확장 + SlackDeliveryWorker 게이팅 (리뷰 CONCERN)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/slack-integration/src/main/kotlin/com/bts/slack/message/SlackBlockKitRenderer.kt`, `backend/modules/slack-integration/src/main/kotlin/com/bts/slack/interaction/SlackModalBuilder.kt`, `backend/modules/slack-integration/src/main/kotlin/com/bts/slack/worker/SlackDeliveryWorker.kt`, `backend/modules/slack-integration/src/test/kotlin/com/bts/slack/message/SlackBlockKitRendererInteractiveTest.kt`, `backend/modules/slack-integration/src/test/kotlin/com/bts/slack/interaction/SlackModalBuilderTest.kt`, `backend/modules/slack-integration/src/test/kotlin/com/bts/slack/worker/SlackDeliveryWorkerActionsTest.kt`]
- depends-on: [1]

**RED**. (a) 전용 렌더 메서드 `renderAssignmentActionsMessage(title, issueKey)` — 상세보기(url) + 완료(action_id=atlas_complete, value=issueKey) 버튼. (b) **SlackDeliveryWorker가 `eventType==ISSUE_ASSIGNED`일 때만** 전용 메서드 호출(그 외는 기존 `render()` — 멘션/댓글 DM에 버튼 오배치 차단, 리뷰 CONCERN). 기존 `render()`·`SlackBlockKitRendererTest` 회귀 0. (c) 모달 빌더가 `IssueCompletionOptions`로 resolution 모달 JSON(callback_id=atlas_complete_modal, resolution static_select, done 전이 static_select[다중 시], private_metadata=issueKey·expectedVersion·channel·ts). resolution 빈 → 섹션 생략(E6).
**GREEN**. Block Kit JSON. 상세보기 url = 설정 baseUrl + issueKey.
**REFACTOR**. action_id/callback_id 상수화. 단일 done 전이면 셀렉트 생략(toStateKey는 여전히 state_values 경로 통일).
**검증**. `./gradlew :modules:slack-integration:test --tests '*RendererInteractiveTest*' --tests '*SlackModalBuilderTest*' --tests '*SlackDeliveryWorkerActionsTest*'`

### Task 8. SlackInteractionService — 오케스트레이션 (완료 동기 flow·타입예외 분류·V703)

**메타**.
- agent: `security-engineer`  # 권한 게이트·역매핑·fail-closed (D4)
- files: [`backend/modules/slack-integration/src/main/kotlin/com/bts/slack/interaction/SlackInteractionService.kt`, `backend/modules/slack-integration/src/test/kotlin/com/bts/slack/interaction/SlackInteractionServiceTest.kt`]
- depends-on: [1, 2, 4, 5, 6, 7]

**RED** (mockk).
- block_actions `atlas_complete` → 역매핑 성공 → `getCompletionOptions`. **null(무권한/미가시) → 모달 안 열고 response_url ephemeral + V703 PERMISSION_DENIED**(S5b, 리뷰 CONCERN·null 가드). non-null → `openModal`(동기, trigger_id).
- view_submission `atlas_complete_modal` → 역매핑 → **동기** `IssueTransitionPort.transition(actor, issueKey, toStateKey=stateValues, expectedVersion=privateMetadata, resolutionId)` → 성공: `chat.update` + 빈 200 + V703 SUCCESS. **`IssueTransitionPermissionDeniedException` → response_action:{errors} + V703 PERMISSION_DENIED. `IssueOptimisticLockException` → response_action:{errors} + V703 CONFLICT. 그 외 → response_action + V703 ERROR.** (best-effort catch에 권한/OCC 예외 포함 금지 — [[best-effort-loop-permission-exception-nonprod-mask]])
- 미연결(역매핑 null) → ephemeral + V703 UNMAPPED.
- url `atlas_view` → no-op.
- 동기 핸들러 top-level try/catch → 항상 200 계열(비밀/원문 미노출 로그, 리뷰 NIT).
**GREEN**. 라우팅 + 역매핑 + SlackInstall 봇토큰 조회 + 포트 위임 + private_metadata 파싱. actor=역매핑 결과만(위조 차단 C3). resolutionId는 신뢰 안 함 — transition 포트가 검증(리뷰 NIT).
**REFACTOR**. outcome→메시지 매핑 상수화. **PR1엔 @Async 없음**(전부 동기 3초 내 — self-invocation 함정 회피 [[transaction-self-invocation-requires-new]] 정신).
**검증**. `./gradlew :modules:slack-integration:test --tests '*SlackInteractionServiceTest*'`

### Task 9. slack test-boot 스텁 결선 (StubIssueCompletionOptionsPort + StubIssueTransitionPort) — 리뷰 BLOCKER-2

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/slack-integration/src/test/kotlin/com/bts/slack/config/SlackTestcontainersConfig.kt`, `backend/modules/slack-integration/src/test/kotlin/com/bts/slack/config/StubIssueCompletionOptionsPort.kt`, `backend/modules/slack-integration/src/test/kotlin/com/bts/slack/config/StubIssueTransitionPort.kt`]
- depends-on: [1, 2, 8]

**RED**. T8의 `SlackInteractionService`(@Component) 추가로 slack full-boot(`SlackContextLoadTest` + 기존 `SlackSlashCommandEndToEndTest`)가 `IssueCompletionOptionsPort`·`IssueTransitionPort` **양쪽** NoSuchBean 회귀([[new-crossbc-dep-openapi-mockbean-regression]]). **@MockBean으론 컨텍스트 로드 테스트 못 고침** → seedable Stub @Bean 필요(StubIssueUnfurlPort 선례).
**GREEN**. seedable `StubIssueCompletionOptionsPort`(옵션 시드/null) + `StubIssueTransitionPort`(성공 결과 시드 / 권한·OCC 예외 주입)를 `SlackTestcontainersConfig`에 `@Bean` 등록. 미시드=fail-closed.
**REFACTOR**. 두 스텁 outcome 재현 가능(happy/무권한/OCC) — T10/T11이 시드로 구동. 기존 형제 full-boot 테스트 green 재확인.
**검증**. `./gradlew :modules:slack-integration:test --tests '*SlackContextLoadTest*' --tests '*SlackSlashCommandEndToEndTest*'`

### Task 10. SlackInteractionsController — POST /slack/interactions (서명검증·크기상한·response_action)

**메타**.
- agent: `security-engineer`  # 서명검증·permitAll·DoS 가드
- files: [`backend/modules/slack-integration/src/main/kotlin/com/bts/slack/web/SlackInteractionsController.kt`, `backend/modules/slack-integration/src/main/kotlin/com/bts/slack/config/SlackSecurityConfig.kt`, `backend/modules/slack-integration/src/test/kotlin/com/bts/slack/web/SlackInteractionsControllerTest.kt`]
- depends-on: [8, 9]

**RED** (TestRestTemplate 실서블릿 — 크기상한/서명 servlet 레벨, MockMvc 가짜그린 방지 [[multipart-default-limit-app-policy-false-green]]).
- **본문 크기 상한(HMAC 전, 초과 → 빈 413)** — SlackCommandsController 동형(리뷰 CONCERN·DoS).
- 유효 서명 + `payload=<json>` → dispatch + 200. view_submission 성공 → 빈 200(닫기), 실패 → `response_action:{errors}` 바디.
- 잘못된/누락 서명 → 빈 401(미노출 N1). `@RequestBody String rawBody`만(@RequestParam 병용 금지).
**GREEN**. 크기검사 → 서명검증 → form-decode → `payload` → 파서 → 서비스. 서비스 반환(빈 ack / response_action)을 그대로 응답.
**REFACTOR**. SlackSecurityConfig test-boot permitAll `/slack/interactions` + CSRF-ignore(C2, SL-04 D8 — 중앙 등록 배포조립 후속).
**검증**. `./gradlew :modules:slack-integration:test --tests '*SlackInteractionsControllerTest*'`

### Task 11. E2E + prod 조립 부팅 (완료 왕복·미연결·무권한·충돌·401·크기·조립)

**메타**.
- agent: `qa-engineer`
- files: [`backend/modules/slack-integration/src/test/kotlin/com/bts/slack/SlackInteractionEndToEndTest.kt`]
- depends-on: [9, 10]

**RED/GREEN**. 유효 서명 생성 → (1) 완료 block_actions → views.open mock 호출 (2) 완료 view_submission → transition + chat.update + V703 SUCCESS (3) 무권한(StubIssueTransitionPort 권한예외 시드) → response_action + V703 PERMISSION_DENIED (4) OCC(예외 시드) → response_action + V703 CONFLICT (5) 미연결 → ephemeral + UNMAPPED (6) 서명 실패 → 401 (7) 초과 본문 → 413. Slack SDK mock. identity-access prod+RANDOM_PORT 레시피([[identity-access-prod-randomport-boot-recipe]]).
**REFACTOR**. `:modules:app` prod 조립 부팅 — issue-tracking `IssueCompletionOptionsAdapter`·`IssueTransitionAdapter`(@Component) 결선 확인([[prod-assembly-boot-verification-required]] — 머지 전 rebase+:modules:app:test).
**검증**. `./gradlew :modules:slack-integration:test --tests '*SlackInteractionEndToEndTest*' && ./gradlew :modules:app:test`

## Plan 메타

- task 수: 11 (PR1, v2 — 리뷰로 T1→11 확장). PR2 후속(담당자 users_select + 코멘트 모달) ~5 task.
- 모듈: shared-kernel(T1·T2) → issue-tracking(T2·T3) + slack(T4~T11). cross-module 컴파일 직렬화([[bts-plan-wave-gradle-module-compile]]).
- 예상 wave: W0=T1·T2 → W1=T3·T4·T5·T6·T7(병렬, files 무충돌) → W2=T8 → W3=T9 → W4=T10 → W5=T11.
- TDD 강제: yes. PR1 전부 **동기**(@Async 0). 추가 검증: ktlint/detekt(모듈 baseline)·:modules:app 부팅.
- 공유 파일 주의: T6(SlackMessageClient.kt)·T7(SlackBlockKitRenderer.kt·SlackDeliveryWorker.kt)는 별 파일. T7·T5가 `interaction/` 패키지 공유하나 서로 다른 파일(SlackModalBuilder vs Payload/Parser). V703은 T4 단독.

## 리뷰 결과

### 적대적 plan 리뷰 (2 렌즈 병렬, 2026-07-12)

**보안 렌즈 (general-purpose subagent)**.
- **[BLOCKER]** 완료(transition) 실패분류 불가 — IssueTransitionPort generic RuntimeException, slack이 issue-tracking 도메인 예외 import 불가(BC 격리), transition용 shared-kernel 타입 예외 부재 → N3 감사·S5/S6 구분 구현 불가. **→ 반영. T2 신설**(IssueTransitionPermissionDeniedException/IssueOptimisticLockException + 어댑터 번역).
- [CONCERN] getCompletionOptions==null 경로 미테스트·미감사·NPE 위험. **→ 반영. T8 RED에 null 케이스(S5b) + V703 + null 가드.**
- [CONCERN] `/slack/interactions` 본문 크기 상한(pre-HMAC DoS 가드) 누락 — SL-04 SlackCommandsController에서 후퇴. **→ 반영. T10 크기상한 413.**
- [CONCERN] 다중 done 전이 시 toStateKey 박제 vs state_values 불일치. **→ 반영. F4/F5·T5/T7: private_metadata에서 toStateKey 제외, state_values로.**
- [NIT] best-effort V703이 PERMISSION_DENIED 증거 못 남길 수 있음. **→ 반영. T4 REFACTOR WARN 별도 흔적.**
- 긍정 확인. actor 위조 차단·서명검증 위치·신규 포트 fail-closed·permitAll 스코프·봇토큰/private_metadata 비밀 무 — 이상 없음.

**엔지니어링 렌즈 (general-purpose subagent)**.
- **[BLOCKER]** view_submission엔 response_url 없음 → 모달 제출 실패 ephemeral 전달 수단 부재. **→ 반영(Option A). 완료 제출 동기 transition + response_action:{errors}**(F5/N2/T8/T10). @Async 제거로 self-invocation 함정도 동시 해소.
- **[BLOCKER]** slack test-boot에 IssueTransitionPort·IssueCompletionOptionsPort 빈 부재 → SlackInteractionService 추가 시 형제 full-boot(SlackContextLoadTest·SlackSlashCommandEndToEndTest) NoSuchBean 회귀. @MockBean 부족. **→ 반영. T9 신설**(SlackTestcontainersConfig에 seedable Stub @Bean 2종).
- [CONCERN] @Async self-invocation 함정. **→ 반영. PR1 전체 동기(@Async 0).**
- [CONCERN] 완료 버튼 렌더가 SlackDeliveryWorker 단일 render() 경로 → 모든 알림 DM에 버튼 오배치. **→ 반영. T7에 SlackDeliveryWorker 추가·eventType==ISSUE_ASSIGNED 게이팅.**
- [CONCERN] toStateKey 이중정의(보안 렌즈와 동일). **→ 반영.**
- [NIT] 크기상한(보안 렌즈와 동일)·동기 핸들러 200 수렴. **→ 반영. T8/T10 top-level try/catch.**
- 긍정 확인. 신규 포트 결합설계·3초 룰 분리·wave 그래프·prod 조립·T1 RED 실질성 — 이상 없음.

**BLOCKER 처리**. 3건 모두 plan v2에 반영 완료(무시/보류 없음). taste 결정 없음 — 전부 엔지니어링 교정.
