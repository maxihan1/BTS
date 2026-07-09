<!-- FR-CA-02 iCal Export: 익명 구독 토큰(SHA-256) + RFC 5545 피드 + FR-CA-01 포트 재사용 ADR -->

# ADR — FR-CA-02 iCal Export: 익명 구독 토큰 + RFC 5545 피드

- 날짜: 2026-07-09
- 상태: 채택 (Accepted)
- 관련 FR: FR-CA-02 (iCal Export — 외부 캘린더 연동)
- 관련 slug: fr-ca-02-ical-export
- 선행: FR-CA-01(개인 캘린더, `2026-07-08-fr-ca-01-calendar.md`) / FR-DB-03(대시보드 공유 토큰, `2026-07-02-fr-db-03-dashboard-share.md`)

## 맥락 (Context)

product(`personalization.md §5.2`)는 FR-CA-02를 `GET /ical/feed/{token}.ics`(RFC 5545 + 구독 URL 토큰) +
`user_calendar_tokens(user_id, token, revoked_at)` 테이블로 명세한다. FR-CA-01(개인 캘린더)의 데이터를
외부 캘린더 앱(Google/Apple/Outlook)이 **구독**하도록 노출하는 것이 목적이다.

코드베이스 조사.
1. **익명 URL 토큰 선례 = FR-DB-03.** `dashboard_share_tokens(token_hash)`가 불투명 토큰을 SHA-256 해시로만
   저장하고(원문 1회 노출·미저장), 취소=하드삭제(임시 자격증명), `GET /api/v1/public/dashboards/{token}`을
   비인증 경로로 노출한다("BTS 첫 익명 데이터 경로"). 신뢰 디바이스(FR-MF-05)·PAT도 동일 해시 저장 관례.
2. **캘린더 데이터 = FR-CA-01 `UserCalendarLookupPort`(shared-kernel, 사용자 축).** `listAssignedScheduledIssues`
   (이슈 이벤트)와 `listWorklogs`(worklog 이벤트) 두 메서드가 독립. issue-tracking adapter가 viewer visibility
   fail-closed 필터·비가시 issueSummary null 마스킹을 이미 구현.
3. **물리 배치 = identity-access.** `/api/v1/users/me/*`가 identity-access에 응집(FR-PR-01/FR-CA-01 선례).

## 결정 (Decision)

### D1. 물리 모듈 = identity-access (personalization 논리 BC)
관리 엔드포인트(토큰 발급/조회/취소)와 익명 피드 엔드포인트를 identity-access에 둔다.
fr-index의 BC 매핑(personalization)·FR 카운트 불변(논리 ≠ 물리, FR-CA-01 선례).

### D2. cross-BC 조회 = FR-CA-01 `UserCalendarLookupPort` 재사용 (신규 포트 0)
피드 생성은 FR-CA-01 포트를 그대로 호출한다. 신규 포트/VO 없음.
- viewer = **토큰 소유자(userId)**. 익명 요청이지만 토큰이 사용자를 식별하므로, 그 사용자의 visibility가
  adapter에서 그대로 적용된다 → fail-closed 유지(링크 소지자에게 소유자가 못 보는 이슈는 노출 안 됨).
- 피드엔 from/to 파라미터가 없으므로 **고정 롤링 윈도**로 호출: 과거 30일 ~ 미래 180일(서버 계산,
  사용자 timezone 기준). truncated LIMIT 가드는 포트가 이미 보유.

### D3. 피드 이벤트 taxonomy = 이슈 + Worklog (Maxi 확정 2026-07-09)
FR-CA-01 인앱 캘린더와 **동일 데이터**. 두 종류 VEVENT.
- **이슈 이벤트** — `DTSTART;VALUE=DATE`(all-day). start~due 있으면 기간(멀티데이 all-day), due만 있으면 단일일.
  UID = 이슈 안정 식별자 기반(`issue-<issueKey>@bts` 형태, spec 확정). 재폴링 시 캘린더 앱이 dedup.
- **Worklog 이벤트** — `DTSTART:<UTC>Z` ~ `DTEND`(started_at + time_spent). UID = `worklog-<id>@bts`.
  비가시 이슈 worklog는 issueSummary null 마스킹(포트가 이미 처리, issueKey는 유지).

대안(이슈만)은 기각 — Maxi가 인앱↔외부 일관성 선택. Worklog가 과거 기록이라 잡음이 될 수 있으나
FR-CA-01 taxonomy 계승이 우선.

### D4. 토큰 모델 = 불투명 랜덤 토큰 + SHA-256 해시 저장 (FR-DB-03 계승)
- 서버 발급 불투명 랜덤 토큰(URL-safe). **원문은 발급 응답에서 1회만 노출**, DB엔 SHA-256 해시만 저장.
  유출 시 원문 복원 불가(DATA.md §8 PAT·FR-DB-03·FR-MF-05 선례).
- **사용자당 활성 토큰 1개.** "재발급"은 이전 토큰 무효화 + 신규 발급(rotate). "구독 URL 발급/취소"(D6) 단수 UX.
- **취소 = 하드 삭제**(row 즉시 제거, `deleted_at` 없음). 임시 자격증명 범주(세션/PAT/공유토큰 동일,
  DATA.md §3 하드삭제 허용·§1.2 "하드삭제는 ADR 필수" 충족 — 본 ADR이 근거).
- product 문서 `user_calendar_tokens(user_id, token, revoked_at)`의 `token` 평문 컬럼 → **`token_hash`로 deviation**
  (보안 관례). `revoked_at` 컬럼도 하드삭제 채택으로 불요 → 실제 컬럼은 D3 마이그레이션에서 확정.

### D5. 익명 피드 엔드포인트 = 비인증 공개 경로 (backend + security-engineer)
- 피드. `GET /ical/feed/{token}.ics` — **비인증**(SecurityFilterChain permitAll 화이트리스트). `text/calendar; charset=utf-8`.
  토큰 해시 조회 실패 시 **404**(토큰 존재 여부 probe 최소화, 계정 열거 차단). 정상 시 200 + .ics 본문.
- 관리. `POST /api/v1/users/me/calendar/feed`(발급) · `GET`(현재 상태 조회) · `DELETE`(취소) — me-scope **JWT-only**.
  **PAT→403** `calendar_feed_requires_interactive_login`(Maxi 게이트1 확정 — 자격증명 관리라 401 아닌 403이 의미상 정확, 세션관리 `AuthController.listSessions` nullable-jwt→`PAT_FORBIDDEN_RESPONSE` 선례). 미인증→401. 응답에 원문 토큰은 발급 시 1회만, 이후 조회는 존재 여부/생성시각만(원문 미노출).
- **타이밍/probe 방어**. 토큰 조회는 상수시간 비교 불필요(해시 인덱스 조회) — 존재 여부만 404로 구분, 본문 차이 없음.
  익명 경로는 rate-limit 대상 후보(측정 후 유예, spec NFR 기록).

### D6. 프론트 = 구독 URL 발급/취소 페이지 (designer → frontend-engineer)
- 설정 페이지(`/settings/calendar` 계열). 미발급 상태 → "구독 URL 발급" 버튼. 발급 직후 원문 URL 1회 표시 +
  복사 버튼 + "이 URL은 다시 표시되지 않습니다" 경고(PAT/공유토큰 UX). 발급됨 상태 → 생성시각 + "재발급"/"취소".
- **Bearer vs 익명 URL**. 피드 URL은 Authorization 헤더 없이 외부 앱이 폴링(아바타 `<img>`·Slack install-url
  선례와 동일 제약). 프론트는 원문 URL을 발급 API 응답으로 받아 텍스트로 표시만(직접 fetch 안 함).

### D7. read-only 피드 + 신규 테이블 1개
FR-CA-01은 조회만이었으나 FR-CA-02는 토큰 저장을 위해 `user_calendar_tokens` **신규 테이블 1개** 도입
(V0xx). init_codegen.sql 미러(jOOQ 사용 여부는 identity-access 관례상 JdbcTemplate 우선 → 미러 불요 가능,
D3에서 확정). 캘린더 데이터 자체는 여전히 FR-CA-01 포트 위임(별도 저장 0).

## 결과 (Consequences)

- identity-access에 `CalendarFeedController`(관리) + 익명 `IcalFeedController` + iCal 직렬화기(RFC 5545) +
  토큰 서비스/리포지토리 추가. FR-CA-01 `UserCalendarLookupPort` 소비 배선 재사용.
- 신규 마이그레이션 1개(`user_calendar_tokens`, token_hash UNIQUE).
- **두 번째 비인증 경로**(FR-DB-03 `/api/v1/public/*` 이후) → SecurityFilterChain 화이트리스트 확장.
  **GET-only 단일 세그먼트 `/ical/feed/*` permitAll**(FR-DB-03 `PUBLIC_DASHBOARDS_PATH` defense-in-depth 동형 — 하위경로/비-GET 와일드카드 금지, `/api/**` authenticated 앞). identity-access 중앙 SecurityConfig 변경(BC 내부라 정상).
- fr-index/SDD FR 카운트·BC 매핑 불변(논리 ≠ 물리). personalization 11/12 → 12/12(BC 완료).
- 새 용어 후보: **캘린더 피드 토큰(Calendar Feed Token)**. Maxi 승인 후 머지 단계 glossary 동기화.
- 기존 결정 충돌: 없음. FR-CA-01 포트·FR-DB-03 익명 토큰 패턴을 조합·구체화.

## 대안 (Rejected)

- **신규 cross-BC 포트.** 기각 — FR-CA-01 `UserCalendarLookupPort`가 사용자 축 이슈+worklog를 이미 제공. 재사용.
- **토큰 평문 저장(product 문자 그대로).** 기각 — FR-DB-03/PAT/신뢰디바이스 해시 저장 관례 위반, 유출 시 원문 노출.
- **사용자당 다중 토큰.** 기각 — 개인 캘린더 구독은 단수 URL로 충분. 재발급(rotate)으로 갈음. 다중 필요 시 후속.
- **이슈만 피드(Worklog 제외).** 기각 — Maxi가 FR-CA-01 taxonomy 일관성 선택(D3).
