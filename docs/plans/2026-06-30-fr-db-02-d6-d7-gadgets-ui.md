# FR-DB-02 D6/D7 — 가젯 시스템 프론트엔드 UI + E2E

> slug: fr-db-02-d6-d7-gadgets-ui
> type: ui
> agent: frontend-engineer (E2E task만 qa-engineer)
> primary_bc: notification-dashboard
> 생성: 2026-06-30

## Brief

FR-DB-02 "가젯 시스템(10종+ 표준)"의 D6(프론트 UI — 가젯 컴포넌트 + 카탈로그) + D7(E2E)을 구현한다.
백엔드 D1~D5는 PR #205로 이미 머지됨.

**핵심 deviation (ADR 2026-06-29-fr-db-02-gadget-system, Maxi 확정)**.
- 별도 `dashboard_gadgets` 테이블 미채택 — 가젯은 기존 `dashboards.layout` JSONB 배열 항목으로 임베드(`{i,x,y,w,h,gadgetType,config}`).
- 가젯 **데이터**는 프론트가 기존 BC API(/search/aql 등) 직접 호출(SDD 14.4).
- notification BC는 **설정 저장·검증 + 카탈로그 API**(`GET /dashboards/gadget-catalog`)만 담당.
- MVP 가시 가젯 = AQL/정적 6종(즉시) + 집계 3종(집계 PR 후) + 선행 FR 의존 3종(후속). 카탈로그 enum 12종 정의.

**분류 메모**. classifier가 'E2E' 키워드로 qa 오판 → ui로 교정(FR-DB-01/FR-BD-01/FR-SR-01 D6/D7 선례 일관).

## 도메인 정리

- **BC**: notification-dashboard (`com.bts.notification.dashboard`). 프론트 전용 PR — 백엔드 도메인 무변경.
- **기존 결정 충돌**: 없음. ADR `2026-06-29-fr-db-02-gadget-system`가 deviation(layout JSON 임베드 + 프론트 직접 fetch)을 이미 채택.
- **관련 ADR**: [docs/decisions/2026-06-29-fr-db-02-gadget-system.md](../decisions/2026-06-29-fr-db-02-gadget-system.md), spec(PR1) [docs/specs/2026-06-29-fr-db-02-gadgets.md](../specs/2026-06-29-fr-db-02-gadgets.md)

### 백엔드 #205 계약 (실측 — 프론트가 1:1 미러)

- **GadgetType 12종**, MVP enabled=true 6종: `assigned_to_me`, `recently_created`, `filter_result`, `issue_count`(ISSUE), `text_widget`, `link_list`(STATIC). 나머지 6종(pie/bar/created_vs_resolved/sprint_burndown=CHART, activity_stream/comments_recent=ACTIVITY) enabled=false → **저장 시 400(EC10)**. MVP 미노출.
- **FieldType**: STRING, INT, UUID, ENUM, ARRAY, URL.
- **카탈로그 API** `GET /api/v1/dashboards/gadget-catalog` → `{ data: { gadgets: [{ type, category, label, enabled, configFields: [{key,type,required,minLength?,maxLength?,min?,max?,enumValues?,itemSchema?,minItems?,maxItems?}], requireAtLeastOne: [[...]] }] } }`. ConfigFieldDto는 @JsonInclude(NON_NULL) → 프론트 Zod는 누락 필드 optional.
- **layout 항목**: `{i,x,y,w,h, gadgetType?, config?}`. gadgetType optional(legacy 타일 호환, Gap A). MAX_GADGETS=50, layout 64KB.
- **per-type config**(저장 검증): assigned_to_me{maxItems?1~50}, recently_created{projectKey?≤100, maxItems?1~50}, filter_result{filterId?UUID | aql?≤2000 (택1필수), maxItems?}, issue_count{filterId?UUID | aql?≤2000 (택1필수)}, text_widget{markdown 1~10000 필수}, link_list{links 1~20 of {label 1~100, url http/https ≤2000} 필수}.
- 에러: 400 NOTIF_DASHBOARD_INVALID (detail 일반 메시지 — 위반 상세는 로그만). 프론트는 클라측 검증으로 구체 안내.

### 프론트 인프라(실측 — 통합 지점)

- 라우트: `routes/dashboards.tsx`(목록), `routes/dashboards.$dashboardId.tsx`(상세/편집). router.ts adapter 패턴.
- 그리드: `components/dashboard/DashboardGrid.tsx`(WidthProvider RGL 12컬럼) + `DashboardTile.tsx`(현재 title+placeholder만 렌더). 저장=serializeLayout(tiles)+version PATCH, OCC 409 시 로컬 보존·invalidate 금지.
- api: `api/dashboards.ts` — layout=`z.string()`, `lib/dashboard-layout.ts` parseLayout/serializeLayout, `DashboardTile` 인터페이스 `{i,x,y,w,h,title}`. 쓰기=apiFetch(+CSRF), 읽기=apiGet.
- 데이터 fetch 재사용: `api/search.ts` `searchAql({projectKey,query,page?,size?})` → Spring Page, `api/saved-filters.ts` `fetchFilter(id)` → {aqlQuery, projectKey,...}.
- MSW: `mocks/dashboard-handlers.ts`(stateful dashboardStore). 카탈로그 핸들러 신규 필요.
- E2E: `e2e/dashboard.spec.ts`(S1~S8, RGL 드래그 SKIP).
- 공유 UI: `components/ui/`(button/input/select/dropdown-menu/card/form). **Dialog/Modal 컴포넌트 존재 여부 spec서 확인**(카탈로그 모달용).

### 새 용어

- "가젯(Gadget)" — glossary 독립 항목 후보(현재 대시보드 항목 내 설명만). ADR §결과대로 Maxi 승인 후 머지 단계 동기화.

### ★핵심 리스크 — 데이터 소스 갭 (실측 확정 → spec서 Maxi 결정 필수)

**백엔드 spec(PR1)이 가정한 데이터 소스(POST /search/aql)가 실제 인프라와 어긋난다.** 실측:
- `searchAql({projectKey,query,...})` — **projectKey 필수**(AqlSearchRequest @NotBlank, "MVP 단일 프로젝트 스코프 필수"). 지원 필드 `AqlFields.MVP_FIELDS = {status,label,summary,priority,text}`만. **assignee/reporter/component/project = PLANNED_FIELDS(미지원)**. ORDER BY 지원하나 정렬 가능 필드 제한(created 미포함 추정).
- `fetchIssues({projectKey,...,filter:{assigneeIds,statusKeys,labels,componentIds}})` — **projectKey 필수**, 정렬 파라미터 없음.
- `fetchFilter(id)` → SavedFilter{projectKey, aqlQuery} → searchAql. ✅

**가젯별 fetch 가능성**:
| 가젯 | 가능? | 경로 |
|---|---|---|
| filter_result(filterId) | ✅ | fetchFilter→searchAql |
| issue_count(filterId) | ✅ | 동일, page.totalElements |
| text_widget | ✅ | 정적 |
| link_list | ✅ | 정적 |
| filter_result/issue_count(aql 직접) | ⚠️ | projectKey 출처 없음 |
| assigned_to_me | ⚠️ | projectKey config 없음 + assignee AQL 미지원. fetchIssues는 projectKey 필수 |
| recently_created | ⚠️ | created 정렬 어느 API도 미지원 |

**spec서 결정할 옵션(초안)**:
- (A) D6/D7 MVP를 **확실히 가능한 4종**(filter_result·issue_count[filterId 중심]·text_widget·link_list)으로 한정. assigned_to_me/recently_created는 데이터 인프라(전역검색·assignee AQL·created 정렬) 완료 후 연기. 카탈로그 enabled=true와의 불일치 처리 방법(프론트 "준비 중" 비활성 vs 백엔드 enabled 조정) 결정.
- (B) 가젯 설정 폼에 **projectKey 선택**을 추가 → assigned_to_me=projectKey+assignee필터(fetchIssues), recently_created=projectKey(정렬은 백엔드 기본 의존). config projectKey가 백엔드 스키마와 정합하는지(assigned_to_me는 미정의=forward-compat 무시) 확인.
- (C) 데이터 소스 인프라(assignee/created AQL or 전역검색)를 issue-tracking/search BC에 신축 — 본 프론트 PR 범위 밖·BC 격리 → 별도 PR, D6/D7 지연.

→ **Maxi 결정 사항. 추측 구현 금지.**

### ✅ 결정 (Maxi 확정 2026-06-30) — 프로젝트 지정형 6종

FR-API-01과 무관(FR-API-01=cursor+OpenAPI, 데이터소스 무변경). 실측 재확인: 이슈 목록 `GET /issues`는 **기본 정렬 created_at DESC**(IssueRepository.kt:2364) + **assignee 필터 기존 존재**(FR-API-01 FR-3) → projectKey만 지정하면 6종 전부 지금 구현 가능. 진짜 제약은 "전역 조회 불가"뿐인데 이는 별도 FR(미채택).

**MVP 6종 데이터 소스 확정**:
| 가젯 | fetch 경로 |
|---|---|
| assigned_to_me | `fetchIssues({projectKey, filter:{assigneeIds:[currentUserId]}})` — projectKey+담당자필터. currentUserId=whoami |
| recently_created | `fetchIssues({projectKey, page:0, size:maxItems})` — 기본 created_at DESC |
| filter_result | filterId→`fetchFilter`→{projectKey,aqlQuery}→`searchAql`; 또는 aql직접+가젯 projectKey |
| issue_count | 위와 동일, `page.totalElements`만 표시 |
| text_widget | 정적 markdown 렌더 |
| link_list | 정적 링크 목록 렌더 |

**파생 결정(→ spec서 정밀화)**:
- **이슈 가젯은 projectKey를 받는다**(프로젝트 지정형). `assigned_to_me` config 백엔드 스키마엔 projectKey 부재 → **백엔드 GadgetType 카탈로그에 projectKey 필드 보강 필요**(same-BC view/도메인 layer 예외, memory `bc-isolation-frontend-same-bc-view-patch` 옵션C 패턴). 카탈로그=단일 진실원천이므로 폼 자동생성 정합 위해 백엔드 노출이 정석. filter_result/issue_count의 aql 직접 경로도 projectKey 필요 여부 spec 확정.
- 이 PR은 **순수 프론트가 아니라 "프론트 + notification BC 가젯 카탈로그 소폭 보강"** — 게이트1에서 Maxi 재확인.
- "전역 가젯"은 후속(전역 이슈목록/검색 FR 선행).

### 기타 리스크
- **MVP 노출 6종 한정**. 카탈로그 모달은 enabled=true만 추가 가능(나머지 400). 카탈로그 API enabled 플래그로 게이팅.
- **계약 drift**(memory `frontend-zod-backend-dto-contract-gap`). 카탈로그/layout Zod 스키마 백엔드 DTO 1:1, MSW 동일.
- **Dialog 컴포넌트**. 카탈로그 모달용 Dialog/Modal 존재 확인 필요(MoveIssueDialog가 radix Dialog 사용 — 재사용 가능 추정).

## 스펙

전체 스펙. [docs/specs/2026-06-30-fr-db-02-d6-d7-gadgets-ui.md](../specs/2026-06-30-fr-db-02-d6-d7-gadgets-ui.md)

핵심 요약.
- 가젯 MVP 6종(데이터 4 + 정적 2) + 카탈로그 모달(enabled=true 게이팅) + 가젯별 설정 폼(클라측 config 검증=백엔드 미러).
- 데이터: assigned_to_me/recently_created=`fetchIssues`(projectKey 지정형), filter_result/issue_count=`fetchFilter`→`searchAql`(MVP filterId 경로만), text/link=정적.
- DashboardTile/Grid·parseLayout/serializeLayout에 gadgetType/config 확장(legacy 타일 호환). 카탈로그 api+Zod+MSW 신규.
- same-BC 백엔드 보강 1건: `assigned_to_me` 카탈로그에 projectKey 노출.

## Brainstorming Check

✅ 통과 (직접 adversarial sanity check, gap 2건 보강).
- Gap 1: filter_result/issue_count → MVP filterId 경로만(aql 직접 후속).
- Gap 2: 이슈 목록 응답 정규화 `GadgetIssueRow{key,summary}`.
- 게이트1 재확인: 프로젝트 지정형 + same-BC 백엔드 보강 + filter_result filterId 한정.

## Plan

> 모든 frontend task TDD red→green→refactor. 검증=`pnpm --filter web test <file>` + `pnpm typecheck`. 병렬 dispatch 시 자기 파일만 stage(memory `parallel-dispatch-precommit-hook-race`).

### Task 1. 가젯 카탈로그 api + Zod 스키마 + MSW 핸들러

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/api/gadget-catalog.ts`, `apps/web/src/api/gadget-catalog.test.ts`, `apps/web/src/mocks/gadget-catalog-fixtures.ts`, `apps/web/src/mocks/dashboard-handlers.ts`]
- depends-on: []

**RED**: gadget-catalog.test.ts — `fetchGadgetCatalog()`가 `{data:{gadgets:[...]}}`를 파싱, enabled=true 6종 필터 헬퍼, ConfigFieldDto optional 필드(@JsonInclude NON_NULL) 누락 허용. **itemSchema 중첩(link_list ARRAY)** 재귀 파싱(노트 A). `validateGadgetConfig(entry, config)` 동적 검증 — configFields(required/min·max/길이/enum/url http(s)) + requireAtLeastOne 순회로 위반 키·사유 반환(EC3/EC5/EC7/EC8).
**GREEN**: `fetchGadgetCatalog` api(apiGet) + Zod 스키마 — 백엔드 `GadgetCatalogDtos.kt` 1:1(type/category/label/enabled/configFields[{key,type,required,minLength?,maxLength?,min?,max?,enumValues?,itemSchema?,minItems?,maxItems?}]/requireAtLeastOne). itemSchema는 z.lazy 재귀. **`validateGadgetConfig` 클라측 검증 헬퍼(카탈로그 configFields 단일 출처 — 정적 Zod 중복 회피)**. MSW 핸들러(백엔드 응답 1:1, enabled 정확).
**REFACTOR**: FieldType union 상수화. 계약 drift 가드 — 백엔드 `GadgetCatalogDtos.kt`·`GadgetType.kt` grep 주석 대조(memory `frontend-zod-backend-dto-contract-gap`).
**검증**: `pnpm --filter web test gadget-catalog && pnpm typecheck`

### Task 2. layout 타입 확장 — gadgetType/config 왕복 보존

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/lib/dashboard-layout.ts`, `apps/web/src/lib/dashboard-layout.test.ts`, `apps/web/src/api/dashboards.ts`]
- depends-on: []

**RED**: dashboard-layout.test.ts — `parseLayout`/`serializeLayout`가 `gadgetType`/`config` 왕복 무손실 보존 + gadgetType 없는 legacy 타일(`{i,x,y,w,h,title}`) 그대로 보존(Gap A 회귀).
**GREEN**: `DashboardTile` 인터페이스에 `gadgetType?: string`, `config?: Record<string,unknown>` 추가. parse/serialize가 두 필드 보존(미지 키 보존).
**REFACTOR**: 타입 주석. 기존 title 타일 호환 단언.
**검증**: `pnpm --filter web test dashboard-layout && pnpm typecheck`

### Task 3. (백엔드) assigned_to_me 카탈로그 projectKey 보강 (same-BC)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/notification/src/main/kotlin/com/bts/notification/dashboard/domain/GadgetType.kt`, `backend/modules/notification/src/test/kotlin/com/bts/notification/dashboard/domain/GadgetTypeTest.kt`, `backend/modules/notification/src/test/kotlin/com/bts/notification/dashboard/web/DashboardGadgetIntegrationTest.kt`]
- depends-on: []

**RED**: GadgetTypeTest — `ASSIGNED_TO_ME`의 configFields에 `projectKey`(STRING, optional, maxLength 100) 포함 단언. 카탈로그 통합 테스트(G3)에 projectKey 노출 확인.
**GREEN**: `ASSIGNED_TO_ME` ConfigFieldDescriptor에 projectKey(recently_created 동일 패턴) 추가. catalog() 단일 출처 불변식 유지.
**REFACTOR**: KDoc. enabled/카테고리 불변.
**검증**: `./gradlew :backend:modules:notification:test --tests "*GadgetType*" --tests "*DashboardGadget*" ktlintCheck detekt` (sub-agent lint 보고 불신 — controller 직접 검증, memory `subagent-ktlint-false-green-controller-verify`)

### Task 4. 가젯 데이터 fetch 훅 + GadgetIssueRow 정규화

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/dashboard/gadgets/useGadgetData.ts`, `apps/web/src/components/dashboard/gadgets/useGadgetData.test.ts`, `apps/web/src/components/dashboard/gadgets/gadget-types.ts`]
- depends-on: []

**RED**: useGadgetData.test.ts — `GadgetIssueRow{key,summary}` 정규화(IssueResponse→Row, AqlSearchHit→Row, Gap 2). 가젯별 fetch 분기(assigned_to_me=fetchIssues+assignee, recently_created=fetchIssues, filter_result/issue_count=fetchFilter→searchAql). maxItems 클램프(1~50). MSW stateful(memory `msw-mutation-stateful-refetch`).
**GREEN**: TanStack Query 훅 — 가젯별 queryKey(gadgetType+config 해시), 정규화 매핑. `useAuthUser()?.userId`는 **항상 호출하되 assigned_to_me일 때만 사용**(조건부 훅 호출 금지·React 규칙, 노트 B). 에러/로딩/빈 상태 노출.
**REFACTOR**: 정규화 매핑 순수 함수 추출. query 키/staleTime 상수.
**검증**: `pnpm --filter web test useGadgetData && pnpm typecheck`

### Task 5. 가젯 컴포넌트 6종 + 클라측 config 검증 스키마

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/dashboard/gadgets/IssueListGadget.tsx`, `apps/web/src/components/dashboard/gadgets/IssueCountGadget.tsx`, `apps/web/src/components/dashboard/gadgets/TextWidgetGadget.tsx`, `apps/web/src/components/dashboard/gadgets/LinkListGadget.tsx`, `apps/web/src/components/dashboard/gadgets/GadgetRenderer.tsx`, `apps/web/src/components/dashboard/gadgets/gadgets.test.tsx`]
- depends-on: [2, 4]

**RED**: gadgets.test.tsx — IssueListGadget(assigned_to_me/recently_created/filter_result 공용 목록 + 로딩/에러/빈), IssueCountGadget(totalElements 숫자), TextWidgetGadget(markdown 안전 렌더 — 노트 E: 기존 렌더러 확인, 없으면 plain text+줄바꿈+XSS 차단), LinkListGadget(http/https only, rel=noopener). GadgetRenderer가 gadgetType→컴포넌트 분기 + legacy/미지원 안전 표시(EC6). (컴포넌트는 저장된 config **렌더 전용** — 입력 검증은 T6 설정 폼이 T1 `validateGadgetConfig` 사용.)
**GREEN**: 6종 컴포넌트(IssueList 공용 1 + IssueCount + Text + Link) + GadgetRenderer. 이슈 키 클릭→SPA 이동.
**REFACTOR**: 공통 타일 본문 레이아웃(DESIGN.md 토큰). XSS sanitize 확인.
**검증**: `pnpm --filter web test gadgets && pnpm typecheck`

### Task 6. 카탈로그 모달 + 가젯별 설정 폼

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/dashboard/GadgetCatalogModal.tsx`, `apps/web/src/components/dashboard/GadgetConfigForm.tsx`, `apps/web/src/components/dashboard/GadgetCatalogModal.test.tsx`]
- depends-on: [1, 2]

**RED**: 모달 테스트 — radix Dialog 열기, 12종 category 그룹 표시, enabled=false "준비 중" 비활성(S6), 가젯 선택→설정 폼, 클라측 검증(T1 `validateGadgetConfig` 사용 — projectKey 필수·filterId 필수·markdown/url/links), 추가 콜백. key prop 재마운트(설정 초기화, memory `react-usestate-stale-key-prop`).
**GREEN**: GadgetCatalogModal(PostActionFormDialog 패턴) + GadgetConfigForm(configFields 기반 타입별 입력 + requireAtLeastOne, 검증=T1 `validateGadgetConfig`). projectKey 입력(프로젝트 지정형 가젯 필수).
**REFACTOR**: 폼 필드 렌더 타입별 분기 추출.
**검증**: `pnpm --filter web test GadgetCatalogModal && pnpm typecheck`

### Task 7. DashboardTile/Grid 가젯 렌더 통합 + 상세 페이지 연결

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/dashboard/DashboardTile.tsx`, `apps/web/src/components/dashboard/DashboardGrid.tsx`, `apps/web/src/routes/dashboards.$dashboardId.tsx`, `apps/web/src/components/dashboard/DashboardTile.test.tsx`]
- depends-on: [5, 6]

**RED**: DashboardTile.test — gadgetType 있으면 GadgetRenderer 렌더(placeholder 대체), 없으면 legacy placeholder. 상세 페이지 "가젯 추가"→모달, 추가 시 handleAddTile 확장(gadgetType/config 포함 타일). 저장 시 serializeLayout가 가젯 보존(OCC 409 로컬 보존 회귀, memory `mutation-setquerydata-partial-response-flicker`).
**GREEN**: DashboardTile 본문에 GadgetRenderer 통합(canEdit 게이팅 유지). handleAddTile이 모달 결과로 가젯 타일 생성. 기본 타일 크기 가젯별.
**REFACTOR**: 타일 헤더(가젯 label) 표시. 편집/읽기 모드 분기 정리.
**검증**: `pnpm --filter web test DashboardTile && pnpm typecheck && pnpm --filter web test dashboard`(기존 무회귀)

### Task 8. E2E — 가젯 추가/렌더/게이팅/읽기전용 + 무회귀

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/dashboard-gadgets.spec.ts`, `apps/web/src/mocks/dashboard-handlers.ts`]
- depends-on: [7]

**E2E 시나리오**: S1(카탈로그→설정→추가→저장), S2(이슈 가젯 렌더, MSW 영속 SPA 이동 — reload 금지 memory `msw-derived-behavior-shared-store-e2e`), S4(정적 text/link 가젯), S6(enabled=false 비활성 게이팅), S7(비편집자 읽기전용). 기존 dashboard.spec.ts 무회귀 동반 실행(memory `ui-pr-defer-e2e-regression-latent`). 텍스트 중복 시 컨테이너 한정(memory `playwright-getbyrole-exact-strict-mode`). RGL 드래그 제약은 기존대로 SKIP.
**검증**: `pnpm --filter web test:e2e dashboard-gadgets && pnpm --filter web test:e2e dashboard`

## Plan 메타

- task 수: 8 (frontend 6 + backend 보강 1 + qa E2E 1)
- 예상 wave: 4 — Wave1[T1,T2,T3,T4 병렬] → Wave2[T5(dep2,4), T6(dep1,2)] → Wave3[T7(dep5,6)] → Wave4[T8(dep7)]
- 파일 겹침 직렬화: dashboard-handlers.ts(T1 카탈로그 핸들러 vs T8 E2E) → T8이 T7 뒤라 자연 직렬. DashboardTile 인터페이스(api/dashboards.ts, T2) ≠ DashboardTile.tsx 컴포넌트(T7) — 다른 파일.
- TDD 강제: yes (frontend·backend 전 task). E2E는 시나리오 기반.
- 추가 검증: typecheck, lint, vitest, playwright(qa), 백엔드 ktlint/detekt(T3).
- BC 격리 예외: T3는 same-BC(notification) 백엔드 보강 — frontend PR 내 view/도메인 layer patch(memory 옵션C 패턴). 게이트1 재확인.

## 리뷰 결과

### 직접 다관점 리뷰 (eng 집중, 2026-06-30)

type=ui지만 핵심 리스크가 계약 정합·데이터 fetch·wave라 design-review 대화형 대신 직접 eng+design 리뷰(memory `bts-review-plan-autoplan-overkill`).

**Eng 관점**.
- ✅ 계약 drift 가드(T1 Zod↔백엔드 grep 대조), 데이터소스 정합(projectKey 필수·T3 보강), TDD red→green 전 task, MSW stateful/React key/OCC invalidate 가드 명시.
- 🔧 **결함 C 수정**: 클라측 config 검증을 T5(렌더 전용)에서 분리 → T1 `validateGadgetConfig`(카탈로그 configFields 동적·단일 출처)로 이동, T6 폼이 사용. 정적 Zod 중복 회피 + 의존성 정리(T5 렌더만, T6 dep[1,2] 유지).
- ⚠️ 노트 A: link_list itemSchema 중첩(ARRAY) z.lazy 재귀 — T1 반영.
- ⚠️ 노트 B: `useAuthUser`는 항상 호출·assigned_to_me일 때만 사용(조건부 훅 금지) — T4 반영.
- ⚠️ 노트 E(리스크): text_widget 마크다운 렌더러 유무 미확정 → T5 착수 전 확인, 없으면 plain text+XSS 차단 — T5 반영.

**Design 관점**.
- ✅ 가젯 타일=DashboardTile/card+DESIGN.md 토큰, 카탈로그 모달=PostActionFormDialog 패턴 계승 → 신규 비주얼 결정 적음(design-shotgun 불요 타당).

**BLOCKER: 없음.** (결함 C는 plan 내 반영 완료.)

### 게이트1 재확인 항목 (Maxi)
1. **프로젝트 지정형 6종** — 이슈 가젯이 프로젝트 무관(전역)이 아니라 프로젝트 지정. "내게 할당된 이슈(전역)"는 후속 FR.
2. **same-BC 백엔드 보강(T3)** — 프론트 PR에 notification BC 가젯 카탈로그 patch(assigned_to_me projectKey). BC 격리 옵션C 예외.
3. **filter_result/issue_count MVP filterId 한정** — aql 직접 입력은 후속(저장필터 중복·projectKey 출처).
