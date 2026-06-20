# FR-TT-01 — Worklog (추정/실제/잔여 시간)

> slug: fr-tt-01-worklog
> type: backend
> agent: backend-engineer
> primary_bc: issue-tracking
> 생성: 2026-06-20

## Brief

FR-TT-01 — Worklog (추정/실제/잔여 시간). 이슈별 작업 시간 기록.

- 원문: "fr-tt-01 진행하자 다른 섹션에서 병행해서 작업하고 있으니 워크트리 새로 만들어서 진행해"
- classify: type=backend, agent=backend-engineer, primary_bc=issue-tracking, slug=fr-tt-01-worklog
- SDD 참조: §5.9 Worklog 데이터 모델, 02-requirements FR-TT-01
- product: docs/plan/product/agile-planning.md §5.1
- **BC 배치 권장**: agile-planning BC 소속 FR이나 issue-tracking BC에 구현 (Worklog 등록 시 Issue.time_spent/remaining_estimate 갱신 = Issue 애그리거트 결합, FR-PL-01/02 선례 동일). bts-domain에서 정식 확정 → 게이트 1 Maxi 확인.

## 도메인 정리

- **BC**: issue-tracking (논리 BC 라벨은 agile-planning 유지 — FR-PL 선례, FR 카운트 변동 없음)
- **신규 엔티티**: Worklog (Issue 애그리거트의 자식)
- **신규 issue 컬럼**: original_estimate_seconds, time_spent_seconds, remaining_estimate_seconds (SDD §5.9/§5.1 명시하나 미마이그레이션)
- **새 용어 (glossary 추가 후보, Maxi 승인 대기)**: Worklog(작업 로그), Original Estimate(원 추정), Time Spent(실제 소요), Remaining Estimate(잔여 추정), Log Work(작업 기록)
- **기존 결정 충돌**: 없음 (worklog 관련 ADR/glossary 항목 0건, 신규 도메인 영역)
- **관련 ADR**: [docs/adr/2026-06-20-worklog-time-tracking-model.md](../adr/2026-06-20-worklog-time-tracking-model.md) (생성됨)

### Maxi 결정 (도메인 게이트)

1. **BC 배치** → issue-tracking (Issue.time_spent/remaining_estimate 갱신 결합 + FR-PL 선례)
2. **Worklog 가시성** → 이슈 권한만 따름 (항목별 PUBLIC/TEAM_ONLY/PRIVATE 제외, 후속 FR 위임)
3. **잔여 추정 동작** → 자동 차감 + 수동 override (기본 max(0, remaining − time_spent), 명시 new_remaining 시 설정)

### 마이그레이션

- 다음 V번호: **V027** (issue-tracking 하위 폴더, 자체 V-series V001~V026) — 머지 직전 재확인 (동시 브랜치 충돌 방지)
- init_codegen.sql 미러 필수 (jOOQ 코드젠)

### PR 분할 (기본 — 게이트1 확인)

- 백엔드 D1~D5 우선 1 PR (#163), 프론트 D6/D7 후속 PR — FR-WT-01/FR-MV-01 선례

## 스펙

전체 스펙. [docs/specs/2026-06-20-fr-tt-01-worklog.md](../specs/2026-06-20-fr-tt-01-worklog.md)

핵심 시나리오 요약.
- `POST /issues/{key}/worklogs`로 작업시간 기록 → issue.time_spent = SUM 재집계, remaining 자동 차감(또는 newRemaining override)
- `PATCH /issues/{key}`로 originalEstimate/remainingEstimate 수동 설정(JsonNullable 3-state, FR-PL-01 패턴), time_spent는 파생·읽기전용
- GET/PATCH/DELETE worklog — VIEW/UPDATE+본인 권한, 단일 트랜잭션, no-bump(OCC 회피), 하드삭제
- V027 신규 worklogs 테이블 + issues 3컬럼, init_codegen 미러

## Brainstorming Check

✅ 통과 (자체 적대적 갭 분석). 주요.
- **G1 반영**: IssueResponse 3필드 추가 팬아웃 → plan에 전 생성지점/테스트 task 필수
- **G2 게이트1 위임**: 추정 changelog 통합 기본값(FR9, 일관성) vs 미통합 대안
- G3~G6 반영: 롤업 범위외·페이지네이션 없음·Instant 재사용·comment 상한 plan확정

## Plan

> 범위 D1~D5 (백엔드). 모든 경로 prefix: `backend/modules/issue-tracking/src/{main,test}/kotlin/com/bts/issue/`
> G2(추정 changelog 통합)는 게이트1 기본값=통합. Maxi가 "미통합" 선택 시 Task 4 제거 + Task 5 history 부분 삭제.

### Task 1. V027 마이그레이션 — worklogs 테이블 + issues 추정 3컬럼

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/issue-tracking/src/main/resources/db/migration/issue-tracking/V027__worklogs_and_estimates.sql`, `backend/modules/issue-tracking/src/main/resources/db/codegen/init_codegen.sql`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/worklog/WorklogSchemaMigrationTest.kt`]
- depends-on: []

**RED**: `WorklogSchemaMigrationTest` (WatcherSchemaMigrationTest 패턴) — 마이그레이션 후 `worklogs` 테이블 + 컬럼(id/issue_id/author_id/time_spent_seconds/started_at/comment/created_at/updated_at/**deleted_at**), CHECK(time_spent_seconds>0), FK issue_id ON DELETE CASCADE, 인덱스 2종 존재 + `issues`에 original_estimate_seconds/time_spent_seconds(NOT NULL DEFAULT 0)/remaining_estimate_seconds 존재 단언. 실패: 테이블/컬럼 없음.

**GREEN**: V027 작성(스펙 §데이터 모델 SQL 그대로) + **init_codegen.sql 미러**(worklogs CREATE + issues 3컬럼 ADD). V번호는 머지 직전 재확인.

**REFACTOR**: 인덱스/CHECK 주석.

**검증**: `./gradlew :backend:modules:issue-tracking:test --tests "*WorklogSchemaMigrationTest"` (jOOQ 코드젠 후 WORKLOGS/ISSUES 신컬럼 생성 확인).

### Task 2. Worklog 도메인 + WorklogRepository (jOOQ CRUD + SUM 재집계)

**메타**.
- agent: `backend-engineer`
- files: [`.../worklog/domain/Worklog.kt`, `.../worklog/repository/WorklogRepository.kt`, `.../test/.../worklog/repository/WorklogRepositoryIntegrationTest.kt`]
- depends-on: [1]

**RED**: `WorklogRepositoryIntegrationTest` (Testcontainers) — insert 후 findByIssueId(deleted_at IS NULL 필터, started_at desc 정렬), update(time_spent/started_at/comment), **소프트 delete**(deleted_at=NOW(), WHERE 명시, 행수 반환), `sumTimeSpentByIssue(issueId)`(deleted_at IS NULL 합산, 소프트삭제분 제외). 실패: WorklogRepository 없음.

**GREEN**: Worklog data class(id/issueId/authorId/timeSpentSeconds/startedAt/comment/createdAt/updatedAt) + jOOQ 리포지토리(IssueWatcherRepository 패턴 — `@Transactional`, insertInto/selectFrom/update). 소프트삭제=`update(WORKLOGS).set(DELETED_AT, now).where(...)`. findByIssueId/sum 모두 `DELETED_AT.isNull` 조건. sum은 `dsl.select(coalesce(sum(WORKLOGS.TIME_SPENT_SECONDS),0))`.

**REFACTOR**: 매퍼 추출, KDoc.

**검증**: `--tests "*WorklogRepositoryIntegrationTest"`.

### Task 3. Issue 추정 필드 — 도메인 + IssueRepository(추정 PATCH + no-bump 롤업 + 읽기)

**메타**.
- agent: `backend-engineer`
- files: [`.../domain/Issue.kt`, `.../repository/IssueRepository.kt`, `.../application/IssueApplicationRequests.kt`, `.../test/.../repository/IssueEstimateRepositoryIntegrationTest.kt`]
- depends-on: [1]

**RED**: `IssueEstimateRepositoryIntegrationTest` — (a) 읽기: findByKey가 original/time_spent/remaining 반환, (b) 추정 PATCH(EstimatePatch 3-state Unchanged/Clear/Set)로 original/remaining 갱신 + **version 증가**(updateFields 경로), (c) `applyWorklogRollup(issueId, timeSpent, remaining)` 호출 시 두 컬럼 갱신 + **version 불변**(no-bump). 실패: 필드/메서드 없음.

**GREEN**:
- Issue.kt: `originalEstimateSeconds: Int? = null`, `timeSpentSeconds: Int = 0`, `remainingEstimateSeconds: Int? = null` (data class **끝에 default 값** — 기존 생성 지점 영향 최소화, [[plan-files-constructor-injection-existing-tests]]).
- IssueApplicationRequests.kt: `EstimatePatch` sealed interface(Unchanged/Clear/Set(Int)) — DatePatch 모방. AppUpdateIssueRequest에 originalEstimate/remainingEstimate EstimatePatch 필드 추가.
- IssueRepository.kt: 읽기 매핑 3컬럼 추가, `applyEstimatePatch` 헬퍼(updateFields, version 증가 경로에 합류), `applyWorklogRollup` no-bump 메서드(version 미증가·expectedVersion 미요구, [[no-bump-sidecar-version-double-bump]]).
- **CONCERN-1/2 반영 — applyWorklogRollup은 원자 SQL**:
  - time_spent: `UPDATE issues SET time_spent_seconds = (SELECT COALESCE(SUM(time_spent_seconds),0) FROM worklogs WHERE issue_id=:id)` — 서브쿼리로 row lock 아래 원자 재집계(앱 SUM 금지, CONCERN-2).
  - remaining 자동차감: 같은 UPDATE에서 `remaining_estimate_seconds = CASE WHEN remaining_estimate_seconds IS NULL THEN NULL ELSE GREATEST(0, remaining_estimate_seconds - :timeSpent) END` — read-modify-write 금지(동시 POST lost-update 차단, CONCERN-1).
  - remaining override: `remaining_estimate_seconds = :newRemaining` 직접 set 변형.
  - `updated_at = NOW()` 갱신, version 미증가.
  - **`RETURNING time_spent_seconds, remaining_estimate_seconds`** — 차감 후 새 값 회수(T5 history 기록용).
  - edit/delete 경로: time_spent만 재집계(remaining 미조정, FR4/FR5).

**REFACTOR**: 헬퍼 KDoc.

**검증**: `--tests "*IssueEstimateRepositoryIntegrationTest"` + 모듈 컴파일(기존 Issue 생성 지점 grep `Issue(` 영향 확인).

### Task 4. IssueChangeDetector — 추정 필드 changelog 추출 (G2 기본값=통합)

**메타**.
- agent: `backend-engineer`
- files: [`.../history/IssueChangeDetector.kt`, `.../test/.../history/IssueChangeDetectorTest.kt`]
- depends-on: [3]

**RED**: detector 테스트 — originalEstimate/remainingEstimate 변경 시 change item 생성, **timeSpent 변경은 미생성**(noise 회피). 실패: 추출기 없음.

**GREEN**: SCALAR_FIELD_EXTRACTORS에 `"originalEstimate" to { it.originalEstimateSeconds?.toString() }`, `"remainingEstimate" to { it.remainingEstimateSeconds?.toString() }` 추가. timeSpent는 미추가.

**REFACTOR**: -

**검증**: `--tests "*IssueChangeDetectorTest"`.

### Task 5. WorklogService — 오케스트레이션(권한·집계·자동차감·이력)

**메타**.
- agent: `backend-engineer`
- files: [`.../worklog/application/WorklogService.kt`, `.../test/.../worklog/application/WorklogServiceIntegrationTest.kt`]
- depends-on: [2, 3, 4]

**RED**: `WorklogServiceIntegrationTest` (Testcontainers) — create(자동차감 remaining=max(0,r−t)), create(newRemaining override), create(remaining NULL 유지), edit(time_spent SUM 재집계·remaining 미조정), delete(재집계·remaining 미복원), 권한거부 403(UPDATE 없음), 404순서(미인증401→권한403→존재404), 타인 worklog edit/delete 403, original/remaining changelog 기록, **동시 create 2건 lost-update 없음(CONCERN-1 회귀가드 — 순차 적용 시 remaining 정확)**. 실패: WorklogService 없음.

**GREEN**: `@Service @Transactional` WorklogService — actor 추출 우선([[auth-extraction-before-resource-lookup]]) → 권한(IssuePermission.UPDATE, IssueScope.Issue) → 이슈 resolve(deleted_at 필터, before 스냅샷) → WorklogRepository CRUD → `applyWorklogRollup`(원자 SQL, no-bump, RETURNING으로 새 time_spent/remaining 회수) → after = before.copy(timeSpent=회수값, remaining=회수값) → IssueHistoryRecorder.record(before, after) (detector가 remaining 변경만 감지, timeSpent 미감지). edit/delete는 author 한정 + remaining 미조정.

**REFACTOR**: 권한 헬퍼 추출(IssueWatcherService 패턴).

**검증**: `--tests "*WorklogServiceIntegrationTest"`.

### Task 6. 추정 PATCH 배선 + IssueResponse 필드 (G1)

**메타**.
- agent: `backend-engineer`
- files: [`.../adapter/inbound/rest/UpdateIssueRequest.kt`, `.../adapter/inbound/rest/IssueController.kt`, `.../adapter/inbound/rest/IssueResponse.kt`, `.../test/.../adapter/inbound/rest/IssueEstimatePatchIntegrationTest.kt`]
- depends-on: [3]

**RED**: `IssueEstimatePatchIntegrationTest` (MockMvc) — PATCH `{originalEstimateSeconds, remainingEstimateSeconds}` 3-state(미변경/null/값), GET 이슈가 original/timeSpent/remaining 노출, timeSpent는 PATCH 무시(읽기전용), 음수 400. 실패: 필드 없음.

**GREEN**: UpdateIssueRequest에 `originalEstimateSeconds/remainingEstimateSeconds: JsonNullable<Int>` 추가, IssueController `toEstimatePatch` 헬퍼(toDatePatch 모방) + AppUpdateIssueRequest 배선, IssueResponse data class에 3필드 추가 + `from()` 본문에서 issue 필드 읽기(**호출처 무변경** — from은 Issue 객체 수신). **G1**: IssueResponse 직접 생성 테스트/픽스처 grep `IssueResponse(` 전수 보정.

**REFACTOR**: -

**검증**: `--tests "*IssueEstimatePatchIntegrationTest"` + 모듈 컴파일.

### Task 7. WorklogController + DTO — REST 엔드포인트

**메타**.
- agent: `backend-engineer`
- files: [`.../worklog/web/WorklogController.kt`, `.../worklog/web/dto/WorklogResponse.kt`, `.../worklog/web/dto/AddWorklogRequest.kt`, `.../worklog/web/dto/UpdateWorklogRequest.kt`, `.../test/.../worklog/web/WorklogControllerIntegrationTest.kt`]
- depends-on: [5]

**RED**: `WorklogControllerIntegrationTest` (MockMvc) — POST 201(WorklogResponse), GET 200(worklogs[] + summary{original/timeSpent/remaining}), PATCH 200, DELETE 204, timeSpent≤0 400, 권한 403, 미존재 404, 다른 이슈 worklogId 404. 실패: 컨트롤러 없음.

**GREEN**: `@RestController` `/api/v1/issues/{key}/worklogs` (IssueWatcherController 패턴) — CurrentActor.current() 우선, WorklogService 위임, WorklogResponse 매핑. startedAt은 Instant(기존 WebMvcConfigurer ISO 재사용, 신규 컨버터 금지 [[fr-vr-04-release-notes-done]]).

**REFACTOR**: 매퍼 정리, KDoc.

**검증**: `--tests "*WorklogControllerIntegrationTest"` + `./gradlew :backend:modules:issue-tracking:test` 전체 + ktlintCheck + detekt.

## Plan 메타

- task 수: 7
- 예상 wave: 5 (W1: T1 / W2: T2,T3 / W3: T4,T6 / W4: T5 / W5: T7) — 단, 단일 Gradle 모듈 test 컴파일 직렬화 요인([[bts-plan-wave-gradle-module-compile]])
- TDD 강제: yes (각 task RED→GREEN→REFACTOR)
- 병렬 dispatch: depends-on + files 교집합으로 wave 계산. T3↔T6, T4↔T6 파일 무충돌 확인
- 추가 검증: ktlintCheck, detekt(aggregate, baseline 동결만 [[backend-detekt-lint-debt-unmasked]]), 모듈 전체 test
- G2 분기: changelog 미통합 시 T4 제거 + T5 history 부분 삭제 (게이트1 확정 반영)

## 리뷰 결과

### plan-eng-review (집중 독립 엔지니어링 리뷰, 2026-06-20)

type=backend → autoplan 대신 eng 집중 리뷰([[bts-review-plan-autoplan-overkill]]).

- 🔴 **CONCERN-1 (반영)**: 잔여 자동 차감 read-modify-write → 동시 POST lost-update(TOCTOU). 원자 SQL `GREATEST(0, remaining - :t)` + RETURNING으로 수정(T3/T5). 회귀가드 테스트 추가(T5).
- 🟡 **CONCERN-2 (반영)**: time_spent 재집계를 단일 UPDATE 서브쿼리(row lock 원자)로. 앱 SUM-후-set 금지(T3).
- 🟢 time_spent SUM-재집계(증분 아님) → 동시성 정합. row lock 직렬화 확인.
- 🟢 IssueResponse 팬아웃 — from(issue) 도메인 객체 수신이라 호출처 무영향, IssueResponse.kt 자체 + 직접생성 테스트만(T6 grep).
- 🟢 권한 VIEW/UPDATE 재사용 — 신규 권한코드 0 → 시드/마이그레이션 테스트 카운트 무영향([[fr-pm-permission-seed-migration-test-coupling]] 회피).
- 🟢 V027 — 병렬 브랜치(fr-nt-03) issue-tracking 마이그레이션 무변경, V026 최신 확인. 머지 직전 재확인.
- 🟢 TDD red→green 순서 + 메타블록 wave 정확(T3↔T6, T4↔T6 파일 무충돌).
- ⚠️ 주의(비차단): worklog 작성 권한=UPDATE 재사용은 의도적 단순화(Jira의 "Work On Issues" 별도권한 미도입). 후속 FR로 세분 가능.
- **BLOCKER: 없음**.

### G2 — Maxi 확정 (게이트1, 2026-06-20)

추정 changelog **통합 확정** → Task 4 유지 + T5 history 기록 유지. 게이트1 승인 → 구현 진행.
