# FR-MF-01 TOTP 2FA 프론트엔드 UI + E2E

> slug: fr-mf-01-totp-ui-e2e
> type: auth (산출물은 frontend UI + E2E)
> agent: security-engineer (가이드) → frontend-engineer / qa-engineer (구현)
> 생성: 2026-06-11

## Brief

FR-MF-01 (TOTP, Authenticator 앱 기반 2FA) 의 **프론트엔드 UI + E2E** 작업.
백엔드는 PR #113 (`5e1c8d03`) 에서 완료된 2-PR 분할의 프론트 PR.

백엔드 API 흐름 (메모리 fr-mf-01-totp-backend-done + history #113):
- `setup` (QR + secret_base32 PENDING)
- `enable` (코드 검증 → ACTIVE)
- 로그인 2단계 — TOTP 활성 시 정식 세션 대신 `200 {mfa_required:true, mfa_challenge_token}` (5분 챌린지 JWT)
- `POST /api/v1/auth/mfa/verify {mfa_challenge_token, code}` → 정식 세션 `mfa_verified=true`
- `disable` (step-up 코드 검증)

산출물:
- `/settings/mfa` 설정 페이지 — QR 코드 표시 · secret 표시 · enable · disable
- 로그인 2단계 — pw 통과 후 `mfa_required` 시 TOTP 코드 입력 화면
- E2E — setup→enable→로그인 2단계 verify→세션 / disable 시나리오

FR-IS-10 선례처럼 이 프론트 PR 머지 시 FR-MF-01 완료 마킹 (현재 122/122 무변경).

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
