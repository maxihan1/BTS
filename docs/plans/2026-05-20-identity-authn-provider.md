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

## 스펙 (← /bts-spec Phase A 채움)

_TBD_

## Brainstorming Check (← /bts-spec Phase B 채움)

_TBD_

## Plan (← /bts-plan 채움)

_TBD_

## 리뷰 결과 (← /bts-review-plan 채움)

_TBD_
