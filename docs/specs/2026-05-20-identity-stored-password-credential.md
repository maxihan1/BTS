<!-- identity-access StoredPasswordCredential 엔티티 도입 스펙 — PoC #2 미완성 마무리 -->

# StoredPasswordCredential 엔티티 도입 — 스펙

> 출처. PR #4 사후 정리 점검 중 phantom 발견 — PoC #2 spec (`docs/specs/2026-05-20-identity-access-authn-poc.md §F1, §5`) 이 `local_credentials` 테이블 + Argon2 저장을 명세했으나 실제로는 `LocalCredentialService` 해시 계산 로직만 구현되고 DB 영속 계층 누락.
> 입력 자연어. "PR #4 사후 정리 작업 묶음 → phantom UserCredential 발견 → 정식 신규 entity 도입"
> BC. identity-access | type. auth | agent. security-engineer
> 선행 PR. #2 / #3 / #4 (모두 머지)
> 관련 ADR. `argon2id-parameters` (재사용), `authentication-provider-spi-naming` (정정 필요), `user-external-accounts-schema` (패턴 재사용)

## 1. 목표 (한 줄)

PoC #2 spec 이 명세했으나 미구현된 비밀번호 영속 저장 계층 (`local_credentials` 테이블 + JPA 엔티티 + Repository + Service 통합) 을 정식 도입.

## 2. 기능 요구사항 (FR)

### F1. V003 마이그레이션 — `local_credentials` 테이블

**Given** Flyway 마이그레이션 V003 작성 (PoC #2 spec §5 의 SQL 그대로 + algo_version 컬럼 추가).
**When** `./gradlew flywayMigrate` 실행.
**Then** PostgreSQL 에 `local_credentials` 테이블 생성. 컬럼. `user_id UUID PK FK → users.id ON DELETE CASCADE`, `password_hash TEXT NOT NULL`, `algo_version TEXT NOT NULL DEFAULT 'argon2id-v1'`, `created_at TIMESTAMPTZ NOT NULL DEFAULT now()`, `updated_at TIMESTAMPTZ NOT NULL DEFAULT now()`.

### F2. `StoredPasswordCredential` JPA 엔티티

**Given** Kotlin `@Entity` 클래스 `StoredPasswordCredential` 정의.
**When** Kotlin 코드에서 `StoredPasswordCredential(userId=..., passwordHash="...", algoVersion="argon2id-v1")` 객체 생성.
**Then** 5개 필드 (userId / passwordHash / algoVersion / createdAt / updatedAt) 노출. `equals`/`hashCode`/`toString` 은 `userId` 기반. `toString` 은 `passwordHash` 마스킹 (`***`). SPI `Credential` (sealed) 와 import / 명명 충돌 없음.

### F3. `StoredPasswordCredentialRepository` — 저장 / 조회 / 갱신 / 삭제

**Given** Spring `@Repository` JdbcTemplate 기반 (PR #4 의 `ExternalAccountRepository` 패턴 재사용, 본 PR 에서 INSERT...RETURNING + getObject 동시 적용).
**When 1 (저장 신규)** `save(StoredPasswordCredential)` 호출, `user_id` 행이 없음.
**Then** INSERT 1행, `created_at`/`updated_at` = now (). UNIQUE 제약 (PK = user_id) 자동 적용.
**When 2 (갱신)** `save(StoredPasswordCredential)` 호출, `user_id` 행이 이미 있음.
**Then** UPSERT (`INSERT ... ON CONFLICT (user_id) DO UPDATE SET password_hash, algo_version, updated_at`). 단일 SQL.
**When 3 (조회)** `findByUserId(uuid)` 호출.
**Then** `StoredPasswordCredential?` (Kotlin nullable). 없으면 `null`.
**When 4 (삭제)** users 테이블에서 user 삭제 → ON DELETE CASCADE 로 local_credentials 자동 삭제 (별도 메서드 불요). 단, 명시 삭제 메서드 `deleteByUserId(uuid)` 도 제공 (사용자 비밀번호만 제거하고 계정 유지하는 시나리오 — 비밀번호 리셋 이후 외부 IdP 만 사용).

### F4. `LocalCredentialService` 통합

**Given** 현재 `LocalCredentialService` 는 Argon2 해시 계산 로직만 (`encode(plain: CharArray): String`, `verify(plain: CharArray, hash: String): Boolean`).
**When** 본 PR 이 `Repository` 의존성 주입 + 새 메서드 추가.
**Then 1 (해시 + 저장)** `store(userId: UUID, plain: CharArray): StoredPasswordCredential` — encode → save → return entity. plain CharArray 는 finally 블록에서 wipe.
**Then 2 (검증 + 조회)** `verifyForUser(userId: UUID, plain: CharArray): Boolean` — findByUserId → 행 없으면 false (timing attack 방어. 빈 해시로 더미 verify 수행), 있으면 Argon2 verify 후 결과 반환.
**Then 3 (변경)** `rotate(userId: UUID, oldPlain: CharArray, newPlain: CharArray): Boolean` — verifyForUser(oldPlain) 통과 시 store(newPlain) 갱신, 실패 시 false (변경 거부). 기존 행 password_hash + updated_at 만 변경, created_at 보존.

### F5. ADR 정정 + 신규 ADR 작성

**Given** ADR `authentication-provider-spi-naming.md` 라인 68-73 의 phantom 가설 발견.
**When** 본 PR 구현 후 ADR 보강 단락 작성 + 신규 ADR 1건.
**Then** (1) `authentication-provider-spi-naming.md` 끝에 "2026-05-20 정정. UserCredential 은 PoC #2 미도입 phantom. 본 PR (#7 또는 후속 번호) 이 `StoredPasswordCredential` 이름으로 처음 도입." 단락 추가. (2) 신규 ADR `2026-05-20-stored-password-credential-schema.md` — V003 스키마 결정 (테이블명 / FK CASCADE / algo_version 사용 방식 / UPSERT 정책).

### F6. glossary / domain 노트 묶음 갱신 (수동 영역)

**Given** PR #3 ADR 가 약속한 glossary 추가 4건 (`Credential`/`Principal`/`AuthnResult`/`ProviderRegistry`) 미반영 + 본 PR 신규 1건 (`StoredPasswordCredential`).
**When** PR 머지 후 Maxi 가 `Maxi_wiki/BTS/glossary.md` + `Maxi_wiki/BTS/domain/identity-access.md` 수동 갱신.
**Then 1 (glossary)** 인증 섹션에 5건 추가 (`StoredPasswordCredential`, `Credential`, `Principal`, `AuthnResult`, `ProviderRegistry`).
**Then 2 (domain/identity-access.md)** 라인 17 `UserCredential` → `StoredPasswordCredential` 정정. ADR 링크 추가.

본 PR 은 코드 변경만 — Obsidian 갱신은 별도 단방향 sync (Phase 0 수동).

## 3. 비기능 요구사항 (NFR)

| 항목 | 임계 / 정책 |
|---|---|
| **평문 미저장** | 절대 규칙. `password_hash` 컬럼에만 Argon2id 인코딩 문자열 저장. 평문은 `CharArray` 로만 메모리 거치 + 사용 후 즉시 wipe |
| **algo_version 정책** | 컬럼은 두되 v1 단일 값 (`argon2id-v1`). 알고리즘 마이그레이션 시 Spring Security `upgrade-encoder` 패턴 적용 (verify 통과 후 새 알고리즘으로 재해시 저장). 본 PR 범위 외 — 후속 PR 위임 |
| **timing attack 방어** | `verifyForUser` 가 row 없는 경우도 dummy Argon2 verify 수행 (응답 시간 일정. PoC #2 NFR `Whoami p95 < 100ms` 와 동일 수준). dummy hash 는 `Argon2Params` companion 상수 `DUMMY_HASH` 에 정의 — 빈 비밀번호 1회 사전 인코딩 결과 + 코드 주석 |
| **로그 정책** | store / rotate / verifyForUser 어느 메서드도 password / hash / userId 로그 출력 금지. 성공/실패 boolean + ms latency 만 INFO. 예외 발생 시 stack trace 에 password 미포함 (Argon2 라이브러리도 안전, 본 PR 가 catch 후 message scrub) |
| **동시 변경 race** | 같은 user 가 두 비밀번호 변경 요청 동시 → UPSERT 가 마지막 INSERT 채택. 비밀번호 변경 빈도 한계 (rate limit) 는 향후 FR-AU-09 세션/토큰 PR 위임 — 본 PR 범위 외 |
| **password_hash 길이** | TEXT (가변), Argon2id 인코딩 표준 (`$argon2id$v=19$m=65536,t=3,p=4$<salt>$<hash>`) ≈ 96~120 chars. 절대 TRIM/CHECK 제약 추가 안 함 (알고리즘 마이그레이션 시 길이 변화) |
| **password 변경 이력** | **보존 안 함**. 단방향 갱신만 (UPSERT). 향후 GDPR 준수 + 동일 비밀번호 N회 재사용 금지 정책 필요 시 별도 `password_history` 테이블 후속 PR |
| **FK CASCADE GDPR** | `local_credentials.user_id → users.id ON DELETE CASCADE`. 사용자 삭제 시 비밀번호 행도 자동 삭제 (PR #4 user_external_accounts 패턴 동일) |
| **마이그레이션 무중단** | V003 = 신규 테이블만 (기존 테이블 변경 없음). 기존 컬럼 DROP / RENAME 없음. zero-downtime |
| **테스트 부담** | 단위 테스트 (해시 + Repo Stub) + 통합 테스트 (Testcontainers Postgres + Flyway). 부팅 시간 PR #4 와 동일 (60s 이내) |

## 4. API 인터페이스

본 PR 은 외부 REST 엔드포인트 미추가. **내부 메서드 API 만 정의** (LocalCredentialService 통합).

### Kotlin 시그니처 (메서드 단위)

```kotlin
// LocalCredentialService (기존 클래스, 본 PR 이 새 메서드 추가)
class LocalCredentialService(
    private val repo: StoredPasswordCredentialRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    // 기존 메서드 (변경 없음)
    fun encode(plain: CharArray): String
    fun verify(plain: CharArray, hash: String): Boolean

    // 신규 메서드 (본 PR)
    fun store(userId: UUID, plain: CharArray): StoredPasswordCredential
    fun verifyForUser(userId: UUID, plain: CharArray): Boolean
    fun rotate(userId: UUID, oldPlain: CharArray, newPlain: CharArray): Boolean
}

// StoredPasswordCredentialRepository (신규)
@Repository
class StoredPasswordCredentialRepository(private val jdbc: NamedParameterJdbcTemplate) {
    fun save(credential: StoredPasswordCredential): StoredPasswordCredential   // UPSERT (INSERT...ON CONFLICT)
    fun findByUserId(userId: UUID): StoredPasswordCredential?
    fun deleteByUserId(userId: UUID): Int   // 영향 행수 반환
}

// StoredPasswordCredential (신규 엔티티)
data class StoredPasswordCredential(
    val userId: UUID,
    val passwordHash: String,
    val algoVersion: String = "argon2id-v1",
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    override fun toString(): String = "StoredPasswordCredential(userId=$userId, passwordHash=***, algoVersion=$algoVersion, ...)"
}
```

OpenAPI 미추가. 비밀번호 흐름은 인증 흐름 (PoC #2 OIDC + 후속 FR-AU-09 SecurityFilterChain) 안에서만 사용.

## 5. 데이터 모델 변경

```sql
-- V003__local_credentials.sql

-- 비밀번호 영속 저장 (Local Provider 한정)
-- PoC #2 spec §5 가 명세했으나 미구현 — 본 PR 이 완성
-- 외부 IdP (Keycloak/LDAP/SAML/OIDC) 인증은 해당 외부 시스템이 저장, 본 테이블 미사용
CREATE TABLE local_credentials (
  user_id        UUID         PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  password_hash  TEXT         NOT NULL,
  algo_version   TEXT         NOT NULL DEFAULT 'argon2id-v1',
  created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
  updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON TABLE  local_credentials IS 'Local Provider 사용자의 Argon2id 해시 저장 (Keycloak/LDAP 인증과 무관)';
COMMENT ON COLUMN local_credentials.password_hash IS 'Argon2id 인코딩 문자열 ($argon2id$v=19$m=65536,t=3,p=4$<salt>$<hash>)';
COMMENT ON COLUMN local_credentials.algo_version IS '알고리즘 버전 (마이그레이션 추적용, 현재 argon2id-v1 단일)';

-- 인덱스. PK 단일로 충분 (user_id 만 조회). FK = users.id 가 CASCADE 처리
```

**FK 정책**.
- `user_id` → `users(id)` ON DELETE CASCADE — 사용자 삭제 시 비밀번호 자동 삭제 (GDPR)
- ON UPDATE 정책 없음 — users.id 는 UUID PK, 갱신 불가

**rollback 정책**. V003 rollback = `DROP TABLE local_credentials`. 데이터 손실 — Argon2 해시는 단방향이라 백업/복원만 가능. Phase 0 단계엔 rollback 시나리오 미적용 (개발 DB only).

## 6. 엣지 케이스

| # | 케이스 | 동작 |
|---|---|---|
| EC-01 | 동일 user 두 번 store() 호출 | 두 번째가 UPSERT 로 password_hash 덮어씀. created_at 보존, updated_at 갱신 |
| EC-02 | rotate() 시 old 비밀번호 불일치 | false 반환, DB 변경 없음. 호출자가 retry / lockout 정책 결정 (본 PR 범위 외) |
| EC-03 | verifyForUser() 시 user 없음 | dummy Argon2 verify 수행 후 false 반환 (timing attack 방어). 로그에 user 없음 정보 미출력 |
| EC-04 | users.id 삭제 시 | ON DELETE CASCADE 로 local_credentials 자동 삭제. 명시 호출 불요 |
| EC-05 | algo_version 미일치 (마이그레이션 미적용) | verifyForUser() 가 algo_version 무시하고 password_hash 의 `$argon2id$` prefix 만으로 verify. 알고리즘 변경 시 upgrade-encoder 패턴 후속 PR |
| EC-06 | 동시 rotate() 두 요청 | 마지막 UPSERT 채택. 비밀번호 변경 race 는 rare. rate limit 은 후속 PR 위임 |
| EC-07 | password_hash 길이 비정상 (DB 손상) | verify 시 Argon2 라이브러리 가 IllegalArgumentException → AuthnResult.Failure(INTERNAL_ERROR) 매핑. 본 PR 범위는 Service 단까지, AuthnResult 매핑은 SPI 어댑터 |

## 7. 제약 조건

- **PoC #2 OIDC / LDAP / SAML / OAuth Provider 회귀 없음** — 각 Provider 는 외부 시스템 자체 저장. 본 PR 의 `local_credentials` 테이블 미사용 확인
- **DEVELOPMENT.md §1.1 절대 규칙 유지** — 평문 비밀번호 저장 금지 + CharArray + finally wipe 패턴
- **DATA.md §6 트랜잭션 경계** — store / rotate 는 `@Transactional` 단일 경계. verify 는 read-only `@Transactional(readOnly=true)` 또는 transaction 없음 (조회만)
- **1 PR = 1 BC** — identity-access 단독. 다른 BC import 금지 (issue-tracking / notification 영향 없음)
- **TDD 강제** — 모든 task RED → GREEN → REFACTOR 순서. `/bts-impl` verifier 가 git log 검증
- **wave 병렬 dispatch dogfood** — `/bts-plan` 이 task 메타 형식 (agent / files / depends-on) 채움. 독립 task 끼리 wave 1 묶음 시 첫 dogfood 성공 사례

## 8. 측정 가능한 완료 기준

- [ ] V003 마이그레이션 파일 작성 + `./gradlew flywayMigrate` 통과
- [ ] `StoredPasswordCredentialRepository` 단위 테스트 (CRUD 4 메서드 × 정상 + 엣지 = 약 8 케이스)
- [ ] `LocalCredentialService` 신규 메서드 (store / verifyForUser / rotate) 단위 테스트 (각 정상 + 엣지)
- [ ] Testcontainers Postgres 통합 테스트 — V003 적용 후 save + findByUserId 왕복
- [ ] 회귀 검증. PoC #2 `KeycloakIntegrationTest` + PR #4 `LdapProvider*Test` 모두 통과
- [ ] ktlint + detekt 0 violations
- [ ] ADR 2건 (정정 1 + 신규 1) 작성 + plan 마지막 섹션 phantom learnings 등록
- [ ] PR #4 code-reviewer / verifier 가 SAVE-2/3 로 남긴 INSERT...RETURNING + UUID RowMapper 패턴을 본 PR Repository 가 그대로 적용 (이전 부채 해소)

## 9. 명시적 비-스코프

- LocalCredentialService 와 SPI `AuthenticationProvider` (PR #3) 연결 — SecurityFilterChain 통합은 FR-AU-09 후속 PR
- 비밀번호 변경 이력 보존 (password_history 테이블) — 향후 GDPR / 정책 필요 시
- 비밀번호 변경 rate limit / lockout — FR-AU-09 후속 PR
- 알고리즘 마이그레이션 (Argon2 파라미터 변경 시 upgrade-encoder) — Argon2 OWASP 갱신 시점 후속 PR
- escapeForLdapFilter / ExternalAccountRepository INSERT...RETURNING — 원래 정리 묶음의 다른 두 항목, 별도 chore PR 위임 (본 PR 정정 phantom 처리만)
- glossary.md / domain/identity-access.md 실제 갱신 — Obsidian 수동 영역, PR 머지 후 별도

## Brainstorming Check

✅ 통과 (1회 iteration — 직접 분석).

발견된 gap 2건 즉시 스펙 본문에 보강.
- **G1**. timing attack 방어용 dummy hash 값 미명시 → NFR 행 보강 (`Argon2Params.DUMMY_HASH` 상수 정의)
- **G2**. 로그 정책 NFR 누락 → NFR 행 신규 (password / hash / userId 로그 출력 금지)

검토한 추가 가능 gap (수용 안 함, 사유 명시).
- **R1**. `algo_version` 컬럼 redundancy — Spring Security `DelegatingPasswordEncoder` 가 `password_hash` prefix `$argon2id$` 로 알고리즘 식별 가능, 별도 컬럼 redundant. **그러나 명시 컬럼이 향후 알고리즘 마이그레이션 시 SQL 필터 (`WHERE algo_version = 'argon2id-v1'` 후 batch 재해시) 단순화. 보존 결정.** Phase 1+ 재검토
- **R2**. `rotate()` race condition 정밀 방어 (optimistic locking `updated_at` 비교) — 비밀번호 변경 race 는 rare + last-writer-wins 수용 가능. rate limit 도입 시 자동 해소. **본 PR 범위 외, FR-AU-09 위임**
- **R3**. `users.id` 삭제 외 사용자 username 변경 시 시나리오 — username 은 users 테이블 컬럼이고 본 PR 의 `local_credentials.user_id` PK 는 변경 영향 없음. **검토 종료**

grill-with-docs / office-hours / brainstorming 직접 분석 갈음 사유 (이전 3 PR 동일 패턴).
1. PoC #2 spec 이 이미 `local_credentials` 테이블 + Argon2 패턴 명세 — 본 PR 은 미완성 마무리
2. ADR 4건 (argon2id-parameters / authentication-provider-spi-naming / user-external-accounts-schema / csrf-cookie-mode) 이 도메인 결정 다수 봉인
3. 1인 + Auto mode 합리적 판단 — 모호한 영역 (`algo_version` 보존 등) 만 Maxi 확인 영역
