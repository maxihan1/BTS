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

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
