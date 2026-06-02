# FR-CM-01 컴포넌트 관리 프론트엔드 (D6/D7) — 스펙

> slug: fr-cm-01-component-frontend · BC: issue-tracking · type: ui
> 백엔드: PR #59 머지 완료. 본 스펙은 프론트 UI(D6) + E2E(D7) 한정.
> 디자인 접근: 멤버 설정 페이지(`projects.$projectKey.settings.members`) 패턴 재사용 (Maxi 결정 2026-06-03).

## 사용자 시나리오 (Given-When-Then)

**S1. 목록 조회**
- Given 프로젝트 PROJ의 설정 권한이 있는 사용자가
- When `/projects/PROJ/settings/components`에 진입하면
- Then 활성 컴포넌트가 name 오름차순으로 목록 표시된다. 각 행은 이름 · 설명 · 리드(표시명) · 액션(수정/삭제).

**S2. 생성**
- Given 컴포넌트 목록 화면에서
- When "컴포넌트 추가"를 눌러 이름(필수)·설명(선택)·리드(선택)를 입력하고 저장하면
- Then 새 컴포넌트가 목록에 추가되고 Dialog가 닫힌다. (낙관적 아님 — invalidate 후 refetch)

**S3. 이름 중복**
- Given 이미 "Backend" 컴포넌트가 있는 프로젝트에서
- When 같은 이름 "Backend"로 생성/수정하면
- Then 409 `COMPONENT_NAME_DUPLICATE` → "이미 같은 이름의 컴포넌트가 있습니다" 폼/토스트 에러. Dialog 유지.

**S4. 수정 (name/description)**
- Given 컴포넌트 행에서
- When 수정 Dialog로 이름·설명을 바꿔 저장하면
- Then 변경된 값만 PATCH(null=무변경 sentinel)되고 목록이 갱신된다.

**S5. 리드 지정/해제**
- Given 컴포넌트 행에서
- When 리드 셀렉터로 사용자를 선택(또는 "미지정" 선택)하면
- Then `PATCH /{id}/lead`로 지정/해제되고 행의 리드 표시가 갱신된다.

**S6. 삭제**
- Given 컴포넌트 행에서
- When 삭제를 확인하면
- Then 소프트 삭제(204)되고 목록에서 사라진다.

**S7. 리드 미존재(422)**
- Given 리드로 지정하려는 userId가 실재하지 않을 때(이론상 셀렉터는 실재 user만 노출하므로 방어적)
- Then 422 `COMPONENT_LEAD_NOT_FOUND` → 토스트 에러, 행 롤백.

**S8. 프로젝트 미존재/접근불가(404)**
- Given 미존재 projectKey 또는 접근불가
- When 페이지 진입 시 GET 목록이 404 `PROJECT_NOT_FOUND`
- Then 멤버 설정 페이지의 `ProjectNotFoundScreen`과 동일한 접근 불가 안내 화면. (members 선례 동형)

## 기능 요구사항 (FR)

- **FR1** 컴포넌트 목록 조회 — 4분기(로딩 스켈레톤/에러/빈/목록), name 오름차순.
- **FR2** 컴포넌트 생성 — 이름(필수,≤255)·설명(선택,≤1000)·리드(선택) Dialog.
- **FR3** 컴포넌트 수정 — name/description Dialog. 변경 필드만 전송(sentinel).
- **FR4** 리드 지정/해제 — 전용 `/lead` 엔드포인트. user 검색 셀렉터 + "미지정".
- **FR5** 컴포넌트 삭제 — 확인 후 소프트 삭제.
- **FR6** 에러 매핑 — 409/422/404/400 errorCode → i18n 메시지(폼 또는 toast).
- **FR7** 리드 표시명 — `useUsersByIds`로 현재 리드 username/displayName 안정 표시.

## 비기능 요구사항 (NFR)

- **NFR1** TanStack Query 캐시 — 목록 queryKey `['components', projectKey]`. mutation은 invalidate-only(메모리 mutation-setquerydata-partial-response-flicker 회피).
- **NFR2** Zod 스키마는 백엔드 `ComponentResponse` DTO와 1:1(`{data:...}` 래퍼). issues.ts 관례(공유 ApiError + wrapped.data).
- **NFR3** CSRF — mutation에 `X-XSRF-TOKEN`(readXsrfToken).
- **NFR4** 접근성 — 목록 `role`, 액션 버튼 aria-label, Dialog 포커스 트랩(shadcn).
- **NFR5** i18n — 라벨/에러 메시지 `component-labels.ts`로 분리(project-member-labels 선례).
- **NFR6** TypeScript strict — `tsc -p tsconfig.app.json` 통과(메모리 ci-typecheck-tsconfig-app-vs-local).

## API 인터페이스 (REST) — 백엔드 PR #59 확정 (도메인 정리 참조)

Base `/api/v1/projects/{projectIdOrKey}/components`: POST(201) · GET(200 배열) · GET/{id}(200) · PATCH/{id}(200, name/description null=무변경) · PATCH/{id}/lead(200, UUID|null) · DELETE/{id}(204). 응답 `{data: ComponentResponse}`. errorCode: VALIDATION_FAILED/PROJECT_NOT_FOUND/COMPONENT_NOT_FOUND/COMPONENT_NAME_DUPLICATE/COMPONENT_ACCESS_DENIED/COMPONENT_LEAD_NOT_FOUND/INTERNAL_ERROR.

## 데이터 모델 변경

없음(프론트 전용). 신규 프론트 타입 `Component` + Zod `componentResponseSchema`.

## 엣지 케이스

- **null=무변경 sentinel** — 수정 Dialog에서 변경 안 한 필드는 PATCH payload에서 제외(또는 기존값 그대로). description을 빈 문자열로 보내는 경우 백엔드 도메인 검증 동작 확인 필요(spec 단계 미확정 → plan에서 백엔드 rename 검증 재확인).
- **리드 셀렉터 검색 debounce** — assignee 셀렉터(IssueMetaPanel) 선례 재사용.
- **빈 목록** — "아직 컴포넌트가 없습니다" 안내 + 추가 유도.
- **동일 텍스트 액션 버튼 다수** — 행마다 "수정"/"삭제" 반복 → E2E는 행 컨테이너 한정 셀렉터(메모리 ui-pr-defer-e2e-regression-latent, playwright-getbyrole-exact-strict-mode).
- **fixture UUID** — v4 형식(메모리 zod-v4-uuid-fixture-strictness).

## 제약 조건

- user 조회는 기존 `fetchUsers/useUsers` + `fetchUsersByIds/useUsersByIds` 재사용. 신규 user API 생성 금지(parallel-fr-overlapping-frontend-infra-collision).
- 권한 UI 게이팅 — non-prod AlwaysAllow라 403 미발생. ADMIN 게이팅은 멤버 설정 선례(useAuthStore) 참고하되, FR-CM-01 백엔드가 actor 추출을 FR-PM-03로 이연했으므로 **UI는 권한 분기 최소화**(페이지 접근은 라우트 requireAuth + 404 처리로 충분). 과한 권한 UI 추가 금지.

## 측정 가능한 완료 기준

- [ ] 단위 테스트(vitest) — 컴포넌트 목록 4분기, 생성/수정/리드/삭제 mutation, 에러 매핑, Zod 파싱. 전체 스위트 그린.
- [ ] `tsc -p tsconfig.app.json` 통과 + ktlint 무관(프론트), `pnpm --filter @bts/web lint` 통과.
- [ ] MSW 핸들러(component-handlers.ts) — stateful CRUD + errorCode 토글. 분기순서 백엔드 일치.
- [ ] E2E(Playwright) — S1 목록, S2 생성, S4 수정, S5 리드, S6 삭제 happy path. 기존 E2E 회귀 0(UI PR은 기존 E2E 함께 실행).
- [ ] 라우트 `/projects/$projectKey/settings/components` 등록 + requireAuth.

## Brainstorming Check

✅ 통과 (1회 iteration, gap 2건 보강).
- **gap(네비 진입점)**: 멤버/워크플로우 설정 페이지가 어디서도 링크 안 되고 직접 URL 접근만 = 현 관례. 컴포넌트 설정도 네비 진입점 신규 불필요(스코프 확대 회피). → S/FR 추가 없음.
- **gap(description 비우기 sentinel)**: PATCH `null=무변경`이라 빈 문자열 처리는 백엔드 도메인 update 로직 재확인 필요 → plan §검증 항목으로 이관.
- **발견(리드 셀렉터)**: assignee 셀렉터가 IssueMetaPanel 인라인이라 재사용 컴포넌트 부재 → 리드 셀렉터는 동일 인라인 패턴(useUsers 검색 + "미지정")으로 신규 작성. plan task 분해 반영.
