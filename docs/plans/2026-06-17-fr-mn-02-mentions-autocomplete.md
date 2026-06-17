# FR-MN-02 — 멘션 자동완성

> slug: fr-mn-02-mentions-autocomplete
> type: api
> agent: backend-engineer
> primary_bc: identity-access
> 생성: 2026-06-17

## Brief

FR-MN-02 멘션 자동완성 (issue-tracking §4.1.2, 선행 FR-MN-01 완료).
- D4 백엔드. `GET /api/v1/users/autocomplete?q=` — prefix 매칭 사용자 목록 반환
- D6 프론트. `@` 트리거 popover (명세 "TipTap 확장" — 실제 에디터 확인 필요)
- D7 E2E

classify: type=api, agent=backend-engineer, primary_bc=identity-access.

선행 쟁점.
1. cross-BC 경계 — `users` 자동완성은 identity-access 소관(데이터 소유), FR은 issue-tracking. 엔드포인트 위치 결정 필요.
2. 에디터 실체 — "TipTap 확장"이 현재 프론트(plain textarea + MentionParser)와 정합한지 확인 필요.

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
