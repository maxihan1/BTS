# FR-MF-05 — 신뢰 디바이스 (30일 MFA 면제) 백엔드 슬라이스 (D1~D5)

> slug: fr-mf-05-trusted-devices
> type: auth
> agent: security-engineer
> 생성: 2026-06-13

## Brief

FR-MF-05 신뢰 디바이스 — 사용자가 MFA를 통과한 디바이스를 "신뢰"로 등록하면 30일 동안 해당 디바이스에서 MFA 재요구를 면제. 본 PR은 백엔드 슬라이스(D1~D5):
- D1. 도메인 — TrustedDevice
- D2. 명세 — 사용자 동의 + 30일 TTL + 취소
- D3. 데이터 모델 — `trusted_devices(device_fingerprint, expires_at)`
- D4. 백엔드 — fingerprint 발급 + MFA 우회 검증
- D5. 백엔드 테스트 — TTL 만료 + 명시적 취소

프론트 UI(D6) / E2E(D7)는 후속 PR로 분리 (FR-MF-01~04 동일 분할).

classify: type=auth, agent=security-engineer, primary_bc=identity-access

## 도메인 정리

- **BC**: identity-access (단일, cross-BC 없음)
- **영향 엔티티**: `TrustedDevice` (신규), `AuthController`(login/verifyMfa 결선), `MfaService.disable`/비밀번호 변경 서비스(자동폐기 훅)
- **신규 용어**:
  - **신뢰 디바이스(Trusted Device)** — 사용자가 MFA 통과 후 "30일 면제"에 동의한 디바이스. 서버 불투명 토큰으로 식별.
  - **신뢰 토큰(trusted device token)** — MFA verify 성공 시 서버가 발급하는 32바이트 난수. HttpOnly 쿠키로 클라이언트 보관, DB는 `SHA-256` 해시(`token_hash`)만 저장.
- **핵심 결정**(ADR `docs/decisions/2026-06-13-trusted-device-mfa-exemption.md`):
  - D1. 식별 = 서버 불투명 토큰(HttpOnly 쿠키), DB 해시만 (클라이언트 핑거프린트 기각 — 위조/충돌/프라이버시)
  - D2. `trusted_devices` V026 (init_codegen 미러 불요 — jdbc-only 모듈)
  - D3. 통합 = `completeLogin`(쿠키→우회, mfaVerified=true) + `verifyMfa`(trustDevice opt-in→등록+Set-Cookie)
  - D4. 고정 30일 만료(sliding 아님)
  - D5. 취소 = 수동(단건/전체) + 자동(비번변경·TOTP비활성)
  - D6. JWT 클레임 미도입 (SDD 스케치 일탈 — 로그인 시점 부재, mfa_verified 재사용)
  - D7. PR 범위 = 백엔드 D1~D5 먼저
- **명명 일탈**: SDD/product 스케치 `device_fingerprint` → 실제 `token_hash`(기존 `Session.deviceFingerprint`=약한 UA+IP 핑거프린트와 혼동 회피). product D3 인라인 메모로 동기화(머지 시).
- **기존 결정 충돌**: 없음. FR-MF-04(MFA 강제)와 직교 — 신뢰 우회는 MFA 활성이 전제라 enrollment 게이트 통과, 우회 세션도 mfaVerified=true.
- **관련 ADR**: docs/decisions/2026-06-13-trusted-device-mfa-exemption.md (생성됨), 선행 2026-06-12-mfa-enforcement-policy.md
- **glossary 갱신 대기**: "신뢰 디바이스", "신뢰 토큰" (§인증 — Maxi 승인 필요, 자동 갱신 안 함)

## 스펙

전체 스펙. docs/specs/2026-06-13-fr-mf-05-trusted-devices.md

핵심 시나리오 요약.
- MFA verify 성공 + `trust_device=true` → 서버 불투명 토큰 발급(HttpOnly 쿠키) + `trusted_devices` 행 INSERT(SHA-256 해시, 30일 만료).
- 다음 로그인 시 쿠키 유효(user 일치+미만료)면 챌린지 생략하고 정식 세션(mfa_verified=true), `last_used_at` 갱신.
- 취소 = 수동(단건 `DELETE /mfa/trusted-devices/{id}`·전체 `DELETE /mfa/trusted-devices`, 목록 `GET`) + 자동(비번변경·TOTP비활성 전량 revoke).
- 감사 로그 `TRUSTED_DEVICE_ADDED`/`TRUSTED_DEVICE_REVOKED` 2종(MFA self-service 일관).

엔드포인트. login/verify 수정 + trusted-devices GET/DELETE(단건)/DELETE(전체) 3 신규(JWT 전용·PAT 403).

## Brainstorming Check

✅ 통과 (1회 iteration). 감사 로그 누락 발견→FR-11 보강, Clock 출처 명시, user-bound 우회 차단·fail-safe 폴백 명시. 상세는 spec 파일 §Brainstorming Check.

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
