# FR-CA-01 개인 캘린더 (할당/마감일/Worklog 통합)

> slug: fr-ca-01-calendar
> type: feature
> agent: backend-engineer (+ frontend-engineer for UI, db-engineer for query, qa-engineer for E2E)
> 생성: 2026-07-08

## Brief

FR-CA-01 개인 캘린더 — 로그인 사용자의 (1) 할당 이슈, (2) 이슈 마감일(due date), (3) Worklog 일정을
월/주 캘린더로 통합 조회. `GET /api/v1/users/me/calendar?from=&to=`.
별도 저장 테이블 없이 조회(read-only)만. personalization BC(논리) / identity-access 모듈(물리).
cross-BC 조회(issue-tracking, agile-planning)가 핵심 설계 포인트.

- 우선순위: 높음
- 선행: issue-tracking §6.1 FR-PL-01 (계획/날짜, 완료됨)
- product: docs/plan/product/personalization.md §5.1
- D1~D7 (도메인 CalendarEvent / 명세 / 데이터모델(조회만) / 백엔드 API / 백엔드 테스트 / 월·주 UI / E2E)

## 도메인 정리

- **BC**: personalization (논리) / **identity-access 모듈**(물리) — `/api/v1/users/me/*` 관례. FR-PR-01 선례.
- **cross-BC 조회 대상**: issue-tracking **한 곳** (할당 이슈·마감일·Worklog 모두 issue-tracking).
- **신규 shared-kernel 포트**: `UserCalendarLookupPort`(사용자 축). issue-tracking adapter 구현. fail-safe 빈 결과.
  TimelineLookupPort(프로젝트 축)는 축이 달라 재사용 불가 — 새 포트 도입.
- **영향 데이터(읽기 전용)**:
  - `issues.assignee_id`(V007) + `start_date/due_date`(V025, FR-PL-01) + `idx_issues_due_date`(V026)
  - `worklogs(author_id, started_at, time_spent_seconds)`(V027, FR-TT-01) + `idx_worklogs_author_started`
- **새 용어(glossary 추가 대기, Maxi 승인)**: "개인 캘린더 / CalendarEvent" — 조회자 담당 이슈(날짜)+본인 Worklog를
  월/주 캘린더로 통합한 읽기 전용 뷰. 이벤트 2종(이슈 이벤트 start/due, Worklog 이벤트 date/seconds).
- **이벤트 taxonomy(Maxi 확정 2026-07-08)**: 기간 막대 포함 = 마감일 점 + start~due 기간 막대 + Worklog. target_date 제외.
- **담당 범위**: assignee = me only.
- **visibility**: adapter가 viewer(=me) 기준 fail-closed 보안 필터(TimelineLookupAdapter 패턴). Worklog는 본인 것만.
- **timezone**: worklog started_at(TIMESTAMPTZ)를 사용자 프로필 timezone(FR-PR-01) 기준 날짜 매핑. from/to=로컬 날짜.
- **기존 결정 충돌**: 없음. read-only, 별도 테이블 X, FR 카운트/BC 매핑 변경 0(논리 ≠ 물리).
- **관련 ADR**: [docs/decisions/2026-07-08-fr-ca-01-calendar.md](../decisions/2026-07-08-fr-ca-01-calendar.md) (생성됨)

## 스펙

전체 스펙. [docs/specs/2026-07-08-fr-ca-01-calendar.md](../specs/2026-07-08-fr-ca-01-calendar.md)

핵심 요약.
- `GET /api/v1/users/me/calendar?from=&to=` — 내 담당 예정 이슈(start/due) + 내 Worklog를 `[from,to]`(≤90일)로 반환.
- cross-BC = 신규 shared-kernel `UserCalendarLookupPort`(issue-tracking adapter, fail-safe). identity-access가 timezone 변환 책임.
- issueEvents는 viewer(=me) 가시성 fail-closed 필터. worklogEvents는 본인 것만. 프로필 timezone(기본 UTC) 기준 날짜 매핑.
- UI = `/calendar` 월/주 뷰(네이티브 Date, 신규 의존성 0), 이슈 막대+마감일 점+Worklog 칩, 이벤트 클릭→이슈 상세. D6 designer→frontend.

## Brainstorming Check

✅ 통과 (자체 sanity 1회). gap 3건 인라인 보강(결정적 정렬·프론트 이중 tz변환 금지·cross-project visibility 술어 검증).
이월 리스크: 새 포트 소비 full-boot/슬라이스 stub 배선, adapter cross-project visibility 술어 실재 확인.

## Plan

> cross-BC 테스트 분리(memory [[no-cross-bc-deployment-assembly]]): adapter 실 SQL 검증은 **issue-tracking 모듈**(실 DB),
> identity-access 컨트롤러 검증은 **stub 포트 @Bean**(BC 격리로 issue-tracking gradle 의존 불가).
> 프론트는 spec §API 계약 고정 → MSW-first로 백엔드와 병렬.

### Task 1. shared-kernel `UserCalendarLookupPort` + VO

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/calendar/UserCalendarLookupPort.kt`, `backend/modules/shared-kernel/src/test/kotlin/com/bts/shared/calendar/UserCalendarLookupPortTest.kt`]
- depends-on: []

**RED**: 포트 default 메서드가 빈 페이지(truncated=false) 반환 검증 (TimelineLookupPortTest 패턴).
**GREEN**: `interface UserCalendarLookupPort` — `listAssignedScheduledIssues(userId, from: LocalDate, to: LocalDate): CalendarIssuePage` + `listWorklogs(userId, fromInstant: Instant, toInstant: Instant): CalendarWorklogPage`, 둘 다 default 빈 반환. `CalendarIssueView`(key/summary/issueType/currentStateKey/startDate?/dueDate?), `CalendarIssuePage`, `CalendarWorklogView`(id/issueKey/issueSummary/startedAt: Instant/timeSpentSeconds), `CalendarWorklogPage`.
**REFACTOR**: KDoc — BC 격리 사유·fail-safe·timezone 책임 경계(소비측이 Instant→로컬 date 매핑).
**검증**: `./gradlew :backend:shared-kernel:test --tests *UserCalendarLookupPortTest`

### Task 2. issue-tracking `UserCalendarLookupAdapter` (실 SQL + visibility)

**메타**.
- agent: `backend-engineer` (visibility 필터 = security-engineer 리뷰 대상)
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/outbound/calendar/UserCalendarLookupAdapter.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/adapter/outbound/calendar/UserCalendarLookupAdapterIntegrationTest.kt`]
- depends-on: [1]

**RED**: Testcontainers 통합 테스트. 시드 — 이슈(assignee=me 날짜있음/없음, assignee=타인, 보안등급 비가시, soft-deleted), worklog(author=me 범위내/밖, author=타인, deleted). 검증: assignee=me + (start|due) + span∩[from,to] + viewer 가시만 / worklog author=me 활성 + started_at∈[fromInstant,toInstant).
**GREEN**: jOOQ 2쿼리. **cross-project visibility 술어 재사용**(IssueSearchPort/IssueRepository 검색 경로 grep — 프로젝트 축 술어 오재사용 금지, spec ⚠). LIMIT 500 + truncated.
**REFACTOR**: 술어 추출·KDoc·인덱스 활용 주석(idx_issues_due_date·idx_worklogs_author_started).
**검증**: `./gradlew :backend:issue-tracking:test --tests *UserCalendarLookupAdapterIntegrationTest`

### Task 3. identity-access `CalendarService` (tz 변환·검증·정렬·조립)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/calendar/CalendarService.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/calendar/CalendarResponse.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/calendar/CalendarServiceTest.kt`]
- depends-on: [1]

**RED**: 단위 테스트(fake `UserCalendarLookupPort`). from>to→400, 창>90일→400, 날짜형식오류→400, tz 변환(worklog Instant→로컬 date, S4 경계), 정렬(issueEvents `(start?:due,key)`·worklogEvents `(date,startedAt)`), 빈/truncated, 프로필 tz 미설정→UTC.
**GREEN**: 서비스. `UserProfileService`(같은 모듈 직접)로 timezone 조회 → from/to 로컬날짜↔Instant 범위 변환 → 포트 2회 호출 → worklog date 매핑 → 정렬 → `CalendarResponse` 조립. 검증 실패는 `ResponseStatusException(400, INVALID_CALENDAR_RANGE)`.
**REFACTOR**: 창 검증 상수(90일)·KDoc.
**검증**: `./gradlew :backend:identity-access:test --tests *CalendarServiceTest`

### Task 4. identity-access `CalendarController` + DTO + boot 배선 + HTTP 통합

**메타**.
- agent: `backend-engineer` (인증 게이트 = security-engineer 리뷰 대상)
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/calendar/CalendarController.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/calendar/CalendarControllerIntegrationTest.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/calendar/StubUserCalendarLookupPortConfig.kt`]
- depends-on: [3]

**RED**: HTTP 통합 테스트(full-boot + `@TestConfiguration` stub `UserCalendarLookupPort` @Bean — BC 격리로 실 adapter 불가). 200(stub 시드), 400(잘못된 창), 401(미인증). identity-access RANDOM_PORT 부팅 레시피(memory [[identity-access-prod-randomport-boot-recipe]]).
**GREEN**: `GET /api/v1/users/me/calendar` 컨트롤러(actor=JWT me), DTO 직렬화(issueEvents/worklogEvents/timezone/truncated), 포트 소비 배선(런타임 fail-safe default). @WebMvcTest 로드 슬라이스는 신규 협력자 mock(memory [[whoami-slice-mock-skipci-masking]]·[[new-crossbc-dep-openapi-mockbean-regression]]).
**REFACTOR**: OpenApi 어노테이션·KDoc.
**검증**: `./gradlew :backend:identity-access:test --tests *CalendarControllerIntegrationTest`

### Task 5. 디자인 스펙 — 캘린더 월/주 뷰 (D6 designer)

**메타**.
- agent: `designer`
- files: [`docs/design/fr-ca-01-calendar.md`]
- depends-on: []
- **TDD 예외**: 디자인 스펙 산출(코드/테스트 없음). RED/GREEN 미적용.

**산출**: 월 6주 그리드 + 주 7일 레이아웃. 이벤트 렌더(상태색 이슈 기간 막대·마감일 점·worklog 시간 칩·셀 오버플로 "+N개"), 이전/다음/오늘·월↔주 토글, 빈/로딩/에러 상태. DESIGN.md 토큰(색/여백/타이포) 준수. WCAG AA(대비·키보드).
**검증**: 스펙 문서 완성 + frontend task가 참조 가능.

### Task 6. 프론트 calendar API + Zod + `useCalendar` 훅

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/api/calendar.ts`, `apps/web/src/api/calendar.test.ts`, `apps/web/src/api/useCalendar.ts`, `apps/web/src/api/useCalendar.test.ts`, `apps/web/src/test/msw/handlers/calendar.ts`]
- depends-on: []

**RED**: Zod parse 테스트(spec §API 응답 fixture — nullable startDate/dueDate, worklog id UUID). 훅 filter-aware queryKey(from/to). MSW 핸들러.
**GREEN**: Zod 스키마(spec §API **정확 미러**, DTO invent 금지 memory [[frontend-zod-backend-dto-contract-gap]]·Zod v4 UUID fixture memory [[zod-v4-uuid-fixture-strictness]]). `apiFetch` 기반 fetch. `useCalendar(from,to)` 쿼리 훅(queryKey에 from/to 포함).
**REFACTOR**: 타입 export·KDoc.
**검증**: `pnpm --filter web test calendar`

### Task 7. 프론트 `/calendar` 월/주 뷰 컴포넌트 + 라우트

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/routes/calendar.tsx`, `apps/web/src/features/calendar/CalendarView.tsx`, `apps/web/src/features/calendar/MonthGrid.tsx`, `apps/web/src/features/calendar/WeekGrid.tsx`, `apps/web/src/features/calendar/calendar.test.tsx`]
- depends-on: [5, 6]

**RED**: 컴포넌트 테스트 — 월 뷰 6주 그리드 렌더, 주 뷰 7일, 이벤트가 올바른 날짜 셀에 배치, 이슈 클릭→`/issues/{key}` 네비, 빈 상태, from/to→useCalendar 호출.
**GREEN**: 네이티브 `Date` 그리드(신규 의존성 0). 디자인 스펙(T5) 반영. 이전/다음/오늘·월↔주 토글이 from/to 갱신. 전역 네비게이션 진입점 추가.
**REFACTOR**: 날짜 유틸 순수함수 추출(테스트 용이)·접근성 속성.
**검증**: `pnpm --filter web test calendar` + `pnpm --filter web typecheck`

### Task 8. E2E (Playwright)

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/calendar.spec.ts`, `apps/web/src/test/msw/handlers/calendar.ts`(시나리오 시드 확장)]
- depends-on: [7]

**시나리오**: 월↔주 전환·이전/다음/오늘·이벤트 클릭→이슈 상세·빈 상태. MSW 시드(serviceWorker block 금지 memory [[e2e-msw-serviceworker-block]], 시나리오 토글 memory [[e2e-msw-scenario-toggle-localstorage-flag]]).
**검증**: `pnpm --filter web test:e2e calendar` (E2E 후 5173 orphan kill memory [[e2e-orphan-vite-after-worktree-remove]])

## Plan 메타

- task 수: 8
- 예상 wave: 3 — Wave1[T1·T5·T6] → Wave2[T2·T3·T7] → Wave3[T4·T8]
- TDD 강제: yes (T5 디자인 스펙만 예외)
- 병렬 dispatch: bts-impl이 depends-on + files 겹침으로 wave 계산. 파일 겹침 0 확인됨.
- 추가 검증: ktlintCheck·detekt(backend), typecheck·lint·vitest(frontend), playwright(qa)
- 보안 리뷰: T2(cross-project visibility 필터) + T4(/users/me 인증 게이트) → gate 2 security-engineer 집중.
- 리스크: (a) T2 cross-project visibility 술어 실재 grep 확인, (b) T4 새 포트 소비 슬라이스/full-boot stub 배선.

## 리뷰 결과 (← /bts-review-plan 채움)
