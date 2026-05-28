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

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
