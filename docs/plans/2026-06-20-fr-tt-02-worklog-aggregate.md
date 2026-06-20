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
- **NFR(N4)**: 5,000건 시드 후 `by=issue` 집계 p95 < 500ms 측정(`@Tag("performance")` 또는 측정 로그). 미달 시 GREEN에서 집계 전용 인덱스 추가.
- 실패 메시지(예상): `WorklogAggregateRepository` 클래스 없음

**GREEN**:
- 도메인 파일(`WorklogAggregate.kt`)에 **enum + VO 전부** 정의(N2 — Bucket/Result도 여기, Task 2가 의존):
  - `enum WorklogAggregateDimension { ISSUE, USER, PERIOD }`
  - `enum AggregateGranularity(val sqlLiteral: String) { DAY("day"), WEEK("week"), MONTH("month") }` — **SQL 리터럴을 enum 속성으로 고정**(화이트리스트, 외부 입력 비삽입)
  - `data class WorklogAggregateRow(groupKey: String, timeSpentSeconds: Long, worklogCount: Int)`
  - `data class WorklogAggregateBucket(key: String, label: String, timeSpentSeconds: Long, worklogCount: Int)`
  - `data class WorklogAggregateResult(buckets: List<WorklogAggregateBucket>, totalTimeSpentSeconds: Long)`
- 리포는 SQL 집계만. groupKey = by=issue→`issues.key` / by=user→`author_id::text` / by=period→`to_char(date_trunc(<리터럴>, started_at), 'YYYY-MM-DD')`. displayName(cross-BC)은 서비스 책임.
- `WorklogAggregateRepository.aggregate(projectKey, dimension, granularity, from, to): List<WorklogAggregateRow>` — jOOQ: `worklogs w JOIN issues i ON ... AND i.deleted_at IS NULL JOIN projects p ON ... AND p.deleted_at IS NULL WHERE p.key=:project AND w.deleted_at IS NULL [AND w.started_at >= :from] [AND w.started_at < :toExcl] GROUP BY <dim>`. `@Transactional(readOnly=true)`.
- **★B2 — date_trunc는 bind 파라미터 금지**: PostgreSQL은 `date_trunc($1, col)`의 첫 인자 bind를 거부(unknown 타입 추론 실패)한다. granularity는 **enum→`DSL.inline(granularity.sqlLiteral)` 안전 리터럴**로 삽입(`AggregateGranularity` 화이트리스트라 인젝션 불가). `DSL.field("date_trunc({0},{1})", DSL.param(...))` 같은 bind 경로 사용 금지.
- **★C1 — SUM nullable 처리**: `SUM(time_spent_seconds)`는 행 0건 그룹에서 NULL/jOOQ `BigDecimal`. `record.get(sumField)?.toLong() ?: 0L`로 받음(`!!` 금지, 절대규칙 §1.3).
- project=BTS, by=issue/user/period 모든 분기에서 cartesian product 없음(worklogs→issues→projects는 각 N:1, base=worklogs라 SUM 정확).

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
- `by=user` → `UserLookupPort.findDisplayNamesByIds`로 label 채움, 미존재 시 빈 문자열(fail-safe). exactly 호출 1회
- **C5** — `by=issue`·`by=period` 케이스: `verify(exactly = 0) { userLookup.findDisplayNamesByIds(any()) }`(불필요 cross-BC 조회 0 박제)
- `by=issue|period` → label = groupKey
- `by=user` groupKey(UUID text)→UUID 변환은 DB cast 값이라 안전하나 `runCatching`/명시 변환으로 `!!` 회피(C2)
- `totalTimeSpentSeconds` = 버킷 합
- 정렬: issue/user DESC(동률 label ASC), period ASC
- **C3** — `from > to` 검증은 컨트롤러 책임(Task 3). 서비스 직접 호출자 없음. 서비스는 전달된 from/to를 그대로 WHERE에 적용(방어 불필요, but 단위 테스트에 from>to→빈 결과 1건 박제로 의도 명시)

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
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/worklog/aggregate/web/WorklogAggregateController.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/worklog/aggregate/web/dto/WorklogAggregateResponse.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/worklog/aggregate/web/WorklogAggregateExceptionHandler.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/worklog/aggregate/web/WorklogAggregateControllerTest.kt`]
- depends-on: [2]

**RED**: `WorklogAggregateControllerTest` (HTTP 통합 — Testcontainers, 시드는 **Task 1 리포지토리 테스트의 시드 헬퍼 재사용**(C4 — 빈 배열 200으로 가짜그린 회피), 권한은 stub resolver 주입).
- `by=issue|user|period` 각 200 + 응답 구조(data.buckets/total/by/granularity/from/to)
- 400: `project` 누락 / `by` 무효 / `granularity` 무효 / `from`·`to` 형식 무효 / `from > to`
- **C6** — `by=issue` + `granularity=month` 동반 전달 → 200 + 응답 `granularity=null`(E2 관대 처리, 400 아님)
- 401: 미인증 **+ nil-UUID·비-UUID actor(B3)** — actor 추출 먼저 → 리소스 probe 방지
- 403: 권한 없는 프로젝트(누출 차단)
- worklog 0 → 200 + `buckets: []`, `totalTimeSpentSeconds: 0`

**GREEN**:
- `@RestController @RequestMapping("/api/v1/worklogs")`, `@GetMapping("/aggregate")`
- 쿼리 파라미터는 **String으로 받아 수동 검증**(enum 자동 바인딩의 MethodArgumentTypeMismatch→500 변질 회피, FR-TT-01 컨트롤러 선례). `project` blank→400, `by` enum 파싱 무효→400, `granularity` 기본 day·무효→400, `from`/`to` `LocalDate.parse` 무효→400, `from>to`→400. `by≠period`면 granularity 무시(응답 null).
- `actor = CurrentActor.current()` 최상단(미인증·nil-UUID 401, probe 방지).
- `service.aggregate(...)` → `WorklogAggregateResponse.from(...)` → `DataResponse`.
- **★B1 — 전용 예외 핸들러 신규(확정)**: `WorklogAggregateExceptionHandler` `@RestControllerAdvice(basePackages = ["com.bts.issue.worklog.aggregate.web"])`. **검증 결과 신규 컨트롤러는 기존 핸들러 둘 다 미커버**(`WorklogExceptionHandler`=assignableTypes WorklogController 한정 / `IssueExceptionHandler`=basePackages `...adapter.inbound.rest` 한정). BC 선례(`LinkExceptionHandler`/`VersionExceptionHandler`/`BulkOperationExceptionHandler`의 basePackages 패턴)와 일관. 매핑: `ResponseStatusException`은 상태코드 보존(400/401), `IssueAccessDeniedException`→403. catch-all `Exception`→500 핸들러는 두지 않음(401/400 삼킴 방지, 메모리 catch-all-exceptionhandler-swallows).

**REFACTOR**: 파라미터 검증 helper 추출 + KDoc(엔드포인트 표).

**검증**: `cd backend && ./gradlew :modules:issue-tracking:test --tests "*WorklogAggregateControllerTest"` + 모듈 전체 `./gradlew :modules:issue-tracking:test`

## Plan 메타

- task 수: 3 (각 TDD 사이클, 레이어별)
- 예상 wave: 3 (직렬 — repo→service→controller 코드 의존, T1→T2→T3)
- 예상 시간: 약 12~18분(직렬, Testcontainers 통합 테스트 2건 포함)
- TDD 강제: yes (test 커밋이 feat 커밋보다 먼저)
- 범위: 백엔드 D1~D5만. D6(프론트 표+recharts)/D7(E2E)는 후속 PR
- 추가 검증: ktlint + detekt(aggregate) + 모듈 전체 test. init_codegen 미러 불요(신규 스키마 0)
- 신규 파일 4개(`WorklogAggregate.kt`/`...Repository.kt`/`...Service.kt`/`...Controller.kt`/`...ExceptionHandler.kt`/`...Response.kt`) L1 한국어 헤더 주석 필수(N3, 글로벌 CLAUDE.md §6)
- 리스크(eng-review로 해소): (1) date_trunc bind param→`DSL.inline` 리터럴 확정(B2). (2) 신규 컨트롤러 전용 예외 핸들러 확정(B1, 코드 검증 완료). (3) period 버킷 타임존 UTC 고정(spec E6).

## 리뷰 결과

### plan-eng-review (독립 backend 적대적 리뷰, 2026-06-20)

초기 종합 판정 **BLOCKED** → plan 보강으로 **해소**(아래 전부 반영 완료).

**BLOCKER 2건 (반영)**.
- **B1. 예외 핸들러 미커버** — 신규 `WorklogAggregateController`는 `WorklogExceptionHandler`(assignableTypes=WorklogController)·`IssueExceptionHandler`(basePackages `...adapter.inbound.rest`) 둘 다 미커버 → 401/403이 500으로 변질 위험. **코드 직접 검증 완료**. → Task 3에 전용 `WorklogAggregateExceptionHandler`(basePackages `...worklog.aggregate.web`) 확정. BC 선례(link/version/bulk) 일관.
- **B2. date_trunc bind param 오류** — `date_trunc($1, col)`은 PostgreSQL이 거부. → `DSL.inline(AggregateGranularity.sqlLiteral)` 안전 리터럴(enum 화이트리스트)로 정정.

**CONCERN 6건 (반영)**. C1 SUM nullable `?:0L`(`!!` 금지) / C3 from>to 컨트롤러 책임 명시 / C4 Task1 시드 헬퍼 재사용(빈배열 가짜그린 회피) / C5 by≠user면 userLookup 0회 박제 / C6 by=issue+granularity→null 케이스 / B3(격하) nil-UUID 401 케이스.

**NIT (반영)**. N2 Bucket/Result domain 패키지 배치 / N3 파일헤더 한국어 / N4 NFR 5,000건 케이스. N1(groupKey String) 단순성 수용.

**보강 후 BLOCKER: 없음.** 권한 누출(BROWSE+Project scope 게이트)·cartesian 없음(N:1)·probe 방지(actor 선추출)·BC 격리(포트 창구) 확인.
