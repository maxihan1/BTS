# BLOCKER 2 hot-fix slice — V003+V004 Flyway 마이그레이션 경로 정리

> slug: blocker-2-hot-fix-slice-v003-v004-flyway-db-migrat
> type: migration
> agent: db-engineer
> primary_bc: issue-tracking
> 생성: 2026-05-28

## Brief

**사용자 원문**.
BLOCKER 2 hot-fix slice — V003+V004 Flyway 마이그레이션 경로 정리 (db/migration → db/migration/issue-tracking/). application-{dev,test}.yml Flyway locations 및 통합테스트 location 정리.

**Classify 결과**.
- type: migration
- agent: db-engineer
- slug: `blocker-2-hot-fix-slice-v003-v004-flyway-db-migrat` (50자컷)
- task_count: 0 (자동 분석 결과, /bts-plan 에서 확정)

**배경 (저장 컨텍스트 2026-05-28T15:22 / PR #28 머지 직후)**.
- V003 (PR #18 산출물) 과 V004 (PR #27 산출물) Flyway 마이그레이션 파일이 `backend/modules/issue-tracking/src/main/resources/db/migration/` 루트에 위치
- `application-{dev,test}.yml` 의 `spring.flyway.locations` 가 `classpath:db/migration/issue-tracking` 만 명시
- 결과. prod boot 시 Flyway 가 V003/V004 를 스캔 못 함 → 운영 incident 위험
- 통합테스트는 `classpath:db/migration` 까지 우회로 동작 중 (잠재 결함 마스킹)

**범위**.
- V003 + V004 파일을 `git mv` 로 `db/migration/issue-tracking/` 하위 이동
- 통합테스트 Flyway locations 정리 (우회 경로 제거)
- 회귀 가드. boot 시 Flyway 가 모든 마이그레이션을 스캔하는지 검증 테스트

**전제 의존**. PR #28 머지 완료 (BLOCKER 1 해소, main `302a637`).

## 도메인 정리

**BC**. `issue-tracking` (primary). `project-workflow` 는 부수 영향 (cross-BC testRuntimeOnly 의존을 통한 마이그레이션 classpath 포함).

**도메인 모델 영향**. 없음. 본 작업은 인프라/배포 정책 정렬 — DB 스키마 변경 0, 도메인 엔티티 변경 0, glossary 신규 용어 0.

**현 상태 (실측 grep)**.
- V001, V002. `db/migration/issue-tracking/` 하위 (올바른 위치)
- V003 (`__issue_types.sql`, PR #18 산출물), V004 (`__lowercase_current_state_key.sql`, PR #27 산출물). `db/migration/` 루트 (잘못된 위치)
- `application-{dev,test}.yml` 의 `spring.flyway.locations` = `classpath:db/migration/issue-tracking` (V003/V004 미스캔)
- 통합테스트 우회 (5개 파일이 `db/migration` 루트 location 사용).
  - `IssueTypeRepositoryIntegrationTest.kt:66, 194`
  - `IssueTypesMigrationIntegrationTest.kt:56`
  - `LowercaseCurrentStateKeyMigrationTest.kt:66, 117`
  - `IssueControllerTransitionIntegrationTest.kt:500` (검증 필요)
  - `IssueTestcontainersBase.kt:89` 은 이미 `db/migration/issue-tracking` 사용 (영향 없음)

**왜 BLOCKER 인가**.
- prod boot 시 `application.yml` 의 `flyway.locations` 만 보므로 V003/V004 미스캔 → 운영 incident 가능성 (issue_types 미생성 / current_state_key 미정규화)
- 통합테스트는 `db/migration` 루트 우회로 통과 중 → silent fail (CI 통과해도 prod 깨짐)

**기존 결정 충돌 / 관련 ADR**.
- 관련 ADR. `docs/adr/2026-05-26-bc-migration-prefix-policy.md` (BC 별 100단위 번호 범위 정책, project-workflow V200~V299 도입 시점).
- 본 결정은 그 ADR 의 **연속선상의 sub-policy** — issue-tracking BC 의 모든 마이그레이션 파일을 `db/migration/issue-tracking/` 하위로 통일. ADR 정책과 충돌 없음, 단순 누락된 file 들의 위치 정렬.
- 신규 ADR 발행 여부. **불필요** 판단 — 기존 ADR `bc-migration-prefix-policy.md` 의 의도 (BC 별 분리) 가 이미 명시되어 있고, 본 PR 은 그 의도의 충실한 적용. 단, plan §Plan 단계에서 후속 회귀 가드 (예. 마이그레이션 파일이 BC 폴더 하위에 있는지 ArchUnit/test 검증) 추가 시 ADR 후보로 재검토.

**도메인 사전 (glossary) 영향**. 없음. 신규 용어 추가 불필요.

**Maxi_wiki/BTS/domain/issue-tracking.md 갱신 영향**. 없음 (도메인 모델 무관).

## 스펙

본 PR 은 hot-fix slice (fast-track inline). 사용자 시나리오 / FR / NFR 형식은 자명하므로 변경 명세 + 검증 기준 중심으로 작성. 외부 office-hours / brainstorming 호출 생략, controller inline 통합 (PR #21/#22/#23/#24/#28 패턴 일관).

### 변경 범위 (D2 옵션 B 결정)

#### 1. issue-tracking V003/V004 파일 이동

- `backend/modules/issue-tracking/src/main/resources/db/migration/V003__issue_types.sql` → `db/migration/issue-tracking/V003__issue_types.sql`
- `backend/modules/issue-tracking/src/main/resources/db/migration/V004__lowercase_current_state_key.sql` → `db/migration/issue-tracking/V004__lowercase_current_state_key.sql`
- `git mv` 사용 (history 보존).

#### 2. issue-tracking 통합테스트 location 정리

| 파일 | 현재 | 변경 후 |
|---|---|---|
| `IssueTypeRepositoryIntegrationTest.kt:66, 194` | `classpath:db/migration` | `classpath:db/migration/issue-tracking` |
| `IssueTypesMigrationIntegrationTest.kt:56` | `classpath:db/migration` | `classpath:db/migration/issue-tracking` |
| `LowercaseCurrentStateKeyMigrationTest.kt:66, 117` | `classpath:db/migration` | `classpath:db/migration/issue-tracking` |
| `IssueControllerTransitionIntegrationTest.kt:500` | `db/migration/issue-tracking` + `db/migration` + `db/migration/project-workflow` 3행 | `db/migration/issue-tracking` + `db/migration/project-workflow` 2행 (루트 제거) + KDoc 주석 정정 |
| `IssueTestcontainersBase.kt:89` | `classpath:db/migration/issue-tracking` | 변경 없음 (이미 정확) |

#### 3. project-workflow 통합테스트 location 정리

cross-BC 의존 (testRuntimeOnly) 으로 issue-tracking 마이그레이션도 함께 적용 필요. Flyway `locations(...)` 는 replace 이므로 양쪽 path 명시.

대상 파일 (grep 기준 15+ 파일, 약 19 occurrences).
- `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/scheme/repository/` — `WorkflowSchemeRepositoryIntegrationTest.kt`, `SchemeIssueTypeMappingRepositoryIntegrationTest.kt`, `ProjectWorkflowSchemeAssignmentRepositoryIntegrationTest.kt`
- `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/scheme/adapter/inbound/` — `WorkflowKeyResolverImplIntegrationTest.kt`, `WorkflowResolverImplIntegrationTest.kt`
- `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/scheme/adapter/outbound/` — `WorkflowSchemeEventPublisherIntegrationTest.kt`
- `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/scheme/migration/` — `WorkflowSchemesMigrationIntegrationTest.kt`
- `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/cache/` — `WorkflowCacheTest.kt`
- `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/repository/` — `WorkflowRepositoryTest.kt`
- `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/seed/` — `YamlSeedServiceTest.kt`

변경 패턴.
```kotlin
// Before
.locations("classpath:db/migration")

// After
.locations(
    "classpath:db/migration/issue-tracking",
    "classpath:db/migration/project-workflow",
)
```

#### 4. 회귀 가드 (Task 1 RED)

`db/migration/` 루트에 `V*.sql` 파일이 잔존하지 않음을 검증하는 통합테스트 또는 단위 테스트 추가. 형식 옵션은 plan §Task 1 단계에서 결정 (BC 별 폴더 외 잔존 SQL 0 검증).

#### 5. application yml 명세

이미 `classpath:db/migration/issue-tracking` 으로 정확히 명시되어 있어 변경 없음. Task 4 (V003/V004 이동 후) 자동으로 4건 모두 스캔됨.

### 측정 가능한 완료 기준

1. `find backend/modules/issue-tracking/src/main/resources/db/migration -maxdepth 1 -name 'V*.sql'` 결과 0건 (루트에 마이그레이션 파일 없음)
2. `find backend/modules/issue-tracking/src/main/resources/db/migration/issue-tracking -name 'V*.sql'` 결과 4건 (V001~V004)
3. `./gradlew :modules:issue-tracking:test` 전체 통과 (회귀 0)
4. `./gradlew :modules:project-workflow:test` 전체 통과 (회귀 0)
5. 회귀 가드 테스트 (Task 1 산출물) GREEN
6. `application-dev.yml` 의 `classpath:db/migration/issue-tracking` location 으로 V001~V004 모두 Flyway 가 스캔 검증 (boot 시점 또는 통합테스트)

### 엣지 케이스 / 제약 조건

- **운영 DB 영향**. Phase 0 진입 직전, 운영 DB 미배포. flyway_schema_history 의 path 비교 회귀 없음 (ADR `bc-migration-prefix-policy.md` §운영 영향 동일 근거).
- **TDD 강제**. Task 1 (회귀 가드 RED) → Task 2 (V003/V004 이동 GREEN) → Task 3 (issue-tracking IT 정리 GREEN) → Task 4 (project-workflow IT 정리 GREEN) → Task 5 (refactor / 주석 정정) 순서. RED 분리 가능 task 만 명시 분리.
- **Flyway locations replace 동작 주의**. 단일 path 에서 멀티 path 로 변경 시 두 path 모두 명시. learnings PR #24 와 동일 패턴.
- **cross-BC ArchUnit 룰**. 본 PR 은 코드 import 변경 0, ArchUnit `BoundedContextIsolationTest` 영향 없음.

### 관련 PR / 학습

- 선행. PR #28 (BLOCKER 1 hot-fix, 머지 완료 `3e225bc`)
- 동일 영역 선행. PR #24 (chore Flyway V001 namespace 정비, commit `842ab23`) — V001/V002 + V200~V202 첫 이동
- ADR. `docs/adr/2026-05-26-bc-migration-prefix-policy.md` 의 후속 적용 (V003/V004 누락분 정리)

## Brainstorming Check

✅ 통과 (1회 iteration, inline self-check)

**self-check 내용**.
- 누락된 IT 파일 있는지 grep 으로 전수 검증 → 19 occurrence (issue-tracking 5 + project-workflow 14) 확인
- cross-BC 의존 영향 (project-workflow testRuntimeOnly 의 issue-tracking 마이그레이션 의존) 검토 완료
- application yml 변경 불필요 확인 (이미 정확)
- 회귀 가드 (Task 1) 도입 결정 — silent 우회 차단 본질
- D2 옵션 B 선택 (사용자 결정) — cross-BC 정합성 우선
- TDD 변형 후보. Task 5 (refactor / 주석 정정) — RED 분리 어려움, 정통 사이클 외

## Plan

TDD red→green→refactor 사이클 강제. hot-fix 특성상 task 일부 변형 (RED 분리 어려운 경우 commit message + plan 본문에 변형 사유 명시, PR #21/#22/#23/#24/#28 패턴 일관).

### Task 1. 회귀 가드 단위 테스트 — `db/migration/` 루트 V*.sql 잔존 0 검증

**메타**.
- agent. `db-engineer`
- files. [`backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/migration/MigrationFileLayoutTest.kt` (신규)]
- depends-on. []

**RED**.
- 파일. `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/migration/MigrationFileLayoutTest.kt` (신규)
- 테스트.
  ```kotlin
  @Test
  fun `db_migration 루트에 V SQL 파일이 잔존하지 않아야 한다 (BC prefix 정책)`() {
      val resolver = PathMatchingResourcePatternResolver(javaClass.classLoader)
      val resources = resolver.getResources("classpath:db/migration/V*.sql")
      assertEquals(0, resources.size,
          "db/migration/ 루트에 V*.sql 잔존. ADR 2026-05-26-bc-migration-prefix-policy.md 위반. " +
          "BC 폴더 (db/migration/issue-tracking/, db/migration/project-workflow/) 하위로 이동 필요. " +
          "잔존 파일: ${resources.map { it.filename }}")
  }
  ```
- 실패 메시지 (예상). `db/migration/ 루트에 V*.sql 잔존. ... 잔존 파일: [V003__issue_types.sql, V004__lowercase_current_state_key.sql]`
- 정당성. Spring 의 `PathMatchingResourcePatternResolver` 사용 (issue-tracking 이 이미 Spring 의존). classpath 기반이라 build output 의 실제 resources 디렉토리 검증.

**GREEN**. (Task 2 에서 file 이동 → 회귀 가드 자동 통과)

**REFACTOR**. (Task 4 에서 KDoc 통합 정리)

**검증**. `./gradlew :modules:issue-tracking:test --tests MigrationFileLayoutTest`

---

### Task 2. V003/V004 파일 이동 + issue-tracking IT location 정리 (한 BC 일관)

**메타**.
- agent. `db-engineer`
- files. 이동.
  - `backend/modules/issue-tracking/src/main/resources/db/migration/V003__issue_types.sql` → `db/migration/issue-tracking/V003__issue_types.sql`
  - `backend/modules/issue-tracking/src/main/resources/db/migration/V004__lowercase_current_state_key.sql` → `db/migration/issue-tracking/V004__lowercase_current_state_key.sql`
- files. 변경.
  - `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/type/repository/IssueTypeRepositoryIntegrationTest.kt`
  - `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/type/migration/IssueTypesMigrationIntegrationTest.kt`
  - `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/migration/LowercaseCurrentStateKeyMigrationTest.kt`
  - `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/adapter/inbound/rest/IssueControllerTransitionIntegrationTest.kt` (locations 의 `classpath:db/migration` 항목 제거)
- depends-on. [1]

**TDD 변형** (RED 분리 불필요 — Task 1 의 회귀 가드가 RED 역할, file 이동 + IT location 변경이 일관 GREEN).

**GREEN**.
1. `git mv` 로 V003/V004 두 파일 이동 (history 보존)
2. `IssueTypeRepositoryIntegrationTest.kt:66, 194` 의 `.locations("classpath:db/migration")` → `.locations("classpath:db/migration/issue-tracking")`
3. `IssueTypesMigrationIntegrationTest.kt:56` 동일
4. `LowercaseCurrentStateKeyMigrationTest.kt:66, 117` 동일
5. `IssueControllerTransitionIntegrationTest.kt:500` 의 3행 locations 에서 `classpath:db/migration` 행 제거 (BC 별 2행 유지)

**검증**.
- `./gradlew :modules:issue-tracking:test` 전체 통과 (회귀 0)
- Task 1 의 `MigrationFileLayoutTest` GREEN
- `find backend/modules/issue-tracking/src/main/resources/db/migration -maxdepth 1 -name 'V*.sql'` 결과 0

---

### Task 3. project-workflow IT location 정리 (cross-BC 의존)

**메타**.
- agent. `db-engineer`
- files. (14 occurrences in 8 파일)
  - `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/scheme/repository/WorkflowSchemeRepositoryIntegrationTest.kt`
  - `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/scheme/repository/SchemeIssueTypeMappingRepositoryIntegrationTest.kt`
  - `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/scheme/repository/ProjectWorkflowSchemeAssignmentRepositoryIntegrationTest.kt`
  - `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/scheme/adapter/inbound/WorkflowKeyResolverImplIntegrationTest.kt`
  - `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/scheme/adapter/inbound/WorkflowResolverImplIntegrationTest.kt`
  - `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/scheme/adapter/outbound/WorkflowSchemeEventPublisherIntegrationTest.kt`
  - `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/scheme/migration/WorkflowSchemesMigrationIntegrationTest.kt`
  - `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/cache/WorkflowCacheTest.kt`
  - `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/repository/WorkflowRepositoryTest.kt`
  - `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/seed/YamlSeedServiceTest.kt`
- depends-on. [2]

**TDD 변형** (RED 자연 발생 — Task 2 후 V003/V004 가 issue-tracking/ 하위로 이동되면, `db/migration` 루트만 명시한 project-workflow IT 가 V003/V004 미스캔 → FK 참조 실패. 그 RED 상태를 GREEN 으로 전환).

**GREEN**. 각 파일의.
```kotlin
.locations("classpath:db/migration")
```
→
```kotlin
.locations(
    "classpath:db/migration/issue-tracking",
    "classpath:db/migration/project-workflow",
)
```
Flyway `locations(...)` 가 replace 동작이므로 양쪽 path 모두 명시 필수. learnings PR #24 와 동일 패턴.

**참고**. `WorkflowSchemesMigrationIntegrationTest.kt:101` 의 주석 `테스트: project-workflow Flyway 는 자체 classpath:db/migration 만 실행하므로 issue_types 없음` 은 의도된 분기 시나리오일 수 있음 — 단순 일괄 변경 대상이 아닌지 verifier 검증 필요.

**검증**.
- `./gradlew :modules:project-workflow:test` 전체 통과 (회귀 0)
- cross-BC 의존 IT 가 V001~V004 + V200~V202 모두 적용 후 동작

---

### Task 4. REFACTOR — KDoc 주석 정정 + plan/learnings append

**메타**.
- agent. `db-engineer`
- files.
  - `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/adapter/inbound/rest/IssueControllerTransitionIntegrationTest.kt` (KDoc 의 "V003~V004 는 classpath:db/migration (루트)" 주석 → "V001~V004 는 classpath:db/migration/issue-tracking" 정정)
  - `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/repository/IssueTestcontainersBase.kt` (KDoc 예시 정정 시)
  - `docs/plans/2026-05-28-blocker-2-hot-fix-slice-v003-v004-flyway-db-migrat.md` (§완료 기준 체크)
- depends-on. [3]

**TDD 변형 (정통 cycle 외)**. RED 분리 불필요 — 문서/주석 정정만 수행.

**검증**.
- `git grep -n "classpath:db/migration\"" backend/modules/issue-tracking/src/test/` 결과 0
- `git grep -n "classpath:db/migration\"" backend/modules/project-workflow/src/test/` 결과 0
- `./gradlew :modules:issue-tracking:test :modules:project-workflow:test` 전체 통과

---

## Plan 메타

- task 수. 4
- 예상 시간. task × 2~5분 = 약 12~15분 (단순 file/string 변경 위주)
- 예상 wave. Wave 0 (Task 1) → Wave 1 (Task 2) → Wave 2 (Task 3) → Wave 3 (Task 4). 직렬 의존 chain, 병렬 wave 없음.
- TDD 강제. yes. 정통 사이클 1건 (Task 1 RED → Task 2 GREEN), 변형 사이클 2건 (Task 3, Task 4 — RED 자연 발생 또는 RED 분리 불필요, commit message + plan 본문에 명시).
- TDD 강제 위반 위험. 낮음 — Task 1 가 명시적 RED, Task 2 가 명시적 GREEN. Task 3/4 의 변형 사유 명시.
- 추가 검증. ktlintCheck, detekt (Phase 0 정책). 통합테스트 부담 큼 (Testcontainers 19+ singleton 재기동).
- 회귀 가드. Task 1 의 `MigrationFileLayoutTest` 가 향후 V005+ 추가 시 자동 회귀 차단.

## 리뷰 결과

### controller inline self-eng-review (2026-05-28, hot-fix fast-track)

본 PR 은 hot-fix slice 특성상 `plan-eng-review` / `plan-ceo-review` 외부 호출 생략 + controller inline self-review (PR #21/#22/#23/#24/#28 패턴 일관). 정식 리뷰 체인 미실행 사유. (a) 도메인 모델 변경 0, (b) 결정 분기 없음 (D2 옵션 B 사용자 확정), (c) hot-fix slice 컨텍스트 (변경 본질 file rename + string replace) — 외부 리뷰의 발견 가치 < 호출 비용. 추후 게이트 2 단계에서 adversarial 외부 리뷰 (`code-reviewer` agent + `/review` gstack) 가 PR 단위 검증.

#### 통과 항목 (✅)

1. **TDD 강제**. Task 1 RED 명시 (`MigrationFileLayoutTest` 신규 + 실패 메시지 정확 인용) + Task 2 GREEN 명시 (file 이동 + IT location 정렬). Task 3 / Task 4 변형 사유 plan 본문에 명시 (RED 자연 발생 / RED 분리 불필요). PR #28 의 Task 5/7 변형 패턴 일관.
2. **BC 격리**. 코드 import 변경 0 (Flyway classpath path-string 만). 본 PR 의 변경은 `:modules:issue-tracking` main + test + `:modules:project-workflow` test (testRuntimeOnly 의존을 통한 마이그레이션 classpath 영향), cross-BC ArchUnit `BoundedContextIsolationTest` 영향 없음.
3. **depends-on chain**. Task 1 ← 2 ← 3 ← 4 직렬 의존. 순환 참조 0. 병렬 wave 없음 (hot-fix slice 의 자연스러운 직렬 sequence).
4. **누락 검증**. IT 우회 location 전수 grep 완료 (issue-tracking 5 occurrence + project-workflow 14 occurrence). 별도 누락 파일 없음.
5. **회귀 가드 본질**. Task 1 의 `MigrationFileLayoutTest` 가 향후 V005+ 추가 시 자동 회귀 차단. PR #25 의 ArchUnit 회귀 가드 도입 패턴 일관.
6. **운영 위험 해소**. prod boot 시 V003/V004 미스캔 → 자동 스캔 전환. Phase 0 진입 직전 실데이터 0, flyway_schema_history 충돌 없음.
7. **ADR 정합성**. `docs/adr/2026-05-26-bc-migration-prefix-policy.md` 의 후속 적용. 정책 충돌 0.
8. **단위 / 통합 부담**. Task 1 단위 테스트 (Spring resolver) 빠름. Task 3 (project-workflow 14 occurrence) 의 통합테스트 부담 큼 (Testcontainers 19+ singleton 재기동) — 의도된 비용, 회귀 본질 차단 가치 > 비용.

#### 주의 항목 (⚠️ CONCERN, BLOCKER 아님)

- **CONCERN-1**. `WorkflowSchemesMigrationIntegrationTest.kt:101` 의 주석 _"테스트. project-workflow Flyway 는 자체 classpath:db/migration 만 실행하므로 issue_types 없음"_ 은 의도된 분기 시나리오일 수 있음. Task 3 verifier 단계에서 _"단순 일괄 변경 대상인지 / 의도된 분기 보존 대상인지"_ 명시 확인. 분기 보존 대상이면 해당 IT 만 변경 제외.
- **CONCERN-2**. `IssueControllerTransitionIntegrationTest.kt:500` 의 3행 locations 중 `classpath:db/migration` 행 제거 — 그 라인의 원 의도는 V003 (issue_types) 의 직접 적용. V003 이 `db/migration/issue-tracking/` 하위로 이동되면 같은 BC 폴더 location 으로 자동 적용 가능. Task 2 verifier 가 통합테스트 통과 (회귀 0) 확인.
- **CONCERN-3**. `MigrationFileLayoutTest` 의 classpath 검증 범위 — `PathMatchingResourcePatternResolver` 가 issue-tracking 모듈 단독 실행 시 `:modules:issue-tracking` 의 main resources 만 검사. project-workflow IT 실행 시에는 cross-BC classpath 에 issue-tracking 의 resources 포함되므로 issue-tracking 의 V*.sql 도 검출. 의도된 동작 — 두 모듈 모두에서 회귀 가드 효과.

#### BLOCKER

없음.

#### 결정 권한 위임 항목

없음. D2 (scope 결정) 는 spec 단계에서 이미 사용자 확정 (옵션 B). 게이트 1 에서 추가 분기 없음.
