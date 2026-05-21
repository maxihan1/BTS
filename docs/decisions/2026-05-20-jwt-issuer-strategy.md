<!-- ADR — BTS 자체 JWT 발급자 전략 결정. Spring Authorization Server 미도입 + Keycloak 역할 재정의 -->

# ADR — JWT 발급자 전략: BTS 자체 발급 (Spring Authorization Server 미도입)

**일자.** 2026-05-20
**상태.** Accepted
**관련 PR.** #7 (`fr-au-09-securityfilterchain-local-provider-pr-6-7`)
**작성자.** security-engineer (Claude Sonnet 4.6)

---

## 컨텍스트

BTS 인증 흐름(SDD 19.2/19.5)은 Local 로그인, LDAP 로그인, Personal Access Token(PAT) 세 경로를 단일 Spring Boot 백엔드에서 처리한다.
PR #2 시점에는 `spring.security.oauth2.resourceserver.jwt.issuer-uri`를 Keycloak realm URI로 설정해 두었다.
이 구조는 Keycloak이 Access Token을 직접 발급한다고 가정하는데, BTS가 요구하는 아래 두 가지와 충돌한다.

1. **Local Provider(이메일/비밀번호) 직접 인증.** Keycloak은 BTS의 `local_credentials` 테이블(V003, Argon2id)을 모르기 때문에 Local 인증 흐름을 대리할 수 없다.
2. **Session DB 연동.** 1 로그인 = 1 `sessions` row(V004). `sid` 클레임을 JWT에 포함해야 하며, 토큰 revoke 시 `revoked_at`을 업데이트한다. Keycloak이 발급한 JWT에는 이 클레임을 심을 방법이 없다.

따라서 JWT 발급 주체를 BTS 자체로 가져올지, Spring Authorization Server를 도입할지, Keycloak에 위임할지 결정해야 한다.

---

## 결정

**BTS 자체 JWT 발급. Spring Authorization Server 미도입.**

구체적으로 다음과 같이 결정한다.

| 항목 | 결정 |
|---|---|
| JWT 발급 라이브러리 | `nimbus-jose-jwt` 직접 사용 (`JwtIssuer` 클래스) |
| 서명 알고리즘 | RS256 (RSA 2048 이상 PEM 키) |
| 키 로딩 | PoC: `DevMemoryKeyProvider` (테스트 전용 인메모리 키). prod: `PemFileKeyProvider` (`bts.auth.jwt.private-key-pem-path` 환경 변수) |
| `kid` 클레임 | 현재 `"k-01"` 상수. 키 rotation 시 변경 예정 |
| 공개키 노출 | `GET /.well-known/jwks.json` (public endpoint, Cache-Control 24h) |
| Spring Authorization Server | **미도입.** `/oauth2/token` 등 표준 OAuth2 endpoint 비활성 |
| Keycloak 역할 | SDD 19.2 `ProviderType.OIDC`의 한 인스턴스 — 외부 IdP(OIDC IdP). BTS Access Token 발급 책임 없음 |

**회귀 가드 — Spring Authorization Server 환각 함정.**
Spring Authorization Server 1.x는 OAuth 2.1 표준만 지원하며 Resource Owner Password Grant를 지원하지 않는다.
BTS의 Local 로그인(`POST /api/v1/auth/login`)은 username/password를 직접 받아 BTS가 인증하는 흐름이므로, Spring Authorization Server를 도입하면 Password Grant 경로가 없어 Local 인증이 불가능하다.
**이 결정은 spec §4.6에도 명시되어 있으며, Spring Authorization Server 도입을 재검토하는 코드 변경은 반드시 이 ADR을 참조해야 한다.**

---

## 대안 검토

### 대안 1. Spring Authorization Server 도입 (불채택)

`spring-security-oauth2-authorization-server`를 추가하고 `RegisteredClient bts-spa`를 등록한다.
표준 endpoint(`/oauth2/token`, `/oauth2/authorize`, `/oauth2/revoke`, `/oauth2/introspect`, `/.well-known/jwks.json`)가 자동으로 구축된다.

**불채택 이유.**
- OAuth 2.1은 Resource Owner Password Grant를 제거했다. BTS의 Local 로그인처럼 username/password를 직접 받는 흐름을 표준 endpoint로 처리할 수 없다.
- `sid` 클레임, `mfa_verified` 클레임 등 BTS 고유 클레임을 JWT에 주입하려면 `OAuth2TokenCustomizer` 구현이 필요하며, BTS 인증 흐름과의 결합이 복잡해진다.
- Spring Authorization Server의 `@Order(1)` SecurityFilterChain이 자동 등록되어 기존 BTS filter chain과 Bean 충돌 위험이 있다(EC-20).
- 3rd-party OAuth2 client 통합(향후 PR)이 확정될 때 도입하는 것이 적합하다.

### 대안 2. Keycloak에 JWT 발급 위임 (PR #2 방식, 불채택)

`spring.security.oauth2.resourceserver.jwt.issuer-uri`를 Keycloak realm으로 유지하고, 모든 인증을 Keycloak에 위임한다.

**불채택 이유.**
- Local Provider 인증(`local_credentials` + Argon2id)을 Keycloak이 처리하려면 Keycloak Custom Identity Provider SPI를 구현해야 한다. BTS 외부 컴포넌트인 Keycloak에 BTS 도메인 로직을 심는 것은 BC 격리 원칙 위반이다.
- `sessions` 테이블(V004)의 `sid` 클레임을 Keycloak 발급 JWT에 포함하는 방법이 없다.
- LDAP 로그인도 Keycloak이 중간 IdP 역할을 맡으면 BTS의 `user_external_accounts`(V002) UPSERT와 충돌한다.

### 대안 3. BTS 자체 JWT 발급 — nimbus-jose-jwt 직접 (채택)

별도 라이브러리 오버헤드 없이 `nimbus-jose-jwt`로 `JwtIssuer` 유틸 클래스를 작성한다.
Spring Boot가 이미 `spring-security-oauth2-resource-server`를 통해 nimbus를 간접 의존하므로 추가 라이브러리 비용이 없다.

---

## 결정 근거

1. **Local Provider 직접 인증 필수.** `local_credentials`(V003) + Argon2id 검증은 BTS 내부 로직이므로, JWT 발급자도 BTS여야 한다.
2. **BTS 고유 클레임.** `sid`(Session ID, V004 revoke 연동), `mfa_verified`(SDD 19.7 준비), `jti`(Refresh rotation) 등을 자유롭게 제어해야 한다.
3. **Keycloak은 외부 IdP 인스턴스.** SDD 19.2 `ProviderType.OIDC`의 한 구현체로서, OIDC 로그인 경로에서만 역할을 가진다. BTS Access Token 발급에는 관여하지 않는다.
4. **단순성 우선.** PoC 단계에서 Spring Authorization Server의 학습 비용과 Bean 충돌 위험보다 `nimbus-jose-jwt` 직접 사용이 명확하다.
5. **JWK Set 공개.** `GET /.well-known/jwks.json`을 직접 구현해 Resource Server(현재 BTS 자신 + 미래 3rd-party)가 서명 검증에 사용한다.

---

## 결과

### 긍정

- BTS 인증 흐름(Local/LDAP/PAT)을 단일 백엔드에서 완전히 제어한다.
- Keycloak 가용성이 BTS Local 로그인에 영향을 주지 않는다. Keycloak이 내려가도 Local + LDAP 로그인 동작(CONCERN-4 격리).
- JWT 클레임 구조가 BTS 도메인에 최적화된다.

### 부정 / 위험

- 표준 OAuth2 endpoint(`/oauth2/authorize` 등)가 없으므로, 향후 3rd-party OAuth2 client 통합 시 Spring Authorization Server를 도입해야 한다.
- JWT 라이브러리 직접 사용에 따른 보안 책임이 BTS에 있다. 서명 알고리즘 선택, 키 관리, 만료 검증 등을 명시적으로 구현해야 한다.
- PoC 단계 키(`DevMemoryKeyProvider`)는 재시작 시 새 키가 생성되므로 기존 Access Token이 무효화된다. 개발 환경 전용.

### 후속 조건

- 3rd-party OAuth2 client 통합 필요 시 → 별도 PR에서 Spring Authorization Server 도입 재검토.
- JWK Set endpoint 캐시 무효화(rotation) → `2026-05-20-jwt-key-rotation-policy.md` 참조.

---

## 참조

- `docs/specs/2026-05-20-fr-au-09-securityfilterchain-local-provider-pr-6-7.md` §4.6 (Spring Authorization Server 미도입 결정)
- `docs/specs/2026-05-20-fr-au-09-securityfilterchain-local-provider-pr-6-7.md` §5 V007 (키 rotation 스키마 후속 결정)
- `docs/decisions/2026-05-20-jwt-key-rotation-policy.md` (kid 관리 + rotation 메커니즘)
- `docs/decisions/2026-05-20-session-pat-schema.md` (Session/RefreshToken/PAT 스키마 + sid 클레임 근거)
- `docs/decisions/2026-05-20-keycloak-image-selection.md` (Keycloak 인스턴스 — 역할 재정의 영향)
- `Maxi_wiki/BTS/domain/identity-access.md` (BC 정의 + 핵심 엔티티)
- SDD 19.2 (플러그형 Provider — ProviderType.OIDC)
- SDD 19.5 (Session/Token 관리 Full scope)
- DEVELOPMENT.md §1 (보안 절대 규칙 — 토큰 해시 저장, localStorage 금지)
