# FR-PF-02 기본 뷰/시작 페이지 — 스펙

> slug: fr-pf-02-start-page | BC: identity-access (논리 personalization §3.2) | PR #246
> ADR: [docs/decisions/2026-07-08-fr-pf-02-start-page.md](../decisions/2026-07-08-fr-pf-02-start-page.md)
> 동형 선례: FR-PF-01 (PR #245) — theme/locale/dateFormat 5계층 확장

## 배경 / 목표

로그인 직후 이동하는 페이지를 사용자가 지정할 수 있게 한다. 현재는 모든 사용자가 `/dashboard`(단순 환영 페이지)로 고정 이동한다. FR-PF-01의 `user_preferences`(V031) + `GET/PATCH /api/v1/users/me/preferences` + whoami view-layer 위에 `start_page` 필드 하나를 확장한다.

## 사용자 시나리오 (Given-When-Then)

- **S1 — 시작 페이지 지정 후 적용**
  Given 로그인한 사용자가 `/settings/preferences`에 있고,
  When 시작 페이지를 "내 이슈"로 선택하면,
  Then PATCH가 즉시 저장되고(`startPage: 'my_issues'`), 다음 로그인 시 `/issues?assignee=<본인 userId>`로 이동한다.

- **S2 — 미설정 사용자 기본값**
  Given 시작 페이지를 한 번도 설정하지 않은 사용자가,
  When 로그인하면,
  Then 기본값 `dashboards`에 따라 `/dashboards`로 이동한다.

- **S3 — returnTo 우선**
  Given 미인증 사용자가 보호된 URL(`/issues/PROJ-5`)에 접근해 `/login?returnTo=/issues/PROJ-5`로 튕겼고,
  When 로그인 폼 제출에 성공하면,
  Then start_page를 무시하고 원래 가려던 `/issues/PROJ-5`로 이동한다. (returnTo > start_page)

- **S4 — 손상된 저장값 방어**
  Given 어떤 이유로 whoami의 startPage가 화이트리스트 밖 값(또는 부재)이면,
  When 로그인하면,
  Then 프론트가 매핑 실패를 감지해 기본 `/dashboards`로 폴백한다(깨진 라우팅 없음).

- **S5 — 봇(PAT) 무관**
  Given PAT로 인증한 봇 계정은 UI 로그인/navigate를 하지 않으며,
  When whoami를 호출하면,
  Then startPage는 상수 기본값(`dashboards`)으로 고정 반환된다(조회 없음).

## 기능 요구사항 (FR)

- **FR1**. `user_preferences.start_page VARCHAR(32) NOT NULL DEFAULT 'dashboards'` 컬럼 추가 (V032, ALTER ADD COLUMN).
- **FR2**. 도메인 `UserPreferences.startPage: String` + companion `START_PAGES = {dashboards, my_issues, issues, inbox}`, `DEFAULT_START_PAGE = "dashboards"`.
- **FR3**. `GET /api/v1/users/me/preferences` 응답에 `startPage`, `PATCH` 요청에 `startPage?`(2-state, 부재=미변경) 포함.
- **FR4**. `PATCH` 시 백엔드가 `START_PAGES` 화이트리스트로 검증 — 위반 시 기존 `PreferencesValidationException` → 400 `PREFERENCES_VALIDATION_FAILED`(부분 적용 없음, 원자성).
- **FR5**. whoami 응답에 `startPage` view-layer 필드 노출(JWT 분기=조회값, PAT 분기=상수 기본값, DTO 기본값 리터럴 `"dashboards"`).
- **FR6**. `PreferencesForm`에 "시작 페이지" `<Select>` 추가 — 4개 옵션(대시보드 목록/내 이슈/전체 이슈 목록/받은 알림함), 선택 즉시 PATCH + 응답 재동기화(기존 theme Select 패턴).
- **FR7**. 로그인 성공 후 목적지 결정:
  - `routes/login.tsx handleSuccess`가 우선순위 **returnTo(안전 검증 통과) > start_page 매핑 경로 > `/dashboards`**로 navigate.
  - `auth/routeGuard.ts redirectIfAuth`의 fallback `/dashboard`도 start_page 매핑으로 일관화(returnTo는 기존대로 우선).
- **FR8**. 키→경로 매핑 상수(프론트) — `dashboards→/dashboards`, `my_issues→/issues?assignee=${userId}`(동적), `issues→/issues`, `inbox→/inbox`. userId는 whoami `user.userId`.

## 비기능 요구사항 (NFR)

- **NFR1 (보안)**. 저장은 논리 키 화이트리스트만 — 임의 경로/오픈 리다이렉트 불가. 백엔드가 값 집합 단일 진실 출처.
- **NFR2 (하위호환)**. whoami `startPage`는 프론트 Zod `z.string().optional()` — 인라인 whoami mock fan-out 회귀 회피(`zod-schema-strengthen-inline-mock-fanout`). 부재 시 프론트 폴백으로 안전. 기존 세션/PAT 무중단.
- **NFR3 (의존성)**. 신규 외부 의존성 0. 기존 preferences/whoami 인프라 재사용.
- **NFR4 (일관성)**. 도메인 companion 값 목록 ↔ 프론트 상수 동기화 계약(도메인 KDoc 명시). theme/locale/dateFormat와 동일 5계층 확장.

## API 인터페이스 (REST)

기존 엔드포인트 확장(신규 엔드포인트 0).

```
GET  /api/v1/users/me/preferences   (JWT)
 → 200 { theme, locale, dateFormat, startPage }

PATCH /api/v1/users/me/preferences  (JWT, X-XSRF-TOKEN)
 body(2-state) { startPage?: "dashboards"|"my_issues"|"issues"|"inbox", ... }
 → 200 { theme, locale, dateFormat, startPage }   (병합된 effective 값)
 → 400 { code:"PREFERENCES_VALIDATION_FAILED", message } (화이트리스트 밖)

GET /api/v1/users/me (whoami)
 → 200 { ..., startPage: "dashboards" }  (non-null, PAT=상수 기본값)
```

## 데이터 모델 변경

```sql
-- V032__user_preferences_start_page.sql (identity-access, jdbc-only, jOOQ codegen 불요)
ALTER TABLE user_preferences
  ADD COLUMN start_page VARCHAR(32) NOT NULL DEFAULT 'dashboards';
```

Repository(`JdbcUserPreferencesRepository`) 5지점 동반 갱신: SELECT 컬럼 · INSERT 컬럼/VALUES · ON CONFLICT SET · RowMapper · upsert 파라미터 맵.

## 키 → 경로 매핑 (프론트 화이트리스트)

| 논리 키 | 경로 | 라벨 | 비고 |
|---|---|---|---|
| `dashboards` | `/dashboards` | 대시보드 목록 | 기본값. Header 메인 nav |
| `my_issues` | `/issues?assignee=${userId}` | 내 이슈 | userId 동적 주입(whoami) |
| `issues` | `/issues` | 전체 이슈 목록 | |
| `inbox` | `/inbox` | 받은 알림함 | |

매핑 밖 키(손상값)·userId 부재 → `/dashboards` 폴백.

## 엣지 케이스

- **EC1**. whoami startPage가 화이트리스트 밖 → 프론트 매핑 실패 감지 → `/dashboards` 폴백. (백엔드 검증이 1차 방어, 프론트 폴백이 2차 심층 방어)
- **EC2**. whoami에 startPage 부재(구세션/PAT/mock) → optional → undefined → `/dashboards` 폴백.
- **EC3**. `my_issues`인데 whoami userId 부재(이론상 불가 — userId required) → `/dashboards` 폴백(방어).
- **EC4**. returnTo + start_page 동시 존재 → returnTo(안전 검증 통과 시) 우선. 안전 검증 실패한 returnTo → start_page로.
- **EC5**. PATCH `startPage`가 허용값 밖 → 400, 다른 필드도 미변경(검증 우선 → 원자성, FR-PF-01 selfsame).
- **EC6**. start_page를 바꿔도 현재 세션 라우팅엔 영향 없음(로그인 시점 결정) — 다음 로그인부터 적용. 의도된 동작.
- **EC7 (보안 심층 방어)**. 4개 후보 라우트(`/dashboards`·`/issues`·`/inbox`·`/dashboard`)는 모두 `requireAuthAndPasswordChanged`(= requireAuth + requirePasswordChanged + requireMfaEnrolled) 가드를 갖는다. 따라서 비밀번호 변경 강제·MFA 등록 강제 상태인 사용자가 start_page로 navigate하면 그 라우트 가드가 `/settings/password`·`/settings/mfa`로 재리다이렉트한다. handleSuccess는 start_page 강제 우회 위험이 없어 별도 password/MFA 체크가 불필요(라우트 가드가 권위 출처).

## 제약 / 결정

- **[게이트1 검토 포인트] returnTo 우선순위 도입**. 기존 `login.tsx handleSuccess`는 returnTo를 무시하고 무조건 `/dashboard`로 갔다(비일관 — `redirectIfAuth`는 returnTo 우선). 본 FR에서 handleSuccess도 `returnTo > start_page > dashboards`로 통일한다. 이는 기존 로그인 플로우의 returnTo 무시 동작을 개선하는 변경을 포함한다. → Maxi 확인 대상.
- **assignee=me 미지원 → userId 동적 주입**. `/issues`는 `assignee=<UUID>`/`assignee=unassigned`만 지원(`me` 센티널 없음). `my_issues`는 로그인 시점 whoami userId를 주입한다. issue-tracking BC에 센티널 추가(범위 확장·BC 침범)는 하지 않는다.
- **BC 경계**. 프론트 라우팅/설정은 personalization view-layer. 백엔드는 identity-access(UserPreferences 1:1). issue-tracking 직접 변경 없음.
- **완제품 기준**. TDD red→green, 화이트리스트 검증, whoami 하위호환, E2E 포함.

## 측정 가능한 완료 기준

1. V032 적용 후 `user_preferences.start_page` 존재, 기존 행 기본값 `dashboards` 백필. `V032MigrationTest` green.
2. 백엔드: `JdbcUserPreferencesRepositoryTest`(startPage 왕복), `UserPreferencesServiceTest`(검증/병합/기본값), `PreferencesControllerMvcTest`(GET/PATCH startPage + 400), `WhoamiControllerTest`(JWT/PAT startPage) green.
3. 프론트: `PreferencesForm` 시작 페이지 Select 렌더/PATCH 단위 테스트, `login.tsx` navigate 우선순위(returnTo/start_page/폴백) 단위 테스트 green.
4. E2E: 설정에서 시작 페이지 변경 → 저장 왕복 유지 → 재로그인 → 지정 경로 URL 도착(`toHaveURL`) 검증. 기본값·폴백 경로 포함.
5. 5모듈 lint/detekt/ArchUnit + pnpm verify green. BC 격리(issue-tracking 미변경) 유지.

## Brainstorming Check

✅ 통과 (1 iteration). 자가 sanity check로 발견한 gap 1건(로그인 후 start_page navigate가 비밀번호/MFA 강제 가드를 우회하는지) 코드 검증 → 4개 후보 라우트 모두 `requireAuthAndPasswordChanged` 보유 확인, 우회 없음(EC7 추가). returnTo 우선순위는 게이트1 검토 포인트로 명시. 잔여 gap 없음.
