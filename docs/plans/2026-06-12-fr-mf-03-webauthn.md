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

## 도메인 정리

- **BC**. identity-access (단일)
- **영향 엔티티**.
  - `WebAuthnCredential` (신규 VO — 도메인 노트 identity-access.md:23에 "미래 PR"로 예약된 이름). 사용자당 **N개**(다중 등록), status PENDING/ACTIVE, sign_count(clone 방어).
  - `MfaChallenge` enum 확장 — `WEBAUTHN` 추가 (`spi/MfaChallenge.kt`, 현재 TOTP/NOT_IMPLEMENTED_YET).
  - `sessions.mfa_verified` 재사용(기존 V022 컬럼, 신규 불요).
- **핵심 정책 결정 (Maxi 확정)**.
  1. WebAuthn = **2차 인증 수단(second factor) only** — 패스키/passwordless 제외. 기존 2단계 로그인 흐름 재사용.
  2. **attestation = none** — 공개키만 저장, 표준 보안키 모두 허용.
  3. credential 사용자당 N개 / sign_count clone 방어.
  4. **webauthn4j 도입** + 기존 MFA 공유 컴포넌트(`MfaSecretEncryptor`·`MfaAttemptLimiter`·`AuthAuditLogService`·`MfaChallengeTokenService`) 재사용.
- **새 용어 (glossary 추가 후보, Maxi 승인 필요)**. WebAuthn · FIDO2 · attestation(등록 증명) · assertion(로그인 검증) · RP ID(Relying Party) · credential ID · sign count.
- **기존 결정 충돌**. 없음. FR-MF-01/02/04 인프라 위에 자연스럽게 확장.
- **spec에서 확정할 열린 결정**. (a) WebAuthn challenge nonce 저장 위치(HttpSession vs Caffeine), (b) 공개키 암호화 저장 여부, (c) RP ID/origin 환경별 설정.
- **관련 ADR**. [docs/decisions/2026-06-12-webauthn-second-factor.md](../decisions/2026-06-12-webauthn-second-factor.md) (생성됨)
- **다음 마이그레이션**. V025 (`webauthn_credentials`). identity-access는 jdbc-only → init_codegen 미러 불요.

## 스펙

전체 스펙. [docs/specs/2026-06-12-fr-mf-03-webauthn.md](../specs/2026-06-12-fr-mf-03-webauthn.md)

핵심 시나리오 3줄 요약.
- 등록(JWT): register/start(challenge 발급+Caffeine 저장) → 기기 서명 → register/finish(attestation 검증 후 credential INSERT, 즉시 활성)
- 로그인 2단계: 비번 통과 → mfa_challenge_token → authenticate/start(challenge) → 기기 서명 → /mfa/verify(method=webauthn) → signCount 갱신 → mfa_verified 세션
- 관리(JWT): 보안 키 목록 조회 / 삭제(본인 키만)

핵심 기술 확정.
- **webauthn4j 0.28.4.RELEASE**(Jackson 2, SB 3.3.5 호환). **0.31+ 금지**(Jackson 3 충돌). `createNonStrictWebAuthnManager()`(attestation none).
- challenge=Caffeine(TTL 5분, 1회용) / 공개키=평문 저장(base64) / status 컬럼 없음(검증 후 INSERT=활성) / 사용자당 N개 + signCount clone 방어.
- RP 설정=`@ConfigurationProperties("bts.webauthn")`. webauthn4j 객체는 자체 ObjectConverter로 직렬화(String 반환).
- V025 마이그레이션(`webauthn_credentials`, jdbc-only → init_codegen 미러 불요).

## Brainstorming Check

✅ 통과 (1회 iteration, adversarial self-review). gap 3건 spec 내 보강(Maxi 결정 불요).
- GAP-1(correctness): webauthn4j 객체 Spring 직렬화 시 표준 JSON 깨짐 → ObjectConverter 직렬화(NFR-9)
- GAP-2(clarify): userVerification=preferred / residentKey=discouraged(2차 인증 맥락, FR-1)
- GAP-3(clarify): user handle = userId 16바이트(FR-1)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
