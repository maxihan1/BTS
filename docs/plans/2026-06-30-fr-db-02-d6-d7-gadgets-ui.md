# FR-DB-02 D6/D7 — 가젯 시스템 프론트엔드 UI + E2E

> slug: fr-db-02-d6-d7-gadgets-ui
> type: ui
> agent: frontend-engineer (E2E task만 qa-engineer)
> primary_bc: notification-dashboard
> 생성: 2026-06-30

## Brief

FR-DB-02 "가젯 시스템(10종+ 표준)"의 D6(프론트 UI — 가젯 컴포넌트 + 카탈로그) + D7(E2E)을 구현한다.
백엔드 D1~D5는 PR #205로 이미 머지됨.

**핵심 deviation (ADR 2026-06-29-fr-db-02-gadget-system, Maxi 확정)**.
- 별도 `dashboard_gadgets` 테이블 미채택 — 가젯은 기존 `dashboards.layout` JSONB 배열 항목으로 임베드(`{i,x,y,w,h,gadgetType,config}`).
- 가젯 **데이터**는 프론트가 기존 BC API(/search/aql 등) 직접 호출(SDD 14.4).
- notification BC는 **설정 저장·검증 + 카탈로그 API**(`GET /dashboards/gadget-catalog`)만 담당.
- MVP 가시 가젯 = AQL/정적 6종(즉시) + 집계 3종(집계 PR 후) + 선행 FR 의존 3종(후속). 카탈로그 enum 12종 정의.

**분류 메모**. classifier가 'E2E' 키워드로 qa 오판 → ui로 교정(FR-DB-01/FR-BD-01/FR-SR-01 D6/D7 선례 일관).

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
