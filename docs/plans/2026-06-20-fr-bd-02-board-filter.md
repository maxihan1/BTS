# FR-BD-02 — 보드 필터 (담당자/라벨/컴포넌트) 백엔드 D1~D5

> slug: fr-bd-02-board-filter
> type: api
> agent: backend-engineer
> BC: agile-planning
> 생성: 2026-06-20

## Brief

FR-BD-02 보드 필터. 칸반 보드 조회 API(`GET /api/v1/boards/{id}`)에 필터
쿼리파라미터(assignee / label / component)를 추가해, 조건에 맞는 카드만
컬럼에 배치하여 반환한다.

- **범위**. 백엔드 D1~D5만 (도메인·명세·데이터모델·백엔드·백엔드 테스트).
- **범위 외**. D6(필터 칩 UI) / D7(E2E) — FR-BD-01 보드 프론트(D6)가 아직
  미존재하므로, 보드 UI 생성 후 후속 PR로 분리 (Maxi 확정 2026-06-20).
- **선행**. §2.1 FR-BD-01 백엔드(#165) 완료 — `BoardController` /
  `BoardApplicationService` / `BoardRepository` 존재.
- **데이터 모델**. 신규 스키마 없음 (URL query 활용, product §2.2 D3).

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
