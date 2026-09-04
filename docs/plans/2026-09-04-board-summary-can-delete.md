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
| **FR-2** | `canDelete` 의 값은 **단건 조회와 같은 술어**다 — `hasPermission(actor, SOFT_DELETE, Project(projectKey))`. 두 응답이 같은 사용자·같은 보드에 대해 갈리지 않는다 |
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
| **E6** | `data-testid` 오타·삭제 | 🛑 **침묵사 지점.** `getByTestId(...).getByRole(...)` 이 count 0 이 되고 `board-manage.spec.ts:183` 의 `toHaveCount(0)` 은 **그대로 통과**한다(실측 확인). 컨테이너 실재 단언 2줄이 이것만을 막는다 |
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
- **C-3 — `.optional()` 의 대가를 판별식으로 갚는다.** 런타임 파싱이 백엔드 누락을 못 잡는
  구멍이 생긴다. 백엔드 DTO ↔ 프론트 zod ↔ MSW 목록 조립부 **세 목록이 서로를 모르는** 상태이고,
  이것은 저장소가 이미 이름 붙인 지배 결함 양식이다. 3-way 차집합 판별식 + 비-공허 짝으로 닫는다.
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

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
