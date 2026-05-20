<!-- ADR — local_credentials 테이블 + StoredPasswordCredential 엔티티 스키마 결정 -->

# ADR — local_credentials 스키마 결정

**일자**. 2026-05-20
**상태**. Accepted
**관련 PR**. #6 (`auth/identity-stored-password-credential`)
**작성자**. Maxi + Claude (security-engineer)

## 컨텍스트

PoC #2 가 명세 (`docs/specs/2026-05-20-identity-access-authn-poc.md §F1, §5`) 한 `local_credentials` 테이블 + Argon2 저장 패턴이 미구현이었음. PR #4 사후 정리 점검 중 phantom `UserCredential` 발견 (`authentication-provider-spi-naming.md` 정정 단락 참조). 본 PR 이 PoC 미완성 마무리 + 처음부터 `StoredPasswordCredential` 이름으로 도입.

## 선택지

### 테이블명. `local_credentials` vs `user_credentials`

- **`local_credentials` (채택)** — PoC spec 채택 표기. Local Provider 한정 의미 명확. 외부 IdP (LDAP / Keycloak / SAML) 인증은 본 테이블 미사용
- `user_credentials` — 더 일반적이지만 외부 IdP 흐름과 헷갈림 (LDAP 사용자도 "credential" 보유)

### algo_version 컬럼 vs prefix-only

- **algo_version 보존 (채택)** — Spring Security `DelegatingPasswordEncoder` 는 password_hash prefix (`$argon2id$`) 로 알고리즘 식별 가능, 컬럼 redundant. 그러나 향후 알고리즘 마이그레이션 시 `WHERE algo_version = 'argon2id-v1'` 후 batch 재해시 SQL 단순화. trade-off 수용
- prefix-only — 더 simple 하지만 batch 마이그레이션 SQL 복잡 (`SUBSTRING(password_hash, ...)` 패턴)

### UPSERT 단일 SQL vs INSERT/UPDATE 분리

- **UPSERT (채택)** — `INSERT ... ON CONFLICT (user_id) DO UPDATE` 한 쿼리. race condition 자동 처리 (마지막 writer wins). PR #4 SAVE-2 부채 본 Repository 에 선반영
- INSERT then UPDATE — 두 쿼리 + 트랜잭션 필요. PR #4 ExternalAccountRepository 패턴이나 SAVE-2 로 지적된 부채

## 결정

**채택. `local_credentials` 테이블 + algo_version 보존 + UPSERT 단일 SQL.**

스키마 (V003__local_credentials.sql).

```sql
CREATE TABLE local_credentials (
  user_id        UUID         PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  password_hash  TEXT         NOT NULL,
  algo_version   TEXT         NOT NULL DEFAULT 'argon2id-v1',
  created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
  updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
```

엔티티 (StoredPasswordCredential.kt). 5 필드 노출. `toString` passwordHash 마스킹.

Repository UPSERT SQL.

```sql
INSERT INTO local_credentials (user_id, password_hash, algo_version, created_at, updated_at)
VALUES (:user_id, :password_hash, :algo_version, now(), now())
ON CONFLICT (user_id) DO UPDATE
SET password_hash = EXCLUDED.password_hash,
    algo_version  = EXCLUDED.algo_version,
    updated_at    = now()
RETURNING user_id, password_hash, algo_version, created_at, updated_at
```

## 근거

1. **PoC #2 spec 충실** — `local_credentials` 테이블명 + Argon2id 저장 그대로
2. **외부 IdP 분리 명확** — Local Provider 만 본 테이블 사용. LDAP / Keycloak / SAML 미사용 (각 외부 시스템 자체 저장)
3. **GDPR FK CASCADE** — PR #4 user_external_accounts 패턴 재사용 — users 삭제 시 비밀번호 자동 삭제
4. **future migration SQL 단순화** — algo_version 컬럼 redundancy 감수
5. **PR #4 SAVE-2 부채 선반영** — INSERT...RETURNING UPSERT 패턴

## 영향

### 긍정

- PoC 미완성 부분 마무리 — FR-AU-09 (SecurityFilterChain 통합) 시점 Local Provider 활성 가능
- phantom UserCredential 해소 — `authentication-provider-spi-naming.md` 정정 단락
- bts-impl wave 병렬 dispatch 첫 dogfood — 7 task / 3 wave

### 부정 / 위험

- algo_version 컬럼 redundancy — Spring Security upgrade-encoder 와 중복. trade-off 수용
- password_history 미보존 — 향후 N회 재사용 금지 정책 필요 시 별도 table

## 대안 채택 조건

- algo_version 사용처 없음으로 판명되면 → 후속 PR 에서 컬럼 제거 (V0NN migration)
- 비밀번호 변경 이력 보존 필요 (compliance / 보안 정책) → `password_history` 테이블 신규 ADR

## 관련

- `backend/modules/identity-access/src/main/resources/db/migration/V003__local_credentials.sql`
- `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/credential/StoredPasswordCredential.kt`
- `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/credential/StoredPasswordCredentialRepository.kt`
- `docs/specs/2026-05-20-identity-access-authn-poc.md` §F1, §5 (PoC spec)
- `docs/decisions/2026-05-20-argon2id-parameters.md` (재사용)
- `docs/decisions/2026-05-20-authentication-provider-spi-naming.md` (phantom 정정 단락)
- `docs/decisions/2026-05-20-user-external-accounts-schema.md` (FK CASCADE 패턴 참조)
- DEVELOPMENT.md §1.1 평문 비밀번호 금지
- DATA.md §6 트랜잭션 경계
