<!-- ADR — sessions / refresh_tokens / personal_access_tokens 스키마 + 1 로그인 = 1 Session row 결정 -->

# ADR — Session / RefreshToken / PAT 스키마 및 관계

**일자.** 2026-05-20
**상태.** Accepted
**관련 PR.** #7 (`fr-au-09-securityfilterchain-local-provider-pr-6-7`)
**작성자.** security-engineer (Claude Sonnet 4.6)

---

## 컨텍스트

BTS는 SDD 19.5 Full scope에 따라 Access JWT + Refresh Token + Session DB + Personal Access Token(PAT)을 구현한다.
PR #7 이전에는 인증 후 토큰을 DB에 전혀 기록하지 않았으므로 다음 문제가 있었다.

1. **토큰 revoke 불가.** 로그아웃해도 기존 Access JWT가 만료 전까지 유효했다.
2. **디바이스 단위 세션 관리 불가.** "다른 기기에서 로그아웃"(logout-all) 기능 구현 불가.
3. **PAT 관리 불가.** 장기 토큰의 발급·조회·revoke 이력이 없었다.
4. **감사 로그 공백.** 로그인 이벤트를 `sessions` row 없이 어떤 디바이스에서 로그인했는지 기록할 수 없었다.

이를 해결하기 위해 DB 스키마 3종(V004/V005/V006)과 엔티티 간 관계, 핵심 설계 결정을 기록한다.

---

## 결정

### 1. 세션 단위: 1 로그인 = 1 `sessions` row (디바이스 단위)

`sessions` 테이블(V004)은 로그인 1회마다 새 row를 생성한다.
같은 사용자가 노트북, 모바일, 회사 PC에서 각각 로그인하면 3개의 row가 생긴다.

`sessions.id`(UUID)가 JWT `sid` 클레임의 값이며, revoke 검증의 기본 단위다.

### 2. Refresh Token: rotation chain (`replaced_by` 자기 참조)

Refresh Token을 사용할 때마다 새로운 row를 INSERT하고 이전 row의 `used_at`을 채운다.
`replaced_by`(UUID, self FK)로 교체 연결을 유지한다.
재사용 감지: `used_at IS NOT NULL`인 token이 다시 들어오면 즉시 전체 Session revoke + `SUSPICIOUS_REFRESH_REPLAY` 감사 로그.

### 3. PAT: User 1:N, DB에 SHA-256 해시만 저장

PAT는 발급 시 `pat_` 접두사가 붙은 원본 token을 응답에 한 번만 반환한다.
DB에는 `SHA-256(전체 token 문자열)` hex 64자만 저장한다(`token_hash`).
원본 token은 BTS가 재현할 수 없으며, 사용자가 분실 시 재발급해야 한다.

`scopes` 컬럼은 JSONB 배열(`["read:issues", "write:comments"]`)로 저장하며, 요청 API의 필요 scope와 교집합이 빈 집합이면 403을 반환한다.

### 4. 스키마 요약

**V004 `sessions`.**

```sql
CREATE TABLE sessions (
  id                   UUID         PRIMARY KEY,
  user_id              UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  provider_id          VARCHAR(64)  NOT NULL,
  device_fingerprint   VARCHAR(128),
  ip_address           INET,
  user_agent           TEXT,
  created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  expires_at           TIMESTAMPTZ  NOT NULL,
  last_seen_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  revoked_at           TIMESTAMPTZ,
  revoke_reason        VARCHAR(64)
);
CREATE INDEX idx_sessions_user_active    ON sessions(user_id)    WHERE revoked_at IS NULL;
CREATE INDEX idx_sessions_expires_active ON sessions(expires_at) WHERE revoked_at IS NULL;
```

`user_agent`는 `VARCHAR(512)` 대신 `TEXT`로 결정했다. 일부 브라우저 UA가 512자를 초과하는 사례가 있어 절삭 시 감사 로그 품질이 저하되기 때문이다(spec §5 DM 보강).

**V005 `refresh_tokens`.**

```sql
CREATE TABLE refresh_tokens (
  id           UUID         PRIMARY KEY,
  session_id   UUID         NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
  token_hash   VARCHAR(64)  NOT NULL UNIQUE,   -- SHA-256 hex
  issued_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  expires_at   TIMESTAMPTZ  NOT NULL,
  used_at      TIMESTAMPTZ,
  replaced_by  UUID         REFERENCES refresh_tokens(id)
);
CREATE INDEX idx_refresh_session ON refresh_tokens(session_id);
```

**V006 `personal_access_tokens`.**

```sql
CREATE TABLE personal_access_tokens (
  id           UUID          PRIMARY KEY,
  user_id      UUID          NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  name         VARCHAR(128)  NOT NULL,
  token_hash   VARCHAR(64)   NOT NULL UNIQUE,
  scopes       JSONB         NOT NULL DEFAULT '[]',
  expires_at   TIMESTAMPTZ,
  last_used_at TIMESTAMPTZ,
  revoked_at   TIMESTAMPTZ,
  created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_pat_user_active ON personal_access_tokens(user_id) WHERE revoked_at IS NULL;
```

`expires_at`은 NULL 허용(무기한 PAT). 운영 관리 차원에서 후속 PR에서 강제 만료 정책(1년 default 등)을 검토한다(EC-27).

### 5. 엔티티 관계

```
users (V001)
  ├── sessions (V004)  1:N  [user_id FK]
  │     └── refresh_tokens (V005)  1:N  [session_id FK]
  │           └── replaced_by  (self FK, rotation chain)
  └── personal_access_tokens (V006)  1:N  [user_id FK]
```

`sessions`이 revoke되면 `refresh_tokens`도 `ON DELETE CASCADE`로 연쇄 삭제된다.

### 6. GC 정책 (후속 PR 메모)

본 PR에서 구현하지 않으며, 다음 조건의 row를 정리하는 GC job을 후속 PR에서 도입한다.

| 대상 | 정책 |
|---|---|
| `refresh_tokens` `used_at IS NOT NULL` | 생성 30일 후 DELETE (rotation chain 참조 종료 후) |
| `sessions` `revoked_at IS NOT NULL OR expires_at < now()` | 30일 후 DELETE |

무한 증가 위험: 14일 Refresh Token 만료 주기에서 1 session = 최대 14×24×6 = 2,016 row(분당 1회 refresh 극단 시나리오). 실제 운영에서는 훨씬 적으나, GC 없이 운영 1년 후 누적 문제 발생 가능(EC-21/EC-22).

---

## 대안 검토

### 대안 1. sessions 없이 Access Token blacklist만 사용 (불채택)

Access Token을 발급할 때 Redis에 JTI를 blacklist로 관리한다.

**불채택 이유.**
- 디바이스 단위 정보(device_fingerprint, ip_address, user_agent)를 저장할 위치가 없다.
- logout-all 구현 시 해당 사용자의 모든 JTI를 알아야 하는데, Access Token이 short-lived(15분)라 전수 관리 비용 대비 효과가 낮다.
- BTS는 Redis를 선택적으로 사용하며(인프라 단순성), Session DB(PostgreSQL)만으로 처리하는 것이 의존성 추가 없이 가능하다.

### 대안 2. Refresh Token을 sessions 테이블에 합치기 (불채택)

`sessions` 테이블에 `refresh_token_hash` 컬럼을 추가하고 rotation 시 UPDATE한다.

**불채택 이유.**
- rotation chain(`replaced_by`)을 저장할 수 없어 재사용 감지(EC-05) 구현이 불가능하다.
- 이력 추적이 필요한 감사 로그 요구사항(SDD 19.9 `TOKEN_REFRESHED` 이전/이후 jti)을 충족하지 못한다.

### 대안 3. PAT에 평문 token 저장 (절대 금지)

**DEVELOPMENT.md §1.1 위반.** 토큰은 반드시 SHA-256 해시만 저장한다. 평문 저장 시 DB 유출 = 즉각적인 API 접근 가능.

---

## 결정 근거

1. **SDD 19.5 Full scope 준수.** 세션 단위 revoke, rotation chain, PAT scope 평가 모두 spec 요구사항이다.
2. **보안 원칙.** 토큰 원본은 발급 응답에만 존재한다(DEVELOPMENT.md §1 토큰 해시 저장). DB 유출 시 token을 직접 사용할 수 없다.
3. **감사 추적.** `sessions` row가 있어야 누가, 어느 기기에서, 언제 로그인했는지 기록할 수 있다(SDD 19.9).
4. **partial index 최적화.** `WHERE revoked_at IS NULL` partial index로 revoke되지 않은 활성 row만 빠르게 조회한다. `sid` 조회 SLA < 20ms(spec NFR).

---

## 결과

### 긍정

- sid 기반 즉각 revoke 가능. 로그아웃 후 `revoked_at` 채우면 다음 요청부터 401.
- logout-all: 해당 user의 모든 sessions `revoked_at = now()` UPDATE 1 쿼리.
- Refresh replay 감지: `used_at IS NULL` WHERE절 optimistic locking으로 race window 처리.

### 부정 / 위험

- Access Token 검증 시마다 `sessions` DB 조회가 발생한다. Caffeine 캐시(TTL 5s, sid → revoked? 결과)로 정상 흐름에서 DB 미접근(EC-29). revoke 후 최대 5초 지연은 허용된 트레이드오프.
- `refresh_tokens` 무한 증가(EC-21). GC job 후속 PR 필수.
- PAT `expires_at NULL`(무기한) 허용. 운영 정책 후속 PR에서 결정.

---

## 참조

- `docs/specs/2026-05-20-fr-au-09-securityfilterchain-local-provider-pr-6-7.md` §5 (V004/V005/V006 스키마)
- `docs/specs/2026-05-20-fr-au-09-securityfilterchain-local-provider-pr-6-7.md` §6 EC-05/EC-21/EC-22/EC-23/EC-26/EC-27/EC-29
- `docs/decisions/2026-05-20-jwt-issuer-strategy.md` (BTS 자체 JWT 발급 — sid 클레임 근거)
- `docs/decisions/2026-05-20-jwt-key-rotation-policy.md` (kid 관리 — Access Token 검증 연동)
- `docs/decisions/2026-05-20-stored-password-credential-schema.md` (V003 FK 참조 — Local Provider 연동)
- `docs/decisions/2026-05-20-user-external-accounts-schema.md` (V002 FK CASCADE 패턴 — LDAP Auto-provisioning)
- `docs/decisions/2026-05-20-argon2id-parameters.md` (Local 로그인 검증 파라미터)
- DEVELOPMENT.md §1.1 (토큰 해시 저장 절대 규칙)
- DATA.md §6 (트랜잭션 경계 — Session 발급/rotation)
- SDD 19.5 (Session/Token 관리 Full scope)
- SDD 19.9 (감사 로그 — LOGIN_SUCCESS / TOKEN_REFRESHED / SUSPICIOUS_REFRESH_REPLAY)
