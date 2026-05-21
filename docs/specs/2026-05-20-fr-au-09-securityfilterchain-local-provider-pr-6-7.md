# FR-AU-09 세션/토큰 관리 — 스펙

> slug. fr-au-09-securityfilterchain-local-provider-pr-6-7
> 일자. 2026-05-20
> 단계. /bts-spec Phase A 직접 작성 (office-hours 부적합, 우회 결정)
> 도메인 정리. plan 파일 ## 도메인 정리 참조
> 사전 결정 (확정 7건). plan 파일 ### 사전 결정 + ### bts-spec 단계 결정 분기

## 1. 사용자 시나리오 (Given-When-Then)

### US-01. Local 로그인 성공

```
Given. BTS 에 등록된 사용자 "alice" + V003 stored_password_credentials 에 Argon2id 해시 row 존재
When.  POST /api/v1/auth/login {provider: "local", username: "alice", password: "***"} + 유효한 X-XSRF-TOKEN 헤더
Then.  200 OK + body {access_token, expires_in: 900, token_type: "Bearer"}
       + Set-Cookie: refresh_token=<rt> HttpOnly Secure SameSite=Strict Max-Age=1209600
       + sessions 테이블에 row 1건 INSERT (revoked_at=null, expires_at=now+14d)
       + refresh_tokens 테이블에 row 1건 INSERT (token_hash, used_at=null)
       + AuthAuditLog LOGIN_SUCCESS 이벤트 기록 (userId, providerId, ipAddress, userAgent)
```

### US-02. Local 로그인 실패 — 잘못된 비번

```
Given. 사용자 "alice" 존재 + 잘못된 비번
When.  POST /api/v1/auth/login + 유효한 CSRF
Then.  401 Unauthorized + body {error: "invalid_credentials"}
       + LocalCredentialService.verifyForUser → false (Argon2 비교)
       + sessions / refresh_tokens INSERT 없음
       + AuthAuditLog LOGIN_FAILURE 이벤트 (reason: "bad_password")
```

### US-03. Local 로그인 실패 — user 없음 (timing attack 방어)

```
Given. 사용자 "ghost" 미존재
When.  POST /api/v1/auth/login
Then.  401 + LocalCredentialService.verifyForUser 내부의 dummy verify (Argon2Params.DUMMY_HASH) 호출됨
       + 응답 시간이 US-02 와 ±50ms 이내 (timing 일정화 검증)
       + AuthAuditLog LOGIN_FAILURE (reason: "no_user")
```

### US-04. LDAP 첫 로그인 + Auto-provisioning

```
Given. BTS users 테이블에 "bob" row 없음 + LDAP 서버 bob 존재 (cn=bob, mail=bob@corp.com)
When.  POST /api/v1/auth/login {provider: "ldap-corp", username: "bob", password: "***"}
Then.  200 OK + access_token + refresh_token Cookie
       + V001 users 테이블에 bob row INSERT (UPSERT ON CONFLICT)
       + V002 user_external_accounts row INSERT (provider_id="ldap-corp", external_id=DN)
       + sessions / refresh_tokens INSERT
       + AuthAuditLog LOGIN_SUCCESS + USER_PROVISIONED 이벤트 둘 다
```

### US-05. Access Token 만료 + Refresh rotation

```
Given. US-01 직후 + 15분 경과 (access expired) + refresh 유효
When.  POST /api/v1/auth/refresh + Cookie refresh_token=<rt>
Then.  200 + body {access_token (new), expires_in: 900}
       + Set-Cookie: refresh_token=<rt2 new> Max-Age=1209600
       + refresh_tokens 의 옛 row used_at=now + replaced_by=<rt2.id>
       + 새 refresh_tokens row INSERT
       + AuthAuditLog TOKEN_REFRESHED
```

### US-06. Refresh Token 재사용 (replay attack) — Session 전체 revoke

```
Given. US-05 직후 (옛 rt used_at != null)
When.  공격자가 옛 rt 로 POST /api/v1/auth/refresh 재시도
Then.  401 + body {error: "refresh_token_reused"}
       + sessions.revoked_at=now (해당 sid 모든 refresh chain 무효화)
       + AuthAuditLog SUSPICIOUS_REFRESH_REPLAY + 관리자 알림 trigger
```

### US-07. 로그아웃 (현재 디바이스)

```
Given. US-01 직후 + 인증된 사용자
When.  POST /api/v1/auth/logout + Authorization: Bearer <access_token>
Then.  204 No Content
       + sessions[해당 sid].revoked_at=now
       + refresh_tokens[해당 session_id 모두].used_at=now (rotation chain 무효화)
       + Set-Cookie: refresh_token= Max-Age=0 (Cookie 만료)
       + AuthAuditLog LOGOUT
```

### US-08. 로그아웃 (모든 디바이스)

```
Given. 사용자가 PC + 핸드폰 동시 로그인 (sessions row 2건)
When.  POST /api/v1/auth/logout/all
Then.  204
       + sessions[user_id=alice 모두].revoked_at=now
       + AuthAuditLog LOGOUT_ALL_DEVICES
```

### US-09. PAT 검증 — API 요청

```
Given. PAT row 사전 등록 (DB INSERT, 본 PR 은 발급 endpoint 없음 — 후속 PR)
       + Authorization: Bearer pat_xxxxxxxx
When.  GET /api/v1/auth/whoami + Authorization Bearer 헤더
Then.  PersonalAccessTokenService.verify(token) → 일치
       + personal_access_tokens.last_used_at=now
       + 200 + Principal body
       + AuthAuditLog PAT_USED
```

### US-10. LDAP unavailable 시 fallback (CONCERN-4)

```
Given. LDAP 서버 stop / 네트워크 단절
When.  POST /api/v1/auth/login {provider: "ldap-corp", ...}
Then.  503 Service Unavailable + body {error: "ldap_unavailable", retry_after: 30}
       + AuthAuditLog LDAP_UNAVAILABLE
       + 클라이언트 측 fallback 흐름. 사용자가 다른 Provider (Local) 선택 가능 (UI 안내)
       + LDAP Provider 외 다른 Provider 의 요청은 정상 동작 (격리 검증)
```

### US-11. 동시 로그인 (멀티 디바이스)

```
Given. 사용자 alice 가 PC 로 로그인 (US-01)
When.  alice 가 핸드폰으로 동일 자격으로 다시 로그인
Then.  PC 의 access/refresh 모두 유효 (revoke 없음)
       + 핸드폰 새 sessions row INSERT (sid 별도) + refresh chain 별도
       + alice 의 active sessions row 2건
       + AuthAuditLog LOGIN_SUCCESS (device_fingerprint 다름)
```

### US-12. CSRF 검증

```
Given. SPA 가 first GET 요청으로 XSRF-TOKEN Cookie 발급받음
When.  POST /api/v1/auth/login 헤더 X-XSRF-TOKEN 미동봉
Then.  403 Forbidden + CsrfFilter 거부
```

## 2. 기능 요구사항 (FR)

| 번호 | 요구사항 | 측정 |
|---|---|---|
| FR-09-1 | SecurityFilterChain 통합 — Spring Authorization Server + SpringSecurityProviderAdapter Bean 명시 등록 | application 기동 시 securityFilterChain Bean 1개 + 모든 Provider 등록 검증 |
| FR-09-2 | BTS 자체 JWT 발급 — nimbus-jose-jwt 직접 호출 + spring-security-oauth2-resource-server JwtDecoder 검증. **Spring Authorization Server 미도입** (OAuth 2.1 password grant 미지원 → BLOCKER #4 해소 / D2 옵션 B 재결정) | JwtIssuer Bean + JwtDecoder(NimbusJwtDecoder) Bean 등록 확인 |
| FR-09-3 | BTS 자체 JWT 발급 — issuer = BTS application.yml `bts.auth.issuer-uri` | JWT iss claim = BTS issuer 검증 |
| FR-09-4 | Access JWT 15분 만료 — exp - iat = 900s | JWT exp claim 검증 |
| FR-09-5 | Refresh Token 14일 HttpOnly Cookie | Set-Cookie 검증 (HttpOnly Secure SameSite=Strict Max-Age=1209600) |
| FR-09-6 | Session DB row 단위 = 1 로그인 (device 단위) | US-11 의 active sessions row 2건 검증 |
| FR-09-7 | PAT 검증 (Provider) — `PatProvider` + PersonalAccessTokenService.verify | Authorization Bearer pat_xxx 인식 + 200 |
| FR-09-8 | LDAP Auto-provisioning — provisionUser 메서드 SecurityFilterChain 통합 | US-04 검증 |
| FR-09-9 | CSRF Cookie 모드 — 기존 ADR + ADR 본문 정정 (stateless 표현) | 기존 PreferencesControllerCsrfTest 통과 + 정정된 ADR 본문 검토 |
| FR-09-10 | 로그아웃 — 현재 디바이스 (/logout) + 모든 디바이스 (/logout/all) | US-07 / US-08 검증 |
| FR-09-11 | revoke 메커니즘 — sid 기반 stateless 검증 + DB revoked_at 보조 | Access Token 의 sid → sessions row 조회 → revoked_at != null 시 401 |
| FR-09-12 | JWK Set endpoint — GET /.well-known/jwks.json | 200 + JSON Web Key Set body |
| FR-09-13 | LDAP unavailable fallback — 503 + 다른 Provider 격리 | US-10 검증 (Testcontainers LDAP stop 시나리오) |
| FR-09-14 | ProviderRegistry.findFor(Credential) 활용 — AuthRouter 도입 없음 | findFor(Credential.UsernamePassword) → LocalProvider / findFor(LdapBind) → LdapProvider |
| FR-09-15 | JWT mfa_verified claim = false 더미 | TOTP/백업코드 후속 PR. 본 PR 은 dummy 발급 |
| FR-09-16 | csrf-cookie-mode ADR 정정 — "stateless" 표현 → "Access Token 검증은 stateless, Session/Refresh/PAT 은 revoke 보조 DB" | docs/decisions/2026-05-20-csrf-cookie-mode.md 본문 patch |
| FR-09-17 | application.yml issuer-uri = BTS 자체 — Keycloak realm URL 제거 (PR #2 잔재) | application.yml diff |
| FR-09-18 | **제거됨** (BLOCKER #4 해소). Spring Authorization Server RegisteredClient 미사용. 3rd-party OAuth2 client 통합은 후속 PR — Spring Auth Server 또는 별도 OAuth2 server 도입 시점에 검토 | 해당 없음 |
| FR-09-19 | JWT signing key — dev 는 memory RSA (DevMemoryKeyProvider, startup 시 KeyPairGenerator), prod 는 PEM file via `bts.auth.jwt.private-key-pem-path` env var. **profile 별 @Bean 분기** (`@Profile("prod")` / `@Profile("!prod")`) | dev 기동 시 메모리 키 생성 + prod 기동 시 PEM 로드 + prod env var 누락 시 fail-fast |
| FR-09-20 | **제거됨** (BLOCKER #5 해소). `/oauth2/token` 등 표준 OAuth2 endpoint 본 PR 미활성화 (Spring Auth Server 미도입). /api/v1/auth/login 만 활성 | 해당 없음 |
| FR-09-21 | GC job 별도 후속 PR — refresh chain 30일 후 used_at != null 정리 + Session row 30일 후 revoked/expired 정리. 본 PR 은 정책 ADR 메모만 | docs/decisions/2026-05-20-session-pat-schema.md 본문에 GC 정책 메모 |
| FR-09-22 | GET /api/v1/auth/providers (public, no auth) — Provider 목록 (id, displayName, type, priority) 조회. UI 로그인 폼 의존 | 200 + JSON array body |
| FR-09-23 | device_fingerprint = Server 측 생성. SHA-256(User-Agent + IP) hex 12자. Privacy 보호 | sessions.device_fingerprint 컬럼 12자 hex 검증 |
| FR-09-24 | CORS origin = application.yml 환경별. `dev: http://localhost:5173`, `prod: https://bts.example.com` (env override) | dev/prod profile 별 CorsConfigurationSource 검증 |
| FR-09-25 | ProviderRegistry 다중 Provider 우선순위 — Local + LDAP + PAT 동시 등록. ProviderType.priority 활용 (SDD 19.2) | findFor(Credential) 가 priority 순 검색 검증 |
| FR-09-26 | **재정의** (BLOCKER #4 해소). 단일 SecurityFilterChain Bean + JwtDecoder Bean (NimbusJwtDecoder.withPublicKey + RS256 알고리즘) + oauth2ResourceServer.jwt converter (sid revoke filter 포함). Order 분기 없음 | application 기동 시 securityFilterChain Bean 1개 + JwtDecoder Bean 1개 확인 |
| FR-09-27 | **신규 (BLOCKER #1 + spec↔plan §6 보강)**. sid 기반 revoke filter — JwtAuthenticationConverter 커스텀 + Access JWT 검증 통과 후 sid claim → sessions.revoked_at 검사. revoked != null 또는 expires_at <= now 시 401. lazy revoke (EC-12). 정상 흐름 캐시 (예. Caffeine 5s TTL) — 매 요청 DB 미접근 | sid 무효 JWT 요청 401 + DB 1회 조회 검증 |
| FR-09-28 | **신규 (BLOCKER 주의 보강)**. AuthenticationProvider SPI + ProviderType enum 에 `priority: Int` 필드 추가. ProviderRegistry.findFor 가 priority 내림차순 정렬 후 `firstOrNull { supports(credential) }`. LDAP 80 > Local 70 > PAT 60 | unit test 로 priority 정렬 동작 확인 + 동률 시 등록 순서 (EC-25) |
| FR-09-29 | **신규 (BLOCKER 주의 보강)**. UserRepository (jdbc) — V001 `users` 테이블 조회. `findByUsername(username): User?` + `provisionFromLdap(...)` UPSERT. LocalProvider / AutoProvisionService 가 사용 | unit test + Testcontainers Postgres |
| FR-09-30 | **신규 (BLOCKER 주의 보강)**. SecurityConfig 의 permitAll 경로 명시 — `/api/v1/auth/login`, `/api/v1/auth/providers`, `/.well-known/jwks.json`, `/actuator/health`. 그 외 `/api/v1/**` authenticated | WebMvcTest slice 로 각 permitAll 경로 200 + 보호 경로 401 확인 |
| FR-09-31 | **신규 (BLOCKER 주의 보강)**. AuthAuditLog 데이터 클래스 + AuthEventType enum (LOGIN_SUCCESS / LOGIN_FAILURE / LOGOUT / LOGOUT_ALL_DEVICES / TOKEN_REFRESHED / SUSPICIOUS_REFRESH_REPLAY / USER_PROVISIONED / PAT_USED / LDAP_UNAVAILABLE 9종) + AuthAuditLogService 인터페이스. 본 PR 은 in-memory or 단순 INSERT 구현. 풀 파티셔닝 (SDD 19.9 월 단위) 은 후속 PR | unit test enum 9종 + Service 발사 검증 |
| FR-09-32 | **신규 (BLOCKER #6 해소)**. JwtKeyProvider profile 분기 — `@Profile("prod") @Bean fun pemKeyProvider(env): PemFileKeyProvider` + `@Profile("!prod") @Bean fun memKeyProvider(): DevMemoryKeyProvider`. prod env var 누락 시 `ApplicationFailedToStartException` + 명확한 에러 메시지 | dev 기동 200 + prod env 정상 200 + prod env 누락 fail-fast 검증 |
| FR-09-33 | **신규 (BLOCKER 주의 보강)**. 로그 자동 검증 — logback test appender (ListAppender) + regex pattern (`password\|secret\|token\|hash`) — 본 PR 의 모든 단위/통합 테스트가 sensitive 단어 미출현 자동 단언 | TestConfiguration LogAssertionsTest 통합 |

## 3. 비기능 요구사항 (NFR)

### 보안 (DEVELOPMENT.md §1)

| 항목 | 요구 |
|---|---|
| 평문 패스워드 저장 | 금지. Argon2id (V003 + LocalCredentialService 활용) |
| Access Token 저장 | sessionStorage (응답 body 반환 — XSS 대비). SPA 책임 |
| Refresh Token 저장 | HttpOnly + Secure + SameSite=Strict Cookie |
| Argon2 wipe | 모든 메서드 종료 시 CharArray.fill (PR #6 패턴 유지) |
| CSRF | Cookie 모드 + SameSite=Strict (기존) |
| Log 정책 | password / hash / userId / token / token_hash 출력 금지 |
| SQL injection | jOOQ 또는 JdbcTemplate prepared statement (DATA.md §SQL) |
| 401 응답 | 상세 사유 미공개 (alice 라는 user 존재 여부 누설 금지) |

### 성능

| 항목 | 목표 |
|---|---|
| 로그인 응답 (Argon2 포함) | < 500ms (p95) |
| Refresh 응답 | < 200ms (p95) |
| JWT 검증 | < 50ms (캐시) |
| Session revoke check | < 20ms (sid 인덱스) |
| JWK Set 응답 | < 50ms (캐시 24h) |
| DB connection pool 크기 | Hikari maxPoolSize. dev=10 / prod=30 (Naver Cloud 단일 호스트 PostgreSQL) |
| 부하 한도 | 1,000 사용자 규모. peak 동시 로그인 300 가정 (사내 일과 시작) — Argon2 memory=64MB × parallelism=4 환경에서 JMH 측정 후 worker pool 조정 |
| JWT key zero-downtime | 본 PR 키 1개 + env var path. docker-compose / Naver Cloud volume mount 로 동일 키 영구 유지. rotation 메커니즘은 후속 PR (V007) |
| CORS | application.yml 환경별. dev origin = `http://localhost:5173`, prod origin = `https://bts.example.com` (env var override) |

### 가용성

| 항목 | 요구 |
|---|---|
| LDAP unavailable | 503 + 다른 Provider 격리 (US-10) |
| DB connection loss | 503 graceful + 재시도 안내 |
| Argon2 verify 예외 | runCatching false (PR #6 EC-07 패턴) |

### 로그 / 감사 (SDD 19.9)

| 이벤트 | 기록 |
|---|---|
| LOGIN_SUCCESS | userId, providerId, ipAddress, userAgent, deviceFingerprint (해시), timestamp |
| LOGIN_FAILURE | (userId 또는 null), providerId, reason ("bad_password" / "no_user" / "locked"), ipAddress |
| LOGOUT / LOGOUT_ALL_DEVICES | userId, sid (해당), timestamp |
| TOKEN_REFRESHED | userId, sid, 이전/이후 jti |
| SUSPICIOUS_REFRESH_REPLAY | userId, sid, ipAddress + 관리자 알림 trigger |
| USER_PROVISIONED | userId, providerId, externalId, 자동 생성 사유 |
| PAT_USED | userId, patId, ipAddress, requestPath |
| LDAP_UNAVAILABLE | providerId, error class, timestamp |

본 PR 은 AuthAuditLog 인터페이스 + 위 이벤트 발사. 월 단위 파티션 (SDD 19.9) 은 후속 PR.

### 트랜잭션 경계 (DATA.md §6, PR #6 learning)

| 메서드 | 경계 |
|---|---|
| `authenticate(credential)` (Provider 메서드 — LocalProvider / LdapProvider / PatProvider) | @Transactional propagation=REQUIRES_NEW (인증 별 격리) — Provider 클래스 자체가 @Service + @Transactional 부착. SpringSecurityProviderAdapter 는 stateless 위임 only (@Transactional 부착 안 함, 트랜잭션 경계는 위임 대상에서 시작) — **BLOCKER #1 + §3 자기모순 해소** |
| LDAP Auto-provisioning (users INSERT + user_external_accounts INSERT) | 단일 @Transactional (UPSERT 패턴 V001/V002) |
| Session 발급 (sessions + refresh_tokens INSERT) | 단일 @Transactional |
| Token refresh rotation (옛 rt update + 새 rt INSERT) | 단일 @Transactional |
| Logout / Logout-all (sessions update + refresh_tokens update) | 단일 @Transactional |
| Session revoke check (sid 조회) | @Transactional(readOnly=true) |
| PAT verify (token_hash 조회 + last_used_at update) | 단일 @Transactional (last_used_at 갱신 포함) |
| SpringSecurityProviderAdapter | @Transactional 부착 안 함. stateless 위임만. 트랜잭션 경계는 위임 대상 (LocalProvider/LdapProvider) 에서 시작 |
| LocalProvider / LdapProvider / PatProvider (신규/기존) | @Service + @Transactional. 인증 흐름의 트랜잭션 경계 시작점 |
| AuthController (신규 — login/logout/refresh endpoint) | @RestController. @Transactional 부착 안 함 (service layer 책임) |
| ProvidersController (신규 — GET /api/v1/auth/providers) | @RestController. @Transactional(readOnly=true) (ProviderRegistry 조회) |

**모든 @Transactional 적용 클래스는 @Service 부착 필수** (PR #6 learning #1). ArchUnit 룰 (FR-09-20 회귀 가드) 로 본 PR 에서 도입.

### 동시성

| 시나리오 | 처리 |
|---|---|
| Auto-provisioning race (같은 LDAP user 동시 첫 로그인) | UPSERT (V001 users.username UNIQUE) + V002 ON CONFLICT |
| Session row 동시 갱신 (last_seen_at) | best-effort UPDATE (lost update 허용 — 통계용) |
| Refresh rotation 동시 (실수로 두 번 호출) | optimistic locking — `used_at IS NULL` WHERE clause + RETURNING 0 row 시 401 |

## 4. API 인터페이스 (REST)

### 4.1 POST /api/v1/auth/login

```http
POST /api/v1/auth/login HTTP/1.1
Content-Type: application/json
X-XSRF-TOKEN: <csrf>

{
  "provider": "local",          // 또는 "ldap-corp"
  "username": "alice",
  "password": "..."
}
```

**200 OK.**

```json
{
  "access_token": "eyJraWQiOiJrLTAxIiwiYWxnIjoiUlMyNTYi...",
  "token_type": "Bearer",
  "expires_in": 900,
  "scope": ""
}
```

Set-Cookie. `refresh_token=<opaque>; HttpOnly; Secure; SameSite=Strict; Max-Age=1209600; Path=/api/v1/auth` (login + logout + refresh 일관 Path — BLOCKER 주의 해소)

**401 Unauthorized.** `{"error": "invalid_credentials"}`
**403 Forbidden.** CSRF 검증 실패 시 `{"error": "csrf_token_mismatch"}`
**503 Service Unavailable.** `{"error": "ldap_unavailable", "retry_after": 30}`

### 4.2 POST /api/v1/auth/refresh

```http
POST /api/v1/auth/refresh HTTP/1.1
Cookie: refresh_token=<opaque>
X-XSRF-TOKEN: <csrf>
```

**200 OK.** body 동일 형식 + Set-Cookie 새 refresh_token.
**401.** `{"error": "refresh_token_expired" | "refresh_token_reused" | "refresh_token_invalid"}`

### 4.3 POST /api/v1/auth/logout / /api/v1/auth/logout/all

```http
POST /api/v1/auth/logout HTTP/1.1
Authorization: Bearer <access>
X-XSRF-TOKEN: <csrf>
```

**204 No Content.** + Set-Cookie 만료.

### 4.4 GET /.well-known/jwks.json

Public endpoint (auth 없음). Resource Server / SPA / 3rd-party 가 서명 검증에 사용.

**200 OK.**

```json
{
  "keys": [
    {"kty": "RSA", "use": "sig", "kid": "k-01", "alg": "RS256", "n": "...", "e": "AQAB"}
  ]
}
```

Cache-Control. `public, max-age=86400` (24h).

### 4.5 GET /api/v1/auth/whoami (인증 필수, 기존)

Authorization Bearer <access> 또는 <pat> 모두 인식. 기존 WhoamiController 확장.

### 4.6 ~~Spring Authorization Server 표준 endpoint~~ (BLOCKER #4/#5 해소 — 제거)

**본 PR 미도입.** Spring Authorization Server 1.x 가 OAuth 2.1 compliance 라 password grant 미지원 → BTS 자체 token 발급 흐름 (nimbus-jose-jwt + JwtIssuer 직접) 으로 결정. 표준 OAuth2 endpoint (`/oauth2/token`, `/oauth2/authorize`, `/oauth2/revoke`, `/oauth2/introspect`) 는 본 PR 에 미활성화 — 3rd-party OAuth2 client 통합 PR 에서 Spring Auth Server 도입 시점에 재검토.

### 4.7 GET /api/v1/auth/providers (public, no auth)

UI 로그인 폼이 어떤 Provider 를 노출할지 결정하려고 사용.

**200 OK.**

```json
{
  "providers": [
    {"id": "local", "displayName": "이메일/비밀번호", "type": "LOCAL", "priority": 100},
    {"id": "ldap-corp", "displayName": "회사 계정", "type": "LDAP", "priority": 80}
  ]
}
```

ProviderRegistry 의 등록 Provider 들을 priority 내림차순으로 정렬. PAT 는 노출 안 함 (UI 가 알 필요 없음).

## 5. 데이터 모델 변경

### V004 `sessions` (신규)

```sql
CREATE TABLE sessions (
  id              UUID PRIMARY KEY,
  user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  provider_id     VARCHAR(64) NOT NULL,
  device_fingerprint VARCHAR(128),
  ip_address      INET,
  user_agent      TEXT,                            -- VARCHAR(512) 일부 UA 초과 가능 → TEXT
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  expires_at      TIMESTAMPTZ NOT NULL,
  last_seen_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  revoked_at      TIMESTAMPTZ,
  revoke_reason   VARCHAR(64)
);
CREATE INDEX idx_sessions_user_active ON sessions(user_id) WHERE revoked_at IS NULL;
CREATE INDEX idx_sessions_expires_active ON sessions(expires_at) WHERE revoked_at IS NULL;
```

### V005 `refresh_tokens` (신규)

```sql
CREATE TABLE refresh_tokens (
  id              UUID PRIMARY KEY,
  session_id      UUID NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
  token_hash      VARCHAR(64) NOT NULL UNIQUE,    -- SHA-256 hex
  issued_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  expires_at      TIMESTAMPTZ NOT NULL,
  used_at         TIMESTAMPTZ,
  replaced_by     UUID REFERENCES refresh_tokens(id)
);
CREATE INDEX idx_refresh_session ON refresh_tokens(session_id);
```

### V006 `personal_access_tokens` (신규)

```sql
CREATE TABLE personal_access_tokens (
  id              UUID PRIMARY KEY,
  user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  name            VARCHAR(128) NOT NULL,
  token_hash      VARCHAR(64) NOT NULL UNIQUE,
  scopes          JSONB NOT NULL DEFAULT '[]',
  expires_at      TIMESTAMPTZ,
  last_used_at    TIMESTAMPTZ,
  revoked_at      TIMESTAMPTZ,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_pat_user_active ON personal_access_tokens(user_id) WHERE revoked_at IS NULL;
```

본 PR 은 PAT 발급/조회/revoke endpoint 없음 (후속 PR). DB 모델 + PatProvider 검증 흐름만.

### V007 `jwt_signing_keys` (선택 — spec 단계 결정)

옵션. 키 1개로 시작 시 application.yml 의 PEM 직접 로드 + V007 없음. 후속 PR rotation 도입 시 추가.
옵션. 본 PR 에 V007 도입 — 키 1개 active row + rotation 메커니즘 schema 만 준비.

**결정. V007 없음 (키 1개 application.yml).** rotation 메커니즘 코드는 후속 PR. 본 PR 의 kid claim 은 `"k-01"` 상수.

### V001 / V002 / V003 의존

- V001 `users.username UNIQUE` (Auto-provisioning UPSERT 활용).
- V002 `user_external_accounts (provider_id, external_id) UNIQUE` (LDAP Auto-provisioning).
- V003 `stored_password_credentials.user_id` FK + `password_hash` (Local 로그인 검증).

## 6. 엣지 케이스

| 번호 | 케이스 | 처리 |
|---|---|---|
| EC-01 | Local password mismatch | Argon2 verify false → 401 + LOGIN_FAILURE (reason="bad_password") |
| EC-02 | Local user 없음 | dummy verify (Argon2Params.DUMMY_HASH) → 401 + LOGIN_FAILURE (reason="no_user"). 응답 시간 ±50ms |
| EC-03 | LDAP unavailable | 503 + LDAP_UNAVAILABLE 기록. 다른 Provider 격리 (Local 정상 동작) |
| EC-04 | Refresh Token 만료 | 401 + "refresh_token_expired" + 재로그인 요구 |
| EC-05 | Refresh Token 재사용 (replay) | 401 + "refresh_token_reused" + sessions.revoked_at=now + SUSPICIOUS_REFRESH_REPLAY |
| EC-06 | Access Token kid 미존재 | 401 + "invalid_token" |
| EC-07 | Access Token 서명 불일치 | 401 |
| EC-08 | PAT 형식 (`pat_` prefix) 인식 실패 | 401 |
| EC-09 | PAT revoked / 만료 | 401 |
| EC-10 | PAT scope 불일치 | 403 + "insufficient_scope" |
| EC-11 | 동시 Auto-provisioning race | UPSERT (V001 username UNIQUE + V002 (provider_id, external_id) UNIQUE) — 한쪽 ON CONFLICT |
| EC-12 | Session expires_at < now | 401 + revoked_at=now 채움 (lazy revoke) |
| EC-13 | CSRF token 불일치 | 403 (기존 CsrfFilter 동작) |
| EC-14 | clock drift (서버 시간 어긋남) | JWT iat/exp leeway 30초 (Spring Authorization Server 기본) |
| EC-15 | DB connection loss | 503 + graceful 응답 |
| EC-16 | Argon2 verify 예외 | runCatching false (PR #6 EC-07 동일) |
| EC-17 | LDAP bind 성공 BUT BTS user 자동 생성 실패 (DB constraint violation) | 500 + rollback + USER_PROVISIONING_FAILED + 관리자 알림. LDAP bind 자체는 무효 처리 |
| EC-18 | logout 시 Authorization 헤더 없음 (이미 만료) | 204 (idempotent) |
| EC-19 | JWT 서명 키 PEM 파일 누락 / 잘못된 형식 | application 기동 실패 + 명확한 에러 메시지 (`bts.auth.jwt.private-key-pem-path` 설정 확인 안내) |
| EC-20 | Spring Authorization Server Bean 충돌 (기존 SecurityFilterChain Bean 과 자동 등록 Bean 동시 존재) | @Order(1) Authorization Server filter chain + @Order(2) BTS 기본 filter chain 으로 분리 |
| EC-21 | refresh chain 무한 증가 (1344 row/sid in 14d) | 본 PR 처리 안 함 — 후속 PR GC job. 30일 후 used_at != null row DELETE 정책 ADR 메모 |
| EC-22 | Session row 무한 증가 (운영 1년 후 누적) | 본 PR 처리 안 함 — 후속 PR. 30일 후 revoked/expired row DELETE |
| EC-23 | Concurrent Refresh rotation (동일 rt 두 요청 동시) | optimistic locking (`used_at IS NULL` WHERE + RETURNING) — 한쪽 401, 한쪽 200. EC-05 의 replay 와 다른 race window |
| EC-24 | Provider 다중 등록 우선순위 충돌 | ProviderType.priority 내림차순 검색 (SDD 19.2). 같은 priority 시 등록 순서 (Spring Bean order) |
| EC-25 | Provider priority 동률 (LDAP 80 / Local 80 등) | 발생 안 함 — LDAP 80 / Local 70 / PAT 60 명시. 동률 시 Spring Bean order (FR-09-28 명시) |
| EC-26 | PAT prefix hash 입력 정책 | `token_hash = SHA-256("pat_" + body)` — prefix 포함 (전체 token 그대로 해시). storage 일관성. Task 14 GREEN 본문 정정 |
| EC-27 | PAT expires_at nullable (무기한) | DEVELOPMENT.md §1 token TTL 정책 — 본 PR 결정. 무기한 허용 (NULL) but 운영 관리 차원 명시 — 후속 PR 에서 강제 만료 정책 검토 (1년 default 등) |
| EC-28 | logout 응답의 refresh_token Cookie 만료 | Set-Cookie: refresh_token=; Path=/api/v1/auth; Max-Age=0 — login 시 Path 와 일치 (RFC 6265). 브라우저 삭제 보장 |
| EC-29 | sid revoke filter 의 DB 부하 | JwtAuthenticationConverter 가 sid 검증 시 매 요청 DB 1회 — Caffeine 캐시 (TTL 5s, sid → revoked? 결과) 로 정상 흐름 DB 미접근. revoke 시 캐시 1회 cycle (≤5s) 후 효력 |
| EC-30 | prod profile JWT key env var 누락 | `bts.auth.jwt.private-key-pem-path` 미설정 + @Profile("prod") 활성 → PemFileKeyProvider Bean 생성 실패 → `ApplicationFailedToStartException` + 명확한 에러 (FR-09-32) |

## 7. 제약 조건

### SDD 19.5 명세 (Full scope 확정)

- Access JWT 15분 / sessionStorage 응답 body
- Refresh Token 14일 / HttpOnly Cookie
- Session DB 14일 / sessions 테이블
- PersonalAccessToken / DB SHA-256 해시
- JWT claims. sub, email, roles, sid, kid, mfa_verified (dummy false)

### SDD 19.2 활용

- `ProviderRegistry.findFor(Credential)` — Credential type 기반 routing
- `Credential.UsernamePassword` → LocalProvider (신규 구현)
- `Credential.LdapBind` → LdapProvider (PR #4)
- `Credential.Pat` → PatProvider (신규 구현, 검증만)

### SDD 19.3 AuthRouter — **본 PR 미도입** (사용자 확정)

- 별도 PR. 다중 Provider 운영 시점.

### SDD 19.7 2FA — **본 PR 미도입** (사용자 확정)

- mfa_verified=false dummy claim 만. TOTP/백업코드 후속 PR.

### DEVELOPMENT.md §1.5 / DATA.md §6

- CSRF 비활성화 금지 (기존 ADR 유지)
- @Transactional + @Service 부착 (learning 회귀 가드)

### ADR 4건 정합 + 1건 정정

- 정정. `2026-05-20-csrf-cookie-mode.md` 본문 "BTS는 JWT Bearer Token 기반 stateless 아키텍처이므로 세션을 유지하지 않는다." → "Access Token 검증은 stateless. Session/Refresh/PAT 은 revoke 보조 DB. JWT Bearer Token 의 stateless 검증은 본질을 유지함." (FR-09-16)
- 정합. `authentication-provider-spi-naming` / `stored-password-credential-schema` / `keycloak-image-selection` (Keycloak 역할 재정의는 신규 ADR 에서 명시).

### 신규 ADR 3건 (본 PR 에서 작성)

- `2026-05-20-jwt-issuer-strategy.md` — BTS 자체 발급자 + Spring Authorization Server 채택. Keycloak 역할 재정의 (OIDC IdP provider 의 한 인스턴스).
- `2026-05-20-session-pat-schema.md` — V004 sessions / V005 refresh_tokens / V006 personal_access_tokens 스키마 + 관계 + 1 로그인 = 1 row 결정.
- `2026-05-20-jwt-key-rotation-policy.md` — kid 관리 정책. 본 PR 키 1개 application.yml + V007 스키마는 후속 PR 도입 (rotation 메커니즘 schema only 결정).

### learning 회귀 가드

- @Service 부착 누락 (PR #6 #1) — ArchUnit 룰 검토.
- Kotlin companion 가시성 (PR #7 #1) — internal 부착 시 객체 전체 검토.
- wave 병렬 dispatch 의존성 (PR #6/#7) — plan 분해 시 의식.
- phantom 엔티티 (PR #6) — "활용 엔티티" 표기 검증.
- ktlintFormat 부수 변경 (PR #6) — wave 후처리 chore commit 패턴.

## 8. 측정 가능한 완료 기준

체크리스트. 본 PR 머지 전 전 항목 통과.

- [ ] V004 / V005 / V006 마이그레이션 Flyway success (Testcontainers 통합 테스트)
- [ ] application.yml issuer-uri = BTS 자체 (Keycloak realm URL 제거)
- [ ] Spring Authorization Server Bean 등록 + GET /.well-known/jwks.json 200
- [ ] SpringSecurityProviderAdapter 명시 @Bean 등록 (SecurityConfig)
- [ ] POST /api/v1/auth/login (Local, US-01) 200 + access_token + refresh_token Cookie
- [ ] POST /api/v1/auth/login (LDAP, US-04) 200 + Auto-provisioning user row INSERT
- [ ] POST /api/v1/auth/login US-02 (bad password) 401 + LOGIN_FAILURE
- [ ] POST /api/v1/auth/login US-03 (no user) 401 + dummy verify + 응답 시간 ±50ms
- [ ] POST /api/v1/auth/refresh (US-05) rotation 검증 (옛 rt used_at, 새 rt INSERT)
- [ ] POST /api/v1/auth/refresh (US-06) replay 검증 (옛 rt 재사용 → 401 + session revoke)
- [ ] POST /api/v1/auth/logout (US-07) 204 + session revoke
- [ ] POST /api/v1/auth/logout/all (US-08) 204 + 모든 session revoke
- [ ] GET /api/v1/auth/whoami + PAT (US-09) 200 + last_used_at 갱신
- [ ] LDAP unavailable 시 503 + 다른 Provider 격리 (US-10, Testcontainers stop 시나리오)
- [ ] 동시 로그인 (US-11) sessions row 2건 active
- [ ] CSRF (US-12) 403 검증 (기존 CsrfFilter)
- [ ] CONCERN-1 해소 — LocalCredentialService @Transactional 이 SecurityFilterChain 흐름 안에서 정상 작동 (통합 테스트)
- [ ] CONCERN-4 해소 — LDAP stop 시나리오 통합 테스트 통과
- [ ] ArchUnit 룰 — @Transactional 메서드 보유 클래스 @Service 강제 (PR #6 learning #1 회귀 가드)
- [ ] 로그인 응답 < 500ms (Argon2 포함, JMH 또는 통합 테스트 시간 측정)
- [ ] Refresh 응답 < 200ms
- [ ] JWT 검증 < 50ms
- [ ] csrf-cookie-mode ADR 본문 정정 (FR-09-16)
- [ ] 신규 ADR 3건 작성 + 머지
- [ ] domain/identity-access.md (Obsidian) 갱신 후보 list 본 PR 끝에 sync-obsidian.ts 미러 (또는 별도 PR 메모)
- [ ] glossary.md (Obsidian) 신규 용어 9건 추가 (sync-obsidian.ts 자동화 PR 의존)
- [ ] ktlintCheck / detekt 위반 0
- [ ] 기존 124 tests + 본 PR 신규 테스트 통과

## 9. CONCERN 해소 검증

### CONCERN-1 — 트랜잭션 경계 통합 (PR #6 learning #1) — **BLOCKER #1 보강**

**해소 방법.**
1. LocalCredentialService 의 모든 메서드 (`store` / `verifyForUser` / `rotate`) 가 SecurityFilterChain 호출 흐름 안에서 호출됨을 통합 테스트로 검증.
2. `@Transactional` 이 실제 동작하는지 검증 — `@Service` 부착 + Spring Bean 등록 사실 확인 + Spring AOP 프록시 trace.
3. ArchUnit 룰 추가. `@Transactional` 메서드 보유 클래스는 `@Service` / `@Component` / `@Repository` 부착 필수.
4. **트랜잭션 컨텍스트 명시 단언 (BLOCKER #1 보강)**. 통합 테스트 (Task 27.a 신규) 가 호출 stack 각 layer 에서 `TransactionSynchronizationManager.isActualTransactionActive()` + `TransactionAspectSupport.currentTransactionStatus().isNewTransaction()` 단언.

**검증 시나리오.**
- 통합 테스트. SecurityFilterChain → SpringSecurityProviderAdapter.authenticate → LocalProvider.authenticate → LocalCredentialService.verifyForUser. 각 단계 트랜잭션 컨텍스트 확인.
- DB 변경 (예. last_used_at 갱신) 이 트랜잭션 rollback / commit 에 정상 반영되는지.
- **propagation=REQUIRES_NEW 검증**. Provider 메서드가 새 트랜잭션 시작 (Spring AOP 프록시 trace + isNewTransaction() == true).
- SpringSecurityProviderAdapter 가 자신은 @Transactional 부착 안 함 + 호출 시점 isActualTransactionActive() == false (위임 대상 진입 시 활성화) 단언.

### CONCERN-4 — LDAP stop 시나리오 (PR #4 후속)

**해소 방법.**
1. LdapProvider 의 `authenticate(Credential.LdapBind)` 호출 시 LDAP 서버 unavailable 감지 — connection timeout / unreachable host / bind 거부 등 예외 유형 명시 처리.
2. 503 응답 + LDAP_UNAVAILABLE 감사 로그.
3. **다른 Provider 격리** — Local Provider 등 LDAP 외 Provider 의 요청은 정상 동작 (LdapProvider 의 예외가 SecurityFilterChain 전체를 막지 않음).

**검증 시나리오.**
- Testcontainers OpenLDAP 컨테이너 `.stop()` 후 LDAP 로그인 시도 → 503 + 30s retry 안내.
- 같은 시점 Local 로그인 시도 → 200 정상.
- LDAP 재시작 후 LDAP 로그인 다시 200 (별도 health check 없이 자동 복구).

---

## Brainstorming 발견 (메인 세션 직접 14 차원 sanity check)

> superpowers:brainstorming 의 design thinking 흐름은 본 sanity check 와 mismatch. 메인 세션이 직접 14 차원 검토.

### gap 카테고리 1. 가정 미명시 (영향 높음)

1. **Spring Authorization Server OAuth2 client 등록 모델** — BTS 자체 client 등록 (Spring Authorization Server 표준) vs client 없이 wrapper. 추천. client 등록. /api/v1/auth/login 은 Resource Owner Password Credentials grant wrapper 또는 자체 발급. **결정 필요**.
2. **JWT signing key 형식** — RSA private key PEM file (single key, env var path) vs JWK Set JSON. 추천. PEM file. 본 PR `bts.auth.jwt.private-key-pem-path` 환경 변수 + V007 없음 (사전 결정 유지). **결정 필요**.
3. **/oauth2/token 표준 endpoint 활성화 vs BTS 자체 /api/v1/auth/login** — 두 endpoint 가 같은 결과? 일관성? 추천. /api/v1/auth/login 은 BTS wrapper (provider 라우팅 + Auto-provisioning). /oauth2/token 은 Spring Authorization Server 기본 — 본 PR 에서 enable but 직접 사용 안 함 (후속 3rd-party 통합 PR). **결정 필요**.

### gap 카테고리 2. 엣지 케이스 미커버 (영향 중간)

4. **EC-19. JWT 서명 키 누락** — application.yml 의 `bts.auth.jwt.private-key-pem-path` 미설정 또는 파일 없음 → 기동 실패 with 명확한 에러 메시지. EC 추가.
5. **EC-20. Spring Authorization Server Bean 충돌** — 기존 SecurityConfig.securityFilterChain Bean 1개 vs Spring Authorization Server 자동 등록 1개 → 2개 충돌 가능. order(@Order(1)/(2)) 명시 + Authorization Server filter chain 분리. EC 추가.
6. **EC-21. refresh chain 무한 증가** — 14일 × 96 회/일 = 1344 row/sid. GC 필요. **결정 필요** (본 PR 포함 vs 후속 PR).
7. **EC-22. Session row 무한 증가** — 운영 1년 후 active + revoked 누적. GC 필요. **결정 필요** (본 PR 포함 vs 후속 PR).
8. **EC-23. Concurrent Refresh rotation** — 같은 refresh token 으로 두 요청 동시 — optimistic locking (§3 명시) 한쪽 401, 한쪽 200. EC-05 의 replay 와 다른 race window — EC 추가.
9. **EC-24. Provider 다중 등록** — ProviderRegistry 가 ProviderType 별 1개 또는 N개? findFor(Credential) 의 routing 우선순위. 본 PR 에서 Local/LDAP/PAT 각 1개 (총 3 Provider) — 우선순위 ProviderType.priority 활용 (SDD 19.2). 명시 필요 — FR 추가.

### gap 카테고리 3. 보안 누락 (영향 중간)

10. **CORS origin 미명시** — SPA (`apps/web`) 가 별도 origin 시 CORS 설정 필요. application.yml 환경별 (`dev: http://localhost:5173`, `prod: https://bts.example.com`). **결정 필요** (본 PR 포함).
11. **Refresh Token Cookie Path 일관성** — 4.1 Set-Cookie Path=/api/v1/auth/refresh 명시. logout 시 Cookie 만료 응답 Path 일관 필요 (/api/v1/auth 또는 둘 다). 명시.
12. **PKCE / state / nonce 검증** — /api/v1/auth/login 은 password grant wrapper 라 PKCE 불요. 3rd-party OAuth2 client 통합 시 표준 endpoint 의 PKCE 활성화. 본 PR scope 명시 (후속 PR 영역).

### gap 카테고리 4. API 누락 (영향 중간)

13. **GET /api/v1/auth/providers** (public, no auth) — UI 가 로그인 폼 그리려면 Provider 목록 (id, displayName, type) 즉시 필요. **본 PR 포함 추천**. API 4.7 추가.
14. **GET /api/v1/auth/sessions** + **DELETE /api/v1/auth/sessions/{sid}** — 사용자의 active session 조회 + 특정 device logout. 후속 PR (UI 의존).

### gap 카테고리 5. DM 누락 (영향 낮음)

15. **sessions.device_fingerprint 생성 책임** — Server 측 (User-Agent + IP 해시) vs Client 측 (fingerprint.js library). 추천. Server 측 (단순 + privacy). FR 추가.
16. **sessions.user_agent VARCHAR(512)** — 일부 UA 가 512 자 초과 가능. TEXT 또는 truncate 정책. 추천. truncate at 510 + ".." 표기.

### gap 카테고리 6. NFR 누락 (영향 중간)

17. **부하 한도** — 1,000 사용자 규모. peak 동시 로그인 가능성 (예. 사내 일과 시작 10분 내 200~300 동시). Argon2 memory=64MB × 300 concurrent = 19GB — 단일 호스트 부담. 추천. JMH 측정 + worker pool 크기 측정 (parallelism=4 의 영향).
18. **DB connection pool 크기** — Hikari maxPoolSize. application.yml 명시. 추천. dev 10 / prod 30 (Naver Cloud 단일 호스트 PostgreSQL).
19. **JWT key 재시작 zero-downtime** — 본 PR 키 1개 + env var path. docker-compose restart 시 동일 키 유지 (volume mount). 명시.

### gap 카테고리 7. 트랜잭션 경계 명확화 (영향 높음 — PR #6 learning 회귀 가드)

20. **SpringSecurityProviderAdapter 가 @Service 부착?** — 현재 코드 (PR #2 / #3) 미부착. SecurityConfig 가 @Bean 등록만. 본 PR 트랜잭션 경계가 SpringSecurityProviderAdapter 또는 LocalProvider 어느 쪽? 추천. LocalProvider / LdapProvider 가 @Service + @Transactional. Adapter 는 stateless 위임만. 명시.
21. **AuthController (신규)** — login / logout / refresh endpoint 가 @RestController. 내부 service 호출 시 트랜잭션 경계. AuthController 의 메서드는 @Transactional 부착 안 함 (service layer 책임). 명시.

### gap 카테고리 8. 결정 분기 미해소 (다음 단계)

22. **본 PR 안에 GC job (refresh chain / Session row) 포함 여부.** 추천. **별도 후속 PR**. 본 PR 의 monster scope 안정 우선. GC 정책 ADR 만 본 PR 에 메모.
23. **CORS 설정 본 PR 포함 여부.** 추천. 본 PR 포함 (UI 가 로그인 동작하려면 즉시 필요).

### 결정 필요 (Maxi 검토 — 묶음 결정)

| 항목 | 추천 | 본 PR scope 영향 |
|---|---|---|
| A) Spring Authorization Server OAuth2 client 등록 모델 | 자체 client 등록 (Spring Authorization Server 표준) | FR 추가 |
| B) JWT signing key 형식 | RSA PEM file + env var path | FR 추가 |
| C) /oauth2/token 표준 endpoint | enable but BTS wrapper (/api/v1/auth/login) 우선 사용 | FR 추가 |
| D) refresh chain GC + Session row GC | 별도 후속 PR | scope 안정 |
| E) Provider 목록 endpoint (GET /api/v1/auth/providers) | 본 PR 포함 | API 4.7 추가 |
| F) device_fingerprint 생성 책임 | Server 측 (User-Agent + IP 해시) | FR 추가 |
| G) CORS origin 환경별 설정 | 본 PR 포함 (dev/prod 분리) | FR 추가 |

7건 묶음 결정 → spec 본문 반영 완료 (FR-09-18 ~ FR-09-26 + API §4.7 + DM user_agent TEXT + NFR DB pool/부하/CORS + EC-19~EC-24 + 트랜잭션 경계 5건 추가).

---

<!-- SPEC READY FOR PLAN — sanity check 통과 + 7건 결정 반영 완료 -->
