# FR-PM-01 프로젝트 행정 (관리자/멤버 관리) — 백엔드 D1~D5

> slug: project-admin-backend
> plan slug: identity/project-admin
> type: auth
> agent: security-engineer
> primary BC: identity-access
> 생성: 2026-06-01

## Brief

FR-PM-01 — 프로젝트 행정 (관리자/멤버 관리). 우선순위 필수, 선행 §2.1(로그인, 완료).
이번 PR 범위는 **백엔드 D1~D5**. D6(프론트 UI)·D7(E2E)은 후속 PR.

- D1. 도메인 — ProjectRole (책임. security-engineer)
- D2. 명세 — 관리자 멤버 초대/제거 (책임. security-engineer)
- D3. 데이터 모델 — `project_memberships(project_id, user_id, role)` (책임. db-engineer)
- D4. 백엔드 — CRUD API + 가드 (책임. security-engineer)
- D5. 백엔드 테스트 (책임. security-engineer)

참고. 이 FR은 미뤄둔 회원가입(전역 admin 권한)의 선행 작업. FR-AU-05 노트에서 "FR-PM-01 선행 필요" 명시.

분류 교정. classifier가 "E2E" 키워드로 qa 오분류 → auth/security-engineer로 교정 (docs/plan/product/identity-access.md §4.1 책임 표기 근거).

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
