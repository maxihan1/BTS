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

## 스펙

전체 스펙. [docs/specs/2026-06-29-fr-db-02-gadgets.md](../specs/2026-06-29-fr-db-02-gadgets.md)

핵심 (PR1 = 백엔드 가젯 저장·검증).
- 가젯 = `dashboards.layout` JSON 항목 임베드(`{i,x,y,w,h,gadgetType,config}`). 신규 테이블 0.
- GadgetType enum 12종(SDD 14.2) + per-type config **형식만** 검증(favorites 선례, cross-BC 존재 미확인).
- `Dashboard.validateLayout` 가젯-aware 확장(쓰기 경로만). 신규 `GET /dashboards/gadget-catalog` 카탈로그 API(enabled 플래그=노출+쓰기수용 단일 출처).
- 데이터 fetch·프론트 컴포넌트·E2E는 PR2. pie/bar 집계 엔드포인트는 issue-tracking BC(별도 PR).

## Brainstorming Check

✅ 통과 (1회 iteration, gap 3건 발견 후 스펙 보강 — 전부 수정 가능, Maxi 결정 불요).
- **Gap A (회귀)**. FR-DB-01 프론트는 layout 을 `{i,x,y,w,h,title}`(gadgetType 없음)로 저장 → gadgetType 필수화 시 PR1~PR2 사이 기존 프론트 저장 전부 400. → **gadgetType 선택**(legacy 타일 허용), 있을 때만 가젯 검증. `title`/미지 키 보존·무시.
- **Gap B (broken 가젯)**. enabled=false(pie/bar/deferred) 저장 허용 여부 → **strict 거부**(EC10). enabled 플래그가 카탈로그 노출+쓰기 수용 단일 출처, false→true 단방향.
- **Gap C (라우팅)**. `/dashboards/gadget-catalog` ↔ `/dashboards/{id}`(UUID) 충돌 → Spring literal 우선이라 동작하나 **라우팅 회귀 테스트 필수**(EC12).

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
