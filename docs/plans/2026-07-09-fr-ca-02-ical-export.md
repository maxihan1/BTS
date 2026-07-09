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

## Plan

> 모듈: identity-access (단일 BC). 백엔드 패키지 `com.atlas.bts.identity.calendar`(FR-CA-01 재사용).
> 마이그레이션 V034. 프론트 선례: `settings.pats.tsx`(발급/1회복사/취소) · code-based router · Header 네비.
> 회귀 주의: whoami 무변경(slice mock fanout 0) · issue-tracking/shared-kernel 무변경 · 포트는 이미 배선됨(CalendarPortConfig, 신규 @MockBean 불요).

### Task 1. V034 `user_calendar_tokens` 마이그레이션

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/identity-access/src/main/resources/db/migration/V034__user_calendar_tokens.sql`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/migration/SchemaMigrationTest.kt`(있으면 카운트 갱신)]
- depends-on: []

**RED**: 마이그레이션 카운트/스키마 가드 테스트가 있으면(예. `SchemaMigrationTest`) V034 추가 전 기대 테이블 수 불일치로 실패하는 것을 확인. 없으면 Testcontainers 부팅 시 테이블 부재로 T2 repo 테스트가 실패(RED 위임).
**GREEN**:
```sql
CREATE TABLE user_calendar_tokens (
    user_id     UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    token_hash  VARCHAR(64) NOT NULL UNIQUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```
- L1 SQL 주석(한글 역할). identity-access=JdbcTemplate → **init_codegen 미러 불요**(jOOQ 아님, FR-PF 선례).
- 기존 마이그레이션 카운트 가드가 있으면 기대값 +1 갱신(memory: migration count guard 깸).
**REFACTOR**: 컬럼 주석/인덱스 확인(token_hash UNIQUE가 익명 조회 인덱스 겸용).
**검증**: `./gradlew :modules:identity-access:test --tests '*SchemaMigration*'` (또는 T2에서 검증).

### Task 2. CalendarFeedToken minter + CalendarFeedTokenRepository

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/calendar/CalendarFeedToken.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/calendar/CalendarFeedTokenRepository.kt`, `.../test/.../calendar/CalendarFeedTokenTest.kt`, `.../test/.../calendar/CalendarFeedTokenRepositoryTest.kt`]
- depends-on: [1]

**RED**:
- `CalendarFeedTokenTest` — `generate()`가 매번 다른 rawToken(64 hex) + 결정적 SHA-256 hash(64) 반환. `hash(raw)` 동일입력 동일출력.
- `CalendarFeedTokenRepositoryTest`(Testcontainers) — `upsert(userId, hash)` 후 `findUserIdByHash(hash)`=userId · 재upsert가 rotate(이전 hash 조회 실패) · `deleteByUserId` 후 조회 null · `findByUserId` created_at 반환.
**GREEN**:
- `CalendarFeedToken` — `TrustedDeviceToken` 동형(32바이트 `SecureRandom` hex + `MessageDigest` SHA-256, DEVELOPMENT.md §1.1.1 원문 미저장).
- `CalendarFeedTokenRepository`(JdbcTemplate) — `INSERT ... ON CONFLICT (user_id) DO UPDATE SET token_hash=EXCLUDED.token_hash, created_at=NOW()` · `SELECT user_id WHERE token_hash=?` · `DELETE WHERE user_id=?` · `SELECT created_at WHERE user_id=?`.
**REFACTOR**: 상수 추출·KDoc(원문 미저장 정책 명시).
**검증**: `./gradlew :modules:identity-access:test --tests '*CalendarFeedToken*'`

### Task 3. IcalSerializer — RFC 5545 자체 직렬화기 (pure)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/calendar/IcalSerializer.kt`, `.../test/.../calendar/IcalSerializerTest.kt`]
- depends-on: []

**RED**: `IcalSerializerTest` — 골든 문자열/구조 단언.
- 이슈 이벤트(start~due)→`DTSTART;VALUE=DATE` + `DTEND`=due+1(exclusive) · due만→단일일 · Worklog→`DTSTART:<UTC>Z`~`DTEND`.
- 이스케이핑: `,` `;` `\` 개행 · **한글 SUMMARY 75옥텟 폴딩**(3바이트 경계) · **CRLF** 줄바꿈 · UID 안정성(`issue-<key>@bts`/`worklog-<id>@bts`).
- 빈 입력→유효 빈 VCALENDAR(VEVENT 0).
**GREEN**: `IcalSerializer.serialize(issues: List<CalendarIssueView>, worklogs: List<CalendarWorklogView>, now: Instant, appBaseUrl: String): String`. escape/fold/CRLF 헬퍼. VCALENDAR wrapper(VERSION/PRODID/CALSCALE/METHOD/X-WR-CALNAME).
**REFACTOR**: escape·fold 함수 분리 + KDoc(75옥텟=UTF-8 바이트 주의).
**검증**: `./gradlew :modules:identity-access:test --tests '*IcalSerializer*'` (테스트 검증용 ical4j는 `testImplementation`만 허용 — prod 의존성 0. 또는 골든 문자열 대조).

### Task 4. CalendarFeedService — 토큰 생명주기 + 피드 생성

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/calendar/CalendarFeedService.kt`, `.../test/.../calendar/CalendarFeedServiceTest.kt`]
- depends-on: [2, 3]

**RED**: `CalendarFeedServiceTest`(mock repo/port/profile) —
- `issue(userId)`→raw 토큰 반환 + repo.upsert(hash) 호출(raw 미저장) · rotate 시 새 raw.
- `revoke(userId)`→repo.deleteByUserId.
- `status(userId)`→enabled/createdAt(원문 없음).
- `generateFeed(token)`→hash로 userId 조회(실패시 예외/null→404 위임) · 윈도 today(zone)−30~+180 계산 · 포트 직접 호출(90일 캡 없음, `CalendarService`와 별도) · `IcalSerializer.serialize` 반환.
**GREEN**: `CalendarService` timezone 로직(`resolveZone`) 재사용 패턴. **Clock 주입**(`=Clock.systemUTC()`). 포트 `listAssignedScheduledIssues`/`listWorklogs` 직접 호출(윈도=LocalDate + Instant). viewer=토큰 소유자.
**REFACTOR**: 윈도 상수(−30/+180)·KDoc(왜 CalendarService 대신 포트 직접 호출: worklog Instant 보존 + 윈도 캡).
**검증**: `./gradlew :modules:identity-access:test --tests '*CalendarFeedService*'`

### Task 5. CalendarFeedController — 관리 API (POST/GET/DELETE me-scope)

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/calendar/CalendarFeedController.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/calendar/CalendarFeedDtos.kt`, `.../test/.../calendar/CalendarFeedControllerTest.kt`]
- depends-on: [4]

**RED**(`@WebMvcTest` 슬라이스): POST→201 `{feedUrl, token, createdAt}`(원문 1회) · GET→200 `{enabled, createdAt?}` · DELETE→204 · **PAT 인증→401**(me-scope JWT-only) · 미인증→401.
**GREEN**: `currentUserId`(JWT subject) 추출(FR-PR 컨트롤러 선례). `feedUrl`=`app.base-url` + `/ical/feed/<raw>.ics`(발급 응답에서만 조합). DTO 3종.
**REFACTOR**: base-url 주입(@Value/config)·KDoc(원문 노출은 발급 응답 1회 한정).
**검증**: `./gradlew :modules:identity-access:test --tests '*CalendarFeedControllerTest*'`

### Task 6. IcalFeedController — 익명 피드 + SecurityConfig permitAll

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/calendar/IcalFeedController.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/config/SecurityConfig.kt`, `.../test/.../calendar/IcalFeedControllerTest.kt`]
- depends-on: [4]

**RED**: `IcalFeedControllerTest` — `GET /ical/feed/{token}.ics`→200 `text/calendar; charset=utf-8` + `Cache-Control: private, no-cache` · 무효/취소 토큰→**404** · 응답에 다른 사용자 데이터/원문/해시 미노출. SecurityConfig permitAll 매처 단위 검증(GET-only).
**GREEN**: `IcalFeedController` — `@GetMapping("/ical/feed/{token}.ics")`, `CalendarFeedService.generateFeed(token)` 호출, null/미존재→`ResponseStatusException(404)`. SecurityConfig에 `auth.requestMatchers(HttpMethod.GET, "/ical/feed/*").permitAll()`(FR-DB-03 defense-in-depth 동형, `/api/**` 앞). DEVELOPMENT.md §1.4 예외 주석(ADR 링크).
**REFACTOR**: 60줄 임계 주의(FR-MF-01 선례) · KDoc(404 수렴=probe 최소화).
**검증**: `./gradlew :modules:identity-access:test --tests '*IcalFeedControllerTest*'`

### Task 7. 백엔드 통합 테스트 (full-boot · negative-probe · PAT 401)

**메타**.
- agent: `security-engineer`
- files: [`.../test/.../calendar/CalendarFeedIntegrationTest.kt`]
- depends-on: [5, 6]

**RED/GREEN**(prod 프로파일 + RANDOM_PORT + Testcontainers, FR-CA-01/identity-access 부팅 레시피): 발급→익명 GET .ics 파싱(이슈+Worklog VEVENT)→재발급(기존 URL 404)→취소(404) 왕복. **negative-probe**: 응답 본문 `doesNotContain` 원문토큰/token_hash/타 사용자. PAT로 관리 API→401. 빈 캘린더→유효 .ics 200.
**검증**: `./gradlew :modules:identity-access:test --tests '*CalendarFeedIntegrationTest*'`

### Task 8. 프론트 api/calendarFeed + useCalendarFeed 훅 + Zod

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/api/calendarFeed.ts`, `apps/web/src/api/calendarFeed.test.ts`, `apps/web/src/api/useCalendarFeed.ts`, `apps/web/src/api/useCalendarFeed.test.ts`]
- depends-on: []

**RED**: `issueCalendarFeed()`(POST→`{feedUrl, token, createdAt}`) · `getCalendarFeed()`(GET→`{enabled, createdAt?}`) · `revokeCalendarFeed()`(DELETE) · `useCalendarFeed` 훅 mutation onSuccess가 `['calendar','feed']` invalidate. Zod 스키마는 백엔드 DTO 계약과 일치(spec §API grep, invent 금지).
**GREEN**: `apiFetch` 사용(관리 API는 Bearer 정상). Zod parse.
**REFACTOR**: 스키마 export·KDoc.
**검증**: `pnpm --filter web test calendarFeed`

### Task 9. /settings/calendar 페이지 + router + MSW

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/routes/settings.calendar.tsx`, `apps/web/src/routes/settings.calendar.test.tsx`, `apps/web/src/components/settings/CalendarFeedCard.tsx`, `apps/web/src/components/settings/CalendarFeedCard.test.tsx`, `apps/web/src/router.ts`, `apps/web/src/components/Header.tsx`, `apps/web/src/mocks/calendar-feed-handlers.ts`, `apps/web/src/mocks/handlers.ts`]
- depends-on: [8]

**RED**: `CalendarFeedCard.test` — 미발급→"구독 URL 발급" 버튼 · 발급→URL 1회표시+복사버튼+"다시 표시되지 않습니다" 경고 · 재발급 확인(기존 URL 무효 경고) · 취소. `settings.calendar.test` 라우트 마운트.
**GREEN**: `settings.pats.tsx` 발급/1회복사/취소 패턴 미러. RouteAdapter export + router.ts 등록(code-based, memory: .ts JSX 제약→adapter). Header 네비 링크(`/settings/keymap` 인접). MSW `calendar-feed-handlers`(stateful 발급/취소, memory: MSW mutation stateful) + `handlers.ts` aggregator 등록. 필수-nullable 신규 필드 MSW stub 누락 주의(memory: E2E만 적발).
**REFACTOR**: i18n 라벨·clipboard 실패 폴백.
**검증**: `pnpm --filter web test settings.calendar CalendarFeedCard`

### Task 10. E2E — 발급/구독/재발급/취소

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/calendar-feed.spec.ts`]
- depends-on: [9]

**RED/GREEN**: MSW 시나리오(localStorage 토글, memory) — 미발급→발급→URL 표시/복사→재발급 경고→취소→미발급 복귀. 기존 profile/calendar E2E 무회귀 동반 실행(memory: UI PR defer E2E 회귀 잠복).
**검증**: `pnpm --filter web test:e2e calendar-feed`

## Plan 메타

- task 수: 10 (각 TDD 사이클)
- 예상 wave: 6 (W1: T1·T3·T8 병렬 / W2: T2 / W3: T4 / W4: T5·T6 병렬 / W5: T7·T9 병렬 / W6: T10). 프론트 wave당 1개(race 회피). 백엔드 동일 모듈이라 compile 직렬화(bts-impl 처리).
- TDD 강제: yes (test→feat 커밋 순서 검증)
- agent 분포: security 5(T2·4·5·6·7 토큰/익명경로/보안) · backend 1(T3 직렬화) · db 1(T1) · frontend 2(T8·9) · qa 1(T10)
- 추가 검증: ktlint·detekt(--rerun-tasks, 캐시 false-green 주의) · typecheck(tsconfig.app) · vitest · playwright · verify-master-plan 123/123
- 머지 시 동기화: product §5.2 D1~D7 체크 + D3 문구(token→token_hash) · CLAUDE/README personalization 11/12→12/12 · glossary "캘린더 피드 토큰"

## 리뷰 결과 (← /bts-review-plan 채움)
