# FR-MF-02 — 백업 코드 (Recovery Codes)

> slug: fr-mf-02-backup-codes
> Plan slug (product 공식): identity/mfa-backup
> type: auth
> agent: security-engineer
> BC: identity-access
> 생성: 2026-06-11

## Brief

사용자 원문. "fr-mf-02 진행"

FR-MF-02 — 백업 코드 (Recovery Codes). FR-MF-01(TOTP 2FA, #113/#116)의 후속.
인증 앱(TOTP)을 분실/사용 불가할 때 쓰는 일회용 복구 코드.

product 정의 (`docs/plan/product/identity-access.md §3.2`).
- 우선순위. 필수 | 선행. §3.1 FR-MF-01 TOTP (완료)
- D1. 도메인 (security-engineer)
- D2. 명세 — 10개 1회용 코드 생성 + 해시 저장 (security-engineer)
- D3. 데이터 모델 — `user_mfa_backup_codes(code_hash, used_at)` (db-engineer)
- D4. 백엔드 — 코드 생성/검증/소진 (security-engineer)
- D5. 백엔드 테스트 (security-engineer)
- D6. 프론트 UI — 코드 다운로드/인쇄 + 1회용 안내 (designer → frontend-engineer)
- D7. E2E (qa-engineer)

classify. type=auth, agent=security-engineer, primary_bc=identity-access

## 도메인 정리

- **BC**: identity-access (단일 BC, cross-BC 없음)
- **SDD**: §19.7 (MFA). product `identity-access.md §3.2`.

### 영향 엔티티/컴포넌트

| 구분 | 항목 | 신규/기존 |
|---|---|---|
| 도메인 | `MfaBackupCode` (1회용 복구 코드, code_hash + used_at) | 신규 |
| 서비스 | `MfaBackupCodeService` (생성/검증/소진/재생성) | 신규 (MfaService 형제) |
| repository | `MfaBackupCodeRepository` | 신규 |
| 데이터 | `user_mfa_backup_codes(user_id, code_hash, used_at)` — V023 | 신규 테이블 |
| 컨트롤러 | `MfaController` 백업 코드 엔드포인트 추가 | 기존 확장 |
| 로그인 2단계 | `AuthController.verify` 백업 코드 분기 | 기존 확장 |
| enum | `MfaChallenge.BACKUP_CODE` | 기존 확장 |

### 재사용 인프라 (FR-MF-01 산출물)

- `MfaAttemptLimiter` — brute-force/rate-limit 방어 (백업 코드 검증에도 적용)
- `AuthAuditLogService` — 감사 emit (BACKUP_CODE 생성/사용/재생성)
- `MfaChallengeTokenService` — 로그인 1단계 후 단명 챌린지 토큰 (TOTP/백업 코드 공통)
- `sessions.mfa_verified` — 2차 요소 통과 전파 (백업 코드 검증도 동일하게 true)

### 해시 vs 암호화 (TOTP와의 차이 — 핵심)

- TOTP secret은 **복호화 필요**(매번 코드 재계산) → `MfaSecretEncryptor` AES-256-GCM (가역)
- 백업 코드는 **검증만**(저장값과 입력 비교) → **단방향 해시** 적합. 평문 복원 불필요.
- 백업 코드는 고엔트로피 랜덤 → 빠른 해시(SHA-256)로도 brute-force 안전. Argon2는 과함(패스워드용). → spec에서 확정.

### 새 용어 (glossary 갱신 대기 — Maxi 승인 필요)

- **백업 코드 (Backup Code / Recovery Code)**. 인증 앱(TOTP)을 분실/사용 불가할 때 2차 요소를 대체하는 일회용 복구 코드. 1회 사용 시 소진. glossary 73행 "2FA = TOTP + 백업 코드"에 개념은 이미 존재 → 별도 표제어 추가 검토.

### 기존 결정 충돌

- **없음**. FR-MF-01 위의 자연스러운 형제 확장. 로그인 2단계 흐름(`/mfa/verify`)에 대체 코드 경로만 추가.

### 관련 ADR

- FR-MF-01엔 별도 ADR 없음(표준 라이브러리). 백업 코드도 표준 패턴.
- ADR 후보(소): "백업 코드 해시 전략(SHA-256 vs Argon2)" — spec D2에서 결정 후 필요 시 docs/decisions 기록.

### spec에서 확정할 갈림길 (도메인 영향)

1. 생성 시점 — TOTP enable과 동시 자동 vs 별도 엔드포인트
2. 로그인 검증 — `/mfa/verify` TOTP/백업 통합 vs 별도 엔드포인트
3. 해시 알고리즘 — SHA-256 vs Argon2
4. 재생성 정책 — 전체 무효화 후 새 10개

## 스펙

전체 스펙. [docs/specs/2026-06-11-fr-mf-02-backup-codes.md](../specs/2026-06-11-fr-mf-02-backup-codes.md)

**이번 PR 범위. 백엔드 D1~D5만** (프론트 UI D6 / E2E D7 후속 PR).

확정 결정 (Maxi 게이트).
- PR 범위 = 백엔드 먼저 / 생성 = 별도 엔드포인트(TOTP ACTIVE 선행) / 검증 = 통합 `/mfa/verify` + `method` 필드 / 해시 = SHA-256 / 재생성 = 전량 교체

핵심 시나리오 3줄 요약.
- TOTP ACTIVE 사용자가 `POST /mfa/backup-codes` → 평문 10개 1회 응답, SHA-256 해시만 저장
- 로그인 2단계 `/mfa/verify` `method:"backup_code"` → 미사용 코드 매칭→atomic 소진→세션 발급(mfa_verified=true)
- 재생성/재사용/race/TOTP disable cascade 모두 fail-safe (atomic UPDATE + 전량 교체 + 트랜잭션)

## Brainstorming Check

✅ 통과 (직접 sanity check, gap 2건 발견 후 spec 보강).
- 갭-1. TOTP disable 시 백업 코드 dead data → FR-9/EC-11로 cascade 삭제 보강
- 갭-2. AuthEventType/MfaChallenge enum 추가가 카운트 가드 위협 → 제약에 전 모듈 grep 명시 (MfaChallenge는 실제 사용처 확인 후 결정)

## Plan

> 단일 모듈(identity-access) 백엔드. SHA-256은 JDK `MessageDigest`(신규 의존성 0).
> 코드 형식 = Crockford base32 10자(혼동문자 제외) `xxxxx-xxxxx`, 32^10≈50bit(≥40bit 충족). 검증 시 대소문자/하이픈 정규화 후 해시.
> **사전 확정(plan grep)**: ① `MfaChallenge` enum 추가 불요(placeholder, verify는 method 문자열 분기) ② `auth_audit_logs.event_type`은 VARCHAR(40) CHECK 없음 → enum 추가가 마이그레이션 무관, KDoc "16종"만 갱신.

### Task 1. V023 마이그레이션 + 스키마 검증

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/identity-access/src/main/resources/db/migration/V023__mfa_backup_codes.sql`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/mfa/MfaBackupCodesSchemaTest.kt`]
- depends-on: []

**RED**. Flyway V001~V023 적용 후 `user_mfa_backup_codes` 테이블 + `idx_mfa_backup_codes_user_active`(부분, used_at IS NULL) + `uq_mfa_backup_codes_user_hash`(UNIQUE) + FK CASCADE 존재 검증(실패: 테이블 없음). `IssueSecuritySchemaMigrationTest`/V022 선례 따름.

**GREEN**. spec §데이터 모델의 DDL 그대로. COMMENT에 `identity-access raw SQL(init_codegen 미러 불요)` 명시(V022 선례).

**REFACTOR**. COMMENT 보강.

**검증**. `./gradlew :identity-access:test --tests '*MfaBackupCodesSchemaTest'`

### Task 2. BackupCodeGenerator — 10개 고엔트로피 코드 생성

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/mfa/BackupCodeGenerator.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/mfa/BackupCodeGeneratorTest.kt`]
- depends-on: []

**RED**. `generate()` → 정확히 10개, 각 `xxxxx-xxxxx` Crockford base32(혼동문자 0/O/1/I/L 제외), 10개 상호 고유, `SecureRandom` 사용(실패: 클래스 없음).

**GREEN**. `SecureRandom` + Crockford 알파벳에서 문자 추출.

**REFACTOR**. 알파벳/개수/길이 상수화 + KDoc(엔트로피 근거).

**검증**. `./gradlew :identity-access:test --tests '*BackupCodeGeneratorTest'`

### Task 3. BackupCodeHasher — SHA-256

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/mfa/BackupCodeHasher.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/mfa/BackupCodeHasherTest.kt`]
- depends-on: []

**RED**. `hash(plain)` → 64자 hex, 같은 입력 같은 출력(결정적), 입력 정규화(대문자·하이픈 제거 후 동일 해시: `a3k9f-2m7qx` == `A3K9F2M7QX`)(실패: 클래스 없음).

**GREEN**. `MessageDigest.getInstance("SHA-256")` + 정규화. 평문/해시 미로깅(§1.1.2).

**REFACTOR**. 정규화 규칙 KDoc.

**검증**. `./gradlew :identity-access:test --tests '*BackupCodeHasherTest'`

### Task 4. MfaBackupCodeRepository — JDBC(전량교체/atomic 소진/카운트)

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/mfa/MfaBackupCodeRepository.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/mfa/JdbcMfaBackupCodeRepository.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/mfa/JdbcMfaBackupCodeRepositoryIntegrationTest.kt`]
- depends-on: [1]

**RED** (통합, 실 PostgreSQL). `replaceAll(userId, hashes)`=기존 전량 DELETE+10 INSERT(단일 트랜잭션), `consumeIfUnused(userId, hash)`=`UPDATE...WHERE used_at IS NULL RETURNING id`(1행 true/0행 false), 같은 hash 2회 consume→2번째 false(멱등), `countUnused(userId)`, `deleteAllByUser(userId)`.

**GREEN**. `JdbcTemplate` raw SQL. consume은 spec의 atomic UPDATE(advisory-lock-bigint-toctou 교훈 — lock 밖 read-then-write 금지).

**REFACTOR**. SQL 상수화 + KDoc.

**검증**. `./gradlew :identity-access:test --tests '*JdbcMfaBackupCodeRepositoryIntegrationTest'`

### Task 5. AuthEventType — 백업 코드 감사 이벤트 추가

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/audit/AuthEventType.kt`]
- depends-on: []

**RED**. `AuthEventType.MFA_BACKUP_CODES_GENERATED` / `MFA_BACKUP_CODE_USED` 참조 테스트(실패: 값 없음). (명시 카운트 가드 부재 확인됨 — DB CHECK 없음.)

**GREEN**. enum 2값 추가 + KDoc 항목 + 상단 주석 `16종`→`18종`.

**REFACTOR**. 없음(최소 변경).

**검증**. `./gradlew :identity-access:compileTestKotlin` (참조 컴파일) + 기존 audit 테스트 회귀.

### Task 6. MfaBackupCodeService — 생성/검증소진/재생성/상태 오케스트레이션

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/mfa/MfaBackupCodeService.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/mfa/MfaBackupCodeServiceTest.kt`]
- depends-on: [2, 3, 4, 5]

**RED**. `@Transactional` 서비스.
- `generateOrRegenerate(userId)`: TOTP ACTIVE 아니면 `NotActive`(`TotpSecretRepository.findByUser().status==ACTIVE` 확인). ACTIVE면 generator 10개 → hasher → `repo.replaceAll`(전량교체) → 평문 10개 반환(`Generated(codes)`) + 감사 `MFA_BACKUP_CODES_GENERATED`.
- `verifyAndConsume(userId, plain)`: limiter 차단 시 `TooManyAttempts`. hasher→`repo.consumeIfUnused` true면 `Success`+limiter.reset+감사 `MFA_BACKUP_CODE_USED`, false면 `InvalidCode`+limiter.recordFailure.
- `status(userId)`: `{generated: countTotal>0, remaining: countUnused}`.

**GREEN**. generator+hasher+repo+limiter+auditLog+totpRepo 조립. sealed interface 결과(MfaService 패턴).

**REFACTOR**. KDoc(보안 불변식: 평문 미저장/미로깅, fail-closed).

**검증**. `./gradlew :identity-access:test --tests '*MfaBackupCodeServiceTest'`

### Task 7. MfaService.disable 백업 코드 cascade 삭제 (갭-1)

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/mfa/MfaService.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/mfa/MfaServiceTest.kt`]
- depends-on: [4]

**RED**. `MfaService.disable` 성공 시 `backupCodeRepo.deleteAllByUser(userId)` 호출 검증(같은 트랜잭션). 기존 MfaServiceTest 생성자 mock에 `MfaBackupCodeRepository` 추가(plan-files-constructor-injection-existing-tests 교훈 — 기존 테스트도 files 포함).

**GREEN**. `MfaService` 생성자에 `backupCodeRepo: MfaBackupCodeRepository` 주입 + `disable` 성공 분기에 `deleteAllByUser` 추가.

**REFACTOR**. KDoc에 cascade 사유(2FA 해제=2차 요소 전체 정리).

**검증**. `./gradlew :identity-access:test --tests '*MfaServiceTest'`

### Task 8. MfaController — 백업 코드 엔드포인트 (POST/GET /mfa/backup-codes)

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/web/MfaController.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/web/MfaControllerTest.kt`]
- depends-on: [6]

**RED**. `POST /api/v1/auth/mfa/backup-codes`: 200 `{codes:[10개]}` / 409 `totp_not_active` / 403 PAT / 401. `GET`: 200 `{generated, remaining}` / 403 PAT. JWT 전용(userIdOrNull→PAT 403, 기존 패턴 재사용).

**GREEN**. 컨트롤러 메서드 2개 + DTO(`BackupCodesResponse`, `BackupCodesStatusResponse`). 도메인 결과→ResponseEntity 직접 매핑(catch-all 변질 가드).

**REFACTOR**. KDoc(JWT 전용/4xx 직접 매핑 사유, 기존 MfaController 스타일).

**검증**. `./gradlew :identity-access:test --tests '*MfaControllerTest'`

### Task 9. AuthController.verifyMfa — method 분기 + 통합/race 테스트

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/web/AuthController.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/web/AuthControllerTest.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/integration/MfaBackupCodeLoginIntegrationTest.kt`]
- depends-on: [6]

**RED**.
- `MfaVerifyRequest`에 `method: String = "totp"` 추가(기본값 → 기존 클라이언트 회귀 0). 직렬화 키 `method`.
- `method="backup_code"` → `backupCodeService.verifyAndConsume` → Success면 `issueTokens(mfaVerified=true)`, InvalidCode 401, TooManyAttempts 429.
- `method` 생략/`"totp"` → 기존 `mfaService.verifyLogin` 경로(회귀 0).
- 미지원 `method` → 400 `invalid_method`(fail-safe, totp fallback 금지).
- 통합. race(동시 같은 코드 2제출→1건만 200), end-to-end(생성→로그인 backup_code→소진→재사용 401).

**GREEN**. `verifyMfa`에 챌린지 토큰 validate/consume 이후 `when(body.method)` 분기. 토큰 검증은 method 무관 공통.

**REFACTOR**. KDoc(method 분기/회귀 0/invalid_method).

**검증**. `./gradlew :identity-access:test --tests '*AuthControllerTest' --tests '*MfaBackupCodeLoginIntegrationTest'`

## Plan 메타

- task 수: 9
- 예상 wave (depends-on 그래프). W1[T1,T2,T3,T5] → W2[T4] → W3[T6,T7] → W4[T8,T9]
  - 단, 단일 모듈 test 컴파일 직렬(bts-plan-wave-gradle-module-compile 교훈) → 실제는 거의 순차. 그래프는 의존성 정확 표기용.
- TDD 강제: yes (test 커밋 먼저)
- 추가 검증: 모듈 전체 ktlintMain/Test + detekt + test --rerun-tasks (subagent-ktlint-false-green → controller 직접 검증), verify-master-plan.sh (FR 카운트 동기화)
- 프론트/E2E(D6/D7): 후속 PR (이번 범위 외)

## 리뷰 결과 (← /bts-review-plan 채움)
