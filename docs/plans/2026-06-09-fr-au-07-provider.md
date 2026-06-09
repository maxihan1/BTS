# FR-AU-07 — 도메인 기반 자동 라우팅

> slug: fr-au-07-provider
> type: auth
> agent: security-engineer
> primary_bc: identity-access
> 생성: 2026-06-09

## Brief

**사용자 원문**. "fr-au-07 진행하자" — FR-AU-07 도메인 기반 자동 라우팅, 이메일 도메인으로 인증 Provider 자동 선택.

**명세 위치**. `docs/plan/product/identity-access.md §2.7`

**핵심**. 사용자가 이메일을 입력하면 그 이메일의 도메인(`@partner.com`)을 키로 매핑된 인증 Provider로 자동 진입시킨다. FR-AU-06(#101, 다중 Provider 명시 선택)이 의도적으로 미룬 "똑똑한 자동 선택"을 담당.

**명세 D 단계**.
- D1. 도메인 (security-engineer)
- D2. 명세 — 이메일 도메인 → Provider 매핑 (security-engineer)
- D3. 데이터 모델 — `domain_provider_routes(domain, provider_id)` (db-engineer)
- D4. 백엔드 — 이메일 입력 → Provider 자동 선택 (security-engineer)
- D5. 백엔드 테스트 (security-engineer)
- D6. 프론트 UI — 이메일 입력 후 Provider 자동 진입 (designer → frontend-engineer)
- D7. E2E (qa-engineer)

**분류 결과**. type=auth, agent=security-engineer, slug=fr-au-07-provider, primary_bc=identity-access. 최신 마이그레이션 V019 → 신규 V020 예정.

## 도메인 정리

- **BC**: identity-access
- **작업 본질**: Home Realm Discovery — 이메일 도메인으로 인증 Provider 자동 안내.
- **영향 엔티티**:
  - `DomainProviderRoute` (신규) — `domain_provider_routes(domain UNIQUE, provider_id → authn_providers(id))`. V020.
  - `authn_providers` (기존) — 라우팅 대상 레지스트리. SAML/OIDC만 유효(항상 행 존재).
  - `saml_idp_configs` / `oidc_provider_configs` (기존) — authn_provider_id로 registration_id·displayName 조회.
- **새 용어**: "도메인 라우팅" / "Home Realm Discovery" (이메일 도메인으로 신원 영역 판별 → glossary 추가 후보, Maxi 승인 대기).
- **핵심 설계 결정 (Maxi 2026-06-09)**:
  - D1. 라우팅 대상 **SSO 전용(SAML/OIDC)**. LOCAL/LDAP 제외(authn_providers 행 부재 + 비번 오전달 위험).
  - D2. 매칭 시 **자동 리다이렉트**, 미매칭 시 기존 전체 목록 fallback.
  - D3. 도메인 **exact match + UNIQUE + lowercase 정규화**. 서브도메인 매칭 후속.
  - D4. 관리 **DB/시드만**(라우트 CRUD API/UI 후속 FR). V020 시드 행 없음.
- **기존 결정 충돌**: 없음. FR-AU-06 ADR이 명시적으로 FR-AU-07에 자동 선택 위임.
- **관련 ADR**: [docs/decisions/2026-06-09-domain-based-provider-routing.md](../decisions/2026-06-09-domain-based-provider-routing.md) (생성됨), 선행 [2026-06-09-multi-provider-explicit-selection.md](../decisions/2026-06-09-multi-provider-explicit-selection.md)
- **회귀 주의(learnings)**: init_codegen 미러 **불필요**(identity-access는 jdbc-only, jOOQ 미사용 — 코드 확인), 마이그레이션 V번호 머지 직전 재확인, 계정 열거 방지(도메인만 판단), cross-BC 아님(identity-access 단일), 다중 LEFT JOIN cartesian 주의(라우트 1:1이라 위험 낮으나 saml/oidc config 조회 분리).

## 스펙

전체 스펙. [docs/specs/2026-06-09-fr-au-07-provider.md](../specs/2026-06-09-fr-au-07-provider.md)

핵심 시나리오 3줄 요약.
- 이메일 입력 → 도메인이 SSO 라우트에 매칭되면 해당 SAML/OIDC로 자동 리다이렉트.
- 미매칭/끊긴 라우트/비활성 → 기존 2단계 로그인 폼(provider 드롭다운+username+password) 노출 (fail-safe).
- 라우팅은 도메인만 판단(계정 열거 0), 신규 테이블 `domain_provider_routes` V020, 관리 UI는 후속 FR.

핵심 결정.
- API. `GET /api/v1/auth/route?domain={domain}` (permitAll, 200 `{matched,type,registrationId,displayName}`).
- 프론트. identifier-first 2단계(Maxi 선택 — 이메일 먼저, 미매칭 시 기존 폼).
- 매칭 판정. provider_id가 가리키는 enabled SAML/OIDC config 있을 때만(LOCAL/LDAP·비활성·삭제는 미매칭).

## Brainstorming Check

✅ 통과 (1회 iteration). gap 1건(프론트 로그인 UX) → Maxi 결정 identifier-first 2단계 채택. 그 외 보안/엣지 점검 통과.

## Plan

> 패키지. `com.atlas.bts.identity` (web / provider / 신규 `provider.route` 또는 `web`). 전부 jdbc(NamedParameterJdbcTemplate). 컨트롤러 MVC 테스트 `@WebMvcTest`, repository 통합테스트 `@SpringBootTest`+Testcontainers(identity-access prod+RANDOM_PORT 부팅 레시피 learnings).

### Task 1. V020 마이그레이션 + 도메인 라우트 조회 repository

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/resources/db/migration/V020__domain_provider_routes.sql`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/provider/route/DomainProviderRouteRepository.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/provider/route/DomainProviderRouteRepositoryIntegrationTest.kt`]
- depends-on: []

**RED**: `DomainProviderRouteRepositoryIntegrationTest` (@SpringBootTest + Testcontainers). 시드: authn_providers(SAML 1, OIDC 1, LOCAL 1) + saml_idp_configs(enabled) + oidc_provider_configs(enabled) + domain_provider_routes 3행(partner.com→SAML, acme.com→OIDC, dead.com→비활성/LOCAL). 테스트:
- `partner.com → SAML 매칭(registrationId, displayName)` (S1)
- `acme.com → OIDC 매칭` (S2)
- `gmail.com(미등록) → null` (S3)
- `dead.com(비활성/LOCAL 지시) → null` (S4 fail-safe)
- `Partner.COM 공백포함 → 정규화 후 partner.com 매칭` (S5)
- 실패 메시지(예상): `DomainProviderRouteRepository` 클래스 없음

**GREEN**: V020 테이블(domain UNIQUE, provider_id → authn_providers ON DELETE CASCADE, idx provider_id). `DomainProviderRouteRepository.findRouteByDomain(domain): RouteMatch?` — domain 정규화는 호출자(Service/Controller) 책임이라 repository는 받은 값 그대로 조회. 매칭 판정: domain_provider_routes JOIN authn_providers(type) → SAML이면 saml_idp_configs, OIDC이면 oidc_provider_configs에서 enabled=true registration_id/display_name. **LEFT JOIN 2개(saml+oidc) 또는 type 분기 2-step** — cartesian 회피(라우트 1:1). LOCAL/LDAP·비활성·미등록은 결과 없음 → null.

**REFACTOR**: SQL 상수화 + KDoc(매칭 판정/fail-safe 사유). `RouteMatch` data class(type: ProviderType, registrationId, displayName).

**검증**: `./gradlew :modules:identity-access:test --tests "*DomainProviderRouteRepositoryIntegrationTest" --rerun-tasks`

### Task 2. 라우트 조회 엔드포인트 + 정규화 + 응답 DTO

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/web/DomainRouteController.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/web/DomainRouteControllerTest.kt`]
- depends-on: [1]

**RED**: `DomainRouteControllerTest` (@WebMvcTest, mock `DomainProviderRouteRepository`). 테스트:
- `GET /api/v1/auth/route?domain=partner.com → 200 {matched:true,type:"SAML",registrationId,displayName}`
- `미매칭 → 200 {matched:false}`
- `domain 누락/빈값 → 400`
- `대문자/공백 입력 → repository에 정규화된 소문자 도메인 전달` (정규화 위임 검증, ArgumentCaptor)
- 실패 메시지(예상): `DomainRouteController` 없음

**GREEN**: `@RestController` GET `/api/v1/auth/route`. `@RequestParam domain` (빈값 → 400). 도메인 정규화(`trim().lowercase()`) 후 repository 호출. `RouteResponse`(matched + nullable type/registrationId/displayName). 매칭 시 채우고 미매칭 시 matched:false만.

**REFACTOR**: KDoc(permitAll 계약·계정 열거 방지·민감정보 미노출). 정규화 헬퍼 private.

**검증**: `./gradlew :modules:identity-access:test --tests "*DomainRouteControllerTest" --rerun-tasks`

### Task 3. SecurityConfig permitAll 등록 + 미인증 접근 통합테스트

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/config/SecurityConfig.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/config/RoutePermitAllIntegrationTest.kt`]
- depends-on: [2]

**RED**: `RoutePermitAllIntegrationTest` (@SpringBootTest + Testcontainers, 미인증). `GET /api/v1/auth/route?domain=x` → 401 아님(200). 실패(예상): permitAll 미등록이라 401.

**GREEN**: companion에 `const val ROUTE_PATH = "/api/v1/auth/route"` + permitAll 블록(라인 143 근처)에 추가. GET이라 CSRF skip 불요(login/refresh만 csrf ignore).

**REFACTOR**: KDoc 주석(FR-AU-07 라우팅, 민감정보 미노출).

**검증**: `./gradlew :modules:identity-access:test --tests "*RoutePermitAllIntegrationTest" --rerun-tasks`

### Task 4. 프론트 route API 클라이언트 + Zod 스키마 + MSW

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/api/route.ts`, `apps/web/src/api/route.test.ts`, `apps/web/src/mocks/route-handlers.ts`, `apps/web/src/mocks/handlers.ts`]
- depends-on: []

**RED**: `route.test.ts` (vitest + MSW). `fetchRoute('partner.com')` → 매칭 `{matched:true,type:'SAML',registrationId,displayName}` 파싱, 미매칭 `{matched:false}` 파싱. 실패(예상): `route.ts` 없음.

**GREEN**: `route.ts` — `routeResultSchema`(Zod, **spec API 계약 1:1 정합** — learnings frontend-zod-backend-dto-contract-gap, matched discriminated union 또는 nullable). `fetchRoute(domain)`. `route-handlers.ts` MSW(브라우저 시드 가능 공유 store — learnings msw-derived-behavior-shared-store) + handlers.ts 등록.

**REFACTOR**: 타입 export, 주석.

**검증**: `pnpm test route` + `pnpm typecheck`(tsconfig.app — learnings ci-typecheck-tsconfig-app)

### Task 5. LoginForm identifier-first 2단계 개조

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/auth/LoginForm.tsx`, `apps/web/src/auth/LoginForm.test.tsx`, `apps/web/src/i18n/ko.ts`]
- depends-on: [4]

**RED**: `LoginForm.test.tsx` 추가/개조. 테스트:
- 1단계: 이메일 입력 + "계속" 버튼만 렌더(드롭다운/비번 미표시)
- 이메일 입력→계속→매칭 → `window.location.assign('/saml2/authenticate/...')` 호출(SAML), OIDC면 `/oauth2/authorization/...` (window.location mock)
- 미매칭 → 2단계(provider 드롭다운+username+password+SSO 버튼) 노출, username에 이메일 프리필
- registrationId `encodeURIComponent` 적용
- 실패(예상): 2단계 흐름 미구현

**GREEN**: LoginForm을 2단계로. 1단계 email state + "계속" → `fetchRoute(email.split('@')[1])`(@ 없으면 미조회 → 2단계). 매칭이면 type별 SSO URL `window.location.assign`. 미매칭이면 2단계 폼(기존 LoginForm 내용). i18n 문구(이메일/계속/안내) `loginStrings` 추가.

**REFACTOR**: 단계 컴포넌트 분리 가독성, 주석.

**검증**: `pnpm test LoginForm` + `pnpm typecheck`

### Task 6. E2E — identifier-first 2단계 + 기존 로그인 E2E 재조정

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/login.spec.ts`, `apps/web/e2e/login-domain-routing.spec.ts`, `apps/web/src/mocks/route-handlers.ts`]
- depends-on: [5]

**RED/시나리오**: 신규 `login-domain-routing.spec.ts` — 이메일 매칭 → SSO 진입 URL 네비게이션 확인, 미매칭 → 2단계 폼 노출. 기존 `login.spec.ts`(FR-AU-05/06)를 2단계 흐름으로 재조정(이메일 입력→계속→2단계). MSW route handler 시드(localStorage 플래그 토글 — learnings e2e-msw-scenario-toggle). 텍스트 중복 버튼 컨테이너 한정(learnings playwright-getbyrole).

**검증**: `pnpm exec playwright test login` (orphan vite kill 주의 — learnings)

## Plan 메타

- task 수: 6 (각 TDD 사이클)
- 예상 시간: 약 18분(직렬), 병렬 wave 적용 시 약 9분 (예상 wave 4)
- 예상 wave. W1[T1, T4] → W2[T2, T5] → W3[T3] → W4[T6]. (identity-access 모듈 test 컴파일 직렬 요인이나 파일 비중첩이라 dispatch는 병렬, learnings bts-plan-wave-gradle-module-compile)
- TDD 강제: yes (test→feat 커밋 순서 자동 검증)
- 추가 검증: typecheck(tsconfig.app), ktlint, detekt, ArchUnit, vitest, playwright

## 리뷰 결과 (← /bts-review-plan 채움)
