# FR-PM-03 D6/D7 — 버전/컴포넌트 권한 게이팅 UI + E2E

> slug: fr-pm-03-d6-d7-permission-gating-ui
> type: feature (혼합 — backend identity-access + frontend apps/web + qa)
> agent: frontend-engineer(주축) + security-engineer(권한질의 API) + qa-engineer(E2E)
> 생성: 2026-06-03

## Brief

FR-PM-03 backend(D1~D5, PR #70)가 prod 권한 리졸버 + MANAGE_COMPONENTS/MANAGE_VERSIONS
매트릭스(PROJECT_ADMIN 전용)를 채웠다. D6/D7은 그 권한을 프론트에서 게이팅한다.

- D6. 권한 없는 사용자에게 버전/컴포넌트 **관리 버튼(생성/수정/삭제) 비활성화**.
- D7. E2E — PROJECT_ADMIN 관리 가능 / 권한 없는 사용자 차단 시나리오.

**핵심(이전 세션 코드리뷰 발견)**. 기존 `MyProjectPermissionController`
(`GET /api/v1/users/me/project-permissions`)는 `IssuePermission.CREATE`만 반환 →
MANAGE_COMPONENTS/MANAGE_VERSIONS 미노출. **D6에 백엔드 권한 질의 API 확장 필요**.
그 컨트롤러가 Component/VersionPermissionResolver(@Profile prod)를 소비하게 되면
**identity-access용 non-prod fallback 빈 필수**(메모리 profile-scoped-bean-boot-failure,
PR #55의 66-test 연쇄 회피).

선례. FR-PM-02 D6/D7(PR #55) 이슈 생성 게이팅, FR-CM-01(PR #64)/FR-VR-01(PR #68) 프론트
관리 UI(`/projects/$projectKey/settings` 버전·컴포넌트). use-project-permissions 훅 +
project-permission-handlers MSW.

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
