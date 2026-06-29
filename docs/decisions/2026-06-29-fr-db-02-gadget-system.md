# ADR: FR-DB-02 가젯 시스템 (도메인 모델 + 데이터 소싱)

> 날짜: 2026-06-29
> 상태: 채택 (Accepted)
> 관련 FR: FR-DB-02 (notification-dashboard BC §3.2)
> 선행: FR-DB-01 (ADR 2026-06-22-fr-db-01-custom-dashboard)
> Plan: docs/plans/2026-06-29-fr-db-02-gadgets.md

## 맥락 (Context)

FR-DB-01에서 대시보드 컨테이너(그리드 레이아웃 + visibility 공유)는 완성됐다(#176 백엔드 / #178 프론트). FR-DB-02는 그 안에 배치하는 **가젯(위젯) 시스템**을 구현한다 — SDD 14.2 표준 가젯 카탈로그(12종 나열, "10종+ 표준").

설계 진입 시 **문서 간 충돌**과 **데이터 인프라 부재** 두 가지가 드러났다.

1. **저장 모델 충돌**. FR-DB-01 ADR D4는 "layout(JSONB)에 가젯 배치를 채운다"고 이미 결정·출시했다(`dashboards.layout`에 react-grid-layout `{i,x,y,w,h}` 배열 저장). 그러나 SDD 05.11/14.3 + product D3는 **별도 테이블** `dashboard_gadgets(gadget_type, position, config)`를 모델링한다. 둘을 모두 쓰면 위치(x,y,w,h)가 layout JSON과 gadget.position 두 곳에 중복돼 동기화 부담이 생긴다.

2. **데이터 소싱 충돌**. SDD 14.4는 "각 가젯이 자체 데이터 쿼리"(프론트가 기존 API 호출)라 하나, product D4는 "백엔드 — Gadget 데이터 API 10종"이라 한다. 후자는 notification-dashboard BC가 이슈/검색 데이터를 직접 쿼리해야 해서 BC 격리(한 PR=한 BC)를 위반한다.

3. **데이터 인프라 부재**. 실제 코드 확인 결과, 12종 중 데이터 소스가 준비된 것은 약 6종뿐이다.
   - 준비됨(AQL/정적): `assigned_to_me`·`recently_created`·`filter_result`·`issue_count`(`POST /api/v1/search/aql`, FR-SR-02/03 완료) + `text_widget`·`link_list`(정적, config만).
   - 인프라 신축 필요: `pie_chart`·`bar_chart`(필드별 분포 집계 — 범용 집계 엔드포인트 없음, worklog 집계만 존재).
   - 선행 FR 의존: `sprint_burndown`(FR-RP-01 §4.1 미착수), `created_vs_resolved`(시계열 집계 부재).
   - 부분: `activity_stream`·`comments_recent`(전역 활동 피드 부재, changelog는 이슈별만).

## 결정 (Decision) — Maxi 확정 2026-06-29

### D1. 가젯 저장 — 기존 `dashboards.layout` JSON 임베드 (별도 테이블 없음)
가젯은 `dashboards.layout` JSONB 배열의 각 항목을 `{i,x,y,w,h}` → `{i,x,y,w,h,gadgetType,config}`로 확장해 저장한다. 신규 테이블 0, 위치 단일 진실원천. FR-DB-01 ADR D4 정신 충실.

- **deviation 기록**. SDD 05.11(`Gadget` 엔티티)·SDD 14.3(`data class Gadget`)·product D3(`dashboard_gadgets` 테이블)와 어긋난다. 본 ADR로 deviation을 채택하고 같은 PR에서 SDD 05/14 + product를 동기화한다(layout JSON 임베드 모델로 정정).
- **불변식**. 기존 `Dashboard.validateLayout`(유효 JSON + 64KB 상한)을 확장해 layout 항목의 `gadgetType`이 표준 카탈로그 enum에 속하는지, `config`가 타입별 스키마를 만족하는지 검증한다. 64KB 상한은 가젯 수×config 크기를 포괄(12종 소규모 config 충분).

### D2. 데이터 소싱 — 프론트가 기존 BC API 직접 호출 (notification BC = 저장·검증만)
notification-dashboard BC는 가젯 **설정**(type + position + config)만 저장·검증한다. 가젯 **데이터**는 프론트 가젯 컴포넌트가 기존 per-BC API를 TanStack Query로 직접 호출한다. SDD 14.4 충실, BC 격리 유지.

- **deviation 기록**. product D4 "백엔드 Gadget 데이터 API 10종"과 어긋난다. notification BC가 이슈/검색 데이터를 쿼리하면 BC 격리 위반이므로 기각. product D4를 "프론트 자체 fetch + 필요한 집계 API는 데이터 소유 BC(issue-tracking)에 신축"으로 정정한다.

### D3. MVP 가젯 범위 — 준비된 6종 + 필드별 집계 API 신축으로 10+종 충족
- **PR1/PR2 즉시(AQL+정적 6종)**. assigned_to_me, recently_created, filter_result, issue_count, text_widget, link_list.
- **집계 API 신축(issue-tracking BC)**. 이슈 필드별 분포 집계(`groupBy` = status/assignee/priority/issueType 등)를 issue-tracking BC에 신축 → `pie_chart`·`bar_chart` 가젯이 프론트에서 직접 호출. **이 엔드포인트는 issue-tracking BC 소유이므로 별도 PR/BC로 분리**(notification PR1과 섞지 않음. plan 단계에서 PR 순서 확정).
- **후속(선행 FR 의존)**. `sprint_burndown`(FR-RP-01 필요), `created_vs_resolved`(시계열 집계), `activity_stream`·`comments_recent`(전역 피드)는 본 FR 범위에서 제외하고 선행 인프라/FR 완료 후 가젯 추가. 카탈로그 enum에는 정의해두되 MVP 미노출 여부는 spec에서 확정.
- "10종+" FR 요건은 (AQL 4 + 정적 2 + 집계기반 pie/bar 2 = 8) + 집계 시계열/추가 2종으로 충족 경로를 spec에서 확정한다.

### D4. PR 분할 — 백엔드(저장·검증) → 프론트(컴포넌트·카탈로그·E2E)
FR-DB-01 선례(#176/#178). PR1 = 가젯 저장·검증 백엔드(D1~D5, notification BC). PR2 = 가젯 컴포넌트 10+종 + 카탈로그 모달 + E2E(D6/D7, apps/web). 집계 API(issue-tracking BC)는 D3대로 별도.

## 결과 (Consequences)

- 신규 마이그레이션 없음(layout JSON 확장은 스키마 변경 아님). 도메인 검증 로직만 확장.
- **문서 전수 동기화 필요**(CLAUDE.md §명세/범위 변경). SDD 05.11/14.2/14.3 + product `notification-dashboard.md §3.2` + fr-index를 layout-임베드 모델 + 프론트 fetch 모델로 정정. `bash scripts/verify-master-plan.sh` 통과 필수.
- 가젯 데이터의 정확성/권한은 호출하는 기존 BC API(visibility·BROWSE 등)가 이미 보장 → notification BC는 권한 재구현 불요.
- 집계 엔드포인트는 issue-tracking BC 소유 → cross-BC. 본 FR-DB-02 PR1/PR2와 BC가 다르므로 별도 PR.
- 기존 결정 충돌: FR-DB-01 ADR과는 일치(layout JSON 정신 계승). SDD/product와는 본 ADR이 deviation을 명시 채택.
- glossary: "가젯(Gadget)" 용어를 독립 항목으로 추가 후보(현재 대시보드 항목 내 설명만 존재). Maxi 승인 후 머지 단계 동기화.
