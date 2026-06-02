# FR-CM-01 컴포넌트 관리 프론트엔드 UI + E2E (D6/D7)

> slug: fr-cm-01-component-frontend
> type: ui
> agent: frontend-engineer (D6) + qa-engineer (D7)
> primary_bc: issue-tracking
> 생성: 2026-06-03

## Brief

FR-CM-01(프로젝트별 컴포넌트 CRUD + 컴포넌트 리드)의 백엔드 D1~D5는 PR #59에서 머지 완료. 이번 작업은 남은 **D6(프론트 UI — 컴포넌트 관리 페이지)** + **D7(E2E)**.

- plan 정본 항목: `docs/plan/product/issue-tracking.md` §3.1.1 FR-CM-01 D6/D7 (미체크)
- 백엔드 산출물(참조): PR #59 — `backend/modules/issue-tracking/.../component/*`, ADR `docs/adr/2026-06-02-component-model-and-permission-deferral.md`
- 백엔드 CRUD API(검증 대상): GET/POST `/api/projects/{idOrKey}/components`, PATCH `/api/.../components/{id}` (name/description), PATCH `.../components/{id}/lead`, DELETE `.../components/{id}` — 정확한 경로/계약은 spec 단계에서 백엔드 코드 grep로 확정

### 핵심 선행 learnings (이번 작업 적용)

- frontend-zod-backend-dto-contract-gap — Zod 스키마는 백엔드 DTO를 grep해 정합. invent 금지.
- e2e-msw-serviceworker-block — 새 API는 MSW 핸들러 추가가 정석, 분기순서 백엔드 일치.
- ui-pr-defer-e2e-regression-latent — UI PR(D6)은 기존 E2E 함께 실행. 텍스트 중복 버튼은 컨테이너/testid 한정.
- parallel-fr-overlapping-frontend-infra-collision — 컴포넌트 리드 선택은 기존 fetchUsers/useUsers 정본 재사용(중복 생성 금지).
- zod-v4-uuid-fixture-strictness — fixture UUID는 v4 형식.

## 도메인 정리

- **BC**: issue-tracking (프론트가 이 BC의 컴포넌트 API를 소비)
- **엔티티(프론트 타입 신규)**: `Component { id, projectId, name, description: string|null, leadUserId: string|null }`
- **새 용어**: 없음. "컴포넌트(Component)"는 glossary 기등재("프로젝트 내 하위 영역 분류"). "컴포넌트 리드"는 PR #59 도메인 ADR에 확립.
- **기존 결정 충돌**: 없음.
- **관련 ADR**: `docs/adr/2026-06-02-component-model-and-permission-deferral.md` (PR #59 — 권한 prod 실판정은 FR-PM-03 이연, non-prod는 AlwaysAllow).

### 백엔드 API 계약 (PR #59 코드 grep 확정 — invent 금지)

Base path: `/api/v1/projects/{projectIdOrKey}/components` (projectIdOrKey = UUID 또는 projectKey)

| 메서드 | 경로 | 응답 | 비고 |
|---|---|---|---|
| POST | `` | 201 `{data: ComponentResponse}` | body: `{name(필수,≤255), description?(≤1000), leadUserId?}` |
| GET | `` | 200 `{data: ComponentResponse[]}` | 활성만(deleted_at null), name 오름차순 |
| GET | `/{id}` | 200 `{data: ComponentResponse}` | |
| PATCH | `/{id}` | 200 `{data: ComponentResponse}` | body: `{name?(≤255), description?(≤1000)}` — **null/생략 = 무변경 sentinel** (리드 변경 불가) |
| PATCH | `/{id}/lead` | 200 `{data: ComponentResponse}` | body: `{leadUserId: UUID|null}` — UUID=지정, null=해제 (2-state, assignee 동형) |
| DELETE | `/{id}` | 204 (no body) | 소프트 삭제 |

- **ComponentResponse**: `{ id: UUID, projectId: UUID, name: string, description: string|null, leadUserId: UUID|null }`
- **응답 래퍼**: `DataResponse = { data: ... }` (issues.ts와 동일, 프론트는 `wrapped.data` 추출)
- **에러 바디**: RFC 7807 ProblemDetail + 커스텀 `errorCode`(대문자 스네이크) + `timestamp`.
  - `VALIDATION_FAILED`(400), `PROJECT_NOT_FOUND`(404), `COMPONENT_NOT_FOUND`(404), `COMPONENT_NAME_DUPLICATE`(409), `COMPONENT_ACCESS_DENIED`(403), `COMPONENT_LEAD_NOT_FOUND`(422), `INTERNAL_ERROR`(500)
  - non-prod AlwaysAllow라 403은 실발생 안 함(미래 대비 스키마엔 포함). 인증 401은 client가 공통 처리.

### 프론트 관례 채택 (선례 grep 확정)

- **issue-tracking BC 관례 채택** = `apps/web/src/api/issues.ts` 패턴: 공유 `ApiError`(from `./client`) + `DataResponse` `wrapped.data` 언래핑 + `errorCode` 대문자. **project-members.ts 패턴(커스텀 ApiError + `{error: 소문자}`) 아님** — 계약갭 방지.
- **페이지 위치**: `/projects/$projectKey/settings/components` (선례 `projects.$projectKey.settings.members.tsx` / `.workflow-scheme.tsx` 동형). RouteAdapter + props 기반 Page 패턴(라우터 비의존 단위 테스트).
- **user 인프라 재사용**: `fetchUsers/useUsers`(검색) + `fetchUsersByIds/useUsersByIds`(현재 리드 이름 표시) 정본 재사용. **중복 생성 금지**(parallel-fr-overlapping-frontend-infra-collision).
- CSRF: mutation은 `readXsrfToken()` → `X-XSRF-TOKEN` 헤더(members/issues 선례).

## 스펙

전체 스펙. [docs/specs/2026-06-03-fr-cm-01-component-frontend.md](../specs/2026-06-03-fr-cm-01-component-frontend.md)

핵심 시나리오 요약.
- `/projects/$projectKey/settings/components`에서 활성 컴포넌트 목록(4분기, name 오름차순) — 멤버 설정 페이지 패턴 재사용.
- 추가 Dialog(이름 필수·설명·리드), 행별 수정(name/description, null=무변경)·리드 지정/해제(전용 /lead)·소프트 삭제.
- 리드는 기존 useUsers/useUsersByIds 재사용, mutation은 invalidate-only, errorCode(409 중복/422 리드미존재/404) i18n 매핑.

디자인 접근: 멤버 설정 패턴 재사용 (Maxi 결정 2026-06-03, design-shotgun 스킵).

## Brainstorming Check

✅ 통과 (1회 iteration). 네비 진입점 불필요(직접 URL 관례), description 비우기 sentinel은 plan 검증 이관, 리드 셀렉터 인라인 신규 작성.

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
