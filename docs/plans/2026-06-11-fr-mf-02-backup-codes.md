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

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
