# 보드 목록 응답에 canDelete — Jira 패리티 캠페인 PR ⑧

> 티어: T2
> slug: board-summary-can-delete
> type: api
> agent: backend-engineer (T4 만 qa-engineer)
> 생성: 2026-09-04

## Brief

**FR.** FR-BD-01 (보드 CRUD). 신규 FR 없음 · FR 총수 불변 · 범위 변경 없음.
`FR-BD-01-2b`(보드 소프트 삭제)는 `docs/plans/2026-08-31-board-crud-recovery.md` 의 **내부 식별자**이지
정본 FR ID 가 아니다 — `fr-index.md` 는 `FR-BD-01` 만 등재한다.

**사용자 원문.**
> 보드 목록 응답(`BoardSummaryResponse`)에 `canDelete` 를 싣고, 보드 e2e 셀렉터 4건을
> 컨테이너 스코프(`data-testid="board-header"`)로 좁힌다 — Jira 패리티 캠페인 PR ⑧.

**왜 지금인가.** 캠페인 다음 큰 UI 변화인 **PR ⑨**(사이드바 하위 목록 → 보드 목록 트리 + 보드마다 `⋯`)가
두 벽에 막혀 있고 이 PR 이 그 둘을 치운다.

1. **사이드바가 삭제 가능 여부를 모른다.** `canDelete` 는 지금 **단건 조회 응답에만** 실린다
   (`BoardController.getBoard`). 목록만 가진 사이드바는 보드마다 `useBoard()` 를 따로 불러야 한다.
   목록 응답에 실으면 **권한 판정 1회로 N건 전부**를 덮는다 — 목록 엔드포인트가 `projectKey` 를
   요청에서 직접 받으므로 N+1 이 성립하지 않는다.
2. **e2e 셀렉터가 스코프 없이 전역을 훑는다.** `page.getByRole('button', { name: /보드 관리/ })` 가
   두 spec 에 복붙돼 있다. PR ⑨ 가 사이드바 보드 행에 `⋯` 를 달면 Playwright strict mode violation
   으로 즉사한다 (캠페인 위험 **R10**).

**이 PR 은 UI 동작을 바꾸지 않는다.** 화면에 보이는 변화 0건. 유일한 prod UI diff 는 헤더 행에 붙는
`data-testid` 한 줄이고 픽셀·접근성 트리 무변경이다.

**분류 메모 — `classify-task` 오분류 1건.**
판별기가 `type=qa · tier=T1 · primary_bc=null` 을 냈다. 「e2e 셀렉터」라는 단어만 읽고 주 표면인
`backend/modules/agile-planning/src/main` 의 응답 DTO·컨트롤러를 놓쳤다.
`/bts` 티어 판정 5문 ①(혼합이면 최고 티어)·②(Maxi 지정 우선)에 따라 **T2 · agile-planning** 으로
덮었다. 표면 혼합 = `BE_MAIN`+`API` (T2) · `FE_SRC` (T1) · `TEST` (T1) → 최고 T2.

**선행 결정 뒤집기.** `docs/plans/2026-08-31-board-crud-recovery.md` 가 #416 에서
「`BoardSummaryResponse` 에 `canDelete`」를 *「목록에 실을 소비처가 없다」* 는 사유로 NOT-in-scope
기각했다. 소비처가 생겼으므로 그 행을 **지우지 않고 그 자리에서 뒤집는다** — 지우면 「왜 미뤘는지」와
「왜 되돌렸는지」가 함께 사라져 다음 판단의 근거가 0 이 된다.

**Maxi 확정 사항 2건.**
1. e2e 스코프화 범위 = **4건**. `board-manage.spec.ts` 의 `/보드 관리/`·`/보드 선택/` 과
   `scrum-board.spec.ts` 의 같은 2건. 캠페인 계획이 알던 것은 1건이었으나 조사 결과 2쌍이었다.
2. 스코프 방식 = **`data-testid="board-header"` 컨테이너**. 정규식 앵커는 PR ⑨ 가 같은
   `boardLabels.actions.triggerAriaLabel` 헬퍼를 재사용하면 접근성 이름이 바이트 단위로 같아져
   무력하다.

**착수 시점 좌표.** `origin/main` = `7d13cdb3d` (#444 컬럼:상태 1:N 반영 후). 이 plan 의 줄 번호는
전부 그 시점 실측값이다.

## Jira 대조 (전 타입 필수)

**조회 방식 — 계약 §1-0 재사용 승계다. 이번 세션은 실물을 조회하지 않았다.**
아래 J4·J5 는 **`docs/plans/2026-08-31-board-crud-recovery.md`(조회일 2026-08-25)** 가 남긴
근거를 계약 §1-0 「같은 표면을 두 번 조사하지 않는다」에 따라 **출처·조회일 그대로** 승계한 것이다.
번호도 원문 그대로 유지한다 — 다시 매기면 두 문서가 같은 근거에 다른 이름을 갖게 된다.

**이번 변경이 새로 건드리는 조작은 0건이다.** PR ⑧ 은 화면을 바꾸지 않고, 이미 조사된 J4·J5 의
동작을 **목록 응답이 지탱할 수 있게** 계약을 넓히는 것뿐이다. e2e 셀렉터 스코프화는 테스트 하네스
내부 사안이라 Jira 에 대응 개념이 없다.

### 근거 표 — 전부 Cloud (company-managed) · 승계

| # | 원문 근거 | 출처 | 조회일 |
|---|---|---|---|
| **J4** | 보드 **삭제**는 Boards 디렉터리 **행 `⋯` → Delete** 다. 설정 화면이 아니다. 이슈는 남는다 | [How to Delete a Software Board in Jira Cloud](https://support.atlassian.com/jira/kb/how-to-delete-a-software-board-in-jira-cloud/) | 2026-08-25 |
| **J5** | 권한 — 삭제는 Board admin 또는 **Project admin**. 권한이 없으면 **메뉴 항목 자체가 부재**하다(비활성이 아니다) | [Configure a company-managed board](https://support.atlassian.com/jira-software-cloud/docs/configure-a-company-managed-board/) | 2026-08-25 |

### 채택 판정

| # | 판정 | 사유 |
|---|---|---|
| J4 | **채택 (이 PR 이 선행 조건을 놓는다)** | 「행마다 `⋯`」는 **목록**이 행별로 무엇을 렌더할지 알아야 성립한다. 지금 `canDelete` 는 단건 조회에만 있어 목록이 판단 근거를 갖지 못한다. 이 PR 이 그 근거를 목록 응답에 싣고, `⋯` 자체는 PR ⑨ 가 단다 |
| J5 | **채택 (글자 그대로)** | 「권한 없으면 **렌더하지 않는다**」를 그대로 따른다 — 비활성이 아니다. 프론트의 fail-closed 관례(`canDelete === true` 로만 연다)가 이미 그 형태이고, 이 PR 은 목록 쪽에 같은 판정을 공급한다. 권한 주체는 `IssuePermission.SOFT_DELETE` 근사(선행 문서 X3 승계) |

### 의도적 편차

- **X3 승계 — 권한 주체 근사.** BTS 에 per-board 관리자 개념이 없어 Jira 의 「Board admin 또는
  Project admin」을 `IssuePermission.SOFT_DELETE` (프로젝트 스코프)로 근사한다. 선행 문서
  `2026-08-31-board-crud-recovery.md` 의 X3 를 그대로 승계하며, 이 PR 이 그 근사를 **바꾸지 않는다** —
  단건 조회가 이미 쓰는 술어를 목록에도 **똑같이** 적용할 뿐이다. 두 응답이 갈리지 않는 것이 요점이다.
- **X10 신설 — 프로젝트 스코프 판정이라 같은 프로젝트의 모든 보드가 같은 값을 갖는다.**
  Jira 는 보드별 admin 이 따로 있어 한 목록 안에서 `canDelete` 가 행마다 다를 수 있다. BTS 는
  프로젝트 스코프 근사(X3)의 필연적 귀결로 목록 전체가 한 값이다. **이번 PR 은 이 성질을 이용한다** —
  판정 1회로 N건을 덮으므로 N+1 이 없다. per-board 관리자를 도입하면 이 최적화도 함께 재설계된다
  (`created_by` 마이그레이션 + shared-kernel 포트 = T3 승격, 선행 문서 X3 가 이미 적어 뒀다).

### 조회하지 못한 것

없다. 이 PR 의 범위 전체가 선행 문서의 조회 범위 안에 있다.
**단, 근거는 2026-08-25 시점이다** — Jira Cloud 문서가 그 뒤 바뀌었는지는 확인하지 않았다.

## 도메인 정리

**BC.** `agile-planning` 단일. 다른 BC 를 직접 import 하지 않는다 — 권한 판정은 shared-kernel
포트 `IssuePermissionResolver` 를 거친다(`AgilePlanningBcArchTest` 가 강제).

**영향 엔티티.** 없다. `Board` 애그리게이트도 `boards` 테이블도 바뀌지 않는다.
이 작업이 건드리는 것은 **표현 계층(web/dto)과 그 계약을 소비하는 프론트**뿐이다.

**새 용어.** 없다. `canDelete` 는 도메인 용어가 아니라 **응답 필드**이고, 그것이 파생되는
권한 코드 `DELETE_ISSUE`(= `IssuePermission.SOFT_DELETE`)는 glossary §권한 「권한 코드」에
이미 등재돼 있다. `glossary.md` 갱신 불필요.

**기존 결정 충돌.** 1건 — 의도적으로 뒤집는다.
`docs/plans/2026-08-31-board-crud-recovery.md` 의 NOT-in-scope 표가 「`BoardSummaryResponse` 에
`canDelete`」를 *「⋯ 메뉴는 현재 보드에만 붙는다. 목록에 실을 소비처가 없다」* 로 기각했다.
그 전제가 캠페인 PR ⑨ 로 무너졌다. **행을 지우지 않고 그 자리에서 뒤집는다** — 지우면
「왜 미뤘는지」와 「왜 되돌렸는지」가 함께 사라져 다음 판단의 근거가 0 이 된다.

**관련 ADR.**

| ADR | 이 작업과의 관계 |
|---|---|
| [`2026-05-22-issue-permission-resolver-port`](../adr/2026-05-22-issue-permission-resolver-port.md) | 권한 포트 계약의 정본. `hasPermission(actorId: UUID, permission, scope): Boolean` 시그니처와 **호출 위치는 컨트롤러**라는 배치를 이 작업이 그대로 따른다. 무효화 없음 |
| [`2026-06-02-issue-permission-scheme-model`](../decisions/2026-06-02-issue-permission-scheme-model.md) | 권한 스킴 = 멤버십 + `role_permissions` 매트릭스. `SOFT_DELETE` 판정의 실제 평가 경로. 무변경 |
| [`2026-09-01-board-type-and-active-sprint`](../adr/2026-09-01-board-type-and-active-sprint.md) | `BoardSummaryResponse.boardType` 을 넣은 ADR. 이 작업은 **같은 DTO 에 필드를 하나 더 얹을 뿐** 그 결정을 건드리지 않는다. 다만 두 필드의 **필수성이 갈리는** 근거를 스펙 §제약 조건에 적는다 |

**BC 격리 확인.** 신규 cross-BC 호출 0건. pgmq 이벤트 0건. 이 작업이 늘리는 것은 이미 배선된
`IssuePermissionResolver` 호출 **1회**뿐이다.

## 스펙

### 사용자 시나리오 (Given-When-Then)

- **S1.** Given `SOFT_DELETE` 권한을 가진 사용자가 프로젝트 `ATLAS` 의 보드 목록을 조회한다.
  When `GET /api/v1/boards?projectKey=ATLAS` 를 부른다.
  Then 응답의 **모든 항목**이 `canDelete: true` 를 갖는다.
- **S2.** Given `BROWSE` 는 있으나 `SOFT_DELETE` 가 없는 사용자.
  When 같은 요청. Then 200 이고 모든 항목이 `canDelete: false` 다.
  **403 이 아니다** — 목록 조회 자체는 `BROWSE` 로 허용되고 `canDelete` 는 표시용 파생값이다.
- **S3.** Given `BROWSE` 조차 없는 사용자. When 같은 요청. Then **403** 이고 본문에 보드 정보가 없다.
  기존 LIST-2 계약이 그대로 유지된다.
- **S4 (소비자 · 이 PR 범위 밖 · PR ⑨).** Given 목록이 `canDelete: false` 를 실었다.
  When 사이드바가 보드 행을 렌더한다. Then `⋯` 메뉴의 「삭제」 항목을 **렌더하지 않는다**(비활성이 아니다).
  Jira 대조 **J5** 를 글자 그대로 따른다.
- **S5 (e2e 하네스).** Given 두 spec 이 보드 헤더의 `⋯` 를 집는다.
  When PR ⑨ 가 사이드바에 같은 접근성 이름의 버튼을 추가한다.
  Then 컨테이너 스코프 덕분에 **여전히 헤더의 것만** 잡힌다.

### Jira 대조 (전 타입 필수)

위 [`## Jira 대조 (전 타입 필수)`](#jira-대조-전-타입-필수) 절이 정본이다.
계약 §1-0 재사용 승계 — **J4**(삭제는 목록 행 `⋯` → Delete) · **J5**(권한 없으면 메뉴 항목 자체가 부재).
의도적 편차 **X3 승계**(권한 주체를 프로젝트 스코프로 근사) · **X10 신설**(그 근사의 귀결로
목록 전체가 한 값 → 판정 1회로 N건을 덮는다).

### 기능 요구사항 (FR)

**FR-BD-01** (보드 CRUD)에 속한다. 신규 FR 없음 · FR 총수 불변 · 범위 변경 없음.

| # | 요구사항 |
|---|---|
| **FR-1** | `GET /api/v1/boards?projectKey=` 응답의 각 항목이 `canDelete: Boolean` 을 싣는다 |
| **FR-2** | `canDelete` 의 값은 **단건 조회와 같은 술어**다 — `hasPermission(actor, SOFT_DELETE, Project(projectKey))`. 두 응답이 같은 사용자·같은 보드에 대해 갈리지 않는다. ⚠️ **백엔드 판별자는 없다**(코드리뷰 지적 5) — 두 줄이 같은 술어인지는 **소스 대조로만** 지킨다. 프론트 쪽은 `board-handlers.test.ts` 의 세 번째 분기가 같은 store 를 목록·상세로 읽어 짝 단언으로 잡는다. 넓히려면 LIST-4 에 `getBoard` 를 이어 붙여 두 응답의 `canDelete` 를 한 줄로 대조한다 |
| **FR-3** | 판정은 요청당 **정확히 1회**다. 보드 개수와 무관하다 |
| **FR-4** | 보드가 0건이어도 판정을 수행한다 — 데이터 유무가 권한 호출 횟수를 바꾸지 않는다 |
| **FR-5** | 프론트 `boardSummarySchema` 가 `canDelete` 를 파싱하고, 없는 응답도 **파싱에 성공**한다 |
| **FR-6** | MSW 목록 핸들러가 store 의 `canDelete` 를 통과시킨다 — 상수를 박지 않는다 |
| **FR-7** | 보드 헤더 `⋯`·보드 스위처를 집는 e2e 셀렉터 4건이 컨테이너로 스코프된다 |

### 비기능 요구사항 (NFR)

| # | 요구사항 | 측정 |
|---|---|---|
| **NFR-1** | 목록 응답 지연이 늘지 않는다 | 권한 판정 1회 추가 = 프로젝트 스코프 조회 1회. 보드 수 무관 (FR-3 이 기계로 잰다) |
| **NFR-2** | fail-closed | 백엔드 기본값 `false` · 프론트 `canDelete === true` 로만 연다. 필드 부재·파싱 실패·권한 불명이 전부 「삭제 불가」로 수렴 |
| **NFR-3** | 기존 화면 회귀 0 | 이 PR 의 유일한 prod UI diff 는 `data-testid` 한 줄. 픽셀·접근성 트리 무변경 |

### API 인터페이스 (REST)

**변경 엔드포인트 1개.** 신규 0 · 삭제 0 · 시그니처 변경 0.

```
GET /api/v1/boards?projectKey={key}
권한: BROWSE (변경 없음)
```

응답 `data[]` 항목 — **필드 1개 추가, 기존 4개 무변경.**

```diff
  {
    "boardId":   "uuid",
    "projectKey": "ATLAS",
    "name":       "ATLAS 보드",
    "boardType":  "SCRUM" | "KANBAN",
+   "canDelete":  true | false
  }
```

**하위 호환.** 필드 추가뿐이라 기존 소비자는 영향받지 않는다. 프론트 스키마는
`.optional()` 이므로 구버전 백엔드 응답도 파싱된다(§제약 조건 C-2).

### 데이터 모델 변경

**없다.** 마이그레이션 0건 · 스키마 0건 · jOOQ 재생성 불필요.
`canDelete` 는 저장되지 않고 **요청 시점에 계산**된다 — 권한은 시간에 따라 바뀌므로 영속하면 거짓이 된다.

### 엣지 케이스

| # | 상황 | 기대 |
|---|---|---|
| **E1** | 보드 0건인 프로젝트 | 200 + 빈 배열. 권한 판정은 **여전히 1회** 수행 (FR-4). 건너뛰면 `permissionGate.calls` 가 데이터 의존이 되어 테스트가 취약해진다 |
| **E2** | `BROWSE` 는 있고 `SOFT_DELETE` 없음 | 200 + 전 항목 `false` (S2). 403 아님 |
| **E3** | 같은 프로젝트 안에서 보드마다 값이 다를 수 있나 | **없다.** 프로젝트 스코프 근사(X10)의 귀결. 목록 전체가 한 값이다 |
| **E4** | 백엔드가 필드를 안 보냄(롤백·구버전) | 프론트 파싱 성공 + `undefined` → `=== true` 가 false → 삭제 UI 미노출. fail-closed 유지 |
| **E5** | MSW store 에 `canDelete` 미정의 | `?? true` 로 상세 조립부(`toResponseDetail`)와 **같은 기본값**. 두 조립부가 갈리면 스위처가 목록 파싱에서 죽는다(`board-handlers.ts` 주석이 경고한 그 결함) |
| **E6** | `data-testid` 오타·삭제 | 🛑 **침묵사 지점.** `getByTestId(...).getByRole(...)` 이 count 0 이 되고 `toHaveCount(0)` 류 **부재 단언은 그대로 통과**한다(실측 확인). 🔧 **정정(코드리뷰 지적 4)** — 「컨테이너 실재 단언이 **이것만을** 막는다」는 과장이었다. 실제 spec 순서상 그보다 앞에 **스코프된 존재 단언**(`board-manage.spec.ts` 의 `toContainText(DEFAULT_BOARD_NAME)` · `scrum-board.spec.ts` 의 `toContainText(KANBAN_BOARD_NAME)`)이 있어 오타는 그쪽에서 먼저 red 다. 실재 단언의 실익은 **실패 메시지 가독성 + 양성 단언이 앞서지 않는 경로가 생겼을 때의 보험**이다. 「가드가 있으니 앞의 양성 단언을 걷어내도 된다」로 읽지 마라 — 반대다 |
| **E7** | 테스트 중간에 보드 이름이 바뀜 (S3 rename) | 셀렉터의 **정규식을 유지**해야 산다. 접근성 이름이 `보드 관리, {name}` 이라 `exact` 로 굳히면 rename 뒤 못 찾는다 |
| **E8** | 보드가 1건뿐인 테스트 픽스처 | 🛑 **판별식이 공허해진다.** N=1 이면 「1회」와 「N회」가 구분되지 않는다. LIST-4·LIST-6 은 **보드 2건 이상**을 쓴다 |

### 제약 조건

- **C-1 — 권한 판정은 컨트롤러에서 한다.** 저장소 can-* 패턴 전수 확인 결과 권한 파생 불리언은
  전부 컨트롤러 계산이고(`canDelete`·`canCreateProject`), 서비스 계산은 도메인 파생 하나뿐이다
  (`canResetToDefault`). `BoardApplicationService` 생성자에 resolver 가 없는 것도 그 배치의 결과다.
  DTO companion 은 **계산하지 않고 인자로 받는다**.
- **C-2 — 프론트 스키마는 `.optional()` 이다.** 상세 스키마의 전례를 따른다.
  필수로 두면 ① 필드를 뺀 응답 1건에 목록 파싱이 통째로 죽고, 그건 보드 스위처 · 백로그 헤더 ·
  `ProjectViewChrome` 탭바 **3곳을 동시에** 지운다 ② `BoardSummary` 로 선언된 인라인 픽스처 9곳이
  타입 에러가 나 PR 이 부풀고 신호가 묻힌다.
  **`boardType` 과 갈리는 이유** — `boardType` 은 없으면 그 자체가 결함이고 기본값으로 때우면
  스크럼이 칸반으로 오인된다. `canDelete` 는 없으면 **「삭제 못 함」이 옳은 해석**이다.
  기본값이 fail-closed 와 일치하는 유일한 필드라 optional 이 안전한 쪽이다.
- **C-3 — `.optional()` 의 대가를 판별식으로 갚는다. 단 그 판별식은 한 축을 못 본다.**
  런타임 파싱이 백엔드 누락을 못 잡는 구멍이 생긴다. 백엔드 DTO ↔ 프론트 zod ↔ MSW 목록 조립부
  **세 목록이 서로를 모르는** 상태이고, 이것은 저장소가 이미 이름 붙인 지배 결함 양식이다.
  3-way 차집합 판별식 + 비-공허 짝으로 닫는다.

  > 🛑 **정정 2회차 (2026-09-04 · 리베이스 후).**
  > 리뷰 시점(base `7d13cdb3d`)의 정정은 「`apps/web` 의 vitest 가 어떤 자동 게이트에서도
  > 안 돈다」였다. **그 사이 `472f05c49`(#451)가 그것을 고쳤다** — `push-frontend-tests.ts` 가
  > 신설되고 `.husky/pre-push:74` 에 무조건 배선됐다. 이제 프론트 테스트는 푸시 때 돈다.
  >
  > **그런데 이 판별식은 여전히 한 축을 못 본다.** 좁힘이 `vitest related` 의
  > **모듈 그래프**로 이뤄지는데(`select-test-scope.ts` `frontendScope`), 이 판별식은
  > `BoardResponses.kt` 를 `import` 가 아니라 **`readFileSync` 로 읽는다.** 따라서.
  >
  > | 무엇이 바뀌면 | 판별식이 도나 |
  > |---|---|
  > | `api/boards.ts` (zod) | ✅ 돈다 — 판별식이 `boardSummarySchema` 를 import 한다 |
  > | `mocks/board-handlers.ts` (MSW) | ✅ 돈다 — 판별식이 핸들러를 import 한다 |
  > | **`BoardResponses.kt` (백엔드 DTO) 단독** | ❌ **안 돈다** — 프론트 변경 0건이라 `frontendScope` 가 `skip` 이다 |
  >
  > ⇒ **가장 위험한 방향이 정확히 안 덮인다.** 이 판별식이 존재하는 이유가 「백엔드가 필드를
  > 추가·개명했는데 프론트 셋이 못 따라가는 것」인데, 그 시나리오에서 아무것도 안 돈다.
  > 저장소가 이미 이름 붙인 양식이다 — 메모리 `[[self-reading-guard-needs-helper-level-tests]]`.
  > ⇒ **처방은 Task 4 안에서 닫는다**(아래 「백엔드 축 커버」 절). 별건 PR 로 미루지 않는다 —
  > 미루면 이 판별식은 만들어진 날부터 반쪽인 채로 굳는다.
- **C-4 — e2e 스코프는 앵커가 아니라 컨테이너다.** `보드 관리` 문자열의 생산자는
  `i18n/board-labels.ts` 의 `triggerAriaLabel` **하나**이고, `ProjectTree.tsx` 가 「PR ⑨ 가 이
  하위 목록을 보드 목록으로 갈아치운다」고 이미 예고한다. PR ⑨ 가 같은 헬퍼를 부르면 접근성 이름이
  **바이트 단위로 같아져** `^…$` 앵커도 `exact` 도 못 가른다.
  **앵커는 「PR ⑨ 가 다른 이름을 쓴다」는 가정 위에서만 옳고, 컨테이너는 무슨 이름을 쓰든 불변이다** —
  미래 PR 의 선택에 정합성을 위탁하는 셀렉터는 스코프된 것이 아니다.
- **C-5 — 한 PR = 한 BC.** 백엔드는 `agile-planning` 만 건드린다. `apps/web` 은 BC 가 아니라
  그 계약의 소비자다.
- **C-6 — 삭제 API 의 「존재 검사 → 권한」 순서를 건드리지 않는다.** 그 순서가 403↔404 의미를
  가르는 계약이고 DEL-3 가 회귀를 막는다. 이 PR 은 목록 경로만 손댄다.

### 측정 가능한 완료 기준

1. `test:` 커밋에서 **LIST-1 · LIST-4 · LIST-5 · LIST-6 red 를 눈으로 봤다**
2. `feat:` 후 **LIST-4 초록**을 눈으로 봤다 — 🛑 기본값 `= false` 때문에 호출부를 안 고쳐도
   컴파일이 통과한다. 응답이 전량 `false` 인데 컴파일러도 detekt 도 침묵하고 **LIST-4 만이** 잡는다
3. 3-way 판별식의 **뮤테이션 2종 red 를 각각** 관측했다 (zod 한 줄 삭제 · MSW 한 줄 삭제).
   하나만 확인하면 「셋 중 둘만 보는 반쪽 판별식」인지 알 수 없다. 🛑 **GREEN 선커밋 뒤에** 한다
4. `data-testid` 오타로 두 spec red 1회 확인 후 원복했다 (E6 침묵사 지점)
5. e2e 로그의 `outside of Vite serving allow list` 오염이 **0** 이다
6. `node scripts/build-doc-index.mjs --check` 초록 · 판별식 전량 초록 · ktlint/detekt 초록

## Sanity Check

컨트롤러가 지목한 3건 + 자체 점검 4항목(누락·모호·가정·엣지)을 흔들었다.
**gap 3건 발견 → 전부 이 문서 안에서 1회 보강했다.** Maxi 결정이 필요한 항목은 없다.

**❓ 발견 1 — 소비처 없는 필드를 먼저 낸다 (지목 1).**
`#416` 이 「목록에 실을 소비처가 없다」로 기각했던 것을 뒤집는 PR 인데, 소비처인 PR ⑨ 는
아직 안 왔다. 두 위험을 갈라 판정했다.
- **죽은 필드로 남을 위험 — 낮다.** PR ⑨ 는 캠페인 계획의 PR 분할표에 `⑤ ⑧ 의존`으로 등재돼
  있고 ⑤ 는 이미 머지됐다(#445). 순서가 「⑧ → ⑨」인 것은 **⑨ 가 ⑧ 없이는 불가능하기 때문**이지
  선호가 아니다.
- **그 사이 기간의 위험 — 0 에 수렴한다.** 필드는 **추가**뿐이고 기존 소비자를 건드리지 않는다.
  값이 틀려도 지금은 아무도 안 읽는다. 반대로 **지금 넣지 않으면 PR ⑨ 가 보드마다
  `useBoard()` 를 추가로 부르는 N+1 을 안고 태어난다** — 그것이 판정 E 가 이 순서를 정한 이유다.
- ⇒ 보강. 위 §사용자 시나리오에 **S4 를 「이 PR 범위 밖 · PR ⑨」로 명시**해 두었다.
  소비처가 문서에 남으면 필드가 고아가 됐을 때 누가 봐도 안다.

**❓ 발견 2 — `.optional()` 의 구멍을 스펙이 인정만 하고 닫지 않았다 (지목 2·3).**
초안은 C-2 에서 `.optional()` 을 고르고 「런타임이 못 잡는다」를 대가로 적기만 했다.
그건 **가정 누락**이다 — 대가를 적는 것과 갚는 것은 다르다.
- ⇒ 보강. **C-3 을 신설**해 3-way 판별식을 스펙 수준 제약으로 못박고, §완료 기준 3 에
  **뮤테이션 2종을 각각 관측**하는 조건을 넣었다. 「하나만 red 확인」은 반쪽 판별식을 통과시킨다.

**❓ 발견 3 — 엣지 케이스에 침묵사 지점이 빠져 있었다.**
초안 엣지 표는 권한·데이터 축만 덮고 **하네스 축이 비어 있었다**. 스코프화는 「깨지면 빨간불」이
아니라 **「깨지면 조용히 0건이 되는」** 종류의 변경이다.
- ⇒ 보강. **E6**(testid 오타 → `toHaveCount(0)` 이 그대로 통과, 실측 확인) ·
  **E7**(rename 중 `exact` 금지) · **E8**(보드 1건이면 N+1 판별식이 공허)을 추가했다.

**자체 점검 4항목.**
- **누락된 요구사항** — 없다. FR-1~7 이 백엔드·프론트·하네스 세 축을 덮고, 각 축에 완료 기준이 물린다.
- **모호한 표현** — 「같은 술어」를 FR-2 에서 함수 호출로 못박았다. 「판정 1회」는 FR-3 이
  기계 단언(LIST-6)으로 환원한다.
- **가정 누락** — 발견 1·2 로 처리. 남은 가정은 **「2026-08-25 시점 Jira 문서가 아직 유효하다」**
  하나이고, §Jira 대조 「조회하지 못한 것」에 그대로 적혀 있다.
- **엣지 케이스 미커버** — 발견 3 으로 처리.

**PR 분할 필요 없음.** gap 이 2회째 반복되지 않았고, 세 축이 파일 교집합 0 이라 한 PR 로 초록이 선다.

## Plan

> **Jira 매핑** (§1-7 차집합 대조) — `J4 → Task 1` · `J5 → Task 1 · Task 3`. 채택 2건 전량이 task 에
> 물렸고 범위 밖 항목은 0건이다. J4·J5 의 **소비 지점**(사이드바 보드 행 `⋯` 렌더)은 PR ⑨ 이지만,
> 두 항목이 요구하는 **데이터 계약**은 이 PR 이 전부 놓는다.

### Task 1. 목록 응답에 `canDelete` — 권한 판정 1회로 N건을 덮는다

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/web/dto/BoardResponses.kt`, `backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/web/BoardController.kt`, `backend/modules/agile-planning/src/test/kotlin/com/bts/agileplanning/web/BoardControllerIntegrationTest.kt`, `backend/modules/agile-planning/src/test/kotlin/com/bts/agileplanning/web/dto/BoardResponsesTest.kt`]
- depends-on: []
- jira: [J4, J5]

**RED** — 🛑 **커밋 2개로 쪼갠다.** Kotlin 은 테스트 **컴파일**이 깨지면 그 모듈의 테스트가
하나도 안 돈다. 한 커밋에 뭉치면 「단언이 빨갛다」가 「컴파일이 빨갛다」에 삼켜져 red-first
대조가 무의미해진다.

*커밋 ① `test:` — 단언 red (컴파일은 통과).* `BoardControllerIntegrationTest.kt`.
하네스 `PermissionGate`(`:177`)를 그대로 쓴다 — 신규 모킹 불필요.

| 테스트 | 내용 | 예상 실패 | 원형 |
|---|---|---|---|
| **LIST-4** | 목록 각 항목에 `canDelete` — 보드 **2건**, 둘 다 `true` | `No value at JSON path $.data[0].canDelete` | LIST-3 `:624-642` 형 |
| **LIST-5** | `permissionGate.denied.add(SOFT_DELETE)` 면 전 항목 `false` (BROWSE 는 통과) | 동상 | CANDEL-1 `:1755`/`:1771` |
| **LIST-6** | **N+1 판별식** — 보드 2건이어도 `permissionGate.calls` 가 정확히 `[BROWSE, SOFT_DELETE]` | 현재 1건 | — |
| **LIST-7** | **빈 프로젝트 판별식** — 보드 **0건**이어도 200 + `[]` 이고 `calls` 가 여전히 `[BROWSE, SOFT_DELETE]` | 현재 1건 | — |
| **LIST-1 수정** | `:606-607` `containsExactly(BROWSE)` → `containsExactly(BROWSE, SOFT_DELETE)` | 현재 1건 | — |

**LIST-6 단언 바로 위에 주석**(리뷰 이슈 1 · 1A).
```
// 이 개수를 늘리는 수정은 「테스트 갱신」이 아니라 X10 근사가 깨졌다는 신호다.
```

**LIST-7 은 리뷰가 찾은 구멍이다** (이슈 3 · 3A). 스펙 FR-4·E1 이 「데이터 유무가 권한 호출 횟수를
바꾸지 않는다」를 명시했는데 초안 task 어디에도 그것을 재는 테스트가 없었다. 기존 목록 테스트는
LIST-1/2/3 뿐이고 **보드 0건 케이스가 전무하다**(`BoardControllerIntegrationTest.kt:91-93` 색인 확인).

★ **LIST-4·LIST-6 은 보드 2건 이상이어야 비-공허하다** (스펙 E8). 1건이면 「1회」와 「N회」가
구분되지 않는다.
★ **LIST-1 수정을 이 커밋에 넣는 것이 핵심이다.** `feat:` 로 미루면 그 커밋이 「초록으로 만든 것」과
「깨서 고친 것」을 섞어 대조가 흐려진다. `test:` 시점의 LIST-1 red 가 곧 *「이 PR 은 판정을 1회 더
한다」* 는 선언이다.
★ 전수 스윕 결과 **깨지는 기존 단언은 LIST-1 하나뿐**이다. 같은 파일의 다른 `permissionGate.calls`
단언 12곳은 전부 다른 엔드포인트고, ERR-1(`:1668`)은 목록 경로지만 calls 단언이 없다.

*커밋 ② `test:` — 컴파일 red.* `BoardResponsesTest.kt` `ExistingFieldsPreserved`(`:423` 부근)에 3건.
기존 4필드 유지 · `from(board, true/false)` 가 인자를 그대로 싣는다 · **기본값이 없다**.

> 🔧 **정정 (구현 중 검증자가 잡음).** 이 줄은 원래 「기본값이 `false`(fail-closed)」였다 —
> 리뷰 이슈 2(2A)가 **기본값 제거**로 뒤집기 **전**의 초안 잔재이고, 같은 문서의 GREEN 명세와
> **정면으로 모순**이었다. GREEN 쪽이 맞다.
> 구현은 이 자리를 `kotlin-reflect` 반사 테스트로 채웠다 —
> `from` 의 `canDelete` 파라미터가 `isOptional == false` 임을 단언한다(신규 의존성 0).
> 「누가 나중에 `= false` 를 붙이면 호출부 누락이 침묵한다」는 이 task 최대 위험을
> **사람 눈이 아니라 기계가** 지키게 만든 것이라, 명세 이탈이 아니라 명세가 요구한 보증의 집행 수단이다.

**GREEN** — 커밋 ③ `feat:`.
- `BoardResponses.kt:161-176` — `val canDelete: Boolean` + KDoc.
  `companion fun from(board: Board, canDelete: Boolean)` — 🛑 **기본값을 주지 않는다**
  (리뷰 이슈 2 · 2A). `boardType` KDoc(`:158-159`)이 「이 필드가 없으면 종류를 알 방법이 없다」를
  적어 둔 것과 같은 층에 `canDelete` 설명을 넣는다.
- `BoardController.kt:184-195` — BROWSE 게이트(`:190`) **직후**
  `val canDelete = permissionResolver.hasPermission(actor, IssuePermission.SOFT_DELETE, IssueScope.Project(projectKey))`,
  이어서 `:192` 의 `.map(BoardSummaryResponse::from)` → `.map { BoardSummaryResponse.from(it, canDelete) }`.
  판정 줄 **바로 위에 주석**(리뷰 이슈 1 · 1A):
  ```
  // 프로젝트 스코프 근사(편차 X3)라 판정 1회로 N건을 덮는다.
  // per-board 관리자가 도입되면 이 줄과 LIST-6 을 반드시 함께 고쳐야 한다 —
  // 안 그러면 전 보드가 첫 보드의 답을 받는다.
  ```
- **보드 0건이어도 무조건 판정한다** (스펙 FR-4 · E1). 비면 건너뛰게 하면 `permissionGate.calls` 가
  데이터 의존이 되어 테스트가 취약해진다. **LIST-7 이 이것을 기계로 박는다.**

> ✅ **기본값을 없애면 컴파일러가 잡는다** (리뷰 이슈 2 · 2A 채택).
> 초안은 `canDelete: Boolean = false` 였고, 그러면 `.map(BoardSummaryResponse::from)` 을
> **고치지 않아도 그대로 컴파일돼** 응답이 전량 `false` 인데 컴파일러도 detekt 도 침묵한다.
> 기본값을 빼면 그 호출부가 **재정의 불가로 컴파일 에러**가 되어, 「`feat:` 후 LIST-4 초록을
> 눈으로 확인할 것」이라는 사람 의존 절차 자체가 사라진다.
> 인용한 선례 `BoardDetailResponse.of(… canDelete: Boolean = false)` 의 기본값은 fail-closed
> 안전장치가 아니라 **테스트 호출부 10곳 이상이 인자를 생략**해서 있는 것이다
> (`BoardResponsesTest` · `BoardColumnStatesResponseTest` 전수 확인). `BoardSummaryResponse::from` 은
> **production 호출부가 `BoardController.kt:192` 단 하나**라 그 동기가 없다.

**REFACTOR**: `@return` KDoc 갱신 + **클래스 KDoc 색인**(`BoardControllerIntegrationTest.kt:91-93`)에
LIST-4/5/6 추가. 이 저장소는 테스트 목록을 클래스 KDoc 에 둔다.

**검증**: `(cd backend && ./gradlew :modules:agile-planning:test ktlintCheck detekt --console=plain)`

★ **`backend/gradlew :modules:…` 형태로 쓰면 안 된다** (구현 중 실측으로 잡혔다). worktree 루트에서
그렇게 부르면 Gradle 이 **cwd 에서** settings 파일을 찾으므로
`Directory '…/board-summary-can-delete' does not contain a Gradle build.` 로 **작업이 시작조차 안 되고**
`EXIT=1` 이 난다. `select-test-scope.ts` 가 렌더하는 형태도 `(cd backend && ./gradlew …)` 다.

### Task 2. 보드 e2e 셀렉터를 컨테이너로 좁힌다 (R10)

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/src/routes/projects.$projectKey.board.tsx`, `apps/web/e2e/fixtures/board-helpers.ts`, `apps/web/e2e/board-manage.spec.ts`, `apps/web/e2e/scrum-board.spec.ts`]
- depends-on: []

**RED** — 🛑 **리팩터 트랙이라 red-first 가 성립하지 않는다.** 지금은 중복이 없어 unscoped 셀렉터도
초록이다. red 는 **「일부러 끊기」**로 산다 (아래 REFACTOR 절). 이 예외 사유를 여기 남기는 것은
`spec-compliance-verifier` 가 `git log` 에서 `test:` → `feat:` 순서를 대조하기 때문이다 —
이 task 는 그 패턴에 해당하지 않는다.

**동반 테스트 (RED 대체)** — 컨테이너 실재 단언 **2줄 신설**.
`board-manage.spec.ts` 진입 직후와 `scrum-board.spec.ts` S2(`:191` 옆)에
`await expect(boardHeader(page)).toBeVisible()`.

> ★ 이게 없으면 testid 오타 시 `getByTestId(...).getByRole(...)` 이 **count 0** 이 되고,
> `board-manage.spec.ts:183` 의 `toHaveCount(0)` 은 **그대로 통과한다**(실측 확인함, 스펙 E6).
> 스코프가 조용히 죽는 자리를 이 2줄이 막는다. 🔧 **단 「유일한」은 아니다**(코드리뷰 지적 4) —
> 두 spec 모두 부재 단언보다 **앞에** 스코프된 존재 단언(`toContainText`)이 있어 오타는 그쪽에서
> 먼저 red 다. 이 2줄의 실익은 실패 메시지 가독성과 **양성 단언이 앞서지 않는 경로가 생겼을 때의
> 보험**이다.

**GREEN**.
- `projects.$projectKey.board.tsx` — 보드 헤더 행에 `data-testid="board-header"`.
  **이 PR 의 유일한 prod UI diff 다.** 접근성 트리 무변경 · 픽셀 무변경.
- `e2e/fixtures/board-helpers.ts`(기존 파일) — 헬퍼 3종 신설.
  ```ts
  boardHeader(page)          = page.getByTestId('board-header')
  boardActionsTrigger(page)  = boardHeader(page).getByRole('button', { name: /보드 관리/ })
  boardSwitcherTrigger(page) = boardHeader(page).getByRole('button', { name: /보드 선택/ })
  ```
  그 파일은 이미 `boardLabels` 를 import 하고 `src/api/boards.ts` 는 **일부러 피한다**
  (`import.meta.env` 가 Playwright 런타임에서 죽는다, `:9-13` 에 사유 기재) — import 그래프 위험 0.
- `board-manage.spec.ts:54-61` · `scrum-board.spec.ts:96-103` — **로컬 헬퍼 4개 삭제 → import**.
  헬퍼가 두 파일에 복붙돼 있던 것이 애초에 R10 이 2곳인 원인이다.

> 🛑 **정규식을 유지한다.** 접근성 이름이 `보드 관리, {name}` · `보드 선택, 현재 {name}` 이고
> `board-manage.spec.ts` S3 가 **테스트 중간에 이름을 바꾼다**. `exact` 로 굳히거나
> `triggerAriaLabel(name)` 을 직접 부르면 rename 단계에서 죽는다 (스펙 E7).

**REFACTOR** — **일부러 끊기 1회.** `data-testid` 값을 오타로 바꿔 두 spec 이 red 인지 보고 원복.
함정 「표면을 없애면 판별자도 사라진다」의 처방이고, 이 task 의 red 관측 지점이다.

**검증**: `(cd apps/web && node_modules/.bin/playwright test board-manage.spec.ts scrum-board.spec.ts)`

★ **명령을 이 줄에 인라인으로 둔다.** `select-test-scope.ts:191` 의 추출 정규식이
`**검증**:` **같은 줄**의 뒤쪽만 읽는다. 다음 줄부터 산문으로 쓰면 **0건으로 추출되고**,
`bts-impl` Step 1 이 그것을 「검증 칸이 비었다」로 보아 **BLOCKED** 를 낸다.
`pnpm` 은 worktree 에서 죽으므로 바이너리를 직접 부르고, `apps/web` 을 cwd 로 둔다.

- 🛑 **worktree 밖(main 체크아웃)에서 돌린다.** 판정 전
  `grep -c 'outside of Vite serving allow list' <log>` 가 0 이 아니면 그 실행은 무효다.
- 눈확인: 불필요 — `data-testid` 는 렌더에 영향이 없다. 시각 회귀 0.

### Task 3. `boardSummarySchema` + MSW 목록 조립부에 `canDelete`

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/api/boards.ts`, `apps/web/src/api/boards.test.ts`, `apps/web/src/mocks/board-handlers.ts`, `apps/web/src/mocks/board-handlers.test.ts`]
- depends-on: []
- jira: [J5]

> **왜 `depends-on: []` 인가.** Kotlin 과 TypeScript 사이에 **코드 의존이 없다** — MSW 가 픽스처를
> 공급하므로 Task 1 없이도 프론트 테스트가 선다. 계약 정합은 Task 4 가 기계로 잰다.

**RED** — 커밋 ④ `test:`.
- `boards.test.ts` — T-BD-20 블록 뒤(`:1151`)에 **T-BD-21** 신설. `true`/`false` 파싱 ·
  **없어도 파싱 성공**하고 `?? false` 로 읽힌다 · boolean 아니면 거부.
  **T-BD-20b**(`boardType` 은 없으면 **거부**) 바로 옆에 놓아 **두 필드의 필수성이 왜 다른지가
  한 화면에 보이게** 한다.
- `board-handlers.test.ts:343` 옆 — **3분기 전량**.
  ① `fetchBoards` 결과 `canDelete === true`
  ② `seedBoard({...DEFAULT_BOARD, canDelete: false})` 면 `false`
  ③ **store 에 `canDelete` 미정의면 `true`** (`?? true` 분기 — 리뷰 이슈 3 · 3A 로 추가)
  ★ **②가 필수다.** `true` 만 재면 핸들러가 **상수 `true`** 를 박아도 통과한다.
  ★ **③도 필수다.** 스펙 E5 가 「상세 조립부(`toResponseDetail:312`)와 **같은 기본값**」을
  요구하는데, 그것을 재는 테스트가 초안에 없었다. 두 조립부의 기본값이 갈리는 것이 바로
  `board-handlers.ts:389-390` 이 주석으로 경고한 사고의 모양이다.
  `board-fixtures.ts:76` 이 이미 `canDelete?: boolean` 이라 세 분기를 실제로 가를 수 있다.
- `board-handlers.test.ts:54` 로컬 `interface BoardSummary` 에 `canDelete?: boolean` (타입만, red 아님).

**GREEN** — 커밋 ⑤ `feat:`.
- `api/boards.ts:31-44` — `canDelete: z.boolean().optional()` + KDoc.
  KDoc 에 **`boardType` 과 필수성이 갈리는 이유**를 못박는다 (스펙 C-2) — `boardType` 은 없으면
  그 자체가 결함이고 기본값으로 때우면 스크럼이 칸반으로 오인되지만, `canDelete` 는 없으면
  **「삭제 못 함」이 옳은 해석**이라 기본값이 fail-closed 와 일치한다.
- `api/boards.ts:206` · `boards.test.ts:866` — 「목록에는 없고 단건만」 주석 정정 →
  「양쪽. 소비처는 PR ⑨ 사이드바 보드 `⋯`」.
- `mocks/board-handlers.ts:379-395` — `canDelete: canDelete ?? true`
  (`toResponseDetail:312` 와 **같은 기본값**, 스펙 E5).

**REFACTOR** — 목록 조립을 `toResponseSummary(stored)` 로 뽑아 `toResponseDetail` **바로 옆**에 둔다.
`:389-390` 의 「다른 조립부라 상세만 고치면 스위처가 죽는다」 경고가 「같은 파일의 형제 함수」로
격하된다.

**검증**: `apps/web/node_modules/.bin/vitest run src/api src/mocks/board-handlers.test.ts`
· 인라인 픽스처 9곳 무변경 확인(`.optional()` 선택의 직접 이득)

### Task 4. 백엔드 DTO ↔ zod ↔ MSW 3-way 정합 판별식 (`scripts/workflow/`)

**메타**.
- agent: `qa-engineer`
- files: [`scripts/workflow/board-summary-contract.test.ts`]
- depends-on: [1, 3]

> **왜 필요한가** (스펙 C-3). `.optional()` 을 고르는 순간 **런타임 파싱이 백엔드 누락을 못 잡는다.**
> 세 목록이 서로를 모르는 상태는 이 저장소가 이미 이름 붙인 지배 결함 양식이고,
> `board-handlers.ts:387-388` 이 그 사고를 **주석으로** 이미 경고하고 있다 — 주석은 다음 필드
> 추가 때 읽히지 않는다.

> 🛑 **왜 `apps/web/src/api/__tests__/` 가 아니라 `scripts/workflow/` 인가** (리베이스 후 재설계).
> `472f05c49`(#451)가 `push-frontend-tests.ts` 를 넣어 프론트 테스트가 푸시 때 돌게 됐지만,
> 좁힘이 `vitest related` 의 **모듈 그래프**다. 이 판별식은 `BoardResponses.kt` 를 `import` 가
> 아니라 **`readFileSync` 로 읽으므로 그래프에 안 걸린다** — `BoardResponses.kt` 만 바뀐 커밋은
> `frontendScope` 가 `skip`(프론트 변경 0건)이라 **아무것도 안 돈다.**
> 그런데 그 시나리오가 바로 이 판별식이 존재하는 이유다.
> `scripts/**/*.test.ts` 는 `.husky/pre-push` **줄 2에서 조건 없이 전량** 실행되므로
> 거기 두면 어느 쪽이 바뀌든 항상 돈다.

**RED**: 신설 `scripts/workflow/board-summary-contract.test.ts`.
골격은 `apps/web/src/api/__tests__/bulk-operation-enum-parity.test.ts` 를 따르되
**import 를 쓰지 않고 세 꼭짓점 전부 텍스트로 추출**한다(`node --test` 는 `import.meta.env` 를
못 견디므로 `api/boards.ts` 를 import 할 수 없다 — `e2e/fixtures/board-helpers.ts:9-13` 이 같은
이유를 이미 기록해 뒀다).

| # | 출처 | 추출 대상 |
|---|---|---|
| 1 | `BoardResponses.kt` | `data class BoardSummaryResponse(` **괄호 본문**의 `val <name>:` |
| 2 | `apps/web/src/api/boards.ts` | `boardSummarySchema` 의 `z.object({ … })` 블록 키 |
| 3 | `apps/web/src/mocks/board-handlers.ts` | **`toResponseSummary` 함수 본문**의 반환 객체 리터럴 키 (`:338` 부터) |

> 🔧 **추출 대상 정정 (Task 3 REFACTOR 이후 · 검증자가 잡음).**
> 이 표는 원래 「`getBoardsHandler` 의 `.map(…) => ({ … })` 객체 리터럴(`:389-394`)」을 가리켰다.
> Task 3 의 REFACTOR 가 plan 지시대로 목록 조립을 `toResponseSummary(stored)` 로 뽑아
> `toResponseDetail` 옆에 옮겼고, 그 결과 **`getBoardsHandler` 안에는 객체 리터럴이 남지 않았다**
> (`:415` 는 `.map(toResponseSummary)` 뿐이다). 옛 위치를 파싱하면 빈 집합이 나온다.
> ★ 다만 이것이 **조용히 깨지지는 않는다** — 비-공허 카나리(세 집합이 `canDelete` 와 `boardType` 을
> 실제로 포함한다)가 빈 집합을 즉시 red 로 잡는다. 카나리를 빼면 이 정정이 없을 때 판별식이
> 「셋 다 비었으니 차집합 0」으로 **가짜 초록**이 된다 — 그 자리가 카나리의 존재 이유다.

**세 집합의 양방향 차집합이 0** 이어야 한다.

★ **꼭짓점 1 — 괄호 밖을 읽으면 안 된다.** 바로 위 KDoc 이 `@property boardId …` 로 필드명을
나열한다. 원형이 명시적으로 경고하는 실패 양식이고, 빠지면 판별식이 「원래 시끄러운 것」으로
학습된다.

**비-공허 5종.**
- `existsSync` **세 파일 전부** — 경로가 틀리면 빈 집합끼리 비교해 조용히 통과한다
- 세 집합 각각 크기 `>= 5`
- **카나리** — 세 집합 전부가 `canDelete` **와** `boardType` 을 실제로 포함한다
  (파서가 KDoc·주석이 아니라 본문을 읽는다는 증거)
- ★ **MSW 블록 모양 단언** — `:389-394` 의 객체 리터럴에 **spread(`...`)가 없다.**
  지금은 명시적 리터럴이라 텍스트 추출이 건전하지만, 누가 `...stored` 로 바꾸면 추출이
  **조용히 틀려진다.** 그 순간 red 가 나게 모양 자체를 단언한다
  (메모리 `[[self-reading-guard-needs-helper-level-tests]]` 의 처방).
- **헬퍼 계약 픽스처** — 세 추출 함수를 픽스처 문자열로 직접 재는 `describe` 를 따로 둔다.
  파일이 어떻게 생겼든 성립하고, 결함 픽스처가 그 자체로 회귀 판정이 된다.

**GREEN**: Task 1·3 이 이미 셋을 맞춰 놨으므로 판별식은 작성 즉시 초록이어야 한다.
초록이 아니면 **판별식이 아니라 앞 task 가 틀린 것**이다.

**REFACTOR** — **뮤테이션 3종을 각각 관측한다.** 🛑 **GREEN 선커밋 뒤에.** 미커밋 원복은 소실이다.
- `BoardResponses.kt` 의 `canDelete` 한 줄 삭제 → red → 원복 ← **A안이 새로 사는 축**
- zod 의 `canDelete` 한 줄 삭제 → red → 원복
- MSW 조립부의 `canDelete` 한 줄 삭제 → red → 원복

**세 red 를 각각 봐야** 「셋 중 둘만 보는 반쪽 판별식」이 아님이 선다. 메모리
`[[mutation-passing-is-evidence-only-if-it-crosses-the-defect]]` — 뮤테이션이 결함 지점을
실제로 지나는지 먼저 물을 것.

**범위 한정** — `BoardSummaryResponse` 삼각형만 건다. `BoardDetailResponse`(필드 10+ ·
`JsonNullable` · 중첩 DTO · #444 로 `states`·`unmappedStates` 추가)까지 넓히면 파서가 무거워지고
PR ⑧ 의 신호가 묻힌다. **넓히지 않은 이유를 파일 상단 미커버 선언으로 남기고** TODOS 후보로 올린다.

**런타임 축은 포기한 것이 아니다.** 「핸들러를 실제로 태워 응답 키를 본다」는 증명은 이 판별식이
안 한다. 대신 `canDelete` 의 런타임 경로는 **Task 3 의 `board-handlers.test.ts` 3분기**가 덮고,
그것은 zod·MSW 어느 쪽이 바뀌든 `vitest related` 로 딸려 온다.

**검증**: `node --experimental-strip-types --test scripts/workflow/board-summary-contract.test.ts`

### Task 5. 문서 동기화 — 기각 뒤집기 + 즉사 계약 1행

**메타**.
- agent: 컨트롤러 인라인 (`/bts` 가 직접 편집)
- files: [`docs/plans/2026-08-31-board-crud-recovery.md`, `docs/design/jira-parity-contract.md`, `docs/INDEX.md`, `docs/INDEX-fr.md`, `docs/INDEX-recent.md`]
- depends-on: [1, 2]

**작업**.
1. `2026-08-31-board-crud-recovery.md` NOT-in-scope 표의 `BoardSummaryResponse 에 canDelete` 행 —
   **지우지 말고 그 자리에서 뒤집는다.** 사유 칸에 취소선 + 「2026-09-04 뒤집음 (캠페인 PR ⑧)」 +
   소비처·N+1 부재·도메인 제약 0건 근거. 지우면 「왜 미뤘는지」와 「왜 되돌렸는지」가 함께 사라진다.
2. `jira-parity-contract.md:79` 「깨면 즉사하는 계약」 표에 **1행 추가**. PR ⑨ 작성자가 착수 전에
   읽는 바로 그 문서이고, 스코프화가 살아남는 메커니즘은 여기 한 줄이 있느냐다.
   계약 「보드 헤더 `⋯` 는 컨테이너 스코프로만 잡는다」 · 실측 명령
   `grep -rn "보드 관리" apps/web/e2e/` + `grep -rn "board-header" apps/web/`.
   ★ **개수 리터럴 금지** — 그 문서 `:76-77` 자체 규칙이다. 「2곳」이라고 적지 말 것.
3. ~~**`TODOS.md` 에 `push-frontend-tests` 등재**~~ — **불필요해졌다.**
   리뷰(base `7d13cdb3d`)가 이 항목을 만들었으나, 리베이스로 들어온 `472f05c49`(#451)가
   `scripts/workflow/push-frontend-tests.ts` 를 신설하고 `.husky/pre-push:74` 에 배선했다.
   `frontend-ci.yml` 의 stale 주석도 같은 커밋이 정리했다. **등재할 부채가 남지 않았다.**
   → 그 발견이 남긴 실질은 **Task 4 의 배치 변경**으로 흡수됐다(판별식을 `scripts/workflow/` 로).
   `TODOS.md` 는 이 PR 에서 손대지 않는다.
4. `node scripts/build-doc-index.mjs` 후 `--check`.

**검증**: `node scripts/build-doc-index.mjs --check`
· `node --experimental-strip-types --test scripts/**/*.test.ts` (TODOS 판별식 포함 — pre-push 가 무조건 돌린다)
· `bash scripts/verify-master-plan.sh` 는 **안 걸린다**(검증 완료) — 그 스크립트는 `docs/plan/`
**단수**의 `product/`·`fr-index.md`·`README.md` 만 본다. 이번에 손대는 것은 `docs/plans/` **복수**다.
FR 추가·삭제·카운트 변경이 0 이라 룰 E·H 도 무관하다.

## Plan 메타

- **task 수**: 5 · **예상 wave**: 3
  - wave 1 — Task 1(`backend-engineer`) · Task 2(`qa-engineer`) · Task 3(`frontend-engineer`) 병렬.
    셋의 `files` 교집합 0.
  - wave 2 — Task 4 (`qa-engineer` · `depends-on: [1, 3]`)
  - wave 3 — Task 5 (`depends-on: [1, 2]`)
- **착수 좌표 갱신** — 이 계획은 base `7d13cdb3d` 에서 썼고 **`c506156a7` 로 리베이스**했다.
  들어온 것 둘. `472f05c49`(#451 하네스 20건) · `c506156a7`(#446 issue-tracking V039).
  ⇒ **Task 4 의 배치가 바뀌었다**(`apps/web/src/api/__tests__/` → `scripts/workflow/`, 사유는 Task 4 본문).
  ⇒ Task 5 의 `TODOS.md` 등재는 **불필요해졌다**(#451 이 그 부채를 이미 갚았다).
  ⇒ **캠페인 위험 R11 이 현실이 됐다** — #446 이 `V039__description_html.sql` 을 가져갔으므로
  캠페인 PR ⑥ 의 `V039__project_nav_tabs.sql` 은 **재번호가 필요하다.** PR ⑧ 범위 밖이지만 기록해 둔다.
  ⇒ 백엔드 줄 번호는 `agile-planning` 이 두 커밋에 안 걸려 **그대로 유효**하다.
- **구현 규율**: TDD red-first. **단 Task 2 는 예외**다 — 리팩터 트랙이라 지금은 중복이 없어
  unscoped 셀렉터도 초록이고, `test:` → `feat:` 커밋 순서가 성립하지 않는다.
  `spec-compliance-verifier` 가 이 예외를 BLOCKER 로 오판하지 않도록 red 관측 지점을
  **「일부러 끊기 1회」**로 대체해 task 본문에 명시했다.
- **Jira 매핑**: `J4 → Task 1` · `J5 → Task 1 · Task 3`. 채택 2건 전량 물림 · 범위 밖 0건.
- **추가 검증**:
  - 백엔드 `backend/gradlew :modules:agile-planning:test ktlintCheck detekt`
  - 프론트 `apps/web/node_modules/.bin/vitest run src/api src/mocks`
  - 타입 `apps/web/node_modules/.bin/tsc -p tsconfig.app.json --noEmit`
    🛑 **`tsconfig.json` 을 쓰면 안 된다** — `files: []` solution 파일이라 아무것도 컴파일하지 않고
    **EXIT=0** 이 난다. 그리고 `include: ["src"]` 라 **`e2e/` 를 안 덮는다**(Task 2 는 lint-staged 경로).
  - 린트 `apps/web/node_modules/.bin/eslint <바뀐 파일>`
  - 문서 `node scripts/build-doc-index.mjs --check`
  - 판별식 전량 `node --experimental-strip-types --test 'scripts/**/*.test.ts' 'scripts/**/*.test.mjs'`
    — pre-push 줄 2 가 **조건 없이** 돌린다. Task 4 를 이 glob 안에 두는 것이 A안의 요점이다
  - 프론트 안전망 `node --experimental-strip-types scripts/workflow/push-frontend-tests.ts`
    — pre-push 줄 4(신설). `vitest related` 로 좁히므로 **프론트 변경 0건이면 `skip`** 이다
- 🛑 **worktree 에서 pnpm 스크립트는 전부 죽는다**(심볼릭 `node_modules` → `ERR_PNPM_ABORTED_REMOVE_MODULES_DIR_NO_TTY`).
  위 명령은 전부 바이너리 직접 호출이다. 프론트 vitest 는 **`apps/web` 이 cwd 여야** `@/` alias 가 해석된다.
- 🛑 **e2e 는 worktree 밖(main 체크아웃)에서.** 판정 전
  `grep -c 'outside of Vite serving allow list' <log>` 가 0 이 아니면 그 실행은 무효다.
  **선재 실패 8건**(`fr-au-05-signup` · `workflow.spec` · `workflow-scheme-assignment`)은 이 작업과
  무관하므로 로그에는 남기되 보고에서 분리한다.

## 리뷰 결과

**렌즈**: `/plan-eng-review` **1종**. `type == "api"` 행이 정하는 값이다
(`bts-review-plan` Step 2 표). `/bts` 티어표의 「독립 리뷰 2종」은 체인 [6] `bts-codereview` 의
값이지 이 단계의 값이 아니다.
**보안 렌즈**: 이 단계 표에 없다. 권한 노출 여부는 아래 §Architecture 에서 판정했다.

**판정 — 통과 (BLOCKER 0 · 이슈 4건 전량 계획에 반영).**

### Step 0. 범위 도전

복잡도 체크가 **걸렸다**(18 파일 경로 > 8). Maxi 판정 = **그대로 진행**.
근거 — 18건 중 production 5(`BoardResponses.kt` · `BoardController.kt` · `api/boards.ts` ·
`mocks/board-handlers.ts` · `board.tsx` 의 `data-testid` 한 줄) · 테스트 7 · 신규 판별식 1 ·
문서 2 + **자동 생성 인덱스 3**. 새 클래스·서비스 0 · 새 추상화 0 · 마이그레이션 0.
파일 수가 부푼 것은 숫자지 설계가 아니다.

### 1. Architecture — 이슈 1건 · 무이슈 판정 1건

**무이슈 — 권한 파생값의 목록 노출은 새 정보를 0비트 흘린다** (confidence 9/10).
`BoardController.kt:190` 의 `requirePermission(actor, BROWSE, Project(projectKey))` 가 응답 전체를
막고, 같은 비트를 `:168-171` 의 단건 조회가 **이미** 같은 프로젝트에 대해 내준다. 호출자는
`getBoard` 한 번으로 이미 알 수 있다. 값은 **요청자 자신의** 권한이라 타인 정보도 아니다.
계획의 판단이 맞다.

**이슈 1 — X10 최적화의 파수꾼이 자기가 뭘 지키는지 말하지 않는다** (confidence 8/10) → **1A 채택**.
`BoardController.kt:192` 의 판정 1회는 X10(프로젝트 스코프 근사)에 **의존한다**. 나중에 per-board
관리자가 도입되면 LIST-6(정확히 2건)이 red 를 내긴 하지만, 그 red 가 말하는 것은 「기대값이
틀렸다」뿐이다. 수정자가 기대 목록을 `[BROWSE, SOFT_DELETE × N]` 으로 고쳐버리면 **전 보드가 첫
보드의 답을 받는 채로 초록**이 된다.
⇒ 판정 줄과 LIST-6 단언 **양쪽에** 사유 주석을 박는다(위 Task 1 본문에 반영).

### 2. Code Quality — 이슈 1건

**이슈 2 — `from(board, canDelete = false)` 의 기본값이 호출부 누락을 숨긴다**
(confidence 9/10) → **2A 채택**.
계획이 스스로 🛑 로 「컴파일러도 detekt 도 침묵하고 LIST-4 만이 잡는다」고 적은 자리다.
**기본값을 없애면 그 자리가 컴파일 에러가 된다** — 사람이 눈으로 확인하는 절차 자체가 사라진다.
인용된 선례 `BoardDetailResponse.of(… canDelete: Boolean = false)` 의 기본값은 fail-closed
안전장치가 아니라 **테스트 호출부 10곳 이상이 인자를 생략**해서 있는 것이고
(`BoardResponsesTest` · `BoardColumnStatesResponseTest` 전수 확인),
`BoardSummaryResponse::from` 은 **production 호출부가 `BoardController.kt:192` 하나뿐**이라
그 동기가 없다.
⇒ 기본값 제거(위 Task 1 GREEN 에 반영).

### 3. Test — 커버리지 다이어그램 + GAP 2건

```
CODE PATHS                                          USER FLOWS
[+] BoardController.listBoards                      [+] 보드 목록 조회
  ├── BROWSE 게이트                                    ├── [★★★] SOFT_DELETE 보유 → 전항목 true — LIST-4
  │   ├── [★★  기존] 통과 — LIST-1 :590                ├── [★★★] 미보유 → 전항목 false — LIST-5
  │   └── [★★  기존] 미충족 403 — LIST-2 :610          └── [★★  기존] BROWSE 없음 → 403 — LIST-2
  ├── SOFT_DELETE 판정 (신규)
  │   ├── [★★★] true/false — LIST-4·5                [+] 보드 헤더 ⋯ (e2e)
  │   ├── [★★★] 정확히 1회 — LIST-6                     ├── [★★  기존] 삭제 흐름 — board-manage.spec
  │   └── [GAP→닫음] 0건일 때도 1회 — LIST-7 신설        └── [★★★] 컨테이너 실재 — 신설 2줄
  └── BoardSummaryResponse.from
      └── [★★★] 필드 보존 + 인자 전달 — BoardResponsesTest

[+] boardSummarySchema (zod)                        [+] 3-way 정합
  ├── [★★★] true/false/누락/비-boolean — T-BD-21       ├── [★★★] 양방향 차집합 0 — Task 4
  └── [★★★] boardType 필수성 대조 — T-BD-20b 옆         └── [★★★] 뮤테이션 2종 각각 — Task 4

[+] MSW 목록 조립부
  ├── [★★★] store true → true
  ├── [★★★] store false → false (상수 박기 차단)
  └── [GAP→닫음] store 미정의 → ?? true (E5) — 3분기로 확장

COVERAGE (반영 후): 15/15  |  GAP 0
```

**이슈 3 — 스펙이 명시한 동작 2건을 아무 테스트도 안 잰다** (confidence 9/10) → **3A 채택**.
① FR-4·E1(보드 0건이어도 판정 1회) — 기존 목록 테스트는 LIST-1/2/3 뿐이고 **0건 케이스가 전무**하다
(`BoardControllerIntegrationTest.kt:91-93` 색인 확인). **LIST-7 신설**.
② E5(MSW store 미정의 → 상세와 같은 기본값 `true`) — 초안은 true/false 2분기만 쟀다. **3분기로 확장**.
둘 다 「문서가 약속하고 판별식은 안 보는」 양식이다.

### 4. Performance — 이슈 0건

추가되는 것은 프로젝트 스코프 권한 조회 **1회**이고 보드 수와 무관하다(LIST-6 이 고정).
`findAllByProjectKey` 무변경 · N+1 없음 · 프론트 쿼리는 이미 `staleTime 30초`.

### 4b. 게이트 실효성 — 이슈 4 (BLOCKER 후보였다)

**이슈 4 — 3-way 판별식이 자동 게이트 어디서도 안 돈다** (confidence 10/10) → **4A 채택**.
스펙 C-3 이 「`.optional()` 의 구멍을 판별식이 메운다」고 적었는데, 실측하면 그 판별식은
**어떤 훅에서도 실행되지 않는다.**

| 게이트 | apps/web 에 대해 실제로 하는 일 |
|---|---|
| `.lintstagedrc` (pre-commit) | `apps/web/**/*.{ts,tsx,js,jsx}` 에 **eslint 만** |
| `.husky/pre-push:47` | `node --test 'scripts/**/*.test.ts' 'scripts/**/*.test.mjs'` — **apps/web 제외** |
| `.github/workflows/frontend-ci.yml` | *「★2026-08-21 — 자동 실행을 껐다. `workflow_dispatch` 로만 돈다」* |

원형 `bulk-operation-enum-parity.test.ts` 도 같은 처지다. 게다가 `frontend-ci.yml` 주석은
*「대신 어디서 보는가. 커밋 전 = `.husky/pre-commit`」* 이라 적어 **stale** 이다.
⇒ (리뷰 시점 판정) 판별식을 넣되 「사람이 손으로 돌려야 값을 한다」를 사실대로 적고,
자동화는 별건 T2 PR + TODOS 로 분리한다.

> **🔄 리베이스 후 갱신 (2026-09-04 · base `7d13cdb3d` → `c506156a7`).**
> 위 판정을 **지우지 않고 그대로 둔다** — 그때의 관측이 맞았기 때문이다. 그 뒤 무엇이 바뀌었는지만 잇는다.
>
> **`472f05c49`(#451)가 같은 결함을 독립적으로 진단해 고쳤다.** `push-frontend-tests.ts` 가
> 신설돼 `.husky/pre-push:74` 에 무조건 배선됐고, `frontend-ci.yml` 의 stale 주석도 정리됐다.
> 그 커밋 메시지가 같은 수치를 적는다 — *「프론트 테스트 9,755개(640파일)가 커밋·푸시 어느
> 훅에서도 안 돌고 있었다」*. ⇒ **TODOS 등재는 불필요해졌다**(Task 5 에서 취소선 처리).
>
> **그러나 이 판별식에 한해서는 아직 안 덮인다.** 좁힘이 `vitest related` 의 모듈 그래프인데
> 이 판별식은 `.kt` 를 `readFileSync` 로 읽어 그래프 밖이고, `frontendScope` 는 프론트 변경이
> 0건이면 `skip` 이다. **백엔드 DTO 만 바뀐 커밋 = 아무것도 안 돈다** — 그런데 그 시나리오가
> 정확히 이 판별식이 존재하는 이유다.
> ⇒ **처방을 별건 PR 이 아니라 Task 4 안에서 닫는다** — 판별식을 `scripts/workflow/` 로 옮겨
> pre-push 줄 2(조건 없는 전량)에 태운다. Maxi 판정 = A안.
> 미루면 이 판별식은 **만들어진 날부터 반쪽인 채로 굳는다.**

### 컨트롤러가 지목한 5문항 답

| # | 질문 | 답 |
|---|---|---|
| 1 | 권한 파생값 목록 노출의 위험 | **계획이 맞다.** 새 정보 0비트 — 단건 조회가 같은 비트를 이미 내주고, BROWSE 가 응답 전체를 막는다 |
| 2 | X10 최적화가 미래에 조용히 틀려지나 | **그럴 수 있었다.** LIST-6 이 red 를 내긴 하나 「기대값 갱신」으로 읽힌다 → 이슈 1(1A)로 사유 주석을 양쪽에 박아 닫았다 |
| 3 | `.optional()` + pre-push 만으로 충분한가 | **전제가 틀렸다.** 판별식은 pre-push 에서도 **안 돈다** → 이슈 4(4A). 계획의 주장을 사실로 정정하고 자동화를 별건 PR + TODOS 로 뺐다 |
| 4 | Task 2 의 TDD 예외가 구멍인가 | **구멍 아니다.** ① 예외 사유가 plan 에 명시돼 검증자가 오판하지 않는다 ② 컨테이너 실재 단언 2줄이 침묵사를 실제로 막는다 — `board-manage.spec.ts:183` 의 `toHaveCount(0)` 이 count 0 을 통과시키는 자리를 정확히 겨눈다 ③ 「일부러 끊기 1회」가 red 관측을 산다 |
| 5 | 소비처 없는 필드를 먼저 내는 것이 낙관인가 | **낙관 아니다.** 순서가 선호가 아니라 **필연**이다 — PR ⑨ 가 목록 행마다 `⋯` 를 렌더하려면 목록이 판단 근거를 가져야 한다. 지금 안 넣으면 ⑨ 는 보드마다 `useBoard()` 를 부르는 N+1 을 안고 태어난다. 그 사이 기간 위험은 0 에 수렴한다(필드 추가뿐, 읽는 사람이 없다). S4 를 「PR ⑨ 범위」로 명시해 고아 판별이 가능하게 해 뒀다 |

### NOT in scope

| 항목 | 사유 |
|---|---|
| ~~`push-frontend-tests.ts` (pre-push 프론트 단계)~~ | **범위 밖이 아니라 이미 끝났다** — `472f05c49`(#451)가 리베이스로 들어오며 신설·배선했다 |
| ~~`frontend-ci.yml` 의 stale 주석 수정~~ | **같은 커밋이 정리했다** |
| 캠페인 PR ⑥ 의 마이그레이션 재번호 | 위험 R11 이 현실이 됐다 — #446 이 `V039` 를 가져갔다. **PR ⑥ 착수 시** 처리한다 |
| `BoardDetailResponse` 까지 판별식 확장 | 필드 10+ · `JsonNullable` · 중첩 DTO · #444 로 `states`/`unmappedStates` 추가. 파서가 무거워지고 ⑧ 신호가 묻힌다 |
| `BoardDetailResponse.of` 의 기본값 제거 | 테스트 호출부 10+ 가 diff 에 들어와 「모든 변경 줄은 요청으로 추적」을 깬다 |
| per-board 관리자 도입 | `created_by` 마이그레이션 + shared-kernel 포트 = **T3 승격**. 선행 문서 X3 가 이미 이연으로 적었다 |
| 사이드바 보드 목록 · 행별 `⋯` | **PR ⑨** 범위. 이 PR 은 그 선행 조건만 놓는다 |
| `bc:*` · `type:*` PR 라벨 | `DEVELOPMENT.md` §4 가 규정했으나 저장소에 라벨이 하나도 없다(기본 GitHub 9개뿐). 별건 |

### What already exists — 재사용 확인

| 이미 있는 것 | 이 계획이 하는 일 |
|---|---|
| `BoardController.kt:168-171` 의 `canDelete` 계산 | **그대로 재사용**. 같은 술어를 목록에도 적용할 뿐 — 두 응답이 갈리지 않는 것이 요점이다. 새 권한 경로 0 |
| `IssuePermissionResolver` (shared-kernel 포트) | 이미 `BoardController` 에 주입돼 있다(`:90`). 신규 배선 0 |
| `PermissionGate` 테스트 하네스 (`:177`) | `allowAll` + `denied` + `calls` 를 그대로 쓴다. 신규 모킹 0 |
| `e2e/fixtures/board-helpers.ts` | 기존 파일에 헬퍼를 **모은다**. 새 픽스처 파일 0 — 두 spec 의 복붙을 없애는 것이 R10 이 2곳인 원인을 제거한다 |
| `bulk-operation-enum-parity.test.ts` | 판별식 골격을 **복제**한다. 새 패턴 발명 0 |
| `board-fixtures.ts:76` 의 `canDelete?: boolean` | store 타입이 **이미** 필드를 갖는다. 픽스처 스키마 변경 0 |

**불필요한 재구축 0건.** 이 계획은 새로 만드는 것보다 이어 붙이는 것이 많다.

### Failure modes

| 신규 경로 | 현실적 실패 | 테스트 | 에러 처리 | 사용자가 보는 것 |
|---|---|---|---|---|
| `listBoards` 의 SOFT_DELETE 판정 | 권한 서비스 예외 | ✅ 기존 403 경로 | ✅ `BoardExceptionHandler` | 명시적 403 |
| `from(board, canDelete)` 호출부 | 인자 누락 | — | ✅ **컴파일 에러**(2A) | 배포 자체가 안 된다 |
| zod `.optional()` | 백엔드가 필드 누락 | ✅ Task 4 판별식이 **pre-push 무조건** 잡는다(A안) | ✅ `=== true` fail-closed | 삭제 UI 미노출 — **조용하지만 안전한 쪽** |
| MSW 목록 조립부 | 상세와 기본값이 갈림 | ✅ 3분기(3A) | — | 개발 환경 한정 |
| `data-testid="board-header"` | 오타·삭제 | ✅ 컨테이너 실재 단언 2줄 | — | e2e red |
| X10 근사 | per-board 관리자 도입 후 첫 보드 답이 전파 | ✅ LIST-6 + 사유 주석(1A) | — | 잘못된 삭제 버튼 노출 |

**critical gap 0건** — 「테스트도 없고 에러 처리도 없고 조용한」 실패는 없다.
`.optional()` 경로가 유일하게 「조용」하지만 그 조용함이 **fail-closed 방향**이고 에러 처리가 있다.

### Worktree parallelization

| Step | 모듈 | 의존 |
|---|---|---|
| Task 1 | `backend/modules/agile-planning/` | — |
| Task 2 | `apps/web/src/routes/` · `apps/web/e2e/` | — |
| Task 3 | `apps/web/src/api/` · `apps/web/src/mocks/` | — |
| Task 4 | `apps/web/src/api/__tests__/` | 1, 3 |
| Task 5 | `docs/` · `TODOS.md` | 1, 2 |

```
Lane A: Task 1 (backend/ 단독)
Lane B: Task 2 (routes/ + e2e/)
Lane C: Task 3 → Task 4 (순차 — api/ 공유)
Lane D: Task 5 (docs/ — A·B 뒤)

실행: A + B + C 를 병렬로. C 안에서만 순차. 셋이 끝나면 D.
```

⚠️ **Lane B 와 Lane C 는 둘 다 `apps/web/` 아래다.** 디렉터리는 갈리지만
(`routes/`+`e2e/` vs `api/`+`mocks/`) 같은 `tsconfig`·`eslint` 캐시를 공유하므로
동시 실행 시 lint 결과가 섞일 수 있다. worktree 를 나눈다면 각자 검증을 완전히 마친 뒤 머지할 것.

### 미해결

없다. 이슈 4건 전부 Maxi 가 판정했고 계획에 반영됐다.

## Implementation Tasks

리뷰 findings 에서 나온 것만. Task 1~5 본문에 이미 반영돼 있고, 아래는 그 반영분의 체크리스트다.

- [ ] **T1 (P1, human: ~5분 / CC: ~2분)** — `agile-planning/web/dto` — `BoardSummaryResponse.from` 의 `canDelete` 기본값을 없앤다
  - Surfaced by: Code Quality 이슈 2 — 기본값 `= false` 때문에 `.map(BoardSummaryResponse::from)` 을 안 고쳐도 컴파일된다. production 호출부는 `BoardController.kt:192` 하나뿐이라 기본값의 동기가 없다
  - Files: `backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/web/dto/BoardResponses.kt`
  - Verify: 호출부를 일부러 안 고친 채 `backend/gradlew :modules:agile-planning:compileKotlin` → **컴파일 에러**를 1회 본다
- [ ] **T2 (P2, human: ~10분 / CC: ~3분)** — `agile-planning/web` — X10 근사의 사유를 판정 줄과 LIST-6 양쪽에 박는다
  - Surfaced by: Architecture 이슈 1 — LIST-6 의 red 가 「기대값 갱신」으로 읽히면 per-board 관리자 도입 시 전 보드가 첫 보드의 답을 받은 채 초록이 된다
  - Files: `BoardController.kt` · `BoardControllerIntegrationTest.kt`
  - Verify: 주석 문구가 「함께 고쳐야 한다」와 「X10 근사가 깨졌다는 신호」를 각각 담는지 눈으로
- [ ] **T3 (P1, human: ~15분 / CC: ~3분)** — `agile-planning/test` — LIST-7 신설 (보드 0건이어도 판정 1회)
  - Surfaced by: Test 이슈 3 — 스펙 FR-4·E1 이 명시했는데 기존 목록 테스트가 LIST-1/2/3 뿐이고 0건 케이스가 전무하다
  - Files: `BoardControllerIntegrationTest.kt`
  - Verify: `backend/gradlew :modules:agile-planning:test --tests '*BoardControllerIntegrationTest*'`
- [ ] **T4 (P2, human: ~15분 / CC: ~3분)** — `apps/web/mocks` — MSW 목록 테스트를 3분기로 (store 미정의 → `?? true`)
  - Surfaced by: Test 이슈 3 — 스펙 E5 가 「상세 조립부와 같은 기본값」을 요구하는데 초안은 2분기만 쟀다
  - Files: `apps/web/src/mocks/board-handlers.test.ts`
  - Verify: `apps/web/node_modules/.bin/vitest run src/mocks/board-handlers.test.ts` (cwd = `apps/web`)
- [x] **T5 (P1, human: ~20분 / CC: ~5분)** — `docs` — C-3 정정 + Task 4 배치 이동
  - Surfaced by: 게이트 실효성 이슈 4 — apps/web 의 vitest 는 어떤 자동 게이트에서도 안 돈다. 「판별식이 구멍을 메운다」가 거짓이었다
  - **리베이스로 절반이 상류에서 해결됐다** — `472f05c49`(#451)가 `push-frontend-tests.ts` 를 넣었다. TODOS 등재는 취소
  - 남은 실질 = **Task 4 를 `scripts/workflow/` 로 옮긴다**(모듈 그래프가 `.kt` 를 못 본다). 계획에 반영 완료
  - Files: `docs/plans/2026-09-04-board-summary-can-delete.md`
  - Verify: `node scripts/build-doc-index.mjs --check`

## GSTACK REVIEW REPORT

| Review | Trigger | Why | Runs | Status | Findings |
|--------|---------|-----|------|--------|----------|
| CEO Review | `/plan-ceo-review` | Scope & strategy | 0 | — | — |
| Codex Review | `/codex review` | Independent 2nd opinion | 0 | skipped | `codex_reviews` disabled |
| Eng Review | `/plan-eng-review` | Architecture & tests (required) | 1 | clean | 4 issues, 0 critical gaps |
| Design Review | `/plan-design-review` | UI/UX gaps | 0 | — | — |
| DX Review | `/plan-devex-review` | Developer experience gaps | 0 | — | — |

**VERDICT:** ENG CLEARED — 구현 착수 가능. 이슈 4건 전량 계획에 반영됐고 BLOCKER 는 0 이다.
Design Review 는 불필요하다(prod UI diff 가 `data-testid` 한 줄, 픽셀·접근성 트리 무변경).
CEO Review 도 불필요하다(신규 FR 0 · 범위 변경 0 · 사용자 표면 변화 0).

NO UNRESOLVED DECISIONS
