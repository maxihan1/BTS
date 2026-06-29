# FR-DB-02 — 가젯 시스템 (10종+ 표준)

> slug: fr-db-02-gadgets
> type: feature
> agent: backend-engineer
> primary_bc: notification-dashboard
> 생성: 2026-06-29

## Brief

FR-DB-02 가젯 시스템(10종+ 표준) 구현. 선행 FR-DB-01(#176 백엔드 / #178 프론트) 완료.
대시보드(FR-DB-01)에 배치하는 가젯(위젯) 다형성 + 10종 표준 가젯 + 데이터 API + 프론트 컴포넌트/카탈로그 + E2E.
notification-dashboard BC, Plan slug=dashboard/gadgets.
fr-ex-02가 다른 워크트리에서 진행 중이라 별도 worktree(fr-db-02-gadgets)로 진행.

product 체크리스트(notification-dashboard.md §3.2):
- D1. 도메인 — Gadget 다형성 + 10종 명세
- D2. 명세
- D3. 데이터 모델 — dashboard_gadgets(gadget_type, config)
- D4. 백엔드 — Gadget 데이터 API 10종
- D5. 백엔드 테스트
- D6. 프론트 UI — Gadget 컴포넌트 10종 + 카탈로그
- D7. E2E

## 도메인 정리

- **BC**: notification-dashboard (모듈 `backend/modules/notification`, 패키지 `com.bts.notification.dashboard`)
- **영향 엔티티**: Dashboard(기존, layout 검증 확장) — 신규 엔티티/테이블 0
- **새 용어**: 가젯(Gadget) — 대시보드 그리드에 배치하는 정보 카드. 표준 카탈로그 12종 나열(SDD 14.2). glossary 대시보드 항목 내 설명 존재 → 독립 항목 추가 후보(Maxi 승인 후 머지 동기화)
- **Maxi 확정 4건 (2026-06-29)**:
  1. 저장 모델 = `dashboards.layout` JSON 임베드(`{i,x,y,w,h}`→`{...,gadgetType,config}`). 별도 `dashboard_gadgets` 테이블 미채택(SDD 05/14.3·product D3와 deviation).
  2. 데이터 소싱 = 프론트가 기존 BC API 직접 호출. notification BC는 가젯 설정 저장·검증만(SDD 14.4 충실, product D4와 deviation).
  3. MVP = AQL/정적 6종 즉시 + 필드별 집계 API 신축(issue-tracking BC)으로 pie/bar 추가 → 10+종. sprint_burndown(FR-RP-01 의존)·created_vs_resolved·activity/comments는 후속.
  4. PR 분할 = 백엔드(저장·검증, D1~D5) → 프론트(컴포넌트·카탈로그·E2E, D6/D7). 집계 API는 issue-tracking BC라 별도 PR.
- **기존 결정 충돌**: FR-DB-01 ADR과 일치(layout JSON 계승). SDD 05.11/14.2/14.3 + product D3/D4와는 deviation → 본 PR에서 전수 동기화 + verify-master-plan 통과 필수.
- **관련 ADR**: [docs/decisions/2026-06-29-fr-db-02-gadget-system.md](../decisions/2026-06-29-fr-db-02-gadget-system.md) (생성됨), 선행 [2026-06-22-fr-db-01-custom-dashboard.md](../decisions/2026-06-22-fr-db-01-custom-dashboard.md)
- **cross-BC 주의**: pie/bar용 필드별 집계 엔드포인트는 issue-tracking BC 소유 → FR-DB-02 PR1/PR2와 BC 다름. plan 단계에서 PR 순서/경계 확정.

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
