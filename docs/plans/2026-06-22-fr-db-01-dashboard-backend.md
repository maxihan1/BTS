# FR-DB-01 — 사용자 정의 대시보드 (백엔드 D1~D5)

> slug: fr-db-01-dashboard-backend
> type: backend
> agent: backend-engineer
> BC: notification-dashboard
> 생성: 2026-06-22

## Brief

FR-DB-01 사용자 정의 대시보드 (notification-dashboard BC §3.1). 대시보드 컨테이너
CRUD + 그리드 레이아웃 저장 + 개인/팀/공유 visibility 권한.

이번 워크트리 범위 = **백엔드 D1~D5** (도메인 · 명세 · 마이그레이션 · CRUD API · 백엔드 테스트).
D6(react-grid-layout UI) / D7(E2E)은 별도 후속 PR로 분리 (Maxi 확정 2026-06-22).

분류 교정 메모. classify-task가 "대시보드/그리드/레이아웃" 단어로 type=ui·agent=frontend-engineer·
primary_bc=agile-planning 오판정 → 실제 백엔드 주도 풀스택이므로 backend/backend-engineer/
notification-dashboard로 교정.

### 명세 정본
- product: `docs/plan/product/notification-dashboard.md §3.1`
- SDD: `docs/sdd/14-dashboard-reports.md §14.1`

### 선행 조건 (§0 — 전부 충족)
- identity-access §2.9 세션: FR-AU-09 완료 (PR #37)
- issue-tracking 이벤트 발행: FR-NT 시리즈 완료
- §1 STOMP WebSocket 기술 검증: FR-NT-02에서 구현·검증됨

### 범위 경계 (명세 분리)
- FR-DB-01 = 대시보드 컨테이너 (그리드 레이아웃 + visibility 권한 + CRUD) ← 이번 작업
- FR-DB-02 = 가젯 10종 (별도 FR)
- FR-DB-03 = URL 공유/임베드 (별도 FR)

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
