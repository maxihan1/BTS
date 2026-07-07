# FR-PF-01 환경 설정 (테마/언어/날짜포맷)

> slug: fr-pf-01-user-preferences
> type: api (full-stack feature)
> agent: backend-engineer (+ db / frontend / qa)
> primary_bc: identity-access (논리 BC personalization)
> 생성: 2026-07-07

## Brief

FR-PF-01 — 사용자별 환경 설정. 테마(라이트/다크) · 언어(locale) · 날짜 포맷.
- D1 도메인. UserPreferences
- D2 명세. 기본값 + 사용자 override
- D3 데이터 모델. user_preferences(theme, locale, date_format, ...)
- D4 백엔드. GET/PATCH /api/v1/users/me/preferences
- D5 백엔드 테스트
- D6 프론트. i18next + theme 토글 + date-fns-tz
- D7 E2E

정본. docs/plan/product/personalization.md §3.1

## 도메인 정리

- **물리 BC**. identity-access | **논리 BC**. personalization ("논리 BC ≠ 물리 모듈" 패턴, FR-PR-01 ADR·FR-UX-01 선례로 확립).
- **신규 엔티티**. `UserPreferences` — User와 1:1 (FR-PR-01 `UserProfile`, FR-PR-02 `UserStatus`, FR-PR-03 `UserOoo`와 동형). PK/FK = `user_id → users.id (ON DELETE CASCADE)`.
- **새 용어**. "환경 설정 (User Preferences)" — 사용자별 UI 표시 설정. 필드. `theme`(라이트/다크/시스템), `locale`(언어), `date_format`(날짜 표기).
- **기존 결정 충돌**. 없음. FR-PR-01 ADR(`docs/decisions/2026-07-05-fr-pr-01-user-profile-placement.md`)의 배치 패턴을 그대로 확장.
- **선행 stub 이관 (핵심)**. `PreferencesController.kt`에 `POST /api/v1/users/me/preferences` stub이 이미 존재 — 본문 미사용, 유일한 목적은 CSRF 토큰 검증 흐름 시연(T5). 의존 테스트 3곳.
  - `web/PreferencesControllerCsrfTest.kt` (POST 토큰 유/무 → 200/403)
  - `integration/PatAndConcurrencyIntegrationTest.kt` CSRF-B (토큰 없는 POST → 403)
  - `web/PasswordControllerMvcTest.kt` (참조)
  - **결정**. stub POST → 실제 `GET`(조회) + `PATCH`(수정)로 이관. CSRF 데모는 상태변경 메서드면 되므로 `PATCH` 대상으로 옮겨 의도 보존. spec에서 이관 방식 확정.
- **마이그레이션 번호**. identity-access 마지막 = V030. 신규 = **V031** (`user_preferences`). ⚠️ 동시 세션(fr-sl-01)은 slack-integration 모듈이라 V번호 네임스페이스 분리 — 머지 직전 재확인.
- **관련 ADR**. FR-PR-01 배치 ADR 재사용. FR-PF-01 신규 결정(기본값 정책 · POST→PATCH 이관 · i18n 범위)은 spec 확정 후 ADR 후보.
- **spec으로 미룬 갈림길**. (1) 기본값 정책(theme=system? locale=ko? date_format=?), (2) **i18next 범위** — 앱 전체 문자열 전수 번역 vs 인프라+설정 저장만(하드코딩 문자열은 후속), (3) locale 지원 언어 목록.

## 스펙

전체 스펙. [docs/specs/2026-07-07-fr-pf-01-user-preferences.md](../specs/2026-07-07-fr-pf-01-user-preferences.md)

**스코프 (Maxi 확정 2026-07-07)**.
- theme (light/dark/system) — `.dark` 전역 토글 + 저장. 완전 동작.
- locale — 저장 + `<html lang>`만. UI 번역(i18next)은 후속 에픽 보류(C안).
- date_format — 프리셋(iso/kr/us/eu) + 공통 포맷터로 앱 전체 즉시 적용(중앙 유틸 2 + 인라인 ~20 이관). 네이티브 Intl(신규 의존성 0).

**핵심 3줄**.
- 백엔드. `user_preferences`(V031) + `GET/PATCH /api/v1/users/me/preferences`. POST stub 제거 + CSRF 데모 3테스트 PATCH 이관.
- 프론트. `/settings/preferences` + 테마 토글(FOUC 방지 localStorage 프리하이드레이션) + date_format 라이브 프리뷰 + locale 저장.
- date_format 전면 이관 — 절대 날짜만, 상대시간 제외.

## Brainstorming Check

✅ 통과 (집중 gap 분석, 4건 발견·반영). G1 whoami 통합은 plan 결정 사항(권장: whoami에 preferences 추가). G2 상대시간 제외·G3 설정 내비·G4 PAT 정책은 스펙 반영.

## Plan

> TDD red→green→refactor. 각 task 메타(agent/files/depends-on)로 bts-impl이 wave 계산.
> identity-access는 JdbcTemplate 모듈 → **jOOQ codegen 불필요**(profile/status/ooo 선례).
> 백엔드 테스트: `./gradlew :backend:identity-access:test`. 프론트: `pnpm --filter web test`.

### Task 1. V031 user_preferences 마이그레이션

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/identity-access/src/main/resources/db/migration/V031__user_preferences.sql`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/db/V031MigrationTest.kt`]
- depends-on: []

**RED**. `V031MigrationTest` (Testcontainers) — `user_preferences` 테이블 존재 + 컬럼(theme/locale/date_format/created_at/updated_at) + 기본값(system/ko/iso) + PK(user_id) + FK(users ON DELETE CASCADE) 단언. 실패: 테이블 없음.

**GREEN**. `V031__user_preferences.sql` — 스펙 §데이터 모델 그대로. `user_id UUID PK REFERENCES users(id) ON DELETE CASCADE`, theme/locale/date_format `VARCHAR(16) NOT NULL DEFAULT`, timestamps.

**REFACTOR**. 컬럼 주석(COMMENT) + L1 한국어 헤더 주석.

**검증**. `./gradlew :backend:identity-access:test --tests '*V031MigrationTest'`. ⚠️ 기존 SchemaMigrationTest류가 전체 테이블/마이그레이션 수를 카운트하면 동반 갱신(메모리 `fr-pm-permission-seed-migration-test-coupling`) — 먼저 grep.

### Task 2. UserPreferences 도메인 + JdbcUserPreferencesRepository + UserPreferencesService

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/preferences/UserPreferences.kt`, `.../preferences/UserPreferencesRepository.kt`, `.../preferences/JdbcUserPreferencesRepository.kt`, `.../preferences/UserPreferencesService.kt`, `.../preferences/PreferencesValidationException.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/preferences/UserPreferencesServiceTest.kt`, `.../preferences/JdbcUserPreferencesRepositoryTest.kt`]
- depends-on: [1]

**RED**.
- `UserPreferencesServiceTest` (mock repo) — (a) 행 없으면 `getPreferences`가 기본값(system/ko/iso) 반환, (b) `patchPreferences` 부분 수정 upsert 위임 + 갱신값 반환, (c) 잘못된 enum → `PreferencesValidationException`.
- `JdbcUserPreferencesRepositoryTest` (Testcontainers) — upsert(INSERT ... ON CONFLICT (user_id) DO UPDATE) 멱등 + findByUserId nullable.

**GREEN**.
- `UserPreferences`(theme/locale/dateFormat) + `PreferencesView` + `PreferencesPatch`(부분 — nullable 필드) + 허용 enum 상수(THEMES/LOCALES/DATE_FORMATS) + `PreferencesValidationException`.
- `JdbcUserPreferencesRepository` — `JdbcUserProfileRepository` 미러. `findByUserId`→nullable, `upsert`.
- `UserPreferencesService` — `@Service` + `@Transactional`. getPreferences(defaults if null), patchPreferences(enum 검증→upsert→effective 반환).

**REFACTOR**. enum 허용값 companion 상수 응집 + KDoc.

**검증**. `./gradlew :backend:identity-access:test --tests '*UserPreferences*'`.

### Task 3. PreferencesController GET/PATCH (POST stub 제거) + CSRF 데모 테스트 이관

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/web/PreferencesController.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/dto/PreferencesResponse.kt`, `.../dto/PreferencesPatchRequest.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/web/PreferencesControllerMvcTest.kt`, `.../web/PreferencesControllerCsrfTest.kt`, `.../integration/PatAndConcurrencyIntegrationTest.kt`, `.../web/PasswordControllerMvcTest.kt`]
- depends-on: [2]

**RED**.
- `PreferencesControllerMvcTest` — GET `/me/preferences` 기본값 200 / PATCH 갱신 200 / 잘못된 enum 400 / PAT·미인증 401.
- `PreferencesControllerCsrfTest` — POST→**PATCH** 이관: CSRF 토큰 없는 PATCH → 403, 있으면 200(스텁 데모 의도 보존).

**GREEN**.
- `PreferencesController` 재작성 — `UserProfileController` 미러(JWT-only `currentUserId`, 로컬 `@ExceptionHandler`로 `PreferencesValidationException`→400). **기존 POST 핸들러 제거**.
- `PreferencesResponse(theme, locale, dateFormat)` + `PreferencesPatchRequest`(3-state 부분 수정; profile의 `JsonNode`/`ProfilePatchField` 패턴 또는 nullable enum 문자열).
- CSRF 데모 테스트 3곳(`PreferencesControllerCsrfTest`·`PatAndConcurrencyIntegrationTest` CSRF-B·`PasswordControllerMvcTest` 참조) POST→PATCH 이관.

**REFACTOR**. 에러코드 상수 + KDoc(profile 컨트롤러 톤). PoC 표현 잔재 제거.

**검증**. `./gradlew :backend:identity-access:test --tests '*Preferences*' --tests '*PatAndConcurrency*'`.

### Task 4. whoami에 preferences 필드 추가 (view-layer)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/dto/WhoamiResponse.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/web/WhoamiController.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/web/WhoamiControllerTest.kt`]
- depends-on: [2]

**RED**. `WhoamiControllerTest` — JWT 분기 whoami가 theme/locale/dateFormat 포함(행 없으면 기본값) + PAT 분기는 기본값(system/ko/iso). 실패: 필드 없음.

**GREEN**. `WhoamiResponse`에 `theme`/`locale`/`dateFormat: String` 추가(FR-PR-02/03 view-layer 패턴). `WhoamiController` JWT 분기가 `UserPreferencesService.getPreferences` 반영, PAT 분기는 기본값 상수.

**REFACTOR**. KDoc 필드 설명(기존 톤).

**검증**. `./gradlew :backend:identity-access:test --tests '*WhoamiController*'`.

### Task 5. 공통 preference-aware 날짜 포맷터 + useDateFormat 훅 + preferences Zod

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/lib/date-preferences.ts`, `apps/web/src/lib/date-preferences.test.ts`, `apps/web/src/api/preferences.ts`, `apps/web/src/api/preferences.test.ts`, `apps/web/src/hooks/use-date-format.ts`, `apps/web/src/api/schemas.ts`, `apps/web/src/api/schemas.test.ts`]
- depends-on: []

**RED**.
- `date-preferences.test.ts` — 프리셋별 순수 포맷 함수(iso→`2026-07-07`, kr→`2026. 07. 07.`, us→`07/07/2026`, eu→`07/07/2026`) + datetime = 날짜+` HH:mm`(24h, Asia/Seoul).
- `schemas.test.ts` — whoami 스키마가 theme/locale/dateFormat 파싱(`.default()`로 기존 mock 무영향) + preferences 스키마 enum 검증.

**GREEN**.
- `date-preferences.ts` — `formatDateByPreset(iso, preset, tz)` / `formatDateTimeByPreset` 순수 함수(Intl, preset 인자).
- `use-date-format.ts` — 현재 사용자 preference(authStore.user.dateFormat)를 읽어 포맷터 바인딩하는 훅.
- `api/preferences.ts` — `preferencesSchema`(theme/locale/dateFormat enum) + GET/PATCH 클라이언트.
- `schemas.ts` — whoami 스키마에 3필드 `.default(...)` 추가(메모리 `zod-schema-strengthen-inline-mock-fanout`·whoami mock fanout 회피).

**REFACTOR**. 프리셋→Intl 옵션 맵 상수화.

**검증**. `pnpm --filter web test -- date-preferences schemas preferences`.

### Task 6. PreferencesProvider + 테마 적용 + FOUC 프리하이드레이션

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/preferences/PreferencesProvider.tsx`, `apps/web/src/components/preferences/PreferencesProvider.test.tsx`, `apps/web/src/lib/theme.ts`, `apps/web/src/lib/theme.test.ts`, `apps/web/src/main.tsx`, `apps/web/index.html`]
- depends-on: [5]

**RED**. `PreferencesProvider.test.tsx` — theme=dark면 `<html>`에 `.dark` 부착, theme=light면 제거, theme=system이면 `matchMedia(prefers-color-scheme)` 추종 + OS 변경 이벤트 반영, locale→`<html lang>`. `theme.test.ts` — `applyTheme`/`resolveSystemTheme` 순수 로직.

**GREEN**.
- `lib/theme.ts` — `applyTheme(theme)`(`.dark` 토글, system→matchMedia), `readStoredTheme`/`writeStoredTheme`(**별도 `bts.theme` localStorage 키 — 비민감 테마 enum만. authStore의 sessionStorage 규칙은 인증 토큰 대상이라 무관, 근거 주석 명시**).
- `PreferencesProvider.tsx` — whoami(authStore.user) 변화에 theme/lang/dateFormat 적용 + `bts.theme` 미러. `main.tsx`에서 앱 루트 래핑.
- `index.html` — `<head>` 인라인 스크립트로 `bts.theme` 읽어 첫 페인트 전 `.dark` 적용(FOUC 0).

**REFACTOR**. matchMedia 구독 cleanup(theme≠system 시 해제) + KDoc.

**검증**. `pnpm --filter web test -- PreferencesProvider theme`.

### Task 7. /settings/preferences 페이지 + 네비 + PATCH mutation

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/routes/settings.preferences.tsx`, `apps/web/src/routes/__tests__/settings.preferences.test.tsx`, `apps/web/src/components/settings/PreferencesForm.tsx`, `apps/web/src/components/settings/PreferencesForm.test.tsx`, `apps/web/src/router.ts`, `apps/web/src/api/preferences.ts`]
- depends-on: [5, 6]

**RED**. `PreferencesForm.test.tsx` — theme 셀렉터 변경→PATCH 호출, date_format 변경→라이브 프리뷰 즉시 갱신(formatter), locale 셀렉터 저장 + "UI 번역 후속" 안내 문구. `settings.preferences.test.tsx` — 라우트 렌더 + 저장 성공 시 whoami invalidate + setUser(메모리 `mutation-setquerydata-partial-response-flicker`→invalidate).

**GREEN**.
- `settings.preferences.tsx`(page + RouteAdapter, code-based) + `router.ts` 등록(메모리 tanstack adapter 패턴) + 기존 설정 네비에 "환경 설정" 링크 추가.
- `PreferencesForm.tsx` — theme(light/dark/system)·date_format(라이브 프리뷰)·locale 셀렉터 + `usePreferencesMutation`(PATCH → whoami invalidate + setUser).
- `api/preferences.ts` — mutation 추가(Task 5 파일에 이어서).

**REFACTOR**. 셀렉터 옵션 상수화 + shadcn Select 재사용.

**검증**. `pnpm --filter web test -- settings.preferences PreferencesForm`.

### Task 8. 날짜 표시 사이트 전면 이관 (절대 날짜만)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/lib/datetime.ts`, `apps/web/src/lib/date-format.ts`, `apps/web/src/lib/datetime.test.ts`, `apps/web/src/lib/date-format.test.ts`, + 인라인 날짜 렌더 컴포넌트 ~20(예: `components/issue/IssueMetaPanel.tsx`, `IssueChangelog.tsx`, `WorklogSection.tsx`, `AttachmentSection.tsx`, `version/VersionRow.tsx`, `auth/SessionList.tsx`, `admin/AuditLogTable.tsx` 등 — 착수 시 전수 grep) + 각 테스트]
- depends-on: [5, 6]

**RED**. 대표 사이트에 프리셋 반영 검증 테스트(예: IssueMetaPanel이 dateFormat=us면 `07/07/2026`) + 기존 날짜 단위 테스트가 기본 프리셋(iso)에서 그대로 통과. **상대시간("N일 전") 표시는 이관 대상 아님** — 뭉갬 금지.

**GREEN**. `lib/datetime.ts`·`lib/date-format.ts`를 공통 preset 포맷터 위임으로 재작성(기본 iso=기존 출력 유지). 인라인 `toLocaleDateString`/`toLocaleString`/`Intl.DateTimeFormat` 절대 날짜 사이트를 `useDateFormat`/공통 포맷터로 이관. 착수 전 `grep -rn "toLocaleDateString\|toLocaleString\|Intl.DateTimeFormat" apps/web/src --include=*.tsx`로 전수 목록 확정(E7 커버).

**REFACTOR**. 중복 포맷 헬퍼 제거 + import 정리.

**검증**. `pnpm --filter web test -- datetime date-format` + 이관 컴포넌트 테스트 + `pnpm --filter web typecheck`.

### Task 9. E2E (테마 지속 · date_format 반영 · 설정 저장)

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/preferences.spec.ts`, `apps/web/src/mocks/preferences-handlers.ts`, `apps/web/src/mocks/auth-fixtures.ts`]
- depends-on: [7, 8]

**RED/시나리오**. (1) 테마 다크 선택→새로고침·다른 라우트 이동해도 다크 유지(localStorage 프리하이드레이션), (2) date_format=us 저장→이슈 상세 날짜가 `MM/DD/YYYY`로 렌더, (3) 설정 저장 왕복(PATCH→whoami 반영). MSW: preferences GET/PATCH stateful 핸들러(메모리 `msw-mutation-stateful-refetch`) + whoami fixture에 preferences 필드.

**검증**. `pnpm --filter web test:e2e -- preferences`. ⚠️ worktree 5173 orphan vite kill(메모리 `e2e-orphan-vite-after-worktree-remove`).

## Plan 메타

- task 수: 9 (백엔드 4 · 프론트 4 · E2E 1)
- depends-on 그래프. T1:[] T2:[1] T3:[2] T4:[2] T5:[] T6:[5] T7:[5,6] T8:[5,6] T9:[7,8]
- 예상 wave. W1{T1,T5} → W2{T2,T6} → W3{T3,T4,T7,T8} → W4{T9}. 약 4 wave.
  - ⚠️ 백엔드 T1~T4는 identity-access 단일 모듈 → Gradle 모듈 컴파일 직렬화(메모리 `bts-plan-wave-gradle-module-compile`). 프론트와는 병렬.
- TDD 강제: yes (test 커밋이 feat 커밋보다 먼저)
- 신규 외부 의존성: 없음
- 추가 검증: ktlint/detekt(`--rerun-tasks`), typecheck(tsconfig.app), vitest, playwright
- BC 격리: identity-access 단일. cross-BC 없음. (프론트+same-BC view-layer는 한 PR 정상 — FR-PR 선례)

## 리뷰 결과 (← /bts-review-plan 채움)
