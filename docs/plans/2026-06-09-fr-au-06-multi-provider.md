# FR-AU-06 다중 Provider 동시 활성화

> slug: fr-au-06-multi-provider
> type: auth
> agent: security-engineer
> primary_bc: identity-access
> 생성: 2026-06-09

## Brief

FR-AU-06 다중 Provider 동시 활성화 — CompositeAuthenticationManager + Provider 우선순위/fallback + authn_providers.priority,enabled + 다중 Provider 선택 화면.

분류 결과. type=auth · agent=security-engineer · BC=identity-access.

product 문서 §2.6 (docs/plan/product/identity-access.md:106) 기준 D1~D7.
- D1. 도메인 (security-engineer)
- D2. 명세 — Provider 우선순위 + fallback 규칙 (security-engineer)
- D3. 데이터 모델 — `authn_providers.priority, enabled` (db-engineer)
- D4. 백엔드 — `CompositeAuthenticationManager` (security-engineer)
- D5. 백엔드 테스트 — 다중 Provider 시나리오 (security-engineer)
- D6. 프론트 UI — 다중 Provider 선택 화면 (designer → frontend-engineer)
- D7. E2E (qa-engineer)

## 도메인 정리

- **BC**: identity-access (담당 security-engineer)
- **새 용어**: 없음 (기존 유비쿼터스 언어 재사용 — `AuthenticationProvider`, `ProviderRegistry`, `Credential`, `sort_order`)

### 스코프 확정 (Maxi 2026-06-09)

> **옵션 1 채택** — "인증 서버는 하나여도, 로그인 방법 종류(LDAP·Local·SAML·OIDC)는 동시에 켜고 화면이 동적으로 보여준다." 같은 type 여러 인스턴스(LDAP 서버 2대 등 LdapTemplate 동적 wiring)는 **제외 → 후속 FR**. 사내 1000명 규모에 디렉토리 1개로 충분, 복잡도/위험 대비 효익 낮음.
> **인증 동작**: 선택우선 + 자동 fallback. 사용자가 드롭다운에서 고른 Provider 우선 인증, 미선택('자동') 시 `sort_order` 우선순위 순으로 시도(예: LDAP 실패→Local).

### 현행 인프라 (이미 구현됨 — Explore 조사 결과)

| 구성요소 | 위치 | 상태 |
|---|---|---|
| `authn_providers.enabled` / `sort_order` | `V002__authn_providers_and_user_external_accounts.sql:8-17` | **이미 존재** (sort_order에 "FR-AU-06" 주석). 새 마이그레이션 거의 불요. 최신 V019 → 필요 시 V020 |
| `AuthenticationProvider` SPI + `ProviderRegistry` | `identity/spi/` | 존재. `findFor(credential)` = priority 내림차순 **첫 매칭 1개만** 반환 (fallback 없음) |
| `ProviderType(priority)` enum | `identity/spi/ProviderType.kt:30-37` | LOCAL 70 / LDAP 80 / SAML 40 / OIDC 50 / PAT 60 / OAUTH 30 — **코드 하드코딩 우선순위** (DB sort_order와 별개) |
| `GET /api/v1/auth/providers` | `web/ProvidersController.kt:36-51` | 존재. 단 `ProviderRegistry.all()`(코드 등록 Provider) 기반 — **DB enabled/sort_order 미반영** |
| SAML/OIDC 동적 IdP 버튼 | `apps/web` `SamlIdpButtons`/`OidcIdpButtons` | 존재. SSO는 리다이렉트 기반(thin Provider, supports=false) |
| LoginForm Provider 드롭다운 | `apps/web/src/auth/LoginForm.tsx:46` | **하드코딩** `z.enum(['local','ldap-corp'])` |
| `LoginRequest.provider` 필드 | `web/AuthController.kt:434-438` | 정의돼 있으나 **백엔드가 무시** |
| `CompositeAuthenticationManager` | — | **없음** |

### 실제 갭 (FR-AU-06이 메꿀 것)

1. **백엔드 — username/password 인증 fallback**: `AuthController`가 `findFor` 첫 매칭만 시도. `LoginRequest.provider` 무시. → 선택된 Provider 우선 + sort_order 순 fallback chain (D2 fallback 규칙 / D4 CompositeAuthenticationManager).
2. **백엔드 — `/api/v1/auth/providers` DB 동적화**: 코드 enum 기반 정적 목록 → `authn_providers`(enabled=true) + sort_order 반영 (D3 데이터 모델 활용).
3. **프론트 — 로그인 화면 동적 목록**: 하드코딩 enum 제거 → `/api/v1/auth/providers` 응답 기반 동적 렌더 (D6 다중 Provider 선택 화면).
4. **E2E**: 다중 Provider 시나리오(선택 인증 + fallback) (D7).

### 기존 결정 충돌 / 관련 ADR

- **충돌 없음**. 기존 ADR(`authentication-provider-spi-naming`, `saml-sso-provider`, `oidc-sso-provider`) 위에 얹음. SSO 전용 @Order 체인은 그대로, 본 작업은 username/password Provider 흐름 + providers 목록 동적화에 집중.
- **신규 ADR 후보**: "다중 Provider fallback chain (선택우선+자동) + /api/v1/auth/providers DB 동적화" — spec/plan 단계에서 결정 구체화 후 `docs/decisions/2026-06-09-multi-provider-fallback.md` 작성.
- **관련 learning**: [[learnings#2026-05-21 — prod 단일 LdapTemplate vs test dead URL 가정]] — 같은 type 다중 인스턴스는 본 PR 스코프 제외이므로 dead URL 동적 wiring 재도입도 **제외**(후속 FR로 유지).

## 스펙

전체 스펙. [docs/specs/2026-06-09-fr-au-06-multi-provider.md](../specs/2026-06-09-fr-au-06-multi-provider.md)

핵심 요약.
- **G1(1순위 버그)**: 현재 `AuthController`가 항상 `UsernamePassword`만 만들어 LDAP은 username/password 로그인 진입 불가. → provider 디스패처가 선택값(local/ldap)에 맞는 Credential(UsernamePassword/LdapBind) 생성.
- **인증 = 명시 선택만**. 자동 fallback은 보안 위험(동명이인·비번 오전달·lockout 2배)으로 폐기. "똑똑한 자동"은 FR-AU-07 도메인 라우팅이 담당.
- **목록 동적화**: `/api/v1/auth/providers`가 username/password 계열(LOCAL/LDAP)만, DB `enabled`/`sort_order` 오버레이로 반환. 프론트 드롭다운 하드코딩 제거.
- 마이그레이션 불요(컬럼 기존재 + LOCAL/LDAP seed 안 함). SSO @Order 무변경.

## Brainstorming Check

✅ 통과 (1회 iteration). 자동 fallback 보안 위험 3종 발견 → Maxi 결정으로 **명시 선택만** 채택(자동 fallback 폐기). 경미 gap 4건(provider 명명 통일 / AUTO 항목 제거 / RequiresMfa 처리 / ProviderUnavailable try-catch 계약) 스펙 보강.

## Plan

> 공통. 모든 백엔드 task는 identity-access 모듈. 같은 모듈 test 컴파일 단위 직렬화 가능(메모리 `bts-plan-wave-gradle-module-compile`) — bts-impl이 wave 계산. `provider` 어휘는 providers API의 `id`(소문자 type: `"local"`,`"ldap"`)로 통일. 기존 프론트 `'ldap-corp'`는 `'ldap'`로 교체.

### Task 1. AuthnProviderConfigRepository — authn_providers enabled/sort_order 조회

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/provider/AuthnProviderConfigRepository.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/provider/AuthnProviderConfigRepositoryTest.kt`]
- depends-on: []

**RED**: `AuthnProviderConfigRepositoryTest` — `isEnabled(LOCAL)` = true(row 없을 때 기본 활성), `isEnabled(LDAP)` = false(enabled=false row INSERT 시), `listEnabledByTypes([LOCAL,LDAP])` = sort_order 오름차순 type 목록. Testcontainers PostgreSQL(기존 base 재사용).

**GREEN**: `LdapProviderConfigService.findEnabledLdapConfig`(jdbc NamedParameterJdbcTemplate) 패턴 차용. `isEnabled(type): Boolean` — `SELECT enabled FROM authn_providers WHERE type=:type AND enabled=true LIMIT 1` 존재 여부; **row 없으면 true 기본 반환**(LOCAL/LDAP seed 없음 대응). `listEnabledByTypes(types): List<Pair<ProviderType,Int>>` — type별 (type, sort_order).

**REFACTOR**: SQL 상수 추출 + KDoc(기본 활성 정책 명시).

**검증**: `./gradlew :identity-access:test --tests "*AuthnProviderConfigRepositoryTest"`

### Task 2. CompositeAuthenticationManager — provider 명시 선택 디스패처

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/auth/CompositeAuthenticationManager.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/auth/CompositeAuthenticationManagerTest.kt`]
- depends-on: [1]

**RED**: `CompositeAuthenticationManagerTest`(mockk) — `authenticate("local",u,pw)` → `UsernamePassword` + LocalProvider.authenticate 호출; `authenticate("ldap",..)` → `LdapBind` + LdapProvider; 미지원 provider(`"saml"`/unknown) → `AuthnResult.Failure`; 비활성(repository.isEnabled=false) → Failure; `RequiresMfa` 그대로 전파; LocalProvider가 `ProviderUnavailableException` 던지면 전파(catch 안 함).

**GREEN**: `@Service class CompositeAuthenticationManager(providerRegistry, authnProviderConfigRepository)`. `authenticate(providerId, username, password: CharArray): AuthnResult` — providerId→ProviderType 파싱(실패 시 Failure), username/password 계열(LOCAL/LDAP)만 허용(그 외 Failure), `repository.isEnabled(type)` false면 Failure, `findByType` null이면 Failure, type별 Credential 생성 → `provider.authenticate(credential)` 반환. `ProviderUnavailableException`은 전파(AuthController가 503 변환).

**REFACTOR**: type→Credential 매핑 when 분기 + KDoc(명시 선택만·fallback 없음 근거 = 스펙 NFR-06-02 링크).

**검증**: `./gradlew :identity-access:test --tests "*CompositeAuthenticationManagerTest"`

### Task 3. AuthController.login — 디스패처 위임 + provider 필수 + 503

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/web/AuthController.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/web/AuthControllerTest.kt`]
- depends-on: [2]

**RED**: `AuthControllerTest`(@WebMvcTest, CompositeAuthenticationManager mock) — `{provider:"local"}` 성공 200; `{provider:"ldap"}` 디스패처 호출 검증; provider 누락/빈값 → 400 `provider_required`; Failure → 401 `invalid_credentials`; `ProviderUnavailableException` → 503; RequiresMfa → 401 `mfa_required`. 기존 login 테스트 갱신(provider 필드 의미 부여).

**GREEN**: `login`이 `providerRegistry.findFor` 제거 → `compositeAuthenticationManager.authenticate(body.provider, body.username, body.password.toCharArray())` 위임. provider blank → 400. try/catch `ProviderUnavailableException` → 503. 생성자에 `CompositeAuthenticationManager` 주입(providerRegistry 직접 사용 제거 여부 확인 — 다른 메서드 미사용 시 제거).

**REFACTOR**: 에러 응답 헬퍼 일원화 + KDoc.

**검증**: `./gradlew :identity-access:test --tests "*AuthControllerTest"`

### Task 4. LDAP username/password 로그인 통합 테스트 (G1 해소 증명)

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/integration/LdapAuthFlowIntegrationTest.kt`]
- depends-on: [3]

**RED**: 기존 `LdapAuthFlowIntegrationTest`(Testcontainers OpenLDAP 재사용)에 시나리오 추가 — `POST /api/v1/auth/login {provider:"ldap", username:"alice", password:"..."}` → 200 + TokenResponse + JIT 프로비저닝 확인. (현재는 provider 무시로 LocalProvider만 타서 실패하던 경로) 추가로 `{provider:"local"}` 회귀.

**GREEN**: Task 2/3 구현으로 통과(테스트 코드만 추가, prod 변경 없음 — TDD 통합 검증 task).

**REFACTOR**: 시드 헬퍼 정리.

**검증**: `./gradlew :identity-access:test --tests "*LdapAuthFlowIntegrationTest"`

### Task 5. ProvidersController — DB 동적화 + username/password 계열만

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/web/ProvidersController.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/web/ProvidersControllerTest.kt`]
- depends-on: [1]

**RED**: `ProvidersControllerTest` 갱신 — (1) SAML/OIDC/PAT **제외**, LOCAL/LDAP만 반환; (2) DB `authn_providers` LDAP `enabled=false`면 LDAP 제외; (3) `sort_order` 오름차순(동률 priority 내림차순) 정렬; (4) "auto" 항목 없음; (5) row 없으면 기본 활성 노출. 기존 4 테스트(PAT 제외/필드/priority/available) 의미 보존하며 갱신.

**GREEN**: `ProvidersController`가 `providerRegistry.all()` username/password 계열 필터(`supports(UsernamePassword)` 또는 `supports(LdapBind)` — type ∈ {LOCAL,LDAP}) ∩ `authnProviderConfigRepository.isEnabled(type)` 오버레이. 정렬은 repository의 sort_order(없으면 priority). 생성자에 repository 주입.

**REFACTOR**: 매핑 로직 추출 + KDoc(SAML/OIDC는 별도 엔드포인트 담당 명시).

**검증**: `./gradlew :identity-access:test --tests "*ProvidersControllerTest"`

### Task 6. 프론트 api/providers.ts — providers 조회 클라이언트 (Zod)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/api/providers.ts`, `apps/web/src/api/providers.test.ts`]
- depends-on: []

**RED**: `providers.test.ts`(MSW) — `fetchProviders()`가 `{providers:[{id,type,displayName,priority,available}]}` 언래핑 → 배열 반환. Zod 스키마 검증(누락 필드 reject). `api/saml.ts` 패턴 동형.

**GREEN**: `providerEntrySchema = z.object({id,type,displayName,priority:z.number(),available:z.boolean()})`, `providersResponseSchema = z.object({providers: z.array(...)})`. `fetchProviders(): Promise<ProviderEntry[]>` = `apiGet('/api/v1/auth/providers', schema)` 언래핑. **스키마는 백엔드 `ProvidersController.ProviderEntry`와 1:1**(메모리 `frontend-zod-backend-dto-contract-gap`).

**REFACTOR**: 타입 z.infer 추론 + KDoc.

**검증**: `pnpm --filter web test providers`

### Task 7. LoginForm 드롭다운 동적화

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/auth/LoginForm.tsx`, `apps/web/src/auth/LoginForm.test.tsx`, `apps/web/src/auth/LoginForm.oidc.test.tsx`, `apps/web/src/mocks/auth-handlers.ts`]
- depends-on: [6]

**RED**: LoginForm 테스트 — providers를 MSW로 제공 시 드롭다운이 응답 기반 동적 렌더(LOCAL/LDAP), 기본 선택 = 첫 항목, value=id; fetch 실패 시 안전 기본값(LOCAL). 하드코딩 `z.enum` 제거. 기존 oidc 테스트 회귀 유지.

**GREEN**: `fetchProviders` useQuery 추가. `loginFormSchema.provider`를 `z.string().min(1)`로(동적). `SelectItem`을 providers map으로 렌더. displayName은 `loginStrings`에서 id→한국어 라벨 매핑(`providerLocal`/`providerLdapCorp` 재사용, 키 없으면 응답 displayName fallback). 기본값 defaultValues.provider = 첫 항목 id. MSW `auth-handlers`에 `/api/v1/auth/providers` 핸들러 추가.

**REFACTOR**: 드롭다운 컴포넌트 정리 + 로딩 처리.

**검증**: `pnpm --filter web test LoginForm`

### Task 8. E2E — 다중 Provider 명시 선택 시나리오 (S1/S2/S3)

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/login-multi-provider.spec.ts`, `apps/web/src/mocks/auth-handlers.ts`]
- depends-on: [7]

**RED**: Playwright — S1(Local 선택→성공), S2(LDAP 선택→성공), S3(LDAP `enabled=false` 시 드롭다운에서 LDAP 미표시). MSW 시나리오 토글(메모리 `e2e-msw-scenario-toggle-localstorage-flag` + `msw-derived-behavior-shared-store-e2e`). 텍스트 중복 시 컨테이너 한정(메모리 `playwright-getbyrole-exact-strict-mode`).

**GREEN**: 시나리오 통과. 기존 login/login-ldap E2E 회귀 확인(메모리 `ui-pr-defer-e2e-regression-latent`).

**REFACTOR**: 셀렉터/헬퍼 정리.

**검증**: `pnpm --filter web test:e2e login-multi-provider`

## Plan 메타

- task 수: 8 (백엔드 5 T1~T5 / 프론트 2 T6~T7 / E2E 1 T8)
- 예상 wave: 약 4 (W1: T1·T6 / W2: T2·T5·T7 / W3: T3·T8 / W4: T4). 백엔드는 같은 모듈이라 실제 직렬화 가능 — bts-impl이 최종 계산
- TDD 강제: yes (RED→GREEN→REFACTOR)
- 마이그레이션: 없음 (enabled/sort_order 기존재, LOCAL/LDAP seed 안 함)
- 추가 검증: ktlint·detekt(백엔드), typecheck·lint·vitest·playwright(프론트), ArchUnit @Transactional+@Service 가드
- 신규 ADR 후보: `2026-06-09-multi-provider-explicit-selection.md` — 명시 선택 채택(자동 fallback 폐기) + providers 목록 username/password 계열 한정 + isEnabled 기본 활성 정책. impl 단계 작성.

## 리뷰 결과 (← /bts-review-plan 채움)
