# FR-CA-02 iCal Export (외부 캘린더 연동)

> slug: fr-ca-02-ical-export
> type: feature (classify_raw=backend, 관례상 feature)
> agent: backend-engineer (+ security-engineer 토큰/엔드포인트, frontend-engineer UI)
> BC: personalization (논리) / identity-access (물리)
> 생성: 2026-07-09

## Brief

FR-CA-01(개인 캘린더 — 할당/마감일/Worklog 통합, PR #249)의 데이터를 외부 캘린더 앱
(Google Calendar / Apple Calendar / Outlook)이 **구독**할 수 있는 RFC 5545(.ics) 피드로 노출.

- **핵심 보안 제약**. 외부 캘린더 앱은 Authorization Bearer 헤더를 못 보냄
  (아바타 `<img src>` 401 · Slack install-url 전체페이지이동 401 과 동일 근본 제약).
  → URL 경로에 추측 불가능한 **불투명 토큰**을 박아 `GET /ical/feed/{token}.ics` 익명 접근 허용.
  토큰 → user 매핑 + 취소(revoke) 가능.
- **데이터 모델**. `user_calendar_tokens(user_id, token, revoked_at)` 신규 테이블 (product §5.2 D3).
- **포맷**. RFC 5545 (iCalendar VEVENT).
- **재사용**. FR-CA-01의 `UserCalendarLookupPort`(shared-kernel, 사용자 축) 캘린더 데이터.
- personalization BC 마지막 FR → 완료 시 11/12 → 12/12.

**product 체크리스트 (§5.2 D1~D7)**.
- D1. 도메인 (backend-engineer)
- D2. 명세 — RFC 5545 + 구독 URL 토큰 (backend + security-engineer)
- D3. 데이터 모델 — `user_calendar_tokens(user_id, token, revoked_at)` (db-engineer)
- D4. 백엔드 — `GET /ical/feed/{token}.ics` (backend-engineer)
- D5. 백엔드 테스트 — iCal 포맷 검증 (backend-engineer)
- D6. 프론트 UI — 구독 URL 발급/취소 페이지 (designer → frontend-engineer)
- D7. E2E (qa-engineer)

## 도메인 정리

- **BC**: personalization (논리) / **identity-access** (물리) — FR-CA-01·FR-PR-01 선례, `/users/me/*` 응집
- **재사용 (신규 cross-BC 포트 0)**: FR-CA-01 `UserCalendarLookupPort`(shared-kernel). `listAssignedScheduledIssues` + `listWorklogs` 둘 다 호출. viewer=토큰 소유자 → visibility fail-closed 유지
- **피드 taxonomy (Maxi 확정 2026-07-09)**: **이슈 + Worklog** (FR-CA-01 인앱 캘린더 동일). 이슈=all-day VEVENT(start~due), Worklog=타임드 VEVENT(UTC)
- **토큰 모델**: 불투명 랜덤 토큰 + **SHA-256 해시만 저장**(원문 1회 노출). 사용자당 활성 1개(재발급=rotate). 취소=하드삭제(임시 자격증명). → product `token` 평문 컬럼 **deviation** (ADR D4)
- **익명 엔드포인트**: `GET /ical/feed/{token}.ics` permitAll·`text/calendar`·해시 조회 실패 404. 관리 `POST/GET/DELETE /api/v1/users/me/calendar/feed` JWT-only(PAT 401)
- **롤링 윈도**: 과거 30일 ~ 미래 180일(피드엔 from/to 없음). timezone=user_profiles.timezone(FR-CA-01 D6)
- **신규 테이블 1개**: `user_calendar_tokens`(token_hash UNIQUE) — FR-CA-01은 조회만이었으나 토큰 저장 필요
- **SecurityFilterChain**: `/ical/**` permitAll 화이트리스트 (FR-DB-03 `/api/v1/public/*` 이후 두 번째 비인증 경로)
- **새 용어 후보**: 캘린더 피드 토큰 (Calendar Feed Token) — Maxi 승인 후 glossary 동기화
- **기존 결정 충돌**: 없음. FR-CA-01 포트 + FR-DB-03 익명 토큰 패턴 조합
- **관련 ADR**: [docs/decisions/2026-07-09-fr-ca-02-ical-export.md](../decisions/2026-07-09-fr-ca-02-ical-export.md) (생성됨)

## 스펙

전체 스펙. [docs/specs/2026-07-09-fr-ca-02-ical-export.md](../specs/2026-07-09-fr-ca-02-ical-export.md)

핵심 요약.
- 관리 API 3종(`POST/GET/DELETE /api/v1/users/me/calendar/feed`, JWT me-scope·PAT 401) + 익명 피드 `GET /ical/feed/{token}.ics`(permitAll·GET-only·404 수렴).
- 토큰 = `TrustedDeviceToken` 동형 minter(256비트 CSPRNG hex + SHA-256 해시). DB `user_calendar_tokens(user_id PK, token_hash UNIQUE, created_at)`. 사용자당 1개(UPSERT rotate)·취소=하드삭제.
- 피드 = FR-CA-01 `UserCalendarLookupPort` 재사용(이슈+Worklog, 롤링 -30d/+180d, viewer=소유자 visibility). **자체 RFC 5545 직렬화기**(신규 의존성 0): 이슈 all-day VEVENT(DTEND exclusive) + Worklog 타임드 VEVENT(UTC), 이스케이핑·75옥텟 폴딩·CRLF.
- 격리: issue-tracking·shared-kernel·whoami 무변경 → identity-access 단일 BC.

## Brainstorming Check

✅ 통과 (1회 iteration). 5 gap(G1~G5) 스펙 보강 흡수, Maxi 결정 불요.
- **게이트1 Maxi 확인 지점**: 자체 iCal 직렬화기 vs ical4j(관례상 자체 채택). 이견 시 변경.
- Maxi 확정 이력: 피드 taxonomy=이슈+Worklog(2026-07-09).

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
