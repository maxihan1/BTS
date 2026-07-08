# FR-CA-01 개인 캘린더 (할당/마감일/Worklog 통합) — 스펙

> slug: fr-ca-01-calendar · BC: personalization(논리)/identity-access(물리)
> 도메인 ADR: [docs/decisions/2026-07-08-fr-ca-01-calendar.md](../decisions/2026-07-08-fr-ca-01-calendar.md)

## 개요

로그인 사용자에게 "내 일정"을 월/주 캘린더로 통합 조회. 세 데이터를 한 화면에.
1. 내 담당 이슈(assignee=me) 중 `start_date`/`due_date` 있는 것 — 마감일 점 + 기간 막대.
2. 내 Worklog(author=me) — 기록일에 시간 칩.

`GET /api/v1/users/me/calendar?from=&to=` (read-only, 별도 테이블 없음). cross-BC(issue-tracking)는
신규 shared-kernel `UserCalendarLookupPort`로 위임.

## 사용자 시나리오 (Given-When-Then)

### S1. 이번 달 내 일정 보기
- **Given** ATLAS-12(담당=나, start 7/3~due 7/10), ATLAS-30(담당=나, due 7/25만), worklog 3h(7/5, ATLAS-12)
- **When** `GET /users/me/calendar?from=2026-07-01&to=2026-07-31`
- **Then** issueEvents=[ATLAS-12(start 7/3,due 7/10), ATLAS-30(due 7/25)], worklogEvents=[7/5 3h ATLAS-12]. 200.

### S2. 주간 뷰 전환
- **Given** 월 뷰 표시 중
- **When** 사용자가 "주" 토글 → 그 주의 from/to(월~일)로 재조회
- **Then** 7일 그리드에 해당 주 이벤트만 표시.

### S3. 담당이지만 볼 수 없는 이슈
- **Given** ATLAS-99가 나에게 할당됐으나 이후 보안등급 상향으로 내가 BROWSE 불가
- **When** 캘린더 조회
- **Then** ATLAS-99는 결과에서 제외(fail-closed). 누출 없음.

### S4. 타임존 경계 Worklog
- **Given** 프로필 timezone=Asia/Seoul, worklog started_at=2026-07-05T23:00:00+09:00
- **When** `from=2026-07-05&to=2026-07-05` 조회
- **Then** worklogEvents=[date=2026-07-05] (UTC로는 7/5 14:00이지만 사용자 로컬 기준 7/5).

### S5. 빈 일정
- **Given** 해당 기간 담당 이슈·worklog 없음
- **When** 조회
- **Then** issueEvents=[], worklogEvents=[], 200. UI는 "이 기간에 일정이 없습니다" 빈 상태.

### S6. 잘못된 창
- **When** `from=2026-07-31&to=2026-07-01`(from>to) 또는 창>90일 또는 날짜 형식 오류
- **Then** 400 (INVALID_CALENDAR_RANGE).

### S7. 이슈 이벤트 클릭
- **Given** 캘린더에 ATLAS-12 막대 표시
- **When** 사용자가 막대 클릭
- **Then** `/issues/ATLAS-12` 이슈 상세로 이동.

## 기능 요구사항 (FR)

- **FR-CA-01.1** `GET /api/v1/users/me/calendar?from=&to=`는 인증 사용자 본인의 (a) 담당 예정 이슈, (b) 본인 Worklog를 `[from,to]` 창으로 반환한다. 세션 JWT 인증(PAT 비대상은 spec 범위 밖, 기본 인증필터 따름).
- **FR-CA-01.2** `from`·`to`는 필수. ISO `YYYY-MM-DD`(사용자 프로필 timezone 기준 로컬 날짜). `from ≤ to`, 창 길이 ≤ 90일. 위반 시 400.
- **FR-CA-01.3** issueEvents = `assignee_id = me` AND `deleted_at IS NULL` AND (`start_date` 또는 `due_date` 중 ≥1 non-null) AND 이슈 날짜 span이 `[from,to]`와 교차 AND viewer(=me) 가시. span = `[start ?: due, due ?: start]`, 교차 = `spanStart ≤ to AND spanEnd ≥ from`.
- **FR-CA-01.4** worklogEvents = `author_id = me` AND `deleted_at IS NULL` AND `started_at`이 사용자 timezone 기준 `[from, to]` 로컬 날짜 범위 내. 각 이벤트 `date` = `started_at`을 사용자 timezone으로 변환한 로컬 날짜. worklog 이벤트 자체는 본인 기록이라 항상 표시하되, **참조 이슈가 현재 조회자에게 비가시면 `issueSummary`를 null 마스킹**(issueKey는 유지). fail-closed 일관(C4, D5).
- **FR-CA-01.5** cross-BC 조회는 신규 shared-kernel `UserCalendarLookupPort`(issue-tracking adapter 구현) 경유. adapter 미등록 시 빈 결과(fail-safe) 반환하되 엔드포인트는 200.
- **FR-CA-01.6** (UI) `/calendar` 라우트. 월 뷰(6주 그리드) + 주 뷰(7일) 토글. 이전/다음/오늘 네비게이션. 이슈=마감일 점 + start~due 기간 막대(상태색), Worklog=시간 칩. 전역 네비게이션에서 진입.
- **FR-CA-01.7** (UI) 이슈 이벤트 클릭 → 이슈 상세 이동. 빈 상태·로딩·에러 배너. WCAG AA(키보드 네비·대비).

## 비기능 요구사항 (NFR)

- **성능**: 30일 창 조회 p95 < 500ms(product NFR). worklog는 `idx_worklogs_author_started`(author 선행+범위) 적합. 이슈는 **D1=프로젝트별 필터라 project_id 스코프 → V029(`project_id,assignee_id`) 재사용**. **측정 기반(D2)**: impl에서 EXPLAIN ANALYZE 실측 → 적합 시 마이그레이션 0 유지, 부적합 시 후속 PR 인덱스(무근거 재사용 주장 제거).
- **보안**: issueEvents는 viewer 가시성 fail-closed 필터(구현체 책임). 본인 데이터만 노출.
- **의존성**: 프론트 신규 라이브러리 0(네이티브 `Date` 자체 그리드, FR-UX-05 선례). 백엔드 신규 마이그레이션 0.
- **BC 격리**: identity-access는 issue-tracking을 gradle 직접 의존하지 않음(ArchUnit). shared-kernel 포트만.
- **접근성**: axe 0 violation.

## API 인터페이스 (REST)

```
GET /api/v1/users/me/calendar?from=2026-07-01&to=2026-07-31
Authorization: Bearer <session JWT>

200 OK
{
  "from": "2026-07-01",
  "to": "2026-07-31",
  "timezone": "Asia/Seoul",
  "issueEvents": [
    {
      "key": "ATLAS-12",
      "summary": "결제 모듈 리팩터링",
      "issueType": "task",
      "currentStateKey": "in_progress",
      "startDate": "2026-07-03",
      "dueDate": "2026-07-10"
    },
    { "key": "ATLAS-30", "summary": "릴리스 노트", "issueType": "task",
      "currentStateKey": "todo", "startDate": null, "dueDate": "2026-07-25" }
  ],
  "worklogEvents": [
    { "id": "…uuid…", "issueKey": "ATLAS-12", "issueSummary": "결제 모듈 리팩터링",
      "date": "2026-07-05", "timeSpentSeconds": 10800 }
  ],
  "truncated": false
}

400 — INVALID_CALENDAR_RANGE (from>to / 창>90일 / 날짜 형식 오류)
401 — 미인증
```

- `truncated`: 각 목록 LIMIT(500) 초과 시 true(창 ≤90일·본인 한정이라 정상 도달 드묾, 안전장치). 최상위 1개 플래그 = 두 목록 중 하나라도 절단되면 true.
- `issueEvents[].startDate`/`dueDate`: 둘 중 하나는 반드시 non-null(FR-CA-01.3).
- `timezone`: 응답에 사용자 적용 timezone 에코. **프론트는 이 문자열을 참고만 하고 `date`/`startDate`/`dueDate`를 재변환하지 않는다**(백엔드가 이미 사용자 로컬 날짜로 계산 — 이중 변환 시 날짜 밀림 버그).
- **정렬(결정적)**: issueEvents = `(startDate ?: dueDate, key)` 오름차순, worklogEvents = `(date, startedAt)` 오름차순. 테스트 안정성 + UI 예측성.

### shared-kernel 포트 (신규)

```kotlin
// shared-kernel/.../calendar/UserCalendarLookupPort.kt
interface UserCalendarLookupPort {
    // 담당(assignee=userId) + 날짜(start/due 중 1개+) + viewer(userId) 가시 이슈, span∩[from,to]
    fun listAssignedScheduledIssues(userId: UUID, from: LocalDate, to: LocalDate): CalendarIssuePage =
        CalendarIssuePage(items = emptyList(), truncated = false)
    // author=userId 활성 worklog, started_at ∈ [fromInstant, toInstant)
    fun listWorklogs(userId: UUID, fromInstant: Instant, toInstant: Instant): CalendarWorklogPage =
        CalendarWorklogPage(items = emptyList(), truncated = false)
}
data class CalendarIssueView(val key, summary, issueType, currentStateKey: String,
    val startDate: LocalDate?, val dueDate: LocalDate?)  // 둘 중 1개+ non-null
data class CalendarWorklogView(val id: UUID, issueKey, issueSummary: String,
    val startedAt: Instant, val timeSpentSeconds: Int)   // 날짜 매핑은 소비측(identity-access)이 tz로 수행
```

- **timezone 책임 경계**: identity-access 서비스가 프로필 timezone 읽기 → 로컬 날짜/Instant 변환 → 포트 호출 → worklog `startedAt`(Instant)을 로컬 date로 매핑. issue-tracking adapter는 timezone 무지(DATE는 tz 무관, worklog는 Instant 범위만 받음).
- **Instant 변환 공식(고정)**: `fromInstant = from.atStartOfDay(userZone).toInstant()`, `toInstant = to.plusDays(1).atStartOfDay(userZone).toInstant()` (half-open `[fromInstant, toInstant)`, `to` 당일 포함). 선례 `WorklogAggregateRepository.aggregate`(`STARTED_AT >= fromOdt AND STARTED_AT < toExcl`)와 동형. `to` 당일 자정/23:59:59로 오해 시 마지막 날 worklog 누락(off-by-one-day) — 반드시 `to+1일 로컬 자정` exclusive.
- **DST 경계**: `atStartOfDay(zone)`은 DST gap/overlap 날 경계가 밀릴 수 있음(한국은 DST 없음). 테스트에 DST 존(America/New_York 봄 전환) 경계 1건 포함.

## 데이터 모델 변경

**없음** (read-only). 재사용 인덱스.
- `idx_issues_due_date`(V026, 부분 인덱스) + `issues.assignee_id`(V007) + `start_date/due_date`(V025)
- `idx_worklogs_author_started(author_id, started_at)`(V027)
- **viewer 가시성 (D1 확정 — 프로젝트별 필터)**: 재사용 가능한 cross-project 술어가 **없으므로**(모두 프로젝트 축 하드코딩), adapter가 `SELECT DISTINCT project_id WHERE assignee_id=me AND (start|due)` → 프로젝트마다 `accessibleLevels(me, projectKey)` → 프로젝트별 보안조건 OR 조립. **fail-open 방지**: 한 프로젝트 등급을 타 프로젝트 이슈에 적용 금지(조건 격리). worklog issueSummary도 동일 가시성 검사로 마스킹.

## 엣지 케이스

| 케이스 | 처리 |
|---|---|
| `from > to` | 400 INVALID_CALENDAR_RANGE |
| 창 > 90일 | 400 |
| 날짜 형식 오류(`2026-13-01`) | 400 |
| 이슈 start만 / due만 | 단일 점(포트 VO 한쪽 null). span=단일 날짜 |
| 이슈가 창 경계 걸침(start<from, due>to) | 포함(교차). 클라이언트가 그리드에서 클리핑 |
| worklog 타임존 경계 | 사용자 tz 기준 로컬 날짜로 귀속(S4) |
| 담당이나 BROWSE 불가 이슈 | 제외(fail-closed, S3, 프로젝트별 필터) |
| 여러 프로젝트 상이 스킴 | 프로젝트별 등급 격리 적용(fail-open 방지 — 타 프로젝트 등급 오적용 금지) |
| worklog 참조 이슈 현재 비가시 | worklog 이벤트는 표시, issueSummary=null 마스킹(issueKey 유지, C4) |
| soft-deleted 이슈 | issueEvents에서 제외. 단 그 이슈에 달린 내 worklog는 worklog 이벤트로 표시되되 issueSummary=null 마스킹(issueKey 유지, 본인 시간기록 보존) |
| soft-deleted worklog | 제외 |
| 프로필 timezone 미설정/무효 | 기본 UTC(UserProfileService.DEFAULT_TIMEZONE) |
| adapter 미등록(단계적 배포/테스트 stub) | 빈 결과, 200 |
| 이벤트 0건 | 빈 배열, 200. UI 빈 상태 |
| 미인증 | 401 |
| 같은 이슈에 worklog 여러 건 | 각각 별도 worklog 이벤트 |

## 제약 조건

- 창 상한 90일(월 6주 그리드 42일 + 주 뷰 여유 커버). CFD/timeline 180일보다 보수적(개인 조회 빈도↑).
- 각 목록 LIMIT 500 + truncated 플래그.
- assignee=me만(reporter/watcher 제외). target_date 제외(로드맵 개념).
- 프론트 신규 의존성 0.

## 측정 가능한 완료 기준

- [ ] `GET /users/me/calendar` 통합 테스트: S1~S7 시나리오 + 엣지(400/401/fail-closed/tz 경계) 그린.
- [ ] `UserCalendarLookupPort` + issue-tracking adapter 통합 테스트(assignee 필터·visibility·날짜 교차·worklog 범위).
- [ ] identity-access application service 단위 테스트(tz 변환·로컬 날짜 매핑·빈/truncated).
- [ ] 프론트 `/calendar` 월/주 뷰 컴포넌트 단위 테스트 + Zod 계약.
- [ ] E2E: 월↔주 전환·네비게이션·이벤트 클릭→이슈 상세·빈 상태(MSW).
- [ ] 30일 창 p95 < 500ms(측정 기록표).
- [ ] axe 0 violation.

## UI/디자인 노트

- 시각 디자인은 DESIGN.md 토큰 준수. 상세 목업/스펙은 plan의 designer 에이전트 task(D6)가 산출 → frontend-engineer 구현.
- Maxi가 gate 1에서 design-shotgun(시안 4종 비교)을 원하면 요청 가능(기본은 designer 위임).

## Brainstorming Check

✅ 통과 (자체 적대적 sanity 1회). 발견 gap 3건 인라인 보강.
1. **결정적 정렬** 규칙 추가(issueEvents/worklogEvents) — 테스트 안정성.
2. **프론트 이중 timezone 변환 금지** 명시 — 백엔드가 이미 로컬 날짜 반환.
3. **cross-project visibility 술어 재사용** impl 검증 포인트 명시 — 프로젝트 축 술어 오재사용 = 필터 누락/vacuous 위험.

이월 리스크(plan/impl에서 처리):
- 새 shared-kernel 포트 소비 → identity-access full-boot/@WebMvcTest 슬라이스에 포트 stub/@MockBean 배선 필요
  (memory: new-crossbc-dep-openapi-mockbean-regression · whoami-slice-mock-skipci-masking).
- issue-tracking adapter가 cross-project visibility 술어를 실제로 노출/재사용 가능한지 impl 착수 시 grep 확인.
