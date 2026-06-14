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

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
