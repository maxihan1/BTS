# FR-TT-02 — 이슈/사용자/기간별 시간 집계

> slug: fr-tt-02-worklog-aggregate
> type: backend
> agent: backend-engineer
> 생성: 2026-06-20

## Brief

사용자 원문: "FR-TT-02 진행. 다른 섹션에서 병행 작업 중이니 워크트리는 새로 만들어서 진행"

FR-TT-02 — 이슈/사용자/기간별 시간 집계 (agile-planning BC, Plan slug `agile/worklog-aggregate`).
선행 FR-TT-01(Worklog, #163/#166) 완료. `worklogs` 테이블 위에 집계(aggregate) 기능을 얹는다.

product/agile-planning.md §5.2 D단계:
- D1. 도메인 (backend-engineer)
- D2. 명세 — 집계 차원 (이슈/사용자/기간/프로젝트) (backend-engineer)
- D3. 데이터 모델 — 머티리얼라이즈드 뷰 검토 (db-engineer)
- D4. 백엔드 — `GET /api/v1/worklogs/aggregate?by=...` (backend-engineer)
- D5. 백엔드 테스트 (backend-engineer)
- D6. 프론트 UI — 표 + recharts 차트 (designer → frontend-engineer)
- D7. E2E + NFR (qa-engineer)

classify 결과: slug=fr-tt-02-worklog-aggregate, type=backend, agent=backend-engineer.
주의: classify의 primary_bc=issue-tracking은 키워드 추정이며, 실제 BC는 worklog 코드 위치로 확정한다.

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
