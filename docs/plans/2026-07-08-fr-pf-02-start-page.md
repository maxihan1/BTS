# FR-PF-02 기본 뷰/시작 페이지

> slug: fr-pf-02-start-page
> type: feature
> agent: backend-engineer (task별 재배정 — db/backend/frontend/qa)
> primary_bc: identity-access (논리 BC personalization)
> 생성: 2026-07-08

## Brief

**원문**. fr-pf-02 진행해줘

**FR-PF-02 (personalization §3.2)** — 로그인 후 사용자가 지정한 시작 페이지로 자동 라우팅.
- D1 도메인 / D2 명세(로그인 후 리다이렉트) / D3 `user_preferences.start_page` 컬럼 / D4 백엔드(기존 preferences API 활용) / D5 백엔드 테스트 / D6 프론트 UI(설정 페이지 + 로그인 후 자동 라우팅) / D7 E2E
- **선행**. §3.1 FR-PF-01(환경설정, V031) 완료 → 같은 user_preferences 테이블 확장
- **동형 참조**. FR-PF-01 (PR #245): whoami view-layer 노출, enum 검증 로컬 @ExceptionHandler, JWT-only

**classify**. 원판정 type=ui → product D1~D7 근거로 feature 교정.

## 도메인 정리 (← /bts-domain 채움)

- **BC**: identity-access (논리 personalization §3.2)
- **영향 엔티티**: `UserPreferences` 기존 확장 (`startPage` 필드 추가). **신규 엔티티·테이블 없음** — `user_preferences`(V031)에 컬럼 하나 ALTER ADD.
- **새 용어**: "시작 페이지(start page)" — 로그인 직후 자동 이동할 목적지. 논리 키로 표현. (glossary 추가 후보, Maxi 승인 대기)
- **저장 방식 (ADR D1)**: 경로 문자열 아닌 **논리 키 화이트리스트**. 오픈 리다이렉트·리네이밍 취약성 차단.
  - 허용 키: `dashboards`(기본) · `my_issues` · `issues` · `inbox`
  - 키→경로: `dashboards→/dashboards`, `my_issues→/issues?assignee=me`, `issues→/issues`, `inbox→/inbox`
- **검증 (ADR D2)**: 백엔드 `UserPreferences.START_PAGES` + 기존 `validateAllowed`/`PreferencesValidationException` 재사용. 신규 예외 0.
- **로그인 라우팅 (ADR D3)**: whoami view-layer `startPage` 노출 → `routes/login.tsx handleSuccess`에서 `user.startPage` 읽어 navigate. `routeGuard.ts redirectIfAuth` fallback도 일관화. 백엔드 리다이렉트 API 미신설.
- **기존 결정 충돌**: 없음. FR-PF-01 ADR 패턴 전면 재사용.
- **관련 ADR**: [docs/decisions/2026-07-08-fr-pf-02-start-page.md](../decisions/2026-07-08-fr-pf-02-start-page.md) (생성됨)
- **동형 참조**: FR-PF-01 (PR #245) — theme/locale/dateFormat과 동일 5계층 확장 패턴.

## 스펙 (← /bts-spec Phase A 채움)

전체 스펙. [docs/specs/2026-07-08-fr-pf-02-start-page.md](../specs/2026-07-08-fr-pf-02-start-page.md)

핵심 시나리오 요약.
- 설정에서 시작 페이지 선택(4종) → PATCH 즉시 저장 → **다음 로그인 시 그 경로로 자동 이동**.
- 로그인 후 목적지 우선순위: **returnTo(안전) > start_page 매핑 > /dashboards**.
- 논리 키 화이트리스트 저장(오픈 리다이렉트 차단). `my_issues`는 whoami userId 동적 주입(`/issues?assignee=${userId}`).

**게이트1 검토 포인트 2건**.
1. returnTo 우선순위 도입 — 기존 handleSuccess의 returnTo 무시(항상 /dashboard) 동작을 `returnTo > start_page`로 개선(로그인 플로우 변경 포함).
2. `assignee=me` 미지원 → userId 동적 주입으로 해결(issue-tracking BC 미변경).

## Brainstorming Check (← /bts-spec Phase B 채움)

✅ 통과 (1 iteration). 보안 gap 후보(start_page navigate가 비밀번호/MFA 강제 우회?) 코드 검증 → 4개 후보 라우트 모두 `requireAuthAndPasswordChanged` 보유, 우회 없음(EC7). 잔여 gap 없음.

## Plan (← /bts-plan 채움)

> 전체 8 task. 백엔드(identity-access) 직렬 체인(T1→T2→T3→T4, 파일 겹침·모듈 컴파일 직렬화) + 프론트(T5→T6/T7 병렬) + E2E(T8). 백엔드와 프론트는 서로 독립(계약 기반)이라 wave 병렬 가능.

### Task 1. V032 마이그레이션 — user_preferences.start_page 컬럼

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/identity-access/src/main/resources/db/migration/V032__user_preferences_start_page.sql`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/db/V032MigrationTest.kt`]
- depends-on: []

**RED**: `V032MigrationTest` — Testcontainers로 마이그레이션 적용 후 `user_preferences.start_page` 컬럼 존재(VARCHAR(32), NOT NULL, DEFAULT 'dashboards') + 기존 행이 있으면 'dashboards'로 백필됨. (V031MigrationTest 패턴 미러)

**GREEN**:
```sql
-- V032__user_preferences_start_page.sql (identity-access, jdbc-only)
ALTER TABLE user_preferences
  ADD COLUMN start_page VARCHAR(32) NOT NULL DEFAULT 'dashboards';
```

**REFACTOR**: 파일 L1 한국어 주석. init_codegen 미러 불요(jdbc-only) 확인.

**주의**. 마이그레이션 개수 카운트 가드(SchemaMigrationTest류)가 있으면 함께 +1 갱신([[fr-pm-permission-seed-migration-test-coupling]] 교훈). grep으로 존재 확인 후 대응.

**검증**: `./gradlew :backend:identity-access:test --tests '*V032MigrationTest*'`

### Task 2. 도메인 + Repository 확장 (startPage 왕복)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/preferences/UserPreferences.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/preferences/JdbcUserPreferencesRepository.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/preferences/JdbcUserPreferencesRepositoryTest.kt`]
- depends-on: [1]

**RED**: `JdbcUserPreferencesRepositoryTest` — `upsert(prefs.copy(startPage="my_issues"))` 후 `findByUserId`가 `startPage="my_issues"` 반환. 기본값 케이스(행 삽입 시 미지정→DB default 'dashboards')도.

**GREEN**:
- `UserPreferences`에 `startPage: String` 필드 + companion `START_PAGES = setOf("dashboards","my_issues","issues","inbox")`, `DEFAULT_START_PAGE = "dashboards"`.
- `JdbcUserPreferencesRepository` **5지점**: SQL_FIND_BY_USER_ID SELECT 컬럼 · SQL_UPSERT INSERT 컬럼/VALUES · ON CONFLICT SET · upsert 파라미터 맵 · UserPreferencesRowMapper. (하나라도 누락 시 NPE/유실)

**REFACTOR**: companion KDoc에 프론트 `api/preferences.ts` START_PAGES 동기화 계약 명시(기존 theme 패턴).

**검증**: `./gradlew :backend:identity-access:test --tests '*JdbcUserPreferencesRepositoryTest*'`

### Task 3. Service 검증/병합 + DTO + Controller

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/preferences/UserPreferencesService.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/dto/PreferencesResponse.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/dto/PreferencesPatchRequest.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/web/PreferencesController.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/preferences/UserPreferencesServiceTest.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/web/PreferencesControllerMvcTest.kt`]
- depends-on: [2]

**RED**:
- `UserPreferencesServiceTest`: patch `startPage="inbox"` → effective startPage="inbox", 다른 필드 미변경. 허용값 밖(`"evil"`) → `PreferencesValidationException`(부분 적용 없음). 미지정 patch → current 유지 → 없으면 DEFAULT_START_PAGE.
- `PreferencesControllerMvcTest`: `GET`이 startPage 포함, `PATCH {startPage:"issues"}` 200 + 응답 반영, `PATCH {startPage:"bad"}` 400 `PREFERENCES_VALIDATION_FAILED`.

**GREEN**:
- `UserPreferencesService.patchPreferences` 검증 라인 `patch.startPage?.let { validateAllowed(it, START_PAGES, "시작 페이지") }` + effective 병합 + `defaults()` startPage + `PreferencesPatch.startPage`.
- `PreferencesResponse.startPage: String`, `PreferencesPatchRequest.startPage: String? = null`(2-state), Controller `toResponse`/`toPatch` 헬퍼에 startPage 전달.

**REFACTOR**: 검증 문구 일관성(기존 "테마"/"로케일" 스타일). 신규 예외/핸들러 도입 없음(기존 경로 재사용).

**검증**: `./gradlew :backend:identity-access:test --tests '*UserPreferencesServiceTest*' --tests '*PreferencesControllerMvcTest*'`

### Task 4. whoami startPage view-layer 노출 (JWT/PAT)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/web/WhoamiController.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/dto/WhoamiResponse.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/web/WhoamiControllerTest.kt`]
- depends-on: [3]

**RED**: `WhoamiControllerTest` — JWT 사용자 whoami가 `startPage`(저장값 또는 기본값) 반환. PAT(봇) whoami가 `startPage="dashboards"`(상수) 반환.

**GREEN**:
- `WhoamiResponse.startPage: String = "dashboards"`.
- WhoamiController JWT 분기 조립에 `startPage = preferences.startPage`, PAT 분기 `handlePat`에 `startPage = UserPreferences.DEFAULT_START_PAGE`.

**REFACTOR**: 세 경로(DTO 기본값·JWT·PAT) 동일값 수렴 주석.

**검증**: `./gradlew :backend:identity-access:test --tests '*WhoamiControllerTest*'`

### Task 5. 프론트 계약 — 키→경로 매핑 + Zod 확장

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/lib/start-page.ts`, `apps/web/src/lib/start-page.test.ts`, `apps/web/src/api/preferences.ts`, `apps/web/src/api/schemas.ts`]
- depends-on: []

**RED**: `start-page.test.ts` — `resolveStartPagePath(startPage, userId)`가 `dashboards→/dashboards`, `my_issues→/issues?assignee=<userId>`, `issues→/issues`, `inbox→/inbox`. 화이트리스트 밖 키·userId 부재 → `/dashboards` 폴백. `preferencesSchema`가 startPage enum 파싱.

**GREEN**:
- (신규) `lib/start-page.ts`: `START_PAGE_KEYS`, `START_PAGE_LABELS`(한국어), `resolveStartPagePath(key, userId)` 매핑+폴백, `isStartPage` 타입가드.
- `api/preferences.ts`: `preferencesSchema`에 `startPage: z.enum(START_PAGES)`, `PreferencesPatchBody`에 `startPage?`, `START_PAGES` 상수(백엔드 companion 미러).
- `api/schemas.ts`: `WhoamiResponseSchema`에 `startPage: z.string().optional()` (**`.optional()` 필수** — `.default()`는 z.infer non-optional화로 mock fan-out, [[zod-schema-strengthen-inline-mock-fanout]]).

**REFACTOR**: 매핑 테이블 주석 + ADR 링크.

**검증**: `pnpm --filter web test -- start-page`, `pnpm --filter web typecheck`

### Task 6. PreferencesForm 시작 페이지 Select

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/settings/PreferencesForm.tsx`, `apps/web/src/components/settings/PreferencesForm.test.tsx`]
- depends-on: [5]

**RED**: `PreferencesForm.test.tsx` — "시작 페이지" Select 렌더(4옵션), 옵션 선택 시 `patchPreferences({startPage})` 호출 + 응답 재동기화. 초기값은 whoami `user.startPage`(폴백 dashboards).

**GREEN**: 기존 theme Select 블록 복제 — `resolveInitialStartPage(user?.startPage)` + `useState` + `handleStartPageChange`(mutate + syncFromResponse) + `<Select>` 블록. `syncFromResponse`에 `setStartPage` 추가.

**REFACTOR**: 라벨 상수 `START_PAGE_LABELS` 재사용(T5). Select 접근성(name).

**검증**: `pnpm --filter web test -- PreferencesForm`

### Task 7. 로그인 후 라우팅 — handleSuccess 우선순위 + redirectIfAuth

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/routes/login.tsx`, `apps/web/src/routes/login.test.tsx`, `apps/web/src/auth/routeGuard.ts`, `apps/web/src/auth/routeGuard.test.ts`]
- depends-on: [5]

**RED**:
- `login.test.tsx`: handleSuccess가 (a) returnTo 있고 안전 → returnTo navigate, (b) returnTo 없음 + startPage='inbox' → `/inbox`, (c) startPage 부재 → `/dashboards`. (store user + window.location.search 조합)
- `routeGuard.test.ts`: `redirectIfAuth` fallback이 `/dashboard` 대신 start_page 매핑(returnTo 우선 유지).

**GREEN**:
- `login.tsx handleSuccess`: `const returnTo = safe(new URLSearchParams(window.location.search).get('returnTo'))` → returnTo ?? `resolveStartPagePath(user?.startPage, user?.userId)` → navigate. (`useAuthStore.getState().user`)
- `routeGuard.ts redirectIfAuth`: safeTo fallback을 `resolveStartPagePath(user?.startPage, user?.userId)`로. (store user 접근)

**REFACTOR**: returnTo 안전검증은 기존 `isSafeReturnTo` 재사용. 우선순위 주석(returnTo > start_page > dashboards).

**검증**: `pnpm --filter web test -- login routeGuard`, `pnpm --filter web typecheck`

### Task 8. E2E — 설정 변경→재로그인→URL 도착

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/start-page.spec.ts`, `apps/web/src/mocks/preferences-handlers.ts`, `apps/web/src/mocks/fixtures/auth-fixtures.ts`]
- depends-on: [6, 7]

**RED/시나리오**:
- MSW preferences PATCH가 startPage를 AUTH_USERS에 반영, whoami가 startPage 노출(mock).
- S1: 로그인 → /settings/preferences → 시작 페이지 "받은 알림함" 선택 → PATCH 바디 `{startPage:'inbox'}` 검증 → 저장 왕복 유지 → 로그아웃/재로그인 → `expect(page).toHaveURL(/\/inbox/)`.
- S2: 기본값 사용자 재로그인 → `/dashboards` 도착.

**GREEN**: mock 핸들러 + fixture에 startPage 필드. E2E는 실 백엔드 불필요(MSW).

**검증**: `pnpm --filter web test:e2e -- start-page`

## Plan 메타

- task 수: 8 (각 TDD 사이클)
- 예상 wave: 약 4 (W1: T1·T5 / W2: T2·T6·T7 / W3: T3 / W4: T4, T8은 T6·T7 완료 후)
- 백엔드 직렬(identity-access 파일 겹침·모듈 컴파일), 프론트 T6/T7 병렬, E2E 최후
- TDD 강제: yes (test 커밋 선행)
- 추가 검증: typecheck, ktlint, detekt, ArchUnit(BC 격리 — issue-tracking 미변경), vitest, playwright
- BC 격리: identity-access 단일. issue-tracking 직접 변경 0(assignee=me는 프론트 userId 주입으로 회피)

## 리뷰 결과 (← /bts-review-plan 채움)
