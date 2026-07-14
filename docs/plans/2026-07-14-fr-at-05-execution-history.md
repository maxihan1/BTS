# FR-AT-05 실행 이력 + 디버깅 (재실행, 단계별 추적)

> slug: fr-at-05-execution-history
> type: backend
> agent: backend-engineer
> primary_bc: automation
> 생성: 2026-07-14

## Brief

FR-AT-05 실행 이력 + 디버깅 (automation BC). 자동화 룰이 언제·어떤 트리거로·어떤
결과(SUCCESS/PARTIAL/FAILED)로 실행됐는지 이력을 남기고(`rule_executions`), 단계별
추적(trace)과 재실행(replay) API를 제공.

- 선행. §2.1~§2.3 (AT-01 트리거 · AT-02 액션 · AT-03 조건) — 완료.
- D단계. D1 도메인(RuleExecution) → D2 명세(trace context + 재실행) → D3 마이그레이션
  (`rule_executions`) → D4 백엔드(이력 저장 + `POST /api/v1/automation/executions/{id}/replay`)
  → D5 테스트 → D6 UI → D7 E2E.
- 선례. automation BC는 백엔드(D1~D5) 먼저 PR → D6/D7 UI 후속 PR로 분할
  (AT-01 #251→#254, AT-04 #268→#269).

## 도메인 정리

- **BC**: automation (9번째 모듈 `com.bts.automation`, JdbcTemplate·V300~V304)
- **영향 엔티티**: RuleExecution (신규 애그리거트 — 한 번의 룰 실행 이력)
- **새 용어**: **RuleExecution** (실행 이력 1건. 룰이 언제·어떤 트리거로·어떤 결과로 실행됐는지 +
  단계별 액션 outcome). SDD §8.6 `AutomationRunLog` 스키마의 실현.
- **권위 있는 스키마 (SDD §8.6)**:
  `rule_id · issue_id · trigger_event · condition_result · actions_executed[{action,result,error}]
   · started_at · finished_at · status(SUCCESS/PARTIAL/FAILED)`.
- **핵심 접지 — 데이터 구조는 이미 존재**: `ActionExecutor.execute()` 가 반환하는
  `ActionExecutionResult(status, outcomes[ActionOutcome(position,actionType,success,error)])` 가
  SDD `actions_executed` 와 1:1 대응. 현재 `AutomationExecutionWorker.runExecution()`(line 203)이
  이 반환값을 **버리고** pgmq 아카이브에만 남긴다 → FR-AT-05 는 이 반환값을 포착해 `rule_executions` 에
  영속화 + 조회/replay 를 얹는 작업.

### Maxi 확정 결정 (2026-07-14 AskUserQuestion)

1. **Replay 의미론 = 동기 실제 재실행**. `POST /executions/{id}/replay` 가 저장된 triggerEvent 로
   `ActionExecutor.execute(rule, storedTriggerEvent, dryRun=false)` 를 **즉시** 호출 → 실제 이슈 변경
   발생 + 새 `rule_executions` row 저장 + 새 trace 를 응답으로 즉시 반환. 정상 워커의 루프가드(b)/억제창은
   우회한다(관리자 명시 재실행이므로). replay 실행 주체 권한 = 룰의 `actorUserId`(정상 실행과 동일).
2. **기록 범위 = 실행 시도분만**. `ActionExecutor.execute` 가 호출된 실행만 기록 →
   SUCCESS/PARTIAL/FAILED + 조건불충족 SKIPPED 캡처. 억제창 suppressed·depth 초과·룰 없음/비활성·
   malformed 는 기록하지 않는다(워커가 ActionExecutor 호출 전에 archive, 운영 노이즈 회피).
3. **PR 분할 = 백엔드 먼저 (D1~D5)**. 이번 PR = 도메인·명세·마이그레이션·이력저장·replay API·백엔드 테스트.
   D6/D7 UI 는 후속 PR (automation BC 선례 AT-01 #251→#254, AT-04 #268→#269).

### 권한/경로 접지 (기존 관례)

- **권한**: 기존 룰 엔드포인트와 동일하게 `MANAGE_AUTOMATION` 가드. 인가 순서 = actor 추출(401) →
  권한 판정(403) → 리소스 조회(404) ([[auth-extraction-before-resource-lookup]]).
  `AutomationActorExtractor`(SecurityContext UUID 추출) 재사용.
- **경로**: 실행 detail/replay 는 product doc 대로 실행 UUID 기반(`/api/v1/automation/executions/{id}`).
  execution → rule → projectKey 역도출해 `MANAGE_AUTOMATION` 가드 적용. 이력 목록은 기존 프로젝트 스코프
  관례를 따라 `/api/v1/projects/{projectKey}/automation/rules/{ruleId}/executions` (SDD "이 이슈에 영향을
  준 자동화" 대응 위해 issueKey 필터 옵션) — 최종 엔드포인트 shape 는 bts-spec 에서 확정.

### 기존 결정 충돌 / 정정 후보

- 충돌: 없음.
- **정정 후보**: `AutomationExecutionWorker` KDoc(line 67)이 "전체 실행 체인 영속 추적/감사"를
  **FR-AT-04**로 위임한다고 적었으나, FR-AT-04 는 실제로 "규칙 충돌 정적 분석"이 됐고 실행 로그/감사는
  **FR-AT-05**(본 작업)다. 이 stale 참조를 본 PR 에서 정정.
- 관련 ADR: [docs/decisions/2026-07-14-fr-at-05-execution-history.md](../decisions/2026-07-14-fr-at-05-execution-history.md) (생성됨)

## 스펙

전체 스펙. [docs/specs/2026-07-14-fr-at-05-execution-history.md](../specs/2026-07-14-fr-at-05-execution-history.md)

핵심 시나리오 요약.
- 워커가 `ActionExecutor.execute()` 반환값(status + outcomes)을 포착해 `rule_executions`(V305)에 영속화. 기록 범위 = 실행 시도분(SUCCESS/PARTIAL/FAILED/SKIPPED), 억제·malformed 미기록.
- 조회 3종 — 룰별 이력 목록(projectKey 가드, 소프트삭제 룰도 조회, issueKey 필터, keyset) · 단건 trace · replay.
- `POST /executions/{id}/replay` = 저장된 triggerEvent로 동기 실제 재실행(dryRun=false) → 새 row(replayed_from) + trace 즉시 반환.
- 권한 = MANAGE_AUTOMATION(기존 룰 컨트롤러 관례). 신규 cross-BC 포트/큐 없음.
- 워커 KDoc stale "FR-AT-04" 참조 정정.

## Brainstorming Check

✅ 통과 (1회 iteration). gap 3건 발견 후 스펙 보강.
- G1 소프트삭제 룰 이력 접근(NFR-4 정합) → FR-3 projectKey 가드 + 직접 조회로 수정.
- G2 keyset 커서 `(started_at, id)` 복합으로 명시.
- G3 retention/TTL 무제한 증가 → 후속 위임 명시.

## Plan

> automation 모듈은 JdbcTemplate(jOOQ 아님) — init_codegen.sql 미러 없음. 모든 task 동일 Gradle
> 모듈이라 wave는 컴파일 직렬화됨([[bts-plan-wave-gradle-module-compile]]). 실행 주체 권한은 기존
> `AutomationPermissionResolver.hasManageAutomation(actorId, projectKey)` 재사용(신규 resolver 없음).

### Task 1. V305 `rule_executions` 마이그레이션 + SchemaMigrationTest

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/automation/src/main/resources/db/migration/automation/V305__rule_executions.sql`, `backend/modules/automation/src/test/kotlin/com/bts/automation/SchemaMigrationTest.kt`]
- depends-on: []

**RED**: `SchemaMigrationTest` 에 `rule_executions` 테이블 존재 + 컬럼(id·rule_id·project_key·trigger_type·trigger_event·issue_key·status·outcomes·replayed_from·started_at·finished_at·created_at) 단언 추가. 테이블 부재로 실패.

**GREEN**: `V305__rule_executions.sql` — 스펙 §데이터 모델의 DDL 그대로. 하드 FK 없음(NFR-4). 인덱스 2종(`(rule_id, started_at DESC)`, `(project_key, issue_key, started_at DESC)`). L1 한글 주석.

**REFACTOR**: 컬럼 주석(COMMENT ON) 정리, 마이그레이션 헤더 주석.

**검증**: `./gradlew :backend:modules:automation:test --tests "*SchemaMigrationTest"`. V번호 머지 직전 재확인([[migration-vnumber-concurrent-branch-collision]]).

### Task 2. RuleExecution 레코드 + RuleExecutionRepository (Testcontainers 통합)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/automation/src/main/kotlin/com/bts/automation/application/RuleExecution.kt`, `backend/modules/automation/src/main/kotlin/com/bts/automation/adapter/RuleExecutionRepository.kt`, `backend/modules/automation/src/test/kotlin/com/bts/automation/adapter/RuleExecutionRepositoryTest.kt`]
- depends-on: [1]

**RED**: `RuleExecutionRepositoryTest`(AutomationTestcontainersBase 상속) —
- `save` → `findById` 라운드트립: `trigger_event`/`outcomes` JSONB 직렬화·역직렬화 정합(`ActionOutcome` 리스트).
- `findByRule(ruleId, issueKey=null, limit, before=null)` started_at DESC 정렬.
- `issueKey` 필터: 특정 이슈 실행만.
- keyset `before`(started_at,id) 페이지네이션.
- **소프트삭제 룰 이력도 반환**(rule join 없음 — rule_id/project_key 직접 조회, NFR-4).
- 실패: `RuleExecution`/`RuleExecutionRepository` 클래스 없음.

**GREEN**: `RuleExecution` 레코드(id·ruleId·projectKey·triggerType·triggerEvent:JsonNode·issueKey·status:`ActionExecutionStatus`·outcomes:`List<ActionOutcome>`·replayedFrom·startedAt·finishedAt). `RuleExecutionRepository`(`NamedParameterJdbcTemplate`, 기존 automation repo 관례). JSONB는 `?::jsonb`/Jackson 매핑. `@Repository`.

**REFACTOR**: SQL 상수 companion 분리, RowMapper object, KDoc(BC 격리 — issue-tracking 타입 미import 명시).

**검증**: `./gradlew :backend:modules:automation:test --tests "*RuleExecutionRepositoryTest"`.

### Task 3. 워커가 실행 결과 포착 + 영속화 (fail-safe)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/automation/src/main/kotlin/com/bts/automation/worker/AutomationExecutionWorker.kt`, `backend/modules/automation/src/test/kotlin/com/bts/automation/worker/AutomationExecutionWorkerTest.kt`]
- depends-on: [2]

**RED**: `AutomationExecutionWorkerTest` 확장(생성자에 `RuleExecutionRepository` 추가 — helper `worker(...)` 갱신, [[plan-files-constructor-injection-existing-tests]]) —
- 실행 성공 후 `rule_executions` row 1건(status=SUCCESS, outcomes 정합, trigger_type=fire-time, project_key=rule.projectKey, issue_key, started<finished).
- SKIPPED(조건불충족) 기록됨(outcomes=[]).
- **억제창/룰없음/malformed는 row 미생성**(ActionExecutor 호출 전 archive 분기).
- **이력 저장 실패해도 archive 정상 진행**(fail-safe, save가 예외 던져도 archive 카운트 유지).
- 실패: 현재 워커는 반환값을 버림 → row 0.

**GREEN**: `runExecution` 에서 `started=clock.instant()` → `result=actionExecutor.execute(...)` → `finished=clock.instant()` → `ruleExecutionRepository.save(RuleExecution(...))` 를 try/catch 격리(저장 실패 시 warn 로그 후 archive 계속). `parsePayload` 에 `triggerType` 파싱 추가(enqueuer가 이미 실음, 실패 시 `rule.triggerType` fallback → `ExecutionPayload.triggerType`).

**REFACTOR**: 저장 매핑을 private helper 로 분리(detekt `TooManyFunctions` 대비 top-level 가능). KDoc 갱신.

**검증**: `./gradlew :backend:modules:automation:test --tests "*AutomationExecutionWorkerTest"`.

### Task 4. 이력 조회 서비스 + 목록/단건 API (read-only)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/automation/src/main/kotlin/com/bts/automation/application/RuleExecutionService.kt`, `backend/modules/automation/src/main/kotlin/com/bts/automation/adapter/web/AutomationExecutionController.kt`, `backend/modules/automation/src/main/kotlin/com/bts/automation/adapter/web/dto/RuleExecutionResponses.kt`, `backend/modules/automation/src/test/kotlin/com/bts/automation/application/RuleExecutionServiceTest.kt`, `backend/modules/automation/src/test/kotlin/com/bts/automation/web/AutomationExecutionControllerTest.kt`]
- depends-on: [2]

**RED**:
- `RuleExecutionServiceTest`(stubs — `StubAutomationPermissionResolver` 재사용): `listByRule(actorId, projectKey, ruleId, issueKey?, limit, before?)` 권한 없으면 `AutomationForbiddenException`, 있으면 repo 위임. `getById(actorId, executionId)` 로드→project_key로 권한→반환, 없음/타프로젝트 null(→404).
- `AutomationExecutionControllerTest`(MockMvc, `AutomationTestSecurityConfig` 재사용): `GET .../rules/{ruleId}/executions` 200 목록·issueKey 필터·limit clamp(≤200)·미인증 401·권한없음 403. `GET /automation/executions/{id}` 200 trace·없음 404.
- 실패: 클래스 없음.

**GREEN**:
- `RuleExecutionService`(`@Service`, `@Transactional(readOnly=true)` where 적절): repository + `AutomationPermissionResolver` 주입. 목록은 **projectKey 가드만**(룰 존재 불요, NFR-4). 단건은 project_key 역도출 후 가드.
- `AutomationExecutionController`(`@RestController`): 자체 `AutomationExecutionActorExtractor`(private object, `AutomationRuleController` 선례 동형 — 다른 파일 private 재사용 불가). 엔드포인트 2종(메서드레벨 전체 경로). 응답 요약/상세 DTO(`RuleExecutionResponses.kt`, outcomes 배열 포함).
- 스코프 예외 핸들러 `@RestControllerAdvice(assignableTypes=[AutomationExecutionController::class])`([[domain-exception-http-handler-basepackage-scope]]): 403/404/401/500 + `AUTOMATION_EXECUTION_NOT_FOUND`.

**REFACTOR**: DTO `from()` 팩토리, 에러코드 상수 companion.

**검증**: `./gradlew :backend:modules:automation:test --tests "*RuleExecutionServiceTest" --tests "*AutomationExecutionControllerTest"`.

### Task 5. Replay 서비스 + POST API (동기 실제 재실행)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/automation/src/main/kotlin/com/bts/automation/application/RuleExecutionService.kt`, `backend/modules/automation/src/main/kotlin/com/bts/automation/adapter/web/AutomationExecutionController.kt`, `backend/modules/automation/src/test/kotlin/com/bts/automation/application/RuleExecutionServiceTest.kt`, `backend/modules/automation/src/test/kotlin/com/bts/automation/web/AutomationExecutionControllerTest.kt`, `backend/modules/automation/src/test/kotlin/com/bts/automation/integration/RuleExecutionReplayIntegrationTest.kt`]
- depends-on: [4]

**RED**:
- `RuleExecutionServiceTest`: `replay(actorId, executionId)` — 로드→가드→`ruleRepository.findById(ruleId)` null(소프트삭제)이면 `AutomationRuleUnavailableException`(→409). 정상이면 `actionExecutor.execute(rule, storedTriggerEvent, dryRun=false)` **정확히 dryRun=false 호출**(mock 검증) → 새 `RuleExecution` save(replayedFrom=원본id, triggerType=원본 record). 반환=새 실행.
- `AutomationExecutionControllerTest`: `POST /automation/executions/{id}/replay` 200 새 trace·룰소프트삭제 409·미인증 401·권한없음 403.
- `RuleExecutionReplayIntegrationTest`(Testcontainers, `StubIssueMutationPort` 관측): replay가 실제 mutation 위임 + 새 row(replayed_from) 검증.
- 실패: `replay`/엔드포인트 없음.

**GREEN**: `RuleExecutionService.replay` + 컨트롤러 POST. `AutomationRuleUnavailableException` + 핸들러 409 매핑(`AUTOMATION_RULE_UNAVAILABLE`).

**REFACTOR**: replay 저장 매핑을 service 내 private helper 로.

**검증**: `./gradlew :backend:modules:automation:test --tests "*RuleExecutionServiceTest" --tests "*AutomationExecutionControllerTest" --tests "*RuleExecutionReplayIntegrationTest"`.

### Task 6. 워커 KDoc 정정 + 문서 전수 동기화

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/automation/src/main/kotlin/com/bts/automation/worker/AutomationExecutionWorker.kt`, `docs/sdd/08-automation-engine.md`, `docs/plan/product/automation.md`, `docs/plan/fr-index.md`]
- depends-on: [3, 5]

**RED**(문서 task — 검증은 `verify-master-plan.sh`): 없음(문서). 대신 완료 게이트 = verify 통과.

**GREEN**:
- 워커 KDoc(line 67 부근) "견고한 사이클 검출/감사는 FR-AT-04에 위임" → 정정. 실행 로그/감사는 FR-AT-05(본 작업), 사이클 정적분석은 FR-AT-04로 분리 표기.
- SDD §8.6 `AutomationRunLog`: `status` 에 SKIPPED 추가, `issue_id`↔`issue_key` 표기 정합.
- product `docs/plan/product/automation.md` §2.5 FR-AT-05 D1~D5 체크박스 `[x]`(D6/D7은 후속 PR이라 `[ ]` 유지).
- fr-index: FR-AT-05 상태 반영(필요 시 note).

**REFACTOR**: 없음.

**검증**: `bash scripts/verify-master-plan.sh` 통과 + `./gradlew :backend:modules:automation:compileKotlin`(KDoc 변경 컴파일 확인).

## Plan 메타

- task 수: 6
- 예상 wave: 약 5 (T1 → T2 → {T3, T4} → T5 → T6). 동일 모듈이라 컴파일 직렬화, 실질 순차에 가까움.
- 예상 시간: 약 25~30분(직렬 기준)
- TDD 강제: yes (T6 문서 제외 — verify 게이트)
- 추가 검증: detekt/ktlint(automation 모듈), `:backend:modules:app:test`(prod 조립 — 신규 @Component 추가로 full-boot 재검증 필요, [[prod-assembly-boot-verification-required]] · [[new-crossbc-dep-openapi-mockbean-regression]])
- 신규 cross-BC 포트/큐 없음(기존 `IssueMutationPort` 재사용) → cross-BC 조립 회귀 위험 낮음. 단 신규 `@Repository`/`@Service`/`@RestController` 3종은 automation test-boot·app 조립 스캔 대상.

## 리뷰 결과 (← /bts-review-plan 채움)
