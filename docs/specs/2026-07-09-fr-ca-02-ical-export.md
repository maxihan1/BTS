<!-- FR-CA-02 iCal Export 스펙 — 익명 구독 토큰 + RFC 5545 피드 직렬화 + 발급/취소 UI -->

# FR-CA-02 iCal Export (외부 캘린더 연동) — 스펙

> slug: fr-ca-02-ical-export · BC: personalization(논리)/identity-access(물리)
> ADR: [docs/decisions/2026-07-09-fr-ca-02-ical-export.md](../decisions/2026-07-09-fr-ca-02-ical-export.md)
> 재사용: FR-CA-01 `UserCalendarLookupPort` · FR-DB-03 익명 토큰 패턴 · `TrustedDeviceToken` 해시 minter

## 사용자 시나리오 (Given-When-Then)

- **S1 발급**. Given 로그인 사용자가 아직 구독 URL 미발급 · When 설정 페이지에서 "구독 URL 발급" · Then 서버가 불투명 토큰을 발급하고 **원문 URL을 1회 표시**(복사 버튼 + "다시 표시되지 않습니다" 경고). DB엔 SHA-256 해시만 저장.
- **S2 구독(익명)**. Given 발급된 피드 URL · When 외부 캘린더 앱(Google/Apple/Outlook)이 `GET /ical/feed/{token}.ics`를 Authorization 헤더 없이 주기 폴링 · Then 200 + `text/calendar` RFC 5545 본문(내 담당 이슈 일정 + 내 Worklog, 롤링 -30일~+180일).
- **S3 재발급**. Given 이미 발급됨 · When "재발급" · Then 기존 토큰 해시 무효화 + 신규 발급(원문 1회 표시). 기존 URL은 다음 폴링부터 404 → 외부 구독 깨짐(UX 경고 명시).
- **S4 취소**. Given 발급됨 · When "구독 취소" · Then row 하드삭제. 이후 그 URL은 404.
- **S5 무효 토큰**. Given 취소됐거나 존재한 적 없는 토큰 · When 익명 GET · Then **404**(토큰 존재 여부 probe 최소화, 본문/상태 차이로 열거 불가).
- **S6 상태 조회**. Given 설정 페이지 진입 · When GET 관리 API · Then `{enabled, createdAt}`(발급됨) 또는 `{enabled:false}`(미발급). **원문 토큰은 응답에 없음**(발급 시 1회만).
- **S7 빈 캘린더**. Given 담당 이슈·Worklog 0건 · When 익명 GET · Then 200 + 유효한 빈 VCALENDAR(VEVENT 0개).

## 기능 요구사항 (FR)

- **FR1 발급**. `POST /api/v1/users/me/calendar/feed`. 사용자당 활성 토큰 1개(UPSERT). 응답 201 `{feedUrl, token(원문·1회), createdAt}`. `TrustedDeviceToken.generate()` 동형 minter로 32바이트 CSPRNG hex(256비트) 원문 + SHA-256 해시.
- **FR2 익명 피드**. `GET /ical/feed/{token}.ics`. 비인증(permitAll·GET-only). 요청 토큰을 SHA-256 해시로 조회 → 소유자 userId 확인. 실패 시 404. 성공 시 200 `text/calendar; charset=utf-8`.
- **FR3 피드 내용**. FR-CA-01 `UserCalendarLookupPort` 재사용. `listAssignedScheduledIssues`(이슈) + `listWorklogs`(Worklog) 둘 다. viewer=토큰 소유자 → visibility fail-closed. 롤링 윈도 = 사용자 timezone 기준 오늘 −30일 ~ +180일.
- **FR4 재발급**. FR1과 동일 엔드포인트 재호출 = rotate(기존 해시 덮어씀).
- **FR5 취소**. `DELETE /api/v1/users/me/calendar/feed`. 하드삭제. 204.
- **FR6 상태 조회**. `GET /api/v1/users/me/calendar/feed`. `{enabled, createdAt?}`. 원문 토큰·해시 미노출.
- **FR7 프론트**. `/settings/calendar` 설정 카드. 미발급→발급 버튼 / 발급됨→URL 1회표시·복사·재발급·취소 + 구독 방법 안내(webcal 힌트).
- **FR8 관리 API me-scope**. FR1·5·6은 JWT subject 본인만(**PAT 401**, 세션관리/FR-PR 선례).

## RFC 5545 직렬화 규칙 (핵심 — 자체 직렬화기)

**신규 외부 의존성 0** — 자체 iCal 직렬화기 구현(mermaid 자체SVG·Gantt SVG·AQL 파서·네이티브 캘린더 그리드 선례, DEVELOPMENT.md §외부 의존성 승인 회피). ical4j 등 미도입.

**VCALENDAR wrapper**.
```
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//BTS//Atlas Issues//KO
CALSCALE:GREGORIAN
METHOD:PUBLISH
X-WR-CALNAME:BTS 내 일정
...VEVENT...
END:VCALENDAR
```

**이슈 VEVENT (all-day)**.
- `UID:issue-<issueKey>@bts` — 안정 식별자(토큰 무관 → 재발급해도 dedup 유지).
- `DTSTART;VALUE=DATE:<yyyyMMdd>` = start_date, 없으면 due_date.
- `DTEND;VALUE=DATE:<yyyyMMdd>` = **due_date + 1일**(RFC 5545 all-day DTEND는 exclusive). start만 있으면 start+1.
- `SUMMARY:[<issueKey>] <이스케이핑된 summary>`.
- `DESCRIPTION`(선택). 이슈 타입/상태. `URL:<앱 base>/issues/<issueKey>`.
- `STATUS:CONFIRMED`. `DTSTAMP:<now UTC>`(Clock 주입).

**Worklog VEVENT (타임드)**.
- `UID:worklog-<worklogId>@bts`.
- `DTSTART:<started_at UTC>Z` ~ `DTEND:<started_at + time_spent_seconds>Z`(UTC 출력 → 캘린더 앱이 로컬 변환, DST 무관).
- `SUMMARY:<Nh Mm> — <issueKey>`. 비가시 이슈면 issueSummary null이라 issueKey만.
- `DTSTAMP:<now UTC>`.

**공통 규칙 (footgun 방지, 단위 테스트 필수)**.
1. **텍스트 이스케이핑**(RFC 5545 §3.3.11): `\` → `\\`, `;` → `\;`, `,` → `\,`, 개행 → `\n`. SUMMARY/DESCRIPTION 전부.
2. **라인 폴딩**(§3.1): 75 **옥텟**(UTF-8 바이트 기준, 문자 수 아님 — 한글 3바이트 주의) 초과 시 CRLF + 선행 공백으로 접기.
3. **줄바꿈 = CRLF**(`\r\n`). LF 단독 금지.
4. `DTSTART`/`DTEND` DATE 포맷 = `yyyyMMdd`, DATE-TIME UTC = `yyyyMMdd'T'HHmmss'Z'`.

## 비기능 요구사항 (NFR)

- 피드 응답 p95 < 500ms(product 게이트, k6). 포트 조회 + 직렬화.
- 토큰 엔트로피 256비트(추측 불가). 원문 DB·로그 미저장(DEVELOPMENT.md §1.1.1).
- 익명 경로 rate-limit = **측정 후 유예**(spec 기록, MVP 미도입 — FR-DB-03 동일 수준).
- 피드 URL은 **HTTPS 전제**(토큰이 path에 노출 — http 시 유출). prod https. `app.base-url` 설정에서 스킴 확정.
- 접근 로그 토큰 노출 = 기존 FR-DB-03 익명 경로와 동일 이슈. 문서화, 마스킹은 후속.

## API 인터페이스 (REST)

| 메서드 | 경로 | 인증 | 응답 |
|---|---|---|---|
| POST | `/api/v1/users/me/calendar/feed` | JWT me-scope | 201 `{feedUrl, token, createdAt}` (token=원문 1회) |
| GET | `/api/v1/users/me/calendar/feed` | JWT me-scope | 200 `{enabled, createdAt?}` |
| DELETE | `/api/v1/users/me/calendar/feed` | JWT me-scope | 204 |
| GET | `/ical/feed/{token}.ics` | **익명(permitAll·GET)** | 200 `text/calendar` / 404 |

## 데이터 모델 변경

**신규 테이블 `user_calendar_tokens`** (V0xx, identity-access, JdbcTemplate 관례 → init_codegen 미러 불요).
```sql
CREATE TABLE user_calendar_tokens (
    user_id     UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,  -- 사용자당 1개(구조적 강제)
    token_hash  VARCHAR(64) NOT NULL UNIQUE,                              -- SHA-256 hex, 익명 조회 인덱스
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```
- 발급/재발급 = `INSERT ... ON CONFLICT (user_id) DO UPDATE SET token_hash=EXCLUDED.token_hash, created_at=NOW()`.
- 취소 = `DELETE WHERE user_id=?`. 익명 조회 = `SELECT user_id WHERE token_hash=?`.
- `revoked_at` 컬럼 없음(하드삭제 채택, ADR D4). user 삭제 시 FK CASCADE로 토큰 자동 삭제 → 그 URL 404.

## 엣지 케이스

- **미발급 사용자 URL 추측 접근** → 해시 미스 → 404(S5).
- **취소된 URL 폴링** → row 없음 → 404. 외부 앱은 구독 오류 표시(정상).
- **빈 캘린더** → 유효 빈 VCALENDAR 200(S7). VEVENT 0개도 파서 통과.
- **삭제/비활성 사용자** → FK CASCADE로 토큰 삭제(삭제 시) → 404. (비활성만 된 경우 정책: 토큰 유효 유지 — 이슈 데이터는 포트 visibility가 처리, MVP 단순.)
- **이슈 summary 특수문자**(`,` `;` 개행 `\`) → 이스케이핑. **한글 SUMMARY** → 폴딩 옥텟 계산(3바이트).
- **이벤트 과다** → 포트 `truncated=true` → 상한 이벤트만 직렬화(피드 유효 유지). truncated 시 서버 로그 WARN(사용자 노출 없음).
- **start > due** → 이슈 생성 시 이미 검증됨(방어적으로 DTEND<DTSTART면 DTEND 생략).
- **재발급 후 기존 구독** → 즉시 404. UI가 "재발급하면 기존 URL 무효" 경고.
- **DST 경계 Worklog** → UTC Z 출력이라 무관(앱 로컬 변환).
- **(G1) `.ics` 경로 추출** → 토큰은 hex(0-9a-f) 전용이라 점 없음. Spring `GET /ical/feed/{token}.ics` 리터럴 `.ics` 매칭 안전(확장자 content-negotiation 모호성 없음).
- **(G2) 피드 캐싱** → `Cache-Control: private, no-cache`(데이터 변동 잦음, 캘린더 앱 폴링 주기는 앱이 결정). ETag/조건부 GET은 후속.

## 제약 조건

- **BC 격리**. notification `ShareTokenMinter` import 금지. identity-access 자체 `TrustedDeviceToken` 패턴 미러. 캘린더 데이터는 `UserCalendarLookupPort`(신규 포트 0).
- **자체 직렬화기**. 외부 iCal 라이브러리 미도입(신규 의존성 0 관례). ← **게이트1에서 Maxi 확인 지점**(ical4j 선호 시 변경 가능).
- **SecurityConfig 확장**. `auth.requestMatchers(HttpMethod.GET, "/ical/feed/*").permitAll()` — FR-DB-03 defense-in-depth(GET-only·단일 세그먼트) 동형. `/api/**`보다 앞. 60줄 임계 주의(FR-MF-01 선례).
- **Clock 주입**. DTSTAMP·롤링 윈도 now는 Clock(identity-access 관례, FR-CA-01/FR-PR-02 선례).
- **me-scope**. 관리 API JWT-only(PAT 401).
- **(G3) whoami 변경 0**. 피드 상태는 전용 GET API로 조회 → whoami view-layer 미변경 → 슬라이스 mock fanout 회피(FR-PR/PF 대비 단순). 긍정적 격리.
- **(G4) issue-tracking 변경 0**. `UserCalendarLookupPort` 완전 재사용 → issue-tracking adapter 무변경. 한 PR = identity-access 단일 BC(+shared-kernel 무변경).
- **(G5) product 문서 동기화(머지 시)**. product §5.2 D3 `user_calendar_tokens(user_id, token, revoked_at)` → `token_hash`(revoked_at 제거)로 정정. §명세 변경 전수 동기화(fr-index는 FR 카운트 불변, D1~D7 체크만).

## 측정 가능한 완료 기준

1. 발급→익명 구독(.ics 파싱)→재발급(기존 404)→취소(404) **E2E** 통과.
2. RFC 5545 직렬화 **단위 테스트** — 이스케이핑(특수문자·한글), 75옥텟 폴딩, CRLF, all-day DTEND exclusive, UTC Worklog. (검증: 표준 iCal 파서 또는 골든 문자열 대조.)
3. 익명 엔드포인트 **negative-probe** — 무효/취소 토큰 404, 응답에 다른 사용자 데이터·원문 토큰·해시 미노출.
4. me-scope 통합 테스트 — PAT 401, 타 사용자 토큰 접근 불가.
5. 백엔드 `:modules:identity-access:test` + ktlint + detekt green. 프론트 typecheck+lint+vitest+E2E green. `verify-master-plan.sh` 123/123.
6. personalization BC 12/12 완료(product §5.2 D1~D7 전부 체크).

## Brainstorming Check

✅ 통과 (1회 iteration). 적대적 새니티 패스에서 5개 gap 발견 → 전부 스펙 보강으로 흡수(Maxi 결정 불요).
- G1 `.ics` 경로 추출 안전성(hex 토큰) · G2 Cache-Control · G3 whoami 무변경(fanout 회피) · G4 issue-tracking 무변경(포트 재사용) · G5 머지 시 product 문구 동기화.
- **게이트1 Maxi 확인 지점 1개**: 자체 iCal 직렬화기 vs ical4j(관례상 자체 직렬화 채택, 이견 시 변경).
- RFC 5545 footgun(이스케이핑·75옥텟 폴딩·CRLF·all-day exclusive DTEND)은 단위 테스트 완료 기준 #2로 강제.
