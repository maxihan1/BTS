# FR-SL-06 PR-B — 채널 라우팅 (백엔드)

> slug: fr-sl-06-pr-b-channel-routing
> type: backend
> agent: backend-engineer
> 생성: 2026-07-13

## Brief

slack-integration BC 마지막 FR(FR-SL-06 채널↔프로젝트 매핑)의 후반부 PR-B(라우팅).
범위 = 백엔드 라우팅만 (D6 UI / D7 E2E는 후속).

PR-A(#264, 머지 완료)가 깔아둔 기반:
- `slack_channel_project_map` (V704, project_key, event_types text[], 하드삭제)
- CRUD API + `SlackChannelMappingPermissionResolver` 포트 + identity-access prod 어댑터
- `SlackChannelEventType` wire 미러 10종

PR-B가 채울 3덩어리:
- (a) notification 모듈: `q_slack_channel_broadcasts` 신규 pgmq 큐 + 이슈 이벤트당 1회
      fan-out 브로드캐스터 (q_issue_events는 competing-consumer라 재사용 불가)
- (b) slack 모듈: 채널 워커 — 매핑 조회 → event_filter(event_types) 매칭 →
      채널당 chat.postMessage 게시
- (c) 보안 게이트: 신규 cross-BC 포트 `IssueSecurityClassificationPort`로
      보안등급 이슈 fail-closed 제외 (issue-tracking prod 어댑터)

완료 시: slack BC 6/6 마킹 + 문서 전수 동기화(verify-master-plan).

관련 산출물(PR-A):
- spec `docs/specs/2026-07-13-fr-sl-06-channel-mapping.md`
- plan `docs/plans/2026-07-13-fr-sl-06-channel-mapping.md`
- ADR `docs/decisions/2026-07-13-fr-sl-06-channel-mapping.md`
- product `docs/plan/product/slack-integration.md §2.3`

## 도메인 정리

- **주 BC**: notification (producer) + slack-integration (consumer). cross-BC 어댑터: issue-tracking (보안게이트).
- **영향 엔티티/개념**:
  - notification: `NotificationWorker.dispatch()` (q_issue_events 소비자) — projectKey 있는 이벤트를 신규 큐로 fan-out. `SlackChannelSender`(FR-SL-02) JSON 큐 경계 패턴 재사용.
  - slack: `SlackNotificationChannel` (프로젝트→채널, slack 도메인 노트에 "FR-SL-06 예정" 명시) = `slack_channel_project_map`(V704, PR-A). 신규 채널 워커.
  - issue-tracking: 이슈 보안등급(`security_level_id`) — 신규 read 포트로 노출.
- **새 용어**: 없음. broadcast/fan-out은 표준 메시징 용어(glossary 추가 불요). "채널 브로드캐스트"(이벤트당 1회, 수신자 무관) vs "DM 딜리버리"(수신자 단위, FR-SL-02)의 granularity 구분만 명확히.
- **기존 결정 충돌**: 없음. PR-A ADR이 PR-B를 이미 확정(D1 라우팅 팬아웃·D5 보안 게이트). 이 PR은 그 결정의 구현.
- **관련 ADR**: [docs/decisions/2026-07-13-fr-sl-06-channel-mapping.md](../decisions/2026-07-13-fr-sl-06-channel-mapping.md) (PR-A, D1·D5가 PR-B 설계 확정 — **신규 ADR 불요**).
- **핵심 도메인 이슈 3건 검증 결과**:
  1. **fan-out 큐 분리 정당** — `q_issue_events`는 notification 전용 경쟁 소비 큐(companion KDoc). slack이 두 번째 consumer로 붙으면 이벤트를 나눠 먹어(competing-consumer) 일부만 게시됨. 채널 게시는 이벤트당 1회(수신자 무관)라 granularity 불일치 → 신규 큐 `q_slack_channel_broadcasts` 필수. (ADR D1, FR-SL-02 큐 경계 선례.)
  2. **BC 격리** — notification→slack는 도메인 타입 import 0, JSON wire 계약만. `SlackChannelEventType`(PR-A) 10종 미러가 `NotificationEventType`과 대응. producer는 wire 필드만 발행(eventType·issueKey·projectKey·title·occurredAt 등).
  3. **confidentiality oracle 회피** — 보안 게이트는 **뷰어별 가시성이 아닌 이슈의 절대 속성**(`security_level_id` non-null)으로 판정. `IssueSecurityClassificationPort.isSecurityRestricted(issueKey)`가 제한/판정불명이면 skip(fail-closed). 위조 가능 actor로 평가하지 않으므로 [[condition-eval-chosen-actor-read-oracle]] 계열 §12.4 오라클 문제 구조적 부재. issueKey 없는 이벤트는 이슈-스코프 아니라 게이트 우회(ADR D5).

## 스펙

전체 스펙 = **기존 FR-SL-06 정본**. [docs/specs/2026-07-13-fr-sl-06-channel-mapping.md](../specs/2026-07-13-fr-sl-06-channel-mapping.md)
(PR-A가 PR-B까지 FR4~FR9·EC1~EC12·완료기준 전부 명세. 별도 PR-B spec 미작성 — drift 방지.)

**PR-B 스코프 (이 PR이 구현하는 FR)**:
- FR4 — notification `SlackChannelBroadcaster` 컴포넌트: `dispatch()`의 **정책 early-return 이전**에서 projectKey 있으면 `q_slack_channel_broadcasts`로 이벤트당 1회 JSON emit(수신자/정책 독립). slack import 0.
- FR5 — slack 채널 워커: `q_slack_channel_broadcasts` 폴링(@Scheduled) → 매핑조회(`findByProjectKey`) → **보안게이트(FR9)** → 매칭 채널별(dedup확인 → 봇토큰해석 → `render(title,issueKey?)` → `chat.postMessage`) → 결과별 pgmq 생명주기(Sent=dedup기록후 delete / Permanent=delete / Retryable=retain, read_ct>MAX archive).
- FR6 — 채널 게시 dedup: 키=(projectKey,eventType,issueKey,occurredAt,channelId), **전송 성공 후에만** 기록(FR-SL-02 B4 exists→send→record).
- FR8 — prod 조립: 새 포트 adapter 2종(권한은 PR-A 완료, 신규=보안게이트) + 워커 @Scheduled 결선, `:modules:app:test` 부팅 검증.
- FR9 — cross-BC 포트 `IssueSecurityClassificationPort.isSecurityRestricted(issueKey)`: prod=issue-tracking(@Profile prod, `security_level_id` non-null), non-prod stub(항상 false). true/불명→해당 이벤트 채널 게시 전부 skip(메시지는 삭제, 유출차단), issueKey null→우회.

**PR-B 밖 (PR-A 완료)**: FR1(V704 테이블)·FR2(CRUD API)·FR3(eventTypes 검증)·FR7(권한 포트+어댑터).

**★ plan 판단 1건 — 채널 dedup 저장소** (spec line 135이 plan으로 위임):
- 옵션 A. 전용 로그 테이블 `slack_channel_broadcast_log(dedup_key PK, ...)` (V705에서 큐와 함께 생성). 의미 분리 명확, cleanup 독립. **권장.**
- 옵션 B. 기존 `slack_delivery_log`(dedup_key PK) 재사용. DM키(recipientUserId 포함)와 채널키(channelId 포함) 비충돌이라 가능하나, DM/채널 로그가 한 테이블에 섞임.
- → 게이트 1에서 Maxi 확인.

**★ 구현 refinement (brainstorming 발견)**: wire payload `dedupKey`는 **이벤트 레벨**(projectKey,eventType,issueKey,occurredAt 해시) — producer는 채널을 모름. 워커가 `dedupKey + channelId`로 per-channel dedup 키 완성.

**신규 마이그레이션 = slack V705**: 큐 `q_slack_channel_broadcasts` 생성(`CREATE EXTENSION pgmq` + `pgmq.create`, producer-creates 예외로 소비 모듈=slack에 배치, V701 선례) [+ 옵션 A 채택 시 dedup 테이블]. JdbcTemplate 모듈이라 init_codegen 미러 없음.

## Brainstorming Check

✅ 통과 (1회 iteration, 집중 자기검증).
- PR-A spec이 PR-B를 이미 comprehensive 하게 명세 — 재작성 대신 정본 참조(drift 방지).
- **발견 1 (구현 refinement)**: wire dedupKey는 이벤트 레벨, 워커가 channelId 덧붙임 → plan 명시.
- **발견 2 (plan 판단 위임)**: 채널 dedup 저장소(전용 vs 재사용) → 게이트 1 Maxi 확인.
- **검증**: producer는 projectKey 있는 이벤트만 emit(없으면 미발행, criterion 3) · issueKey nullable(sprint.*)는 보안게이트 우회하되 event_filter 적용 · 새 cross-BC 포트 소비(slack 워커→IssueSecurityClassificationPort)는 full-boot @MockBean 회귀 확인 필요([[new-crossbc-dep-openapi-mockbean-regression]]).
- 잔여 blocking gap 없음. plan 단계 진행 가능.

## Plan

> 정본 spec FR4~FR9. cross-BC 4모듈(shared-kernel·issue-tracking·slack·notification)+app 조립.
> dedup 저장소는 **옵션 A(전용 테이블)** 로 task 구성 — 게이트 1에서 Maxi 확인(옵션 B 채택 시 Task 3 축소).

### Task 1. shared-kernel 포트 + issue-tracking 보안게이트 prod 어댑터 (FR9)

**메타**.
- agent: `backend-engineer` (security-engineer 리뷰 대상)
- files: [`backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/issue/IssueSecurityClassificationPort.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/IssueSecurityClassificationAdapter.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/adapter/IssueSecurityClassificationAdapterTest.kt`]
- depends-on: []

**RED**: `IssueSecurityClassificationAdapterTest` (Testcontainers, issue-tracking) —
```kotlin
@Test fun `security_level_id non-null 이슈는 제한(true)`()
@Test fun `security_level_id null 이슈는 비제한(false)`()
@Test fun `존재하지 않는 issueKey는 fail-closed(true)`()
```
실패: `IssueSecurityClassificationPort` / `IssueSecurityClassificationAdapter` 없음.

**GREEN**:
- 포트: `interface IssueSecurityClassificationPort { fun isSecurityRestricted(issueKey: String): Boolean }` (default 금지, UUID/String 원시타입만 — SharedKernelBoundaryArchTest 준수).
- 어댑터: `@Component @Profile("prod") class IssueSecurityClassificationAdapter(issueRepository)` — `findByKey(IssueKey(issueKey))?.securityLevelId != null` 판정, **null(미존재)→true(fail-closed)**. `security_level_id`는 issues 테이블(V014).

**REFACTOR**: KDoc(포트 목적=채널 브로드캐스트 제외 게이트, IssueVisibilityPort/IssueSecurityDirectory와 목적 상이 명시).

**검증**: `./gradlew :backend:modules:issue-tracking:test --tests '*IssueSecurityClassificationAdapterTest'`

### Task 2. slack: 보안게이트 non-prod stub (FR9 fail-safe)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/slack-integration/src/main/kotlin/com/bts/slack/config/AlwaysUnrestrictedIssueSecurityClassification.kt`, `backend/modules/slack-integration/src/test/kotlin/com/bts/slack/config/AlwaysUnrestrictedIssueSecurityClassificationTest.kt`]
- depends-on: [1]

**RED**: stub이 항상 `false`(게시 허용) 반환 단위 테스트. 실패: 클래스 없음.
**GREEN**: `@Component @Profile("!prod") class AlwaysUnrestrictedIssueSecurityClassification : IssueSecurityClassificationPort { override fun isSecurityRestricted(issueKey) = false }` (consumer-owns-stub 관례, `AlwaysAllowSlackChannelMappingPermissionResolver` 선례).
**REFACTOR**: KDoc(비prod에선 보안등급 판정 불가→게시 허용, prod만 실제 제한).
**검증**: `./gradlew :backend:modules:slack-integration:test --tests '*AlwaysUnrestricted*'`

### Task 3. slack V705 마이그레이션 + 채널 dedup 저장소 (FR6·큐 생성)

**메타**.
- agent: `db-engineer` (마이그레이션) / backend-engineer 겸(repository)
- files: [`backend/modules/slack-integration/src/main/resources/db/migration/slack-integration/V705__slack_channel_broadcast.sql`, `backend/modules/slack-integration/src/main/kotlin/com/bts/slack/persistence/JdbcSlackChannelBroadcastDedupRepository.kt`, `backend/modules/slack-integration/src/main/kotlin/com/bts/slack/application/SlackChannelBroadcastDedupRepository.kt`, `backend/modules/slack-integration/src/test/kotlin/com/bts/slack/persistence/JdbcSlackChannelBroadcastDedupRepositoryTest.kt`]
- depends-on: []

**RED**: `JdbcSlackChannelBroadcastDedupRepositoryTest` (Testcontainers pgmq 이미지) — `existsPosted(dedupKey)`=false→`recordPosted(dedupKey)`→`existsPosted`=true, 중복 record 멱등. 실패: 테이블/repo 없음.
**GREEN**:
- V705: `CREATE EXTENSION IF NOT EXISTS pgmq; SELECT pgmq.create('q_slack_channel_broadcasts');` (V701:44-45 선례) + `CREATE TABLE slack_channel_broadcast_log(dedup_key text PRIMARY KEY, posted_at timestamptz not null default now())`. **신규 V번호만(기존 편집 금지 — app-test 영속DB 체크섬)**.
- repository 포트 + JdbcTemplate 구현 — `INSERT ... ON CONFLICT (dedup_key) DO NOTHING` 멱등, `existsPosted` SELECT.
**REFACTOR**: dedupKey 컬럼 주석(=event-level hash + channelId).
**검증**: `./gradlew :backend:modules:slack-integration:test --tests '*BroadcastDedupRepositoryTest'`

> **★ 옵션 B 채택 시**: V705는 큐만 생성, 기존 `slack_delivery_log` 재사용(채널키는 channelId 포함→DM키와 비충돌). dedup repository는 기존 것 확장.

### Task 4. slack: SlackMessageClient.postChannelMessage (채널 게시 메서드)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/slack-integration/src/main/kotlin/com/bts/slack/message/SlackMessageClient.kt`, `backend/modules/slack-integration/src/test/kotlin/com/bts/slack/message/SlackMessageClientTest.kt`]
- depends-on: []

**RED**: `postChannelMessage(botToken, channelId, message)` — mock `MethodsClient`, channel=channelId(C…)로 chat.postMessage, `SlackSendResult` 반환(Sent/Retryable/Permanent 분류 재사용). 실패: 메서드 없음.
**GREEN**: `postDirectMessage` 미러(channel 파라미터만 slackUserId→channelId). 봇토큰 3중 미노출 유지(reason=오류코드만).
**REFACTOR**: 공통 게시 로직 추출(postDirectMessage/postChannelMessage 중복 제거).
**검증**: `./gradlew :backend:modules:slack-integration:test --tests '*SlackMessageClientTest'`

### Task 5. notification: SlackChannelBroadcaster + NotificationWorker 결선 (FR4)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/notification/src/main/kotlin/com/bts/notification/channel/SlackChannelBroadcaster.kt`, `backend/modules/notification/src/main/kotlin/com/bts/notification/channel/NotificationTitleBuilder.kt`, `backend/modules/notification/src/main/kotlin/com/bts/notification/worker/NotificationWorker.kt`, `backend/modules/notification/src/test/kotlin/com/bts/notification/channel/SlackChannelBroadcasterTest.kt`, `backend/modules/notification/src/test/kotlin/com/bts/notification/worker/NotificationWorkerTest.kt`]
- depends-on: []

**RED**: `SlackChannelBroadcasterTest` —
```kotlin
@Test fun `projectKey 있으면 q_slack_channel_broadcasts에 이벤트당 1회 emit`()  // JSON: projectKey,eventType,issueKey?,title,occurredAt,dedupKey(이벤트레벨 해시)
@Test fun `projectKey 없으면 emit 안 함`()  // sprint 없는 이벤트 등
```
+ `NotificationWorkerTest` — dispatch가 정책 early-return 이전에 broadcaster 호출(수신자 0이어도 emit). 실패: 클래스/결선 없음.
**GREEN**:
- `@Component class SlackChannelBroadcaster(dsl, objectMapper, titleBuilder)` — `broadcastIfApplicable(event)`: projectKey null이면 no-op, 아니면 `dsl.execute("SELECT pgmq.send('q_slack_channel_broadcasts', ?::jsonb)", json)` (SlackChannelSender.kt:45 미러). dedupKey=`sha256(projectKey|eventType|issueKey|occurredAt)`. slack 도메인 타입 import 0.
- **★ title 출처(리뷰 발견)**: `NotificationSourceEvent`에 title 필드 없음. 기존 `NotificationWorker.buildTitleBody()`(:353, 10개 eventType 커버·issueKey+eventType 기반·actor 조회 없음 FR10)를 **`NotificationTitleBuilder`(title-only) 공용 헬퍼로 추출** → NotificationWorker(수신자 경로)·SlackChannelBroadcaster(브로드캐스트 경로) 공유(DRY). files에 `NotificationTitleBuilder.kt` 추가.
- `NotificationWorker.dispatch()` 최상단(정책 평가 이전)에서 `slackChannelBroadcaster.broadcastIfApplicable(event)` 호출. **best-effort 격리**: 좁은 catch(DataAccessException)로 로그만, 알림 dispatch 미차단(권한예외 아님 — best-effort-loop 함정 무해). 생성자에 broadcaster 주입(기존 테스트 plan files 영향 → mock 추가).
**REFACTOR**: dedupKey 해시 유틸 추출, JSON 빌드 objectMapper.
**검증**: `./gradlew :backend:modules:notification:test --tests '*SlackChannelBroadcasterTest' --tests '*NotificationWorkerTest'`

### Task 6. slack: 채널 브로드캐스트 워커 (FR5, 핵심)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/slack-integration/src/main/kotlin/com/bts/slack/worker/SlackChannelBroadcastWorker.kt`, `backend/modules/slack-integration/src/test/kotlin/com/bts/slack/worker/SlackChannelBroadcastWorkerTest.kt`]
- depends-on: [1, 2, 3, 4]

**RED**: `SlackChannelBroadcastWorkerTest` (Testcontainers pgmq) — 8 시나리오:
```
happy(매핑채널 게시·dedup기록·delete) / 미매핑 프로젝트(skip·delete) /
event_filter 불일치(skip·delete) / 보안등급 이슈(FR9 true→전채널 skip·delete·유출차단) /
issueKey null sprint(보안게이트 우회·게시) / 봇 미설치(skip·delete) /
429/5xx RetryableDeliveryException(retain·read_ct>MAX archive) / 다채널 팬아웃+재전달 dedup(effectively-once)
```
실패: 워커 없음.
**GREEN**: `@Component class SlackChannelBroadcastWorker(jdbcTemplate, objectMapper, mappingRepository, dedupRepository, securityClassificationPort, botTokenResolver, renderer, messageClient)` — `@Scheduled(fixedDelayString="\${bts.slack.channel-broadcast.poll-interval-ms:1000}") pollAndProcess()`. SlackDeliveryWorker 구조 미러:
  1. `pgmq.read('q_slack_channel_broadcasts', vt, batch)`
  2. per-message: 파싱 → issueKey 있으면 `securityClassificationPort.isSecurityRestricted` (try-catch fail-closed skip) → 제한/불명이면 전채널 skip·delete
  3. `mappingRepository.findByProjectKey(projectKey)` → `eventTypes.contains(eventType)` 필터
  4. per-channel: dedupKey+channelId로 `existsPosted` → 있으면 skip / 봇토큰 `resolve(teamId)` null이면 skip → `renderer.render(title, issueKey)` → `messageClient.postChannelMessage` → Sent이면 `recordPosted`·PermanentFailure이면 로그 / RetryableFailure이면 throw RetryableDeliveryException(채널별 독립 실패 격리 vs 배치 재전달 균형 — NFR4)
  5. 생명주기: 성공 delete / retryable retain·archive(read_ct>MAX) / poison archive
  - **@Transactional 부재**(pgmq vt·self-invocation, 기존 워커 동형). @Scheduled은 기존 `SlackSchedulingConfiguration`(@EnableScheduling @Profile !test)이 활성화 — 신규 config 불요(Explore E15 확인).
**REFACTOR**: 채널별 처리/생명주기 헬퍼 분리, SlackDeliveryWorker와 공통 pgmq 헬퍼 중복 최소화.
**검증**: `./gradlew :backend:modules:slack-integration:test --tests '*SlackChannelBroadcastWorkerTest'`

### Task 7. prod 조립 부팅 + full-boot @MockBean 회귀 (FR8)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/app/src/test/kotlin/com/bts/app/BtsApplicationContextTest.kt`, (slack full-boot 슬라이스 테스트 — new-crossbc-dep @MockBean 필요 시)]
- depends-on: [1, 5, 6]

**RED**: (a) `BtsApplicationContextTest`(@ActiveProfiles prod)에 신규 prod 빈 FQN containsBean 단언 추가 — `com.bts.issue.adapter.IssueSecurityClassificationAdapter`, `com.bts.slack.worker.SlackChannelBroadcastWorker`. (b) slack 전체 test 실행 → 새 워커가 소비하는 `IssueSecurityClassificationPort`가 없는 로드 슬라이스에서 NoSuchBean → **@MockBean 추가**([[new-crossbc-dep-openapi-mockbean-regression]]).
**GREEN**: containsBean 단언 통과 확인(@Component 자동 스캔) + 슬라이스 @MockBean 배선.
**REFACTOR**: 없음(검증 태스크).
**검증**: `./gradlew :backend:modules:app:test --tests '*BtsApplicationContextTest'` (prod 프로파일·5433 postgres) + slack 전체 test green.

## Plan 메타

- task 수: 7
- 의존성 그래프: T1→T2, {T1,T2,T3,T4}→T6, {T1,T5,T6}→T7. T3·T4·T5는 서로 독립.
- wave (이론): W1={T1,T3,T4,T5} → W2={T2} → W3={T6} → W4={T7}. **단, 단일 worktree라 Gradle 모듈 컴파일 직렬화 → controller 직렬 dispatch 권장**([[bts-plan-wave-gradle-module-compile]]·최근 slack PR 전부 직렬).
- 예상 시간: 7 task, 직렬 기준 약 25~35분(Testcontainers pgmq 통합 다수).
- TDD 강제: yes (test 커밋이 feat 커밋보다 먼저 — bts-impl 자동 검증).
- 추가 검증: ktlint, detekt(`--rerun-tasks` false-green 방지), :modules:app:test prod 조립 부팅.
- 게이트 1 Maxi 확인 사항: (1) dedup 저장소 옵션 A(전용) vs B(재사용), (2) broadcast emit best-effort 격리 승인.

## 리뷰 결과

> 병렬 dispatch한 security/eng 리뷰 에이전트가 세션 한도로 중단 → **controller가 직접 집중 리뷰**(코드 실증 포함). memory bts-review-plan-autoplan-overkill 원칙(저위험 후속=집중 리뷰).

### eng-review (controller, 2026-07-13) — 코드 실증 포함
- ✅ **PASS 핫패스 emit 위치**: `NotificationWorker.dispatch()` 최상단(policyEvaluator.evaluate 이전, :169-175 실증)이 정확. `if(matches.isEmpty()) return` 이전 emit이라 수신자 0이어도 발행(criterion 3). q_issue_events 단일 소비자(pollAndProcess) 확인.
- ✅ **PASS pgmq 생명주기**: SlackDeliveryWorker와 동형(성공 delete / retryable retain·vt재전달 / read_ct>MAX archive / poison archive).
- ✅ **PASS dedup**: exists→send→record 순서(FR-SL-02 B4 회귀 방어). 전용 테이블 옵션 A 타당.
- ✅ **PASS prod 조립**: @Component 자동 스캔, @Scheduled은 기존 SlackSchedulingConfiguration(@EnableScheduling @Profile !test) 활성화(신규 config 불요), @MockBean 회귀 Task 7 커버.
- ✅ **PASS 마이그레이션**: V705 신규만(기존 편집 금지·체크섬), 큐 생성=소비 모듈(slack) 관례.
- ✅ **PASS task 분해**: 의존성 그래프 정확. Task 1 인터페이스 RED는 어댑터 Testcontainers 테스트로, Task 7 조립 RED는 @MockBean 회귀로 실질 RED 확보.
- ⚠️ **CONCERN-E1 (해소)**: `title` 출처 — NotificationSourceEvent에 title 필드 없음. 기존 `buildTitleBody()`(:353) 재사용(`NotificationTitleBuilder` 추출)로 해소. Task 5 반영.
- ⚠️ **CONCERN-E2 (수용)**: 채널별 RetryableFailure→전체 메시지 재전달→성공 채널 재시도→dedup skip. 정확하나 부분 재시도 비용. dedup이 흡수하므로 수용(NFR1 effectively-once).
- ⚠️ **CONCERN-E3 (게이트1)**: broadcast emit best-effort(좁은 catch DataAccessException)→transient 실패 시 유실. 채널 피드 비크리티컬·알림 핫패스 미차단 우선. Maxi 승인 사항.
- ⚠️ **CONCERN-E4 (minor 수용)**: producer가 projectKey 있는 모든 이벤트 emit(eventType 무관)→worker가 event_types 필터. 큐 노이즈. BC 격리상 producer는 매핑 못 봄 → 수용.

### security-review (controller, 2026-07-13) — 코드 실증 포함
- ✅ **PASS fail-closed 완전성**: `findByKey`는 활성만(deleted_at IS NULL, :253) → 미존재/soft-deleted → 어댑터 `securityLevelId != null` 판정서 null→**true(제한)**. non-null 주입(nullable+?:return fail-open 금지), allow-all default 없음.
- ✅ **PASS confidentiality oracle 부재**: 게이트가 뷰어별 가시성 아닌 **이슈 절대속성**(security_level_id, Issue.kt:116)으로 판정. 위조 가능 actor 미사용 → §12.4 오라클([[condition-eval-chosen-actor-read-oracle]]) 구조적 부재.
- ✅ **PASS 유출 차단**: 보안게이트가 fan-out·render·postMessage **이전**(Task 6 step 2). 제한 이슈는 제목·키가 Slack에 절대 안 나감(EC11).
- ✅ **PASS issueKey null 우회**: sprint.* 이벤트는 이슈-스코프 아님(제목만). 유출 위험 없음.
- ✅ **PASS 봇토큰 미노출**: postChannelMessage가 postDirectMessage 미러(reason=오류코드만, 3중 미노출).
- ✅ **PASS BC 격리**: notification→slack JSON wire(import 0), 권한/보안게이트=shared-kernel 포트.
- ⚠️ **CONCERN-S1 (수용·게이트1 인지)**: 보안게이트=소비자측(worker). 보안등급 이슈 title이 내부 `q_slack_channel_broadcasts` payload에 잠시 존재(워커가 게시 차단). **내부 DB 경계라 외부 유출 아님**(이슈 데이터와 동일 신뢰경계). 생산자측 게이트로 옮기면 notification에 보안 포트 의존 추가 → ADR D5가 소비자측 선택.

### BLOCKER: 없음

게이트 1 Maxi 확인 2건: (1) dedup 저장소 옵션 A(전용) vs B(재사용), (2) broadcast emit best-effort 격리(CONCERN-E3) 승인.
