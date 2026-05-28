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

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
