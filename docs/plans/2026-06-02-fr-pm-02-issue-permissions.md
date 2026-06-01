# FR-PM-02 이슈 등록/수정/삭제 권한 분리 (백엔드 슬라이스 D1~D5)

> slug: fr-pm-02-issue-permissions
> plan slug (FR 추적): identity/issue-permissions
> type: auth
> agent: security-engineer
> primary BC: identity-access (+ issue-tracking 이슈 API 가드 지점)
> 생성: 2026-06-02

## Brief

역할(role) × 권한(permission)을 매트릭스로 정의해, 이슈 등록/수정/삭제를 역할별로 분리 통제한다 (Jira permission scheme 방식). FR-PM-01(ProjectMembership: PROJECT_ADMIN/MEMBER)이 선행 토대.

이번 작업 범위 — 백엔드 D1~D5만. 프론트 UI(D6)/E2E(D7)는 후속 PR로 분리 (FR-PM-01 패턴 답습).

- D1. 도메인 — Permission (CREATE_ISSUE / EDIT_ISSUE / DELETE_ISSUE)
- D2. 명세 — 역할 × 권한 매트릭스
- D3. 데이터 모델 — `permission_schemes` + `role_permissions`
- D4. 백엔드 — `@PreAuthorize("hasPermission(...)")` 가드
- D5. 백엔드 테스트 — 권한 매트릭스 전수

사용자 원문. "FR-PM-02 이슈 등록/수정/삭제 권한 분리 — 백엔드 슬라이스 D1~D5 ... 프론트 UI(D6)/E2E(D7)는 후속 PR. 선행 FR-PM-01 완료."

classify 결과. type=auth / agent=security-engineer / primary_bc=identity-access

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
