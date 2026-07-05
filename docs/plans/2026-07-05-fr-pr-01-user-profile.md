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

## 도메인 정리

- **BC (논리)**: personalization / **모듈 (물리)**: identity-access (Maxi 확정 — 논리 BC ≠ 물리 모듈, FR-UX-01 선례)
- **영향 엔티티**: `UserProfile` (신규), `User` (기존 — display_name 재사용)
- **데이터 모델 (Option A, Maxi 확정)**:
  - `users.display_name` — '이름' 편집 대상, 여기 유지 (이관/중복 없음)
  - `user_profiles(user_id PK/FK CASCADE, avatar_url, timezone, department)` — 신규
  - 프로필 조회 = users JOIN user_profiles / 이름 편집 = users 업데이트 / 아바타·타임존·부서 편집 = user_profiles 업데이트
- **새 용어**: "UserProfile" (사용자 프로필 — 이름/아바타/타임존/부서). glossary 추가 후보 (Maxi 승인 대기)
- **아바타**: identity-access 자체 MinIO 배선 신설 (issue-tracking 첨부 보안 패턴 재사용 — MIME 화이트리스트·크기 제한·nosniff)
- **FR-PR-04 경계**: LDAP 동기화 vs 사용자 편집 **출처 분리(source 컬럼)** 는 FR-PR-04로 미룸. 본 테이블은 source 컬럼 추가 여지 유지
- **기존 결정 충돌**: 없음. `PreferencesController` stub(`/api/v1/users/me/preferences`)은 CSRF 시연용 — 경로 다름(`/profile`), 무충돌
- **관련 ADR**: [docs/decisions/2026-07-05-fr-pr-01-user-profile-placement.md](../decisions/2026-07-05-fr-pr-01-user-profile-placement.md) (생성됨)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
