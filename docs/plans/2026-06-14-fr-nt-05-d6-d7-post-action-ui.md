# FR-NT-05 D6/D7 PR-B — 워크플로우 전이 post-action 설정 UI + E2E

> slug: fr-nt-05-d6-d7-post-action-ui
> type: ui
> agent: frontend-engineer
> primary_bc: project-workflow (프론트 apps/web)
> 생성: 2026-06-14

## Brief

FR-NT-05 D6/D7 — apps/web 워크플로우 화면에 관리자가 전이별 post-action(CALL_WEBHOOK url/method)을 추가/수정/삭제하는 설정 UI + E2E. 현재 `workflows.$key.tsx`는 읽기 전용 mermaid 다이어그램. 백엔드 API는 PR-A(#143)로 준비됨:
- `GET/POST/PUT/DELETE /api/v1/workflows/{workflowKey}/transitions/{transitionKey}/post-actions` (+`/{id}`), transitionKey=`from__to`.
- 권한 MANAGE_SCHEME(시스템 admin). 요청 {type, config, displayOrder}, 응답 {id, type, config, displayOrder}.

완료 시 **FR-NT-05 [~]→완료 전환**(D6/D7 마킹, product notification-dashboard.md §2.5). 직전: PR2(#141)+PR-A(#143) 머지됨.

- 핵심: admin 전용 게이팅(비admin엔 미노출/403 처리), 전이 선택→post-action 목록/폼, E2E는 MSW로 API mock.

classify: type=ui, agent=frontend-engineer, slug=fr-nt-05-d6-d7-post-action-ui (classify qa 오판정 교정)

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
