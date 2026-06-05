# FR-CM-03 — 컴포넌트별 기본 담당자 자동 할당

> slug: fr-cm-03-default-assignee
> plan_slug: issue/components-default-assignee
> type: feature
> agent: backend-engineer
> 생성: 2026-06-05

## Brief

이슈 생성 또는 컴포넌트 변경 시, 할당된 컴포넌트의 리드(lead)를 이슈의 기본 담당자로
자동 할당한다. 다중 컴포넌트가 할당된 경우 우선순위 규칙으로 단일 담당자를 결정한다.

- BC: issue-tracking
- 데이터 신설 없음 — 기존 `components.lead_user_id`(FR-CM-01) + `issue_components`(FR-CM-02) 활용
- 선행 완료: FR-CM-01(PR #59/#64), FR-CM-02(PR #81)

원문: FR-CM-03 컴포넌트별 기본 담당자 자동 할당 — 이슈 생성/컴포넌트 변경 시 컴포넌트
리드를 기본 담당자로 자동 할당, 다중 컴포넌트 우선순위 규칙.

분류: classify 오판(ui/frontend-engineer) → Maxi 확정 feature/backend-engineer.

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
