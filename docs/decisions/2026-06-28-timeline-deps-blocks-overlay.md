# ADR — 타임라인 의존성 라인은 agile-planning 전용 project-scoped BLOCKS 엣지 엔드포인트 (FR-TL-02)

> 날짜. 2026-06-28
> 상태. 채택(Accepted)
> 관련 FR. FR-TL-02 (이슈 간 의존성 라인 — blocks 관계)
> BC. agile-planning (엔드포인트) + issue-tracking (데이터 어댑터) + shared-kernel (포트)
> 관련 ADR. [2026-06-26-gantt-rendering-self-svg](../adr/2026-06-26-gantt-rendering-self-svg.md) · [2026-06-14-link-graph-mermaid-visualization](2026-06-14-link-graph-mermaid-visualization.md) · [2026-06-13-issue-link-vs-parent-child-separation](2026-06-13-issue-link-vs-parent-child-separation.md)

## 맥락

FR-TL-01(PR #192/#194)이 agile-planning BC에 자체 SVG/CSS 간트 타임라인을 완성했다.
`GET /api/v1/timeline?project={key}` → `TimelineApplicationService`(BROWSE 게이트) →
cross-BC `TimelineLookupPort`(shared-kernel) → `TimelineLookupAdapter`(issue-tracking, 2단 visibility 게이트).

FR-TL-02는 이 간트 위에 `blocks` 관계 이슈를 화살표 라인으로 오버레이한다(SDD §13.3.2).
데이터는 FR-LK-01(PR #135)이 만든 `issue_links` 테이블(`source_id`, `target_id`, `link_type`)을 활용한다.
`LinkType.BLOCKS`는 단방향 저장(source가 target을 차단)이다.

이미 존재하는 유사 엔드포인트.
- **FR-LK-02 그래프** `GET /api/v1/issues/{key}/graph?depth={1..3}` (issue-tracking) — **단일 이슈 중심** BFS,
  **전체 링크 타입 5종**(BLOCKS/RELATES/DUPLICATES/CLONES/PARENT), mermaid flowchart 시각화.

## 결정

**FR-LK-02 그래프 엔드포인트를 재사용하지 않고, agile-planning에 타임라인 전용 project-scoped BLOCKS 엣지 엔드포인트를 신설한다.**

- 엔드포인트. `GET /api/v1/timeline/deps?project={key}` — agile-planning `TimelineController`에 추가.
  기존 `GET /api/v1/timeline`과 동일하게 actor 추출(401) → `TimelineApplicationService`가 BROWSE 게이트(403) → cross-BC 조회 순서.
- cross-BC. `TimelineLookupPort`(shared-kernel)에 `listBlocksDepsByProject(projectKey, viewerUserId): TimelineDepsPage` 메서드를
  **default 빈 구현**으로 추가(인터페이스 확장은 default 메서드로 인라인 fake 보호 — memory `interface-extension-default-method`).
  `TimelineLookupAdapter`(issue-tracking)가 실 구현.
- 범위. **BLOCKS 타입만**. 엣지 = `(blockerKey, blockedKey)` (source가 blocker, target이 blocked).
- 가시성. **양 끝 이슈가 모두 viewer에게 가시 + 동일 프로젝트 + 타임라인 아이템(start/due 중 1개+ 보유)** 인 엣지만 반환.
  한쪽이라도 비가시면 엣지 자체를 제외한다(비가시 이슈 키/존재 누출 차단 — FR-NT-03 BLOCKER 정신).

### 근거 — FR-LK-02 그래프 재사용을 폐기한 이유

| 축 | FR-LK-02 그래프 | FR-TL-02 타임라인 deps |
|---|---|---|
| 중심 | 단일 이슈 + depth BFS | 프로젝트 전역(타임라인에 보이는 이슈들 사이) |
| 링크 타입 | 5종 전체 | BLOCKS만 |
| 출력 | 노드+엣지(노드 메타 포함) | 엣지만(노드는 간트가 이미 렌더) |
| 시각화 | 독립 mermaid flowchart | 기존 간트 위 SVG 오버레이 |
| BC 소유 | issue-tracking | agile-planning(타임라인 소유자) |

두 기능은 질의 형태·BC 소유·시각화가 모두 다르다. 그래프 엔드포인트를 project-scoped로 비틀면
단일-중심 BFS 계약이 오염되고, agile-planning이 issue-tracking 엔드포인트를 직접 소비하게 되어 BC 경계가 무너진다.
FR-TL-01이 확립한 `TimelineLookupPort` 패턴을 그대로 확장하는 편이 BC 격리·일관성 모두 우월하다.

## 폐기한 대안

- **B. FR-LK-02 `/graph`를 project-scoped로 확장해 공유.** 단일-중심 BFS 계약 오염 + agile-planning→issue-tracking 직접 의존(BC 위반). 폐기.
- **C. 프론트가 각 타임라인 이슈마다 `/issues/{key}/links`를 N회 호출해 blocks만 필터.** N+1 호출 + 가시성 필터를 프론트가 담당(비가시 이슈 키 누출 위험). 폐기.
- **D. 엔드포인트를 issue-tracking에 두고 agile-planning 프론트가 두 BC 엔드포인트를 각각 호출.** 타임라인 = agile-planning 책임이라는 FR-TL-01 결정과 불일치. 폐기.

## 영향

- 백엔드(agile-planning). `TimelineController`에 `getDeps` 추가, `TimelineApplicationService`에 `getDeps(actorId, projectKey)` 추가(BROWSE 게이트 재사용).
- 백엔드(shared-kernel). `TimelineLookupPort`에 `listBlocksDepsByProject` default 메서드 + `TimelineDepEdge`/`TimelineDepsPage` VO 추가.
- 백엔드(issue-tracking). `TimelineLookupAdapter`가 신규 메서드 구현 — 단일 self-join SQL이 아니라 **가시 타임라인 집합 재사용** 방식. 기존 `IssueRepository.listVisibleForTimeline`(accessibleLevels 2단 게이트 + 최신 500 윈도우)이 반환한 가시 이슈 `id→key` 맵을 만들고, `IssueLinkRepository.findBlocksEdgesAmong(idSet)`가 양끝이 모두 그 집합 멤버인 BLOCKS 엣지만 반환한다. 양끝 가시성·동일프로젝트·날짜보유가 집합 멤버십으로 자동 보장(새 보안 판정 경로 0). **self-join 대신 집합 재사용 채택 사유** — (a) 보안 술어를 source/target 두 alias에 복제하면 검증 안 된 새 보안 경로가 생김(회피), (b) deps가 타임라인과 동일 500 윈도우에 결합돼 반환 엣지가 항상 렌더 가능한 막대 양끝(self-join은 그릴 수 없는 윈도우 밖 엣지까지 반환). trade-off — 500 초과 대형 프로젝트에서 양끝이 모두 윈도우 밖인 엣지는 누락되나 그 이슈는 간트에도 안 보임. `truncated`로 정직하게 알림.
- 데이터. 신규 테이블 0. `issue_links`(FR-LK-01) 활용. EXPLAIN 확정 — `findBlocksEdgesAmong`의 `link_type='blocks' AND source_id IN ids AND target_id IN ids`는 기존 `uq_issue_links(source_id,target_id,link_type)` UNIQUE 인덱스로 Index Only Scan. **마이그레이션 0 확정**(V033 불필요).
- 프론트(apps/web). 기존 자체 SVG 간트(`GanttChart.tsx`) 위에 의존 라인 SVG 오버레이 레이어 추가 + `/timeline/deps` 클라이언트. 클릭 강조. D6/D7.
- 계약. 엣지 응답 = `{ deps: [{ blockerKey, blockedKey }], truncated }`(정확 형식은 spec §6에서 확정).
