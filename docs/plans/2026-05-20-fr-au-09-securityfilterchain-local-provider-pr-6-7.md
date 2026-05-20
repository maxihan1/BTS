# FR-AU-09 세션/토큰 관리 — SecurityFilterChain 통합 + Local Provider 연결

> slug: fr-au-09-securityfilterchain-local-provider-pr-6-7
> type: auth
> agent: security-engineer
> primary_bc: identity-access
> 생성: 2026-05-20

## Brief

**사용자 원문**.
> FR-AU-09 세션/토큰 관리 진입 — SecurityFilterChain 통합 + Local Provider 연결. PR #6/#7 blocker 모두 해소된 상태에서 백엔드 인증 매듭의 마지막 조각. CONCERN-1 트랜잭션 경계 + CONCERN-4 LDAP stop 시나리오 포함.

**classify 결과**.
- type. `auth`
- agent. `security-engineer`
- primary_bc. `identity-access`
- slug. `fr-au-09-securityfilterchain-local-provider-pr-6-7`

**선행 컨텍스트 (체크포인트 §Remaining Work #1 + PR #6 learning)**.
- PR #6 의 `LocalCredentialService` 가 `@Service` 부착 + `@Transactional` 정합 통과, but **호출자 없음**. 본 PR 이 첫 호출자.
- PR #6 CONCERN-1 (트랜잭션 경계 통합) + CONCERN-4 (LDAP stop 시나리오) 가 본 PR 의 스펙 범위.
- learning **"@Service 부착 누락 시 @Transactional 무력화"** 가 FR-AU-09 SecurityFilterChain 통합 시점 표면화 위험으로 명시됨. 본 PR 에서 회귀 검증.

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
