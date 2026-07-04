# FR-PR-01 사용자 프로필 (이름/아바타/타임존/부서)

> slug: fr-pr-01-user-profile
> type: api
> agent: backend-engineer
> primary_bc: identity-access (domain 단계에서 확정)
> 생성: 2026-07-05

## Brief

FR-PR-01 사용자 프로필 (이름/아바타/타임존/부서) — `GET/PATCH /api/v1/users/me/profile` + MinIO 아바타 + 프로필 UI.

product 문서: `docs/plan/product/personalization.md §2.1`
- D1. 도메인 — UserProfile
- D2. 명세
- D3. 데이터 모델 — `user_profiles(user_id, display_name, avatar_url, timezone, department)`
- D4. 백엔드 — `GET/PATCH /api/v1/users/me/profile`. MinIO 아바타
- D5. 백엔드 테스트
- D6. 프론트 UI — 프로필 페이지 + 아바타 업로드
- D7. E2E

**미결 결정**: personalization BC는 백엔드 모듈이 없음. user_profiles를 identity-access에 둘지, 신규 모듈을 만들지 domain 단계에서 결정.

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
