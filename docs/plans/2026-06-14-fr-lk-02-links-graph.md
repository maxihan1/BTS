# FR-LK-02 링크 그래프 시각화 (백엔드 D1~D5)

> slug: fr-lk-02-links-graph
> type: api
> agent: backend-engineer
> primary_bc: issue-tracking
> 생성: 2026-06-14

## Brief

FR-LK-02 (§5.3.2) — 링크 그래프 시각화. FR-LK-01(완료, #135/#136)이 만든
`issue_links`(blocks/relates/duplicates/clones 4종, UUID FK) + `issues.parent_id`
데이터를 노드/엣지 그래프 형태로 노출한다.

이번 PR 범위 = **백엔드 D1~D5**.
- D1. 도메인 (그래프 노드/엣지 표현)
- D2. 명세 — 노드/엣지 표현
- D3. 데이터 모델 — (활용, 신규 테이블 없음)
- D4. 백엔드 — `GET /api/v1/issues/{key}/graph`
- D5. 백엔드 테스트

프론트 D6(SVG/force-directed) + D7(E2E)는 후속 PR(`ui/fr-lk-02-d6-d7-...`).

선행. §5.3.1 FR-LK-01 완료.

## 도메인 정리

- **BC**: issue-tracking (단일 BC, cross-BC 호출 없음).
- **영향 엔티티**: 없음(신규). FR-LK-01의 `issue_links`(blocks/relates/duplicates/clones 4종, source_id/target_id UUID FK) + `issues.parent_id`를 **읽기 전용 그래프**로 노출. D3 "(활용)" 일치.
- **새 용어**: 없음. node(노드=이슈), edge(엣지=링크/부모자식 관계)는 일반 시각화 용어 — glossary 추가 불필요.
- **재사용 인프라**:
  - `IssueLinkRepository.findOutwardWithIssue(issueId)` / `findInwardWithIssue(issueId)` — issue_links + issues 단일 JOIN, soft-delete 제외(N+1/cartesian 안전).
  - `IssueRepository.collectAncestors(issueId)` — parent_id 재귀 CTE(조상 체인).
  - `LinkType`(code/outwardLabel/inwardLabel/isSymmetric).
  - **갭**: 자식 조회(`findByParentId` 또는 `findChildren`) 부재 → 신규 추가 필요(parent-child 엣지의 자식 방향).
- **기존 결정 충돌**: 없음. ADR `2026-06-13-issue-link-vs-parent-child-separation`(링크 4종 ↔ parent_id 별개 메커니즘)을 그대로 따른다 — 그래프는 **두 메커니즘을 한 화면에 합쳐 표현**하되 엣지 종류로 구분(link 4종 + parent).
- **관련 ADR**: 없음(신규). 단, "그래프 탐색 범위/깊이"는 D2 명세에서 확정해야 할 핵심 결정(아래 스펙 미결).
- **인증/권한**: issue_links/parent_id는 created_by 미보존. SecurityFilterChain이 401 보장. 이슈 열람 권한 게이팅은 FR-LK-01 GET /links와 동일 수준(현재 추가 게이팅 없음) 유지.

### D2 핵심 미결(스펙에서 확정)
1. **탐색 범위/깊이** — (A) 1-hop 직접 이웃만 / (B) 깊이 제한(default 2, maxDepth+노드 상한) / (C) 연결 컴포넌트 전체(BFS, 노드 상한 가드).
2. **포함 관계** — link 4종 + parent-child를 모두 엣지로? (현 권장: 모두 포함, edge.type으로 구분).
3. **응답 형태** — `{ nodes:[{key,summary,statusKey,issueType?,isCenter}], edges:[{from,to,type,direction}] }` 형태 확정.

## 스펙

전체 스펙. [docs/specs/2026-06-14-fr-lk-02-links-graph.md](../specs/2026-06-14-fr-lk-02-links-graph.md)

핵심 결정 요약.
- `GET /api/v1/issues/{key}/graph?depth={1..3}`(기본 2). 깊이 제한 BFS, 노드 상한 100, `truncated` 플래그.
- 엣지 = link 4종(blocks/relates/duplicates/clones) + parent. `edge.type` 소문자. 링크는 저장 방향, parent는 from=부모/to=자식.
- 응답 `DataResponse<{center, depth, nodes:[{key,summary,statusKey,depth}], edges:[{from,to,type}], truncated}>`.
- 그래프 컨트롤러를 `com.bts.issue.link.web`에 두어 `LinkExceptionHandler`(404/400/500) 재사용. `INVALID_DEPTH`(400) + `MethodArgumentTypeMismatchException`(400) 핸들러만 신규.
- 신규 테이블/마이그레이션 없음(D3 활용). 읽기 쿼리(부모 1건·자식 N건)만 추가.

## Brainstorming Check

✅ 통과 (1회 iteration). gap 2건 보강.
1. depth 타입 불일치 → catch-all 500 변질 위험 → 400 `INVALID_DEPTH` 타입미스매치 핸들러 추가.
2. 응답 결정성(ORDER BY 부재) → 서비스 최종 정렬(노드 depth↑·key↑, 엣지 from·to·type) 명시.

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
