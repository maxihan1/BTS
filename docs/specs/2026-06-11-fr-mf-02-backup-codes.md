# FR-MF-02 — 백업 코드 (Recovery Codes) 스펙

> slug: fr-mf-02-backup-codes · type: auth · BC: identity-access · SDD §19.7
> 선행: FR-MF-01 TOTP (완료). product `identity-access.md §3.2`.
> **이번 PR 범위: 백엔드 D1~D5** (프론트 UI D6 / E2E D7은 후속 PR).

## 확정 결정 (Maxi 게이트, 2026-06-11)

| # | 결정 | 선택 | 근거 |
|---|---|---|---|
| 0 | PR 범위 | 백엔드(D1~D5)만 | FR-MF-01 선례, auth 폭발반경 분리 |
| 1 | 생성 시점 | 별도 엔드포인트 (TOTP ACTIVE 선행) | TOTP와 결합 없음, 사용자 제어 |
| 2 | 검증 경로 | 통합 `/mfa/verify` + `method` 필드 | 챌린지 토큰 공유, fail-safe 명시 분기 |
| 3 | 해시 | SHA-256 (unsalted) | 고엔트로피 랜덤 → 사전공격 불가, 업계 표준 |
| 4 | 재생성 | 전량 교체 (사용/미사용 모두 무효화 후 새 10개) | GitHub/Google 표준, 단순·명확 |

## 사용자 시나리오 (Given-When-Then)

**S1. 백업 코드 생성**
- Given: TOTP가 ACTIVE인 사용자(JWT 세션)
- When: `POST /api/v1/auth/mfa/backup-codes` 호출
- Then: 평문 10개 코드를 응답으로 1회 받고(다운로드/저장용), 서버에는 SHA-256 해시만 저장된다.

**S2. 백업 코드로 로그인 (TOTP 분실 시)**
- Given: 1단계(비밀번호) 통과 → 챌린지 토큰 보유, TOTP 앱 사용 불가
- When: `POST /api/v1/auth/mfa/verify` `{mfa_challenge_token, code, method:"backup_code"}` 호출
- Then: 미사용 백업 코드와 매칭되면 정식 세션 발급(`mfa_verified=true`), 해당 코드는 소진(`used_at` 마킹)되어 재사용 불가.

**S3. 사용한 코드 재사용 차단**
- Given: 이미 한 번 사용한 백업 코드
- When: 같은 코드를 다시 제출
- Then: 401 `invalid_code` (1회용 불변식).

**S4. 남은 코드 조회**
- Given: 백업 코드를 생성한 사용자
- When: `GET /api/v1/auth/mfa/backup-codes`
- Then: `{generated:true, remaining:N}` (평문 코드는 절대 재노출 안 됨).

**S5. 재생성**
- Given: 백업 코드를 일부 소진했거나 분실한 사용자
- When: `POST /api/v1/auth/mfa/backup-codes` 재호출
- Then: 기존 코드(사용/미사용 전부) 무효화 + 새 평문 10개 반환.

## 기능 요구사항 (FR)

- **FR-1**. 백업 코드 생성은 **TOTP ACTIVE** 사용자만 가능. PENDING/미설정이면 409 `totp_not_active`.
- **FR-2**. 한 번에 정확히 **10개** 생성. 각 코드는 고엔트로피 무작위(≥40bit/code).
- **FR-3**. 평문 코드는 **생성 응답에만 1회** 노출. 저장은 SHA-256 해시만(§1.1.1). 평문/해시 미로깅(§1.1.2).
- **FR-4**. 로그인 2단계 `/mfa/verify`에서 `method` 필드로 분기. `method` 미지정 시 기본 `"totp"`(회귀 0).
- **FR-5**. 백업 코드 검증 성공 시 해당 코드 1회용 소진(`used_at`), 정식 세션 발급(`mfa_verified=true`).
- **FR-6**. 재생성은 전량 교체(기존 user_id 전체 삭제 후 새 10개 INSERT, 단일 트랜잭션).
- **FR-7**. `GET /mfa/backup-codes`로 생성 여부 + 남은 미사용 코드 수 조회. 평문 재노출 금지.
- **FR-8**. 생성/사용/재생성 시 감사 이벤트 emit (`AuthAuditLogService` 재사용).
- **FR-9 (갭-1 보강)**. TOTP **disable 시 백업 코드도 함께 삭제**한다. TOTP 비활성화는 2FA 전체 해제이므로, 백업 코드를 남기면 (a) dead data + (b) 챌린지 토큰이 TOTP ACTIVE에서만 발급돼 어차피 사용 불가. `MfaService.disable`이 `totp_secrets` 삭제와 같은 트랜잭션에서 `user_mfa_backup_codes`도 정리한다.

## 비기능 요구사항 (NFR)

- **NFR-1 (보안)**. 평문 코드 미저장(§1.1.1). 코드/해시 미로깅(§1.1.2). 검증 시 상수시간 비교 불필요(해시 매칭은 DB 인덱스 조회, 평문 비교 아님) — 단, 에러 응답은 `invalid_code` 일반화로 존재 비노출.
- **NFR-2 (rate-limit)**. 백업 코드 검증은 brute-force 차단을 위해 rate-limit하되, **TOTP와 분리된 별도 카운터**를 쓴다(차단 시 429 `too_many_attempts`). **리뷰 C-2 = 별도 키 분리 (Maxi 게이트 1 확정)**: TOTP 실패로 잠겨도 백업 코드(분실 시 최후 수단)는 독립적으로 시도 가능. 백업 코드도 자체 MAX 5/5분으로 brute-force는 동일하게 차단. 구현 — `MfaAttemptLimiter` 클래스를 재사용하되 백업 코드 전용 별도 인스턴스(`@Bean` + `@Qualifier` 또는 전용 컴포넌트)로 카운터 공간 분리.
- **NFR-3 (동시성)**. 같은 코드 동시 제출 race는 atomic `UPDATE ... WHERE used_at IS NULL`로 1건만 성공(advisory-lock-bigint-toctou 교훈 — lock 밖 read-then-write 금지).
- **NFR-4 (인증)**. 생성/조회/재생성은 JWT 전용. PAT 시 403 `session_management_requires_interactive_login` (MfaController 기존 패턴). `/mfa/verify`는 기존대로 permitAll(챌린지 토큰이 1단계 증명).
- **NFR-5 (트랜잭션)**. 생성/재생성/소진은 단일 트랜잭션. 감사는 같은 트랜잭션에 묶음(MfaService 선례).

## API 인터페이스 (REST)

### 신규 — 백업 코드 self-service (`MfaController` 확장, JWT 전용)

```
POST /api/v1/auth/mfa/backup-codes        # 생성/재생성 (전량 교체)
  → 200 {"codes": ["a3k9f-2m7qx", ... 10개]}   # 평문 1회 노출
  → 409 {"error":"totp_not_active"}            # TOTP ACTIVE 아님
  → 403 PAT / 401 미인증

GET  /api/v1/auth/mfa/backup-codes        # 상태 조회
  → 200 {"generated": true, "remaining": 7}
  → 403 PAT / 401 미인증
```

### 확장 — 로그인 2단계 (`AuthController.verifyMfa`)

```
POST /api/v1/auth/mfa/verify
  body: {"mfa_challenge_token": "...", "code": "...", "method": "totp"|"backup_code"}
        # method 생략 시 "totp" (기존 클라이언트 회귀 0)
  → 200 TokenResponse + Set-Cookie (mfa_verified=true)   # totp 또는 backup_code 성공
  → 401 invalid_code      # 토큰 무효/재사용/코드 불일치/사용된 코드
  → 429 too_many_attempts
```

## 데이터 모델 변경 (V023)

```sql
CREATE TABLE user_mfa_backup_codes (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    code_hash   TEXT        NOT NULL,              -- SHA-256 hex (64자)
    used_at     TIMESTAMPTZ,                       -- NULL=미사용, 값=소진 시각
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
-- 검증 조회 (user_id + code_hash + 미사용) 가속
CREATE INDEX idx_mfa_backup_codes_user_active
    ON user_mfa_backup_codes(user_id) WHERE used_at IS NULL;
-- 같은 사용자 내 code_hash 중복 방지 (고엔트로피라 충돌 사실상 없음, 방어적)
CREATE UNIQUE INDEX uq_mfa_backup_codes_user_hash
    ON user_mfa_backup_codes(user_id, code_hash);
```
- identity-access raw SQL 모듈 → `init_codegen.sql` 미러 불요(TOTP V022 선례 COMMENT 명시).

**소진 쿼리 (atomic)**:
```sql
UPDATE user_mfa_backup_codes SET used_at = NOW()
WHERE user_id = ? AND code_hash = ? AND used_at IS NULL
RETURNING id;   -- 1행이면 검증 성공+소진 완료, 0행이면 실패/이미사용
```

## 엣지 케이스

- **EC-1**. TOTP PENDING/미설정에서 생성 시도 → 409 `totp_not_active`.
- **EC-2**. 사용한 코드 재제출 → atomic UPDATE 0행 → 401 `invalid_code`.
- **EC-3**. 같은 코드 동시 2회 제출(race) → atomic UPDATE로 1건만 성공, 나머지 401.
- **EC-4**. 재생성 시 기존 사용/미사용 전부 DELETE → 새 10개. 이전 코드 전량 무효.
- **EC-5**. 코드 전부 소진(remaining=0) → 검증 401, status는 `remaining:0` 반환(클라이언트가 재생성 유도). 백엔드는 자동 재생성 안 함.
- **EC-6**. brute-force(연속 오답) → `MfaAttemptLimiter` 429.
- **EC-7**. PAT로 생성/조회/재생성 → 403.
- **EC-8**. verify에서 `method:"backup_code"`인데 챌린지 토큰 무효/재사용 → 401(코드 검증 전 차단, TOTP 동일).
- **EC-9**. verify `method` 오타/미지원 값 → 400 `invalid_method` (또는 totp fallback 금지, 명시 거부 — fail-safe).
- **EC-10**. 백업 코드 검증 성공 시 `MfaAttemptLimiter.reset` + `sessions.mfa_verified=true`(TOTP와 동일 세션 효과).
- **EC-11 (갭-1)**. TOTP disable → `user_mfa_backup_codes` 전량 삭제(같은 트랜잭션). 이후 재-setup→enable 시 백업 코드는 0개(재생성 전까지 없음).
- **EC-12 (리뷰 C-4)**. 백업 코드 오답 시 챌린지 토큰은 **이미 소비**된 상태(verify는 코드 검증 전 `consume`) → 사용자는 1단계(비밀번호)부터 재로그인. TOTP와 동일 fail-closed 동작이나, 백업코드는 10자라 오타 가능성이 높음을 UI(후속 PR)에서 안내. 백엔드는 401 `invalid_code`.
- **EC-13 (리뷰 누락)**. TOTP ACTIVE가 아닌 사용자가 `method:"backup_code"`로 verify 시도 → 챌린지 토큰 자체가 TOTP ACTIVE에서만 발급되므로 도달 불가. 방어적으로 매칭 0 → 401 `invalid_code`(회귀 가드 테스트).

## 제약 조건

- 단일 BC(identity-access). cross-BC 호출 없음.
- FR-MF-01 인프라 재사용(암호화 제외 — 백업 코드는 해시). 신규 라이브러리 0(SHA-256은 JDK `MessageDigest`).
- **(갭-2)** enum 추가는 카운트 가드를 깰 수 있다(enum-add-breaks-crossmodule-count-guard 교훈). 다음을 **전 모듈 grep으로 영향 확인 후** 추가/조정한다.
  - `AuthEventType`에 백업 코드 감사 이벤트(예: `MFA_BACKUP_CODES_GENERATED` / `MFA_BACKUP_CODE_USED`) 추가.
  - `MfaChallenge` enum `BACKUP_CODE` 추가는 **실제 사용처 확인 후** 결정(현재 SPI placeholder — verify는 `method` 문자열로 분기하므로 enum 미사용이면 추가 보류).
- 백엔드만. 프론트/E2E는 후속 PR(D6/D7).

## 측정 가능한 완료 기준

- [ ] V023 마이그레이션 + 스키마 검증 테스트 통과
- [ ] 생성: TOTP ACTIVE만 허용, 정확히 10개, 평문 1회 응답, 해시 저장 검증
- [ ] 검증: 미사용 코드 매칭→소진→세션 발급, 사용된 코드 재제출 거부
- [ ] race: 동시 제출 1건만 성공(통합 테스트)
- [ ] 재생성: 전량 교체 검증
- [ ] rate-limit/PAT 차단/감사 emit 검증
- [ ] 기존 TOTP verify 회귀 0 (method 생략 시 totp)
- [ ] 모듈 전체 ktlintMain/Test + detekt + test --rerun-tasks BUILD SUCCESSFUL
- [ ] verify-master-plan.sh 통과 (FR 카운트 동기화)
