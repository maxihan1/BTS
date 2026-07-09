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

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
