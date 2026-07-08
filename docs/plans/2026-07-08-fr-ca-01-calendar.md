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

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
