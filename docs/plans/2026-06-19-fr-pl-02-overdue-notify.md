# FR-PL-02 지연/임박 자동 알림

> slug: fr-pl-02-overdue-notify
> type: feature
> agent: backend-engineer
> 생성: 2026-06-19

## Brief

**원문**. "fr-pl-02 진행하자"

**FR-PL-02 (agile-planning product §6.2)** — 지연/임박 자동 알림. 우선순위 높음. 선행 §6.1(FR-PL-01 일정 필드, 완료) + notification BC.

**핵심 흐름**. 이슈 마감일(due_date/target_date)을 매일 1회 스캔 → 지연(overdue)/임박(D-day 접근) 이슈를 찾아 pgmq 이벤트 발행 → 기존 notification 인프라가 토스트로 전달.

**product D단계**.
- D1. 도메인 (backend-engineer)
- D2. 명세 — D-day 트리거 (스케줄러) (backend-engineer)
- D3. 데이터 모델 — (활용, 신규 스키마 없음) (db-engineer)
- D4. 백엔드 — Spring @Scheduled 일 1회 + pgmq 이벤트 (backend-engineer)
- D5. 백엔드 테스트 (backend-engineer)
- D6. 프론트 UI — 알림 토스트 (frontend-engineer)
- D7. E2E (qa-engineer)

**classify 보정 메모**. primary_bc=notification→issue-tracking (스케줄러는 일정필드 보유 BC 소유, BC 격리상 다른 BC 테이블 직접 조회 불가). type=backend→feature. cross-BC(스케줄러 위치 / notification 소비 / PR 분할 여부)는 도메인 단계에서 결정.

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
