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

## 도메인 정리

- **BC**: identity-access (프론트는 view layer — 계약 소비, 백엔드 변경 0 목표)
- **영향 엔티티**: 없음(신규). 백엔드 `WebAuthnCredential`(VO) + `webauthn_credentials`(V025)는 PR #129에서 확정.
- **새 용어**: 없음. glossary §2FA에 "WebAuthn(선택)" 이미 등록, "Assertion" 용어도 SAML 맥락으로 존재. 프론트는 사용자 노출 명칭으로 **"보안 키"**(한국어) 사용 — 신규 도메인 용어가 아니라 UI 라벨.
- **기존 결정 충돌**: 없음.
- **관련 ADR**: [docs/decisions/2026-06-12-webauthn-second-factor.md](../decisions/2026-06-12-webauthn-second-factor.md) (PR #129, 확정). 핵심 — D1 2차 인증 only(passwordless 제외, 로그인 2단계 재사용 method=webauthn), D2 attestation=none, D3 사용자당 N개 credential(전역 credential_id UNIQUE)+sign_count clone 방어. **프론트는 이 결정을 변경하지 않고 소비만 한다.**
- **신규 ADR**: 없음(view layer 작업, 도메인 결정 없음).

## 스펙

전체 스펙. [docs/specs/2026-06-13-fr-mf-03-webauthn-ui-e2e-d6-d7.md](../specs/2026-06-13-fr-mf-03-webauthn-ui-e2e-d6-d7.md)

핵심 시나리오 3줄.
- 설정 `/settings/mfa` 보안 키 독립 섹션(항상 노출) — start→`@simplewebauthn` create→finish 등록, 목록, 인라인 확인 삭제.
- 로그인 MFA 화면에 "보안 키로 인증" 독립 버튼(코드 입력 병렬) — authenticate/start→get→verify(method=webauthn).
- 백엔드 변경 0(계약 소비). `@simplewebauthn/browser` 신규 의존성(절대규칙 #17 승인)으로 base64url↔ArrayBuffer 변환.

확정 결정 4종(§2) + 위험 R-1(의존성 버전)/R-2(E2E 가짜 credential 형태)/R-3(worktree pnpm install).

## Brainstorming Check

✅ 통과 (1회 self-iteration). gap 5건 발견·보강.
- A(P0) 등록 후 mfaEnrollmentRequired면 refreshSession(클레임-read 게이트, fr-mf-04) → FR-8.
- B 로그인 webauthn=독립 액션 버튼(세 번째 mode 아님) → FR-5 명확화.
- C verify(method=webauthn)는 mfa-handlers verifyHandler 확장 → §7.
- D @simplewebauthn 버전 고정 + E2E 가짜 credential 형태 → R-1/R-2.
- E worktree pnpm install node_modules 함정 → R-3.

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
