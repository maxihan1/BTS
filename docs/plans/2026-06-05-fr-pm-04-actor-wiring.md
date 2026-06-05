# FR-PM-04 후속 — 워크플로우 스킴 컨트롤러 actor 결선

> slug: fr-pm-04-actor-wiring
> type: auth
> agent: security-engineer
> primary_bc: project-workflow
> 생성: 2026-06-05

## Brief

FR-PM-04(워크플로우 스킴 권한 prod resolver, PR #73)의 선행 부채 C2 해소.
현재 워크플로우 스킴 컨트롤러 2개가 인증 주체 대신 하드코딩 system actor를 넘겨,
prod 프로파일에서 전 스킴 API가 fail-closed(403)된다.

- 대상 파일(2개, actor 호출 8곳):
  - `ProjectWorkflowSchemeController.kt` — `ActorId(SYSTEM_ACTOR_UUID)` 2곳
  - `WorkflowSchemeController.kt` — `systemActor()` 6곳
- 해소: `@AuthenticationPrincipal jwt: Jwt`에서 인증 사용자 UUID 추출 → actor 자리에 결선.
- 참조 패턴(identity-access): AuthController/PasswordController/WhoamiController/ProjectMemberController.
- 결정 필요: PAT 경로(jwt=null) 정책 — 스킴 관리 API를 PAT로 허용할지(세션 관리 API는 Jira식 PAT 403).
- 범위: 순수 백엔드, UI 없음.

분류: classifier가 type=ui/frontend로 오판 → Maxi 확정으로 type=auth/security-engineer 정정.

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
