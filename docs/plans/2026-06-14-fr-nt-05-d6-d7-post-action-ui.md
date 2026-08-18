# FR-NT-05 D6/D7 PR-B — 워크플로우 전환 post-action 설정 UI + E2E

> slug: fr-nt-05-d6-d7-post-action-ui
> type: ui
> agent: frontend-engineer
> primary_bc: project-workflow (프론트 apps/web)
> 생성: 2026-06-14

## Brief

FR-NT-05 D6/D7 — apps/web 워크플로우 화면에 관리자가 전환별 post-action(CALL_WEBHOOK url/method)을 추가/수정/삭제하는 설정 UI + E2E. 현재 `workflows.$key.tsx`는 읽기 전용 mermaid 다이어그램. 백엔드 API는 PR-A(#143)로 준비됨:
- `GET/POST/PUT/DELETE /api/v1/workflows/{workflowKey}/transitions/{transitionKey}/post-actions` (+`/{id}`), transitionKey=`from__to`.
- 권한 MANAGE_SCHEME(시스템 admin). 요청 {type, config, displayOrder}, 응답 {id, type, config, displayOrder}.

완료 시 **FR-NT-05 [~]→완료 전환**(D6/D7 마킹, product notification-dashboard.md §2.5). 직전: PR2(#141)+PR-A(#143) 머지됨.

- 핵심: admin 전용 게이팅(비admin엔 미노출/403 처리), 전환 선택→post-action 목록/폼, E2E는 MSW로 API mock.

classify: type=ui, agent=frontend-engineer, slug=fr-nt-05-d6-d7-post-action-ui (classify qa 오판정 교정)

## 도메인 정리

- BC: project-workflow(프론트 apps/web). 신규 용어 없음. **실측 선례**:
  - 화면: `workflows.$key.tsx`(읽기전용 mermaid, code-based router), `admin.workflow-schemes.$schemeKey.tsx`(CRUD 선례: MappingTable inline + SchemeMetaPanel + SchemeInUseModal radix Dialog).
  - 타입: `workflow.types.ts` `WorkflowTransitionView{key,name,fromStateKey,toStateKey}` + `transitionKey(from,to)=from__to`.
  - api: `api/workflow-schemes.ts` mutation 선례(apiFetch+Zod+`dataOf` envelope+ApiError code 보존), CSRF는 client.ts credentials:include(선례 그대로 확인).
  - 게이팅: `authStore` `user.isSystemAdmin` + `routeGuard.requireSystemAdmin`.
  - E2E/MSW: `mocks/workflow-handlers.ts`+`workflow-fixtures.ts`, `routes/__tests__/admin.workflow-schemes.$schemeKey.test.tsx` 패턴.
- 기존 결정 충돌: 없음. ADR 불요(PR-A ADR이 백엔드 결정 포괄, 프론트는 선례 계승).

## 스펙

전체. [docs/specs/2026-06-14-fr-nt-05-d6-d7-post-action-ui.md](../specs/2026-06-14-fr-nt-05-d6-d7-post-action-ui.md)

3줄. workflows.$key 하단 admin 섹션(isSystemAdmin)에서 전환 선택→CALL_WEBHOOK post-action CRUD. api/hooks/section/dialog 신규 + workflows.$key 와이어 + MSW/E2E. 백엔드 API=PR-A #143.

Maxi 게이트. 배치=A(workflows.$key 섹션), type=CALL_WEBHOOK, 게이팅=isSystemAdmin.

## Brainstorming Check

✅ 통과. Zod 백엔드 DTO grep 정합·CSRF 선례·플리커 invalidate-only·MSW stateful·셀렉터 컨테이너 한정·type CALL_WEBHOOK 범위.

## Plan

> apps/web. Zod는 백엔드 PR-A DTO 1:1. transitionKey 헬퍼 재사용. CSRF/에러는 workflow-schemes.ts 선례.

### Task 1. api/post-actions.ts — Zod + fetch (list/create/update/delete)
**메타**. agent: `frontend-engineer` · files: [`apps/web/src/api/post-actions.ts`, `apps/web/src/api/__tests__/post-actions.test.ts`] · depends-on: []
**RED**. list/create/update/delete가 올바른 경로·메서드·envelope·Zod 파싱, ApiError code 보존(MSW 또는 fetch mock). **GREEN**. workflow-schemes.ts 패턴 복사: apiGet/apiFetch + dataOf + 스키마(요청 {type,config,displayOrder}/응답 {id,type,config,displayOrder}). transitionKey=`from__to`. **REFACTOR**. 스키마 export 정리.
**검증**: `pnpm --filter web test -- post-actions`

### Task 2. usePostActions hooks (TanStack Query)
**메타**. agent: `frontend-engineer` · files: [`apps/web/src/hooks/use-post-actions.ts`, `apps/web/src/hooks/__tests__/use-post-actions.test.tsx`] · depends-on: [1]
**RED**. usePostActions list query, add/update/remove mutation 성공 시 list invalidate(플리커 회피 invalidate-only). **GREEN**. use-workflow-schemes 선례 패턴. **REFACTOR**. queryKey 상수.
**검증**: `pnpm --filter web test -- use-post-actions`

### Task 3. PostActionFormDialog (radix Dialog, CALL_WEBHOOK url/method)
**메타**. agent: `frontend-engineer` · files: [`apps/web/src/components/workflow/PostActionFormDialog.tsx`, `apps/web/src/components/workflow/__tests__/PostActionFormDialog.test.tsx`] · depends-on: []
**RED**. url/method 입력·검증(http/https·빈값)·추가/수정 겸용·취소/저장. **GREEN**. radix-ui Dialog(SchemeInUseModal 패턴), 제어 폼(useState 또는 RHF, MappingTable 선례). i18n 키. **REFACTOR**. KDoc/접근성(aria).
**검증**: `pnpm --filter web test -- PostActionFormDialog`

### Task 4. PostActionConfigSection (전환 선택 + 목록 + 게이팅)
**메타**. agent: `frontend-engineer` · files: [`apps/web/src/components/workflow/PostActionConfigSection.tsx`, `apps/web/src/components/workflow/__tests__/PostActionConfigSection.test.tsx`] · depends-on: [2, 3]
**RED**. isSystemAdmin true만 렌더, 전환 선택→목록(빈/N건), 추가/수정/삭제 버튼→dialog/mutation, 비admin 미렌더. **GREEN**. hooks+dialog 조합, 전환 목록은 props(workflow.transitions). **REFACTOR**. 목록 테이블 분리.
**검증**: `pnpm --filter web test -- PostActionConfigSection`

### Task 5. workflows.$key.tsx 와이어 + isSystemAdmin 게이팅
**메타**. agent: `frontend-engineer` · files: [`apps/web/src/routes/workflows.$key.tsx`, `apps/web/src/routes/__tests__/workflows.$key.test.tsx`] · depends-on: [4]
**RED**. admin이면 다이어그램 하단 섹션 렌더, 비admin이면 미렌더, 기존 읽기전용 회귀 0. **GREEN**. 조건부 렌더(useAuthStore). **REFACTOR**. KDoc.
**검증**: `pnpm --filter web test -- 'workflows'`

### Task 6. MSW stateful + Playwright E2E
**메타**. agent: `qa-engineer` · files: [`apps/web/src/mocks/post-action-handlers.ts`, `apps/web/tests/e2e/workflow-post-action.spec.ts`] · depends-on: [5]
**작업**. MSW stateful post-action handlers(GET/POST/PUT/DELETE, store 공유 — 메모리 msw-derived-behavior-shared-store-e2e/msw-mutation-stateful-refetch). E2E: admin 추가→목록→수정→삭제(셀렉터 컨테이너 한정), 비admin 미노출. 구현 코드 수정 금지.
**검증**: `pnpm --filter web test:e2e -- workflow-post-action`

### Task 7. FR-NT-05 완료 전수 동기화 (문서)
**메타**. agent: `frontend-engineer`(controller 직접) · files: [`docs/plan/product/notification-dashboard.md`, `docs/plan/fr-index.md`, `docs/plan/README.md`, `CLAUDE.md`, `docs/sdd/09-notifications-slack.md`] · depends-on: []
**작업**. §2.5 D6/D7 [x], FR-NT-05 `[~]`→완료. fr-index/README/CLAUDE/SDD의 FR-NT-05 상태(카운트 123 불변). verify-master-plan exit 0.
**검증**: `bash scripts/verify-master-plan.sh` exit 0.

## Plan 메타
- task 수: 7. 의존: T1→T2, T3 독립, T4←[2,3], T5←[4], T6←[5], T7 독립. T6 qa-engineer, 나머지 frontend-engineer.
- TDD 강제(T1~T5). T6 E2E, T7 문서. 추가 검증: pnpm typecheck/lint/build(CI tsconfig.app).

## 리뷰 결과

### design + eng 집중 직접 리뷰 (2026-06-14, ui — 선례 재사용이라 design-shotgun 회피)
- ✅ 배치 A 자연스럽고 기존 admin CRUD 패턴 재사용. 게이팅 isSystemAdmin이 백엔드 MANAGE_SCHEME+Global과 정합(prod resolver=isSystemAdmin 확인).
- 📌 **config Zod 관대화**. API 제네릭(config=map). 목록은 타 type post-action도 올 수 있어 list 스키마 `config`는 record/passthrough, 폼만 CALL_WEBHOOK {url,method} 검증(T1/T3). 메모리 frontend-zod-backend-dto-contract-gap.
- 📌 낙관적 업데이트 플리커 invalidate-only, E2E MSW stateful 공유 store(T2/T6).
- 📌 type=CALL_WEBHOOK 범위(다른 4종 편집 UI speculative 금지).
- **BLOCKER: 없음.**
