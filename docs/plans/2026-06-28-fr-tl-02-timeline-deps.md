# FR-TL-02 — 이슈 간 의존성 라인 (blocks 관계)

> slug: fr-tl-02-timeline-deps
> type: api
> agent: backend-engineer
> primary BC: issue-tracking (논리 FR은 agile-planning §4.2)
> 생성: 2026-06-28

## Brief

FR-TL-01에서 구현한 자체 SVG/CSS Gantt 타임라인 위에, `blocks` 관계로 연결된 이슈들을
화살표 라인으로 오버레이한다. 데이터는 FR-LK-01의 `issue_links` 테이블(blocks 관계 포함)을 활용한다.

- 백엔드: `GET /api/v1/timeline/deps?project=...` 신규 엔드포인트 — 타임라인에 표시 중인 이슈들 사이의
  blocks 의존 엣지 목록 반환
- 프론트: 자체 SVG Gantt 위에 의존 라인 SVG 오버레이 (클릭 시 강조)

선행(완료): FR-TL-01(Gantt 뷰), FR-LK-01(이슈 링크 issue_links), FR-LK-02(링크 그래프 BFS)

classify: type=api, agent=backend-engineer, primary_bc=issue-tracking

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
