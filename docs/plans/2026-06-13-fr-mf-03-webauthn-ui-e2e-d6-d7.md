# FR-MF-03 WebAuthn 보안 키 — D6 프론트 UI + D7 E2E

> slug: fr-mf-03-webauthn-ui-e2e-d6-d7
> type: auth (frontend-engineer 주도 + security-engineer 검토 + qa-engineer E2E)
> agent: security-engineer (classify 기본 — task별 plan 메타로 frontend/qa 재지정)
> 생성: 2026-06-13

## Brief

FR-MF-03 WebAuthn(Passkey/하드웨어 키) 2차 인증의 프론트 UI(D6) + E2E(D7). 백엔드 D1~D5는 PR #129로 완료 — 계약 준비됨. 잔여 D6/D7만 본 PR 범위.

**Maxi 확정 결정 3종(게이트0 office-hours 대체).**
1. **설정 레이아웃** — 보안 키를 TOTP와 독립된 섹션으로 **항상 노출**(백엔드 `isAnyMfaEnabled = TOTP OR 백업코드 OR WebAuthn`, 보안 키는 TOTP 없이도 등록 가능). 백업코드는 현행대로 TOTP 활성 시에만.
2. **로그인 2단계** — "보안 키로 인증" 버튼 + 기존 코드 입력 병렬(mfa_required 응답이 보유 요소를 알려주지 않으므로 선택지 모두 노출).
3. **D7 E2E** — `navigator.credentials.create/get`을 addInitScript로 stub(가짜 PublicKeyCredential 반환), MSW가 임의 credential 수락. 기존 MSW 결정적 패턴과 일관(CDP 가상 authenticator 미사용).

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
