# FR-BD-03 — WIP 제한 + 스윔레인 (백엔드 D1~D5)

> slug: fr-bd-03-wip-swimlane
> type: api
> agent: backend-engineer (D3는 db-engineer)
> 생성: 2026-06-22

## Brief

FR-BD-03 (agile-planning BC, §2.3) — WIP 제한 + 스윔레인 백엔드.

- D1. 도메인
- D2. 명세 — WIP 초과 시 시각 경고만 (이동 차단 옵션)
- D3. 데이터 모델 — `board_columns.wip_limit`, `boards.swimlane_field`
- D4. 백엔드 — 카운트 + 경고 응답 API
- D5. 백엔드 테스트

선행 §2.1 FR-BD-01 완료됨. 직전 FR-BD-01/02와 동일하게 백엔드 먼저 → 프론트 D6/D7은 후속 PR.

SDD 참조. 13.1.1 (WIP 제한 = 컬럼당 최대 이슈 수), 13.1.3 (스윔레인 = 담당자별/Epic별/우선순위별 가로 분리).

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
