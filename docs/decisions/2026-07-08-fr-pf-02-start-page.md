# ADR — FR-PF-02 기본 뷰/시작 페이지: 논리 키 화이트리스트 + user_preferences 확장

> 날짜: 2026-07-08
> 상태: 결정됨 (Maxi 확정)
> 관련 FR: FR-PF-02 (기본 뷰/시작 페이지)
> 관련 slug: fr-pf-02-start-page | PR #246
> 선행: [2026-07-07-fr-pf-01-preferences-i18n-defer](2026-07-07-fr-pf-01-preferences-i18n-defer.md) (user_preferences 도입) · [2026-07-05-fr-pr-01-user-profile-placement](2026-07-05-fr-pr-01-user-profile-placement.md) (논리 BC personalization → 물리 identity-access)

## 맥락

FR-PF-02는 "로그인 후 사용자가 지정한 시작 페이지로 자동 이동"이다. product 문서(`docs/plan/product/personalization.md §3.2`)의 D3는 `user_preferences.start_page` 컬럼을 명시한다. FR-PF-01(환경설정)이 `user_preferences` 테이블(V031)과 `GET/PATCH /api/v1/users/me/preferences` + whoami view-layer를 이미 확립했으므로, 이 위에 필드 하나를 얹는 확장 성격이다.

결정이 필요한 지점은 세 가지였다.
1. 시작 페이지 값을 **경로 문자열**(`/dashboards`)로 저장할지, **논리 키**(`dashboards`)로 저장할지.
2. 옵션으로 제공할 페이지 집합과 기본값.
3. 로그인 후 라우팅을 어디서 수행할지.

## 결정

### D1. 저장 값 = 논리 키 화이트리스트 (경로 문자열 금지)

`start_page`에 임의 경로 문자열이 아니라 **고정 논리 키**를 저장한다. 프론트가 키→경로 매핑 테이블(화이트리스트)로 해석해 navigate한다.

- 허용 키: `dashboards`(기본) · `my_issues` · `issues` · `inbox`
- 키→경로 매핑: `dashboards → /dashboards`, `my_issues → /issues?assignee=me`, `issues → /issues`, `inbox → /inbox`
- 기본값: `dashboards`

**근거.** (1) 임의 경로 저장은 오픈 리다이렉트(`//evil.com`)·깨진 링크(경로 리네이밍 시 DB 값 무효화) 위험. 화이트리스트 논리 키는 이 두 문제를 원천 차단한다. (2) FR-PF-01의 theme(`light|dark|system`)·locale(`ko|en`)이 이미 "논리 키 저장 + 클라이언트 해석" 패턴이므로 일관. (3) 파라미터가 필요한 경로(`/projects/$projectKey/board`)는 특정 프로젝트에 종속돼 정적 시작 페이지로 부적합 → 옵션에서 제외.

### D2. 검증 = 백엔드 화이트리스트 (FR-PF-01 validateAllowed 재사용)

백엔드 `UserPreferences.START_PAGES` 집합으로 PATCH 값을 검증한다. 허용값 밖이면 기존 `PreferencesValidationException` → 로컬 `@ExceptionHandler` → 400 `PREFERENCES_VALIDATION_FAILED`. 신규 예외/핸들러 도입 없이 FR-PF-01 경로를 그대로 확장한다.

**근거.** 프론트 화이트리스트만으로는 API 직접 호출을 막지 못한다. 백엔드가 값 집합의 단일 진실 출처(도메인 companion object)이고, 프론트 상수는 그 미러(도메인 KDoc이 동기화 계약 명시).

### D3. 로그인 후 라우팅 = 프론트 view-layer 단일 지점

start_page를 whoami view-layer(`WhoamiResponse.startPage`)에 노출하고, `routes/login.tsx`의 `handleSuccess`에서 `user.startPage`를 읽어 매핑된 경로로 navigate한다. 이미 인증된 사용자가 `/login` 방문 시의 `redirectIfAuth` fallback(`auth/routeGuard.ts`)도 동일 매핑으로 일관화한다.

**근거.** 모든 로그인 성공 경로(일반/MFA/WebAuthn)가 `onSuccess` 직전 `setSession`으로 whoami를 store에 주입하므로, `handleSuccess` 시점에 startPage를 안전하게 읽을 수 있다. 백엔드 리다이렉트 API를 신설하지 않는다(프론트 라우팅 결정이므로 서버 왕복 불필요, FR-UX-04 slash-cmd의 "네비게이션은 클라이언트 dispatch" 선례 일관).

### D4. 모듈·저장소 = identity-access, user_preferences 확장

신규 엔티티·테이블 없이 `user_preferences`에 `start_page VARCHAR(32) NOT NULL DEFAULT 'dashboards'` 컬럼을 V032로 추가한다. 도메인 `UserPreferences`·Repository·Service·DTO·whoami를 FR-PF-01과 동일 패턴으로 확장한다.

**근거.** UserPreferences는 User와 1:1. VARCHAR(32)는 논리 키가 기존 3필드(theme/locale/date_format)보다 서술적일 수 있어 여유를 둔 것(`dashboards`=10자, `my_issues`=9자).

## 영향

- 신규 마이그레이션 `V032__user_preferences_start_page.sql` (identity-access, JdbcTemplate 전용 → jOOQ codegen 불요, ALTER TABLE ADD COLUMN).
- 기존 엔드포인트 `GET/PATCH /api/v1/users/me/preferences` 응답/요청에 `startPage` 필드 추가.
- whoami에 `startPage` view-layer 필드(백엔드 non-null 기본값 `dashboards`, 프론트 Zod optional — `zod-schema-strengthen-inline-mock-fanout` 회귀 회피).
- 프론트: `PreferencesForm`에 시작 페이지 Select, `login.tsx handleSuccess`·`routeGuard.ts` 라우팅, 키→경로 매핑 상수 신설.
- **신규 외부 의존성 0.**

## 폐기된 대안

- **경로 문자열 직접 저장** — 오픈 리다이렉트·리네이밍 취약. D1에서 화이트리스트 논리 키로 대체.
- **백엔드 리다이렉트 엔드포인트** (`GET /me/start-page` → 302) — 서버 왕복 불필요, 프론트 라우팅으로 충분. D3에서 배제.
- **프로젝트 종속 보드/백로그를 옵션에 포함** — 파라미터 종속이라 정적 시작 페이지로 부적합. 옵션 집합에서 제외.
