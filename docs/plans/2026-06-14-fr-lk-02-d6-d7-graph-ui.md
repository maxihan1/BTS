# FR-LK-02 D6/D7 — 이슈 링크 그래프 프론트엔드 시각화 + E2E

> slug: fr-lk-02-d6-d7-graph-ui
> type: ui
> agent: frontend-engineer
> 생성: 2026-06-14

## Brief

사용자 원문: `FR-LK-02 D6/D7`

이슈 링크 그래프 시각화 프론트엔드(D6) + E2E(D7).
백엔드 D1~D5는 #138로 완료 — `GET /api/v1/issues/{key}/graph?depth={1..3}` (기본 2).
응답: `DataResponse<{center, depth, nodes:[{key,summary,statusKey,depth}], edges:[{from,to,type}], truncated}>`.
노드 상한 NODE_CAP=100, 초과 시 truncated=true. edge.type 대문자(BLOCKS/RELATES/DUPLICATES/CLONES/PARENT).

classify 결과: type=ui(E2E 키워드로 qa 오판정 → ui 교정, FR-LK-01 선례), agent=frontend-engineer, primary_bc=issue-tracking.

## 도메인 정리

- **BC**: issue-tracking (프론트엔드)
- **영향 엔티티**: 없음 (읽기 전용 시각화 — 백엔드 graph 엔드포인트 #138 소비만)
- **새 용어**: 없음. glossary "링크"(이슈 간 의존/연관) 그대로 사용. 그래프=center 이슈 기준 depth 제한 BFS 이웃.
- **백엔드 계약 (확정, #138)**:
  - `GET /api/v1/issues/{key}/graph?depth={1..3}` (기본 2)
  - 응답 `DataResponse<{ center, depth, nodes:[{key,summary,statusKey,depth}], edges:[{from,to,type}], truncated }>`
  - edge.type 대문자 5종: BLOCKS / RELATES / DUPLICATES / CLONES / PARENT (parent 엣지는 from=부모/to=자식)
  - node depth = BFS 최단거리(center=0). NODE_CAP=100 초과 시 truncated=true
  - 비정수/범위밖 depth → 400 INVALID_DEPTH, 이슈 없음 → 404 ISSUE_NOT_FOUND
- **시각화 기술 결정**: **mermaid flowchart** (Maxi 확정 2026-06-14). 새 의존성 0(mermaid ^11.4.0 기설치), WorkflowDiagram 패턴(동적 import→SVG 주입→fallback→aria-label) 재사용. force-directed lib는 새 의존성+jsdom 테스트 곤란으로 폐기.
- **기존 결정 충돌**: 없음. [[2026-06-13-issue-link-vs-parent-child-separation]] 위에서 graph는 issue_links 4종 + parent_id를 모두 엣지로 통합 표시(읽기 전용이라 충돌 없음).
- **관련 ADR**: [docs/decisions/2026-06-14-link-graph-mermaid-visualization.md](../decisions/2026-06-14-link-graph-mermaid-visualization.md) (생성됨)

## 스펙

전체 스펙. [docs/specs/2026-06-14-fr-lk-02-d6-d7-graph-ui.md](../specs/2026-06-14-fr-lk-02-d6-d7-graph-ui.md)

핵심 시나리오 요약.
- 이슈 상세 "링크 그래프" 섹션을 펼치면(기본 접힘, lazy 조회) `GET /graph?depth=2` 호출 → mermaid flowchart로 center 강조 + edge.type 라벨 렌더
- depth 컨트롤(1/2/3)로 범위 전환, truncated=true면 "일부 생략" 안내, 빈 그래프는 메시지
- 노드 클릭(또는 Enter)으로 해당 이슈 상세로 이동(center는 no-op), mermaid securityLevel 변경 없이 DOM 바인딩
- 단위테스트는 mermaid mock, 실제 렌더는 D7 E2E

## Brainstorming Check

✅ 통과 (집중 사니티 체크 1회). 발견 gap 1건 — "그래프 노드 클릭 내비게이션 포함 여부" → Maxi 결정 "포함"(2026-06-14). FR-8 + S7 + EC-8 + 완료기준에 반영. office-hours/design-shotgun은 contract-고정 FR 연속 작업이라 스킵(bts-spec-office-hours-mismatch 교훈).

## Plan

> WorkflowDiagram.tsx(mermaid 동적 import→SVG 주입→fallback) + issue-links.ts(apiFetch+Zod+lazy query) 선례 재사용.
> 단위테스트는 `vi.mock('mermaid')`(jsdom getBBox 미구현 — learnings 2026-05-23). 실제 렌더는 D7 E2E.

### Task 1. i18n linkGraphStrings 추가

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/i18n/ko.ts`, `apps/web/src/i18n/ko.test.ts`]
- depends-on: []

**RED**: `ko.test.ts`에 `linkGraphStrings`가 필수 키(sectionTitle, expandLabel, collapseLabel, depthLabel, depthOptionN, emptyState, truncatedNotice, loadingState, renderError, loadError, notFound, edgeBlocks/edgeRelates/edgeDuplicates/edgeClones/edgeParent, nodeAriaLabel)를 갖는지 + 기존 콜론종결 검증에 포함되는지 단언 → `linkGraphStrings` 없어 실패.

**GREEN**: `ko.ts`에 `linkGraphStrings` 객체 추가(콜론 종결 금지). edge type 라벨 5종은 대문자 enum→한국어 매핑.

**REFACTOR**: 그룹 주석 + 기존 `issueLinkStrings` 인접 배치.

**검증**: `pnpm test ko.test`

### Task 2. graph API 클라이언트 + Zod + lazy query 훅

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/api/issue-graph.ts`, `apps/web/src/api/issue-graph.test.ts`]
- depends-on: []

**RED**: `fetchIssueGraph(key, depth)` — `GET /api/v1/issues/{key}/graph?depth={n}` 호출+`{data}` 언랩, Zod 스키마 parse(center/depth/nodes[{key,summary,statusKey,depth}]/edges[{from,to,type}]/truncated), 400·404 시 ApiError throw, `useIssueGraph(key, depth, enabled)` 훅이 `enabled=false`면 미조회. → 모듈 없어 실패.

**GREEN**: `issue-links.ts` 패턴 그대로 — 로컬 `dataResponseSchema`, `apiFetch`, Zod 1:1 미러, `useQuery({ enabled })` lazy 게이트, `issueGraphKey(key, depth)`.

**REFACTOR**: KDoc + edge type 상수(대문자 5종) export(helper·component 공유).

**검증**: `pnpm test issue-graph`

### Task 3. mermaid 코드 생성 + ID sanitize/역매핑 helper (순수 함수)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/issue/link-graph-mermaid.ts`, `apps/web/src/components/issue/link-graph-mermaid.test.ts`]
- depends-on: [2]   # IssueGraphResponse 타입 import (type-only)

**RED**: `generateGraphMermaidCode(graph, edgeLabels)` →
- `flowchart LR` 코드 + center 노드 classDef 강조
- 엣지 `from -->|label| to` 방향 보존(PARENT는 부모→자식), edgeLabels 주입(i18n은 component가 전달 — helper 순수 유지)
- 노드 ID sanitize(이슈 키 `ATLAS-1`의 하이픈/숫자 → mermaid 안전 ID, learnings 2026-05-23 보수적 식별자), 라벨엔 원래 키
- 반환 `{ code, idToKey }`(클릭 내비용 역매핑)
- center만 있고 엣지 0 → `null`(빈 그래프 신호)
→ 모듈 없어 실패.

**GREEN**: 순수 함수 구현. 노드 dedup, sanitize 맵 빌드.

**REFACTOR**: sanitize 정규식 상수화 + KDoc.

**검증**: `pnpm test link-graph-mermaid`

### Task 4. LinkGraph 컴포넌트 (렌더 + 5상태 + depth + lazy 펼침 + 노드 클릭 + a11y)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/issue/LinkGraph.tsx`, `apps/web/src/components/issue/LinkGraph.test.tsx`]
- depends-on: [1, 2, 3]

**RED** (`vi.mock('mermaid')`로 node 포함 가짜 SVG 반환):
- 기본 접힘 — 펼치기 전 `useIssueGraph` 미조회(enabled=false)
- 펼침 → depth=2 조회 → mermaid SVG 주입(aria-label 부여)
- 빈 그래프(helper null) → "연결된 이슈가 없습니다", mermaid 미렌더
- 로딩 상태 표시
- 로드 에러 — 404→notFound, 그 외→loadError (role=alert)
- 렌더 실패(mermaid throw) → renderError (role=alert)
- truncated=true → truncatedNotice 표시
- depth 컨트롤 2→3 변경 → depth=3 재조회
- 노드 클릭 → `navigate({to:'/issues/$key'})`(useNavigate mock), center 노드 클릭 no-op
- 키보드 — 노드에 tabindex=0/role=link/aria-label, Enter keydown → navigate
→ 컴포넌트 없어 실패.

**GREEN**: WorkflowDiagram 패턴(동적 `import('mermaid')`→`render`→innerHTML 주입). 렌더 후 `g.node` 요소에 idToKey로 클릭/keydown 핸들러+tabindex/role 바인딩(securityLevel 변경 없이). depth/expand `useState`, 재렌더 시 핸들러 재바인딩(누수 없이 cleanup).

**REFACTOR**: 빈/에러/로딩 분기 서브컴포넌트화 + KDoc. 코드 생성 helper named export 회귀 가드.

**검증**: `pnpm test LinkGraph`

### Task 5. issues.$key.tsx 배선 + 기존 상세 회귀

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/routes/issues.$key.tsx`, `apps/web/src/routes/issues.$key.test.tsx`]
- depends-on: [4]

**RED**: 이슈 상세에 "링크 그래프" 섹션(LinkGraph, issueKey 전달, 기본 접힘)이 IssueLinksPanel 인근 전체폭에 렌더되는지 단언 → 미배선 실패.

**GREEN**: `import { LinkGraph }` + IssueLinksPanel 아래 전체폭 섹션 배치.

**REFACTOR**: 섹션 주석.

**검증**: `pnpm test "issues.\$key"` — 기존 상세 단위테스트 회귀 0.

### Task 6. E2E + MSW graph 핸들러/픽스처

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/issue-link-graph.spec.ts`, `apps/web/src/mocks/issue-graph-handlers.ts`, `apps/web/src/mocks/handlers.ts`]
- depends-on: [5]

**RED**: E2E 시나리오 작성(핸들러/배선 없어 실패) — 펼치기→그래프 SVG 렌더(`.node`/svg 셀렉터, learnings: flowchart 노드 셀렉터 실측 후 확정)→depth 전환→빈 그래프→truncated→노드 클릭 시 이슈 이동.

**GREEN**: `issue-graph-handlers.ts`(GET /graph MSW 핸들러+depth별 픽스처+truncated/빈 시나리오) 작성 + `handlers.ts` 등록.

**검증**: `pnpm exec playwright test issue-link-graph` + 기존 issue E2E(`issue-links`, `issue-ui-regression`) 회귀 0(텍스트 중복 셀렉터 컨테이너 한정 — learnings 2026-05-31).

## Plan 메타

- task 수: 6
- 예상 wave: 5 (W1: T1‖T2 병렬, W2: T3, W3: T4, W4: T5, W5: T6)
- TDD 강제: yes (test 커밋이 feat 커밋보다 먼저)
- 병렬 dispatch: T1·T2 file 겹침 0 + depends-on [] → 단일 wave 병렬. 나머지는 의존 직렬
- 추가 검증: typecheck(tsconfig.app), vitest, playwright(qa), pnpm verify. mermaid 단위는 mock, 실렌더는 E2E

## 리뷰 결과

### design+eng 집중 리뷰 (2026-06-14)

> type=ui지만 시각화 방식(mermaid)·노드 클릭은 도메인/스펙에서 Maxi 확정 → 재론 금지(bts-review-plan-autoplan-overkill 교훈). 검증된 선례 재사용 plan에 대한 집중 독립 리뷰.

**✅ 통과**.
- TDD 분해 타당 — RED→GREEN→REFACTOR 라벨 + 메타(agent/files/depends-on) 전 task 기재. T1·T2 file 겹침 0 + depends-on [] → 단일 wave 병렬 정당.
- 선례 재사용 — WorkflowDiagram(동적 import→SVG 주입→fallback→aria), issue-links.ts(apiFetch+Zod 1:1+lazy query). 새 의존성 0(절대 규칙 #17 무부담).
- 단위테스트 mermaid mock + 실렌더 E2E 위임 — learnings 2026-05-23(jsdom getBBox) 정합.
- BC 격리 — issue-tracking 프론트 단독, 백엔드 무변경.

**⚠️ CONCERN (impl/E2E에서 반드시 검증, BLOCKER 아님)**.
- **C1. mermaid 노드 id 변형**. mermaid는 렌더 SVG에서 노드 id를 자체 prefix로 변형(`flowchart-<id>-<n>` 류). Task 4 GREEN의 클릭 바인딩이 helper의 sanitize id와 렌더 id를 직접 매칭하면 안 맞을 수 있음 → **렌더된 SVG의 실제 노드 식별 방식(id 형식 또는 노드 텍스트=이슈 키)을 실측 후 바인딩**. learnings 2026-05-22(`.node` vs `.statediagram-state` 셀렉터 실측) 동일 주의. Task 4·T6에 실측 단계 명시 권장.
- **C2. SVG 노드 키보드 a11y**. `<g class="node">`에 tabindex=0/role=link 부여는 유효하나 axe가 SVG 인터랙티브 요소를 플래그할 수 있음 → D7 E2E에서 axe 통과 확인. 미통과 시 그래프 하단에 키보드 접근 가능한 노드 링크 목록 보조 제공(대안).

**ℹ️ NOTE (taste, 선택)**.
- N1. 노드 라벨은 이슈 키만 표시(summary 미표시). 단순성 우선으로 적절. 필요 시 summary를 mermaid 노드 tooltip으로 보강 가능(후속).

**BLOCKER: 없음.**
