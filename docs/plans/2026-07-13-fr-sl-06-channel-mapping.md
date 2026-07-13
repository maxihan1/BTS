# FR-SL-06 채널 매핑

> slug: fr-sl-06-channel-mapping
> type: backend
> agent: backend-engineer
> 생성: 2026-07-13

## Brief

FR-SL-06 채널 매핑 구현. slack-integration BC의 마지막 남은 FR (현재 5/6 완료).
사용자 원문: "fr-sl-06 진행하자"
classify: type=backend, agent=backend-engineer, slug=fr-sl-06

## 도메인 정리

- **BC**. slack-integration (주). 라우팅 팬아웃 지점 결정에 따라 notification BC producer 소폭 변경 가능성.
- **FR**. FR-SL-06 — 채널 ↔ 프로젝트 매핑 (product/slack-integration §2.3, SDD 09 §9.1.1 "Slack 채널 = 프로젝트별 활동 피드").
- **핵심 엔티티 (신규)**. `ChannelProjectMapping` — Slack 채널 ↔ 프로젝트 매핑 + 이벤트 종류 필터. 테이블 `slack_channel_project_map`.
  - 다대다. 한 프로젝트 → 여러 채널, 한 채널 → 여러 프로젝트.
  - `event_filter`. 이 매핑으로 라우팅할 이벤트 종류 집합 (notification `NotificationEventType.wireValue` 문자열 기준, BC 격리로 enum 직접 import 금지).
- **유비쿼터스 언어**. "채널 매핑(channel mapping)", "프로젝트 활동 피드(project activity feed)", "이벤트 필터(event filter)". glossary에 신규 용어 추가 후보.
- **기존 코드 지형 (검증 완료)**.
  - 이벤트 소스 `q_issue_events` — **projectKey 보유**(`NotificationWorker.buildSourceEvent`). 단 **경쟁 소비 큐** — 두 번째 consumer 금지(companion KDoc 명시). fan-out은 용도별 큐 분리 필요.
  - 현행 Slack 경로 = 수신자 단위 DM (`notification.SlackChannelSender` → `q_slack_deliveries` → `slack.SlackDeliveryWorker`). projectId 없음, per-recipient granularity.
  - 채널 브로드캐스트는 **이벤트당 1회**(수신자 무관) 라 DM 경로 재사용 불가.
  - 재사용 자산. `SlackMessageClient.chat.postMessage`(채널 게시 가능), `SlackBlockKitRenderer`, `SlackBotTokenResolver`(teamId→봇토큰), `SystemPermissionResolver` 포트(관리자 가드), pgmq 워커 패턴(vt/재시도/dead-letter).
- **관련 ADR**. [[2026-07-10-fr-sl-02-slack-notification-delivery]](큐 경계 패턴), [[2026-07-07-fr-sl-01-slack-bot-app]](BC 신설 원칙). 충돌 없음. 본 FR로 신규 ADR 1건 예상(라우팅 팬아웃 결정).
- **핵심 결정 (Maxi 확정 2026-07-13)**.
  1. **라우팅 팬아웃 = notification 브로드캐스트**. notification `NotificationWorker.dispatch()`가 이벤트당 1회 프로젝트 브로드캐스트를 **새 큐 `q_slack_channel_broadcasts`**로 발행(projectKey 보유). slack BC 새 워커가 소비 → `slack_channel_project_map`(projectKey+eventType) 조회 → event_filter 적용 → 채널당 1회 `chat.postMessage`. JSON 큐 경계 = FR-SL-02 `SlackChannelSender` 패턴 재사용. 매핑 키 = **projectKey(String)** (라우팅 시점 cross-BC 조회 회피 — 스펙 `project_id` 컬럼은 projectKey deviation).
  2. **CRUD 권한 = 프로젝트 관리자**. `AutomationPermissionResolver` 패턴 미러 → **신규 cross-BC 포트 `SlackChannelMappingPermissionResolver`**(shared-kernel, Boolean fail-closed, actorId:UUID + projectKey:String, prod adapter=identity-access @Profile prod, non-prod=consumer stub). 소비자(slack 컨트롤러)가 거부 시 일반 403.
- **BC 경계**. 한 PR = slack BC(매핑/CRUD/워커/포트) + notification BC(브로드캐스트 producer 1개). notification은 이미 slack용 producer(`SlackChannelSender`) 보유 — 확립된 경계 내 확장. 큐/포트 JSON·인터페이스 경계만 공유, 도메인 타입 직접 import 금지.

## 스펙

전체 스펙. [docs/specs/2026-07-13-fr-sl-06-channel-mapping.md](../specs/2026-07-13-fr-sl-06-channel-mapping.md)

**스코프**. 이 PR = 백엔드 코어 D1~D5. D6 UI/D7 E2E는 후속 PR(FR-SL-01/02 관례).

핵심 시나리오 요약.
- 프로젝트 관리자가 프로젝트↔Slack 채널 매핑(+이벤트 필터) CRUD. 비관리자 403(fail-closed).
- 프로젝트 이벤트 발생 → notification이 `q_slack_channel_broadcasts`로 이벤트당 1회 브로드캐스트 → slack 워커가 event_filter 매칭 채널마다 1회 게시(채널별 effectively-once, 다대다 팬아웃).
- 보안등급 걸린 이슈는 채널 게시 제외(fail-closed, Maxi 확정).

신규 자산. `slack_channel_project_map`(V704) · CRUD API(`/api/v1/slack/channel-mappings`) · `SlackChannelBroadcaster`(notification) · slack 채널 워커 · cross-BC 포트 2종(`SlackChannelMappingPermissionResolver`·`IssueSecurityClassificationPort`).

## Brainstorming Check

✅ 통과 (1회). refinement 4건 반영 + team_id 단일설치 가정 문서화 + 보안 결정(보안등급 이슈 제외) Maxi 확정 → 신규 포트 FR9 추가. 잔여 gap 없음.

## Plan

> **이 PR = PR-A (설정/CRUD)**. 매핑 CRUD + 권한 포트 + identity-access prod 어댑터 + prod 조립. PR-B(라우팅)는 후속.
> agent 기본값 = `backend-engineer`. 권한/보안 task는 `security-engineer` 지정. 마이그레이션은 `db-engineer`.
> BC 격리 — slack은 notification `NotificationEventType` enum을 **import 금지**. 라우팅 이벤트 카탈로그는 slack이 wire 문자열로 미러(SlackDeliveryWorker `ASSIGNED_EVENT_TYPE` 선례).

### Task 1. V704 `slack_channel_project_map` 마이그레이션 + init_codegen 미러

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/slack-integration/src/main/resources/db/migration/slack-integration/V704__slack_channel_project_map.sql`, `backend/modules/slack-integration/src/main/resources/db/init_codegen.sql`, `backend/modules/slack-integration/src/test/kotlin/com/bts/slack/persistence/V704SchemaMigrationTest.kt`]
- depends-on: []

**RED**. Testcontainers 마이그레이션 검증 테스트 — `slack_channel_project_map` 테이블 존재 + 컬럼(team_id, project_key, channel_id, channel_name, event_types text[], created_at, updated_at) + `UNIQUE(team_id, project_key, channel_id)` + `(project_key)` 인덱스 확인. 실패: 테이블 부재.

**GREEN**. V704 `CREATE TABLE slack_channel_project_map(...)` + UNIQUE + INDEX. `event_types text[] NOT NULL`. init_codegen.sql 동일 미러(DATA.md §jOOQ init_codegen 미러).

**REFACTOR**. 컬럼 주석 + L1 한글 헤더 주석.

**검증**. `./gradlew :backend:slack-integration:test --tests "*V704SchemaMigrationTest*"`

### Task 2. `ChannelProjectMapping` 도메인 + 이벤트 카탈로그 + 검증

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/slack-integration/src/main/kotlin/com/bts/slack/domain/ChannelProjectMapping.kt`, `backend/modules/slack-integration/src/main/kotlin/com/bts/slack/domain/SlackChannelEventType.kt`, `backend/modules/slack-integration/src/test/kotlin/com/bts/slack/domain/ChannelProjectMappingTest.kt`]
- depends-on: []

**RED**. `ChannelProjectMapping` 생성 시 (a) eventTypes 빈 집합 → 예외, (b) 미지 wire 문자열 → 예외, (c) 정상 → 값 보존. `SlackChannelEventType`은 notification wireValue 미러 카탈로그(`issue.created`·`issue.transitioned`·`issue.commented`·`issue.assigned`·`issue.mentioned`·... 10종).

**GREEN**. 불변 data class(id, teamId, projectKey, channelId, channelName?, eventTypes:Set<String>, createdAt, updatedAt) + `SlackChannelEventType.isKnown(wire)` / `validEventTypes(...)` 검증. notification enum import 0.

**REFACTOR**. 검증 메시지 상수화 + KDoc(BC 격리 미러 사유 명시).

**검증**. `./gradlew :backend:slack-integration:test --tests "*ChannelProjectMappingTest*"`

### Task 3. `SlackChannelMappingPermissionResolver` cross-BC 포트 (shared-kernel)

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/permission/SlackChannelMappingPermissionResolver.kt`, `backend/modules/shared-kernel/src/test/kotlin/com/bts/shared/architecture/SharedKernelBoundaryArchTest.kt`(기존 확장 여부만 확인)]
- depends-on: []

**RED**. 포트 인터페이스 부재로 shared-kernel 컴파일/경계 테스트 실패(또는 계약 테스트: 원시 타입 시그니처 `hasManageChannelMapping(actorId: UUID, projectKey: String): Boolean`).

**GREEN**. `AutomationPermissionResolver` 미러 인터페이스 작성 — Boolean fail-closed, 원시 타입만, default 금지. KDoc: prod=identity-access(@Profile prod), non-prod=slack consumer 스텁, fail-open 금지.

**REFACTOR**. KDoc `@see AutomationPermissionResolver`.

**검증**. `./gradlew :backend:shared-kernel:test`

### Task 4. `SlackChannelMappingRepository` + Jdbc 구현 (text[] 처리)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/slack-integration/src/main/kotlin/com/bts/slack/application/SlackChannelMappingRepository.kt`, `backend/modules/slack-integration/src/main/kotlin/com/bts/slack/persistence/JdbcSlackChannelMappingRepository.kt`, `backend/modules/slack-integration/src/test/kotlin/com/bts/slack/persistence/JdbcSlackChannelMappingRepositoryIntegrationTest.kt`]
- depends-on: [1, 2]

**RED**. Testcontainers 통합 테스트 — insert/findByProjectKey/update(eventTypes,channel)/delete/findById + UNIQUE 위반 시 예외. text[] round-trip(Set<String>) 검증. 실패: 구현 부재.

**GREEN**. 인터페이스(save/findByProjectKey/findById/update/deleteById) + Jdbc 구현. `event_types` text[]는 `java.sql.Array`(`connection.createArrayOf("text", ...)`) 바인딩·`ResultSet.getArray` 역매핑. `?` 바인딩만(문자열 결합 금지, DATA.md §5).

**REFACTOR**. RowMapper 추출 + KDoc.

**검증**. `./gradlew :backend:slack-integration:test --tests "*JdbcSlackChannelMappingRepositoryIntegrationTest*"`

### Task 5. non-prod 스텁 `AlwaysAllowSlackChannelMappingPermissionResolver` (slack, @Profile !prod)

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/slack-integration/src/main/kotlin/com/bts/slack/config/AlwaysAllowSlackChannelMappingPermissionResolver.kt`, `backend/modules/slack-integration/src/test/kotlin/com/bts/slack/config/AlwaysAllowSlackChannelMappingPermissionResolverTest.kt`]
- depends-on: [3]

**RED**. 스텁이 dev/test에서 `hasManageChannelMapping(...) == true` 반환(소비 모듈 fail-safe 스텁, WorkflowSchemePermissionResolver AlwaysAllow 동형). 실패: 스텁 부재.

**GREEN**. `@Component @Profile("!prod")` 스텁 — 항상 true(dev/test 편의). KDoc: prod은 identity-access 어댑터가 실판정.

**REFACTOR**. KDoc consumer-owns-stub 명시.

**검증**. `./gradlew :backend:slack-integration:test --tests "*AlwaysAllowSlackChannelMapping*"`

### Task 6. `SlackChannelMappingService` (CRUD + team_id 해석 + 권한 게이트 + 검증)

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/slack-integration/src/main/kotlin/com/bts/slack/application/SlackChannelMappingService.kt`, `backend/modules/slack-integration/src/main/kotlin/com/bts/slack/application/SlackChannelMappingExceptions.kt`, `backend/modules/slack-integration/src/test/kotlin/com/bts/slack/application/SlackChannelMappingServiceTest.kt`]
- depends-on: [2, 3, 4]

**RED**. 서비스 단위 테스트(mock repo/permission/install) — (a) 비관리자(resolver=false) → 권한 예외, (b) 정상 create → team_id를 유일 SlackInstall에서 해석·저장, (c) 설치 0건 → 생성 거부 예외, (d) 미지 eventType → 검증 예외, (e) 중복(UNIQUE) → 충돌 예외, (f) list/update/delete 각 권한 게이트 선확인. 실패: 서비스 부재.

**GREEN**. `@Service` — 각 연산 전 `permissionResolver.hasManageChannelMapping(actorId, projectKey)` fail-closed 확인(거부 시 도메인 예외). team_id = `SlackInstallRepository`(기존 재사용)에서 유일 설치 해석(0건 예외). create/list/update/delete 위임. 인증 추출은 리소스 조회보다 먼저(learnings).

**REFACTOR**. 권한 게이트 헬퍼 추출(DRY) + KDoc.

**검증**. `./gradlew :backend:slack-integration:test --tests "*SlackChannelMappingServiceTest*"`

### Task 7. `SlackChannelMappingController` + DTO + 예외 핸들러

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/slack-integration/src/main/kotlin/com/bts/slack/web/SlackChannelMappingController.kt`, `backend/modules/slack-integration/src/main/kotlin/com/bts/slack/web/SlackChannelMappingResponses.kt`, `backend/modules/slack-integration/src/main/kotlin/com/bts/slack/web/SlackChannelMappingExceptionHandler.kt`, `backend/modules/slack-integration/src/test/kotlin/com/bts/slack/web/SlackChannelMappingControllerTest.kt`]
- depends-on: [6]

**RED**. WebMvc 슬라이스 테스트(mock service) — POST 201, GET 200 목록, PATCH 200, DELETE 204, 검증 400, 권한 403(일반 메시지·내부사정 비노출), 미존재 404, 중복 409, 미인증 401. 응답에 team_id 비노출.

**GREEN**. `@RestController @RequestMapping("/api/v1/slack/channel-mappings")` + `@AuthenticationPrincipal Jwt?`(JWT 전용). 4 엔드포인트 + `@ControllerAdvice`(basePackages 스코프 제한 — 타 컨트롤러 오염 금지, learnings) 예외→상태코드 매핑.

**REFACTOR**. DTO↔도메인 매핑 함수 추출 + KDoc.

**검증**. `./gradlew :backend:slack-integration:test --tests "*SlackChannelMappingControllerTest*"`

### Task 8. identity-access prod 어댑터 `IdentityAccessSlackChannelMappingPermissionResolver` (@Profile prod, PROJECT_ADMIN)

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/permission/IdentityAccessSlackChannelMappingPermissionResolver.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/permission/IdentityAccessSlackChannelMappingPermissionResolverTest.kt`]
- depends-on: [3]

**RED**. 어댑터 테스트 — (a) actor가 projectKey의 `PROJECT_ADMIN` 역할 보유 → true, (b) MEMBER/비멤버 → false, (c) 미해석 projectKey → false(fail-closed). `ProjectSecuritySchemeService.requireProjectAdmin` 패턴 미러(`ProjectMembershipRepository.findByProjectAndUser` → `role == ProjectRole.PROJECT_ADMIN`). 신규 권한 코드 시드 없음.

**GREEN**. `@Component @Profile("prod")` — projectKey→projectId 해석 후 멤버십 역할 확인. 미해석/비관리자 false. `IdentityAccessAutomationPermissionResolver` 구조 미러.

**REFACTOR**. KDoc(PROJECT_ADMIN 직접 확인 사유 — 신규 코드 회피).

**검증**. `./gradlew :backend:identity-access:test --tests "*IdentityAccessSlackChannelMappingPermissionResolverTest*"`

### Task 9. prod 조립 배선 + 부팅 검증 (:modules:app)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/app/src/test/kotlin/...`(조립 부팅/컨텍스트 테스트, 기존 확장), 필요 시 full-boot 슬라이스 `@MockBean` 보강]
- depends-on: [5, 7, 8]

**RED**. `:modules:app` prod 프로파일 컨텍스트 로드 테스트 — 새 포트 소비(slack 서비스)의 prod 어댑터(identity-access) 빈 해석 확인. 어댑터 미배선 시 NoSuchBean 실패(new-crossbc-dep 회귀 가드). 슬라이스 전 로드 테스트가 새 포트로 깨지면 `@MockBean`/스텁 보강.

**GREEN**. 컴포넌트 스캔/프로파일 정합 확인(@Profile prod 어댑터 + !prod 스텁 배타). 필요한 슬라이스 `@MockBean` 추가.

**REFACTOR**. 없음(배선 검증 중심).

**검증**. `./gradlew :modules:app:test`(prod 조립) + `./gradlew :backend:slack-integration:test`(전 모듈 그린)

## Plan 메타

- task 수: 9
- 예상 wave: 4 (W1: T1·T2·T3 / W2: T4·T5·T8 / W3: T6 / W4: T7 → T9는 T5·T7·T8 후 최종). 파일/모듈 겹침 없어 W1 3-병렬.
- TDD 강제: yes (test→feat 커밋 순서 검증)
- 병렬 dispatch: bts-impl이 depends-on + files 교집합으로 wave 계산
- 추가 검증: ktlint·detekt(모듈별 baseline), :modules:app prod 부팅(rebase 후), verify-master-plan(전수 동기화)
- BC 경계: slack↔notification enum import 0(미러 카탈로그), 권한은 shared-kernel 포트 경계
- 후속(PR-B): 브로드캐스터·채널 워커·보안 게이트 포트·issue-tracking 어댑터

## 리뷰 결과 (← /bts-review-plan 채움)
