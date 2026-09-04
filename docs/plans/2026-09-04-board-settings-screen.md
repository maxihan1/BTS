# 보드 설정 화면 — 지라 Board settings 패리티 (부채 177)

> 티어: T3
> slug: board-settings-screen
> type: ui
> agent: frontend-engineer
> 생성: 2026-09-04

## Brief

**사용자 원문.** 「보드개선 작업 진행 남은 작업 진행하」 → 장부 잔여 6건 제시 → Maxi 가 **부채 177**
(보드 설정 화면이 통째로 없다 · T3) 선택.

**classify 결과와 그 오분류.** `type=ui` · `agent=frontend-engineer` · `primary_bc=agile-planning` 은
맞다. **`tier=T1` 은 오분류다** — Maxi 지정 T3 가 우선한다(`/bts` 판정 5문 ②). classify 가
「board settings screen」을 프론트 표면으로만 읽고 신규 스키마·API 가능성을 못 봤다.
형제 작업 `board-summary-can-delete` 도 같은 시간대에 classify 오분류를 기록했다 —
**연속 2건이면 판별식 자체가 대상**이지만 이 PR 범위는 아니다.

FR — **FR-BD-01** · **FR-BD-03** · **FR-BD-04**. 신규 FR 필요 여부는 스펙에서 판단한다
(카드 레이아웃 갭 B 가 유일한 후보이고, FR-BD-03 범위 확장으로 흡수 가능한지가 쟁점이다).

## ★ 착수 실측 — 장부 갭 표가 3곳 틀렸다 (2026-09-04 · 재조사 불필요)

`TODOS.md:1459~` 의 갭 표는 2026-09-03 실측이다. 오늘 다시 재면 **3행이 다르다.**
스펙은 아래를 전제로 쓴다 — 장부 표를 그대로 옮기면 이미 있는 것을 다시 만든다.

| 장부 판정 | 오늘 실측 | 근거 |
|---|---|---|
| **갭 A** Swimlanes — 「서버는 되는데 누를 곳이 없다」 | **거짓. 화면이 이미 있다.** | `apps/web/src/components/board/SwimlaneSelector.tsx` 가 존재하고 `projects.$projectKey.board.tsx:799` 에서 렌더된다(`:29` import · `:495` CREATE 권한 게이팅 · `:543` onChange). FR-BD-03 **D6 (PR #173)** 이 「스윔레인 셀렉터(서버 저장, CREATE 게이팅)」로 이미 냈다 |
| **갭 F** 「설정 라우트 자체가 없다」 | **부분 거짓.** 프로젝트 설정 라우트는 **13종** 있다 | `apps/web/src/routes/projects.$projectKey.settings.{automation,components,custom-fields,details,field-permissions,import,issue-templates,members,project-lead,slack-channels,versions,workflow-scheme}.tsx`. 없는 것은 **보드 스코프** 설정 라우트와 보드 화면에서 그리로 가는 **진입 경로**다 |
| **표에 없는 갭** WIP 제한 **편집** UI | **없다. 그런데 갭 A~F 어디에도 안 적혔다** | `WipCountBadge.tsx` 는 표시 전용(`count/limit` · 초과 경고)이고 `BoardColumn.tsx:142` 가 `column.wipLimit` 를 읽기만 한다. FR-BD-03 **D6 deviation ③** 「WIP 제한 '편집' UI는 후속 이연」이 그대로 살아 있다 — 지라 `Columns` 탭의 1급 항목인데 담당자가 없다 |

**★이 3행이 말하는 것.** 부채 177 은 「7탭이 통째로 없다」가 아니라 **「지라가 설정 탭에 모아 둔
것들이 BTS 에서는 화면 여기저기에 흩어져 있거나 담당자 없이 빠져 있다」**이다. 성격이 다르므로
스펙의 출발점도 다르다 — 신설이 아니라 **수렴 + 결손 보충**이다.
memory `two-lists-never-check-each-other` 의 문서판 — 장부 갭 표와 `docs/plan/product/agile-planning.md`
의 D 단계 체크박스가 **서로를 검사한 적이 없다.**

## 승계 — 이 작업의 정중앙에 있는 learning

**2026-07-17 「도메인·서비스·repo 가 다 있어도 REST 노출이 없으면 기능이 없는 것이다」**
(`Maxi_wiki/BTS/learnings.md:758`). 장부가 갭 A 를 이 learning 의 **UI 판본**이라 불렀는데,
실측 결과 갭 A 자체는 거짓이었다. 그러나 **양식은 여전히 유효하다** — WIP 편집이 정확히 그 자리다.
백엔드가 `wipLimit` 를 들고 화면이 그것을 그리는데, **바꿀 곳이 없다.**

## 착수 시 먼저 풀 것 (장부가 남긴 3건 · 스펙이 답한다)

1. `Days in column`(J20)의 컬럼 진입 시각을 어디서 얻나 — 전환 이력은 `issue-tracking` 소유라 BC 격리상 포트 필요 가능
2. 카드 색(J21)의 JQL 기준은 `search` BC 소관 — 배제 대상인지
3. 퀵필터를 설정 화면으로 옮기면(J11) 칩이 한 번에 안 보여 오늘 UX 가 나빠진다 — 의도적 편차 후보

**4번을 더한다.** 스윔레인 셀렉터(이미 있음)를 설정 탭으로 **옮길지**. 옮기면 오늘 되는 것이
한 단계 멀어진다 — 3번과 같은 축이고 같은 답을 줘야 한다.

## 병행 세션 (건드리지 말 것)

다른 Claude 세션이 `.worktrees/board-summary-can-delete` 에서 **캠페인 PR ⑧**(보드 요약 `canDelete`)를
진행 중이다(10:24 plan 스텁 커밋). 같은 `agile-planning` BC 이고 **응답 DTO·컨트롤러가 겹칠 수 있다** —
`BoardResponses.kt` · `BoardController.kt` 를 만지기 전에 머지 여부를 확인한다.

## Jira 대조

**조회 주체·일자.** 부채 177 등재 세션이 2026-09-03 에 실물 조회했고 그 결과를 `TODOS.md:1459~`
본문에 **J7~J21 로 남겼다** — 그 절이 「착수 시 계약 §1-0 **재사용 대상**」이라고 스스로 적었다.
따라서 이 스텁은 재조회하지 않고 승계한다. **스펙 단계에서 카드 레이아웃·Estimation 2탭만
재확인**한다(갭 B·C 가 이 PR 의 신규 표면이고 나머지는 승계로 충분하다).

| 출처 | 무엇을 정하는가 |
|---|---|
| [Configure a company-managed board](https://support.atlassian.com/jira-software-cloud/docs/configure-a-company-managed-board/) | 진입 경로(J7 — *more → Configure board*) · 권한(J8 — space admin 또는 board admin) · 7탭 구성(J9~J15) |
| [Customize cards](https://support.atlassian.com/jira-software-cloud/docs/customize-cards/) | 카드 레이아웃 경로(J16) · **추가 필드 상한 3개**(J17) · **백로그와 활성 스프린트가 서로 다른 설정**(J18) · 필드 순서(J19) · Days in column(J20) · 카드 색 기준(J21) |

**BTS 현재 상태와의 대조 3줄.**
1. **진입 경로(J7)** — BTS 보드 화면에 「보드 설정」으로 가는 경로가 **없다**. 갭 F 의 진짜 내용.
2. **권한(J8)** — BTS 는 보드 API 게이트를 프로젝트 권한으로 이미 건다. 지라의 *board administrator*
   에 대응하는 **보드 단위 역할이 BTS 에 없다** — 편차 후보이고 스펙이 판단한다.
3. **탭 배치(J9~J15)** — BTS 는 스윔레인·퀵필터를 **보드 화면 인라인**에 뒀다. 지라로 수렴시키면
   오늘 되는 것이 한 단계 멀어진다(위 「먼저 풀 것」 3·4번). **의도적 편차 후보 1순위.**

## 도메인 정리

**BC — `agile-planning` 단일.** 한 PR = 한 BC 를 지킨다.

**영향 엔티티.** `Board` · `BoardColumn`(`name` · `category` · `displayOrder` · `wipLimit` · `stateKeys`) ·
`board_column_states`(#444 V508). **워크플로우 상태**는 `project-workflow` 소유이고
`service.listWorkflowStates(projectKey)` 경유로만 읽는다 — 직접 import 금지.

**새 용어 — 없다.** 「컬럼」 · 「미매핑 상태」 · 「WIP 제한」 모두 기존 용어이고
`unmappedStates` 는 #444 가 이미 `BoardDetailResponse` 에 실었다. `glossary.md` 갱신 대상 없음.

**관련 ADR 2건 · 충돌 0.**

| ADR | 관계 |
|---|---|
| [`2026-09-03-board-column-multi-state`](../adr/2026-09-03-board-column-multi-state.md) | **전제를 깔아 준다.** D5·D7 이 「`category` 는 표시 전용」을 확정했고 이 PR 의 편차 `X1`(category 를 사용자가 안 고른다)이 그것을 승계한다. 컬럼 관리 API 3종·`unmappedStates` 도 이 ADR 의 산물이다 |
| [`2026-09-01-board-type-and-active-sprint`](../adr/2026-09-01-board-type-and-active-sprint.md) | **충돌 없음.** 이 PR 은 보드 종류를 읽지도 쓰지도 않는다 — 컬럼 구성은 스크럼·칸반 공통이다 |

**신규 ADR 필요 없다.** 새 엔티티·경계·용어가 0건이고 기존 결정을 무효화하지 않는다.
T3 선언이지만 `grill-with-docs` 호출 조건(신규 도메인 개념)에 해당하지 않아 부르지 않는다.

## 스펙

정본 **[`docs/specs/2026-09-04-board-settings-screen.md`](../specs/2026-09-04-board-settings-screen.md)**
— R1~R11 · N1~N6 · E1~E7 · 편차 X1~X4 · 완료 기준 14개.

핵심 3줄.
1. **범위는 「뼈대 + Columns 탭」 하나다**(Maxi 확정). 나머지 6탭은 만들지 않는다 — 비활성 골격은
   「도달할 UI 가 없는 기능」의 거울상이라 배포하지 않는다. 탭이 하나라 **탭바도 안 만든다**.
2. **마이그레이션 0 · 신규 API 2종뿐.** Columns 탭 백엔드 4종(WIP 편집·생성·삭제·상태 교체)이
   이미 있고 UI 만 0개였다. 없는 것은 **컬럼 이름 변경 · 순서 변경** 둘이다.
3. **이 PR 이 부채 178 을 해금한다.** 178 이 착수 조건을 「드롭존 UI PR 안정화 뒤」로 걸었고
   상태 매핑 드래그가 그 드롭존이다. 동시에 FR-BD-03 **D6 deviation ③**(WIP 편집 UI 이연)을 상환한다.

## Sanity Check

**gap 3건 발견 · 2건 자체 보강 · 1건 Maxi 확정. ✅ 통과 — 미해결 결정 없음.**

- ❓**G1 🔴 마지막 컬럼 삭제 처방 부재** → **보강.** 컬럼 1개면 삭제 비활성(R7).
  이 PR 이 부채 179(컬럼 0개 조회 500)로 가는 **클릭 한 번짜리 경로**를 새로 만들 뻔했다.
- ❓**G2 🟡 낙관적 반영의 되돌리기 기준 모호** → **보강.** 409 만이 아니라 **모든 실패**에서 되돌린다(E3).
- ❓**G3 🔴 탭 골격 7종을 지금 그리는가** → **Maxi 확정.** Columns 하나만.

**흔들었으나 문제없던 것.** 편차 `X3`(스윔레인·퀵필터 인라인 유지)이 지라 패리티를 깨는지 —
깨지 않는다. 지라도 **보드 화면에서 스윔레인을 볼 수 있고**, 다른 것은 「설정을 어디서 바꾸나」뿐이다.
게다가 BTS 쪽이 조작 단계가 짧다.

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
