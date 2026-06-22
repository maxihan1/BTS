# FR-EP-01 — 에픽 이슈 타입과 자식 이슈 연결 메커니즘 (백엔드 D1~D5)

> slug: fr-ep-01-epic-link
> type: backend
> agent: backend-engineer
> primary_bc: issue-tracking
> 생성: 2026-06-22

## Brief

FR-EP-01 — 에픽 이슈 타입과 자식 이슈 연결 메커니즘 구현.

- 현재 `epic`은 이슈 타입으로만 존재(V003/V005 level 1). 이슈↔에픽 연결 메커니즘은 미구현.
- 선행 FR-IS-02(이슈 타입), FR-LK-01(이슈 링크 + parent-child) 모두 완료.
- **설계 갈림길(도메인 단계 결정)**: 기존 `issues.parent_id`(parent-child, FR-LK-01) 재사용 vs 별도 `issues.epic_id` 컬럼. FR-EP-02 진행률 집계 의미론과 맞물림 → bts-domain에서 결정 후 게이트 1에서 Maxi 확정.
- **이번 PR 범위**: 백엔드 D1~D5(에픽 연결 메커니즘 + API). 프론트 D6/D7(이슈상세 에픽 연결 UI · 보드 에픽 스윔레인)은 후속 PR(fr-ep-01-d6-d7-*).

classify: type=backend, agent=backend-engineer, primary_bc=issue-tracking

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
