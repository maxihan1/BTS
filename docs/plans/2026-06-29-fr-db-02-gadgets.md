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

## Plan

> 전 task notification 단일 Gradle 모듈(`:backend:modules:notification`) — 테스트 컴파일 직렬화(memory: bts-plan-wave-gradle-module-compile). 카탈로그는 GadgetType enum 순수함수로 노출 → DashboardController 생성자 불변(memory: plan-files-constructor-injection-existing-tests).

### Task 1. GadgetType 카탈로그 enum + per-type config 형식 검증

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/notification/src/main/kotlin/com/bts/notification/dashboard/domain/GadgetType.kt`, `backend/modules/notification/src/test/kotlin/com/bts/notification/dashboard/domain/GadgetTypeTest.kt`]
- depends-on: []

**RED** (`GadgetTypeTest.kt`):
- `enum 은 12종(SDD 14.2) 을 정의한다` — assigned_to_me/recently_created/filter_result/issue_count/text_widget/link_list/pie_chart/bar_chart/created_vs_resolved/sprint_burndown/activity_stream/comments_recent.
- `각 타입은 category(ISSUE/STATIC/CHART/ACTIVITY) 와 enabled 플래그를 가진다` — MVP 6=true, AGG 3·DEFERRED 3=false.
- `text_widget 은 markdown 필수·1~10000 자 검증` (누락/초과 → 위반).
- `link_list 는 links 1~20 of {label 1~100, url http/https ≤2000} 검증` (javascript: 스킴 → 위반, EC6).
- `filter_result/issue_count 는 filterId(UUID)|aql(≤2000) 적어도 하나 필수` (둘 다 없음 → 위반, 둘 다 있음 → 통과, EC13).
- `pie_chart/bar_chart 는 field enum(status|assignee|priority|issueType) 필수` (enum 밖 → 위반, EC9).
- `알 수 없는 config 키는 무시한다` (EC5).
- 실패(예상): `GadgetType` 클래스 없음.

**GREEN** (`GadgetType.kt`):
- enum 12종 + `category`·`enabled` 프로퍼티 + per-type config 필드 디스크립터(key/type/required/maxLength/enumValues) 선언.
- `validateConfig(config: JsonNode?): Unit`(위반 시 `DashboardDomainException`) — 디스크립터 기반 형식 검증(형식만, cross-BC 존재 미확인 — favorites 선례).
- `catalog(): List<GadgetCatalogEntry>` 순수함수(디스크립터=검증과 단일 출처, drift 차단).

**REFACTOR**: 상수(MAX_MARKDOWN/MAX_LINKS 등) 추출 + KDoc. URL 스킴 화이트리스트 공통 함수.

**검증**: `./gradlew :backend:modules:notification:test --tests '*GadgetTypeTest'`

### Task 2. Dashboard.validateLayout 가젯-aware 확장 (쓰기경로)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/notification/src/main/kotlin/com/bts/notification/dashboard/domain/Dashboard.kt`, `backend/modules/notification/src/test/kotlin/com/bts/notification/dashboard/domain/DashboardTest.kt`]
- depends-on: [1]

**RED** (`DashboardTest.kt` 확장 — 기존 테스트 green 유지):
- `gadget 항목을 가진 layout 은 통과한다` — `{i,x,y,w,h,gadgetType:issue_count,config:{aql}}`.
- `legacy 타일(gadgetType 없음, {i,x,y,w,h,title}) 은 통과한다` (Gap A·EC11).
- `알 수 없는 gadgetType → DashboardDomainException` (S2·EC4 대소문자).
- `enabled=false 타입(pie_chart) → DashboardDomainException` (Gap B·EC10).
- `config 형식 위반 → DashboardDomainException` (S3).
- `i 누락/중복 → 위반` (S4·EC3). `x·y 음수 / w·h<1 → 위반` (S4).
- `layout 이 배열 아님(객체) → 위반`. `[] 빈 배열 → 통과` (EC2).
- `항목 51개 → 위반` (EC8·MAX_GADGETS). `64KB 초과 → 위반(기존)`.
- create() 와 applyPatch() 양 쓰기 경로 모두 검증.

**GREEN** (`Dashboard.kt`):
- `validateLayout` 확장 — JSON 배열 파싱, 항목별 i/x/y/w/h 검증, i 유일성, MAX_GADGETS(50), gadgetType 존재 시 GadgetType.valueOf + enabled=true + validateConfig 위임. gadgetType 없으면 위치만(legacy).
- 위반 메시지에 위반 항목 i + 사유.

**REFACTOR**: 항목 검증을 private helper(`validateLayoutItem`)로 추출. MAX_GADGETS 상수.

**검증**: `./gradlew :backend:modules:notification:test --tests '*DashboardTest'`

### Task 3. 가젯 카탈로그 API (GET /dashboards/gadget-catalog)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/notification/src/main/kotlin/com/bts/notification/dashboard/web/dto/GadgetCatalogDtos.kt`, `backend/modules/notification/src/main/kotlin/com/bts/notification/dashboard/web/DashboardController.kt`, `backend/modules/notification/src/test/kotlin/com/bts/notification/dashboard/web/DashboardControllerTest.kt`]
- depends-on: [1]

**RED** (`DashboardControllerTest.kt` 확장):
- `GET /gadget-catalog 는 200 + 12종 카탈로그(type/category/label/enabled/configFields) 를 반환한다`.
- `enabled 플래그가 정확하다` (MVP 6=true).
- `미인증 → 401` (currentActorId).
- 정렬: category→type 안정.

**GREEN**:
- `GadgetCatalogDtos.kt` — `GadgetCatalogResponse(gadgets: List<GadgetCatalogEntryDto>)`, entry=type/category/label/enabled/configFields. enum `catalog()` → DTO 매핑.
- `DashboardController` 에 `@GetMapping("/gadget-catalog")` 추가 — **생성자 불변**(enum 순수함수 호출, 신규 주입 없음). DataResponse 래퍼. currentActorId 401.

**REFACTOR**: 매핑 함수 분리 + KDoc.

**검증**: `./gradlew :backend:modules:notification:test --tests '*DashboardControllerTest'`

### Task 4. HTTP end-to-end + 라우팅 + repository 라운드트립 통합 테스트

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/notification/src/test/kotlin/com/bts/notification/dashboard/web/DashboardGadgetIntegrationTest.kt`]
- depends-on: [2, 3]

**RED/GREEN** (Testcontainers 통합 — vacuous 회피, 실 repo+HTTP):
- `POST/PATCH /dashboards 에 gadget layout → 200 저장 후 GET 라운드트립 시 config 보존` (repository JSONB 영속).
- `알 수 없는 gadgetType / enabled=false / config 위반 → 400 NOTIF_DASHBOARD_INVALID` (HTTP 경로 errorCode 단언).
- `GET /dashboards/gadget-catalog → 200`, **`gadget-catalog` 가 {id} UUID 파싱 400 으로 새지 않음**(Gap C·EC12 라우팅 회귀).
- `legacy title-타일 저장 → 200` (Gap A 회귀가드).
- 기존 FR-DB-01 통합 시나리오 green 유지(회귀 0).

**검증**: `./gradlew :backend:modules:notification:test --tests '*DashboardGadgetIntegrationTest'`

### Task 5. 문서 deviation 전수 동기화 + D 단계 마킹

**메타**.
- agent: `backend-engineer`
- files: [`docs/sdd/05-data-model.md`, `docs/sdd/14-dashboard-reports.md`, `docs/plan/product/notification-dashboard.md`, `docs/plan/fr-index.md`]
- depends-on: [4]
- 비-TDD(docs). RED/GREEN 없음 — 검증=verify-master-plan.

**작업**:
- SDD 05.11 `Gadget`/`dashboard_gadgets` → "layout JSON 임베드(별도 테이블 미채택)" 정정 + ADR 링크.
- SDD 14.3 `data class Gadget`(별도 엔티티) → layout 항목 임베드 표기. 14.4 데이터 fetch=프론트 직접(유지) 명확화.
- product `notification-dashboard.md §3.2` — D1(도메인 layout 임베드)·D2(명세)·D3(데이터모델=layout JSON, 별도테이블 아님)·D4(백엔드=저장·검증+카탈로그 API, 데이터 API 아님)·D5(테스트) → PR1(#205) 해당분 `[x]` + PR 참조. D6/D7 `[ ]` 유지(PR2).
- fr-index 주석/카운트 점검(FR 총수 123 불변 — 기존 FR 구현이라 추가/삭제 없음).

**검증**: `bash scripts/verify-master-plan.sh` (종료 0).

## Plan 메타

- task 수: 5
- 예상 wave: 4 (W1=T1 · W2=T2,T3 · W3=T4 · W4=T5). 단일 모듈이라 테스트 컴파일 직렬화 영향 — bts-impl 실측.
- TDD 강제: yes (T1~T4). T5 docs 예외(verify-master-plan).
- 신규 마이그레이션: 없음(layout JSON 확장). cross-BC import: 0.
- 추가 검증: `./gradlew :backend:modules:notification:test ktlintCheck detekt` clean + verify-master-plan.

## 리뷰 결과 (← /bts-review-plan 채움)
