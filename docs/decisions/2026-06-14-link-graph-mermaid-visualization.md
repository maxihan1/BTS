# ADR — 링크 그래프 시각화는 mermaid flowchart로 (FR-LK-02 D6)

> 날짜. 2026-06-14
> 상태. 채택(Accepted)
> 관련 FR. FR-LK-02 (링크 그래프 시각화)
> BC. issue-tracking (프론트엔드)
> 관련 ADR. [2026-06-13-issue-link-vs-parent-child-separation](2026-06-13-issue-link-vs-parent-child-separation.md)

## 맥락

FR-LK-02 백엔드(D1~D5, PR #138)가 `GET /api/v1/issues/{key}/graph?depth={1..3}`(기본 2)를 제공한다. 응답은 깊이 제한 BFS로 만든 노드/엣지 그래프 — `{center, depth, nodes:[{key,summary,statusKey,depth}], edges:[{from,to,type}], truncated}`. 노드 상한 NODE_CAP=100, 초과 시 `truncated=true`. edge.type은 대문자 5종(BLOCKS/RELATES/DUPLICATES/CLONES/PARENT).

D6 product 명세(`docs/plan/product/issue-tracking.md:428`)는 "프론트 UI — **SVG 또는 force-directed lib** (designer → frontend-engineer)"로 시각화 기술을 의도적으로 열어 두었다.

코드 상태(구현 시작 시점).
- `apps/web/package.json`의 그래프 라이브러리는 **mermaid ^11.4.0 단 하나**. `WorkflowDiagram.tsx`가 stateDiagram-v2를 동적 import + SVG 주입 + 에러 fallback + aria-label 패턴으로 사용.
- force-directed 계열(react-force-graph / d3-force / cytoscape / vis-network)은 **미설치**.
- graph 엔드포인트를 소비하는 프론트 클라이언트는 아직 없음.

## 결정

**링크 그래프 시각화는 mermaid flowchart로 구현한다.** (Maxi 확정, 2026-06-14)

- `nodes`/`edges` 데이터로 mermaid `flowchart` 코드 문자열을 생성 → `WorkflowDiagram`과 동일하게 동적 import + `mermaid.render()` → SVG 주입.
- center 노드 강조(classDef), edge.type을 엣지 라벨/스타일로 표현.
- depth(1~3) 선택 컨트롤로 그래프 범위 조절, `truncated=true`면 "그래프 일부 생략" 표시.

근거.
- **새 의존성 0**. mermaid는 이미 설치돼 있어 절대 규칙 #17(외부 의존성 Maxi 승인) 부담 없음. force-directed는 새 의존성 + 번들 증가.
- **선례 재사용**. `WorkflowDiagram.tsx`의 동적 import·SVG 주입·렌더 실패 fallback·접근성(aria-label) 패턴을 그대로 따른다. 미술적 일관성도 확보.
- **규모 적합**. depth ≤3, 노드 ≤100(NODE_CAP)의 이웃 그래프는 mermaid dagre 자동 레이아웃의 정적 방향그래프로 충분히 읽힌다. 1,000명 규모 사내 도구에 force-directed 인터랙티브는 과임.
- **테스트 가능성**. mermaid SVG 셀렉터 선례(learnings: `.statediagram-state`/`.node`, jsdom getBBox mock)가 이미 축적됨. canvas 기반 force lib는 jsdom 곤란 + 선례 없음.

## 폐기한 대안

- **B. force-directed lib(react-force-graph 등).** 드래그·줌·노드클릭 확장 등 인터랙티브가 강하나 (1) 새 의존성 + 번들 크기 증가, (2) canvas/WebGL 계열은 jsdom 단위테스트 곤란(선례 없음), (3) ≤100 노드 depth 제한 이웃엔 과임. 향후 그래프가 훨씬 커지거나 인터랙션 요구가 생기면 재검토.
- **C. 수제 SVG 레이아웃.** 새 의존성은 없으나 depth별 방사형/계층 레이아웃 알고리즘을 직접 구현해야 해 구현량 최대. mermaid가 같은 결과를 자동 레이아웃으로 제공하므로 불필요.

## 영향

- 프론트. 신규 `LinkGraph` 컴포넌트(mermaid flowchart 코드 생성 helper + 렌더) + graph API 클라이언트(`GET /graph` 소비, Zod 스키마 = 백엔드 응답 1:1 미러). depth 선택 UI + truncated 표시.
- 계약. edge.type 대문자 5종을 그대로 받아 라벨/스타일 매핑. node depth로 center(=0) 강조.
- 테스트. mermaid 렌더는 단위테스트에서 mock(jsdom getBBox 미구현 — learnings 2026-05-23), 실제 렌더는 D7 E2E로 위임. 코드 생성 helper는 named export로 순수 단위테스트.
- 범위. 이번 PR은 D6(프론트 UI) + D7(E2E). 백엔드 무변경.
