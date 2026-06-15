# FR-NT-05 D6/D7 PR-B — 워크플로우 전이 post-action 설정 UI + E2E 스펙

> slug: fr-nt-05-d6-d7-post-action-ui
> BC: project-workflow (프론트 apps/web)
> type: ui
> 관련: FR-NT-05 D6(UI)/D7(E2E). 백엔드 API=PR-A #143, 디스패처=PR2 #141.

## 개요

시스템 관리자가 워크플로우 상세 화면(`workflows.$key.tsx`)에서 전이를 골라 `CALL_WEBHOOK` post-action(url/method)을 추가/수정/삭제할 수 있는 admin 전용 설정 UI + E2E. 완료 시 FR-NT-05 D6/D7이 채워지고 FR-NT-05가 `[~]`→완료로 전환된다.

## Maxi 게이트 확정 결정

- **배치(A)**: `workflows.$key.tsx`의 읽기 전용 mermaid 다이어그램 **하단에 admin 전용 "전이 post-action 설정" 섹션** 추가. 전이 목록(workflow.transitions, 이미 로드됨)에서 선택→해당 전이의 post-action 목록/폼.
- **type 범위**: CALL_WEBHOOK 중심(FR-NT-05 webhook). 목록은 기존 post-action(모든 type) 표시하되 추가/편집 폼은 CALL_WEBHOOK(url/method). (다른 4종 편집 UI는 FR 밖, speculative 금지.)
- **권한 게이팅**: `useAuthStore` `user.isSystemAdmin === true`일 때만 섹션 렌더. 백엔드 403은 백스톱(toast 에러).
- **패턴**: api=`workflow-schemes.ts` mutation 선례(apiFetch+Zod+CSRF 동일), Dialog=radix-ui(SchemeInUseModal 선례), 폼=MappingTable/SchemeMetaPanel 선례, E2E=workflow-handlers MSW stateful.

## 사용자 시나리오 (Given-When-Then)

1. **admin이 webhook 추가**
   - Given. isSystemAdmin인 사용자가 `/workflows/software-default` 진입.
   - When. 하단 설정 섹션에서 전이 `open__in_progress` 선택 → "Webhook 추가" → url/method 입력 → 저장.
   - Then. `POST .../transitions/open__in_progress/post-actions` 호출, 목록에 새 행 표시(낙관적/invalidate).

2. **목록/수정/삭제**
   - 전이 선택 시 `GET .../post-actions` 목록(displayOrder). 행의 수정→`PUT`, 삭제→`DELETE` + 목록 갱신.

3. **비admin 게이팅**
   - Given. isSystemAdmin=false.
   - Then. 설정 섹션 미렌더(다이어그램만). (직접 API 호출 시도해도 백엔드 403.)

4. **검증/에러**
   - url 빈값/비-http → 클라이언트 검증 또는 백엔드 400 → toast. 백엔드 403 → 권한 에러 toast.

## 기능 요구사항 (FR)

- **FR1.** `api/post-actions.ts` — Zod 스키마(요청 {type,config,displayOrder}, 응답 {id,type,config,displayOrder}, 백엔드 DTO 1:1) + list/create/update/delete fetch 함수(workflow-schemes.ts 패턴: apiFetch, dataOf envelope, ApiError code 보존).
- **FR2.** TanStack Query hooks — `usePostActions(workflowKey, transitionKey)`(list query), `useAddPostAction`/`useUpdatePostAction`/`useRemovePostAction` mutation(성공 시 list invalidate).
- **FR3.** `PostActionConfigSection` — 전이 선택(select/list) + 선택 전이의 post-action 목록(테이블) + "Webhook 추가" 버튼. isSystemAdmin 게이팅.
- **FR4.** `PostActionFormDialog`(radix Dialog) — CALL_WEBHOOK url/method 입력 폼(추가/수정 겸용). url 형식 클라이언트 검증(http/https).
- **FR5.** `workflows.$key.tsx`에 PostActionConfigSection을 다이어그램 하단에 조건부(isSystemAdmin) 렌더. 기존 read-only 동작 회귀 0.
- **FR6.** E2E — MSW stateful post-action handlers(GET/POST/PUT/DELETE, 메모리 msw-mutation-stateful-refetch 준수) + Playwright 시나리오(admin 추가→목록→수정→삭제, 비admin 미노출).

## 비기능 요구사항 (NFR)

- **권한**. isSystemAdmin 게이팅은 UX(노출 제어), 진짜 경계는 백엔드 MANAGE_SCHEME(403). 프론트 게이팅만으로 보안 가정 금지.
- **계약 정합**. Zod 스키마는 백엔드 PR-A DTO와 1:1(메모리 frontend-zod-backend-dto-contract-gap — invent 금지, PR-A spec grep). transitionKey=`from__to`(transitionKey 헬퍼 재사용).
- **회귀**. 기존 workflows.$key E2E/단위(읽기 전용 다이어그램) 회귀 0. 텍스트 중복 셀렉터는 컨테이너 한정(메모리 playwright-getbyrole-exact-strict-mode).
- **i18n**. 사용자 노출 문자열은 ko 로케일 키(하드코딩 금지, 콜론 종결 금지 — ko.test).

## API 인터페이스

소비(PR-A 제공). `GET/POST/PUT/DELETE /api/v1/workflows/{workflowKey}/transitions/{transitionKey}/post-actions(/{id})`. 신규 백엔드 0.

## 데이터 모델 변경

없음(프론트 전용).

## 엣지 케이스

- 전이에 post-action 0건 → 빈 목록 안내.
- url 비-http/빈값 → 클라 검증 + 백엔드 400 toast.
- 403(권한) → toast, 섹션은 isSystemAdmin이라 보통 미도달.
- 비admin → 섹션 미렌더.
- 낙관적 업데이트 부분응답 플리커(메모리 mutation-setquerydata-partial-response-flicker) → invalidate-only 또는 캐시머지.

## 측정 가능한 완료 기준

1. 단위(vitest+MSW) — api 함수·hooks·PostActionConfigSection·PostActionFormDialog: 추가/수정/삭제/검증/게이팅.
2. E2E(Playwright+MSW stateful) — admin webhook 추가→목록→수정→삭제, 비admin 미노출.
3. 기존 workflows.$key 단위/E2E 회귀 0.
4. pnpm typecheck/lint/test/build green(CI tsconfig.app — 메모리 ci-typecheck-tsconfig-app-vs-local).
5. **FR-NT-05 완료 전수 동기화** — D6/D7 [x], FR-NT-05 [~]→완료, verify-master-plan exit 0(카운트 123 불변, 상태 변경).

## Brainstorming Check

✅ 통과(직접). 주의: Zod 백엔드 DTO grep 정합·CSRF는 workflow-schemes.ts 선례 그대로·낙관적 업데이트 플리커는 invalidate-only·E2E MSW stateful·셀렉터 컨테이너 한정. type=CALL_WEBHOOK 범위(다른 4종 speculative 금지).
