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

## 도메인 정리 (← /bts-spec §1 채움)

## 스펙 (← /bts-spec §2 채움)

## Sanity Check (← /bts-spec §3 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
