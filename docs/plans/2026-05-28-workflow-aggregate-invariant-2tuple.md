# CONCERN-1 cleanup — Workflow.of() aggregate invariant 2-tuple 정합 정렬

> slug: workflow-aggregate-invariant-2tuple
> type: bugfix
> agent: backend-engineer
> primary_bc: project-workflow
> 생성: 2026-05-28

## Brief

PR #29 (BLOCKER 2 hot-fix) 머지 후 잔존 CONCERN-1 (Important). `Workflow.of()` aggregate factory invariant 5번이 transition uniqueness 를 `(from, to, name)` 3 튜플로 검사 중. ADR `docs/adr/2026-05-28-workflow-transition-identity-policy.md` 결정 (transition identity = `(from, to)` 2 튜플) + yaml seed fail-fast 정책과 불일치.

위협. 다른 진입 경로 (예. `WorkflowRepository.toAggregate()` 의 DB load, FR-WF-02 CRUD, 향후 도메인 호출) 가 같은 `(from, to)` 가 `name` 만 다른 두 transition 을 보유한 채 aggregate 를 통과시킬 수 있음 → policy enforcement holes.

본 PR scope.
1. `Workflow.of()` invariant 5번을 `(from, to)` 2 튜플 기준으로 변경
2. 회귀 가드 단위 테스트 1건 추가 (`name` 만 달라도 invariant 위반으로 reject)
3. 영향 범위 검증. `WorkflowRepository.toAggregate()` / FR-WF-02 CRUD / 기타 진입 경로의 정책 일치 확인 (회귀 0)

비-범위 (out of scope).
- ADR 본문 갱신 (이미 결정 명시)
- 다른 CONCERN (#2 dead code, ktlint cleanup) — 별 PR 위임
- 새 진입 경로 추가

## 도메인 정리

- **BC**. project-workflow
- **영향 Aggregate**. `Workflow` (Aggregate Root)
- **영향 메서드**. `Workflow.of()` companion factory invariant 5번
- **신규 용어**. 없음 (ADR 에서 이미 정의 완료)
- **fast-track 사유**. `type=bugfix` (정책 정합 정정). grill-with-docs 비용 > 효익.

### ADR 인용 (정합 기준)

`docs/adr/2026-05-28-workflow-transition-identity-policy.md` §결정.

> 옵션 (b1) 채택 — `(from, to)` 2 튜플을 전이 identity로 확정.
> `name` 필드는 사람 친화 표시 라벨 (UI 표시 / yaml 가독성). 엔진 매칭에 사용하지 않는다.
> yaml seed 단계에서 같은 워크플로우 내 `(fromStateKey, toStateKey)` 중복을 fail-fast로 차단. `name` 중복은 허용.

### 현재 코드 (Workflow.kt L33, L66-70) vs ADR 의 drift

```kotlin
// L33 KDoc
// 5. [transitions] 내 (fromStateKey, toStateKey, name) 조합 중복이 0건이어야 한다.

// L66-67 검증 로직
val transitionKeys = transitions.map { Triple(it.fromStateKey, it.toStateKey, it.name) }
val duplicateTransitions = transitionKeys.groupBy { it }.filter { it.value.size > 1 }.keys

// L69 오류 메시지
"Workflow '$key': duplicate transition (from, to, name) combinations found — $duplicateTransitions"
```

= ADR `(from, to)` 2 튜플 정책 위반. `name` 만 다른 두 transition (예. "Start Work" + "시작") 이 같은 `(from, to)` 를 가져도 invariant 5번 통과 — 다른 진입 경로 (WorkflowRepository.toAggregate(), 향후 FR-WF-02 CRUD) 가 policy 우회.

### 다른 진입 경로 (Workflow.of() 호출자)

```
backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/repository/WorkflowRepository.kt
backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/scheme/adapter/inbound/WorkflowKeyResolverImpl.kt
```

= 모두 `Workflow.of(...)` companion factory 통과. invariant 정정 시 자동 적용 (회귀 0).

### 기존 결정 충돌

- 없음. ADR `2026-05-28-workflow-transition-identity-policy.md` 와 정합 정렬이 본 PR 본질.

### 관련 ADR

- `docs/adr/2026-05-28-workflow-transition-identity-policy.md` (기준)
- `docs/adr/2026-05-21-workflow-yaml-vs-db-storage.md` (yaml seed 정책 정합)
- `docs/adr/2026-05-26-workflow-transition-port-result-sealed.md` (보완)

## 스펙

- **fast-track 사유**. `type=bugfix` (정책 정합 정정). office-hours / brainstorming 비용 > 효익. PR #28/#29 inline 패턴 일관.

### 사용자 시나리오 (Given-When-Then)

#### 시나리오 1 — 정상 transitions 통과 (회귀 0)

- Given. yaml 표준 4종 (`software-default`, `bug-tracking`, `simple`, `kanban-basic`) 의 transition 정의 (`(from, to)` 모두 유일)
- When. `Workflow.of(...)` 호출
- Then. invariant 5번 통과 → Workflow 인스턴스 정상 생성

#### 시나리오 2 — 같은 (from, to) 가 name 만 다른 두 transition (회귀 가드 핵심)

- Given. transitions = `[ T1(from=open, to=in_progress, name="Start Work"), T2(from=open, to=in_progress, name="시작") ]`
- When. `Workflow.of(...)` 호출
- Then. invariant 5번 위반 → `IllegalArgumentException` throw + 메시지에 duplicate `(from, to)` 표현 포함

#### 시나리오 3 — 같은 (from, to) 동일 name 중복 (회귀 0)

- Given. transitions = `[ T1(from=open, to=in_progress, name="Start"), T2(from=open, to=in_progress, name="Start") ]`
- When. `Workflow.of(...)` 호출
- Then. invariant 5번 위반 → throw (변경 전후 동일 동작)

#### 시나리오 4 — 다른 (from, to) 가 같은 name (회귀 0)

- Given. transitions = `[ T1(from=open, to=in_progress, name="Move"), T2(from=in_progress, to=done, name="Move") ]`
- When. `Workflow.of(...)` 호출
- Then. invariant 5번 통과 (name 은 사람 친화 라벨, identity 아님)

### 기능 요구사항 (FR)

- **FR-1**. `Workflow.of()` 의 invariant 5번이 `(fromStateKey, toStateKey)` 2 튜플 기준 중복 검증
- **FR-2**. invariant 5번 위반 오류 메시지가 duplicate `(from, to)` 표현 + workflow key 포함
- **FR-3**. KDoc L33 의 invariant 5번 명세가 코드 동작과 일치 (`(fromStateKey, toStateKey)` 명시)
- **FR-4**. 회귀 가드 단위 테스트가 시나리오 1~4 모두 검증

### 비기능 요구사항 (NFR)

- **NFR-1**. 변경 후 기존 테스트 전체 (단위 + 통합) 회귀 0
- **NFR-2**. 변경 영역 = `Workflow.kt` 단일 파일 + 회귀 가드 테스트 1 파일

### API 인터페이스 (REST)

- 변경 없음 (내부 도메인 invariant 정정).

### 데이터 모델 변경

- 변경 없음 (Workflow.kt 의 검증 로직만 정정).

### 엣지 케이스

- **EC-1**. transitions 빈 리스트 → invariant 5번 무관 통과 (states 검증만 적용)
- **EC-2**. 단일 transition → 중복 검증 무관 통과
- **EC-3**. 다른 workflow key 의 같은 `(from, to)` → invariant 5번은 workflow 내부만 검증 (집계 영역 한정)

### 제약 조건

- **C-1**. ADR `docs/adr/2026-05-28-workflow-transition-identity-policy.md` 의 §결정 (`(from, to)` 2 튜플 채택) 과 정합
- **C-2**. WorkflowRepository.toAggregate() 와 WorkflowKeyResolverImpl 의 호출 흐름 회귀 0 (모두 `Workflow.of(...)` 경로 통과 → 자동 적용)

### 측정 가능한 완료 기준

1. `Workflow.kt` 의 L33 KDoc + L66 `Triple` → `Pair` + L69 오류 메시지 모두 `(fromStateKey, toStateKey)` 표현으로 정렬
2. 신규 회귀 가드 단위 테스트 1 파일 (`WorkflowInvariantTest.kt`) 시나리오 1~4 모두 GREEN
3. `./gradlew :modules:project-workflow:test` 전체 통과 (회귀 0)
4. `./gradlew :modules:issue-tracking:test` 전체 통과 (cross-BC test 무영향)

## Brainstorming Check

✅ 통과 (fast-track inline, 1회). office-hours / brainstorming sub-agent 호출 없음 — PR #28/#29 일관 패턴.

- ✅ scope 명확 (Workflow.of() 단일 메서드)
- ✅ 회귀 가드 핵심 시나리오 (시나리오 2) 명시
- ✅ ADR 정합 기준 명시
- ✅ 다른 진입 경로 (toAggregate, FR-WF-02 CRUD) 자동 적용 확인
- ⚠️ 잠재 우려. yaml seed 단의 fail-fast 가 이미 적용 중인지 plan 단계에서 grep 확인 필요 (이미 적용되어 있으면 본 PR 와 정합. 미적용이면 별 PR 위임 또는 scope 확장 판단).

## Plan 메타

- **task 수**. 1 (정통 TDD red→green→refactor 1 cycle)
- **예상 시간**. 약 5분 (직렬, fast-track inline)
- **TDD 강제**. yes (RED 새 테스트 case 1건 + 기존 case 1건 정정 → GREEN Workflow.kt L66+L69+L33 정합 정렬 → REFACTOR 클래스 KDoc L18)
- **병렬 dispatch**. 1 task 라 무관 (단일 wave)
- **fast-track inline**. PR #28/#29 일관 패턴

### Brainstorming ⚠️ 우려 해소

- **yaml seed fail-fast `(from, to)` 유일성 검증 이미 적용 중**. `YamlSeedService.kt:205-218` `validateTransitionUniqueness` 가 `transitions.groupBy { it.from to it.to }` 기준 중복 fail-fast. ADR §yaml seed 유일성 검증 결정과 정합 — 본 PR 의 scope 와 분리, 별 작업 불필요.

## Plan

### Task 1. Workflow.of() invariant 5번 (from, to) 2 튜플 정렬 + WorkflowAggregateTest 회귀 가드 확장

**메타**.
- agent: `backend-engineer`
- files:
  - `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/domain/Workflow.kt`
  - `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/domain/WorkflowAggregateTest.kt`
  - `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/property/WorkflowPropertyTest.kt` (D2 변경 — 잠재 회귀 C1 표면화 자연 정렬)
- depends-on: []

> **변형 사유 — D2 (controller 직접 fix)**. RED + GREEN 후 verification 단계에서 `WorkflowPropertyTest.kt` 3 case fail 표면화. 제너레이터 L246 의 dedup key `Triple(from, to, name)` 이 invariant 강화 시 같은 `(from, to)` 다른 name transition 을 생성 → throw. plan §리뷰 §C1 "잠재 회귀 GREEN 후 결정" 의 정답 = 본 PR 안 정렬 (Maxi D2 옵션 A 결정). controller 가 dedup key 를 `Pair(from, to)` 로 1-line fix + 별 commit (`fix: ... WorkflowPropertyTest 제너레이터 정합`). PR #29 의 task 4 변형 패턴 (verifier 발견 자연 보강) 일관.

#### RED (테스트 fail 보장)

**파일**. `WorkflowAggregateTest.kt`

**변경**.

(a) 기존 case `transition from-to-name 조합 중복 시 IllegalArgumentException` (L152-168) 정정.
- 테스트 이름. `transition from-to-name 조합 중복 시 IllegalArgumentException` → `transition (from, to) 조합 중복 시 IllegalArgumentException`
- `.hasMessageContaining("duplicate")` 유지 (메시지 안 단어 "duplicate" 는 GREEN 후에도 유지)
- 추가 검증. `.hasMessageContaining("(from, to)")` 명시 — GREEN 후 오류 메시지에 새 표현 포함 검증

(b) 신규 case 추가 — **시나리오 2 (회귀 가드 핵심)**.
- 이름. `transition (from, to) 가 같고 name 만 다른 두 transition 시 IllegalArgumentException`
- transitions = `[ (TODO, IN_PROGRESS, "Start Work"), (TODO, IN_PROGRESS, "시작") ]`
- 기대. `IllegalArgumentException` + 메시지에 `"(from, to)"` 및 `"duplicate"` 포함

(c) 신규 case 추가 — **시나리오 4 (false positive 방지)**.
- 이름. `다른 (from, to) 가 같은 name 인 두 transition 은 정상 생성`
- transitions = `[ (TODO, IN_PROGRESS, "Move"), (IN_PROGRESS, DONE, "Move") ]`
- 기대. 정상 Workflow 인스턴스 반환 (`name` 은 identity 아님 — ADR 정합)

(d) 클래스 KDoc L18 정정 — `transition (from, to, name) 조합 중복` → `transition (from, to) 조합 중복`

**실패 메시지 (예상)**.
- case (a). `hasMessageContaining("(from, to)")` 실패 — 현재 메시지가 `"(from, to, name)"` 표현
- case (b). 시나리오 2 — 현재 invariant 5번 Triple 검증이 `name` 까지 비교 → 두 transition 이 중복 미인식 → throw 없이 정상 반환 → `assertThatThrownBy` 실패
- case (c). 현재 코드에서도 통과 (false positive 방지 검증 — GREEN 후에도 통과 유지)

#### GREEN (최소 구현)

**파일**. `Workflow.kt`

**변경**.

(a) L66-67 — `Triple(it.fromStateKey, it.toStateKey, it.name)` → `Pair(it.fromStateKey, it.toStateKey)`

```kotlin
// 변경 전
val transitionKeys = transitions.map { Triple(it.fromStateKey, it.toStateKey, it.name) }
val duplicateTransitions = transitionKeys.groupBy { it }.filter { it.value.size > 1 }.keys

// 변경 후
val transitionKeys = transitions.map { it.fromStateKey to it.toStateKey }
val duplicateTransitions = transitionKeys.groupBy { it }.filter { it.value.size > 1 }.keys
```

(b) L69 오류 메시지 — `(from, to, name)` → `(from, to)`

```kotlin
// 변경 전
"Workflow '$key': duplicate transition (from, to, name) combinations found — $duplicateTransitions"

// 변경 후
"Workflow '$key': duplicate transition (from, to) combinations found — $duplicateTransitions"
```

(c) factory KDoc L33 invariant 5번 — `(fromStateKey, toStateKey, name) 조합` → `(fromStateKey, toStateKey) 조합`

#### REFACTOR (정리)

(d) `WorkflowAggregateTest.kt` 클래스 KDoc L18 정정 (RED 단계에 포함 — 테스트 KDoc 변경이라 RED commit 에 같이 수반).

> 변형 사유. KDoc 변경은 빌드 산출물 아님 (런타임 동작 0) — 테스트 KDoc 은 RED, 도메인 KDoc 은 GREEN, REFACTOR phase 자체는 변경 없음. 정통 TDD 의 GREEN→REFACTOR 사이 본질 변경 없음. plan §변형 사유 명시 (PR #29 의 task 3/4 변형 패턴 일관).

#### 검증

```bash
# 1. 신규/정정 invariant 테스트 모두 GREEN
./gradlew :modules:project-workflow:test --tests "com.bts.workflow.domain.WorkflowAggregateTest"

# 2. project-workflow 전체 — 회귀 0
./gradlew :modules:project-workflow:test

# 3. cross-BC (issue-tracking) — 회귀 0 (Workflow.of() 호출자 0 이라 무영향 기대)
./gradlew :modules:issue-tracking:test

# 4. 린트
./gradlew :modules:project-workflow:ktlintCheck :modules:project-workflow:detekt
```

#### 영향 범위 검증 (Workflow.of() 호출자 전수)

raw grep 결과 (`grep -RIn "Workflow\.of" backend/modules/project-workflow/src/`).

- **prod 호출자 (1건)**. `repository/WorkflowRepository.kt:163` (toAggregate)
- **prod KDoc/error msg 참조 (2건)**. `scheme/adapter/inbound/WorkflowKeyResolverImpl.kt:30, :84` (참조만, 직접 호출 X)
- **test 호출자 (~25건)**. WorkflowAggregateTest / WorkflowCacheTest / WorkflowPropertyTest / WorkflowGraphClosedTest / WebDtoSerializationTest / WorkflowEngineUnitTest / WorkflowControllerMvcTest / WorkflowApplicationServiceTest / RequiredFieldValidatorTest / NotStatusCategoryValidatorTest / CustomExpressionValidatorTest / *PostActionTest 등

= 모두 `Workflow.of(...)` 통과 → invariant 정정 시 자동 적용. 기존 test 의 transition 정의가 같은 `(from, to)` 에 다른 `name` 을 보유한 경우는 invariant 강화 후 fail 가능 — 그러나 정책 정합 의도 본질이라 fail 발생 시 test 도 정합 정정 대상 (가능성 낮음, GREEN 후 grep 으로 확인).

## verification 결과 (2026-05-28)

### PASS

- ✅ `./gradlew :modules:project-workflow:test` — BUILD SUCCESSFUL (4m 16s). `WorkflowAggregateTest` 8 case + `WorkflowPropertyTest` 모두 통과 (C1 dedup fix 효과).
- ✅ `./gradlew :modules:issue-tracking:test` — BUILD SUCCESSFUL (1m 44s). cross-BC 회귀 0.
- ✅ `./gradlew :modules:project-workflow:ktlintMainSourceSetCheck` — BUILD SUCCESSFUL. 본 PR 변경 영역 (Workflow.kt) 린트 통과.

### PRE_EXISTING (본 PR 변경 무관, cleanup PR 위임)

- ❌ `./gradlew :modules:project-workflow:ktlintTestSourceSetCheck` — 3 violations.
  - `WorkflowKeyResolverImplIntegrationTest.kt:27` Unused import
  - `WorkflowKeyResolverImplIntegrationTest.kt:396:26, :396:77` parameter-list-wrapping
  - `git diff main..HEAD -- WorkflowKeyResolverImplIntegrationTest.kt` = 0 → main 동일 결함 → PRE_EXISTING 확정.
- ❌ `./gradlew :modules:project-workflow:detekt` — 64 weighted issues. implementer 보고 + 본 PR 변경 영역 무관.

### D5 옵션 C — cleanup PR 위임 (PR #29 패턴 일관)

PR #29 의 learnings `PRE_EXISTING hot-fix D5 옵션 C 재확인` 패턴 적용.

판별 절차 (5 초).
- `git diff main..HEAD -- <file>` = 0 → PRE_EXISTING 확정
- main 머지 차단 사유 = `ktlintMainSourceSetCheck` 만 (PR #29 시 관측). 본 PR `Main` 통과 → 머지 차단 사유 0
- 본 PR 변경 영역 0 (모두 다른 파일) → hot-fix 자연스럽지 않음

결정 = 본 PR 안 hot-fix 안 함. PR #29 §Remaining Work #2 의 cleanup PR 후보와 동일 항목으로 위임. 본 PR 머지 가능.

> 추후 cleanup PR 시 본 PR 의 detekt 64 + ktlintTest 3 violations 일괄 해소 권장. detekt baseline 도입도 옵션.

## 리뷰 결과

- **fast-track 사유**. `type=bugfix` → 분기표 §"bugfix/chore/qa = skip". 외부 plan-eng-review / plan-ceo-review / plan-design-review / autoplan 호출 없음. controller inline self-review 1단락 (PR #21~#29 일관 패턴).

### controller inline self-review (2026-05-28)

#### 통과 (8건)

1. ✅ **ADR 정합** — `Workflow.of()` invariant 5번이 ADR `2026-05-28-workflow-transition-identity-policy.md` §결정 (`(from, to)` 2 튜플 채택) 과 정합 정렬.
2. ✅ **scope 명확** — 단일 prod 파일 (`Workflow.kt`) + 단일 test 파일 (`WorkflowAggregateTest.kt`). 1 task TDD 1 cycle.
3. ✅ **호출자 전수 확인** — raw grep 인용 (prod 1건 + test ~25건). 모두 `Workflow.of(...)` 통과 → invariant 정정 자동 적용.
4. ✅ **회귀 가드 핵심 시나리오 명시** — 시나리오 2 (name 만 다른 두 transition) RED case 신규 추가. ADR 정신 직접 검증.
5. ✅ **false positive 방지** — 시나리오 4 (다른 from/to 같은 name) RED case 신규 추가. invariant 가 너무 강해지지 않음을 검증.
6. ✅ **Brainstorming ⚠️ 우려 해소** — yaml seed fail-fast `(from, to)` 검증이 `YamlSeedService.kt:205-218` 이미 적용 중. 본 PR scope 와 분리.
7. ✅ **BC 격리** — project-workflow 단일 BC. cross-BC import / pgmq event 발사 없음.
8. ✅ **DEVELOPMENT.md 절대 규칙 정렬** — 변경이 작아 #1.1~#1.5 모두 자명 통과. `@Service` / `@Transactional` 변경 0, Spring Bean 영향 없음.

#### CONCERN (2건, 본 PR 안 처리)

- **C1 — 잠재 회귀 가능성 (Suggestion)**. invariant 강화 시 기존 test (~25 호출자) 중 `(from, to)` 같고 `name` 다른 transition 정의를 보유한 케이스가 fail 가능. plan §검증 #2 (`./gradlew :modules:project-workflow:test`) 에서 표면화. 발견 시 옵션. (a) 정합 강화 자연 부작용 → 본 PR 안 정정 (test 의 정의도 ADR 정합) (b) scope 명확성 위해 별 cleanup PR 분리. GREEN 후 실제 발견 여부에 따라 결정. 본 PR plan §verification 결과 단락에 명시 예정.

  > **GREEN 후 실측 — D2 옵션 A 선택**. `WorkflowPropertyTest.kt` 제너레이터 L246 dedup key 가 `Triple(from, to, name)` 이라 invariant 강화 후 3 case fail. Maxi D2 결정으로 본 PR 안 1-line fix (Pair(from, to) dedup). Task 1 변형 사유에 명시.

- **C2 — TDD 변형 (Note)**. REFACTOR phase 의 본질 변경 없음 (factory KDoc 정정만 GREEN 에 포함, 클래스 KDoc 정정은 RED 에 같이 수반). plan §RED §(d) 에 변형 사유 명시. PR #29 의 task 3/4 변형 패턴 일관.

#### BLOCKER (0건)

- 없음.

### 다음 단계

🛑 **게이트 1 — Maxi 검토 부탁드립니다**.
