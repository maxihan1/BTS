# FR-LK-02 링크 그래프 시각화 (백엔드 D1~D5) — 스펙

> 날짜. 2026-06-14
> FR. FR-LK-02 (§5.3.2), BC. issue-tracking
> 선행. FR-LK-01(완료, #135/#136) — issue_links 4종 + issues.parent_id
> 범위. 백엔드 D1~D5 (읽기 전용 그래프 엔드포인트). 프론트 D6/D7은 후속 PR.

## 한 줄 요약

한 이슈를 중심으로 한 **이웃 그래프**를 깊이 제한 BFS로 조회하는 읽기 전용 엔드포인트.
`issue_links`(blocks/relates/duplicates/clones) + `issues.parent_id`(parent-child)를
노드/엣지로 합쳐 노출한다. 신규 테이블 없음.

## 사용자 시나리오 (Given-When-Then)

- **S1**. Given 이슈 BTS-1이 BTS-2를 blocks 하고 BTS-3이 BTS-1의 부모일 때, When `GET /api/v1/issues/BTS-1/graph` 호출, Then 노드 3개(BTS-1 depth0, BTS-2 depth1, BTS-3 depth1) + 엣지 2개(blocks BTS-1→BTS-2, parent BTS-3→BTS-1)를 반환한다.
- **S2**. Given 링크가 2단계로 이어진 이슈망(BTS-1 → BTS-2 → BTS-5)에서, When `?depth=2` (기본값), Then BTS-5(depth2)까지 포함한다. When `?depth=1`, Then BTS-5는 제외된다.
- **S3**. Given 링크가 전혀 없는 이슈, When graph 조회, Then 노드 1개(자기 자신, depth0) + 엣지 0개 + `truncated=false`.
- **S4**. Given 존재하지 않거나 소프트삭제된 이슈 키, When graph 조회, Then 404 `ISSUE_NOT_FOUND`.
- **S5**. Given `?depth=0` 또는 `?depth=4`(범위 밖), When 조회, Then 400 `INVALID_DEPTH`.
- **S6**. Given 이웃이 노드 상한(100)을 초과하는 대형 그래프, When 조회, Then 상한까지만 노드/엣지를 담고 `truncated=true`.
- **S7**. Given 중심 이슈의 이웃 중 일부가 소프트삭제됨, When 조회, Then 삭제된 이웃은 노드/엣지에서 제외된다.

## 기능 요구사항 (FR)

- **FR1**. 엔드포인트 `GET /api/v1/issues/{key}/graph`. 인증 필요(SecurityFilterChain 401 보장).
- **FR2**. 쿼리 파라미터 `depth`(선택, 기본 `2`, 허용 `1..3`). 범위 밖 → 400.
- **FR3**. 중심 이슈에서 시작하는 BFS로 노드를 수집한다. 깊이 `d < depth`인 노드만 확장(이웃 조회)한다.
- **FR4**. 한 노드의 이웃 = (a) outward 링크 target, (b) inward 링크 source, (c) parent, (d) children. 소프트삭제된 이웃은 제외.
- **FR5**. 엣지 종류(`type`): `blocks`/`relates`/`duplicates`/`clones`(링크) + `parent`(부모-자식). 모두 소문자.
  - 링크 엣지: `from`=source 키, `to`=target 키(저장된 방향 보존).
  - parent 엣지: `from`=부모 키, `to`=자식 키.
- **FR6**. 노드 상한 `NODE_CAP=100`. BFS 중 상한 도달 시 추가 노드를 담지 않고 `truncated=true`.
- **FR7**. 엣지는 최종 노드 집합에 양 끝이 모두 포함된 것만 응답에 담는다. 엣지 중복은 제거한다(링크는 linkId, parent는 (from,to) 기준).
- **FR8**. 중심 이슈 미존재/소프트삭제 → 404 `ISSUE_NOT_FOUND`(`LinkedIssueNotFoundException` 재사용).

## 비기능 요구사항 (NFR)

- **NFR1**. N+1 금지. 노드당 이웃 조회는 기존 `findOutwardWithIssue`/`findInwardWithIssue`(단일 JOIN) 재사용 + parent/children 단일 쿼리. BFS는 노드 수만큼 쿼리(상한 100으로 폭주 차단).
- **NFR2**. 응답 결정성(테스트 안정성). 기존 링크 조회 쿼리에 `ORDER BY`가 없어 DB 반환 순서가 비결정적이므로, **서비스가 최종 출력을 정렬**한다. 노드는 `depth ASC, key ASC`. 엣지는 `from ASC, to ASC, type ASC`. 노드의 `depth`는 BFS 최단 거리(첫 발견 시점, `visited`로 고정).
- **NFR3**. 읽기 전용. `@Transactional(readOnly = true)`.
- **NFR4**. SQL injection 0 — jOOQ DSL 또는 파라미터 바인딩만(DATA.md §5).

## API 인터페이스 (REST)

```
GET /api/v1/issues/{key}/graph?depth=2
200 OK
{
  "data": {
    "center": "BTS-1",
    "depth": 2,
    "nodes": [
      { "key": "BTS-1", "summary": "...", "statusKey": "in_progress", "depth": 0 },
      { "key": "BTS-2", "summary": "...", "statusKey": "open",        "depth": 1 },
      { "key": "BTS-3", "summary": "...", "statusKey": "open",        "depth": 1 },
      { "key": "BTS-5", "summary": "...", "statusKey": "done",        "depth": 2 }
    ],
    "edges": [
      { "from": "BTS-1", "to": "BTS-2", "type": "blocks" },
      { "from": "BTS-3", "to": "BTS-1", "type": "parent" },
      { "from": "BTS-2", "to": "BTS-5", "type": "relates" }
    ],
    "truncated": false
  }
}
```

오류.
- 404 `{ "errorCode": "ISSUE_NOT_FOUND", ... }` — 중심 이슈 미존재/소프트삭제.
- 400 `{ "errorCode": "INVALID_DEPTH", ... }` — depth 범위 밖.

응답은 다른 이슈 링크 엔드포인트와 동일하게 `DataResponse<GraphResponse>`로 감싼다.

## 데이터 모델 변경

**없음(활용)**. D3 "(활용)" 표기대로 신규 테이블/컬럼/마이그레이션 없음.
새 **읽기 쿼리**만 추가한다(스키마 무변경).
- `IssueLinkRepository` 재사용: `findOutwardWithIssue`, `findInwardWithIssue`.
- 신규 읽기 메서드: 부모 1건 조회 + 자식 N건 조회(소프트삭제 제외, key/summary/statusKey/id 반환). `issues` 테이블 대상이므로 IssueRepository(또는 graph 전용 read 메서드)에 추가.

## 엣지 케이스

- **E1**. 자기 링크/자기 부모 없음 — DB CHECK(`chk_issue_links_no_self`) + parent self 가드로 원천 차단(그래프에 self-loop 불가).
- **E2**. 순환 그래프(relates 대칭 등) — `visited` 집합으로 무한 루프 차단. 같은 노드 재방문 시 노드 추가 안 함, 엣지만 dedup 후 보존.
- **E3**. depth=maxDepth인 두 노드 사이 엣지 — 둘 다 확장되지 않으므로 발견되지 않을 수 있음(깊이 제한 그래프의 의도된 한계). 최소 한쪽이 확장된 엣지만 응답.
- **E4**. 노드 상한 도달 후 발견된 엣지 — 끝점 중 하나가 노드 집합 밖이면 응답에서 제외(FR7).
- **E5**. 중심 이슈는 있으나 이웃 전부 소프트삭제 — 노드 1개 + 엣지 0개.
- **E6**. depth 파라미터 비정수(예: `?depth=abc`) — Spring `MethodArgumentTypeMismatchException` 발생. `LinkExceptionHandler`에 이 핸들러가 없으면 catch-all이 500으로 변질시키므로(brainstorming 발견), **타입 불일치 핸들러를 400 `INVALID_DEPTH`로 추가**한다. 범위 밖(0, 4, 음수)은 서비스가 `InvalidGraphDepthException` → 400 `INVALID_DEPTH`.

## 제약 조건

- 한 PR = 한 BC(issue-tracking). cross-BC 호출 없음.
- 그래프 컨트롤러는 `com.bts.issue.link.web` 패키지에 두어 `LinkExceptionHandler`(basePackages `com.bts.issue.link.web`)의 404/400/500 매핑을 재사용한다. `INVALID_DEPTH`(400)만 핸들러에 신규 추가.
- actor 추출 없음(graph는 읽기 전용, created_by 미보존) → catch-all이 401을 500으로 변질시킬 경로 구조적 부재(FR-LK-01과 동일).
- 권한 게이팅은 GET /links와 동일 수준 유지(이슈 열람 추가 게이팅 없음 — 후속 과제).

## 측정 가능한 완료 기준

- [ ] `GET .../graph` 200 — 노드/엣지/depth/truncated 정확(S1, S2, S3).
- [ ] depth 파라미터 동작 — 기본 2, 범위 밖 400(S2, S5).
- [ ] 404 — 미존재/소프트삭제 중심 이슈(S4).
- [ ] truncated — 노드 상한 동작(S6).
- [ ] 소프트삭제 이웃 제외(S7).
- [ ] parent + children 양방향 엣지 포함, link 4종 방향 보존.
- [ ] 단위 테스트(BFS/depth/cap/dedup/soft-delete) + HTTP 통합 테스트(200/404/400) 그린.
- [ ] ktlint + detekt(aggregate) 그린, BC 빌드 그린.

## Brainstorming Check

✅ 통과 (1회 iteration). 발견·보강한 gap 2건.
1. **depth 타입 불일치 → 500 변질 위험**(E6). `Int` 파라미터 비정수 입력은 `MethodArgumentTypeMismatchException`인데 LinkExceptionHandler에 핸들러가 없어 catch-all 500이 됨 → 400 `INVALID_DEPTH` 핸들러 추가로 보강.
2. **응답 결정성 모호**(NFR2). 기존 링크 쿼리에 ORDER BY 부재 → 서비스 최종 정렬 규칙(노드 depth↑·key↑, 엣지 from·to·type) 명시.

추가 확인(gap 아님, 의도 확정).
- 같은 쌍 다중 링크 타입(예: blocks + relates)은 별 엣지 2개(linkId로 dedup, 둘 다 보존).
- 노드 issueType은 이번 범위 제외(statusKey로 색상 충분, 프론트 enrich는 후속).
- 권한 게이팅은 GET /links와 동일 수준 유지(후속 과제) — 형제 일관성.
