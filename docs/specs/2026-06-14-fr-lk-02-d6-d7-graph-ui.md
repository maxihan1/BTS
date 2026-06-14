# FR-LK-02 D6/D7 — 이슈 링크 그래프 시각화 스펙

> slug: fr-lk-02-d6-d7-graph-ui
> type: ui (D6 프론트 + D7 E2E)
> BC: issue-tracking (프론트엔드)
> 백엔드 계약: PR #138 (확정, 무변경)
> 시각화 결정: mermaid flowchart — ADR 2026-06-14-link-graph-mermaid-visualization

## 배경

FR-LK-02 백엔드(#138)가 `GET /api/v1/issues/{key}/graph`를 제공한다. 이슈 상세 페이지(`issues.$key.tsx`)에 center 이슈를 기준으로 한 링크 이웃 그래프를 mermaid flowchart로 시각화한다. 기존 `IssueLinksPanel`(직접 링크 목록 관리)과 별개로, **읽기 전용 그래프 개요**를 제공한다.

## 사용자 시나리오 (Given-When-Then)

### S1. 그래프 펼쳐 보기 (lazy load)
- **Given** 이슈 상세 페이지(ATLAS-1)를 연 사용자
- **When** "링크 그래프" 섹션의 펼치기 토글을 클릭
- **Then** 그 시점에 `GET /api/v1/issues/ATLAS-1/graph?depth=2`를 호출하고, 노드/엣지를 mermaid flowchart SVG로 렌더한다. center(ATLAS-1)는 강조 표시된다.
- (그래프는 접힌 상태가 기본 — 매 이슈 조회마다 그래프를 미리 가져오지 않는다, NFR-1.)

### S2. depth 전환
- **Given** depth=2로 그래프가 렌더된 상태
- **When** 사용자가 depth 컨트롤을 3으로 변경
- **Then** `?depth=3`으로 재조회하고 그래프를 다시 렌더한다. depth 컨트롤은 1/2/3만 허용한다.

### S3. 연결 없는 이슈 (빈 그래프)
- **Given** 링크·부모·자식이 하나도 없는 이슈
- **When** 그래프 섹션을 펼침
- **Then** mermaid를 렌더하지 않고 "연결된 이슈가 없습니다" 빈 상태 메시지를 표시한다. (center 노드 1개만 있는 그래프는 시각화 가치가 없음.)

### S4. 그래프 일부 생략 (truncated)
- **Given** 노드 수가 NODE_CAP(100)을 초과해 백엔드가 `truncated: true`를 반환
- **When** 그래프 렌더
- **Then** 그래프 위/아래에 "그래프가 너무 커서 일부만 표시합니다 (최대 100개 노드)" 안내를 표시한다. (반환된 노드/엣지는 그대로 렌더.)

### S5. depth 범위 밖 / 잘못된 요청
- **Given** depth 컨트롤은 1/2/3만 노출하므로 정상 경로에선 400이 발생하지 않는다.
- **When** (방어) 백엔드가 400 INVALID_DEPTH 또는 404 ISSUE_NOT_FOUND를 반환
- **Then** 그래프 영역에 에러 메시지(role="alert")를 표시한다. 404는 "이슈를 찾을 수 없습니다", 그 외는 일반 로드 실패 메시지.

### S6. mermaid 렌더 실패
- **Given** 정상 응답을 받았으나 mermaid.render()가 예외를 던짐
- **When** 렌더 시도
- **Then** WorkflowDiagram과 동일하게 "그래프 렌더 실패" fallback(role="alert")을 표시한다.

### S7. 노드 클릭으로 이슈 이동
- **Given** 그래프가 렌더된 상태(center=ATLAS-1, 노드 ATLAS-2/ATLAS-9 표시)
- **When** 사용자가 ATLAS-9 노드를 클릭(또는 키보드 포커스 후 Enter)
- **Then** TanStack Router로 `/issues/ATLAS-9` 상세 페이지로 이동한다. center 노드(ATLAS-1, 현재 이슈) 클릭은 no-op.

## 기능 요구사항 (FR)

- **FR-1**. graph API 클라이언트 — `GET /api/v1/issues/{key}/graph?depth={n}` 호출 + `{ data: {...} }` 언랩. Zod 스키마는 백엔드 응답과 1:1 미러(center, depth, nodes[{key,summary,statusKey,depth}], edges[{from,to,type}], truncated). TanStack Query 훅(`enabled` 게이트로 lazy 조회).
- **FR-2**. mermaid 코드 생성 helper(named export, 순수 함수) — nodes/edges → mermaid `flowchart` 코드 문자열. center 노드 classDef 강조, edge.type별 라벨, 방향(from→to) 보존. 노드 ID는 mermaid 안전 식별자로 sanitize(이슈 키의 하이픈 등 처리).
- **FR-3**. `LinkGraph` 컴포넌트 — 동적 import 후 `mermaid.render()` → SVG 주입(WorkflowDiagram 패턴). 빈/로딩/에러/렌더실패/truncated 상태 처리. aria-label 부여.
- **FR-4**. depth 선택 컨트롤(1/2/3, 기본 2). 변경 시 재조회·재렌더.
- **FR-5**. 접기/펼치기 토글 — 기본 접힘. 펼칠 때만 쿼리 enabled.
- **FR-6**. `issues.$key.tsx`에 "링크 그래프" 섹션 배선(IssueLinksPanel 인근, 전체폭).
- **FR-7**. i18n — 모든 사용자 표시 문자열은 `i18n/ko`에 추가(콜론 종결 금지, ko.test 자동검증 대상).
- **FR-8**. 노드 클릭 내비게이션 — mermaid 렌더 후 SVG 노드(`g.node`)에 DOM 클릭 핸들러 바인딩(mermaid `securityLevel` 변경 없이). 노드 ID→이슈 키 매핑으로 TanStack Router `navigate({ to: '/issues/$key', params })` 호출. center 노드는 no-op. 키보드 접근성 — 노드 요소에 `tabindex=0` + `role="link"` + `aria-label`(이슈 키) + Enter keydown 핸들러 부여(WCAG AA).

## 비기능 요구사항 (NFR)

- **NFR-1**. lazy 조회 — 그래프는 펼칠 때만 fetch+render. 매 이슈 상세 조회 시 graph 요청·mermaid import 발생 금지.
- **NFR-2**. mermaid 동적 import(`await import('mermaid')`)로 초기 번들에 포함하지 않음(WorkflowDiagram 선례, NFR 메인 번들 gzip 200KB).
- **NFR-3**. 접근성 — 그래프 컨테이너 aria-label, depth 컨트롤·토글 label. WCAG AA.
- **NFR-4**. edge.type 대문자 5종(BLOCKS/RELATES/DUPLICATES/CLONES/PARENT)을 한국어 라벨로 매핑(i18n). 미지의 type은 원문 fallback(fail-safe, drift 시 깨지지 않음).

## API 인터페이스 (REST) — 확정, 무변경

```
GET /api/v1/issues/{key}/graph?depth={1..3}   (기본 2)
200 → { data: {
  center: string,                  // 이슈 키
  depth: number,                   // 적용된 depth
  nodes: [{ key, summary, statusKey, depth }],
  edges: [{ from, to, type }],     // type 대문자 5종, parent는 from=부모/to=자식
  truncated: boolean               // NODE_CAP=100 초과 시 true
} }
400 → INVALID_DEPTH  (비정수/범위밖)
404 → ISSUE_NOT_FOUND
```

## 데이터 모델 변경

없음 (읽기 전용 시각화).

## 엣지 케이스

- **EC-1**. center만 있고 노드 1개·엣지 0 → 빈 상태 메시지(S3). mermaid 렌더 안 함.
- **EC-2**. truncated=true → 안내 + 반환분 렌더(S4).
- **EC-3**. mermaid 식별자 충돌 — 이슈 키(`ATLAS-1`)의 하이픈/숫자가 mermaid 노드 ID로 직접 쓰일 때 파싱 위험 → 안전 ID로 매핑(sanitize), 라벨엔 원래 키 표시. (learnings 2026-05-23 식별자 보수적 선택.)
- **EC-4**. 같은 두 노드 사이 복수 엣지(예: blocks + relates) → mermaid가 각각 라인으로 렌더(백엔드가 이미 dedup, 타입 다르면 별개 엣지).
- **EC-5**. depth 전환 중 로딩 → 이전 그래프 유지하며 로딩 표시(플리커 최소화) 또는 로딩 표시. (invalidate-only 패턴, setQueryData 부분응답 금지 — learnings.)
- **EC-6**. 404/400 → 에러 alert(S5). PARENT 엣지 방향(부모→자식) 라벨 정확성.
- **EC-7**. mermaid 렌더 실패 → fallback alert(S6).
- **EC-8**. 노드 클릭 바인딩 — mermaid SVG 노드 id는 sanitize된 안전 ID(EC-3)이므로, 렌더 후 sanitize ID→원래 이슈 키 역매핑으로 navigate 대상 키를 복원. center 노드 클릭은 no-op(현재 이슈). depth 재조회/재렌더 시 핸들러 재바인딩(이전 핸들러 누수 없이).

## 제약 조건

- 새 의존성 0 (mermaid 기설치). force-directed lib 도입 금지(ADR).
- 백엔드 무변경 (계약 확정).
- BC 격리 — issue-tracking 프론트 단독. 다른 BC 변경 없음.
- 단위테스트는 mermaid mock(jsdom getBBox 미구현 — learnings 2026-05-23), 실제 렌더 검증은 D7 E2E.
- 인증 전 호출 아님 → 일반 `apiFetch` 사용(401 자동 refresh 정상 경로).

## 측정 가능한 완료 기준

- [ ] graph API 클라이언트 + Zod 스키마(백엔드 1:1 미러) — 단위테스트 통과
- [ ] mermaid 코드 생성 helper — center 강조/edge 라벨/방향/sanitize 단위테스트 통과
- [ ] LinkGraph 컴포넌트 — 빈/로딩/에러/렌더실패/truncated 5상태 단위테스트(mermaid mock) 통과
- [ ] depth 컨트롤 + 접기/펼치기(lazy) 단위테스트 통과
- [ ] 노드 클릭 내비게이션 — sanitize ID 역매핑→navigate, center no-op, 키보드(Enter) 단위테스트 통과
- [ ] issues.$key.tsx 배선 + 기존 이슈 상세 단위테스트 회귀 0
- [ ] i18n ko 추가 + ko.test(콜론 종결 검증) 통과
- [ ] D7 E2E — 펼치기→그래프 렌더(.node/SVG 셀렉터)→depth 전환→빈/truncated 시나리오
- [ ] pnpm verify (lint + typecheck + test + build) 그린, 기존 E2E 회귀 0
