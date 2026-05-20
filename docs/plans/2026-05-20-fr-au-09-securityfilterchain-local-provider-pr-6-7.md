# FR-AU-09 세션/토큰 관리 — SecurityFilterChain 통합 + Local Provider 연결

> slug: fr-au-09-securityfilterchain-local-provider-pr-6-7
> type: auth
> agent: security-engineer
> primary_bc: identity-access
> 생성: 2026-05-20

## Brief

**사용자 원문**.
> FR-AU-09 세션/토큰 관리 진입 — SecurityFilterChain 통합 + Local Provider 연결. PR #6/#7 blocker 모두 해소된 상태에서 백엔드 인증 매듭의 마지막 조각. CONCERN-1 트랜잭션 경계 + CONCERN-4 LDAP stop 시나리오 포함.

**classify 결과**.
- type. `auth`
- agent. `security-engineer`
- primary_bc. `identity-access`
- slug. `fr-au-09-securityfilterchain-local-provider-pr-6-7`

**선행 컨텍스트 (체크포인트 §Remaining Work #1 + PR #6 learning)**.
- PR #6 의 `LocalCredentialService` 가 `@Service` 부착 + `@Transactional` 정합 통과, but **호출자 없음**. 본 PR 이 첫 호출자.
- PR #6 CONCERN-1 (트랜잭션 경계 통합) + CONCERN-4 (LDAP stop 시나리오) 가 본 PR 의 스펙 범위.
- learning **"@Service 부착 누락 시 @Transactional 무력화"** 가 FR-AU-09 SecurityFilterChain 통합 시점 표면화 위험으로 명시됨. 본 PR 에서 회귀 검증.

## 도메인 정리 (/bts-domain 작성 완료)

### BC + 영향 모듈

- BC. identity-access
- 모듈. `backend/modules/identity-access/`
- agent. security-engineer

### 사전 결정 (사용자 확정)

1. **세션 전략 scope.** SDD 19.5 Full — Access JWT (15분) + Refresh Token (14일, HttpOnly Cookie) + Session DB (14일, revoke 보조) + PersonalAccessToken (사용자 지정, DB SHA-256 해시) 일괄 구현.
2. **JWT 발급자.** BTS 자체. `oauth2ResourceServer.issuer-uri = Keycloak realm` 은 PR #2 잔재 — 본 PR 에서 BTS 자체 issuer 로 교체. Keycloak 은 SDD 19.2 `ProviderType.OIDC` 의 한 인스턴스로 역할 재정의.
3. **JWT 발급 메커니즘.** **Spring Authorization Server** (`spring-security-oauth2-authorization-server`) 도입. token / authorize / revoke / introspect / jwks 표준 OAuth2 endpoint 자동 구축.

### 신규 용어 (glossary.md 추가 후보)

| 용어 | 영문 | 정의 |
|---|---|---|
| 액세스 토큰 | Access Token | 15분 만료 JWT. sessionStorage 저장. API 호출 시 Authorization Bearer 헤더 |
| 리프레시 토큰 | Refresh Token | 14일 만료. HttpOnly Cookie 저장. 만료 access token 갱신용 |
| 세션 | Session | BTS 자체 세션 row (DB). sid = JWT 클레임. revoke 메커니즘. **1 로그인 = 1 row (device 단위)** |
| 개인 액세스 토큰 | Personal Access Token (PAT) | API 클라이언트용 장기 토큰. DB SHA-256 해시 저장. supportedScopes 평가 |
| JWK Set | JSON Web Key Set | BTS 가 노출하는 공개키 묶음 (`/.well-known/jwks.json`). Resource Server 가 서명 검증에 사용 |
| kid | Key ID | JWT 헤더 클레임. JWK Set 의 어느 키로 서명했는지 식별. rotation 대비 |
| sid | Session ID | JWT 클레임. Session 테이블 row id. revoke 검증 시 사용 |
| 토큰 재발급 | Token Rotation | Refresh Token 사용 시 새 access + 새 refresh 발급 + 옛 refresh 무효화 |
| revoke | Revocation | Session/Refresh/PAT 즉시 무효화. Session 테이블 `revoked_at` 채움 |

### 신규 엔티티 (DB 마이그레이션 V004+)

| 엔티티 | 테이블 | 관계 |
|---|---|---|
| Session | `sessions` (V004) | User 1:N. 디바이스/로그인 단위 row. sid = PK |
| RefreshToken | `refresh_tokens` (V005) | Session 1:N (rotation chain). 토큰 SHA-256 해시 저장 |
| PersonalAccessToken | `personal_access_tokens` (V006) | User 1:N. 토큰 SHA-256 해시 저장. supportedScopes JSON |
| JwtSigningKey | `jwt_signing_keys` (V007, optional) | kid rotation 용. 키 1개 application.yml 시작도 가능 — spec 결정 |

### 신규 ADR 후보 (본 PR 에서 작성)

1. **`2026-05-20-jwt-issuer-strategy.md`** — BTS 자체 JWT 발급자 (Spring Authorization Server 채택). Keycloak 은 OIDC IdP 인스턴스로 역할 재정의.
2. **`2026-05-20-session-pat-schema.md`** — Session / RefreshToken / PersonalAccessToken 스키마 + 관계. 1 로그인 = 1 Session row (device 단위) 결정.
3. **`2026-05-20-jwt-key-rotation-policy.md`** — kid 관리 정책. PoC 단계 키 1개 + rotation 메커니즘은 schema 만 준비.

### 기존 ADR 정정 후보

- **`2026-05-20-csrf-cookie-mode.md`** — 본문 "BTS는 JWT Bearer Token 기반 stateless 아키텍처이므로 세션을 유지하지 않는다." 표현이 SDD 19.5 의 Session DB 와 모순. 본 PR 에서 정정 — 정확히는 "Access Token 검증은 stateless, Session/RefreshToken/PAT 은 revoke 보조 DB 사용".

### domain/identity-access.md 갱신 후보 (Obsidian 단방향 룰 — sync-obsidian.ts 자동화 또는 별도 PR 메모)

- 책임 추가. "세션/토큰 발급 (BTS 자체 JWT)" 명확화.
- 핵심 엔티티 갱신. Session / RefreshToken / PersonalAccessToken / JwtSigningKey 추가.

### 모순 / 충돌 해소

1. **SDD 19.5 stateless vs Session DB 모순.** 본 PR 에서 명확화 — Access Token 은 stateless 검증, Session DB 는 revoke 보조 (정상 흐름에서는 DB 미접근, revoke 시에만 검사).
2. **SDD 19.3 AuthRouter vs 현재 ProviderRegistry.findFor(Credential) 책임 분리.** ProviderRegistry 는 Credential type → Provider, AuthRouter 는 username/email domain → Provider. 본 PR 에는 ProviderRegistry 만 활용, AuthRouter 도입은 spec 단계 결정.

### bts-spec 단계 결정 분기 (scope 묶음 미루기)

1. **2FA (SDD 19.7) 본 PR scope.** JWT 클레임 `mfa_verified` 가 본 PR 에서 의미 갖게 하려면 TOTP/백업코드 부분 도입 필요. 또는 dummy `mfa_verified=false` 시작 + 후속 PR.
2. **AuthRouter (SDD 19.3) 본 PR scope.** 본 PR vs 별도 PR.
3. **Auto-provisioning (SDD 19.2 supportsAutoProvisioning) 본 PR scope.** LDAP 첫 로그인 시 BTS user 자동 생성 — 본 PR 동작에 필요한가.
4. **PAT 발급 endpoint 본 PR scope.** 모델만 정의 + endpoint 후속 PR? 또는 본 PR 에 endpoint 포함?

### 관련 기존 ADR

- `docs/decisions/2026-05-20-csrf-cookie-mode.md` (CSRF Cookie 모드 — 본 PR 정정 대상)
- `docs/decisions/2026-05-20-authentication-provider-spi-naming.md` (Credential / Provider SPI 명칭)
- `docs/decisions/2026-05-20-stored-password-credential-schema.md` (V003 스키마)
- `docs/decisions/2026-05-20-keycloak-image-selection.md` (Keycloak 이미지 — 역할 재정의 영향)

## 스펙 (/bts-spec Phase A 작성 완료)

전체 스펙. [docs/specs/2026-05-20-fr-au-09-securityfilterchain-local-provider-pr-6-7.md](../specs/2026-05-20-fr-au-09-securityfilterchain-local-provider-pr-6-7.md)

**핵심 시나리오 3줄 요약.**
- Local 로그인. Spring Authorization Server 자체 client (`bts-spa`) 가 Access JWT (15분, RSA PEM 서명) + Refresh Token (14일 HttpOnly Cookie) + Session DB row 발급.
- LDAP 로그인. bind 성공 시 Auto-provisioning (V001 users + V002 user_external_accounts UPSERT) + 동일 JWT/Session 흐름.
- LDAP unavailable. 503 + 다른 Provider 격리 (CONCERN-4 해소). Logout = sid 기반 revoke (`revoked_at`) → 즉시 모든 Access/Refresh 무효.

**spec 본문 규모.** FR 26건 / EC 24건 / NFR 7 카테고리 / API 7 endpoint / DM 마이그레이션 3종 (V004~V006). **monster PR scope 확정.**

## Brainstorming Check (/bts-spec Phase B 메인 세션 직접 sanity check)

✅ 통과 (14 차원 sanity check, gap 23건 발견 → 결정 7건 묶음 동의 후 spec 본문 반영 완료).

**주요 발견 + 반영.**
- 가정 미명시 3건. Spring Auth Server client 등록 모델 / JWT key 형식 / /oauth2/token 표준 endpoint 활성화 정책 — 추천대로 반영 (FR-09-18/19/20).
- 엣지 케이스 추가. EC-19~EC-24 (6건).
- API 추가. GET /api/v1/auth/providers (UI 로그인 폼 의존, §4.7).
- DM 보강. user_agent VARCHAR(512) → TEXT.
- NFR 보강. DB connection pool (Hikari maxPoolSize dev=10/prod=30) / 부하 한도 (300 동시 가정) / JWT key zero-downtime / CORS origin 환경별.
- 트랜잭션 경계 명확화 5건. SpringSecurityProviderAdapter / LocalProvider / LdapProvider / AuthController / ProvidersController.

**별도 후속 PR 결정.** GC job (refresh chain + Session row 각 30일 후 정리). 본 PR scope 안정 우선.

**스킬 우회 메모.** office-hours (design doc 흐름) + superpowers:brainstorming (HARD GATE no-implementation) 둘 다 본 작업의 implementation spec sanity check 과 mismatch 라 메인 세션이 직접 작성. spec 파일 끝의 「Brainstorming 발견」 섹션이 historical record.

## Plan

**For agentic workers.** REQUIRED SUB-SKILL = `superpowers:subagent-driven-development` via `/bts-impl`. Steps use checkbox tracking, TDD red→green→refactor 강제 (bts-impl SKILL.md `spec-compliance-verifier` 자동 검증).

**Goal.** SDD 19.5 Full scope — BTS 자체 JWT 발급자 + Session DB + Refresh Token + PAT + SecurityFilterChain 통합 + LDAP Auto-provisioning + CONCERN-1/4 해소.

**Architecture.** Spring Authorization Server (자체 RegisteredClient `bts-spa`) + RSA JWT 서명 + Order(1) Auth Server filter chain + Order(2) BTS API filter chain. ProviderRegistry priority routing (LDAP > Local > PAT). LDAP unavailable 시 Provider 격리 503.

**Tech Stack.** Kotlin 1.9 + Spring Boot 3.x + Spring Security 6.x + spring-security-oauth2-authorization-server + Argon2id (PR #6 활용) + jOOQ/JdbcTemplate + Flyway + Testcontainers (Postgres + OpenLDAP).

### Wave 1 — 의존성 0 (마이그레이션 + 도메인 엔티티 + 키 환경)

---

### Task 1. V004 sessions 마이그레이션

**메타**.
- agent. `db-engineer`
- files. [`backend/modules/identity-access/src/main/resources/db/migration/V004__sessions.sql`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/db/V004MigrationTest.kt`]
- depends-on. []
- 가시성 영향. 없음.
- 회귀 가드. phantom 엔티티 (PR #6) — V001 `users` FK 실재 확인 (`git grep "CREATE TABLE users"` 결과).

**RED**. `V004MigrationTest`. `fun "V004 creates sessions table with expected columns and indexes"()` — 컬럼 11개 + 인덱스 2개 (idx_sessions_user_active partial, idx_sessions_expires_active partial) assertEquals 검증. 실패. `table "sessions" does not exist`.

**GREEN**. `V004__sessions.sql`. spec §5 V004 그대로 — `CREATE TABLE sessions (id UUID PK, user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE, provider_id VARCHAR(64), device_fingerprint VARCHAR(128), ip_address INET, user_agent TEXT, created_at TIMESTAMPTZ DEFAULT NOW(), expires_at TIMESTAMPTZ NOT NULL, last_seen_at TIMESTAMPTZ DEFAULT NOW(), revoked_at TIMESTAMPTZ, revoke_reason VARCHAR(64));` + 2개 partial index.

**REFACTOR**. 각 컬럼 SQL COMMENT (SDD 19.5 명세 인용).

**검증**. `./gradlew :backend:identity-access:test --tests V004MigrationTest`

---

### Task 2. V005 refresh_tokens 마이그레이션

**메타**.
- agent. `db-engineer`
- files. [`backend/modules/identity-access/src/main/resources/db/migration/V005__refresh_tokens.sql`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/db/V005MigrationTest.kt`]
- depends-on. [1]   # sessions FK
- 가시성 영향. 없음.
- 회귀 가드. phantom 엔티티 (PR #6) — sessions FK 실재 확인.

**RED**. `V005MigrationTest`. `fun "V005 creates refresh_tokens with sessions FK + self FK replaced_by + token_hash UNIQUE"()`. 실패. `table "refresh_tokens" does not exist`.

**GREEN**. spec §5 V005 그대로.

**REFACTOR**. token_hash COMMENT 'SHA-256 hex 64자 — 평문 token 미저장'.

**검증**. `./gradlew :backend:identity-access:test --tests V005MigrationTest`

---

### Task 3. V006 personal_access_tokens 마이그레이션

**메타**.
- agent. `db-engineer`
- files. [`backend/modules/identity-access/src/main/resources/db/migration/V006__personal_access_tokens.sql`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/db/V006MigrationTest.kt`]
- depends-on. []   # users 만 의존. sessions/refresh 무관
- 가시성 영향. 없음.
- 회귀 가드. phantom 엔티티 — users FK.

**RED**. `V006MigrationTest`. 컬럼 11개 + `scopes JSONB DEFAULT '[]'` + `token_hash UNIQUE` + partial index `idx_pat_user_active`.

**GREEN**. spec §5 V006 그대로.

**REFACTOR**. scopes 컬럼 COMMENT 'JSON array of scope strings — SDD 19.2 supportedScopes 평가용'.

**검증**. `./gradlew :backend:identity-access:test --tests V006MigrationTest`

---

### Task 4. Session 도메인 엔티티 (data class)

**메타**.
- agent. `security-engineer`
- files. [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/session/Session.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/session/SessionTest.kt`]
- depends-on. []
- 가시성 영향. data class default public OK.
- 회귀 가드. 없음.

**RED**. `SessionTest`. `fun "Session constructor enforces non-null user_id provider_id expires_at"()` + `fun "Session#isActive returns false when revoked_at != null OR expires_at <= now"()`.

**GREEN**. `Session.kt`. `data class Session(val id: UUID, val userId: UUID, val providerId: String, val deviceFingerprint: String?, val ipAddress: String?, val userAgent: String?, val createdAt: Instant, val expiresAt: Instant, val lastSeenAt: Instant, val revokedAt: Instant?, val revokeReason: String?) { fun isActive(now: Instant): Boolean = revokedAt == null && expiresAt > now }`

**REFACTOR**. KDoc — SDD 19.5 §세션 관리 인용.

**검증**. `./gradlew :backend:identity-access:test --tests SessionTest`

---

### Task 5. RefreshToken 도메인 엔티티

**메타**.
- agent. `security-engineer`
- files. [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/session/RefreshToken.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/session/RefreshTokenTest.kt`]
- depends-on. []
- 가시성 영향. 없음.
- 회귀 가드. 없음.

**RED**. `RefreshTokenTest`. `fun "RefreshToken#isUsable returns false when used_at != null OR expires_at <= now"()` + `fun "tokenHash 는 64자 SHA-256 hex 강제"()`.

**GREEN**. `RefreshToken.kt`. data class — id, sessionId, tokenHash, issuedAt, expiresAt, usedAt, replacedBy + init `require(tokenHash.length == 64 && tokenHash.all { it.isDigit() || it in 'a'..'f' })`.

**REFACTOR**. KDoc + `companion object { const val HASH_LENGTH = 64 }` (internal companion — PR #7 learning #1 가시성 일관성).

**검증**. `./gradlew :backend:identity-access:test --tests RefreshTokenTest`

---

### Task 6. PersonalAccessToken 도메인 엔티티

**메타**.
- agent. `security-engineer`
- files. [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/pat/PersonalAccessToken.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/pat/PersonalAccessTokenTest.kt`]
- depends-on. []
- 가시성 영향. companion object internal (Token prefix 상수).
- 회귀 가드. PR #7 #1 companion 가시성 일관성.

**RED**. `PersonalAccessTokenTest`. `fun "PAT#isUsable + scopes 평가 (hasScope)"()` + `fun "token format 은 pat_ prefix + 48 char base62"()`.

**GREEN**. `PersonalAccessToken.kt`. data class + `fun hasScope(scope: String): Boolean = scopes.contains(scope) || scopes.contains("*")` + `internal companion object { const val TOKEN_PREFIX = "pat_"; const val TOKEN_BODY_LENGTH = 48 }`.

**REFACTOR**. KDoc.

**검증**. `./gradlew :backend:identity-access:test --tests PersonalAccessTokenTest`

---

### Task 7. JwtKeyProvider 인터페이스 + DevMemoryKeyProvider + PemFileKeyProvider + **profile 분기 @Bean (BLOCKER #6 해소)**

**메타**.
- agent. `security-engineer`
- files. [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/jwt/JwtKeyProvider.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/jwt/DevMemoryKeyProvider.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/jwt/PemFileKeyProvider.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/jwt/JwtKeyProviderConfig.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/jwt/JwtKeyProviderTest.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/jwt/JwtKeyProviderConfigTest.kt`]
- depends-on. []
- 가시성 영향. 인터페이스 + 두 구현 클래스 default public + `@Configuration` JwtKeyProviderConfig + `@Profile` 분기 명시.
- 회귀 가드. Claude 환각 (사전 함정 #3) — JCA `KeyPairGenerator.getInstance("RSA")` + Spring Security 6 의 `RSAKey.Builder` (com.nimbusds.jose.jwk) 정확 API. BLOCKER #6 prod fail-fast.

**RED**. (a) `DevMemoryKeyProvider startup RSA 2048 + kid="k-01"`. (b) `PemFileKeyProvider 가 env var path 누락 시 IllegalStateException + 메시지에 path 설정 안내` (EC-19). (c) **profile 분기 — @Profile("prod") 활성 + env var 누락 → ApplicationFailedToStartException** (EC-30). (d) @Profile("!prod") 활성 → DevMemoryKeyProvider Bean 만 생성.

**GREEN**. `interface JwtKeyProvider { val kid: String; val privateKey: RSAPrivateKey; val publicKey: RSAPublicKey }`. DevMemoryKeyProvider = startup `KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()`. PemFileKeyProvider = path 읽기 + PEMParser (BouncyCastle, optional). **JwtKeyProviderConfig**. `@Profile("prod") @Bean fun pemKeyProvider(@Value("\${bts.auth.jwt.private-key-pem-path}") path: String): JwtKeyProvider = PemFileKeyProvider(path).also { it.load() }; @Profile("!prod") @Bean fun memKeyProvider(): JwtKeyProvider = DevMemoryKeyProvider()`. prod env var 누락 시 Spring `@Value` 가 PlaceholderResolutionException → application 기동 실패 (fail-fast).

**REFACTOR**. KDoc + FR-09-19/32 spec 참조 + EC-19/30 명시.

**검증**. `./gradlew :backend:identity-access:test --tests JwtKeyProviderTest JwtKeyProviderConfigTest`

### Wave 2 — Wave 1 의존 (Repository + Provider + JwtIssuer + RegisteredClient)

---

### Task 8. SessionRepository (jdbc)

**메타**.
- agent. `security-engineer`
- files. [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/session/SessionRepository.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/session/SessionRepositoryTest.kt`]
- depends-on. [1, 4]
- 가시성 영향. interface + impl `@Repository` 부착 (`@Service` 룰 동일).
- 회귀 가드. PR #6 #1 @Service/@Repository + @Transactional 통합.

**RED**. `SessionRepositoryTest` (Testcontainers Postgres). `save / findById / findActiveByUserId / revokeAllByUserId / markRevoked`.

**GREEN**. interface + `JdbcSessionRepository` `@Repository` + JdbcTemplate + RowMapper. `markRevoked(id, reason)` 단일 UPDATE.

**REFACTOR**. @Transactional propagation REQUIRED 모든 변경 메서드. readOnly 조회 메서드.

**검증**. `./gradlew :backend:identity-access:test --tests SessionRepositoryTest`

---

### Task 9. RefreshTokenRepository

**메타**.
- agent. `security-engineer`
- files. [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/session/RefreshTokenRepository.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/session/RefreshTokenRepositoryTest.kt`]
- depends-on. [2, 5]
- 가시성 영향. `@Repository` 부착.
- 회귀 가드. PR #6 #1.

**RED**. `save / findByTokenHash / markUsedAndChain(oldId, newId)` + EC-23 concurrent 처리 (`UPDATE ... WHERE used_at IS NULL RETURNING` 으로 optimistic locking).

**GREEN**. `JdbcRefreshTokenRepository`. `markUsedAndChain` 가 single `UPDATE refresh_tokens SET used_at = NOW(), replaced_by = ? WHERE id = ? AND used_at IS NULL RETURNING id` — 0 row → null 반환 (race loser).

**REFACTOR**. KDoc EC-23 race 명시 + token_hash 로그 금지 명시.

**검증**. `./gradlew :backend:identity-access:test --tests RefreshTokenRepositoryTest`

---

### Task 10. PersonalAccessTokenRepository

**메타**.
- agent. `security-engineer`
- files. [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/pat/PersonalAccessTokenRepository.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/pat/PersonalAccessTokenRepositoryTest.kt`]
- depends-on. [3, 6]
- 가시성 영향. `@Repository`.
- 회귀 가드. PR #6 #1.

**RED**. `findByTokenHash / touchLastUsed(id) / revoke(id)`.

**GREEN**. JsonbType (PG JSONB) scopes 매핑 + JdbcTemplate.

**REFACTOR**. KDoc.

**검증**. `./gradlew :backend:identity-access:test --tests PersonalAccessTokenRepositoryTest`

---

### Task 11. JwtIssuer 서비스

**메타**.
- agent. `security-engineer`
- files. [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/jwt/JwtIssuer.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/jwt/JwtIssuerTest.kt`]
- depends-on. [7]
- 가시성 영향. `@Service`.
- 회귀 가드. PR #6 #1 / Claude 환각 #3.

**RED**. `JwtIssuerTest`. `issueAccessToken(Principal, sid: UUID)` 가 SDD 19.5 claims 6종 (sub, email, roles, sid, kid, mfa_verified=false) + exp = iat + 900. 검증 `verify(token)` round-trip.

**GREEN**. nimbus-jose-jwt `JWSSigner` + `JWTClaimsSet.Builder` + `SignedJWT.sign(signer)`. kid = JwtKeyProvider.kid.

**REFACTOR**. constant `ACCESS_TOKEN_TTL_SECONDS = 900`. `internal companion object`.

**검증**. `./gradlew :backend:identity-access:test --tests JwtIssuerTest`

---

### Task 12. JwtDecoder / JwtEncoder Bean (NimbusJwtEncoder/Decoder + JwkSource) — **재정의 (BLOCKER #4 해소)**

**메타**.
- agent. `security-engineer`
- files. [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/config/JwtConfig.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/config/JwtConfigTest.kt`]
- depends-on. [7]
- 가시성 영향. `@Configuration` + `@Bean` 3개 (JwkSource, JwtEncoder, JwtDecoder).
- 회귀 가드. Claude 환각 — Spring Security 6.x oauth2-resource-server 의 `NimbusJwtDecoder.withPublicKey(...).signatureAlgorithm(SignatureAlgorithm.RS256).build()` 정확 API. Spring Authorization Server 미도입 — `RegisteredClient` 등 미사용.

**RED**. JwtConfigTest. (a) `JwkSource<SecurityContext> Bean 이 JwtKeyProvider.publicKey + kid 로 JWK 한 개 제공`. (b) `JwtEncoder Bean (NimbusJwtEncoder) 가 JwkSource 활용 + RS256 서명 동작`. (c) `JwtDecoder Bean (NimbusJwtDecoder.withPublicKey) 가 JwtEncoder 발급 token round-trip 검증`.

**GREEN**. `@Configuration class JwtConfig { @Bean fun jwkSource(provider: JwtKeyProvider): JWKSource<SecurityContext> = ImmutableJWKSet(JWKSet(RSAKey.Builder(provider.publicKey).privateKey(provider.privateKey).keyID(provider.kid).build())); @Bean fun jwtEncoder(jwkSource: JWKSource<SecurityContext>): JwtEncoder = NimbusJwtEncoder(jwkSource); @Bean fun jwtDecoder(provider: JwtKeyProvider): JwtDecoder = NimbusJwtDecoder.withPublicKey(provider.publicKey).signatureAlgorithm(SignatureAlgorithm.RS256).build() }`.

**REFACTOR**. KDoc — FR-09-2 / FR-09-26 인용. Spring Authorization Server 미도입 명시 (3rd-party OAuth2 client 통합 후속 PR).

**검증**. `./gradlew :backend:identity-access:test --tests JwtConfigTest`

---

### Task 13. LocalProvider 구현

**메타**.
- agent. `security-engineer`
- files. [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/provider/local/LocalProvider.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/provider/local/LocalProviderTest.kt`]
- depends-on. [32, 33]   # UserRepository(32) + SPI priority(33) 의존 / LocalCredentialService PR #6 기존
- 가시성 영향. `@Service` + class implements `AuthenticationProvider` (SPI). priority=70.
- 회귀 가드. PR #6 #1 (@Service + @Transactional) / phantom 엔티티 #4 (LocalCredentialService 실재 `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/credential/LocalCredentialService.kt`).

**RED**. EC-01 (bad password 401) + EC-02 (no user 401 + dummy verify timing 일정화).

**GREEN**. `LocalProvider(localCredentialService, userRepository)`. authenticate(Credential.UsernamePassword) → userRepository.findByUsername → if null return dummy + Failure / else verifyForUser → Success or Failure.

**REFACTOR**. priority constant 70. KDoc — SDD 19.2 supports 메서드 명시.

**검증**. `./gradlew :backend:identity-access:test --tests LocalProviderTest`

---

### Task 14. PatProvider 구현 (검증만)

**메타**.
- agent. `security-engineer`
- files. [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/provider/pat/PatProvider.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/provider/pat/PatProviderTest.kt`]
- depends-on. [10]
- 가시성 영향. `@Service` + priority=60.
- 회귀 가드. PR #6 #1.

**RED**. EC-08 (PAT prefix 인식 실패 401) + EC-09 (revoked/만료 401) + EC-10 (scope 불일치 403).

**GREEN**. authenticate(Credential.Pat) → token_hash (SHA-256(plain)) → patRepo.findByTokenHash → check isUsable + scope.

**REFACTOR**. priority 60 < LocalProvider 70 < LdapProvider 80.

**검증**. `./gradlew :backend:identity-access:test --tests PatProviderTest`

---

### Task 15. LdapProvider Auto-provisioning 통합

**메타**.
- agent. `security-engineer`
- files. [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/provider/ldap/LdapProvider.kt` (수정), `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/provider/ldap/AutoProvisionService.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/provider/ldap/AutoProvisionServiceTest.kt`]
- depends-on. [32, 33]   # UserRepository(32) + SPI priority(33). PR #4 LdapProvider + ExternalAccountRepository 기존 활용
- 가시성 영향. AutoProvisionService `@Service` + LdapProvider 수정 (기존 @Component → **@Service 확정** — @Transactional 적용 시 PR #6 learning #1).
- 회귀 가드. PR #6 #1 (@Transactional 적용 시 @Service) / EC-11 동시 race UPSERT / EC-17 provisioning 실패 시 rollback. LdapProvider priority=80 (LDAP > Local > PAT).

**RED**. `AutoProvisionServiceTest`. US-04 (LDAP cn=bob 첫 로그인 → V001 users INSERT + V002 user_external_accounts INSERT 동일 @Transactional) + EC-11 race (UPSERT) + EC-17 rollback.

**GREEN**. AutoProvisionService.provision(externalAccount, ldapAttrs) — single @Transactional. UPSERT users (ON CONFLICT username DO UPDATE) + UPSERT user_external_accounts (ON CONFLICT (provider_id, external_id) DO UPDATE).

**REFACTOR**. LdapProvider.authenticate 가 bind 성공 후 AutoProvisionService.provision 호출.

**검증**. `./gradlew :backend:identity-access:test --tests AutoProvisionServiceTest LdapProviderIntegrationTest`

### Wave 3 — Wave 2 의존 (Service layer + SecurityConfig 재구성 + ADR 정정)

---

### Task 16. SessionService

**메타**.
- agent. `security-engineer`
- files. [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/session/SessionService.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/session/SessionServiceTest.kt`]
- depends-on. [8]
- 가시성 영향. `@Service` + @Transactional 다수.
- 회귀 가드. PR #6 #1.

**RED**. `create(userId, providerId, ipAddress, userAgent)` → device_fingerprint = SHA-256(ua+ip) hex 12자 (FR-09-23) + expires_at = now + 14d. `revoke(sid, reason)` + `revokeAll(userId)` + `findActiveBySid(sid)` + last_seen_at 갱신.

**GREEN**. SessionService(repo, clock). create 메서드 sid 생성 + UPSERT 아닌 INSERT. fingerprint = MessageDigest.

**REFACTOR**. internal const FP_LENGTH = 12 / SESSION_TTL_DAYS = 14.

**검증**. `./gradlew :backend:identity-access:test --tests SessionServiceTest`

---

### Task 17. RefreshTokenService (rotation)

**메타**.
- agent. `security-engineer`
- files. [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/session/RefreshTokenService.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/session/RefreshTokenServiceTest.kt`]
- depends-on. [9, 16]
- 가시성 영향. `@Service`.
- 회귀 가드. PR #6 #1 / EC-05 replay / EC-23 race.

**RED**. `issue(sessionId)` (token 생성 + INSERT) + `rotate(plain)` (옛 markUsedAndChain + 새 INSERT 단일 @Transactional + replay 감지) + `revokeSessionRefreshes(sid)`.

**GREEN**. SHA-256 hex token_hash. rotate. find by hash → if used_at != null → sessionService.revoke(sid, "REFRESH_REPLAY") + throw RefreshReuseException. else markUsedAndChain (race-safe).

**REFACTOR**. internal const REFRESH_TTL_DAYS = 14 / TOKEN_BYTES = 32.

**검증**. `./gradlew :backend:identity-access:test --tests RefreshTokenServiceTest`

---

### Task 18. PersonalAccessTokenService (검증만)

**메타**.
- agent. `security-engineer`
- files. [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/pat/PersonalAccessTokenService.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/pat/PersonalAccessTokenServiceTest.kt`]
- depends-on. [10]
- 가시성 영향. `@Service`.
- 회귀 가드. PR #6 #1.

**RED**. `verify(plain): Result<PersonalAccessToken>` — token_hash 조회 + isUsable + last_used_at 갱신 (단일 @Transactional).

**GREEN**. SHA-256(plain) → findByTokenHash → 검증 + touchLastUsed.

**REFACTOR**. 발급 endpoint 후속 PR 메모.

**검증**. `./gradlew :backend:identity-access:test --tests PersonalAccessTokenServiceTest`

---

### Task 19. SecurityConfig 재구성 (단일 chain + JwtAuthenticationConverter + permitAll) — **재작성 (BLOCKER #4/#5/주의 해소)**

**메타**.
- agent. `security-engineer`
- files. [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/config/SecurityConfig.kt` (수정), `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/config/SecurityConfigTest.kt`]
- depends-on. [12, 13, 14, 15, 34, 35]   # 신규 Task 34 (SidRevokeJwtConverter) + Task 35 (ProviderRegistry priority) 의존
- 가시성 영향. SpringSecurityProviderAdapter @Bean 명시 (`@Service` 부착 안 함 — stateless 위임만, 트랜잭션 경계는 위임 대상 시작).
- 회귀 가드. PR #6 #1 / Spring Auth Server 미도입 (BLOCKER #4 해소) / permitAll 경로 (FR-09-30).

**RED**. context loads + **단일 SecurityFilterChain Bean** + SpringSecurityProviderAdapter Bean 등록 + ProviderRegistry 가 Local/LDAP/PAT 3개 priority 정렬 (LDAP 80 / Local 70 / PAT 60) + permitAll 4개 경로 (`/api/v1/auth/login`, `/api/v1/auth/providers`, `/.well-known/jwks.json`, `/actuator/health`) + 그 외 `/api/v1/**` authenticated + jwt converter = SidRevokeJwtConverter.

**GREEN**. `@Bean fun securityFilterChain(http: HttpSecurity, sidRevokeConverter: SidRevokeJwtConverter): SecurityFilterChain = http.csrf { csrf -> csrf.csrfTokenRepository(csrfRepo); csrf.csrfTokenRequestHandler(CsrfTokenRequestAttributeHandler()) }.authorizeHttpRequests { auth -> auth.requestMatchers("/api/v1/auth/login", "/api/v1/auth/providers", "/.well-known/jwks.json", "/actuator/health").permitAll(); auth.requestMatchers("/api/v1/**").authenticated(); auth.anyRequest().permitAll() }.oauth2ResourceServer { rs -> rs.jwt { jwt -> jwt.jwtAuthenticationConverter(sidRevokeConverter) } }.build()`. application.yml issuer-uri = BTS 자체 `${bts.auth.issuer-uri:http://localhost:8080}`.

**REFACTOR**. KDoc — FR-09-26/27/30 인용. Spring Authorization Server 미도입 명시.

**검증**. `./gradlew :backend:identity-access:test --tests SecurityConfigTest`

---

### Task 20. csrf-cookie-mode ADR 본문 정정

**메타**.
- agent. `security-engineer`
- files. [`docs/decisions/2026-05-20-csrf-cookie-mode.md` (수정)]
- depends-on. []
- 가시성 영향. 없음 (문서).
- 회귀 가드. 없음.

**RED**. 본 ADR 의 "BTS는 JWT Bearer Token 기반 stateless 아키텍처이므로 세션을 유지하지 않는다." 표현 → "Access Token 검증은 stateless. Session/Refresh/PAT 은 revoke 보조 DB 사용. JWT Bearer Token 의 stateless 검증 본질은 유지." (FR-09-16 명시).

**GREEN**. 본문 patch + "## 보정 (2026-05-20 FR-AU-09 PR)" 단락 추가.

**REFACTOR**. 표 (정정 전 / 후) 형식.

**검증**. 수동 검토 + grep "stateless" 결과 정합 확인.

### Wave 4 — Wave 3 의존 (Controller + 설정 교체)

---

### Task 21. AuthController (login / logout / refresh)

**메타**.
- agent. `security-engineer`
- files. [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/web/AuthController.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/web/AuthControllerTest.kt`]
- depends-on. [16, 17, 19]
- 가시성 영향. `@RestController` (`@Service` 룰 무관). @Transactional 없음 (service layer 책임).
- 회귀 가드. CSRF (EC-13) / EC-18 logout idempotent.

**RED**. WebMvcTest slice. POST /api/v1/auth/login → 200 + access_token body + Set-Cookie refresh_token HttpOnly Secure SameSite=Strict Max-Age=1209600 + X-XSRF-TOKEN 헤더 검증 (CSRF). POST /api/v1/auth/logout → 204 + Cookie 만료. POST /api/v1/auth/refresh → 200 rotation. POST /api/v1/auth/logout/all → 204.

**GREEN**. AuthController(authMgr, sessionService, refreshTokenService, jwtIssuer). login 메서드 = SpringSecurityProviderAdapter.authenticate → sessionService.create → refreshTokenService.issue → jwtIssuer.issueAccessToken.

**REFACTOR**. Cookie Path=/api/v1/auth (logout 응답 만료와 일관) / KDoc.

**검증**. `./gradlew :backend:identity-access:test --tests AuthControllerTest`

---

### Task 22. ProvidersController

**메타**.
- agent. `security-engineer`
- files. [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/web/ProvidersController.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/web/ProvidersControllerTest.kt`]
- depends-on. [19]
- 가시성 영향. `@RestController` + `@Transactional(readOnly = true)`.
- 회귀 가드. PR #6 #1 @Transactional → @Service / @RestController 무관 (@RestController 가 Spring Bean 이므로 OK).

**RED**. GET /api/v1/auth/providers (public, no auth) → 200 + [{id, displayName, type, priority}, ...] priority 내림차순. PAT provider 제외.

**GREEN**. ProvidersController(providerRegistry). filter ProviderType != PAT + sortedByDescending { it.priority }.

**REFACTOR**. KDoc — FR-09-22.

**검증**. `./gradlew :backend:identity-access:test --tests ProvidersControllerTest`

---

### Task 23. JwksController

**메타**.
- agent. `security-engineer`
- files. [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/web/JwksController.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/web/JwksControllerTest.kt`]
- depends-on. [7, 19]
- 가시성 영향. `@RestController`.
- 회귀 가드. 없음.

**RED**. GET /.well-known/jwks.json (public) → 200 + {keys: [{kty: "RSA", use: "sig", kid: "k-01", alg: "RS256", n, e}]} + Cache-Control: public, max-age=86400.

**GREEN**. JwksController(jwtKeyProvider). JWKSet([RSAKey.Builder(publicKey).keyID(kid).build()]).toJSONObject().

**REFACTOR**. Cache-Control 헤더 명시.

**검증**. `./gradlew :backend:identity-access:test --tests JwksControllerTest`

---

### Task 24. WhoamiController PAT 인식 확장

**메타**.
- agent. `security-engineer`
- files. [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/web/WhoamiController.kt` (수정), `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/web/WhoamiControllerTest.kt` (수정)]
- depends-on. [14, 18, 19]
- 가시성 영향. 없음.
- 회귀 가드. 없음.

**RED**. Authorization Bearer pat_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx → 200 + Principal + last_used_at 갱신 확인 + AuthAuditLog PAT_USED.

**GREEN**. Authorization 헤더 prefix 인식 — pat_ 인 경우 PatProvider 흐름, JWT 인 경우 기존 JWT 검증 흐름.

**REFACTOR**. AuthAuditLog 이벤트 enum 확장.

**검증**. `./gradlew :backend:identity-access:test --tests WhoamiControllerTest`

---

### Task 25. CORS 설정 + application.yml issuer-uri 교체

**메타**.
- agent. `security-engineer`
- files. [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/config/CorsConfig.kt`, `backend/modules/identity-access/src/main/resources/application.yml` (수정), `backend/modules/identity-access/src/main/resources/application-prod.yml` (신규), `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/config/CorsConfigTest.kt`]
- depends-on. [19]
- 가시성 영향. `@Configuration`.
- 회귀 가드. FR-09-17 (issuer-uri Keycloak 제거) / FR-09-24 (CORS 환경별).

**RED**. CorsConfigTest. dev profile = http://localhost:5173 allowed / prod profile = https://bts.example.com (env var BTS_FRONTEND_ORIGIN override).

**GREEN**. CorsConfigurationSource Bean + application.yml `bts.security.cors.allowed-origins`. application.yml issuer-uri 제거 (oauth2ResourceServer.jwt.jwk-set-uri = `${bts.auth.issuer-uri:http://localhost:8080}/.well-known/jwks.json`).

**REFACTOR**. dev-key.pem 은 generation 책임이 DevMemoryKeyProvider → application.yml 의 PEM path env var 는 prod only.

**검증**. `./gradlew :backend:identity-access:test --tests CorsConfigTest IssuerUriEnvOverrideTest`

### Wave 5 — Wave 4 의존 (통합 테스트 + ArchUnit + ADR 3건)

---

### Task 26. 통합 테스트 — Local 로그인 + Refresh rotation + Logout + Replay

**메타**.
- agent. `security-engineer`
- files. [`backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/integration/LocalAuthFlowIntegrationTest.kt`]
- depends-on. [21, 25]
- 가시성 영향. `@SpringBootTest` + Testcontainers Postgres.
- 회귀 가드. CONCERN-1 트랜잭션 경계.

**RED**. US-01 / US-02 / US-03 / US-05 / US-06 / US-07 / US-08 e2e + 응답 시간 ±50ms (EC-02 dummy verify 타이밍).

**GREEN**. SpringBootTest + RestAssured/MockMvc + Testcontainers Postgres.

**REFACTOR**. CSRF token 자동 획득 헬퍼.

**검증**. `./gradlew :backend:identity-access:test --tests LocalAuthFlowIntegrationTest`

---

### Task 27. 통합 테스트 — LDAP Auto-provisioning + LDAP unavailable (CONCERN-4)

**메타**.
- agent. `security-engineer`
- files. [`backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/integration/LdapAuthFlowIntegrationTest.kt`]
- depends-on. [15, 21, 25]
- 가시성 영향. `@SpringBootTest` + Testcontainers Postgres + OpenLDAP.
- 회귀 가드. CONCERN-4 LDAP stop / EC-11 race / EC-17 rollback.

**RED**. US-04 (Auto-provisioning) + US-10 (LDAP stop → 503 + Local 격리 정상) + EC-11 동시 첫 로그인 race + EC-17 user INSERT 실패 시 LDAP bind 무효.

**GREEN**. ldapContainer.stop() 후 LDAP login 503 + 동일 시점 Local login 200.

**REFACTOR**. CONCERN-4 검증 시나리오 KDoc.

**검증**. `./gradlew :backend:identity-access:test --tests LdapAuthFlowIntegrationTest`

---

### Task 28. 통합 테스트 — PAT 검증 + Concurrent Refresh + CSRF

**메타**.
- agent. `security-engineer`
- files. [`backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/integration/PatAndConcurrencyIntegrationTest.kt`]
- depends-on. [24, 25]
- 가시성 영향. `@SpringBootTest` + Testcontainers Postgres.
- 회귀 가드. EC-23 concurrent rotation / EC-13 CSRF.

**RED**. US-09 (PAT 검증) + EC-23 concurrent refresh (CompletableFuture 두 요청) — 한쪽 401 / 한쪽 200 + EC-13 CSRF 누락 403.

**GREEN**. SpringBootTest 동시 호출 (ExecutorService).

**REFACTOR**. concurrent test KDoc.

**검증**. `./gradlew :backend:identity-access:test --tests PatAndConcurrencyIntegrationTest`

---

### Task 29. ArchUnit 룰 — @Transactional 메서드 보유 클래스 @Service 강제

**메타**.
- agent. `security-engineer`
- files. [`backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/architecture/TransactionalServiceArchTest.kt`]
- depends-on. []
- 가시성 영향. 없음.
- 회귀 가드. PR #6 #1 (@Service 부착) 회귀 가드 — 핵심.

**RED**. ArchUnit `classes().that().haveMethodWithAnnotation(@Transactional).should().beAnnotatedWith(@Service or @Component or @Repository)`.

**GREEN**. 본 PR 의 모든 @Transactional 보유 클래스가 @Service/@Component/@Repository 부착됨을 정적 검증.

**REFACTOR**. KDoc — PR #6 learning #1 인용.

**검증**. `./gradlew :backend:identity-access:test --tests TransactionalServiceArchTest`

---

### Task 30. 신규 ADR 3건 작성

**메타**.
- agent. `security-engineer`
- files. [`docs/decisions/2026-05-20-jwt-issuer-strategy.md`, `docs/decisions/2026-05-20-session-pat-schema.md`, `docs/decisions/2026-05-20-jwt-key-rotation-policy.md`]
- depends-on. []
- 가시성 영향. 없음 (문서).
- 회귀 가드. 없음.

**RED**. 3건 ADR 각자 형식 (컨텍스트 / 결정 / 대안 / 결과 / 영향) 검토.

**GREEN**. ADR 1 = BTS 자체 JWT 발급자 + Spring Authorization Server (Keycloak OIDC IdP 역할 재정의). ADR 2 = V004/V005/V006 스키마 + 1 로그인 = 1 sessions row + refresh rotation chain + PAT scope 평가 모델 + GC 정책 (후속 PR 메모). ADR 3 = kid 관리 + 본 PR 키 1개 + DevMemoryKeyProvider / PemFileKeyProvider + rotation 메커니즘 schema 후속.

**REFACTOR**. plan 의 ## 도메인 정리 cross-link.

**검증**. 수동 검토 + grep "jwt-issuer-strategy" 정합 확인.

---

### Wave 0 — 의존성 0 (build 의존성)

---

### Task 31. build.gradle.kts 의존성 추가 — **신규 (주의 보강)**

**메타**.
- agent. `backend-engineer`
- files. [`backend/modules/identity-access/build.gradle.kts` (수정), `backend/build.gradle.kts` (root, 필요 시 BOM)]
- depends-on. []
- 가시성 영향. 없음.
- 회귀 가드. 환경 함정 — `bootRun` 시 의존성 누락 fail-fast.

**RED**. Gradle dependency report — `com.nimbusds:nimbus-jose-jwt` (transitive 가능 but 명시 추가), `org.bouncycastle:bcprov-jdk18on:1.78` (PEM 파싱), `org.bouncycastle:bcpkix-jdk18on:1.78`. `org.springframework.security:spring-security-oauth2-resource-server` 기존 있는지 확인 (PR #2 도입).

**GREEN**. `implementation("com.nimbusds:nimbus-jose-jwt:9.40")` + `implementation("org.bouncycastle:bcpkix-jdk18on:1.78")` 등 build.gradle.kts 추가. version catalog 활용 권장.

**REFACTOR**. KDoc 주석 — "FR-AU-09 JWT 자체 발급 + PEM 파싱" 사유 명시.

**검증**. `./gradlew :backend:identity-access:dependencies | grep -E "nimbus|bouncycastle"`

---

### Task 32. UserRepository (jdbc) — **신규 (BLOCKER 주의 보강)**

**메타**.
- agent. `security-engineer`
- files. [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/user/User.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/user/UserRepository.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/user/UserRepositoryTest.kt`]
- depends-on. []   # V001 users 기존 마이그레이션
- 가시성 영향. `@Repository` (PR #6 learning #1).
- 회귀 가드. phantom 엔티티 — V001 `users` 테이블 실재 확인.

**RED**. `UserRepositoryTest` (Testcontainers Postgres). `findByUsername(username): User?` + `findById(id): User?` + `provisionFromExternal(provider_id, external_id, attrs)` UPSERT (EC-11 race).

**GREEN**. `data class User(id, username, email, displayName, status, createdAt)` + `interface UserRepository` + `JdbcUserRepository` @Repository + JdbcTemplate. UPSERT = `INSERT INTO users (...) ON CONFLICT (username) DO UPDATE SET email=EXCLUDED.email, display_name=EXCLUDED.display_name RETURNING *`.

**REFACTOR**. KDoc + FR-09-29 인용.

**검증**. `./gradlew :backend:identity-access:test --tests UserRepositoryTest`

---

### Task 33. AuthenticationProvider SPI + ProviderType priority 필드 — **신규 (BLOCKER 주의 보강)**

**메타**.
- agent. `security-engineer`
- files. [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/spi/AuthenticationProvider.kt` (수정), `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/spi/ProviderType.kt` (수정), `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/spi/AuthenticationProviderPriorityTest.kt`]
- depends-on. []
- 가시성 영향. interface SPI 변경 — breaking change. PR #3/#4/PR #6 의 기존 LdapProvider / FakeLocalProvider / FakePatProvider 모두 priority 필드 추가 필요. **하위 호환 깨짐 — 본 PR 안에서 일괄 정정**.
- 회귀 가드. PR #7 #1 (companion 가시성) — `internal companion object` 안 priority 상수.

**RED**. `AuthenticationProviderPriorityTest`. (a) interface 에 `val priority: Int` 추가됨. (b) ProviderType enum 의 각 type 이 default priority 상수 제공 (LDAP=80, LOCAL=70, PAT=60, OIDC=50, SAML=40, OAUTH=30). (c) PR #4 LdapProvider / 기존 fake 들 모두 priority 반환.

**GREEN**. `interface AuthenticationProvider { ...; val priority: Int }`. enum ProviderType { LDAP, LOCAL, PAT, OIDC, SAML, OAUTH; internal companion object { fun defaultPriority(type: ProviderType): Int = when (type) { LDAP -> 80; LOCAL -> 70; PAT -> 60; OIDC -> 50; SAML -> 40; OAUTH -> 30 } } }`. 기존 LdapProvider / fake 들 priority 추가 (override val priority = ProviderType.defaultPriority(LDAP)).

**REFACTOR**. KDoc + SDD 19.2 priority 명세 인용 + FR-09-28.

**검증**. `./gradlew :backend:identity-access:test --tests AuthenticationProviderPriorityTest`

### Wave 추가 task (의존성 명시 wave 그룹은 ## Plan 메타 참조)

---

### Task 34. SidRevokeJwtConverter (sid 기반 revoke filter) — **신규 (BLOCKER 주의 보강 — FR-09-11 누락 회로 해소)**

**메타**.
- agent. `security-engineer`
- files. [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/jwt/SidRevokeJwtConverter.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/jwt/SidRevokeJwtConverterTest.kt`]
- depends-on. [16]   # SessionService 의존
- 가시성 영향. `@Component` (Spring Bean — Converter<Jwt, AbstractAuthenticationToken>).
- 회귀 가드. PR #6 #1 (@Component 부착) / EC-29 캐시 (Caffeine 5s TTL).

**RED**. `SidRevokeJwtConverterTest`. (a) revoked sid 의 JWT → `BadCredentialsException` 또는 throws Spring `InsufficientAuthenticationException`. (b) active sid 의 JWT → JwtAuthenticationToken 정상 반환. (c) 같은 sid 5초 안에 두 번 호출 시 DB 1회만 조회 (캐시 검증).

**GREEN**. `class SidRevokeJwtConverter(sessionService: SessionService, clock: Clock) : Converter<Jwt, AbstractAuthenticationToken> { private val cache: Cache<UUID, Boolean> = Caffeine.newBuilder().expireAfterWrite(5, SECONDS).maximumSize(10_000).build(); override fun convert(jwt: Jwt): AbstractAuthenticationToken { val sid = UUID.fromString(jwt.getClaimAsString("sid")); val active = cache.get(sid) { sessionService.findActiveBySid(sid)?.isActive(clock.instant()) ?: false }; if (!active) throw BadCredentialsException("session_revoked"); val auth = JwtAuthenticationConverter().convert(jwt); return auth!! } }`.

**REFACTOR**. KDoc + FR-09-11 + EC-12 + EC-29 인용. cache size + TTL 상수 internal.

**검증**. `./gradlew :backend:identity-access:test --tests SidRevokeJwtConverterTest`

---

### Task 35. ProviderRegistry priority 정렬 — **신규 (BLOCKER 주의 보강 — FR-09-25 회로 해소)**

**메타**.
- agent. `security-engineer`
- files. [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/spi/ProviderRegistry.kt` (수정), `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/spi/ProviderRegistryPriorityTest.kt`]
- depends-on. [33]   # SPI priority 필드 의존
- 가시성 영향. 기존 `@Component` 유지. findFor 로직 변경 — breaking 가능성 (기존 PR #3/#4 호출자 영향). 본 PR 안에서 일괄 정정.
- 회귀 가드. EC-24 priority 정렬 / EC-25 동률.

**RED**. `ProviderRegistryPriorityTest`. (a) `findFor(Credential.LdapBind)` → LdapProvider (priority 80). (b) `findFor(Credential.UsernamePassword)` → LocalProvider (priority 70). (c) `findFor(Credential.Pat)` → PatProvider (priority 60). (d) 같은 type 의 다른 priority Provider 두 개 등록 시 높은 priority 선택. (e) PR #3 의 기존 ProviderRegistryTest 회귀 통과 확인.

**GREEN**. `class ProviderRegistry(providers: List<AuthenticationProvider>) { fun findFor(credential: Credential): AuthenticationProvider? = providers.sortedByDescending { it.priority }.firstOrNull { it.supports(credential) } }`.

**REFACTOR**. KDoc + FR-09-28 인용.

**검증**. `./gradlew :backend:identity-access:test --tests ProviderRegistryPriorityTest ProviderRegistryTest`

---

### Task 36. AuthAuditLog + AuthEventType enum + AuthAuditLogService — **신규 (BLOCKER 주의 보강 — FR-09-31)**

**메타**.
- agent. `security-engineer`
- files. [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/audit/AuthEventType.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/audit/AuthAuditLog.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/audit/AuthAuditLogService.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/audit/InMemoryAuthAuditLogService.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/audit/AuthAuditLogServiceTest.kt`]
- depends-on. []
- 가시성 영향. enum / data class / interface 공개. InMemoryAuthAuditLogService `@Service`.
- 회귀 가드. PR #6 #1 / log 정책 (sensitive 단어 미출현).

**RED**. (a) enum 9종 (LOGIN_SUCCESS / LOGIN_FAILURE / LOGOUT / LOGOUT_ALL_DEVICES / TOKEN_REFRESHED / SUSPICIOUS_REFRESH_REPLAY / USER_PROVISIONED / PAT_USED / LDAP_UNAVAILABLE). (b) AuthAuditLog data class (userId / eventType / providerId / ipAddress / userAgent / deviceFingerprint / metadata / createdAt). (c) Service interface `record(event)` + InMemoryAuthAuditLogService 가 thread-safe MutableList 보관. (d) 본 PR 은 in-memory + log file 만, DB persistence + 월 단위 파티션 (SDD 19.9) 은 후속 PR.

**GREEN**. data class + enum + interface + simple in-memory. log file = logback 의 audit-specific logger.

**REFACTOR**. KDoc + FR-09-31 + SDD 19.9 후속 PR 메모.

**검증**. `./gradlew :backend:identity-access:test --tests AuthAuditLogServiceTest`

---

### Task 37. CorsConfig — **신규 (Task 25 에서 CORS 분리, 주의 보강)**

**메타**.
- agent. `security-engineer`
- files. [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/config/CorsConfig.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/config/CorsConfigTest.kt`]
- depends-on. []
- 가시성 영향. `@Configuration` + `@Bean CorsConfigurationSource`.
- 회귀 가드. FR-09-24 (CORS 환경별).

**RED**. CorsConfigTest. dev profile = `http://localhost:5173` allowed origin / prod profile = `https://bts.example.com` (env `BTS_FRONTEND_ORIGIN` override). HTTP methods (GET/POST/PUT/DELETE/OPTIONS) + headers (Authorization, X-XSRF-TOKEN, Content-Type) + credentials true (Cookie 전달).

**GREEN**. `@Bean fun corsConfigurationSource(@Value("\${bts.security.cors.allowed-origins}") origins: List<String>): CorsConfigurationSource { val cfg = CorsConfiguration().apply { allowedOrigins = origins; allowedMethods = listOf("GET","POST","PUT","DELETE","OPTIONS"); allowedHeaders = listOf("Authorization","X-XSRF-TOKEN","Content-Type"); allowCredentials = true }; return UrlBasedCorsConfigurationSource().apply { registerCorsConfiguration("/api/v1/**", cfg) } }`.

**REFACTOR**. application.yml `bts.security.cors.allowed-origins` 환경 변수 명시. application-prod.yml 별도 origins.

**검증**. `./gradlew :backend:identity-access:test --tests CorsConfigTest`

---

## Plan 메타 (BLOCKER 5건 보강 후 갱신)

- **task 수**. **37** (기존 30 + 신규 7. Task 31~37 = build 의존성 / UserRepository / SPI priority / SidRevokeJwtConverter / ProviderRegistry priority / AuthAuditLog / CorsConfig)
- **wave 매핑 (실제)**. 본문의 ### Wave N 섹션 헤더는 historical record. **실제 wave 는 본 표 기준** (depends-on 그래프).

| wave | task |
|---|---|
| Wave 0 | 31 (build) |
| Wave 1 | 1 (V004), 3 (V006), 4 (Session entity), 5 (RefreshToken entity), 6 (PAT entity), 7 (JwtKeyProvider + profile 분기), 20 (csrf ADR 정정), 30 (ADR 3건), 32 (UserRepository), 33 (SPI priority), 36 (AuthAuditLog), 37 (CorsConfig) |
| Wave 2 | 2 (V005, depends [1]), 8 (SessionRepo, depends [1,4]), 10 (PatRepo, depends [3,6]), 11 (JwtIssuer, depends [7]), 12 (JwtDecoder/Encoder Bean, depends [7]), 13 (LocalProvider, depends [32,33]), 14 (PatProvider, depends [10]), 15 (LdapProvider Auto-provision, depends [32,33]), 25 (application.yml issuer-uri 교체), 35 (ProviderRegistry priority, depends [33]) |
| Wave 3 | 9 (RefreshTokenRepo, depends [2,5]), 16 (SessionService, depends [8]), 18 (PatService, depends [10]), 29 (ArchUnit) |
| Wave 4 | 17 (RefreshTokenService, depends [9,16]), 19 (SecurityConfig, depends [12,13,14,15,34,35]), 34 (SidRevokeJwtConverter, depends [16]) |
| Wave 5 | 21 (AuthController, depends [16,17,19]), 22 (ProvidersController, depends [19]), 23 (JwksController, depends [7,19]), 24 (WhoamiController PAT 확장, depends [14,18,19]) |
| Wave 6 | 26 (Local integration), 27 (LDAP integration), 28 (PAT + Concurrency integration) |

- **longest path 실측**. 6 (Wave 0 → 1 → 2 → 3 → 4 → 5 → 6). 본 PR scope monster + sub-decision 30+ 라 합리적 path 길이.
- **예상 직렬 시간**. 약 110분 (task × 3분 평균).
- **예상 wave 시간**. 약 30~40분 (6 wave × 5~6분).
- **TDD 강제**. yes (`spec-compliance-verifier` 가 git log 의 `test:` ↔ `feat:` 순서 검증).
- **병렬 dispatch**. yes (bts-impl wave 계산 — depends-on + files 교집합 ∅).
- **agent 분포**. db-engineer 3 task (V004/V005/V006) + backend-engineer 1 task (Task 31 build) + security-engineer 33 task.
- **회귀 가드 매핑**. PR #6 #1 (@Service + @Transactional) = Task 8/9/10/11/13/14/15/16/17/18/29/32/34/36. PR #7 #1 (companion 가시성) = Task 5/6/7/11/16/17/33. phantom 엔티티 = Task 1/2/3/13/32. Claude 환각 = Task 7/11/12.
- **CONCERN 해소 매핑**. CONCERN-1 (트랜잭션 경계 + 통합 단언) = Task 8~18 + 26 + 29. CONCERN-4 (LDAP stop) = Task 15 + 27.
- **BLOCKER 5건 해소 매핑** (외부 sub-agent 결과 → 본 plan 반영).
  - #1 (트랜잭션 전파 통합 단언). Task 26 RED 본문에 TransactionSynchronizationManager 단언 추가 (spec §9 CONCERN-1 본문) + spec §3 자기모순 해소.
  - #2 (Wave 1 grouping). Task 2 Wave 2 이동 (wave 매핑 표 명시).
  - #3 (longest path 메타). 실측 6 명시.
  - #4 (Spring Auth Server password grant). Task 12 재정의 + Task 19 단일 chain + FR-09-2/18/20/26 spec 정정 (Spring Auth Server 미도입 결정).
  - #5 (/oauth2/token 활성화). Task 12 재정의 자동 해소 (Spring Auth Server 미도입 → endpoint 미존재).
  - #6 (JWT key prod fail-fast). Task 7 profile 분기 + FR-09-32.
- **주의 14건 해소 매핑** (외부 sub-agent 주의 → 본 plan 반영). UserRepository = Task 32. SPI priority = Task 33. ProviderRegistry priority = Task 35. sid revoke filter = Task 34. AuthAuditLog enum = Task 36. permitAll = Task 19 본문. Cookie Path 통일 = Task 21 본문 + spec §4.1 정정. LdapProvider @Service = Task 15 본문. CorsConfig 분리 = Task 37. build 의존성 = Task 31. 통합 테스트 분해는 Task 26 RED 본문에 시나리오 별 명시 (분해는 implementer 위임 — sub-agent 가 wave 단위 처리). 로그 자동 검증 = FR-09-33 + 통합 테스트 (Task 26~28) 의 LogAssertionsTest 참조. PAT prefix hash = EC-26 + Task 14 GREEN 본문 정정. PAT 무기한 = EC-27 (정책 결정 — 본 PR 무기한 허용).
- **본 PR 미포함 (후속 PR)**. 2FA / AuthRouter / PAT 발급/조회/revoke endpoint / Session row + refresh chain GC job / 로그인 폼 UI D6 / Playwright E2E D7 / sync-obsidian.ts 자동화 확장 / Spring Authorization Server 본격 도입 (3rd-party OAuth2 client 통합 PR) / AuthAuditLog DB persistence + 월 단위 파티션 (SDD 19.9 full).

## 리뷰 결과 — engineering (외부 sub-agent fresh 시각, 2026-05-20)

### 1. 트랜잭션 경계
- ✅ LocalCredentialService(PR #6) / ExternalAccountRepository(PR #4) 기존 @Service/@Repository 부착. plan Task 8~18 모두 @Service 명시. Task 29 ArchUnit 룰이 회귀 가드.
- ✅ SpringSecurityProviderAdapter 의 @Bean 등록 (stateless 위임) + 트랜잭션 경계 위임 대상 시작 결정 spec §3 + plan Task 19 일관.
- ⚠️ LdapProvider 현재 @Component(PR #4). plan Task 15 "기존 @Component → @Service 검토" 모호.
- 🛑 **BLOCKER**. Task 13 LocalProvider depends-on [] 인데 LocalCredentialService(PR #6) 호출 흐름의 트랜잭션 컨텍스트 전파(REQUIRED) 통합 검증 명시적이지 않음. CONCERN-1 의 프록시 trace 가 단위 테스트로만 분산. SecurityFilterChain → Adapter → Provider → CredentialService 전 stack 트랜잭션 컨텍스트 통합 단언 필요.
- ⚠️ spec §3 의 "authenticate(credential) (Spring 어댑터) @Transactional propagation=REQUIRES_NEW" vs "SpringSecurityProviderAdapter @Transactional 부착 안 함" **자기모순**. Provider 메서드의 propagation 인지 어댑터인지 ambiguous.
- ⚠️ Task 15 AutoProvisionService depends-on [] — 실제 V001/V002 + ExternalAccountRepository(PR #4) 의존성 명시 누락.

### 2. wave 그래프
- ✅ 같은 wave 내 files 교집합 ∅ 표면 검토상 OK.
- 🛑 **BLOCKER**. Wave 1 grouping 오류 — Task 2 (V005) depends-on [1] 인데 Wave 1 (의존성 0) 에 배치. Wave 2 로 이동 필요. PR #6 learning #2 회귀 위험.
- 🛑 **BLOCKER**. longest path 메타 부정확. 실측 chain. `T7 → T11 → T12 → T19 → T21 → T26` (depth 6) 또는 `T1 → T2 → T9 → T17 → T21 → T26` (depth 6). plan 메타가 "longest path = 4" 단언 vs 실제 6. wave 수 5 와도 불일치.
- ⚠️ Task 13 LocalProvider depends-on [] 의심 — LocalCredentialService(PR #6) + UserRepository(미존재) 의존. **UserRepository 도입 task 가 plan 전체에 누락**.
- ⚠️ Task 15 LdapProvider 수정 + AutoProvisionService depends-on [] — PR #4 의존 명시 누락.
- ⚠️ Wave 5 의 Task 29/30 "wave 1~4 와 병렬 가능" 메타 vs Wave 5 배치 불일치 — wave 그룹 정의 헷갈림.

### 3. 마이그레이션 의존성
- ✅ V001/V002/V003 기존 정합. V004 sessions.user_id FK / V005 refresh_tokens.session_id FK ON DELETE CASCADE 정확. V006 PAT 무관.
- ✅ partial index `WHERE revoked_at IS NULL` 설계 적합.
- ⚠️ V005 `idx_refresh_session` 의 `findByTokenHash` 중복 가능성 없음 (UNIQUE 가 cover).
- ⚠️ V006 expires_at nullable — PAT 무기한 가능성 정책 명시 누락 (DEVELOPMENT.md §1 token TTL 확인 필요).
- ⚠️ PAT prefix hash 입력 정책 명시 누락 (Task 14 GREEN `SHA-256(plain)` 만, `pat_` prefix 포함 여부 모호).

### 4. 보안
- ✅ Refresh Token replay (Task 17) — race-safe optimistic locking + session 전체 revoke 정확.
- ✅ Argon2 dummy verify (US-03 / EC-02) — PR #6 Argon2Params.DUMMY_HASH 활용 timing attack 방어.
- ✅ CSRF Cookie 모드 + X-XSRF-TOKEN 헤더 검증 — 기존 ADR + Task 20 정정.
- 🛑 **BLOCKER**. JWT signing key prod fail-fast 부족. Task 25 본문 "dev-key.pem 은 DevMemoryKeyProvider 생성 → PEM path env var 는 prod only" — `@Profile("prod") @Bean fun jwtKeyProvider(): PemFileKeyProvider` + `@Profile("!prod") @Bean fun jwtKeyProvider(): DevMemoryKeyProvider` 같은 명시적 분기 task 없음. prod env var 누락 시 silent dev fallback → 매 재시작마다 키 변경 위험.
- 🛑 **BLOCKER**. Spring Authorization Server 1.3.x 의 `password` grant **deprecated / 미지원**. Task 12 GREEN `.authorizationGrantTypes { it.add(PASSWORD); it.add(REFRESH_TOKEN) }` — OAuth2.1 spec 도 password grant 제거. `AuthorizationGrantType.PASSWORD` 가 컴파일 안 되거나 deprecated. Claude 환각 함정 (사전 등록 #3). plan "/api/v1/auth/login = password grant wrapper" 가정이 **잘못**. → BTS 자체 token 발급 (Spring Authorization Server 의 RegisteredClient 우회 + 자체 JwtEncoder + JwtIssuer 직접 활용) 으로 재설계 필요.
- 🛑 **BLOCKER**. `/oauth2/token` 표준 endpoint 활성화 vs 미사용 충돌. password 미지원이면 client_credentials / authorization_code 만 발급 가능. 본 PR scope 에서 사용처 없음 → **공격 표면 확대**. 비활성화 또는 후속 PR 분리.
- ⚠️ Auto-provisioning EC-17 rollback — plan Task 15 RED "EC-17 rollback" 만, "LDAP bind 자체 무효 처리" 검증 모호.
- ⚠️ 로그 정책 자동 검증 task 누락 (logback test appender + regex 미명시).
- ⚠️ Refresh Cookie Path 불일치. spec §4.1 `Path=/api/v1/auth/refresh` vs plan Task 21 REFACTOR `Path=/api/v1/auth`. logout 응답 만료시 Path 가 발급 시와 일치해야 브라우저가 삭제 (RFC 6265).

### 5. TDD task 명확도
- ✅ 대부분 task RED → GREEN → REFACTOR 구조 명확. gradle test path 정확.
- ✅ Task 17 RED 명세 race-safe + replay 감지 구체적.
- ⚠️ Task 26~28 통합 테스트 RED phase 가 broad (7개 시나리오/task). 한 task = 한 시나리오로 분해 권장.
- ⚠️ Task 7 GREEN 의 `nimbus-jose-jwt` / `BouncyCastle PEMParser` 의존성 — **build.gradle.kts 의존성 추가 task 누락**.
- ⚠️ Task 8 RED "5 함수 동작" 압축 — 함수별 실패 메시지 구체 부족.
- ⚠️ Task 11 `JwtIssuerTest` RED "verify(token) round-trip" — verify 가 JwtIssuer 책임인지 Spring Authorization Server JwtDecoder 책임인지 모호.

### 6. spec ↔ plan 정합
- ✅ FR 26건 대부분 task 매핑. 마이그레이션 FK 순서 / partial index / Argon2 dummy verify / CSRF 등 핵심 골격 견고.
- ⚠️ FR-09-11 (sid 기반 revoke). Access Token JWT 검증 시점에 sid → sessions DB 조회 → revoked_at 검사 로직 task 누락. **별도 OncePerRequestFilter 또는 JwtAuthenticationConverter 커스텀 task 미존재** — revoke 메커니즘 핵심 회로 missing.
- ⚠️ FR-09-15 mfa_verified=false 더미 — Task 11 claims 6종에 포함되지만 RED 단언 누락.
- ⚠️ EC-12 Session expires_at < now lazy revoke — FR-09-11 연장으로 task 누락.
- ⚠️ EC-04 Refresh 만료 — Task 17 RED 본문에 만료 단언 누락.
- ⚠️ FR-09-22 + API §4.7 — Task 22 cover but SecurityConfig (Task 19) 에서 `/api/v1/auth/providers` / `/.well-known/jwks.json` permitAll 명시 누락.
- ⚠️ AuthAuditLog 8개 이벤트 enum 정의 task 누락 — Task 24 REFACTOR "AuthAuditLog 이벤트 enum 확장" 한 줄만, 신규 클래스 task 없음.
- ⚠️ FR-09-25 ProviderRegistry priority 정렬 — **현재 ProviderRegistry.findFor 가 `firstOrNull { supports }` 만, priority 무시. AuthenticationProvider SPI / ProviderType enum 에 priority 필드 자체가 없음**. priority 필드 도입 + 정렬 로직 변경 task 명시 누락.
- ✅ Task 메타 5필드 (agent / files / depends-on / 가시성 영향 / 회귀 가드) 30 task 전부 준수.

### 종합 판정

- 🛑 BLOCKER. **6건**
  1. (§1) Adapter → Provider → CredentialService 트랜잭션 전파 통합 검증 모호 + spec §3 propagation 자기모순.
  2. (§2) Wave 1 grouping 오류 (Task 2 depends-on [1]).
  3. (§2) longest path 메타 부정확 (4 vs 실측 6).
  4. (§4) **Spring Authorization Server 1.3.x password grant deprecated/미지원** — Claude 환각 위험. Task 12 + FR-09-2/18 API 가정 재검토 필수.
  5. (§4) `/oauth2/token` 표준 endpoint 활성화 공격 표면 확대 — 비활성화 또는 후속 PR.
  6. (§4) JWT signing key prod fail-fast 코드 task 누락 — silent dev fallback 위험.
- ⚠️ 주의. **14건** (위 본문 명시)
- ✅ 통과. 마이그레이션 FK 순서 / partial index / Refresh replay race-safe / Argon2 dummy verify / CSRF / ArchUnit 회귀 가드 / monster scope 인정 + GC 후속 PR 결정 등 핵심 골격 견고.

**monster PR scope + auth 절대 규칙 영역. BLOCKER 0건 도달 시 머지 권장. 본 결과 BLOCKER 6건 → /bts-review-plan 단계 loop back 권장.**

핵심 권고 (외부 sub-agent).
(a) **Spring Authorization Server 1.3.x password grant 가정 검증** — `AuthorizationGrantType.PASSWORD` 실제 컴파일 sample 확인. 미지원 시 BTS 자체 token 발급 (RegisteredClient 우회 + JwtEncoder + JwtIssuer 직접) 으로 재설계.
(b) **`/oauth2/token` endpoint 본 PR 비활성화** + Spring Authorization Server Bean 자체 본 PR 미도입 검토 (BTS 자체 JWT 우선).
(c) Wave 그래프 재계산 + longest path 메타 보정.
(d) sid 기반 revoke 검사 task + ProviderRegistry priority task + UserRepository task 명시 추가.
(e) prod profile JwtKeyProvider 분기 task 추가.

---

## 리뷰 결과 — 메인 세션 감수 (2026-05-20)

외부 sub-agent fresh 시각 결과 신뢰. 특히 BLOCKER #4 (Spring Authorization Server password grant) 가 plan 핵심 가정 재검토 수준. 사용자 결정 분기 직전. 다음 옵션 제시 — 게이트 1 진입 전 사용자 결정 필수.

A) **BLOCKER 6건 + 주의 14건 모두 spec/plan 보강 + plan-eng-review 재호출** — 정석 loop back.
B) **BLOCKER 6건 우선 해소 + 주의 implementer 위임** — 빠른 보강 + 게이트 1.
C) **BLOCKER #4 (Spring Authorization Server) 채택 자체 재결정** — 사전 결정 7건 중 #2 재검토. 옵션 (i) Spring Authorization Server 유지 + 자체 JWT 발급 흐름 / (ii) Spring Authorization Server 제거 + nimbus-jose-jwt 직접 (직전 grill D2 의 옵션 B 재고려).
D) **작업 분할** — monster PR scope 가 BLOCKER 6건 시그널. PR 1 (마이그레이션 + 도메인 엔티티 + repository), PR 2 (LocalProvider + SecurityConfig + login/logout), PR 3 (Refresh rotation), PR 4 (LDAP Auto-provisioning + LDAP unavailable), PR 5 (PAT 검증 + ADR) 등.

**사용자 결정.** 옵션 A (정석 loop back — BLOCKER 5건 + 주의 14건 모두 보강 + sub-agent 재호출) 확정. BLOCKER #4 별도 D2 옵션 A (nimbus-jose-jwt 직접 + Spring Auth Server 제거) 재결정 확정.

---

## 리뷰 결과 — engineering (외부 sub-agent fresh 시각, 보강 round 2, 2026-05-20)

### 1. 트랜잭션 경계 검증
- ✅ BLOCKER #1 해소. spec §9 CONCERN-1 본문에 `TransactionSynchronizationManager.isActualTransactionActive()` + `TransactionAspectSupport.currentTransactionStatus().isNewTransaction()` 단언 추가.
- ✅ spec §3 자기모순 해소. Provider 메서드 @Transactional REQUIRES_NEW + Adapter stateless 위임 일관.
- ✅ Task 15 LdapProvider @Component → @Service 확정.
- ✅ Task 29 ArchUnit 룰 회귀 가드 유지.

### 2. wave 그래프 검증
- ✅ BLOCKER #2 해소. ## Plan 메타 wave 매핑 표 권위. Task 2 Wave 2 이동.
- ✅ BLOCKER #3 해소. longest path 실측 6 명시.
- ✅ Task 19 depends-on [12,13,14,15,34,35] 의 wave 정렬 정합 (Task 34 Wave 4, Task 19 Wave 4 동시 — files 교집합 ∅).
- ✅ Task 33 SPI breaking change 가 Wave 1 → Wave 2 dependents 정합.

### 3. 마이그레이션 의존성 검증
- ✅ V004 / V005 / V006 FK 순서 정확.
- ✅ partial index `WHERE revoked_at IS NULL` 설계.
- ✅ EC-26 PAT prefix hash 정책 + EC-27 PAT 무기한 정책 명시.

### 4. 보안 검증
- ✅ BLOCKER #4 해소. Spring Authorization Server 미도입 결정. Task 12 재정의 (JwkSource + NimbusJwtEncoder + NimbusJwtDecoder Bean 3개). FR-09-2/18/20/26 정정.
- ✅ BLOCKER #5 해소. /oauth2/token 등 표준 endpoint 미활성화. Task 19 단일 SecurityFilterChain Bean. 공격 표면 축소.
- ✅ BLOCKER #6 해소. Task 7 profile 분기 (@Profile prod/!prod) + Spring @Value placeholder 미해소 fail-fast + EC-30.
- ✅ sid revoke filter Task 34 추가 + Caffeine 5s TTL 캐시 EC-29.
- ✅ Cookie Path 통일 (/api/v1/auth).
- ✅ ProviderRegistry priority (LDAP 80 > Local 70 > PAT 60) + 동률 EC-25.
- ✅ permitAll 4개 경로 명시 (FR-09-30 / Task 19).
- ✅ AuthAuditLog 9종 enum (Task 36 / FR-09-31 / SDD 19.9 후속 PR 메모).
- ✅ 로그 자동 검증 FR-09-33 (logback ListAppender regex).

### 5. TDD task 명확도 검증
- ✅ 37 task 전부 RED → GREEN → REFACTOR 구조 유지.
- ✅ Task 31 build 의존성 Wave 0 분리.
- ✅ Task 7 / 12 / 11 RED 본문 명확화.
- ⚠️ 잔여 미세 — Task 26~28 통합 테스트 시나리오 다중. implementer 위임 명시 (조직 결정 — BLOCKER 아님).

### 6. spec ↔ plan 정합 검증
- ✅ FR-09-1~33 (33건) 모두 task 매핑.
- ✅ EC-01~30 (30건) 모두 매핑.
- ✅ API §4.1~4.7 + DM V004~V006 모두 매핑.

### 신규 BLOCKER 발견 검토
- **신규 BLOCKER 0건**. Spring Security 6.x / nimbus-jose-jwt 9.x API 가정 일관.
- ⚠️ 잔여 미세 (BLOCKER 아님). Task 33 SPI breaking change wave 2 안 dependents 영향 → implementer dispatch 시 Task 33 우선 완료 후 13/14/15 시작 보장 필요 (bts-impl depends-on 그래프 이미 표현).

### 종합 판정 (round 2)
- BLOCKER 직전 6건 → **해소 6건 / 잔여 0건**. ✅
- 주의 직전 14건 → **해소 14건 / 잔여 0건**. ✅
- 신규 BLOCKER 발견. **0건**.
- 권고. **게이트 1 진입 권장**. monster PR scope 인정 + 37 task / 6 wave / longest path 6 합리. **추가 loop 불필요**.
