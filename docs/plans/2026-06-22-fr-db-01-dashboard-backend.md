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

## 도메인 정리

- BC: notification-dashboard (백엔드 모듈 = `notification`)
- 영향 엔티티: Dashboard (신규 Aggregate Root), DashboardShare (신규 자식), DashboardVisibility (신규 enum)
- 패키지: `com.bts.notification.dashboard.{domain, application, repository, web}`
- ID/user_id 타입: UUID (BC 관례, identity-access users.id 논리 참조 — FK 미설정)
- 다음 마이그레이션: V405 (notification 모듈, 머지 직전 재확인)

### Aggregate 모델
- **Dashboard** (Root): id, ownerId, name, description?, visibility, layout(JSONB), createdAt, updatedAt, version(OCC)
- **DashboardShare** (자식): (dashboard_id, user_id) 복합 PK, FK ON DELETE CASCADE — TEAM 전용
- **DashboardVisibility** (enum): PRIVATE(owner만) / TEAM(owner+shared) / ORG(인증 사용자 전체). PUBLIC=FR-DB-03 제외

### Maxi 핵심 결정 (2026-06-22)
- TEAM 공유 = 대시보드별 명시 사용자 목록 (`dashboard_shares`), user_groups 재사용 아님
- 모듈 위치 = notification 모듈 내 dashboard 패키지 (새 Gradle 모듈 아님)

### 새 용어 (glossary 추가 후보, 머지 시 동기화)
- 대시보드 (Dashboard), 공유 범위 (Visibility), 대시보드 공유 (Dashboard Share)
- 가젯 (Gadget) — FR-DB-02 예고

### 기존 결정 충돌
- 없음 (BTS 첫 대시보드)

### 관련 ADR
- [docs/decisions/2026-06-22-fr-db-01-custom-dashboard.md](../decisions/2026-06-22-fr-db-01-custom-dashboard.md) (생성됨)

### 절차 메모
- 무거운 대화형 grill-with-docs 대신 직접 도메인 정리 + Maxi 핵심 결정 2건 확인 (BTS 직접-진행 패턴). 신규 도메인이나 핵심 갈림길은 Maxi 결정 완료, 나머지는 SDD §14.1 / product §3.1에 명확.

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
