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

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
