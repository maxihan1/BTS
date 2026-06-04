<!-- ADR — FR-AU-04 OIDC SSO Provider 도입 + OIDC 전용 SecurityFilterChain 분리 결정 -->

# ADR: OIDC SSO Provider 도입 + OIDC 전용 SecurityFilterChain 분리

> 결정일. 2026-06-04
> 상태. Accepted
> 컨텍스트. identity-access BC §2.4 FR-AU-04 (slug `fr-au-04-oidc-sso`, PR #80)
> 선행. ADR `2026-05-20-authentication-provider-spi-naming`(SPI 어댑터 패턴), `2026-06-04-saml-sso-provider`(SSO 전용 체인 분리 + JIT 재사용 선례)

## 컨텍스트

FR-AU-04는 OIDC(OpenID Connect) SSO를 identity-access BC에 추가한다. FR-AU-01의 SPI 패턴 위에 OIDC를 얹되,
Spring Security OAuth2 Client(`spring-boot-starter-oauth2-client`)가 Authorization Code + PKCE 인증 흐름을
프레임워크 차원에서 주도한다. 직전 FR-AU-03(SAML, PR #76)이 확립한 "SSO 전용 SecurityFilterChain @Order 분리 +
JIT 프로비저닝 재사용" 패턴을 그대로 따른다.

## 결정

### D1. OIDC 전용 SecurityFilterChain @Order 분리 (SAML 선례 동형)

Spring Security OAuth2 Client의 Authorization Code 흐름은 `state`/`nonce`/PKCE `code_verifier`를 HttpSession에
저장한다(CSRF/replay 방어). 전역 STATELESS(`SecurityConfig`)와 충돌하므로, **OIDC 경로
(`/oauth2/authorization/**`, `/login/oauth2/code/**`)에 한정한 별도 `SecurityFilterChain`을 `@Order(1)`로 분리**하고
`SessionCreationPolicy.IF_REQUIRED`를 허용한다. 나머지 모든 경로(`/api/**` 등)는 기존 STATELESS 유지.

SAML 체인(Order=1)과 OIDC 체인이 공존하므로, 두 체인은 `securityMatcher`로 상호 배타적 경로를 잡는다.
클래스 레벨 `@ConditionalOnBean(ClientRegistrationRepository::class, ...)`으로 OIDC 협력자 빈이 존재할 때만
활성화하여(profile-scoped boot 회귀 방지, 메모리 `profile-scoped-bean-boot-failure`), 협력자가 없는 슬라이스 테스트
컨텍스트 부팅을 깨지 않는다.

### D2. 엔드포인트 = Spring Security OAuth2 표준 경로

진입 `/oauth2/authorization/{registrationId}`, 콜백(redirect URI) `/login/oauth2/code/{registrationId}`로 통일한다.
SAML의 D5 교훈(`/sso/saml2/...` 미배선 별칭 BLOCKER)을 회피하기 위해 비표준 별칭을 만들지 않는다.
프론트(OIDC 버튼)·`securityMatcher` 모두 표준 경로만 사용한다.

### D3. 의존성 = oauth2-client 단독 (resource-server 제외)

BTS는 IdP로 로그인만 받고 **자체 JWT/세션을 발급**한다(ADR `jwt-issuer-strategy`). 외부 IdP의 access token을
BTS가 보호 리소스 검증 용도로 소비하지 않으므로 `spring-boot-starter-oauth2-resource-server`는 도입하지 않는다
(dead 의존성 회피). `oauth2-client`만으로 충분하다. SAML이 `saml2-service-provider`만 쓴 것과 동형.

### D4. oidc_provider_configs ↔ authn_providers 연결 + client_secret 암호화 저장

JIT는 `AutoProvisionService.provision(providerId: authn_providers.id, ...)`를 호출한다. 따라서
`oidc_provider_configs.authn_provider_id uuid NOT NULL REFERENCES authn_providers(id)`를 두고, 마이그레이션이
OIDC `authn_providers` seed row(고정 UUID, type=OIDC)를 함께 INSERT한다. 성공 핸들러는
OAuth2 `registrationId` → `oidc_provider_configs` → `authn_provider_id`로 해소한다.

**client_secret은 비밀값이므로 평문 저장하지 않는다.** SAML `idp_x509_cert`(공개값, 평문 허용)와 달리,
`client_secret`은 애플리케이션 키 기반 대칭 암호화(Spring Security Crypto `AesBytesEncryptor` 계열)로 암호화하여
`oidc_provider_configs.client_secret_encrypted`에 저장한다. 복호화 키는 환경변수/시크릿으로 주입한다
(`application.yml` 평문 금지). 절대 규칙 "평문 비밀 저장 금지"의 정신을 시스템 자격증명에도 적용한다.

### D5. OidcProvider 역할 (dead-path 방지 — SAML SamlProvider 동형)

`OidcProvider`는 `ProviderType.OIDC`(priority 50, 이미 존재) 등록/메타 노출 목적의 thin 구현으로 두고,
**인증 검증은 Spring OAuth2 필터 + `OidcAuthenticationSuccessHandler`(자작)가 수행**한다. 도메인 SPI
`authenticate()`를 OIDC에 강제하지 않는다(호출 진입점이 없어 dead-path). `supports()`는 항상 false.
`Credential.OidcToken`은 SPI 표현 일관성을 위한 최소 형태로 신규 추가 검토(SAML `SamlAssertion` 선례).

## 결과

- 외부 의존성 1개 추가(`spring-boot-starter-oauth2-client`, Spring Boot 3.x BOM 관리). resource-server 미도입.
- SecurityFilterChain 3개(SAML IF_REQUIRED @Order(1) + OIDC IF_REQUIRED @Order(1, 배타 경로) + 기존 STATELESS).
- 신규 테이블 `oidc_provider_configs`(authn_provider_id FK + client_secret 암호화 컬럼). users/user_external_accounts 재사용.
- 암호화 유틸 신규 도입(키 관리 환경변수). 기존 BC에 암호화 유틸 부재 확인됨.
- JIT 자동 프로비저닝 LDAP/SAML 패턴 재사용(`AutoProvisionService`).

## 보안 (DEVELOPMENT.md §1)

- PKCE 필수(Authorization Code 가로채기 방어, Spring oauth2Client 기본 지원).
- ID Token 서명 검증 필수(JWKS, 프레임워크 위임), `nonce` 검증(replay 방어), `state` 검증(CSRF 방어).
- client_secret 암호화 저장(D4), 복호화 키 환경변수 주입(평문 yml 금지).
- redirect URI 화이트리스트(open-redirect 차단), PII 미로깅.
- 콜백(`/login/oauth2/code/**`)은 표준 OAuth2 필터가 처리 — 자체 토큰 교환 코드 작성 금지(프레임워크 위임).

## 관련

- 마스터플랜 §2.4 (`docs/plan/product/identity-access.md`)
- 선례 ADR `docs/decisions/2026-06-04-saml-sso-provider.md`
- plan `docs/plans/2026-06-04-fr-au-04-oidc-sso.md`
- SDD 19장(인증)
