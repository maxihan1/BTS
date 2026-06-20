# FR-TT-02 — 이슈/사용자/기간별 시간 집계

> slug: fr-tt-02-worklog-aggregate
> type: backend
> agent: backend-engineer
> 생성: 2026-06-20

## Brief

사용자 원문: "FR-TT-02 진행. 다른 섹션에서 병행 작업 중이니 워크트리는 새로 만들어서 진행"

FR-TT-02 — 이슈/사용자/기간별 시간 집계 (agile-planning BC, Plan slug `agile/worklog-aggregate`).
선행 FR-TT-01(Worklog, #163/#166) 완료. `worklogs` 테이블 위에 집계(aggregate) 기능을 얹는다.

product/agile-planning.md §5.2 D단계:
- D1. 도메인 (backend-engineer)
- D2. 명세 — 집계 차원 (이슈/사용자/기간/프로젝트) (backend-engineer)
- D3. 데이터 모델 — 머티리얼라이즈드 뷰 검토 (db-engineer)
- D4. 백엔드 — `GET /api/v1/worklogs/aggregate?by=...` (backend-engineer)
- D5. 백엔드 테스트 (backend-engineer)
- D6. 프론트 UI — 표 + recharts 차트 (designer → frontend-engineer)
- D7. E2E + NFR (qa-engineer)

classify 결과: slug=fr-tt-02-worklog-aggregate, type=backend, agent=backend-engineer.
주의: classify의 primary_bc=issue-tracking은 키워드 추정이며, 실제 BC는 worklog 코드 위치로 확정한다.

## 도메인 정리

- **BC**: issue-tracking (FR-TT-01 ADR D1 계승 — worklog는 Issue 애그리거트 강결합. product의 agile-planning §5.2는 논리 라벨)
- **영향 엔티티**: `Worklog`(읽기 전용 집계 소스, 기존), `Issue`(project 필터 JOIN용)
- **신규 도메인 타입**: `WorklogAggregateDimension` enum(ISSUE/USER/PERIOD), 집계 결과 VO(`WorklogAggregateBucket`)
- **새 용어**: 워크로그 집계(Worklog Aggregate), 집계 차원(dimension), 기간 버킷(period bucket)
- **신규 스키마**: 없음(읽기 전용). 집계 전용 인덱스는 D3 성능 검토 후 조건부 추가
- **기존 결정 충돌**: 없음. FR-TT-01 ADR D4 가시성 규칙을 cross-issue 집계로 확장(누출 차단)

### Maxi 확정 결정 (3종)

1. **권한 범위** = 프로젝트 단위 + Project scope VIEW 권한. `?project=KEY` 필수, 미보유 403. 개별 이슈 보안수준은 미반영(FR-TT-01 D4 단순성 계승).
2. **계산 방식** = 실시간 SQL 집계(jOOQ GROUP BY). 머티리얼라이즈드 뷰 회피(권한 동적필터 불가 + REFRESH 관리 부담 + 현 규모 과함).
3. **집계 차원** = `by ∈ {issue, user, period}`. period는 `granularity ∈ {day, week, month}`. project는 그룹축 아닌 필수 필터.

- **엔드포인트**: `GET /api/v1/worklogs/aggregate?project=&by=&from=&to=&granularity=` (신규 cross-issue 컨트롤러)
- **관련 ADR**: [docs/adr/2026-06-20-worklog-aggregate-model.md](../adr/2026-06-20-worklog-aggregate-model.md) (생성됨)

## 스펙

전체 스펙. [docs/specs/2026-06-20-fr-tt-02-worklog-aggregate.md](../specs/2026-06-20-fr-tt-02-worklog-aggregate.md)

핵심 요약.
- `GET /api/v1/worklogs/aggregate?project=&by={issue|user|period}&granularity=&from=&to=` (읽기 전용, jOOQ GROUP BY)
- 권한 = `BROWSE` + `IssueScope.Project(key)` (VIEW 아님 — 목록/보고 성격). 미보유 403, 미인증 401
- worklogs JOIN issues JOIN projects, 셋 다 `deleted_at IS NULL`. 응답 = 버킷 배열 + `totalTimeSpentSeconds`
- by=issue→issueKey 라벨 / by=user→displayName(UserLookupPort, 실패 시 "") / by=period→date_trunc UTC 버킷
- 신규 스키마 0(인덱스는 NFR 미달 시 조건부). NFR: 5,000건 p95 < 500ms

ADR 정정. 권한 표기 VIEW → **BROWSE** (집계는 cross-issue 목록 성격, BROWSE_PROJECT 매트릭스가 정확).

## Brainstorming Check

✅ 통과 (self-review 1-pass — 정의된 FR이라 office-hours 스킵, Maxi 3종 결정으로 핵심 갈림길 사전 확정).
보강 항목. period sparse 정책 · granularity 오용 관대 처리 · total 필드 · 프로젝트 존재 probe 방지(404→빈결과) · 타임존 UTC 고정 · 페이지네이션 부재 명시.

## Plan

레이어별 3 task 직렬(리포지토리 → 서비스 → 컨트롤러). 신규 스키마 0. 패키지 `com.bts.issue.worklog.aggregate.{domain,repository,application,web}`.

### Task 1. 집계 도메인 타입 + 리포지토리 GROUP BY 쿼리

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/worklog/aggregate/domain/WorklogAggregate.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/worklog/aggregate/repository/WorklogAggregateRepository.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/worklog/aggregate/repository/WorklogAggregateRepositoryTest.kt`]
- depends-on: []

**RED**: `WorklogAggregateRepositoryTest` (Testcontainers — 실 DB 시드).
- `by=issue` 두 이슈 worklog SUM 정확 + worklogCount
- `by=user` 두 author SUM
- `by=period` granularity day/week/month 각 date_trunc 버킷 경계
- `from`/`to` 필터 경계(to 당일 포함 = to+1일 00:00 UTC exclusive)
- 다른 프로젝트 worklog 제외(project key 필터)
- `deleted_at` 있는 worklog/issue 제외
- 빈 결과 → `emptyList`
- 실패 메시지(예상): `WorklogAggregateRepository` 클래스 없음

**GREEN**:
- `enum WorklogAggregateDimension { ISSUE, USER, PERIOD }`, `enum AggregateGranularity { DAY, WEEK, MONTH }`
- `data class WorklogAggregateRow(groupKey: String, timeSpentSeconds: Long, worklogCount: Int)` — 리포는 SQL 집계만. groupKey = by=issue→`issues.key` / by=user→`author_id::text` / by=period→`to_char(date_trunc(granularity, started_at), 'YYYY-MM-DD')`. displayName(cross-BC)은 서비스 책임.
- `WorklogAggregateRepository.aggregate(projectKey, dimension, granularity, from, to): List<WorklogAggregateRow>` — jOOQ: `worklogs w JOIN issues i ON ... AND i.deleted_at IS NULL JOIN projects p ON ... AND p.deleted_at IS NULL WHERE p.key=:project AND w.deleted_at IS NULL [AND w.started_at >= :from] [AND w.started_at < :toExcl] GROUP BY <dim>`. `@Transactional(readOnly=true)`.
- period date_trunc는 `DSL.field("date_trunc({0}, {1})", ...)` 파라미터 바인딩(SQL 인젝션 방지 — granularity는 enum→안전 리터럴).

**REFACTOR**: dimension별 groupKey 표현식 헬퍼 추출 + KDoc.

**검증**: `cd backend && ./gradlew :modules:issue-tracking:test --tests "*WorklogAggregateRepositoryTest"`

### Task 2. WorklogAggregateService — 권한 + displayName + total + 정렬

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/worklog/aggregate/application/WorklogAggregateService.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/worklog/aggregate/application/WorklogAggregateServiceTest.kt`]
- depends-on: [1]

**RED**: `WorklogAggregateServiceTest` (mockk — repo/resolver/userLookup stub).
- 권한 없음(`resolver.hasPermission(actor, BROWSE, IssueScope.Project(key))=false`) → `IssueAccessDeniedException`(403)
- 권한 있음 → repo 호출 → 버킷 매핑
- `by=user` → `UserLookupPort.findDisplayNamesByIds`로 label 채움, 미존재 시 빈 문자열(fail-safe). exactly 호출 1회(불필요 조회 0 — by≠user면 호출 안 함)
- `by=issue|period` → label = groupKey
- `totalTimeSpentSeconds` = 버킷 합
- 정렬: issue/user DESC(동률 label ASC), period ASC

**GREEN**:
- `aggregate(actorId, projectKey, dimension, granularity, from, to): WorklogAggregateResult`
- 권한 먼저 검증(resolver) → 실패 `IssueAccessDeniedException`. **리소스/repo 조회보다 앞**(probe 방지).
- `repo.aggregate(...)` → rows
- `by=user`면 authorId(UUID) 수집 → `userLookup.findDisplayNamesByIds(ids)` → label 매핑(없으면 `""`)
- 정렬 + total 계산 → `WorklogAggregateResult(buckets, totalTimeSpentSeconds)`, `WorklogAggregateBucket(key, label, timeSpentSeconds, worklogCount)`

**REFACTOR**: label 해석 + 정렬 helper 분리.

**검증**: `cd backend && ./gradlew :modules:issue-tracking:test --tests "*WorklogAggregateServiceTest"`

### Task 3. WorklogAggregateController + DTO + 입력 검증 + 예외

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/worklog/aggregate/web/WorklogAggregateController.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/worklog/aggregate/web/dto/WorklogAggregateResponse.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/worklog/aggregate/web/WorklogAggregateControllerTest.kt`]
- depends-on: [2]

**RED**: `WorklogAggregateControllerTest` (HTTP 통합 — Testcontainers + 실 권한 stub).
- `by=issue|user|period` 각 200 + 응답 구조(data.buckets/total/by/granularity/from/to)
- 400: `project` 누락 / `by` 무효 / `granularity` 무효 / `from`·`to` 형식 무효 / `from > to`
- 401: 미인증(actor 추출 먼저 → 리소스 probe 방지)
- 403: 권한 없는 프로젝트
- worklog 0 → 200 + `buckets: []`, `totalTimeSpentSeconds: 0`

**GREEN**:
- `@RestController @RequestMapping("/api/v1/worklogs")`, `@GetMapping("/aggregate")`
- 쿼리 파라미터는 **String으로 받아 수동 검증**(enum 자동 바인딩의 MethodArgumentTypeMismatch→500 변질 회피, FR-TT-01 컨트롤러 선례). `project` blank→400, `by` enum 파싱 무효→400, `granularity` 기본 day·무효→400, `from`/`to` `LocalDate.parse` 무효→400, `from>to`→400.
- `actor = CurrentActor.current()` 최상단(미인증 401, probe 방지).
- `service.aggregate(...)` → `WorklogAggregateResponse.from(...)` → `DataResponse`.
- 예외 매핑: 기존 worklog 패턴 재사용 — `ResponseStatusException`(400), `IssueAccessDeniedException`→403, `IssueNotFoundException`→404. **신규 컨트롤러라 기존 `WorklogExceptionHandler`가 `@RestControllerAdvice` basePackage로 커버하는지 확인**(미커버 시 전용 핸들러 추가 — catch-all이 401/400을 500으로 삼키지 않게 명시 핸들러, 메모리 catch-all-exceptionhandler-swallows / bulk-operation-exception-handler 패턴).

**REFACTOR**: 파라미터 검증 helper 추출 + KDoc(엔드포인트 표).

**검증**: `cd backend && ./gradlew :modules:issue-tracking:test --tests "*WorklogAggregateControllerTest"` + 모듈 전체 `./gradlew :modules:issue-tracking:test`

## Plan 메타

- task 수: 3 (각 TDD 사이클, 레이어별)
- 예상 wave: 3 (직렬 — repo→service→controller 코드 의존, T1→T2→T3)
- 예상 시간: 약 12~18분(직렬, Testcontainers 통합 테스트 2건 포함)
- TDD 강제: yes (test 커밋이 feat 커밋보다 먼저)
- 범위: 백엔드 D1~D5만. D6(프론트 표+recharts)/D7(E2E)는 후속 PR
- 추가 검증: ktlint + detekt(aggregate) + 모듈 전체 test. init_codegen 미러 불요(신규 스키마 0)
- 리스크: (1) jOOQ date_trunc 표현식 — granularity enum→안전 리터럴 바인딩. (2) 신규 컨트롤러 예외 핸들러 커버리지 — basePackage 확인 필수. (3) period 버킷 타임존 UTC 고정(spec E6).

## 리뷰 결과 (← /bts-review-plan 채움)
