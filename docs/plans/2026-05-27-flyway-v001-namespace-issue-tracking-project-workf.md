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

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan — chore fast-track 으로 skip)

> chore type — plan 작성 후 review-plan 단계 skip 가능. /bts-codereview 단계에서 PR 단위 리뷰로 충분.

## 검증 기준 (NFR-3 충족)

- `./gradlew :backend:issue-tracking:test --tests IssueRepositoryTest` 2회 연속 0 fail.
- `./gradlew :backend:project-workflow:test` 0 fail (회귀 가드).
- `./gradlew :backend:issue-tracking:test`, `./gradlew :backend:project-workflow:test` 전체 통과.
- Flyway 로딩 시 `FlywayException: duplicate version` 발현 0 — 두 모듈 모두 격리된 locations 만 스캔.

## 관련 PR / 산출물

- PR #23 (선행). FR-IS-01 D4+D5 마무리. plan `docs/plans/2026-05-26-issue-tracking-bc-fr-is-01-cleanup-tests.md` §F10 에 본 PR 의 절차가 박힘.
- PR #14. issue-tracking BC 부트스트랩. V007→V001 모듈별 namespace 결정 (history.md PR #14 entry) 이 본 충돌의 출발점.

## 후속 trigger (본 PR 머지 후)

- `docs/plan/product/issue-tracking.md` §2.1.1 FR-IS-01 D5 `[~]` → `[x]` (NFR-3 측정 통과 시점).
- `docs/plans/2026-05-26-issue-tracking-bc-fr-is-01-cleanup-tests.md` §F10 의 "Flyway namespace 후속 cleanup PR" trigger 충족 기록.
- identity-access (V001~V006) 동일 namespace 정비는 별 chore PR 로 분리 — 본 PR 머지 후 별 trigger 결정.
