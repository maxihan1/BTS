# FR-PM-02 CREATE 게이트 — 목록 새 이슈 버튼 권한 비활성화

> slug: fr-pm-02-create-gate
> type: api (vertical slice: backend + frontend + e2e)
> agent: backend-engineer (+ frontend-engineer, security-engineer 검토)
> primary_bc: identity-access
> 생성: 2026-06-02

## Brief

이슈 목록의 "새 이슈" 버튼을 프로젝트 스코프 CREATE 권한이 없는 사용자에게 비활성화한다.
FR-PM-02 D6/D7(PR #55)은 이슈 *상세* 화면의 수정/삭제 게이트만 다뤘고, 상세 도달자는
이미 멤버라 CREATE 게이트가 no-op이었다. 목록의 "새 이슈"는 프로젝트 스코프 권한(특정
프로젝트에서 이슈를 만들 수 있는가)이라 이슈 키가 아닌 프로젝트 키 기준 조회가 필요하다.

기존 권한 조회 API `GET /api/v1/users/me/issue-permissions?issueKey=`를 프로젝트 스코프
(projectKey)까지 확장한다. fail-closed(권한 미확정 시 비활성), 서버 single source of truth
(Jira mypermissions 방식) 원칙은 D6/D7과 동일하게 유지한다.

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
