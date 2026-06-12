# FR-MF-03 백엔드 (D1~D5) — WebAuthn(Passkey/하드웨어 키) FIDO2 서버

> slug: fr-mf-03-webauthn
> type: auth
> agent: security-engineer
> primary_bc: identity-access
> 생성: 2026-06-12

## Brief

**원문 요청**. "fr-mf-03 진행하자"

**확정 범위 (Maxi 결정)**.
- 이번 PR = 백엔드 slice **D1~D5만** (도메인·FIDO2 명세·credentials 스키마·webauthn4j 백엔드·가상 Authenticator 테스트). 프론트(D6 `navigator.credentials`)·E2E(D7)는 **후속 PR** — FR-MF-01/02/04 동일 분할 패턴.
- 외부 라이브러리 = **webauthn4j 도입 확정** (product 문서 D4 명시, FIDO2 표준, DEVELOPMENT.md §외부 의존성 Maxi 승인 완료).

**컨텍스트 메모**.
- FR-MF-03 우선순위 = **선택** (FR-MF-01/02/04 는 필수), SDD 로드맵상 **Phase 4(Polish)** 작업을 당겨서 진행.
- 선행 §3.1 TOTP 완료(#113/#116), §3.2 백업코드 완료(#117/#121), §3.4 강제정책 완료(#123/#128).
- product 정본. `docs/plan/product/identity-access.md §3.3` (L208~218).
- SDD. `19-authentication.md:113`, `02-requirements.md:232`.

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
