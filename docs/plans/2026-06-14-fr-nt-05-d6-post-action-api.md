# FR-NT-05 D6 선행 PR-A — 워크플로우 전이 post-action CRUD API

> slug: fr-nt-05-d6-post-action-api
> type: api
> agent: backend-engineer (권한 가드는 security-engineer 검토)
> primary_bc: project-workflow
> 생성: 2026-06-14

## Brief

FR-NT-05 D6(워크플로우 post-action 설정 프론트 UI)의 백엔드 enabler. 현재 post-action(CALL_WEBHOOK의 url/method 등)은 `YamlSeedService` → `workflow_post_actions`(V200) 부팅 시드 전용이고 **런타임 편집 엔드포인트가 없다**. 관리자가 전이별 post-action을 추가/수정/삭제할 수 있는 REST API(GET/POST/PUT/DELETE `.../transitions/{transitionKey}/post-actions`)를 admin 권한으로 신설한다.

- workflow_post_actions 스키마 기존(id/transition_id/type/config jsonb/display_order).
- **핵심 결정거리**: YAML seed와 런타임 편집 공존(시드 재적재가 런타임 편집을 덮어쓰지 않게), 편집 가능 post-action type 범위(CALL_WEBHOOK만? 전부?), 권한 모델(전역 admin vs 프로젝트 admin), CALL_WEBHOOK config 검증(url 형식·SSRF 사전검증 위치).
- 직전: PR2(#141) notification 디스패처 백엔드 완성. 후속: PR-B(프론트 설정 UI + E2E).

classify: type=api, agent=backend-engineer, primary_bc=project-workflow, slug=fr-nt-05-d6-post-action-api

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
