<!-- Flyway V001 namespace 충돌 정비 — issue-tracking + project-workflow 두 BC의 classpath 격리 -->

# Flyway V001 namespace 충돌 정비

> slug: flyway-v001-namespace-issue-tracking-project-workf
> type: chore (manual_override — classify 자동 결과 `migration`. DB 스키마 무변경이라 chore 본질)
> agent: db-engineer
> 생성: 2026-05-27

## Brief

### 사용자 원문

> Flyway V001 namespace 충돌 정비 — issue-tracking + project-workflow 두 BC의 db/migration 하위에 BC별 디렉토리(db/migration/issue-tracking/, db/migration/project-workflow/) 신설하고 V001~V002 파일 이동 + IssueTestcontainersBase.configureFlyway locations 갱신 + application-*.yml의 spring.flyway.locations 갱신 + IssueRepositoryTest @Disabled 제거. PR #23 plan §F10 절차 그대로. 검증 = `./gradlew :backend:issue-tracking:test --tests IssueRepositoryTest` 2회 연속 0 fail (NFR-3 충족).

### classify + manual_override 결과

- type. **chore** (manual_override) — 자동값 `migration` 은 DB 스키마 변경 가정. 본 작업은 파일 이동 + Flyway locations 설정만, 운영 DB 무영향.
- agent. **db-engineer** (자동값 유지) — Flyway 영역 적임.
- slug. `flyway-v001-namespace-issue-tracking-project-workf` (자동 50자컷).
- BC. **issue-tracking + project-workflow 2 BC** (multi-BC chore 예외 — 인프라 정비 한정. CLAUDE.md §핵심 패턴 "한 PR = 한 BC" 의 정당화된 예외, PR #13 learnings #3 BC 격리 예외 패턴과 동일 결).

### 충돌 사실 (배경)

- 세 모듈 모두 동일 classpath path 사용. `backend/modules/<bc>/src/main/resources/db/migration/V001__*.sql`.
  - `identity-access/V001__users.sql` ~ `V006__personal_access_tokens.sql`
  - `issue-tracking/V001__issues_initial.sql`, `V002__pgmq_queue_issue_events.sql`
  - `project-workflow/V001__init_workflow.sql`
- Flyway 가 classpath `db/migration` 통째로 스캔 → 동일 버전 발견 → `FlywayException: duplicate version` 가능.
- 현재 `IssueRepositoryTest.kt:40` `@Disabled` 가드로 실행 차단 (PR #23 시점).

### scope 명시

본 PR 정비 대상.

- (a) `backend/modules/issue-tracking/src/main/resources/db/migration/V001*.sql`, `V002*.sql` → `db/migration/issue-tracking/` 하위로 이동.
- (b) `backend/modules/project-workflow/src/main/resources/db/migration/V001*.sql` → `db/migration/project-workflow/` 하위로 이동.
- (c) `IssueTestcontainersBase.configureFlyway` default `locations("classpath:db/migration/issue-tracking")` 갱신 (project-workflow 의 동급 Testcontainers base 도 동시 갱신).
- (d) `application-*.yml` 의 `spring.flyway.locations` 갱신 (영향 받는 yml 전체).
- (e) `IssueRepositoryTest` `@Disabled` annotation 제거.
- (f) 검증. `./gradlew :backend:issue-tracking:test --tests IssueRepositoryTest` 2회 연속 0 fail (NFR-3).

### scope 명시적 제외

- **identity-access** (V001~V006). 본 PR scope 밖. 현재 충돌 실측 영역이 아니라 별 chore PR 로 분리. plan §F10 (PR #23) 도 issue-tracking + project-workflow 2 BC 만 절차 명시. identity-access 충돌 발현 시점에 동일 패턴 적용.
- **신규 마이그레이션 추가**. 본 PR 은 기존 파일 이동만. DDL 변경 0.

## 도메인 정리 (← /bts-domain — chore fast-track 으로 skip)

> chore type — domain 단계 skip. 도메인 모델 변경 0 (Flyway namespace 는 인프라/빌드 설정 영역).

## 스펙 (← /bts-spec Phase A — chore fast-track 으로 skip)

> chore type — spec 단계 skip. 사용자 원문 + PR #23 plan §F10 의 (a)~(e) 절차 자체가 명세 역할. brainstorming sanity check 도 skip.

## Brainstorming Check (← /bts-spec Phase B — chore fast-track 으로 skip)

> chore type — Phase B skip.

## Plan

> **TDD 변형 — `@Disabled` 제거가 만드는 fail 이 RED, file move + locations 정합이 GREEN.** PR #12 / PR #16 의 E2E TDD 변형 (test 작성 = RED → 실행 = GREEN, 별 commit 분리 불요) 패턴과 동일 결. 본 작업은 새 단위 테스트 추가 없음 → 기존 `@Disabled` 가드 제거 + 기존 9 시나리오 (T1~T9) 가 RED→GREEN 시퀀스 자체 형성.

> **충돌 실측 영역 = issue-tracking 모듈만**. `backend/modules/issue-tracking/build.gradle.kts` 의 `implementation(project(":modules:project-workflow"))` 단방향 의존성으로, issue-tracking 의 test classpath 에 두 모듈 V001 가 동거 → Flyway duplicate. 반대 방향 의존 없음 → project-workflow 단독은 통과 중. 그러나 본 PR scope 에 project-workflow 도 포함 — 미래 다른 모듈이 project-workflow 를 dependency 로 가질 때 동일 충돌 방지 (plan §F10 절차 (b) 명시).

### Task 1. project-workflow Flyway namespace 격리 — 미래 안전 차원

**메타**.
- agent: `db-engineer`
- files: [
    `backend/modules/project-workflow/src/main/resources/db/migration/V001__init_workflow.sql` (이동 → `db/migration/project-workflow/V001__init_workflow.sql`),
    `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/repository/WorkflowRepositoryTest.kt`,
    `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/integration/WorkflowIntegrationTest.kt`,
    `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/seed/YamlSeedServiceTest.kt`,
    `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/cache/WorkflowCacheTest.kt`,
    `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/db/V001MigrationTest.kt`
  ]
- depends-on: []

**RED**.
- 트리거. file move 만 먼저 (locations 갱신 전) → `./gradlew :backend:project-workflow:test` 실행 시 Flyway 가 `classpath:db/migration` 에서 V001 못 찾음 → `No migration scripts found` fail.
- TDD 변형 사실. 실측 충돌 (`FlywayException: duplicate version`) 은 project-workflow 단독 모듈에서는 발현 안 됨 (issue-tracking 의존성 부재). 따라서 본 task 의 RED 본질 = "file 이동 후 locations 미갱신 시 발생하는 자연스러운 fail". 별 RED commit 분리 불요 — file move + 5곳 locations 갱신을 한 묶음 GREEN 으로 처리.

**GREEN**.
- (1) `mkdir backend/modules/project-workflow/src/main/resources/db/migration/project-workflow` + V001 file `git mv`.
- (2) 5 테스트 파일 모두 `.locations("classpath:db/migration")` → `.locations("classpath:db/migration/project-workflow")`. WorkflowRepositoryTest.kt:51 / WorkflowIntegrationTest.kt:97 / YamlSeedServiceTest.kt:67 / WorkflowCacheTest.kt:64 / V001MigrationTest.kt:42.
- 검증. `./gradlew :backend:project-workflow:test` 0 fail (PR #10 baseline 186 tests + PR #13 후속 +5 = 약 191 tests 회귀 가드).

**REFACTOR**.
- 5 테스트 파일 상단 주석/KDoc 의 `classpath:db/migration` 언급이 있으면 새 path 반영. 본 PR scope 의 최소 정리만.

**검증**. `./gradlew :backend:project-workflow:test` (모듈 전체 회귀 가드)

---

### Task 2. issue-tracking Flyway namespace 격리 — 충돌 실측 해소

**메타**.
- agent: `db-engineer`
- files: [
    `backend/modules/issue-tracking/src/main/resources/db/migration/V001__issues_initial.sql` (이동 → `db/migration/issue-tracking/V001__issues_initial.sql`),
    `backend/modules/issue-tracking/src/main/resources/db/migration/V002__pgmq_queue_issue_events.sql` (이동 → `db/migration/issue-tracking/V002__pgmq_queue_issue_events.sql`),
    `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/repository/IssueTestcontainersBase.kt`,
    `backend/modules/issue-tracking/src/main/resources/application-dev.yml`,
    `backend/modules/issue-tracking/src/main/resources/application-test.yml`
  ]
- depends-on: []

**RED**.
- 트리거 1. file move 만 먼저 (locations 갱신 전) → `./gradlew :backend:issue-tracking:test` 실행 시 Flyway 가 V001/V002 못 찾음 fail.
- 트리거 2. **현재 main 상태** = IssueRepositoryTest 가 `@Disabled` 라 충돌 발현 차단 중. 본 task 단독으로는 IssueRepositoryTest 미실행 → 충돌 실측 발현 없음. 충돌 실측 발현 = Task 3 의 `@Disabled` 제거 시점.
- TDD 변형 사실. Task 2 의 GREEN = "file move + 3곳 locations 갱신" 한 묶음. 별 RED commit 분리 불요 — Task 3 의 `@Disabled` 제거가 본 namespace 격리의 GREEN 검증 본질.

**GREEN**.
- (1) `mkdir backend/modules/issue-tracking/src/main/resources/db/migration/issue-tracking` + V001/V002 `git mv`.
- (2) `IssueTestcontainersBase.kt:85` default locations 갱신. `builder.placeholderReplacement(false).locations("classpath:db/migration")` → `.locations("classpath:db/migration/issue-tracking")`.
- (3) `application-dev.yml:25` `locations: classpath:db/migration` → `locations: classpath:db/migration/issue-tracking`.
- (4) `application-test.yml:54` 동일 갱신.
- 검증. `./gradlew :backend:issue-tracking:test` 0 fail (IssueRepositoryTest 는 여전히 @Disabled 라 미실행, 다른 단위/통합 테스트 33건 회귀 가드).

**REFACTOR**.
- `IssueTestcontainersBase.kt:19-46` KDoc 의 사용 예시 `"classpath:db/migration"` 가 있다면 BC namespace path 로 정정. 본 PR scope 의 최소 정리.

**검증**. `./gradlew :backend:issue-tracking:test` (IssueRepositoryTest 제외 전체 통과)

---

### Task 3. IssueRepositoryTest @Disabled 제거 — Task 1+2 GREEN 의 본질 검증

**메타**.
- agent: `db-engineer`
- files: [
    `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/repository/IssueRepositoryTest.kt`
  ]
- depends-on: [2]

**RED**.
- Line 40-45 의 `@org.junit.jupiter.api.Disabled(...)` annotation 제거 → `./gradlew :backend:issue-tracking:test --tests IssueRepositoryTest` 실행 시도.
- Task 2 의 namespace 정비가 부정확하면 이 시점에 Flyway duplicate 또는 migration 미발견 fail 발현. 이게 TDD 변형 RED 의 본질 — "9 시나리오 (T1~T9) 의 기존 fail 상태가 본 task 의 RED commit 역할".

**GREEN**.
- Task 2 의 GREEN 이 정확하면 IssueRepositoryTest 의 T1~T9 9 시나리오 모두 통과 (singleton container 기동 + Flyway migrate `db/migration/issue-tracking` 만 스캔 → V001 + V002 적용 → 9 시나리오 검증).
- KDoc line 18-38 의 "Flyway V001 + V002 를 JVM singleton 라이프사이클로 기동" 부분은 본질 무변경 — 단 line 21 의 `db/migration/V001` path 언급은 새 namespace path 로 정정 가능 (REFACTOR 단계).

**REFACTOR**.
- KDoc line 18-46 의 path 언급 정정 + `@Disabled` annotation 제거로 사라진 사유 메모를 KDoc 의 별 단락 ("**PR #24 — Flyway namespace 격리 완료로 Disabled 해제**") 로 1줄 보존. learnings.md 사후 append 시 trigger 명시 효과.

**검증**. `./gradlew :backend:issue-tracking:test --tests IssueRepositoryTest` 9/9 통과.

---

### Task 4. NFR-3 검증 — IssueRepositoryTest 2회 연속 0 fail

**메타**.
- agent: `db-engineer`
- files: [] (코드 변경 0 — 명령 실행만, 결과는 plan/PR body 에 기록)
- depends-on: [3]

**검증 명령**.
```bash
./gradlew :backend:issue-tracking:test --tests IssueRepositoryTest
# 1회차 결과 기록
./gradlew :backend:issue-tracking:test --tests IssueRepositoryTest
# 2회차 결과 기록 — 양쪽 모두 0 fail 이어야 NFR-3 충족
```

**NFR-3 충족 조건**.
- 1회차 = 9/9 통과 (BUILD SUCCESSFUL).
- 2회차 = 9/9 통과 (singleton container 의 stateful 부작용 없음 — `@BeforeEach cleanIssues()` 가 매 테스트 격리 보장).
- 두 회 모두 동일 결과 = `IssueRepositoryTest` stability 증명, NFR-3 통과.

**추가 회귀 가드**.
- `./gradlew :backend:issue-tracking:test` 모듈 전체 0 fail.
- `./gradlew :backend:project-workflow:test` 모듈 전체 0 fail (Task 1 회귀 가드 재검증).
- `./gradlew test` (모든 모듈) 0 fail 권장 — 시간 허용 시.

**결과 기록**.
- 본 plan 파일의 `## 검증 기준 (NFR-3 충족)` 섹션에 측정 시간 + 결과 (PASS/FAIL) append.
- PR #24 body 의 체크리스트에 "NFR-3 측정 — 2회 연속 0 fail" 결과 보고.
- 후속 — `docs/plan/product/issue-tracking.md` §2.1.1 FR-IS-01 D5 `[~]` → `[x]` 마킹은 본 PR 머지 후 별 docs commit (BTS 워크플로우의 자동 갱신 — `/bts-codereview` 통과 직후).

---

## Plan 메타

- task 수. **4**
- 예상 시간. task × 3분 = 약 12분 직렬 / wave 적용 시 약 9분 (wave 1 = T1+T2 병렬 → wave 2 = T3 → wave 3 = T4 검증).
- TDD 강제. yes (변형 — `@Disabled` 제거 + 기존 9 시나리오 = 자연스러운 RED→GREEN).
- 병렬 dispatch. wave 1 의 T1, T2 는 다른 모듈 파일이라 겹침 0 → 같은 wave 가능.
- 추가 검증. ktlint + detekt (Task 1, 2 의 코드 변경 영역에 한정) / wave 3 의 NFR-3 측정값은 plan + PR body 양쪽 기록.

## 리뷰 결과 (← /bts-review-plan — chore fast-track 으로 skip)

> chore type — plan 작성 후 review-plan 단계 skip 가능. /bts-codereview 단계에서 PR 단위 리뷰로 충분.

## 검증 기준 (NFR-3 충족)

### 검증 명령 (목표)

- `./gradlew :modules:issue-tracking:test --tests IssueRepositoryTest` 2회 연속 0 fail.
- `./gradlew :modules:project-workflow:test` 0 fail (회귀 가드).
- `./gradlew :modules:issue-tracking:test`, `./gradlew :modules:project-workflow:test` 전체 통과.
- Flyway 로딩 시 `FlywayException: duplicate version` 발현 0 — 두 모듈 모두 격리된 locations 만 스캔.

### 측정 결과 (2026-05-27, controller direct execution)

| 회차 | 명령 | 소요 시간 | 결과 |
|---|---|---|---|
| NFR-3 1회차 | `:modules:issue-tracking:cleanTest :modules:issue-tracking:test --tests IssueRepositoryTest` | 24s | ✅ BUILD SUCCESSFUL |
| NFR-3 2회차 | (동일 명령) | 24s | ✅ BUILD SUCCESSFUL |
| 회귀 가드 (두 모듈 전체) | `:modules:issue-tracking:cleanTest :modules:project-workflow:cleanTest :modules:issue-tracking:test :modules:project-workflow:test` | 3m 47s | ✅ BUILD SUCCESSFUL (12 tasks: 4 executed, 8 up-to-date) |

### NFR-3 충족 판정

- ✅ **2회 연속 BUILD SUCCESSFUL (24s 동일 시간)** — IssueRepositoryTest 9 시나리오 (T1~T9 = 10 tests) stability 증명.
- ✅ **issue-tracking 모듈 전체 170 tests** (단위 160 + IssueRepositoryTest 10) 통과.
- ✅ **project-workflow 모듈 전체 195 tests** 통과 (Task 1 namespace 격리 회귀 가드).
- ✅ **`FlywayException: duplicate version` 발현 0** — 두 모듈 모두 `db/migration/<bc>/` 격리된 locations 만 스캔.

→ **NFR-3 PASS**. FR-IS-01 D5 [~] → [x] 마킹 trigger 충족 (본 PR 머지 후 `docs/plan/product/issue-tracking.md` §2.1.1 자동 갱신 — `/bts-codereview` 통과 직후).

## 관련 PR / 산출물

- PR #23 (선행). FR-IS-01 D4+D5 마무리. plan `docs/plans/2026-05-26-issue-tracking-bc-fr-is-01-cleanup-tests.md` §F10 에 본 PR 의 절차가 박힘.
- PR #14. issue-tracking BC 부트스트랩. V007→V001 모듈별 namespace 결정 (history.md PR #14 entry) 이 본 충돌의 출발점.

## 후속 trigger (본 PR 머지 후)

- `docs/plan/product/issue-tracking.md` §2.1.1 FR-IS-01 D5 `[~]` → `[x]` (NFR-3 측정 통과 시점).
- `docs/plans/2026-05-26-issue-tracking-bc-fr-is-01-cleanup-tests.md` §F10 의 "Flyway namespace 후속 cleanup PR" trigger 충족 기록.
- identity-access (V001~V006) 동일 namespace 정비는 별 chore PR 로 분리 — 본 PR 머지 후 별 trigger 결정.
