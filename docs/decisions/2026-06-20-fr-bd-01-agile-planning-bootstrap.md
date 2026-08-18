# ADR: agile-planning BC 부트스트랩 + 칸반 보드 컬럼=상태 매핑 (FR-BD-01)

> 날짜: 2026-06-20
> 상태: 채택
> 범위: agile-planning BC (신규 모듈), FR-BD-01 백엔드 (D1~D5)
> 관련 SDD: §13.1 (칸반 보드), §13.1.1 (컬럼=워크플로우 카테고리)
> 관련 PR: #165

## 맥락

agile-planning BC는 그린필드다. `backend/modules/`에는 identity-access · issue-tracking · project-workflow · notification · shared-kernel 5개 모듈만 있고 agile-planning 모듈 자체가 없다. FR-BD-01이 agile-planning BC의 첫 작업이다.

product 명세(agile-planning §2.1)는 칸반 보드를 도메인(D1. Board/Column) · 명세(D2. 컬럼=상태 매핑) · 데이터 모델(D3. `boards`, `board_columns(state_id)`) · 백엔드(D4. `GET /api/v1/boards/{id}` + 카드 이동 API) · 테스트(D5) · 프론트 UI(D6. @dnd-kit) · E2E+NFR(D7)로 정의한다.

SDD §13.1.1은 칸반 컬럼을 "워크플로우 카테고리(TODO / IN_PROGRESS / DONE)", 카드를 "이슈", 드래그앤드롭을 "@dnd-kit"으로 명시한다. 이슈는 issue-tracking BC가 소유하고 상태 전환 API는 issue-tracking의 `IssueController`가, 워크플로우 상태 카탈로그는 project-workflow의 `WorkflowStateCatalogImpl`이 제공한다.

## 결정 1 — agile-planning을 새 BC 모듈로 부트스트랩

`backend/modules/agile-planning/`을 project-workflow / notification 모듈을 템플릿으로 신규 생성한다. settings.gradle.kts 등록, build.gradle.kts(jOOQ codegen + Flyway + detekt/ktlint), `db/migration/agile-planning/` 마이그레이션 디렉토리, `com.bts.agileplanning.*` 패키지 레이아웃, ArchUnit BC 격리 룰을 포함한다.

**근거**. _index.md / product 명세 / domain/agile-planning.md가 agile-planning을 독립 BC로 정의한다. 다른 BC 호출은 shared-kernel 포트 + pgmq 이벤트 경유만 허용(BC 격리). notification(FR-NT-01) 부트스트랩과 동일하게 라이브러리 모듈로 시작.

## 결정 2 — 보드 컬럼 = 워크플로우 상태 카테고리 매핑

`board_columns`는 워크플로우 상태(`state_id`)에 매핑한다. 컬럼은 하나 이상의 상태를 묶을 수 있고(예: TODO 카테고리의 여러 상태), 보드 표시 시 각 이슈는 현재 상태가 속한 컬럼에 배치된다. 상태 카탈로그는 project-workflow의 `WorkflowStateCatalogImpl`을 cross-BC 포트로 읽는다(직접 import 금지).

**근거**. SDD §13.1.1. 칸반은 워크플로우 상태의 시각화이므로 컬럼이 상태(카테고리)에 종속되는 것이 자연스럽다. 보드가 상태를 독자 정의하면 워크플로우와 drift가 발생한다.

## 결정 3 — 카드 이동 = 컬럼 간 이동 = 워크플로우 전환 재사용

카드를 다른 컬럼으로 드래그 = 대상 컬럼이 매핑한 상태로의 **워크플로우 상태 전환**. issue-tracking의 기존 전환 메커니즘을 재사용하며 agile-planning이 전환 로직을 중복 구현하지 않는다. 컬럼 내 카드 순서는 기본 정렬(우선순위/생성일)이며 **드래그 재정렬(LexoRank)은 이번 범위에서 제외**한다(FR-BL-01로 미룸).

**근거**. 전환 규칙(게이트/권한/전환 가능 여부)은 project-workflow/issue-tracking이 소유한다. 보드가 상태를 직접 UPDATE하면 워크플로우 불변식(허용된 전환만)을 우회한다(patch-merge-domain-bypass 반례). 구체 메커니즘(전환 API 직접 호출 vs agile-planning 카드 이동 엔드포인트가 전환 포트 위임)은 spec에서 확정.

## 결정 4 — FR-BD-01 범위 = 백엔드 D1~D5

이번 작업은 백엔드(BC 신설 + 스키마 + `GET /boards/{id}` + 카드 이동 + 테스트)로 한정한다. 프론트 D6(@dnd-kit UI)/D7(E2E+NFR)은 후속 PR.

**근거**. 최근 BTS 표준(FR-NT-03, FR-MV-01 등 백엔드/프론트 분리). agile-planning 첫 BC 신설까지 겹치면 단일 PR이 과대해진다.

## 대안

- **보드가 상태를 독자 정의** — 워크플로우와 drift, 전환 불변식 우회. 기각.
- **카드 이동 시 보드가 직접 issues.status UPDATE** — 전환 게이트/권한 우회. 기각(결정 3).
- **컬럼 내 LexoRank 정렬 동시 도입** — FR-BL-01 범위 침범 + @dnd-kit/LexoRank 선행검증 부담. 이번 제외(Maxi 확정).

## 결과

- agile-planning 모듈이 신설되어 이후 FR-BD-02/03, FR-BL, FR-TL, FR-TT, FR-PL(일부)의 기반이 된다.
- 보드는 워크플로우 상태에 종속되며 전환 불변식을 재사용한다.
- 컬럼 내 재정렬·@dnd-kit·LexoRank는 후속 FR로 명시 이연.
