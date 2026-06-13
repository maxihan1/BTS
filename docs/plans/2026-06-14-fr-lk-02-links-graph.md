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

## Plan

> 3개 TDD task. 단일 Gradle 모듈(issue-tracking)이라 wave는 의존 체인대로 직렬(T1→T2→T3).
> 파일 겹침 없음. 기존 링크 테스트 3계층 구조(repository/application/web) 미러.

### Task 1. 그래프 read 저장소 — 부모 1건 + 자식 N건 (소프트삭제 제외)

**메타**.
- agent: `backend-engineer`
- files:
  - `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/link/repository/IssueGraphRepository.kt`
  - `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/link/repository/GraphNeighborRow.kt`
  - `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/link/repository/IssueGraphRepositoryTest.kt`
- depends-on: []

**RED**: `IssueGraphRepositoryTest`(Testcontainers 통합, `IssueLinkRepositoryTest` 패턴 미러).
- `findParent`는 parent_id 가 가리키는 부모 행(id/key/summary/statusKey)을 반환한다.
- parent_id 가 NULL 이면 `findParent` 는 null.
- 부모가 소프트삭제(deleted_at)면 `findParent` 는 null(쿼리 단 제외).
- `findChildren`은 parent_id = issueId 인 자식들을 반환(소프트삭제 자식 제외, key ASC).

**GREEN**: `IssueGraphRepository`(`@Repository`, `DSLContext`).
- `GraphNeighborRow(id: UUID, key: String, summary: String, statusKey: String)`.
- `findParent(childId): GraphNeighborRow?` — issues self LEFT JOIN parent on `issues.parent_id`, `parent.deleted_at IS NULL`.
- `findChildren(parentId): List<GraphNeighborRow>` — `WHERE parent_id = ? AND deleted_at IS NULL ORDER BY key`.
- 모든 메서드 `@Transactional(readOnly = true)`, jOOQ DSL(파라미터 바인딩, injection 0).

**REFACTOR**: KDoc(메서드 책임·소프트삭제 정책), 컬럼 참조 상수화.

**검증**: `./gradlew :backend:issue-tracking:test --tests '*IssueGraphRepositoryTest'` (backend/ 하위에서 실행).

---

### Task 2. 그래프 BFS 서비스 — depth/cap/dedup/sort + depth 파싱

**메타**.
- agent: `backend-engineer`
- files:
  - `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/link/application/IssueGraphService.kt`
  - `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/link/domain/LinkExceptions.kt` (추가: `InvalidGraphDepthException`)
  - `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/link/application/IssueGraphServiceTest.kt`
- depends-on: [1]

**RED**: `IssueGraphServiceTest`(mockk — `IssueRepository`, `IssueLinkRepository`, `IssueGraphRepository` 페이크). `LinkApplicationServiceTest` 패턴.
- 중심 이슈 없음/소프트삭제 → `LinkedIssueNotFoundException`.
- 링크 1-hop(outward blocks / inward) + parent + children 이 노드/엣지로 정확히 수집(방향: 링크=저장방향, parent=부모→자식).
- depth=1 vs 2 차이(2-hop 이슈 포함/제외).
- 같은 링크가 양쪽에서 발견돼도 엣지 1개(linkId dedup), parent 엣지 (from,to) dedup.
- 노드 상한 초과 → 상한까지만 + `truncated=true`, 상한 밖 끝점 엣지 제외.
- 정렬: 노드 `depth ASC, key ASC`, 엣지 `from,to,type ASC`.
- `resolveDepth`: null/blank→2, "abc"→`InvalidGraphDepthException`, "0"/"4"/"-1"→`InvalidGraphDepthException`, "3"→3.

**GREEN**: `IssueGraphService`(`@Service`).
- `data class GraphNodeModel(key, summary, statusKey, depth)`, `GraphEdgeModel(fromKey, toKey, type)`, `IssueGraphResult(centerKey, depth, nodes, edges, truncated)` (파일 상단, `LinkResult` 동형).
- `companion object { const val DEFAULT_DEPTH=2; const val MAX_DEPTH=3; const val NODE_CAP=100 }`.
- `buildGraph(centerKey: IssueKey, rawDepth: String?): IssueGraphResult` — `@Transactional(readOnly=true)`.
  - depth 파싱·검증 → 중심 findByKey(404) → BFS(큐 `(id,key,summary,statusKey,depth)`, `visited:Set<UUID>`).
  - depth < maxDepth 인 노드만 확장: `findOutwardWithIssue`/`findInwardWithIssue`(링크) + `findParent`/`findChildren`(parent).
  - 후보 엣지 수집(fromId/toId 포함) → 최종 `visited` 양끝 필터 + dedup + sort.
- `InvalidGraphDepthException(raw: String?)`를 `LinkExceptions.kt`에 추가(다른 링크 예외 형제).

**REFACTOR**: BFS 확장부 private helper 분리(detekt 복잡도), KDoc(불변식·상한·정렬 규칙).

**검증**: `./gradlew :backend:issue-tracking:test --tests '*IssueGraphServiceTest'`.

---

### Task 3. 웹 계층 — 컨트롤러 + DTO + 400 핸들러 + HTTP 통합 테스트

**메타**.
- agent: `backend-engineer`
- files:
  - `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/link/web/IssueGraphController.kt`
  - `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/link/web/dto/GraphResponse.kt`
  - `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/link/web/LinkExceptionHandler.kt` (추가: `InvalidGraphDepthException`→400)
  - `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/link/web/LinkErrorCodes.kt` (추가: `INVALID_DEPTH`)
  - `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/link/web/IssueGraphControllerIntegrationTest.kt`
- depends-on: [2]

**RED**: `IssueGraphControllerIntegrationTest`(@SpringBootTest + Testcontainers, `IssueLinkControllerIntegrationTest` 패턴, 실 DB 시드).
- 200: 시드된 그래프(blocks + parent + 2-hop)로 nodes/edges/depth/truncated 검증.
- `?depth=1` vs 미지정(2) 차이.
- 404 `ISSUE_NOT_FOUND`: 미존재 키.
- 400 `INVALID_DEPTH`: `?depth=abc`, `?depth=0`, `?depth=4`.
- 엣지 type 소문자(blocks/parent), parent from=부모/to=자식.

**GREEN**:
- `IssueGraphController`(`com.bts.issue.link.web`, `@GetMapping("/api/v1/issues/{key}/graph")`, `@RequestParam(name="depth", required=false) depth: String?`) → `IssueGraphService.buildGraph` → `DataResponse(GraphResponse.from(result))`.
- `GraphResponse(center, depth, nodes: List<GraphNodeDto>, edges: List<GraphEdgeDto>, truncated)` + `GraphNodeDto(key, summary, statusKey, depth)` + `GraphEdgeDto(from, to, type)` + `from(result)`.
- `LinkErrorCodes.INVALID_DEPTH = "INVALID_DEPTH"`.
- `LinkExceptionHandler`에 `@ExceptionHandler(InvalidGraphDepthException::class)` → 400 + `INVALID_DEPTH`(기존 `problem()` 헬퍼 재사용, `@Suppress("TooManyFunctions")` 유지).

**REFACTOR**: KDoc(엔드포인트·depth 파싱 위임), import 정리.

**검증**: `./gradlew :backend:issue-tracking:test --tests '*IssueGraphControllerIntegrationTest'` → 모듈 전체 `:backend:issue-tracking:test` + `ktlintCheck` + `detekt`.

## Plan 메타

- task 수: 3 (각 TDD 사이클)
- wave: 의존 체인 직렬(T1→T2→T3), 단일 모듈 컴파일 단위라 병렬 이득 적음
- TDD 강제: yes (test 커밋 먼저)
- 신규 스키마/마이그레이션: 없음(D3 활용)
- 추가 검증: ktlint, detekt(aggregate), 모듈 test 그린

## 리뷰 결과 (← /bts-review-plan 채움)
