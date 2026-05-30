<!-- BTS plan — identity-access FR-AU-05 로컬 계정 가입/비밀번호 변경/리셋 -->

# FR-AU-05 로컬 계정(외부 협력사용) — 가입/비밀번호 변경/리셋

> slug: fr-au-05-local-account
> type: auth
> agent: security-engineer
> primary_bc: identity-access
> 생성: 2026-05-30
> 마스터플랜: `docs/plan/product/identity-access.md §2.5`

## Brief

FR-AU-05 로컬 계정의 남은 작업. 기존 인프라(LocalCredentialService hash/store/verify/rotate,
LocalProvider, V003 local_credentials 마이그레이션 — PR #6/#8)는 완성된 production 코드로 재사용.
남은 범위: D4(가입/비밀번호 변경/리셋 API) + D5(비밀번호 정책 위반 테스트) +
D6(프론트 가입/변경 폼 UI) + D7(E2E).

D1(LocalCredential VO)/D2(비밀번호 정책 명세)/D3(local_credentials 테이블)은 완료 상태.

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
