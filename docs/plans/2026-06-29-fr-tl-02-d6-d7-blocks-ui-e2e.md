# FR-TL-02 D6/D7 — 타임라인 의존 라인(blocks 오버레이) 프론트엔드 UI + E2E

> slug: fr-tl-02-d6-d7-blocks-ui-e2e
> type: ui
> agent: frontend-engineer
> primary BC: agile-planning (프론트)
> 생성: 2026-06-29

## Brief

FR-TL-02 백엔드 D1~D5(#200)는 머지 완료. 남은 프론트 D6~D7을 구현한다.
FR-TL-01에서 만든 자체 SVG/CSS Gantt 타임라인 위에, `blocks` 관계로 연결된 이슈들을
화살표 라인으로 오버레이하고 클릭 시 강조한다.

**백엔드 계약(머지 완료, #200)**.
- `GET /api/v1/timeline/deps?project=KEY` → `{data:{deps:[{blockerKey,blockedKey}],truncated}}`
- 엣지 = 양끝 이슈가 모두 (동일 프로젝트 + 미삭제 + 타임라인 아이템 + viewer 가시)인 BLOCKS 링크만.
  비가시/cross-project/날짜0 이슈는 백엔드에서 이미 제외 → 프론트는 누출 걱정 없이 그대로 렌더.
- blockerKey=차단측(source), blockedKey=피차단측(target).
- DTO nullable 0 (blockerKey/blockedKey: String, truncated: Boolean) → @JsonInclude(NON_NULL)↔Zod drift 무관.

**기존 plan 참조**: docs/plans/2026-06-28-fr-tl-02-timeline-deps.md §"(후속 PR) 프론트 D6~D7 — 개요" (T6~T11).
**게이트 1 PR 분할 결정**: 백엔드/프론트 2 PR (백엔드 #200 종료, 이 PR이 프론트).

classify: type=ui, agent=frontend-engineer, primary_bc=agile-planning (classifier qa 오판 교정).

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
