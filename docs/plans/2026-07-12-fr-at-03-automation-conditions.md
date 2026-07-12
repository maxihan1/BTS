# FR-AT-03 조건 분기 (if-else, 표현식)

> slug: fr-at-03-automation-conditions
> type: backend
> agent: backend-engineer
> primary_bc: automation
> 생성: 2026-07-12

## Brief

FR-AT-03 — automation BC 조건 평가 엔진. 자동화 룰이 트리거 발화 후 액션 실행 전에
조건(Condition/Expression)을 평가하여 분기(if-else)한다.

원문 요청: "fr-at-03 진행해줘"

classify 결과:
- type: backend
- agent: backend-engineer
- primary_bc: automation

product doc §2.3 스코프:
- D1. 도메인 — Condition + Expression
- D2. 명세 — 표현식 문법
- D3. 데이터 모델 — automation_conditions(expression)
- D4. 백엔드 — 표현식 평가 엔진 (Spring SpEL 또는 자체)
- D5. 백엔드 테스트 — 표현식 케이스 50개
- D6. 프론트 UI — 조건 빌더 (후속 PR 예상)
- D7. E2E (후속 PR 예상)

선행: FR-AT-01(트리거) 완료, FR-AT-02(액션) 완료.
분할 방침(선례): 백엔드 D1~D5 이번 PR, 프론트 D6/D7 후속 PR. (spec/plan에서 확정)

## 도메인 정리

- **BC**: automation (TCA — Trigger-Condition-Action 엔진의 빠진 중간 조각 = Condition)
- **선행 완료**: FR-AT-01(트리거, #251/#254), FR-AT-02(액션, #256/#260)
- **영향 엔티티**:
  - `AutomationRule` (aggregate) — `conditions: List<Condition>` 필드 신규 추가 (현재 예약 슬롯 없음. FR-AT-02가 `actions`를 add한 방식과 동일)
  - `Condition` (신규 도메인) — `Action`의 `sealed class` + `fromJson(type, configJson)` 팩토리 패턴 미러링
  - `automation_conditions` 테이블 (신규, V304 — V302 `automation_actions` 스키마 미러링. 최신 마이그레이션 V303)
- **실행 hook**: `ActionExecutor.execute` — 컨텍스트 구성(`buildContext`) 직후·액션 dispatch 직전 사이에 조건 게이트 삽입. 조건 불충족 시 액션 0개 실행하고 no-op 조기 반환.

### Maxi 확정 결정 (2026-07-12 게이트, AskUserQuestion 3건)

1. **표현식 엔진 = 구조화 조건 모델** (JSONLogic류 데이터 트리 / `{field, operator, value}` 절, BTS 자체 코드로 평가).
   - 근거: 자동화 조건은 프로젝트 관리자가 **런타임 API**로 입력. project-workflow의 샌드박스 SpEL은 "런타임 API 유입 표현식 금지"를 문서상 전제로 하므로 부적합. 데이터 트리는 **코드 실행이 구조적으로 불가** → API 유입이어도 RCE 표면 0. D6 조건 빌더 UI와 직결. 50케이스 테스트 용이. SDD §8.3 첫 번째 안.
   - 기각: 샌드박스 SpEL 재사용 (선례 일관성 이점보다 API 유입 보안 전제 충돌이 큼).
2. **이슈 필드 값 읽기 = 신규 cross-BC 읽기 포트** (`IssueSnapshotPort`, shared-kernel).
   - 근거: 트리거 이벤트(`IssueUpdated` 등)는 `{issueKey, 변경된 필드 이름}`만 담고 status/priority/assignee/labels **값이 없음**. SDD 조건 예시는 전부 값 비교라 새 읽기 통로 필수. FR-AT-02 `IssueMutationPort`(쓰기 포트)가 확립한 automation→shared-kernel←issue-tracking 방향·fail-closed·actor 신뢰 모델 미러. automation BC 격리 유지. 덤: FR-AT-02 `{{ issue.status }}` 템플릿도 현재 값이 비어 있어 이 포트의 수혜.
   - 기각: 이벤트 payload enrich (producer 변경 폭 큼 + SCHEDULED/WEBHOOK 트리거는 스냅샷 원천 없음).
3. **이번 PR 범위 = 백엔드 D1~D5** (도메인·명세·데이터모델·평가엔진·테스트 50케이스). 프론트 D6(조건 빌더 UI)·D7(E2E)는 후속 PR. FR-AT-01/02 분할 선례 동일.

- **기존 결정 충돌**: 없음. SDD §8.3의 2안(JSONLogic vs SpEL) 중 JSONLogic 계열 확정으로 정합.
- **glossary 갱신 후보**: "조건 (Condition)" — 자동화 룰에서 트리거 발화 후 액션 실행 전 평가하는 분기 판정. (Maxi 승인 후 추가)
- **관련 ADR**: `docs/decisions/2026-07-12-fr-at-03-automation-conditions.md` (bts-spec 단계에서 전체 설계와 함께 작성 예정)

## 스펙

전체 스펙. [docs/specs/2026-07-12-fr-at-03-automation-conditions.md](../specs/2026-07-12-fr-at-03-automation-conditions.md)

핵심 3줄 요약.
- 조건 = 구조화 데이터 트리(JSONLogic 호환 부분집합: and/or/not · ==/!=/></>=/</<= · in · !/!! · var). 코드 실행 불가.
- `ActionExecutor.execute` 내부에 조건 게이트(액션 로드 후·dispatch 전). 불충족→SKIPPED·액션 0건. dry-run 동일.
- 이슈 필드 값은 신규 `IssueSnapshotPort`(shared-kernel, issue-tracking prod 어댑터)로 읽음. V304 automation_conditions.

## Brainstorming Check

✅ 통과 (1회, 적대적 self-review). gap 8건 = 전부 구현 레벨 리스크(아래 §리뷰 결과/리스크로 이관).

## 리스크 / 구현 주의 (Brainstorming 발견 8건 + prod 조립)

1. **ActionExecutor 생성자 확장** — 신규 의존(IssueSnapshotPort·ConditionEvaluator·AutomationConditionRepository)
   주입 시 기존 `ActionExecutorTest` 전수 갱신 ([[plan-files-constructor-injection-existing-tests]]).
2. **full-boot NoSuchBean** — ActionExecutor가 IssueSnapshotPort 요구 → automation test-boot 슬라이스에
   `StubIssueSnapshotPort` 동반(`StubIssueMutationPort` 선례) ([[new-crossbc-dep-openapi-mockbean-regression]]).
3. **`ActionExecutionStatus.SKIPPED` 추가** — 모듈 내 exhaustive `when` 전수 grep 갱신.
4. **V304 충돌/체크섬** — 머지 직전 최신 V번호 재확인 ([[migration-vnumber-concurrent-branch-collision]]),
   `:modules:app:test`는 영속 5433 DB라 적용된 마이그레이션 편집 금지 ([[app-test-persistent-db-migration-checksum-trap]]).
   `SchemaMigrationTest` V304 단언 블록 추가.
5. **prod 조립 부팅 재검증** — issue-tracking `IssueSnapshotPort` `@Profile("prod")` 어댑터를 `:modules:app`에
   배선 + 머지 전 rebase + `:modules:app:test` 부팅 확인 ([[prod-assembly-boot-verification-required]]).
   fail-closed 회귀 가드(어댑터 부재→NoSuchBean) ([[crossbc-resolver-nullable-fail-open]]).
6. **priority 타입** — 스냅샷 priority 숫자(1-5, FR-AT-02) vs 이름. `IssueSnapshot` 계약에서 D3/D4 확정.
7. **조건 replace 트랜잭션성** — 룰 수정과 조건 upsert 동일 트랜잭션.
8. **보안 checkpoint** — 스냅샷 포트 가시성 필터 부재. 안전 근거(PROJECT_ADMIN 생성·프로젝트 스코프 트리거·
   admin 자기 프로젝트 이슈 열람 가능) 성립하나 codereview 시 security 관점 확인.
9. **동형 복제 권한/cross-cutting** — Condition/repo/포트를 Action 패턴 복제 시 권한 가드·트랜잭션 전수 대조
   ([[isomorphic-clone-permission-guard-gap]]).

## Plan (← /bts-plan 채움)

## Plan

> BC=automation(9번째 모듈, test-boot·JdbcTemplate). 파일 경로는 repo 루트 기준.
> 도메인/평가기/repo/게이트는 automation, 포트는 shared-kernel, 어댑터는 issue-tracking(cross-BC 예외, IssueMutationPort 선례).

### Task 1. Condition sealed 도메인 + fromJson/toJson + 형식 검증

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/automation/src/main/kotlin/com/bts/automation/domain/Condition.kt`, `backend/modules/automation/src/test/kotlin/com/bts/automation/domain/ConditionTest.kt`]
- depends-on: []

**RED**: `ConditionTest` — (a) `Condition.fromJson` 이 JSONLogic 부분집합(and/or/not·==/!=/>/>=/</<=·in·!/!!·var)을 sealed 트리로 파싱, (b) `toJson` round-trip 동일, (c) 미지원 연산자·화이트리스트 밖 필드·깊이>10·노드>100 → `InvalidConditionExpressionException`. 클래스 없음으로 실패.

**GREEN**: `Condition.kt` — `sealed class Condition { And/Or/Not/Comparison }` + `ComparisonOperator` enum + `fromJson(json: String): Condition`/`toJson(): String`(Jackson) + `validate`(연산자·필드 화이트리스트·깊이/노드 상한). 필드 화이트리스트 상수(스펙 FR-AT-03-2).

**REFACTOR**: 파싱/검증 헬퍼 분리, KDoc(연산자 표·화이트리스트), 예외 메시지 일반화(민감정보 누출 금지).

**검증**: `./gradlew :modules:automation:test --tests '*ConditionTest'`

### Task 2. ConditionEvaluator 순수 평가기 + 50 케이스

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/automation/src/main/kotlin/com/bts/automation/application/ConditionEvaluator.kt`, `backend/modules/automation/src/main/kotlin/com/bts/automation/application/ConditionContext.kt`, `backend/modules/automation/src/test/kotlin/com/bts/automation/application/ConditionEvaluatorTest.kt`]
- depends-on: [1]

**RED**: `ConditionEvaluatorTest` — 스펙 §테스트 50 케이스(비교12·멤버십8·존재6·조합8·null6·타입불일치5·항등2·검증3). fixture `ConditionContext`(합성 스냅샷 맵). `evaluate` 없음으로 실패.

**GREEN**: `ConditionEvaluator.evaluate(condition, ctx): Boolean` 재귀 트리 워크(IO 없음). `ConditionContext` = 화이트리스트 필드 읽기 뷰. 서수 비교 양쪽 숫자만(아니면 false), ==/!= 스칼라 deep equal, in 배열/부분문자열, !/!! truthy, 누락=null, 빈 and=참·빈 or=거짓. 평가 예외는 던지지 않고 상위(게이트)가 fail-safe 처리.

**REFACTOR**: 연산자 dispatch 정리, KDoc(각 연산자 의미·fail-safe 계약).

**검증**: `./gradlew :modules:automation:test --tests '*ConditionEvaluatorTest'` (50 케이스 그린)

### Task 3. shared-kernel IssueSnapshotPort + IssueSnapshot VO

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/issue/IssueSnapshotPort.kt`, `backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/issue/IssueSnapshot.kt`]
- depends-on: []

**RED**: shared-kernel은 순수 계약(구현 없음) — 컴파일 계약 테스트 또는 VO 필드 단언 최소 테스트(`IssueSnapshotContractTest`). 인터페이스/VO 없음으로 실패.

**GREEN**: `interface IssueSnapshotPort { fun fetch(actorUserId: UUID, issueKey: String): IssueSnapshot? }`
(**actor 필수 — BLOCKER 해소**) + `data class IssueSnapshot(key, projectKey, type: String?(이름), status: String(stateKey),
priority: Int?(1-5), assigneeId: UUID?, reporterId: UUID?, labels: List<String>, summary: String)`. Jackson 비의존
순수 계약. KDoc: 방향(`automation→shared-kernel←issue-tracking`)·fail-closed·**actor 가시성 강제**(어댑터가 룰
actor로 기존 가시성 강제 read 재사용, actor가 못 보는 이슈→null, §12.4 관리자 우회 없음 준수)·표현 명시
(type=이름, status=워크플로우 stateKey, priority=Int 1-5) — IssueMutationCommands actor 신뢰 모델 선례.

**REFACTOR**: KDoc에 IssueMutationPort 선례 링크.

**검증**: `./gradlew :modules:shared-kernel:test`

### Task 4. V304 automation_conditions + AutomationConditionRepository

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/automation/src/main/resources/db/migration/automation/V304__automation_conditions.sql`, `backend/modules/automation/src/main/kotlin/com/bts/automation/adapter/AutomationConditionRepository.kt`, `backend/modules/automation/src/test/kotlin/com/bts/automation/adapter/AutomationConditionRepositoryTest.kt`, `backend/modules/automation/src/test/kotlin/com/bts/automation/SchemaMigrationTest.kt`]
- depends-on: [1]

**RED**: `AutomationConditionRepositoryTest`(Testcontainers) — replace(upsert)/findByRuleId/delete(null) round-trip. `SchemaMigrationTest` V304 컬럼/제약 단언. 테이블/repo 없음으로 실패.

**GREEN**: `V304__automation_conditions.sql`(rule_id PK/FK ON DELETE CASCADE·expression JSONB NOT NULL·타임스탬프). `AutomationConditionRepository`(JdbcTemplate, `findByRuleId → Condition?`·`replace(ruleId, Condition?)` upsert/delete, Condition.toJson/fromJson 사용). 머지 직전 최신 V번호 재확인 주석.

**REFACTOR**: SQL 상수화, KDoc.

**검증**: `./gradlew :modules:automation:test --tests '*AutomationConditionRepositoryTest' --tests '*SchemaMigrationTest'`

### Task 5. AutomationRule.condition 필드 + updateCondition

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/automation/src/main/kotlin/com/bts/automation/domain/AutomationRule.kt`, `backend/modules/automation/src/test/kotlin/com/bts/automation/domain/AutomationRuleTest.kt`]
- depends-on: [1]

**RED**: `AutomationRuleTest` — `create`에 condition(기본 null) 파라미터, `updateCondition(condition?)` 불변 copy(version 규칙은 액션 선례 따름). 필드 없음으로 실패.

**GREEN**: `AutomationRule`에 `condition: Condition? = null` 필드 + `create` 파라미터 + `updateCondition` 동작 메서드(`updateActions` 대칭).

**REFACTOR**: KDoc "conditions는 FR-AT-03에서 추가"(actions 선례 문구 동형).

**검증**: `./gradlew :modules:automation:test --tests '*AutomationRuleTest'`

### Task 6. issue-tracking IssueSnapshotPort prod 어댑터

**메타**.
- agent: `backend-engineer` (cross-BC 읽기, codereview 시 security 관점 확인)
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/automation/AutomationIssueSnapshotAdapter.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/automation/AutomationIssueSnapshotAdapterTest.kt`]
- depends-on: [3]

**RED**: `AutomationIssueSnapshotAdapterTest`(Testcontainers, issue-tracking) — (a) 실 이슈→IssueSnapshot 매핑
(type=이름·status=stateKey·priority=Int·assignee/reporter/labels/summary), (b) 이슈 부재→null, (c) **actor가
못 보는 보안수준 제한 이슈→null**(§12.4 가시성 강제 검증), (d) **관통 통합 테스트: 실이슈→fetch→ConditionContext
→ConditionEvaluator.evaluate가 스펙 S1(`type=="Bug" && priority>=3`) 실제 매치**(C1 해소 — 이름/키 불일치가
green 배포되는 것 차단). 어댑터 없음으로 실패.

**GREEN**: `@Profile("prod") @Component AutomationIssueSnapshotAdapter : IssueSnapshotPort` — `fetch(actorUserId, issueKey)`
가 issue-tracking **기존 actor 가시성 강제 read 경로 재사용**(도메인/가시성 우회 금지, `IssueRepository` 가시성
술어 경유). actor가 못 보면 null. 매핑: typeId→**타입 이름**, currentStateKey→**status(stateKey)**, priority→Int(1-5),
labels 포함. IssueSnapshot 계약(Task 3)대로.

**REFACTOR**: 매핑 헬퍼 정리, KDoc(AutomationIssueMutationAdapter 선례 링크).

**검증**: `./gradlew :modules:issue-tracking:test --tests '*AutomationIssueSnapshotAdapterTest'`

### Task 7. ActionExecutor 조건 게이트 + SKIPPED 상태 + StubIssueSnapshotPort

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/automation/src/main/kotlin/com/bts/automation/application/ActionExecutor.kt`, `backend/modules/automation/src/test/kotlin/com/bts/automation/testfixture/StubIssueSnapshotPort.kt`, `backend/modules/automation/src/test/kotlin/com/bts/automation/application/ActionExecutorTest.kt`, `backend/modules/automation/src/test/kotlin/com/bts/automation/application/ActionPermissionSecurityTest.kt`, `backend/modules/automation/src/test/kotlin/com/bts/automation/application/ActionExecutorFailClosedTest.kt`, `backend/modules/automation/src/test/kotlin/com/bts/automation/AutomationTestcontainersBase.kt`, `backend/modules/automation/src/test/kotlin/com/bts/automation/ModuleBootTest.kt`, `backend/modules/automation/src/test/kotlin/com/bts/automation/web/AutomationRuleControllerTest.kt`, `backend/modules/automation/src/test/kotlin/com/bts/automation/web/AutomationRuleActionsControllerTest.kt`, `backend/modules/automation/src/test/kotlin/com/bts/automation/web/AutomationWebhookControllerTest.kt`, `backend/modules/automation/src/test/kotlin/com/bts/automation/application/ActionExecutionEndToEndIntegrationTest.kt`, `backend/modules/automation/src/test/kotlin/com/bts/automation/worker/AutomationExecutionWorkerTest.kt`]
- depends-on: [1, 2, 3, 4, 5]

> **C2 해소**: ActionExecutor 생성자 3개 신규 의존으로 (1) 직접생성 3곳(ActionExecutorTest·ActionPermissionSecurityTest·ActionExecutorFailClosedTest) 컴파일 갱신, (2) 풀부팅 슬라이스 전수에 `StubIssueSnapshotPort` 빈 등록(위 files의 boot 테스트 + 공유 base). implementer가 `IssueMutationPort`/`StubIssueMutationPort` 등록 지점을 grep해 동일 지점마다 IssueSnapshotPort 짝 등록.

**RED**: `ActionExecutorTest`(기존 갱신) — (a) condition null → 기존대로 액션 실행, (b) 충족 → 액션 실행, (c) 불충족 → `SKIPPED`·액션 0건, (d) issueKey 없음/스냅샷 null(actor 미가시 포함)/평가예외 → fail-safe SKIPPED, (e) dry-run 경로도 게이트 동일(execute(dryRun=true) 단위 검증). `StubIssueSnapshotPort`(consumer-owns-stub, 시드 가능).

**GREEN**: `ActionExecutor` 생성자에 `IssueSnapshotPort`(non-null, fail-closed) + `ConditionEvaluator` + `AutomationConditionRepository` 주입. `execute` 액션 로드 후·dispatch 전 조건 게이트: `condition = conditionRepo.findByRuleId(rule.id)`; null→통과; 있으면 issueKey 추출→`snapshotPort.fetch(rule.actorUserId, issueKey)`(**룰 actor 전달 — 가시성 강제**)→ConditionContext→evaluate; false/null/예외→`ActionExecutionResult(SKIPPED, emptyList())`. `ActionExecutionStatus.SKIPPED` 추가(모듈 내 exhaustive when 없음 확인). **C3 KDoc**: 조건은 최신 DB 스냅샷 기준, 템플릿 `{{issue.*}}`는 이벤트 payload 기준(FR-AT-02 현 한계, 본 PR은 템플릿 enrich 안 함) 명시.

**REFACTOR**: 게이트 로직 private 메서드 추출, KDoc(fail-safe·dry-run 일관·SKIPPED 의미).

**검증**: `./gradlew :modules:automation:test --tests '*ActionExecutorTest'` + 모듈 전체 컴파일(SKIPPED when 갱신 확인)

### Task 8. 룰 CRUD DTO condition 필드 + 형식 검증 400

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/automation/src/main/kotlin/com/bts/automation/application/AutomationRuleService.kt`, `backend/modules/automation/src/main/kotlin/com/bts/automation/web/`(룰 controller/DTO — implementer가 정확 파일명 grep), `backend/modules/automation/src/test/kotlin/com/bts/automation/web/`(controller 통합 테스트)]
- depends-on: [1, 4, 5, 7]   # T7이 controller 부팅 슬라이스에 StubIssueSnapshotPort 등록(C2), T8 통합테스트가 ActionExecutor 부팅 의존

**RED**: 룰 controller 통합 테스트 — (a) 생성/수정 payload에 `condition` 포함→저장·응답 반영, (b) 무효 표현식→`400 INVALID_CONDITION_EXPRESSION`, (c) MANAGE_AUTOMATION 가드 유지. condition 필드 없음으로 실패.

**GREEN**: 룰 생성/수정 요청·응답 DTO에 `condition`(nullable) 추가. `AutomationRuleService`가 룰 저장 트랜잭션 내에서 `AutomationConditionRepository.replace` 호출(조건 replace 트랜잭션성). `Condition.fromJson`/`validate` 실패→400 매핑. 형식 검증만(cross-BC 존재검증 없음).

**REFACTOR**: DTO 매핑 헬퍼, KDoc.

**검증**: `./gradlew :modules:automation:test --tests '*RuleController*'`

### Task 9. prod 조립 :modules:app 배선 + fail-closed 부팅 재검증

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/app/`(컴포넌트 스캔/Flyway 배선 — 필요 시), `backend/modules/app/src/test/kotlin/`(BtsApplicationContextTest 또는 신규 fail-closed 가드)]
- depends-on: [6, 7]

**RED**: prod 조립 부팅 테스트 — IssueSnapshotPort 어댑터 미배선 시 `NoSuchBeanDefinitionException`(fail-closed 회귀 가드). 배선 전 실패.

**GREEN**: `:modules:app` prod 컨텍스트가 `AutomationIssueSnapshotAdapter`(@Profile prod)를 IssueSnapshotPort 빈으로 등록 → ActionExecutor non-null 주입 충족. 이미 9BC 조립됨(#259) — 스캔 범위 확인, 필요 시 배선 추가. 머지 전 rebase + `:modules:app:test` 부팅 확인.

**REFACTOR**: 부팅 가드 KDoc(왜 fail-closed인지 — silent no-op 금지).

**검증**: `./gradlew :modules:app:test` (부팅 그린)

## Plan 메타

- task 수: 9
- 예상 wave: 약 4 (automation 단일 모듈이라 Gradle 컴파일 직렬화 [[bts-plan-wave-gradle-module-compile]] — wave 병렬은 파일 작성 단계)
  - wave 1(depends-on []): T1, T3
  - wave 2(T1/T3 후): T2, T4, T5, T6
  - wave 3(게이트 통합): T7
  - wave 4(API+조립): T8, T9
- TDD 강제: yes (RED→GREEN→REFACTOR, test 커밋 선행)
- 추가 검증: ktlint/detekt(automation·shared-kernel·issue-tracking·app), `:modules:app:test` 부팅
- 병렬 dispatch: bts-impl이 depends-on + files 교집합으로 wave 재계산

## 리뷰 결과

### plan-eng-review (2026-07-12, Plan 서브에이전트 적대적 리뷰)

**🔴 BLOCKER 1건 — 무필터 IssueSnapshotPort 읽기가 SDD §12.4 "관리자 우회 없음" 우회**
- 보안 수준(FR-PM-06) 제한 이슈는 지정 그룹 비멤버면 PROJECT_ADMIN도 필드 값 열람 불가(SDD 12장 §12.4 `docs/sdd/12-permissions.md:114`). `fetch(issueKey)` actor·가시성 필터 부재 → 불변식 위반 + "도메인 우회 금지" 모순(기존 read는 actor+visibility 술어 강제 `IssueRepository:1120-1160`). 오라클 익스플로잇(조건 매치→웹훅 발사/SKIPPED 관측으로 미열람 필드 leak). FR-AT-02 쓰기는 actor 강제인데 읽기만 비대칭.
- **해소(Maxi 게이트1 결정)**: (a) 포트에 actor 탑재 `fetch(actorUserId, issueKey)` → 어댑터가 룰 actor로 가시성 강제 read 재사용, 제한 이슈→null→fail-safe SKIPPED [권장·write 경로 대칭·도메인 우회 금지 충족] / (b) `security_level_id IS NOT NULL` 이슈 스냅샷 제외 / (c) ADR+명시 리스크 수용(§12.4 충돌, 비권장).

**🟡 CONCERN 4건 (plan 수정으로 해소)**
- C1. type/status 표현 미확정 + 어댑터 관통 통합테스트 부재 → **해소**: IssueSnapshot 계약에 표현 명시(type=이름, status=stateKey, priority=Int 1-5), Task 6에 실이슈→ConditionContext→evaluate 통합 테스트 1건 추가.
- C2. ActionExecutor 생성자 확장 blast radius(직접생성 3곳 + 풀부팅 7~8개 StubIssueSnapshotPort 필요) 태스크 files 미반영 → **해소**: Task 7 files에 전 부팅 슬라이스 stub 등록 열거, T8 depends-on에 7 추가.
- C3. buildContext(thin,이벤트) vs ConditionContext(rich,DB) 이중 뷰 → **해소**: 스펙/게이트 KDoc에 "조건은 최신 스냅샷, 템플릿은 이벤트 payload(FR-AT-02 현 한계)" 명시. 템플릿 enrich는 본 PR 범위 밖(후속).
- C4. dry-run 실 caller 부재 → **해소**: 스펙 EC12를 "미래 dry-run caller가 생기면 게이트가 자동 커버(현재는 execute(dryRun=true) 단위 테스트로만 검증)"로 정정.
- (경미) Task 7 depends-on [5] 불필요(게이트는 conditionRepo로 읽음, rule.condition 필드 미사용) → T5 의존 유지하되 무해.

**✅ 건전 확인**: 게이트 위치(유일 초크포인트 `AutomationExecutionWorker:203`)·fail-closed 선례(`ActionExecutorFailClosedTest`)·V304 번호(slack V700대 무관)·SKIPPED enum(exhaustive when 0곳)·조건 replace 트랜잭션성(`AutomationRuleService` 기존 @Transactional)·하위호환.

**판정**: BLOCKER 1(포트 actor 계약 결정)·CONCERN 4(plan 수정 해소). 재설계 아님 — 포트 계약+어댑터 매핑 2곳 정정으로 충분. Maxi 게이트1에서 BLOCKER 해소 방식 확정 후 진행.

### BLOCKER 해소 반영 (Maxi 게이트1 확정 — 2026-07-12)

- **BLOCKER 해소 = 옵션 (a) 포트에 actor 탑재.** `IssueSnapshotPort.fetch(actorUserId: UUID, issueKey: String): IssueSnapshot?`.
  어댑터가 룰 actor 권한으로 issue-tracking 기존 **가시성 강제 read 경로** 재사용 → 보안수준 제한 이슈는
  actor가 볼 수 없으면 null → 게이트 fail-safe SKIPPED. FR-AT-02 쓰기 경로(actor 강제)와 대칭.
  → Task 3(포트 시그니처)·Task 6(어댑터 가시성 강제)·Task 7(게이트가 `rule.actorUserId` 전달) 반영.
- **CONCERN 4건 해소도 아래 태스크에 반영**(C1 type=이름·status=stateKey + 관통 통합테스트 / C2 stub 열거 + T8→T7 / C3 이중뷰 명시 / C4 dry-run 정정).
- **게이트1 승인 완료** → bts-impl 진행.
