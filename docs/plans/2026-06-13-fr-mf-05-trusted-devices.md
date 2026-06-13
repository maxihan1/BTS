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

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
