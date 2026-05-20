<!-- BTS plan — identity-access FR-AU-01 플러그형 AuthenticationProvider 구조 -->

# FR-AU-01 — 플러그형 AuthenticationProvider 구조

> slug: identity-authn-provider
> type: auth
> agent: security-engineer
> primary_bc: identity-access
> 생성: 2026-05-20
> 마스터플랜: `docs/plan/product/identity-access.md §2.1`

## Brief

identity-access BC 정식 진입의 첫 PR. SDD 04장 (인증/계정) §1 + 마스터플랜 §2.1 기준 — 향후 LDAP/SAML/OIDC/Local 등 모든 Provider가 등록될 **확장 지점**을 먼저 구축.

§1 PoC (PR #2)는 Spring Security + Keycloak 한 묶음으로 통합 검증한 PoC였다. 이 PR은 그 위에 정식 도메인 모델 (`Principal`/`Credential`/`AuthnResult`) + 인터페이스 (`AuthenticationProvider`) + 등록 메커니즘 (`ProviderRegistry`) 을 얹는다. 이후 FR-AU-02~05 (LDAP/SAML/OIDC/Local) 가 모두 이 인터페이스 구현체로 들어온다.

**이 PR 스코프** = §2.1 D1~D5 (백엔드 + 백엔드 테스트) 까지. D6 (UI) + D7 (E2E) 는 별도 PR.

## 도메인 정리

- **BC**. identity-access
- **영향 엔티티**. `AuthenticationProvider` (기존 glossary 등재, 인터페이스 정식화), `ProviderRegistry` (신규), VO 3종 `Principal`/`Credential`/`AuthnResult` (신규)
- **새 용어** (glossary 추가 후보, Maxi 승인 필요).
  - `Principal` — 인증 완료 후의 주체 식별 정보 VO (userId, providerType, displayName, externalSubject)
  - `Credential` — 인증 시도 입력 VO. sealed (UsernamePassword / OidcToken / SamlAssertion / LdapBind / Pat). 메모리에만 존재, DB 저장 안 됨
  - `AuthnResult` — 인증 결과 VO. sealed (Success / Failure / RequiresMfa)
  - `ProviderRegistry` — 도메인 `AuthenticationProvider` Bean 등록소, Spring DI 컨테이너 위 얇은 추상화
- **기존 결정 충돌**.
  - **명명 충돌 위험**. BTS 도메인 `AuthenticationProvider` ≠ Spring Security `org.springframework.security.authentication.AuthenticationProvider`. ADR로 해소 (B안. 도메인 이름 유지 + 패키지 격리 + Spring 어댑터 1개)
  - **`UserCredential` (PoC DB 엔티티)** vs **`Credential` (신규 입력 VO)** — 다른 레이어. 다음 PR로 `UserCredential` 리네임 검토 (본 PR 스코프 외)
- **관련 ADR**. [docs/decisions/2026-05-20-authentication-provider-spi-naming.md](../decisions/2026-05-20-authentication-provider-spi-naming.md) (신규, 본 PR로 생성)
- **선행 PoC ADR** (PR #2, 2026-05-20). argon2id-parameters / csrf-cookie-mode / keycloak-image-selection / testcontainers-docker-desktop-config — 변경 없음, 본 PR 영향 없음
- **grill-with-docs 우회 사유**. FR-AU-01은 인터페이스 + Registry라는 단일 책임. 도메인 모델 면적 작음. 1인 부담 + Auto mode 합리적 판단으로 직접 분석 (PoC 패턴 일관성)

## 스펙

전체 스펙. [docs/specs/2026-05-20-identity-authn-provider.md](../specs/2026-05-20-identity-authn-provider.md)

핵심 3줄.
- **순수 SPI 도입** — `AuthenticationProvider` 인터페이스 + `ProviderRegistry` + VO 3종 (`Principal`/`Credential`/`AuthnResult`) + Spring 어댑터 1개. 외부 REST 변경 0건
- **명명 충돌 해소** — BTS 도메인 SPI vs Spring Security 동명 인터페이스 분리. ArchUnit 룰로 경계 강제
- **CONCERN-NEW-2 처리** — PoC #2의 `issuer-uri` 하드코딩을 `${BTS_KEYCLOAK_ISSUER_URI:...}` 환경변수로 외부화

명시적 비-스코프. LDAP/SAML/OIDC Provider 실제 구현 (FR-AU-02~04 별도 PR), `authn_providers` 테이블 (LDAP PR에서 도입), SecurityFilterChain 통합 (FR-AU-09 PR에서).

## Brainstorming Check

✅ 통과 (1회 iteration, gap 7건 식별 모두 spec 본문에서 닫힘 — iteration 불필요).

### Phase B 직접 수행 — 발견된 gap

1. **`Credential.UsernamePassword.password: CharArray` equals/hashCode 우려** — Argon2.verify로 비교하므로 data class equals 불사용. 정상.
2. **가짜 Provider 등록 프로필** — `@TestConfiguration` + `@Profile("test")` 보다 **`@Profile("test-spi")` 별도 프로필**이 PoC #2 OIDC 통합 테스트와 충돌 방지에 안전. spec FR-1 보강 — plan task에서 명시.
3. **ArchUnit 신규 도입** — `libs.versions.toml` 등록 task 분리. plan T1 (인프라).
4. **`SpringSecurityProviderAdapter` 의 Spring DI 등록 정책** — `@Component` 자동 등록은 SecurityFilterChain 충돌 위험. `@Bean` 명시 등록만 + 본 PR에서는 FilterChain 미등록 (단위 테스트만). spec FR-3 명시.
5. **`MfaChallenge.NOT_IMPLEMENTED_YET`** placeholder enum — 향후 sealed로 변경 시 ABI 깨짐, BTS는 내부 backend라 무관. 정상.
6. **CONCERN-NEW-2 + PoC #2 회귀** — `@DynamicPropertySource` > env > yaml 우선순위로 PoC #2 `KeycloakIntegrationTest` 영향 없음 확인. spec NFR 명시.
7. **`Principal.toString` PII 마스킹** — Kotlin data class toString override. spec §7 §1.2 명시.

### iteration 결정

gap 모두 spec 본문 내에서 닫힘 + plan task 분리로 위임 가능. office-hours/brainstorming 대화형 우회 사유. PoC 패턴 일관성 (1인 부담 + 도메인/스펙 결정이 비교적 명확). `/bts-plan` 진입.

## Plan (← /bts-plan 채움)

_TBD_

## 리뷰 결과 (← /bts-review-plan 채움)

_TBD_
