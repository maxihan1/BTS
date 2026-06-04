# FR-AU-04 — OIDC SSO

> slug: fr-au-04-oidc-sso
> type: auth
> agent: security-engineer
> primary BC: identity-access
> 생성: 2026-06-04

## Brief

FR-AU-04 OIDC SSO (OpenID Connect 기반 Single Sign-On). identity-access BC.

- Authorization Code + PKCE 흐름, JWT(ID Token) 검증
- 기술 스택: `spring-boot-starter-oauth2-client` + `-resource-server`
- 데이터 모델: `oidc_provider_configs` (authn_provider_id FK, SAML `saml_idp_configs` 선례)
- 테스트: Keycloak Testcontainers OIDC 모드
- 구조 재사용: FR-AU-03 SAML SSO (PR #76) — 별도 @Order SecurityFilterChain 분리, JIT 프로비저닝(AutoProvisionService) 재사용, STATELESS↔oauth2Login 세션 충돌 회피

product plan: docs/plan/product/identity-access.md §2.4 (D1~D7)

## 도메인 정리

- **BC**: identity-access (security-engineer)
- **영향 엔티티**: `OidcProvider`(신규, thin SPI), `OidcProviderConfig`(신규), `User`/`UserExternalAccount`(재사용), `AutoProvisionService`(재사용)
- **ProviderType.OIDC**: 이미 존재 (priority 50, SAML 40보다 우선). enum 변경 불필요
- **SAML(PR #76) 동형 매핑**:
  - `spring-security-saml2-service-provider` → `spring-boot-starter-oauth2-client`
  - `SamlSecurityConfig`(@Order(1), IF_REQUIRED, saml2Login) → `OidcSecurityConfig`(@Order(1), IF_REQUIRED, oauth2Login)
  - `RelyingPartyRegistrationRepository` → `ClientRegistrationRepository`
  - `/saml2/authenticate/{id}`·`/login/saml2/sso/{id}` → `/oauth2/authorization/{id}`·`/login/oauth2/code/{id}` (Spring 표준)
  - `Saml2AuthenticationSuccessHandler`(JIT) → `OidcAuthenticationSuccessHandler`(JIT, AutoProvisionService 재사용)
  - `SamlProvider`(thin) → `OidcProvider`(thin)
  - `saml_idp_configs` + SAML seed → `oidc_provider_configs` + OIDC seed
- **핵심 결정 (Maxi 확인 2026-06-04)**:
  - D3. `-resource-server` **제외** — oauth2-client 단독. BTS 자체 JWT 발급(jwt-issuer-strategy), 외부 access token 미소비
  - D4. `client_secret` **암호화 저장** — 비밀값. AesBytesEncryptor + app key(환경변수). 기존 암호화 유틸 부재 → 신규 도입. SAML x509_cert(공개값 평문)와 구분
- **새 용어(glossary 인증 섹션 추가 후보, Maxi 승인 대기)**: OIDC, ID Token, Authorization Code + PKCE, ClientRegistration, issuer/discovery(.well-known), client_id/client_secret, nonce
- **기존 결정 충돌**: 없음. `jwt-issuer-strategy`(자체 JWT)와 정합, SAML 체인과 배타 경로로 공존
- **관련 ADR**: [docs/decisions/2026-06-04-oidc-sso-provider.md](../decisions/2026-06-04-oidc-sso-provider.md) (생성됨, D1~D5)
- **관련 learnings**: `saml-spring-security-integration`(STATELESS↔oauth2Login 충돌·@Order 분리·JIT 재사용), `profile-scoped-bean-boot-failure`(@ConditionalOnBean 부팅가드)

## 스펙

전체 스펙. [docs/specs/2026-06-04-fr-au-04-oidc-sso.md](../specs/2026-06-04-fr-au-04-oidc-sso.md)

핵심 시나리오 요약.
- 사용자가 OIDC 버튼 클릭 → `/oauth2/authorization/{id}` → IdP(Authorization Code + PKCE) → 콜백 `/login/oauth2/code/{id}` → token 교환 + ID Token 검증 → BTS 자체 세션/JWT 발급
- 첫 SSO 로그인 시 JIT 자동 프로비저닝(`AutoProvisionService` 재사용, ID Token `sub` → external_subject)
- 활성 IdP만 버튼 노출(`oidc_provider_configs` enabled), client_secret 암호화 저장
- 인증 검증은 Spring OAuth2 필터가 주도(`OidcProvider`는 thin), BTS는 성공 핸들러로 세션 발급(SAML 동형)

## Brainstorming Check

✅ 통과 (1회 iteration). SAML PR #76 산출물 ground-truth 점검으로 gap 3건 발견·보강(G1 Keycloak OIDC realm 추가 / G2 LdapProvisionAttrs 재사용 / G3 성공 핸들러 자체 세션발급+Clock). 모두 SAML 선례로 자명, Maxi 결정 불요.

## Plan

> 전체 TDD red→green→refactor. agent 기본값 `security-engineer`(auth BC).
> **단일 Gradle 모듈(identity-access)** — backend test끼리 컴파일 단위 공유로 wave 병렬 효과 제한(메모리 `bts-plan-wave-gradle-module-compile`). 프론트(apps/web)는 별 모듈로 백엔드와 병행 가능.
> **SAML(PR #76) 동형** — 대부분 SAML 파일을 OIDC로 1:1 매핑. SAML 산출물을 참조 선례로 활용.

### ★ 게이트1 Maxi 확인 항목

1. **외부 의존성** — **신규 추가 0**(ground-truth: `oauth2-client`/`oauth2-resource-server` build.gradle.kts:54/58 기존재). SAML과 달리 의존성 승인 불요. ADR D3 정정 반영
2. **client_secret 암호화 키** — app encryption key 환경변수 신규 도입(`infra` dev/prod). 키 관리 방식 확인(아래 Task 1)
3. **공유 자산** — SAML ADR D2(이동 없이 `provider.ldap.AutoProvisionService` 재사용) 그대로 승계. 추가 결정 불요

### Task 1. client_secret 암호화 유틸 (신규 인프라)

**메타**.
- agent: `security-engineer`
- files: [`.../identity/config/SecretEncryptor.kt`, `.../identity/config/OidcEncryptionConfig.kt`, `.../test/.../SecretEncryptorTest.kt`]
- depends-on: []

**RED**: 평문 → 암호화 → 복호화 round-trip 일치 + 같은 평문이 매번 다른 ciphertext(salt/IV) + 잘못된 키로 복호화 실패 단위 테스트.
**GREEN**: Spring Security Crypto `AesBytesEncryptor`(또는 `Encryptors.stronger`, AES-256-GCM 계열) 래퍼 `SecretEncryptor`. app key + salt는 환경변수 주입(`@Value`). **OIDC 미설정 환경 부팅 보호** — `@ConditionalOnProperty(oidc.encryption.key)` 또는 키 부재 시 OIDC 협력자 빈만 미구성(메모리 `profile-scoped-bean-boot-failure`).
**REFACTOR**: 키 부재 진단 메시지 명확화, KDoc(복호화 결과 미로깅 N4).
**검증**: `./gradlew :modules:identity-access:test --tests *SecretEncryptor*`

### Task 2. oidc_provider_configs 마이그레이션 V011 + OIDC authn_providers seed

**메타**.
- agent: `db-engineer`
- files: [`.../resources/db/migration/V011__oidc_provider_configs.sql`, `.../test/.../OidcProviderConfigsSchemaTest.kt`]
- depends-on: []

**RED**: V011 적용 후 `oidc_provider_configs` 테이블 존재 + 컬럼(spec §5) + UNIQUE(registration_id) + `authn_provider_id` FK(→authn_providers) + OIDC authn_providers seed row 존재 단언(Testcontainers).
**GREEN**: V011 — spec §5 컬럼(`client_secret_encrypted`, `issuer_uri`, `client_id`, `scopes` 등) + `authn_provider_id uuid NOT NULL REFERENCES authn_providers(id)` + **OIDC `authn_providers` seed**(고정 UUID, type='OIDC', name='OIDC SSO', SAML V010 선례 동형) + enabled 부분 인덱스.
**REFACTOR**: COMMENT/인덱스 정리.
**주의**: 권한 코드 시드 아님 → `PermissionSchemaMigrationTest` 영향 0(메모리 `fr-pm-permission-seed-migration-test-coupling`). plain JDBC repo면 `init_codegen.sql` 미러 불요(SAML V010이 미러 안 함 → 동일).
**검증**: `./gradlew :modules:identity-access:test --tests *OidcProviderConfigsSchema*`

### Task 3. OidcProviderConfig 도메인 + Repository(read) + DB기반 ClientRegistrationRepository 어댑터

**메타**.
- agent: `security-engineer`
- files: [`.../provider/oidc/OidcProviderConfig.kt`, `.../provider/oidc/OidcProviderConfigRepository.kt`, `.../provider/oidc/DbClientRegistrationRepository.kt`, `.../test/.../OidcProviderConfigRepositoryTest.kt`, `.../test/.../DbClientRegistrationRepositoryTest.kt`]
- depends-on: [1, 2]

**RED**: oidc_provider_configs read(enabled만, 비활성 제외 EC5) → `ClientRegistration` 변환(client_secret 복호화 적용) 테스트. issuer-uri discovery로 엔드포인트 해소.
**GREEN**: plain JDBC repository(SAML `SamlIdpConfigRepository` 패턴) + Spring `ClientRegistrationRepository` 구현 어댑터(`DbClientRegistrationRepository`, SAML `DbRelyingPartyRegistrationRepository` 동형). `ClientRegistrations.fromIssuerLocation(issuerUri)`로 discovery + client_secret은 `SecretEncryptor`로 복호화. discovery 호출 비용 → lazy 생성 + 캐시.
**REFACTOR**: discovery 캐시(registrationId별), KDoc.
**주의**: discovery 실패(IdP 다운) 시 부팅 막지 않음(lazy 해소, EC6).
**검증**: `./gradlew :modules:identity-access:test --tests *OidcProviderConfig* --tests *DbClientRegistration*`

### Task 4. OidcProvider thin + OidcAuthenticationSuccessHandler (Principal 변환 + JIT + JWT)

**메타**.
- agent: `security-engineer`
- files: [`.../spi/Credential.kt`(OidcToken 추가), `.../provider/oidc/OidcProvider.kt`, `.../provider/oidc/OidcAuthenticationSuccessHandler.kt`, `.../test/.../OidcProviderUnitTest.kt`, `.../test/.../OidcSuccessHandlerTest.kt`]
- depends-on: [1, 3]

**RED**: `OAuth2AuthenticationToken`(OidcUser) → `Principal`(sub=externalSubject) 변환 + registrationId→authn_providers.id 매핑 + `AutoProvisionService.provision(providerId, LdapProvisionAttrs)` 호출(claims→attrs) + 복귀 경로 화이트리스트(open-redirect N5) 단위 테스트(mock).
**GREEN**:
- `Credential.kt`에 `data class OidcToken(...)` 추가(SPI 일관성 최소 표현, SAML `SamlAssertion` 선례. 검증은 Spring 필터가 수행)
- `OidcProvider` thin(`ProviderType.OIDC` 등록, `supports()`=false, `authenticate()` dead-path Failure 반환 — SAML `SamlProvider` 동형)
- `OidcAuthenticationSuccessHandler` — OidcUser claims 변환 → registrationId로 OIDC authn_providers.id 해소 → AutoProvision(JIT) → 세션/JWT 발급(`SessionService`/`RefreshTokenRepository`/`JwtIssuer` 직접 + Clock 주입, SAML 핸들러 동형) → 복귀
**REFACTOR**: 복귀 경로 검증 분리, KDoc(PII/secret 미로깅 N4).
**검증**: `./gradlew :modules:identity-access:test --tests *OidcProvider* --tests *OidcSuccess*`

### Task 5. OidcSecurityConfig @Order 체인 + oauth2Login wiring + permitAll/CSRF

**메타**.
- agent: `security-engineer`
- files: [`.../config/OidcSecurityConfig.kt`, `.../config/SecurityConfig.kt`(permitAll/CSRF skip 목록 추가), `.../test/.../OidcSecurityConfigTest.kt`]
- depends-on: [4]

**RED**: `/oauth2/authorization/{registrationId}`(진입) + `/login/oauth2/code/{registrationId}`(콜백)가 oauth2 필터에 연결 + 성공 핸들러 wiring + 세션 정책(IF_REQUIRED) + permitAll 슬라이스 테스트.
**GREEN**:
- **OIDC 전용 `@Order(1)` SecurityFilterChain `IF_REQUIRED`**(ADR D1, SAML `SamlSecurityConfig` 동형). `securityMatcher`로 OIDC 경로(`/oauth2/**`, `/login/oauth2/**`)만 배타 매칭 — SAML 체인과 경로 겹침 없음 확인
- `oauth2Login {}` DSL + `DbClientRegistrationRepository` 빈 + `OidcAuthenticationSuccessHandler` 연결
- `@ConditionalOnBean(ClientRegistrationRepository::class, OidcAuthenticationSuccessHandler::class)` 부팅 가드(메모리 `profile-scoped-bean-boot-failure`)
- **permitAll** — `/oauth2/authorization/**`, `/login/oauth2/code/**`, `/api/v1/auth/oidc/providers`(미인증). 콜백은 표준 OAuth2 필터가 처리(자체 토큰교환 코드 금지)
**REFACTOR**: 경로 상수화(`AntPathRequestMatcher`, MVC 비의존 — SAML 선례).
**주의**: SAML 체인(@Order(1))과 OIDC 체인 공존 — `@Order` 값/경로 배타 확인. 활성 PR #75(SecurityFilterChain) 머지 순서 인지.
**검증**: `./gradlew :modules:identity-access:test`

### Task 6. 활성 OIDC IdP 목록 API

**메타**.
- agent: `security-engineer`
- files: [`.../web/OidcProviderController.kt`, `.../web/dto/OidcProviderResponse.kt`, `.../test/.../OidcProviderControllerTest.kt`]
- depends-on: [3]

**RED**: `GET /api/v1/auth/oidc/providers` → enabled IdP만(registrationId, displayName) 반환, 비활성 제외, **client_secret/client_id/issuer 등 민감/내부 정보 미노출**.
**GREEN**: 컨트롤러 + DTO(민감정보 제외). 미인증 접근 허용(로그인 전 호출, SAML `SamlIdpController` 동형).
**REFACTOR**: 응답 DTO ↔ 프론트 Zod 1:1(drift 차단, 메모리 `frontend-zod-backend-dto-contract-gap`).
**검증**: `./gradlew :modules:identity-access:test --tests *OidcProviderController*`

### Task 7. Keycloak OIDC Testcontainers 통합 테스트

**메타**.
- agent: `security-engineer` (+ qa-engineer 인프라 검토)
- files: [`.../test/.../integration/OidcAuthFlowIntegrationTest.kt`, `.../test/kotlin/.../integration/KeycloakOidcTestcontainersBase.kt`, `.../test/resources/keycloak/oidc-test-realm.json`]
- depends-on: [5]

**RED→GREEN**: Keycloak 컨테이너 OIDC 모드 — Authorization Code 진입(`/oauth2/authorization/{id}` → 실 Keycloak authorization endpoint 302 + state/PKCE), 전체 로그인(code 콜백→token 교환→ID Token 검증→세션 발급 S1), ID Token 검증 실패 거부(S3), JIT 멱등(S2/EC2). Testcontainers singleton 패턴(메모리 `Testcontainers 클래스 라이프사이클` stale port 회피).
**주의(G1)**: SAML PR #76은 `KeycloakSamlTestcontainersBase` + `saml-test-realm.json`(SAML 전용 realm)만 도입 → **OIDC realm/client(`oidc-test-realm.json`) + OIDC base 신규**(컨테이너 이미지·`KeycloakImageSelection` ADR 재사용). 단일 모듈 직렬 끝단이라 막히면 전체 지연 → qa-engineer 인프라 검토 게이트1에서 당김. orphan vite/port 방지.
**검증**: `./gradlew :modules:identity-access:test --tests *OidcAuthFlow*`

### Task 8. 프론트 — OIDC IdP 선택 동적 버튼 (D6)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/api/oidc.ts`, `apps/web/src/auth/OidcIdpButtons.tsx`, `apps/web/src/auth/LoginForm.tsx`(OIDC 버튼 영역 추가), `apps/web/src/mocks/oidc-handlers.ts`, `apps/web/src/mocks/handlers.ts`(인덱스 1줄), `apps/web/src/**/*.test.tsx`]
- depends-on: [6]   # API 계약 의존, MSW로 백엔드와 병행 가능

**RED**: `GET /api/v1/auth/oidc/providers` mock → 활성 IdP 버튼 동적 렌더(0개면 미노출 S5), 클릭 시 `/oauth2/authorization/{registrationId}` 이동.
**GREEN**: `api/oidc.ts`(같은 BC api 관례 grep — 메모리 `frontend-api-convention-per-bc`, SAML `api/saml.ts` 선례) + `OidcIdpButtons`(SAML `SamlIdpButtons` 동형) + LoginForm 통합 + MSW handler(read-only). 텍스트 중복 버튼 컨테이너 한정(메모리 `playwright-getbyrole-exact-strict-mode`).
**REFACTOR**: 버튼 컴포넌트 SAML과 공통화 검토(과하면 분리 유지).
**주의**: `handlers.ts`/`LoginForm.tsx`는 SAML 산출물과 같은 파일 — SAML 버튼 영역 옆에 추가(라인 분리). 활성 PR #74/#79 router/handlers 머지 순서 인지.
**검증**: `pnpm --filter @bts/web typecheck && pnpm --filter @bts/web test`

### Task 9. E2E (D7)

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/login-oidc.spec.ts`]
- depends-on: [8]

**RED→GREEN**: MSW 기반 — OIDC 버튼 노출/클릭 리다이렉트(S1 진입), IdP 0개 미노출(S5). 실제 IdP 왕복은 백엔드 통합테스트(T7) 위임. **기존 login/SAML E2E 회귀 0**(컨테이너 한정 셀렉터, 메모리 `playwright-getbyrole-exact-strict-mode` + `ui-pr-defer-e2e-regression-latent`). worktree orphan vite 5173 정리(메모리 `e2e-orphan-vite-after-worktree-remove`).
**검증**: `pnpm --filter @bts/web test:e2e --grep oidc`

## Plan 메타

- task 수: 9
- 예상 wave: backend(T1·T2 병렬 → T3 → T4·T6 → T5 → T7)는 단일 모듈이라 대체로 직렬, 프론트(T8·T9)는 T6 계약 후 백엔드와 병행
- TDD 강제: yes
- 병렬 dispatch: bts-impl이 depends-on + files로 wave 계산
- 추가 검증: ktlint/detekt/ArchUnit + vitest/typecheck + playwright(qa-engineer)
- 게이트1 Maxi 확인: 외부 의존성 0(승인 불요) + client_secret 암호화 키 환경변수 도입

## 구현 결과 (/bts-impl, 2026-06-04)

전 9 task TDD red→green→refactor 완료 + 부팅 fix + PKCE 보강. 백엔드 직렬(단일 모듈), 프론트 병행.

| Task | 상태 | 비고 |
|---|---|---|
| T1 client_secret 암호화 유틸 | ✅ | `Encryptors.stronger`(GCM+random IV, C3). 초기 `@ConditionalOnProperty`(C2)가 부팅 결함 유발 |
| T2 V011 마이그레이션 + OIDC seed | ✅ | seed UUID `...4a04...0004`(C4, SAML과 구분). FK 인덱스 보강 |
| T3 config repo + ClientRegistration 어댑터 | ✅ | issuer discovery seam(`IssuerLocationDiscovery`), client_secret 복호화 |
| T4 OidcProvider thin + 성공 핸들러 | ✅ | SAML 동형, JIT 재사용, Clock 주입. `Credential.OidcToken` 추가 |
| T5 OidcSecurityConfig @Order 체인 | ✅ | OIDC @Order(2), STATELESS 2→3 재배치(C1 동률 회피). 모듈 전체 test로 부팅 결함 표면화 |
| T6 OIDC IdP 목록 API | ✅ | `GET /api/v1/auth/oidc/providers`, 민감정보 미노출 |
| T7 Keycloak OIDC 통합테스트 | ✅ | 실 Keycloak 302 진입 검증, discovery, secret 암호화 round-trip |
| **부팅 fix** | ✅ | `SecretEncryptor` 항상 등록 + 사용 시점 검증(C2 정정). 모듈 전체 test 756 그린(100건 부팅실패 해소) |
| **PKCE 보강** | ✅ | spec N2 충족 — confidential client에도 PKCE S256 강제(authorizationRequestResolver). 실 Keycloak 302에 code_challenge 부착 검증 |
| T8 프론트 OIDC 버튼 | ✅ | SAML 동형, `{providers:[...]}` 언래핑. 프론트 test 1169 그린 |
| T9 E2E | ✅ | OIDC E2E 2개 + SAML E2E 회귀 1건 수정(strict mode, exact:true) |

**게이트2 코드리뷰 전달 항목**:
- C2 정정 — `@ConditionalOnProperty` 부팅가드가 항상 스캔되는 의존성(`DbClientRegistrationRepository`)을 깸 → "항상 등록 + 사용 시점 검증"으로 정정(메모리 `profile-scoped-bean-boot-failure` 재현·해소)
- Task 4 concern — `username = sub` 매핑(SAML nameId 동형). OIDC sub는 불투명 식별자라 `preferred_username` 우선 정책 검토 필요(코드리뷰 판단)
- Task 5 concern — 복귀경로(`returnTo`)는 본 FR에서 기본 랜딩(`/dashboard`) 고정, open-redirect 방어만 유지. state 연동 복귀는 후속

## 리뷰 결과

### code-reviewer ground-truth 리뷰 (2026-06-04, plan-eng 대체 — 메모리 `bts-review-plan-autoplan-overkill`)

**BLOCKER 0 / CONCERN 4.** SAML plan-review(BLOCKER 1+CONCERN 9) 대비 환각·FK갭·세션충돌·의존성환각 모두 사전 해소. 코드로 검증한 PASS 11건(의존성 line 54/58 정확, ProviderType.OIDC(50), SAML 선례 전부 실재, Spring OAuth2 API 환각 0, `AutoProvisionService.provision(UUID, LdapProvisionAttrs)` 시그니처 일치, V011 정확, FK 갭 없음 등). 게이트1 통과 권장.

CONCERN 4건 — **impl 착수 시 반영(못박기)**:
- **C1 (Task 5, @Order 동률)** — SAML 체인 `@Order(1)`(`SamlSecurityConfig.kt:56`), STATELESS `@Order(2)`(`SecurityConfig.kt:75`). OIDC도 `@Order(1)`로 두면 SAML과 **동률 → 평가순서 비결정**(경로 배타라 실해는 없으나 fragile). → **OIDC 체인 `@Order(2)` + 기존 STATELESS `@Order(3)`으로 재배치**(SecurityConfig.kt order 값 1칸 밀기). Task 5 files에 `SecurityConfig.kt` order 수정 포함
- **C2 (Task 1/5, 부팅 가드)** — `@ConditionalOnProperty`는 BC 내 선례 0. SAML 선례 `@ConditionalOnBean`(`SamlSecurityConfig.kt:40`) **우선 채택**. `@ConditionalOnProperty` 신규 도입 시 슬라이스 부팅 영향 별도 검증
- **C3 (Task 1, 암호화 구성)** — `AesBytesEncryptor` 기본(CBC+고정IV)이면 "매번 다른 ciphertext" RED 깨짐. → **`Encryptors.stronger(password, hexSalt)` 또는 GCM+random-IV 명시 구성** 강제. salt는 hex 문자열
- **C4 (Task 2, seed UUID)** — OIDC authn_providers seed 고정 UUID는 SAML(`00000000-0000-4a03-8000-000000000003`)과 **반드시 다른 값**(예: `...4a04...`). `ON CONFLICT (name) DO NOTHING` 멱등 유지

전체 리뷰 근거: code-reviewer agent `af0bfe6e407284e1c` (파일:라인 인용 포함).

### 게이트2 PR 코드리뷰 (2026-06-04, code-reviewer ground-truth) — PASS

**BLOCKER 0 / CONCERN 2.** auth 영역 절대규칙 §1.1 보안 + §1.2 데이터무결성 + 환각/dead-path + 전달 concern을 파일:라인으로 직접 검증. PASS 항목:
- §1.1.1 client_secret 암호화(통합테스트가 DB 저장값≠평문 단언), §1.1.2 PII/secret 미로깅(로그는 providerId/registrationId만)
- §1.1.4 3체인 distinct @Order(SAML 1/OIDC 2/API 3)+securityMatcher 배타, permitAll 의도 경로만, §1.1.5 OIDC 체인 csrf.disable이 콜백 한정·API CSRF 무손상
- §1.2 V011 Flyway·FK 정합(seed 선INSERT)·파라미터 바인딩(SQL인젝션 0)
- OIDC 보안: ID Token 검증 프레임워크 위임(N1), PKCE S256 강제(실 Keycloak 302 code_challenge 단언), state/nonce, open-redirect(RelayStateValidator 재사용)
- C2 정정 검증: SecretEncryptor 항상등록+사용시점 fail-fast → 부팅 안전(profile-scoped-bean-boot-failure 회피 확인)
- 환각 0: OidcProvider thin/dead-path, Credential.OidcToken 정합, JIT 멱등(ON CONFLICT)

CONCERN 2건(머지 비차단, 의도된 후속 이연):
- C-1 (Task4) username=sub — UX 차원(displayName은 fullName→preferredUsername→sub fallback 정상, username은 ON CONFLICT 키라 sub 안정성 우선). FR-AU-06/08 시 preferred_username 승격 검토
- C-2 (Task7) full callback 왕복 미자동화 — SAML 게이트2 "302 진입 검증" 동형, spec §9 완료기준 충족. token 교환/ID Token 검증은 프레임워크 책임

리뷰 근거: code-reviewer agent `a2bd3dcff3d0b8709`.

**/review(gstack)·/plan-ceo-review 생략** — code-reviewer가 SQL안전성/CSRF/부수효과/plan약속을 종합 커버, 메모리 `bts-review-plan-autoplan-overkill` 정신(SAML PR #76 동형).

### 게이트1 Maxi 결정 (2026-06-04, 승인 → 구현 착수)

- **외부 의존성** = 신규 0(oauth2-client/resource-server 기존재) → 승인 불요 확정
- **client_secret 암호화** = 채택(Task 1, GCM+random-IV C3 반영)
- **공유 자산** = SAML ADR D2(이동 없이 AutoProvisionService 재사용) 승계
- **게이트1** = 승인. 9 task TDD 구현 착수. CONCERN 4건(C1~C4) impl 반영 조건
