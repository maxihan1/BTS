<!-- identity-access §1 AuthN PoC 스펙 — Given/When/Then + 엣지 + NFR -->

# identity-access §1 AuthN PoC 스펙

> 출처. 마스터플랜 [docs/plan/product/identity-access.md](../../../docs/plan/product/identity-access.md) §1 "기술 검증 (AuthN Provider + Keycloak PoC)"
> 입력 자연어. "identity-access §1 AuthN PoC 시작 — Spring Boot + Spring Security + Argon2 + Keycloak 컨테이너"
> BC. identity-access | type. auth | agent. security-engineer

## 1. 목표 (한 줄)

BTS 첫 백엔드 코드 도입. 6 항목 기술 검증 통과로 identity-access BC 정식 구현 진입 게이트 통과.

## 2. 6 PoC 항목 (FR)

마스터플랜 §1 그대로. 각 항목에 Given-When-Then.

### F1. AuthenticationProvider 인터페이스 + Argon2id 패스워드 해싱

**Given** 사용자가 가입 시 평문 비밀번호 `Test1234!`를 제공.
**When** `LocalCredentialService.create(plain)` 호출.
**Then** DB `local_credentials.password_hash`에 Argon2id 해시(접두사 `$argon2id$`, `m=65536,t=3,p=4`) 저장. 평문은 메모리에서도 즉시 폐기 (변수 nullable). 동일 평문으로 `verify(plain)` 호출 시 `true`, 다른 평문 `false`.

### F2. OIDC Authorization Code + PKCE (Keycloak 25)

**Given** Keycloak 컨테이너에 `bts` realm + `bts-web` 클라이언트 (Public, PKCE 강제) import.
**When** 사용자가 `/oauth2/authorization/keycloak` 진입 → Keycloak 로그인 → callback.
**Then** Spring Security가 `access_token` (JWT) + `refresh_token` 발급. `SecurityContextHolder`에 `OAuth2AuthenticationToken` 저장. `/api/v1/users/me/whoami` 호출 시 200 + 사용자 정보.

### F3. Keycloak realm import 스크립트

**Given** `infra/keycloak/realm-bts.json` 파일에 realm + bts-web 클라이언트 + 테스트 사용자 (`alice` / `Test1234!`) 정의.
**When** Keycloak 컨테이너 기동 시 `--import-realm` 옵션으로 자동 import.
**Then** Keycloak Admin Console `http://localhost:8080`에서 `bts` realm 확인 가능. `alice` 로그인 가능.

### F4. Spring Security 필터 체인 — 보호 엔드포인트 1개

**Given** `/api/v1/users/me/whoami` 엔드포인트 (인증 필요).
**When** 무인증 GET 호출.
**Then** HTTP 401 + JSON `{"error": "unauthorized"}`. 응답 헤더에 `WWW-Authenticate: Bearer` 포함.
**When** F2의 JWT로 GET 호출.
**Then** HTTP 200 + `{"username": "alice", "email": "alice@bts.local"}`.

### F5. CSRF 토큰 검증 (DEVELOPMENT.md §1.5)

**Given** `CookieCsrfTokenRepository.withHttpOnlyFalse()` 설정 + `CsrfTokenRequestAttributeHandler` (SPA 호환).
**When** 인증된 사용자가 `POST /api/v1/users/me/preferences` (가상 엔드포인트 — F4와 별개 검증용)에 CSRF 토큰 없이 요청.
**Then** HTTP 403 + JSON `{"error": "csrf_token_missing"}`.
**When** 동일 요청에 유효 CSRF 토큰 (Cookie `XSRF-TOKEN` + Header `X-XSRF-TOKEN`) 포함.
**Then** HTTP 200.

### F6. Testcontainers Keycloak 통합 테스트

**Given** JUnit5 `@Testcontainers` + Keycloak 25 컨테이너 (realm-bts.json 마운트).
**When** `@SpringBootTest` 컨텍스트 부팅 → MockMvc로 F4 시나리오 실행.
**Then** 테스트 통과 (무인증 401, JWT 200). `./gradlew :backend:test` 1회 통과.

## 3. 비기능 요구사항 (NFR)

| 항목 | 임계 |
|---|---|
| Argon2id 파라미터 | `memory=65536KB (64MB), iterations=3, parallelism=4` (OWASP 2024 권장) |
| Argon2id 해싱 시간 | 100ms ± 50ms (단일 코어, 사용자 체감 영향 최소) |
| 로그인 응답 (Keycloak 통합 포함) | p95 < 800ms |
| Whoami 엔드포인트 응답 | p95 < 100ms |
| Testcontainers 부팅 + 테스트 | < 60s (CI 부담) |
| Spring Boot 어플리케이션 부팅 | < 10s |
| `./gradlew build` 전체 | < 60s |

## 4. API 인터페이스

PoC라 최소.

| 메서드 | 경로 | 인증 | 응답 |
|---|---|---|---|
| GET | `/oauth2/authorization/keycloak` | 비인증 | 302 Redirect → Keycloak |
| GET | `/login/oauth2/code/keycloak` | 비인증 (callback) | 302 Redirect → home |
| GET | `/api/v1/users/me/whoami` | JWT | 200 + 사용자 JSON |
| POST | `/api/v1/users/me/preferences` (CSRF 검증 시연용) | JWT + CSRF | 200 |

OpenAPI 스펙은 PoC 단계 미작성. Phase 1 정식 구현 시 `springdoc-openapi` 도입.

## 5. 데이터 모델

PoC라 최소 테이블 1개.

```sql
-- backend/modules/identity-access/src/main/resources/db/migration/V001__init_identity_access.sql
CREATE TABLE local_credentials (
  user_id        UUID PRIMARY KEY,
  password_hash  TEXT NOT NULL,     -- Argon2id encoded (예. $argon2id$v=19$m=65536,t=3,p=4$...)
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Phase 1 진입 시 users, sessions, user_external_accounts 테이블 추가 예정
```

**주의**. PoC라 `users` 테이블은 만들지 않음. F2 OIDC 흐름에서 Keycloak이 ID 발급, BTS는 JWT claim 만 신뢰 (`sub` = Keycloak user UUID). DB users 테이블은 마스터플랜 §2.5 (FR-AU-05 로컬 계정) 시 도입.

F1 Argon2 검증은 단위 테스트로만 (`LocalCredentialServiceTest`). 통합 테스트엔 미포함.

## 6. 엣지 케이스

| # | 케이스 | 기대 동작 |
|---|---|---|
| E1 | Argon2 verify 시 잘못된 해시 형식 | 예외 → 401, 로그에 "invalid hash format" (해시값 자체는 PII라 로그 금지 — DEVELOPMENT.md §1.2) |
| E2 | Keycloak 컨테이너 미기동 시 OIDC 진입 | 502 Bad Gateway 또는 적절한 에러 페이지 (사용자에게 평문 stack trace 노출 금지) |
| E3 | JWT 만료 후 whoami 호출 | 401 + `WWW-Authenticate: Bearer error="invalid_token"` |
| E4 | CSRF 토큰 변조 (Cookie ≠ Header) | 403, 로그에 IP + UA 기록 (감사) |
| E5 | OIDC state 파라미터 변조 | Spring Security 자동 차단 → 401 |
| E6 | 동일 비밀번호로 두 번 가입 시도 (가상 — PoC엔 가입 흐름 없음) | (F1 검증은 단위 테스트만, 통합 흐름 없음 — 엣지 미발생) |
| E7 | `/api/v1/users/me/whoami` 무인증 (Authorization 헤더 없음) | 401, Spring Security 기본 401 응답 |
| E8 | Testcontainers Keycloak 부팅 타임아웃 | 테스트 fail + Maxi 알림. 60s 한계 확장 검토 |

## 7. 제약 조건

### 절대 규칙 (DEVELOPMENT.md §1.1~§1.6 보안)

- §1.1 DB 평문 비밀번호 저장 금지 → Argon2id 적용 (F1)
- §1.2 로그에 PII 출력 금지 → Pino logger redact 룰 (`password`, `password_hash`, `token`, `refresh_token`)
- §1.4 인증 없는 엔드포인트 추가 금지 → Spring Security 필터 체인 (F4)
- §1.5 CSRF 검증 비활성화 금지 → CookieCsrfTokenRepository 활성 (F5)
- §1.6 검증 안 된 입력으로 명령 실행 금지 → konform 입력 검증 (PoC 단계 최소)

### 의존성 카탈로그 준수 (DEVELOPMENT.md §1.16)

도입 라이브러리는 모두 `docs/poc/dependencies.md`에 명시된 것만.

| 라이브러리 | 출처 §1.x |
|---|---|
| `spring-boot-starter-web`, `-security`, `-oauth2-client`, `-oauth2-resource-server` | §2.1, §2.3 |
| `de.mkammerer:argon2-jvm` | §2.3 |
| `org.testcontainers:testcontainers`, `:postgresql`, `:junit-jupiter` | §2.5 |
| `spring-security-test` | §2.5 |
| `org.jooq:jooq` (Phase 1+ 사용, PoC엔 Spring JdbcTemplate만) | §2.2 |
| `org.flywaydb:flyway-core`, `-database-postgresql` | §2.2 |

**카탈로그에 없는 라이브러리는 Maxi 확인 필수** — 발생 시 작업 중단.

### BC 격리

이 PoC는 identity-access BC 단독. 다른 BC import 금지. notification-dashboard 등 알림 BC와 통신 시 pgmq 이벤트 발행 (PoC엔 미발생).

## 8. 측정 가능한 완료 기준

마스터플랜 `docs/plan/product/identity-access.md §1 기술 검증 체크박스 6개` 모두 통과 +.

- [ ] `./gradlew :backend:test` 통과 (Testcontainers 부팅 포함)
- [ ] `./gradlew :backend:bootRun` 후 `curl http://localhost:8080/api/v1/users/me/whoami` 401 응답
- [ ] Keycloak `http://localhost:8080` (또는 다른 포트 매핑) Admin Console 접속 + `bts` realm 확인
- [ ] OIDC 브라우저 흐름 1회 완료 (수동 검증) — `alice` 로그인 후 whoami 200
- [ ] NFR §3 측정값 plan 파일에 기록
- [ ] DEVELOPMENT.md §1.1~§1.6 자가 점검 통과 (코드리뷰)

## 9. 범위 외 (이번 PR 제외)

- Phase 1 정식 구현 (FR-AU-01~10): 본 PoC 후속 PR.
- 2FA (FR-MF-01~05): identity-access §3 별도 PoC + 정식.
- 권한 가드 (FR-PM-01~07): identity-access §4 별도.
- 사용자 가입 UI: F1 Argon2 검증만, 가입 흐름은 미구현.
- LDAP/SAML Provider: F2 OIDC 흐름 1개만 검증, 나머지 Provider는 Phase 1 정식.
- 감사 로그 (FR-AU-10): PoC 미포함.
- 세션 강제 종료, 신뢰 디바이스: 별도.
