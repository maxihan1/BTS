# FR-AT-02 — 자동화 액션 (필드 변경/담당자/댓글/API 호출)

> slug: fr-at-02
> type: backend
> agent: backend-engineer (+ security-engineer, db-engineer, designer/frontend-engineer, qa-engineer)
> primary_bc: automation
> 생성: 2026-07-11

## Brief

사용자 원문: "fr-at-02 진행해줘"

FR-AT-02 (automation §2.2) — 자동화 규칙의 **액션(Action)** 실행 엔진.
FR-AT-01(트리거)이 매칭된 규칙을 `q_automation_execution` 큐에 enqueue → FR-AT-02가
consumer로 dequeue 후 4종 액션 실행.

- D1. 도메인 — Action 다형성 (backend-engineer)
- D2. 명세 — 4종 액션(필드 변경/담당자/댓글/API 호출) + 권한 가드 (backend + security-engineer)
- D3. 데이터 모델 — automation_actions(action_type, config) (db-engineer)
- D4. 백엔드 — Action executor + dry-run 모드 (backend-engineer)
- D5. 백엔드 테스트 — 권한 부족 시 reject (backend + security-engineer)
- D6. 프론트 UI — 액션 빌더 (designer → frontend-engineer)
- D7. E2E (qa-engineer)

SDD 참조: 08장 (자동화 엔진). 선행: FR-AT-01(완료, PR #251/#254).

## 도메인 정리

- **BC**: automation (BTS 9번째 모듈, `com.bts.automation`, JdbcTemplate, test-boot)
- **영향 엔티티**:
  - `AutomationRule`(기존, 확장) — 트리거 전용 → **액션 리스트 보유**로 확장
  - `Action`(신규, sealed 4종) — `SetFieldAction`/`AssignAction`/`AddCommentAction`/`CallWebhookAction`
  - `ActionType`(신규 enum 4종)
- **신규 shared-kernel 포트**: `IssueMutationPort`(가칭) — issue-tracking 변경 위임(동기).
  `IssueTransitionPort` 선례 동형. `OutboundUrlValidator`(기존 SSRF 가드) CallWebhook 재사용.
- **새 용어(glossary 후보, Maxi 승인 대기)**:
  - 액션(Action) — 이미 존재, 4종 구체화
  - dry-run 모드 — 실제 커밋 없이 "무엇이 바뀔지 + 권한 통과"만 계산
  - rule actor(룰 액터) — 액션을 실행하는 권한 주체 = 룰 생성자(`created_by`)
  - 실행 체인 깊이(execution depth) — 액션→이벤트→재발화 무한루프 차단(10 제한)
- **Maxi 확정 3결정** (ADR D2/D3/D6):
  1. cross-BC 실행 = **동기 커맨드 포트**(shared-kernel), 비동기 이벤트 큐 기각
  2. 실행 권한 = **룰 생성자(rule actor)**, fail-closed, 트리거유발자/시스템액터 기각
  3. 이번 PR = **백엔드 코어 D1~D5**, UI(D6)/E2E(D7)는 후속 PR
- **기존 결정 충돌**: 없음 (FR-AT-01 ADR D4 이음선을 그대로 소비)
- **관련 ADR**: [docs/decisions/2026-07-11-fr-at-02-automation-actions.md](../decisions/2026-07-11-fr-at-02-automation-actions.md) (생성됨)

## 스펙

전체 스펙. [docs/specs/2026-07-11-fr-at-02-automation-actions.md](../specs/2026-07-11-fr-at-02-automation-actions.md)

핵심 시나리오 3줄 요약.
- executor 워커가 `q_automation_execution`(FR-AT-01 이음선) 첫 소비자로서 룰의 액션 리스트를 순차 실행
- 이슈 변경 3종(SetField/Assign/AddComment)은 shared-kernel `IssueMutationPort`(동기)로 위임,
  외부호출 1종(CallWebhook)은 기존 `OutboundUrlValidator`(SSRF) 재사용
- 권한은 **선택 가능한 rule actor**(기본 created_by)로 fail-closed 강제 + dry-run + 체인 상한

핵심 결정(ADR + Maxi 확정).
- cross-BC 실행 = 동기 커맨드 포트 / rule actor = 선택형(지라 Actor) / 댓글 작성자 = rule actor
- AddComment 템플릿 변수 `{{ var }}` 포함 / 무한루프 = 런타임 상한 + FR-AT-04 위임 / 이번 PR = D1~D5

## Brainstorming Check

✅ 통과 (Phase B adversarial 검토 — gap 4건 발견 후 해소).
- Gap A 댓글 작성자 → 선택 가능한 rule actor(지라 모델), D3 정제
- Gap B 템플릿 변수 → 포함(FR10 신설)
- Gap C 무한루프(왕복 시 깊이 리셋) → 런타임 상한 + FR-AT-04 위임(FR8 정제)
- Gap D at-least-once 중복 → best-effort 수용, dedup 은 FR-AT-05 위임(EC9)

## Plan

> TDD red→green→refactor 강제. 각 task 메타(agent/files/depends-on)로 bts-impl 이 wave 계산.
> **모듈 컴파일 직렬화 주의**([[bts-plan-wave-gradle-module-compile]]) — files 교집합이 없어도 같은
> Gradle 모듈(automation)을 동시 편집하는 task 는 bts-impl 이 컴파일 경합 회피를 위해 직렬화할 수 있다.

### Task 1. 마이그레이션 — automation_actions(V302) + automation_rules.actor_user_id(V303)

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/automation/src/main/resources/db/migration/automation/V302__automation_actions.sql`, `backend/modules/automation/src/main/resources/db/migration/automation/V303__automation_rules_actor_user_id.sql`, `backend/modules/automation/src/test/kotlin/com/bts/automation/SchemaMigrationTest.kt`]
- depends-on: []

**RED**: `SchemaMigrationTest` 에 automation_actions 테이블/컬럼(id, rule_id, position, action_type CHECK 4종, action_config, created_at) + automation_rules.actor_user_id 존재 단언 추가 → 미생성으로 실패.
**GREEN**: V302(테이블 + FK rule_id ON DELETE CASCADE + UNIQUE(rule_id,position) + 인덱스 + CHECK 4종), V303(actor_user_id NOT NULL, 기존 행 backfill=created_by). automation 은 JdbcTemplate 라 init_codegen 미러 불필요.
**REFACTOR**: COMMENT ON + V번호 충돌 재확인([[migration-vnumber-concurrent-branch-collision]]).
**검증**: `./gradlew :backend:modules:automation:test --tests '*SchemaMigrationTest'`

### Task 2. shared-kernel IssueMutationPort + 커맨드/결과 VO

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/issue/IssueMutationPort.kt`, `backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/issue/IssueMutationCommands.kt`, `backend/modules/shared-kernel/src/test/kotlin/com/bts/shared/issue/IssueMutationPortContractTest.kt`]
- depends-on: []

**RED**: 포트 계약 테스트 — `setField`/`assign`/`addComment` 3 메서드, 각 커맨드에 `actorUserId`·`issueKey`·`dryRun` 필드, `MutationResult` 반환 형태 단언.
**GREEN**: `interface IssueMutationPort`(fail-closed, default 구현 없음 — IssueTransitionPort 선례). 커맨드 VO 3종 + `MutationResult`(성공/미커밋예상/오류). actor 는 VO 로 전달(SecurityContext 직접 접근 금지).
**REFACTOR**: KDoc 에 BC 격리·의존 방향·async 안전 근거(IssueTransitionPort 동형).
**검증**: `./gradlew :backend:modules:shared-kernel:test --tests '*IssueMutationPortContractTest'`

### Task 3. Action 도메인 — ActionType(4종) + Action sealed + config 검증

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/automation/src/main/kotlin/com/bts/automation/domain/ActionType.kt`, `backend/modules/automation/src/main/kotlin/com/bts/automation/domain/Action.kt`, `backend/modules/automation/src/main/kotlin/com/bts/automation/domain/ActionConfig.kt`, `backend/modules/automation/src/test/kotlin/com/bts/automation/domain/ActionTest.kt`]
- depends-on: []

**RED**: Action 4종(SetField/Assign/AddComment/CallWebhook) 생성·config 형식 검증(누락 필드 예외) 테스트. TriggerConfig 선례 동형.
**GREEN**: `enum ActionType`(4종) + `sealed class Action` 4 서브타입 + `ActionConfig.validate(type, json)`. cross-BC 존재 검증은 실행 시점(형식만).
**REFACTOR**: 예외 타입 정리(AutomationDomainException 계열), KDoc.
**검증**: `./gradlew :backend:modules:automation:test --tests '*ActionTest'`

### Task 4. TemplateRenderer — `{{ var }}` 단순 치환

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/automation/src/main/kotlin/com/bts/automation/application/TemplateRenderer.kt`, `backend/modules/automation/src/test/kotlin/com/bts/automation/application/TemplateRendererTest.kt`]
- depends-on: []

**RED**: `{{ issue.key }}` 치환, 미정의→빈문자열+경고, 문법오류(`{{` 미닫힘)→리터럴 유지, 중첩 없음(로직 없음) 테스트.
**GREEN**: 정규식 기반 순수 함수 — 컨텍스트 `Map<String,Any?>` 에서 `path.to.var` dot 경로 해석.
**REFACTOR**: 경로 해석 헬퍼 분리, 정규식 상수화.
**검증**: `./gradlew :backend:modules:automation:test --tests '*TemplateRendererTest'`

### Task 5. AutomationRule 확장 — actions 리스트 + actorUserId

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/automation/src/main/kotlin/com/bts/automation/domain/AutomationRule.kt`, `backend/modules/automation/src/test/kotlin/com/bts/automation/domain/AutomationRuleActionsTest.kt`]
- depends-on: [3]

**RED**: 룰이 `actions: List<Action>`(빈 리스트 허용) + `actorUserId`(기본=create 시 createdBy) 보유. actions 추가/교체 시 version bump 테스트.
**GREEN**: `AutomationRule` 에 `actions`·`actorUserId` 필드 + `create` 팩토리 확장(actorUserId 기본=createdBy) + `updateActions`/`changeActor` 동작 메서드(불변, copy, version+1).
**REFACTOR**: KDoc, 기존 create 호출부 컴파일 유지.
**검증**: `./gradlew :backend:modules:automation:test --tests '*AutomationRuleActionsTest'`

### Task 6. automation_actions repository + rule actor_user_id 영속

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/automation/src/main/kotlin/com/bts/automation/adapter/AutomationActionRepository.kt`, `backend/modules/automation/src/main/kotlin/com/bts/automation/adapter/AutomationRuleRepository.kt`, `backend/modules/automation/src/test/kotlin/com/bts/automation/adapter/AutomationActionRepositoryTest.kt`]
- depends-on: [1, 3]

**RED**: ruleId 로 actions position 순 조회, 룰 저장 시 actions replace + actor_user_id 저장/조회 테스트(Testcontainers).
**GREEN**: `AutomationActionRepository`(JdbcTemplate, positional 바인딩, `?::jsonb`) + `AutomationRuleRepository` 에 actor_user_id + actions join 로드.
**REFACTOR**: SQL 상수화, 매핑 헬퍼.
**검증**: `./gradlew :backend:modules:automation:test --tests '*AutomationActionRepositoryTest'`

### Task 7. issue-tracking IssueMutationPort prod 어댑터 (위임 + OCC + dryRun)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/outbound/automation/AutomationIssueMutationAdapter.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/adapter/outbound/automation/AutomationIssueMutationAdapterTest.kt`]
- depends-on: [2]

**RED**: setField→`IssueApplicationService.updateIssue`, assign→`changeAssignee`, addComment→`CommentApplicationService.create(actor=ruleActor, authorId=ruleActor)` 위임 단언. OCC — 대상 이슈 현재 version 조회 후 적용(EC1). dryRun=true 면 미커밋·이벤트 미발행 단언.
**GREEN**: `@Profile("prod")` 어댑터(도메인 우회 금지 — 기존 application service 위임 [[patch-merge-domain-bypass]]). 현재 version fetch + 1회 충돌 재시도. dryRun 은 assertPermission+검증까지만.
**REFACTOR**: KDoc(actor VO 신뢰·async 안전), 공통 issueKey 파싱.
**검증**: `./gradlew :backend:modules:issue-tracking:test --tests '*AutomationIssueMutationAdapterTest'`

### Task 8. WebhookActionClient — SSRF 가드 + HTTP

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/automation/src/main/kotlin/com/bts/automation/adapter/WebhookActionClient.kt`, `backend/modules/automation/src/test/kotlin/com/bts/automation/adapter/WebhookActionClientTest.kt`]
- depends-on: [3]

**RED**: 사설/loopback/링크로컬 URL 거부(OutboundUrlValidator), 정상 URL POST, 타임아웃·본문크기 제한, 리다이렉트 미추종 테스트.
**GREEN**: `shared-kernel/http/OutboundUrlValidator`·`OutboundHttpClientConfig` 재사용. action_config 의 url/headers/body 사용.
**REFACTOR**: 타임아웃 상수, 실패 결과 매핑(best-effort).
**검증**: `./gradlew :backend:modules:automation:test --tests '*WebhookActionClientTest'`

### Task 9. ActionExecutor — 디스패치 + rule actor + 템플릿 + dryRun + 부분실패

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/automation/src/main/kotlin/com/bts/automation/application/ActionExecutor.kt`, `backend/modules/automation/src/test/kotlin/com/bts/automation/StubIssueMutationPort.kt`, `backend/modules/automation/src/test/kotlin/com/bts/automation/application/ActionExecutorTest.kt`]
- depends-on: [2, 3, 4, 8]

**RED**: 액션 리스트 순차 실행, actor=ruleActor 전달, AddComment 본문 템플릿 치환, dryRun 전파, 한 액션 실패해도 나머지 진행(SUCCESS/PARTIAL/FAILED 집계) 테스트. non-prod `StubIssueMutationPort`(consumer-owns-stub).
**GREEN**: `ActionExecutor`(IssueMutationPort·WebhookActionClient·TemplateRenderer 주입, non-null fail-closed [[crossbc-resolver-nullable-fail-open]]). 액션별 디스패치 + 상태 집계.
**REFACTOR**: when(action) exhaustive, 결과 VO.
**검증**: `./gradlew :backend:modules:automation:test --tests '*ActionExecutorTest'`

### Task 10. AutomationExecutionWorker — q_automation_execution 소비 + 체인 가드

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/automation/src/main/kotlin/com/bts/automation/worker/AutomationExecutionWorker.kt`, `backend/modules/automation/src/test/kotlin/com/bts/automation/worker/AutomationExecutionWorkerTest.kt`]
- depends-on: [6, 9]

**RED**: `q_automation_execution` read→룰+액션 로드→ActionExecutor 호출→archive(pgmq 생명주기 [[pgmq-consumer-message-lifecycle-p0]]). executionDepth>10 중단, 단일 root 실행 상한, 비활성/삭제 룰 스킵(EC7), 처리 실패 시 메시지 정책 테스트.
**GREEN**: `@Scheduled` 폴링(AutomationEventWorker 동형), depth/상한 가드, best-effort.
**REFACTOR**: 폴링 배치 크기 상수, 로깅.
**검증**: `./gradlew :backend:modules:automation:test --tests '*AutomationExecutionWorkerTest'`

### Task 11. 룰 CRUD payload 확장 — actions[] + actorUserId

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/automation/src/main/kotlin/com/bts/automation/adapter/web/AutomationRuleController.kt`, `backend/modules/automation/src/main/kotlin/com/bts/automation/application/AutomationRuleService.kt`, `backend/modules/automation/src/main/kotlin/com/bts/automation/adapter/web/dto/AutomationRuleRequests.kt`, `backend/modules/automation/src/main/kotlin/com/bts/automation/adapter/web/dto/AutomationRuleResponses.kt`, `backend/modules/automation/src/test/kotlin/com/bts/automation/web/AutomationRuleActionsControllerTest.kt`]
- depends-on: [5, 6]

**RED**: 룰 생성/수정 payload 에 `actions[]`·`actorUserId?` → 저장·응답 반영, actorUserId 미지정 시 createdBy 기본, MANAGE_AUTOMATION 가드 유지 테스트.
**GREEN**: DTO 확장 + Service 가 actions/actor 영속(T6 repo). bare DTO·XSRF·invalidate-only(FR-AT-01 선례).
**REFACTOR**: DTO 매핑 헬퍼.
**검증**: `./gradlew :backend:modules:automation:test --tests '*AutomationRuleActionsControllerTest'`

### Task 12. 통합 테스트 (Testcontainers) + BC 격리 ArchTest

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/automation/src/test/kotlin/com/bts/automation/ActionExecutionEndToEndIntegrationTest.kt`, `backend/modules/automation/src/test/kotlin/com/bts/automation/architecture/AutomationBcArchTest.kt`]
- depends-on: [7, 10, 11]

**RED**: enqueue(q_automation_execution)→worker→4종 액션 실행 e2e(mutate/webhook 관측), dry-run 미커밋, 체인 상한 발동, at-least-once 재처리 관측. ArchTest — automation→issue-tracking 직접 import 0.
**GREEN**: 통합 배선(@TestConfiguration, 새 포트 소비 full-boot [[new-crossbc-dep-openapi-mockbean-regression]]).
**REFACTOR**: 픽스처 헬퍼.
**검증**: `./gradlew :backend:modules:automation:test --tests '*EndToEnd*' --tests '*BcArchTest'`

### Task 13. 보안 검증 — rule actor fail-closed + SSRF + 위조 차단

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/automation/src/test/kotlin/com/bts/automation/security/ActionPermissionSecurityTest.kt`]
- depends-on: [7, 10, 11]

**RED**: rule actor 권한 부족 시 액션 reject(차단율 100%, 이슈 미변경), actor 위조 불가(VO 만 신뢰), CallWebhook SSRF 거부(사설/메타데이터 IP), rule actor 비활성 시 거부(EC11) 테스트.
**GREEN**: (구현은 T7/T9/T10 에서 완료) — 이 task 는 보안 경계 검증 전용. 누락 발견 시 해당 task 로 fix 위임.
**REFACTOR**: 보안 케이스 표 주석.
**검증**: `./gradlew :backend:modules:automation:test --tests '*ActionPermissionSecurityTest'`

## Plan 메타

- task 수: 13 (각 TDD 사이클)
- 예상 wave: 약 5 (T1~4 병렬 → T5~8 병렬 → T9·T11 → T10 → T12·T13). 단, automation 모듈 집중이라
  모듈 컴파일 직렬화([[bts-plan-wave-gradle-module-compile]])로 실제 병렬도는 제한적.
- 예상 시간: 직렬 기준 약 40분, wave 적용 시 약 20~25분
- TDD 강제: yes (test: 커밋이 feat: 보다 선행 — bts-impl 자동 검증)
- 크로스-BC touch: shared-kernel(신규 포트) + issue-tracking(prod 어댑터) — BC 격리 예외(IssueTransitionPort 선례)
- 추가 검증: ktlint, detekt(type-resolved), SchemaMigrationTest 카운트, BC ArchTest
- **범위 확정**: 백엔드 코어 D1~D5. UI(D6 액션 빌더)·E2E(D7)는 후속 PR

## 리뷰 결과 (← /bts-review-plan 채움)
