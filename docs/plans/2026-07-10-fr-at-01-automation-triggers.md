# FR-AT-01 — 자동화 트리거 (automation BC 첫 기능)

> slug: fr-at-01-automation-triggers
> type: api
> agent: backend-engineer
> primary_bc: automation
> 생성: 2026-07-10

## Brief

FR-AT-01 자동화 트리거. automation BC(TCA — Trigger-Condition-Action 엔진, 7 FR)의 첫 기능이자
BTS 9번째 Gradle 모듈(`backend/modules/automation`) 착수 지점.

5종 트리거: CREATED / UPDATED / COMMENTED / SCHEDULED / WEBHOOK.
백엔드: pgmq consumer + Spring @Scheduled + Webhook 엔드포인트.
데이터 모델: automation_rules(trigger_type, config).
진입조건 §0: MANAGE_AUTOMATION 권한 동반 결선(ADR D1, dead 시드 회피).

D1~D7: 도메인·명세·데이터모델·백엔드·테스트·UI·E2E.

## 도메인 정리

- **BC**: automation (신규 9번째 모듈 `backend/modules/automation`, `com.bts.automation`, test-boot only)
- **영향 엔티티**: `AutomationRule`(신규, 트리거만 — 조건/액션은 FR-AT-02/03), `IssueCommented`(issue-tracking 신규 이벤트)
- **새 용어**: 자동화 룰(AutomationRule) — glossary 트리거/액션 항목으로 대부분 커버, 신규 용어 추가는 Maxi 확인 대기
- **이벤트 통합**: 전용 큐 `q_automation_events`(신규) + `IssueEventPublisher` fan-out(q_webhook_events 선례). automation 대상 = issue.created/updated/commented
- **발화 이음선**: 매칭된 트리거 → `q_automation_execution` 큐 enqueue(FR-AT-02 액션 executor가 소비, 이 FR은 이음선만)
- **cross-BC touch (BC 격리 예외)**: issue-tracking `IssueEventPublisher` 2건 — ① automation fan-out ② IssueCommented 이벤트+발행. FR-API-03 fan-out 선례 준거
- **기존 결정 충돌**: 없음. automation.md 도메인 노트(pgmq 비동기·체인깊이10)와 정합. 체인깊이10은 액션(FR-AT-02) 시점 도입
- **Maxi 확정 3건**: PR범위=백엔드코어 D1~D5 / 발화=실행큐 enqueue / COMMENTED=5종 모두
- **관련 ADR**: [docs/decisions/2026-07-10-fr-at-01-automation-triggers.md](../decisions/2026-07-10-fr-at-01-automation-triggers.md) (생성됨)
- **회귀 함정(memory)**: 신규BC 첫 @Repository test-boot 회귀 · 모듈 첫 @Scheduled/detektMain · pgmq consumer 생명주기 P0 · sealed 서브타입 추가→exhaustive when 전수 · 권한시드↔SchemaMigrationTest 카운트 · Flyway V번호 충돌

## 스펙

전체 스펙. [docs/specs/2026-07-10-fr-at-01-automation-triggers.md](../specs/2026-07-10-fr-at-01-automation-triggers.md)

핵심 시나리오 요약.
- 룰 CRUD(MANAGE_AUTOMATION 가드, PROJECT_ADMIN) + 5종 트리거 타입 enum + triggerConfig 형식 검증
- 5종 트리거 감지 → 매칭 → `q_automation_execution` enqueue (액션 실행은 FR-AT-02)
  - 이슈 이벤트(created/updated/commented): `q_automation_events`(신규 fan-out 큐) 폴링
  - SCHEDULED: @Scheduled + cron(UTC) + nextFireAt 중복억제
  - WEBHOOK: 불투명 토큰 인바운드 엔드포인트(202, 미존재 404)
- cross-BC touch 3곳: issue-tracking(fan-out+IssueCommented)·identity-access(MANAGE_AUTOMATION 시드+resolver)·shared-kernel(port)

## Brainstorming Check

✅ 통과 (자기검토 — 명확 FR이라 대화형 office-hours 대신 직접 작성+적대적 검토). gap 6건(projectKey 파싱·payload 상한·nextFireAt/cron TZ·실행큐 dead-end·disabled 웹훅 404·액터 컨텍스트) 발견 후 전부 자체 해소, Maxi 결정 불필요.

## Plan

> 지속성=JdbcTemplate(slack 선례, 단순 단일테이블 쿼리). automation Flyway=V300~(ADR bc-migration-prefix-policy).
> 큐 소유권: `q_automation_execution`=automation 소유(V300대), `q_automation_events`=producer 선례(q_webhook_events)로
> issue-tracking 생성 후보 — db-engineer가 T2/T10에서 확정. 발행 fan-out end-to-end는 T11 전조립에서 검증.

### Task 1. automation 모듈 부트스트랩 + Testcontainers 베이스

**메타**.
- agent: `backend-engineer`
- files: [`backend/settings.gradle.kts`, `backend/modules/automation/build.gradle.kts`, `backend/modules/automation/detekt-baseline.xml`, `backend/modules/automation/src/main/kotlin/com/bts/automation/package-info 스켈레톤`, `backend/modules/automation/src/test/kotlin/com/bts/automation/AutomationTestcontainersBase.kt`, `backend/modules/automation/src/test/kotlin/com/bts/automation/ModuleBootTest.kt`]
- depends-on: []

**RED**: `ModuleBootTest` — Testcontainers Postgres 위 빈 Spring 컨텍스트 부팅 성공 단언. 모듈 미등록이라 컴파일/부팅 실패.
**GREEN**: settings.gradle.kts에 `include(":modules:automation")`. build.gradle.kts는 slack-integration 미러(test-boot only, JdbcTemplate, Kotest/Testcontainers/ArchUnit, shared-kernel 의존). 빈 detekt-baseline. `AutomationTestcontainersBase`(singleton container `.apply { start() }` — [[concurrent-testcontainers-suite-flaky]] 방지).
**REFACTOR**: 패키지 구조(domain/application/adapter/worker) 스켈레톤 + L1 한글 주석.
**검증**: `./gradlew :modules:automation:test --tests ModuleBootTest`

### Task 2. 데이터 모델 — automation_rules + 큐 (Flyway V300대)

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/automation/src/main/resources/db/migration/automation/V300__automation_rules.sql`, `backend/modules/automation/src/main/resources/db/migration/automation/V301__pgmq_queue_automation_execution.sql`, `backend/modules/automation/src/test/kotlin/com/bts/automation/SchemaMigrationTest.kt`]
- depends-on: [1]

**RED**: `SchemaMigrationTest` — `automation_rules` 컬럼 세트 + `q_automation_execution` 큐 존재 단언(Testcontainers). 마이그레이션 부재로 실패.
**GREEN**: V300 automation_rules(id UUID PK, project_key, name, enabled, trigger_type, trigger_config JSONB, webhook_token_hash UNIQUE nullable, next_fire_at nullable, created_by, created_at, updated_at, version bigint, deleted_at nullable). V301 `SELECT pgmq.create('q_automation_execution')`. `q_automation_events` 소유 결정(producer측 issue-tracking 후보) 주석 명시.
**REFACTOR**: 인덱스(project_key+trigger_type+enabled WHERE deleted_at IS NULL, webhook_token_hash, next_fire_at WHERE trigger_type='SCHEDULED').
**검증**: `./gradlew :modules:automation:test --tests SchemaMigrationTest`

### Task 3. 트리거 도메인 + TriggerType enum + config 형식 검증

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/automation/src/main/kotlin/com/bts/automation/domain/AutomationRule.kt`, `backend/modules/automation/src/main/kotlin/com/bts/automation/domain/TriggerType.kt`, `backend/modules/automation/src/main/kotlin/com/bts/automation/domain/TriggerConfig.kt`, `backend/modules/automation/src/test/kotlin/com/bts/automation/domain/TriggerConfigTest.kt`]
- depends-on: [1]

**RED**: `TriggerConfigTest` — 타입별 config 검증(SCHEDULED cron 필수·파싱, UPDATED fields 선택, WEBHOOK/CREATED/COMMENTED 빈 config) + 잘못된 config 거부. 순수 도메인, DB 무관.
**GREEN**: `TriggerType`(ISSUE_CREATED/ISSUE_UPDATED/ISSUE_COMMENTED/SCHEDULED/WEBHOOK) + `TriggerConfig` sealed/검증 + `AutomationRule` 애그리거트(enable/disable/rename/updateConfig, OCC version). cron 파싱=Spring `CronExpression`.
**REFACTOR**: 검증 예외 도메인 타입 + KDoc.
**검증**: `./gradlew :modules:automation:test --tests TriggerConfigTest`

### Task 4. Repository + 실행 큐 enqueuer (outbound)

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/automation/src/main/kotlin/com/bts/automation/adapter/AutomationRuleRepository.kt`, `backend/modules/automation/src/main/kotlin/com/bts/automation/adapter/AutomationExecutionEnqueuer.kt`, `backend/modules/automation/src/test/kotlin/com/bts/automation/adapter/AutomationRuleRepositoryTest.kt`]
- depends-on: [2, 3]

**RED**: `AutomationRuleRepositoryTest`(Testcontainers) — CRUD + `findEnabledByProjectAndTriggerType` + `findByWebhookTokenHash` + `findScheduledDue(now)` + `updateNextFireAt`(no-bump) + soft delete 제외 + OCC. enqueuer는 `q_automation_execution` 도달 단언.
**GREEN**: JdbcTemplate Repository(첫 @Repository — [[new-bc-first-repository-testboot-context-regression]] 베이스 배선). `AutomationExecutionEnqueuer.enqueue(ruleId, triggerType, triggerEvent)` = `pgmq.send('q_automation_execution', jsonb)`.
**REFACTOR**: row mapper 추출 + SQL 상수화.
**검증**: `./gradlew :modules:automation:test --tests AutomationRuleRepositoryTest`

### Task 5. MANAGE_AUTOMATION 권한 — shared-kernel 포트 + identity-access 구현 + 시드

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/permission/AutomationPermissionResolver.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/permission/IdentityAccessAutomationPermissionResolver.kt`, `backend/modules/identity-access/src/main/resources/db/migration/V034__manage_automation_permission.sql`, `backend/modules/identity-access/src/test/.../PermissionSchemaMigrationTest.kt`, `backend/modules/identity-access/src/test/.../IdentityAccessAutomationPermissionResolverTest.kt`]
- depends-on: []

**RED**: `PermissionSchemaMigrationTest` 카운트 +1(MANAGE_AUTOMATION 시드) + resolver 판정 테스트(PROJECT_ADMIN 통과·비멤버 거부·fail-closed).
**GREEN**: shared-kernel `AutomationPermissionResolver` 포트(WorkflowSchemePermissionResolver 동형). identity-access 구현(멤버십 + role_permissions MANAGE_AUTOMATION 매트릭스, [[crossbc-permission-resolver-not-role-lookup]]·non-null·[[crossbc-resolver-nullable-fail-open]] 금지). V034 시드(V013 MANAGE_WORKFLOW 동형, PROJECT_ADMIN). **V번호 머지직전 재확인**([[migration-vnumber-concurrent-branch-collision]]).
**REFACTOR**: non-prod stub 빈(@ConditionalOnMissingBean — [[profile-scoped-bean-boot-failure]]).
**검증**: `./gradlew :modules:identity-access:test --tests PermissionSchemaMigrationTest --tests IdentityAccessAutomationPermissionResolverTest`

### Task 6. 룰 CRUD 서비스 + 컨트롤러 + MANAGE_AUTOMATION 가드

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/automation/src/main/kotlin/com/bts/automation/application/AutomationRuleService.kt`, `backend/modules/automation/src/main/kotlin/com/bts/automation/adapter/web/AutomationRuleController.kt`, `backend/modules/automation/src/main/kotlin/com/bts/automation/adapter/web/dto/`, `backend/modules/automation/src/test/kotlin/com/bts/automation/web/AutomationRuleControllerTest.kt`]
- depends-on: [4, 5]

**RED**: `AutomationRuleControllerTest`(Testcontainers HTTP end-to-end) — CRUD 5종 + MANAGE_AUTOMATION 403 negative + WEBHOOK 생성 시 토큰 1회 노출·단건조회 미노출 + OCC 409 + config 검증 400.
**GREEN**: Service(도메인 조립·토큰 발급 SHA-256 해시 저장·[[avatar-auth-image-cachebust]] 무관, dashboard share token 선례) + Controller(권한 가드=resolver, [[fr-pm-04-guard-exception-message-http-leak]] 일반메시지 403).
**REFACTOR**: DTO 매핑 정리 + KDoc.
**검증**: `./gradlew :modules:automation:test --tests AutomationRuleControllerTest`

### Task 7. AutomationEventWorker — 이슈 이벤트 트리거 감지

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/automation/src/main/kotlin/com/bts/automation/worker/AutomationEventWorker.kt`, `backend/modules/automation/src/main/kotlin/com/bts/automation/application/TriggerMatcher.kt`, `backend/modules/automation/src/test/kotlin/com/bts/automation/worker/AutomationEventWorkerTest.kt`]
- depends-on: [4]

**RED**: `AutomationEventWorkerTest`(Testcontainers) — q_automation_events에 issue.created/updated/commented 넣고 → 매칭 룰 → q_automation_execution 도달. UPDATED fields 교집합 필터(S4). disabled/soft-deleted 제외. 미관심 타입 skip+archive(EC4).
**GREEN**: pgmq consumer([[pgmq-consumer-message-lifecycle-p0]] read→처리→archive/delete·vt 재시도) + `TriggerMatcher`(이벤트→트리거 매핑, IssueKey에서 projectKey 파싱=G1). @Scheduled 폴링(모듈 첫 @Scheduled — [[module-first-scheduled-worker-detektmain-traps]] @EnableScheduling은 T11 조립).
**REFACTOR**: 매핑 테이블 상수화.
**검증**: `./gradlew :modules:automation:test --tests AutomationEventWorkerTest`

### Task 8. AutomationScheduleWorker — SCHEDULED cron 트리거

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/automation/src/main/kotlin/com/bts/automation/worker/AutomationScheduleWorker.kt`, `backend/modules/automation/src/test/kotlin/com/bts/automation/worker/AutomationScheduleWorkerTest.kt`]
- depends-on: [4]

**RED**: `AutomationScheduleWorkerTest` — nextFireAt 경과 룰 발화→enqueue + nextFireAt 갱신 중복억제(S6/EC5, 한 폴링 여러주기 경과 시 1회) + Clock 주입 결정성.
**GREEN**: @Scheduled fixedDelay 폴링 → `findScheduledDue(now)` → enqueue → `CronExpression.next()`로 nextFireAt 갱신(UTC=G3). Clock 주입([[fr-rp-04-d6-d7-cycle-time-done]] 선례).
**REFACTOR**: cron 평가 헬퍼 추출.
**검증**: `./gradlew :modules:automation:test --tests AutomationScheduleWorkerTest`

### Task 9. AutomationWebhookController — WEBHOOK 인바운드 트리거

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/automation/src/main/kotlin/com/bts/automation/adapter/web/AutomationWebhookController.kt`, `backend/modules/automation/src/test/kotlin/com/bts/automation/web/AutomationWebhookControllerTest.kt`]
- depends-on: [4]

**RED**: `AutomationWebhookControllerTest` — 유효 토큰→202+enqueue(payload) + 미존재/취소/disabled 토큰 404(S7/G5) + payload 크기 상한 413(G2). permitAll(토큰 인증).
**GREEN**: 토큰 SHA-256 해시 조회→enqueue. 동기 경로=조회+enqueue만(NFR2 <200ms). 서블릿 기본상한 무력화 방지([[multipart-default-limit-app-policy-false-green]]) — 앱 정책 크기 검증.
**REFACTOR**: 토큰 해시 유틸 공유(T6와 동일 해시 함수 — 파일 겹침 없으면 shared util 위치 조정).
**검증**: `./gradlew :modules:automation:test --tests AutomationWebhookControllerTest`

### Task 10. issue-tracking 확장 — IssueCommented 이벤트 + fan-out

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/event/IssueDomainEvent.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/event/IssueEventPublisher.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/comment/application/CommentApplicationService.kt`, `backend/modules/issue-tracking/src/main/resources/db/migration/issue-tracking/V0xx__pgmq_queue_automation_events.sql`, `backend/modules/issue-tracking/src/test/.../IssueEventPublisherTest.kt`, `backend/modules/issue-tracking/src/test/.../CommentApplicationServiceTest.kt`]
- depends-on: []

**RED**: `IssueEventPublisherTest` — created/updated/commented가 q_automation_events로 fan-out 단언(q_webhook_events 선례). `CommentApplicationServiceTest` — 댓글 생성 시 IssueCommented 발행(MANDATORY 트랜잭션).
**GREEN**: `IssueCommented(issueKey, projectKey, commentId, actorId, occurredAt)` sealed 서브타입 추가 → **전 모듈 exhaustive `when (event)` grep 후 전수 갱신**(isWebhookPublishable 등, IssueCommented→false·[[enum-add-breaks-crossmodule-count-guard]]·EC8). IssueEventPublisher fan-out(created/updated/commented→q_automation_events). CommentApplicationService 발행 결선. q_automation_events 큐 생성(issue-tracking 다음 V번호).
**REFACTOR**: fan-out 대상 판정 exhaustive when(under-send 컴파일 차단).
**검증**: `./gradlew :modules:issue-tracking:test`

### Task 11. 모듈 전조립 + end-to-end 통합 + 문서 동기화

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/automation/src/main/kotlin/com/bts/automation/AutomationConfiguration.kt`, `backend/modules/automation/src/test/kotlin/com/bts/automation/AutomationEndToEndTest.kt`, `backend/modules/automation/src/test/kotlin/com/bts/automation/architecture/BcIsolationArchTest.kt`, `docs/plan/product/automation.md`, `docs/plan/fr-index.md`, `docs/sdd/08-automation-engine.md`]
- depends-on: [6, 7, 8, 9, 10]

**RED**: `AutomationEndToEndTest`(Testcontainers 전조립) — 룰 생성 API → 트리거 이벤트 → q_automation_execution 도달까지 5종 각 경로. `BcIsolationArchTest`(identity-access/issue-tracking import 0, 권한은 resolver만).
**GREEN**: `@EnableScheduling` + 빈 배선(AutomationConfiguration). 전 모듈 exhaustive when 그린 확인. 문서 전수 동기화(automation.md D1~D5 `[x]`·fr-index D단계·SDD 카운트, CLAUDE.md "8개 모듈"→"9개 모듈").
**REFACTOR**: verify-master-plan.sh 통과 확인.
**검증**: `./gradlew :modules:automation:test :modules:issue-tracking:test :modules:identity-access:test :modules:shared-kernel:test` + `bash scripts/verify-master-plan.sh`

## Plan 메타

- task 수: 11
- 예상 wave: 5 (W1: T1·T5·T10 / W2: T2·T3 / W3: T4 / W4: T6·T7·T8·T9 / W5: T11)
- 파일 겹침 직렬화: T2·T3·T4 automation 내부 순차, T6·T7·T8·T9는 서로 다른 파일이라 병렬
- cross-BC 병렬: T5(shared-kernel+identity-access)·T10(issue-tracking)은 automation 내부 task와 무충돌 → 조기 wave
- TDD 강제: yes (모든 task RED→GREEN→REFACTOR)
- 신규 BC 단일 PR — D1~D5 코어(Maxi 확정). D6/D7은 후속 PR. task 11개는 BC 부트스트랩 특성상 응집(추가 PR 분할 시 수직슬라이스 파편화)
- 추가 검증: ktlint + detekt(신규 모듈 빈 baseline) + ArchUnit(BC 격리) + verify-master-plan.sh

## 리뷰 결과 (← /bts-review-plan 채움)
