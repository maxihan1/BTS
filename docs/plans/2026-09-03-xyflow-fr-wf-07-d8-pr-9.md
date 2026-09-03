# xyflow 다이어그램 편집기 (FR-WF-07 D8 · 로드맵 PR 9)

> 티어: T2
> slug: xyflow-fr-wf-07-d8-pr-9
> type: ui
> agent: frontend-engineer
> 생성: 2026-09-03

## Brief

**FR-WF-07 D8** — 워크플로우 편집기에 `@xyflow/react` 다이어그램 모드를 얹는다. 로드맵 PR 9.
FR-WF-07 의 **유일한 미완 D 마커**이고, 이것이 닫히면 §2 FR-WF 7개가 전부 `[x]` 가 된다.

**classify 원본과 교정.** `classify-task.ts` 는 `type=backend · agent=backend-engineer · tier=T1 ·
primary_bc=agile-planning` 을 냈으나 **세 축이 틀렸다**. 교정 근거는 실측이다.

- **tier T1 → T2.** `detect-tier.ts` 가 예상 변경 집합에서 `TIER: T2 · SURFACES: TEST, DEPS, DOC, FE_SRC`
  를 냈다. `DEPS`(`apps/web/package.json` · `pnpm-lock.yaml`)가 T2 표면이고, 혼합이면 최고 티어가
  지배한다(`/bts` §티어 판정 ①). **선언 T2 == 실측 T2.**
- **type backend → ui · agent → frontend-engineer.** 변경 표면이 전부 `apps/web` 이다.
- **primary_bc agile-planning → project-workflow.** FR-WF-07 은 project-workflow BC 다.

## 도메인 정리 (← /bts-spec §1 채움)

## 스펙 (← /bts-spec §2 채움)

## Sanity Check (← /bts-spec §3 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
