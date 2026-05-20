<!-- identity-access FR-AU-02 spec — LDAP/AD Provider 정식 구현 (D1~D5 백엔드 + DB) -->

# FR-AU-02 — LDAP/AD Provider 정식 구현 (스펙)

> 마스터플랜. `docs/plan/product/identity-access.md §2.2`
> 선행. PR #3 (FR-AU-01 SPI)
> 본 PR 스코프. **D1~D5 (백엔드 + DB) 만**. D6 (UI) + D7 (E2E) 는 별도 PR (정당화 §10)
> 관련 ADR. impl 단계에서 4건 작성 예정 (LDAP 이미지 / V002 스키마 / Lockout 위치 / 그룹 매핑)

## §1 사용자 시나리오 (Given-When-Then)

본 PR 의 외부 노출 인터페이스는 (1) Spring Security `LdapAuthenticationProvider` 가 SecurityFilterChain 에 등록되어 / (2) `POST /api/v1/auth/login` 엔드포인트 신규 추가가 검토되나, **본 PR 은 SPI 구현체 + DB 모델 + 통합 테스트만** 다루고 실제 로그인 엔드포인트는 D6 (UI) 단계 PR 에서 추가 (UI 가 호출할 endpoint 함께 정의가 정합).

따라서 본 PR 의 시나리오는 **테스트 시나리오**.

### S-01. LDAP 인증 성공 (자동 프로비저닝 X — 기존 사용자)

- **Given**. `users` 테이블에 `(id=u1, username=alice@bts.local)` 행 존재. `user_external_accounts(provider_id=p1, external_subject="uid=alice,ou=people,dc=bts,dc=local", user_id=u1)` 매핑 존재. OpenLDAP 컨테이너에 동일 DN + 비밀번호 `Test1234!`.
- **When**. `LdapProvider.authenticate(Credential.LdapBind("alice", "Test1234!"))` 호출.
- **Then**. `AuthnResult.Success(Principal(userId=u1, providerType=LDAP, displayName=alice, externalSubject="uid=alice,...dc=local"))`. `user_external_accounts.last_login_at` 갱신.

### S-02. LDAP 인증 성공 (자동 프로비저닝 — 첫 로그인)

- **Given**. `users` 테이블에 alice 행 없음. `user_external_accounts` 도 매핑 없음. OpenLDAP 에 `alice` 존재.
- **When**. `LdapProvider.authenticate(Credential.LdapBind("alice", "Test1234!"))` 호출.
- **Then**. `users` 에 새 행 INSERT (`username` = LDAP `mail` 또는 `uid` 폴백). `user_external_accounts` 매핑 INSERT. `Success(Principal)` 반환. 두 INSERT 는 **단일 트랜잭션** (DATA.md §6).

### S-03. LDAP 인증 실패 — 잘못된 비밀번호

- **Given**. alice 존재. password 입력 = "wrong".
- **When**. `LdapProvider.authenticate(...)` 호출.
- **Then**. `Failure(INVALID_CREDENTIALS)`. **PII 로깅 금지** — 로그에 password / DN 미출력 (DEVELOPMENT.md §1.2).

### S-04. LDAP 인증 실패 — 사용자 미존재

- **Given**. OpenLDAP 에 `bob` 미존재.
- **When**. `LdapProvider.authenticate(Credential.LdapBind("bob", "anything"))` 호출.
- **Then**. `Failure(INVALID_CREDENTIALS)`. 사용자 enumeration 방지 — INVALID_CREDENTIALS / USER_NOT_FOUND 구분 미노출.

### S-05. LockoutPolicy — N회 실패 시 잠금

- **Given**. `authn_providers.config.lockout_policy = {max_attempts:5, lockout_minutes:15, scope:"per-user-per-provider"}`. alice 가 직전 5회 실패.
- **When**. 6회째 시도 (비밀번호 맞아도).
- **Then**. `Failure(ACCOUNT_LOCKED)`. `user_external_accounts.locked_until` = now + 15분. 15분 경과 후 자동 해제.

### S-06. LDAP 서버 장애

- **Given**. OpenLDAP 컨테이너 중지 (네트워크 timeout).
- **When**. `LdapProvider.authenticate(...)` 호출.
- **Then**. `Failure(PROVIDER_UNAVAILABLE)` (5초 timeout 후). 예외 throw 금지 — Provider contract.

### S-07. 그룹 정보 저장 (권한 매핑 자체는 FR-PM-01 위임)

- **Given**. alice 가 OpenLDAP 그룹 `cn=engineers,ou=groups,dc=bts,dc=local` 멤버.
- **When**. 로그인 성공.
- **Then**. `user_external_accounts.groups` (JSONB array) = `["cn=engineers,ou=groups,dc=bts,dc=local"]`. **권한 매핑은 본 PR 범위 외**, FR-PM-01 가 group → role 매핑 처리.

## §2 기능 요구사항 (FR)

### FR-1. `Credential.LdapBind` sealed 변종 추가

`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/spi/Credential.kt` 에 sealed 변종 추가.

```kotlin
sealed interface Credential {
    data class UsernamePassword(...) : Credential   // PR #3 기존
    data class Pat(...) : Credential                 // PR #3 기존
    data class LdapBind(                              // 본 PR 추가
        val username: String,
        val password: CharArray,
    ) : Credential
}
```

- `equals/hashCode` CharArray 내용 비교 (PR #3 패턴 동일)
- KDoc 에 `password.fill(' ')` wipe contract 명시 (PR #3 패턴 동일)

PR #3 ADR `2026-05-20-authentication-provider-spi-naming.md` line 51~57 약속 이행.

### FR-2. `LdapProvider` 구현

위치. `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/provider/ldap/LdapProvider.kt`.

- `AuthenticationProvider` SPI 구현. `type = ProviderType.LDAP`. `supports(c) = c is Credential.LdapBind`.
- 내부에서 Spring Security `LdapAuthenticationProvider` 호출 (어댑터 패턴). 직접 LDAP 통신 코드 작성 X.
- `authenticate(credential)` 흐름.
  1. `LdapConfig` 로드 — `authn_providers` 테이블 (type=LDAP, enabled=true) 단일 행 가정 (FR-AU-06 다중 활성화는 후속 PR)
  2. LockoutPolicy 검사 — `user_external_accounts.locked_until > now` 면 `Failure(ACCOUNT_LOCKED)`
  3. Spring `LdapAuthenticationProvider.authenticate` 호출
  4. 성공 — 자동 프로비저닝 + 그룹 정보 저장 + `last_login_at` 갱신 + lockout 카운터 reset. 단일 트랜잭션
  5. 실패 — lockout 카운터 +1. `failed_attempts >= max_attempts` 면 `locked_until` 갱신
  6. 예외 (LDAP 서버 장애) — `Failure(PROVIDER_UNAVAILABLE)`. 예외 swallow X (로그는 stack trace 없이 message만)

- `@Component` 부착 (Spring DI 자동 수집, PR #3 `ProviderRegistry` 가 발견)
- `@Profile` 미부착 (production 활성)
- **bind password 로딩 시점**. application startup 시점 1회 `System.getenv(bindPasswordEnv)` 호출 후 메모리 보관 (cache). 환경변수 변경 시 application restart 필수 (명시 결정 — 매 인증마다 syscall 회피 + Spring 표준 패턴 일치)
- **`authn_providers` 행 0개 처리**. lazy init. 첫 인증 시도 시 `findOneByType(LDAP, enabled=true)` 결과 null → `Failure(PROVIDER_UNAVAILABLE)` + 1회 WARN 로그 ("LDAP provider not configured"). 부팅 실패 X (다른 Provider 만으로 동작 가능해야 함)

### FR-3. `LdapConfig` VO

위치. `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/provider/ldap/LdapConfig.kt`.

```kotlin
data class LdapConfig(
    val serverUrl: String,             // ldap://openldap:389
    val baseDn: String,                 // dc=bts,dc=local
    val bindDn: String,                 // cn=admin,dc=bts,dc=local
    val bindPasswordEnv: String,        // BTS_LDAP_BIND_PASSWORD (env var name, 값 X)
    val userSearchBase: String,         // ou=people
    val userSearchFilter: String,       // (uid={0}) — {0} 은 입력 username
    val groupSearchBase: String,        // ou=groups
    val groupSearchFilter: String,      // (member={0}) — {0} 은 user DN
    val lockoutPolicy: LockoutPolicy,
    val userMailAttribute: String = "mail",
    val userDisplayNameAttribute: String = "cn",
)

data class LockoutPolicy(
    val maxAttempts: Int = 5,
    val lockoutMinutes: Int = 15,
    val scope: LockoutScope = LockoutScope.PER_USER_PER_PROVIDER,
)

enum class LockoutScope { PER_USER_PER_PROVIDER, GLOBAL }
```

- `authn_providers.config` 컬럼 (JSONB) 에 직렬화. Jackson 으로 자동 처리.
- **`bindPassword` 자체는 저장 X** — env var name 만 저장. 런타임에 `System.getenv(bindPasswordEnv)` 호출 (DEVELOPMENT.md §1.1)

### FR-4. Flyway V002 마이그레이션 (db-engineer 영역)

위치. `backend/modules/identity-access/src/main/resources/db/migration/V002__authn_providers_and_user_external_accounts.sql`.

```sql
-- 인증 공급자 메타데이터 (LDAP/SAML/OIDC 설정 저장)
CREATE TABLE authn_providers (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    type        VARCHAR(16) NOT NULL,    -- LOCAL/LDAP/SAML/OIDC
    name        VARCHAR(64) NOT NULL UNIQUE,
    config      JSONB NOT NULL,
    enabled     BOOLEAN NOT NULL DEFAULT true,
    sort_order  INTEGER NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_authn_providers_type_enabled ON authn_providers(type, enabled) WHERE enabled = true;

-- User × External Identity 매핑 (LDAP DN, OIDC sub, SAML NameID)
CREATE TABLE user_external_accounts (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider_id        UUID NOT NULL REFERENCES authn_providers(id) ON DELETE RESTRICT,
    external_subject   VARCHAR(512) NOT NULL,
    user_id            UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    groups             JSONB NOT NULL DEFAULT '[]'::jsonb,
    failed_attempts    INTEGER NOT NULL DEFAULT 0,
    locked_until       TIMESTAMPTZ,
    last_login_at      TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (provider_id, external_subject)
);

CREATE INDEX idx_uea_user_id ON user_external_accounts(user_id);
CREATE INDEX idx_uea_provider_subject ON user_external_accounts(provider_id, external_subject);
```

**ON DELETE 정책 결정 (ADR 작성 항목)**.
- `authn_providers ← user_external_accounts` = RESTRICT (Provider 삭제 시 매핑 남으면 잠재 미아 데이터 보호)
- `users ← user_external_accounts` = CASCADE (User 삭제 시 매핑 동반 삭제)

### FR-5. ExternalAccount 엔티티 + Repository

위치. `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/provider/ldap/ExternalAccount.kt`, `ExternalAccountRepository.kt`.

- Spring Data JDBC 사용 (PR #3 PoC 패턴과 정합 — Jackson + JDBC 만, JPA 미사용)
- 또는 plain JDBC + Repository 패턴. impl 단계 결정.
- 기본 query.
  - `findByProviderIdAndExternalSubject(...)` → `Optional<ExternalAccount>`
  - `insertWithUserId(...)` — 단일 트랜잭션 (`User` 신규 + 매핑 동시 INSERT)
  - `incrementFailedAttempts(...)`, `resetFailedAttempts(...)`, `markLockedUntil(...)`
  - `updateLastLoginAt(...)`

### FR-6. Testcontainers OpenLDAP 통합 테스트

위치. `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/provider/ldap/LdapProviderIntegrationTest.kt`.

- `@Testcontainers @SpringBootTest`. OpenLDAP 컨테이너 (이미지 ADR 결정).
- 초기 데이터. ldif 파일 (`infra/ldap/seed.ldif`) — alice + bob + engineers 그룹.
- 시나리오 S-01 ~ S-07 모두 통합 테스트로 검증.
- `@DynamicPropertySource` 로 OpenLDAP container host/port 를 Spring config 에 주입.

## §3 비기능 요구사항 (NFR)

- **로그인 응답 p95 < 500ms** (마스터플랜 §2.2 측정값 표). Testcontainers 환경 측정값 plan T7 에 기록.
- **PII 로깅 금지**. DN / password / mail 모두 마스킹 (DEVELOPMENT.md §1.2). `LdapProvider` 의 로그는 `Failure(reason)` 만 (사용자 식별자 없음).
- **트랜잭션 경계**. 자동 프로비저닝 (`users` INSERT + `user_external_accounts` INSERT) 단일 트랜잭션 (`@Transactional`). 부분 성공 금지 (DATA.md §6).
- **bind password env var**. `BTS_LDAP_BIND_PASSWORD` 환경변수. yaml 에 평문 저장 금지 (DEVELOPMENT.md §1.1).
- **테스트 커버리지**. `provider/ldap/` 패키지 90% 이상. `LdapProvider.authenticate` 모든 분기 (S-01~S-07) 커버.
- **`groups` JSONB 인덱스 없음**. 본 PR 그룹 검색 query 없음. 권한 매핑 (FR-PM-01) 도입 시 GIN 인덱스 추가 검토. 본 PR 안전 default = 인덱스 없음.
- **PoC #2 + PR #3 회귀 격리**. `LdapProvider` 가 `@Component` 로 자동 등록되면 PoC #2 의 `KeycloakIntegrationTest` + PR #3 의 `ProviderRegistryTest` 컨텍스트에 LDAP 도 함께 로드됨. 두 테스트가 `authn_providers` 행 0개 가정 → lazy init 분기로 안전 (`Failure(PROVIDER_UNAVAILABLE)`). 그러나 ProviderRegistryTest 의 가짜 Provider 카운트가 영향받을 수 있음. impl 단계 — `@TestConfiguration` 으로 `LdapProvider` 제외 또는 ProviderRegistryTest 의 assertion 갱신 (필요시).
- **빌드 시간**. OpenLDAP Testcontainers cold start < 30 초 (Keycloak 60초 한계와 별개로 더 빠른 target).
- **의존성**. Spring Security LDAP starter (`spring-boot-starter-data-ldap` 또는 `spring-security-ldap`) 신규 도입. build.gradle.kts 추가. 의존성 카탈로그 미사용 패턴 일관성.

## §4 API 인터페이스

본 PR REST 변경 0건. `/api/v1/auth/login` 엔드포인트는 D6 (UI) PR 에서 추가 (UI 가 호출할 endpoint 함께 정의가 정합).

`LdapProvider` 는 Spring DI 로 `ProviderRegistry` 에 자동 등록. SecurityFilterChain 통합은 FR-AU-09 PR (PR #3 spec 일치).

## §5 데이터 모델 변경

FR-4 의 V002 마이그레이션. 신규 테이블 2개. 기존 테이블 변경 0건.

**PoC #2 회귀 위험**. PoC #2 의 V001 (`users` 테이블) 은 본 PR 미변경. `user_external_accounts` 가 `users.id` FK 만 추가 — 기존 데이터 무영향.

## §6 엣지 케이스

| ID | 케이스 | 처리 |
|---|---|---|
| E1 | LDAP 서버에는 alice 있는데 `user_external_accounts` 매핑 X + `users` 에도 동일 username 없음 | 자동 프로비저닝 (S-02). 단일 트랜잭션 |
| E2 | LDAP 서버에는 alice + `users` 에 동일 username 있는데 `user_external_accounts` 매핑 X | 위험 케이스. 다른 사용자의 username 충돌 가능. **Failure(INVALID_INPUT) 또는 ADMIN_REVIEW_REQUIRED**. ADR 결정. 본 PR 안전 default = Failure |
| E3 | `LdapConfig.bindPasswordEnv` 환경변수 미설정 | 부팅 시점 Failure. `LdapProvider` 가 application startup 시 env var 존재 확인 (lazy init X) — 부팅 실패가 안전 |
| E4 | LDAP 그룹이 nested (그룹의 그룹) | 본 PR 범위 외. 1-depth 그룹만 추출. impl 단계 KDoc 명시 |
| E5 | `external_subject` 변경 (LDAP 측에서 DN 변경) | 본 PR 미지원. ADMIN_REVIEW. ADR 노트 |
| E6 | `authn_providers.config` JSON 스키마 검증 | impl 단계 — Jackson deserialization 실패 시 부팅 실패 + Bean Validation. JSON Schema 외부 검증은 후속 PR |
| E7 | `last_login_at` 갱신 vs lockout 카운터 reset 동시성 | 단일 트랜잭션 + 비관적 락 (`SELECT FOR UPDATE`). impl 단계 정확화 |
| E8 | LDAP 그룹 목록이 매우 큼 (1000+) | JSONB 컬럼 크기 제한 검토. PostgreSQL TOAST 처리 정상 동작 가정. impl 단계 측정 |

## §7 제약 조건

- DEVELOPMENT.md §1 NEVER-1 (평문 비밀번호) — bind password env var. LDAP 측 사용자 비밀번호는 LDAP 서버 책임 (BTS DB 미저장)
- §1.2 PII 로깅 — `Principal.toString` PR #3 마스킹 그대로 활용. `LdapConfig.toString` 도 bindDn / config 마스킹 추가
- §1.4 인증 우회 — 본 PR REST 엔드포인트 추가 0건. SecurityFilterChain 변경 0건
- §1.5 CSRF — 본 PR 영향 없음
- §1.6 입력 검증 — `LdapBind.username` 길이 제한 (256자) + LDAP injection 방어 (특수문자 escape). Spring Security `LdapAuthenticationProvider` 가 이미 처리하지만 BTS 측 사전 검증 추가
- DATA.md §6 트랜잭션 경계 — 자동 프로비저닝 단일 트랜잭션 강제
- DATA.md §2 이슈키 영속성 — 해당 없음 (인증 영역)
- TDD 강제 — RED → GREEN → REFACTOR

## §8 측정 가능한 완료 기준

- [ ] `Credential.LdapBind` sealed 변종 추가 + equals/hashCode + KDoc
- [ ] `LdapConfig` + `LockoutPolicy` + `LockoutScope` VO 정의 + Jackson 직렬화 검증
- [ ] `LdapProvider` 구현 + 7 시나리오 (S-01~S-07) 통합 테스트 모두 통과
- [ ] V002 마이그레이션 작성 + Flyway clean state 부터 적용 검증
- [ ] `ExternalAccount` + Repository (Spring Data JDBC) 구현 + 단위 테스트
- [ ] OpenLDAP Testcontainers 통합 + `infra/ldap/seed.ldif` 작성
- [ ] PoC #2 `KeycloakIntegrationTest` 회귀 없음 확인
- [ ] PR #3 `ProviderRegistryTest` 회귀 없음 (LdapProvider 가 Registry 에 자동 등록되는지 검증 추가)
- [ ] `./gradlew test ktlintCheck detekt` 모두 통과
- [ ] ArchUnit 룰 (PR #3) 그대로 통과 — `provider/ldap/` 패키지 Spring Web 비결합 (data layer + ldap layer 만 허용)
- [ ] 신규 ADR 4건 작성 (impl 단계)

## §9 제외 (비-스코프)

- **D6 (로그인 폼 UI)** — 별도 PR 위임. 정당화 §10. designer + frontend-engineer + DESIGN.md 초기 생성 (design-consultation) 트리거.
- **D7 (Playwright E2E)** — D6 PR 에 포함 (UI 가 있어야 E2E 의미). qa-engineer.
- **`POST /api/v1/auth/login` 엔드포인트** — UI 가 호출할 endpoint. D6 PR.
- **FR-AU-06 다중 Provider 동시 활성화** — 본 PR 은 LDAP 1개 활성 가정. `idx_authn_providers_type_enabled` 인덱스가 추후 확장 대비.
- **FR-AU-07 도메인 자동 라우팅** — `@bts.local` → LDAP / `@external.com` → OIDC 같은 라우팅. 별도 FR.
- **FR-AU-08 계정 통합 (Account Linking)** — 이미 다른 Provider 로 가입한 사용자의 LDAP 매핑 추가. 별도 FR.
- **FR-PM-* 권한 매핑** — 본 PR 은 그룹 정보 저장만. group → role 매핑은 FR-PM-01 영역.
- **`user_credentials` (PoC #2 DB 엔티티) → `StoredPasswordCredential` 리네임** — 별도 refactor PR

## §10 PR 분할 정당화

**D1~D5 (백엔드 + DB) 본 PR, D6 + D7 (UI + E2E) 별도 PR**.

근거.
1. **컨텍스트 폭주 회피**. design-consultation (BTS 첫 DESIGN.md 초기 생성, ~30분 + 디자인 시스템 결정) + design-shotgun (4종 변형) + designer agent + frontend-engineer agent = 본 PR 면적이 백엔드만의 2배 이상. 1세션 처리 불안정.
2. **디자인 시스템 신중도**. BTS 1,000명 협업 도구의 첫 UI. shadcn/ui + Radix + Tailwind v4 토큰 결정은 LDAP PR 에 끼워서 빠르게 정할 사안 아님. 별도 디자인 PR 가치 큼.
3. **백엔드 자체 가치**. D1~D5 만으로도 LDAP 인증 SPI 완성 + DB 모델 + 통합 테스트 = `ProviderRegistry.findFor(LdapBind(...))` 가 production 동작 가능. UI 가 없어도 backend integration test 로 검증 가능.
4. **위험 분리**. D1~D5 = security-engineer + db-engineer 영역 (보안 폭발 반경 ↑). D6 = designer + frontend-engineer (시각/접근성 영역). 한 PR 에 묶으면 코드 리뷰 시 두 영역 동시 검증 부담.
5. **마스터플랜 정합**. 마스터플랜 §2.2 의 D1~D7 분해 자체가 단계별 책임 분리 의도. 한 PR 묶음 강제 X.

후속 PR 흐름.
- PR #4 (본). D1~D5 백엔드 + DB
- PR #5. D6 — design-consultation (DESIGN.md) → design-shotgun (4종) → designer (스펙) → frontend-engineer (구현). `POST /api/v1/auth/login` endpoint 추가
- PR #6. D7 — qa-engineer Playwright E2E (LDAP 로그인 시나리오 + S-01~S-07 중 E2E 가능한 것)

Maxi 확정 필요 — 게이트 1 에서 AskUserQuestion 으로 명시 (D1~D7 일괄 의도 인지하고 분할 권장 사유 제시).
