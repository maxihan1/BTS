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

## Plan

**분해 원칙 2가지.**

1. **Kotlin 모듈은 컴파일 단위가 하나다** — #444 구현 실측이 「파일 무교집합 ⇒ 병렬」이 성립하지
   않음을 증명했다(wave 1 에서 7파일 43곳 연쇄). T1·T2 는 `BoardController.kt` 를 공유해
   **파일 겹침으로 자동 직렬화**되고, 억지 병렬은 서로의 산출물을 덮으므로 그대로 둔다.
2. **프론트는 읽기 → 조작 순서다.** T5(읽기 렌더)가 서고 나서야 T6~T8 의 조작이 붙을 자리가 생긴다.

### 계약 §5 사전 grep 결과 (눈가리개 방지 · 착수 전 실측)

- `apps/web/e2e/` 에서 **보드 라우트를 잡는 spec 15개** — 그중 이 PR 이 실제로 흔드는 것은
  `board-manage.spec.ts`(더보기 메뉴 · T4 가 항목을 더한다) · `board-wip-swimlane.spec.ts`(WIP 배지 ·
  T8 이 값을 바꾼다) · `board-kanban.spec.ts`(컬럼 렌더 · T7 이 컬럼 수를 바꾼다) · `board-reorder.spec.ts`
  (순서 · T8). 나머지 11개는 보드를 **경유만** 한다.
- **`보드 설정` 문자열은 e2e 에 0건** — 신규 라벨이라 기존 셀렉터와 충돌하지 않는다.
- ★**함정.** `board.tsx:392` 가 `if (!canRename && !canDelete) return null` 로 드롭다운을 통째로
  숨긴다. 「보드 설정」 항목만 더하고 이 줄을 안 고치면 **두 권한이 다 없는 사용자에게 설정 진입도
  사라진다** — 의도한 게이팅(S7)과 우연히 같아 보이지만 근거가 다르다. T4 가 이 줄을 명시적으로 고친다.

### Task 1. `PATCH /boards/{id}/columns/{columnId}` 가 `name` 을 받는다 (R9 · J24)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/web/BoardController.kt`, `backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/web/dto/BoardResponses.kt`, `backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/application/BoardApplicationService.kt`, `backend/modules/agile-planning/src/test/kotlin/com/bts/agileplanning/web/BoardControllerIntegrationTest.kt`]
- depends-on: []
- jira: [J24]

**RED**. `BoardControllerIntegrationTest` 에 3건.
```kotlin
@Test fun `PATCH 컬럼이 name 만 보내면 이름이 바뀌고 wipLimit 은 보존된다`()
@Test fun `PATCH 컬럼이 name 과 wipLimit 을 함께 보내면 둘 다 반영된다`()
@Test fun `PATCH 컬럼이 아무 필드도 안 보내면 400`()
```
예상 실패 — `UpdateColumnRequest` 타입 없음 · `name` 프로퍼티 없음.

**GREEN**.
- `UpdateColumnWipLimitRequest` → **`UpdateColumnRequest` 개명** + `name: String?` 추가.
  ★개명을 미루지 않는다 — `name` 을 받는데 타입 이름이 `WipLimit` 이면 **이름이 거짓말**이 되고,
  그 거짓말은 다음 사람이 읽는 유일한 단서다.
- 서비스에 `updateColumn(boardId, columnId, name, wipLimit)`. `name` 은 `isNotBlank` 검증
  (`BoardColumn.kt:66` 의 `require` 와 **같은 판정**을 컨트롤러가 400 으로 앞당긴다).
- 둘 다 `null` 이면 400 — 기존 `updateBoard` 의 「name 또는 swimlaneField 중 하나는 전송」 양식 승계.

**REFACTOR**. KDoc 을 개명에 맞춰 갱신. `wipLimit` 전용이라 적힌 주석이 남으면 그것도 거짓말이다.

**검증**. `./gradlew :modules:agile-planning:test --tests '*BoardControllerIntegrationTest*'` ·
`ktlintCheck` · `detekt` **둘 다 `--rerun-tasks`** (#444 실측 — 훅이 린트를 안 돌아 3커밋이 red 로 지나갔다).

**⚠️ 병행 세션 충돌 지점.** `BoardResponses.kt` 는 다른 세션(캠페인 PR ⑧)이 건드리는 파일이다.
**착수 직전 `gh pr list` 로 그 PR 머지 여부를 확인**하고, 열려 있으면 `git pull --rebase origin main` 을 먼저 돌린다.

### Task 2. `PUT /boards/{id}/columns/order` 신설 — 전체 순서 교체 (R10 · J25)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/web/BoardController.kt`, `backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/web/dto/BoardResponses.kt`, `backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/application/BoardApplicationService.kt`, `backend/modules/agile-planning/src/test/kotlin/com/bts/agileplanning/web/BoardControllerIntegrationTest.kt`]
- depends-on: [1]   # 같은 컨트롤러·DTO 파일 — 자동 직렬화
- jira: [J25]

**RED**. 4건.
```kotlin
@Test fun `PUT columns order 가 순서를 통째로 바꾼다`()
@Test fun `columnIds 에 컬럼이 빠지면 400`()
@Test fun `columnIds 에 중복이 있으면 400`()
@Test fun `다른 보드의 컬럼 id 가 섞이면 400`()
```

**GREEN**. `ReorderColumnsRequest(columnIds: List<UUID>)`. 서비스가 **집합 일치**를 먼저 판정
(누락·중복·외부 id 를 한 번에 잡는다) 후 `display_order` 를 0부터 다시 매긴다.

★ **부분 이동이 아니라 전체 교체인 이유.** #444 가 상태 집합을 통째로 받기로 한 것과 같은 근거다 —
「지금 이 보드의 컬럼 순서」가 클라이언트와 서버 사이에서 갈리지 않는다.

**REFACTOR**. 집합 일치 판정을 순수 함수로 빼 뮤테이션 짝이 흔들 수 있게 한다.

**검증**. 위와 동일 + **N2 실측** — `wc -l BoardRepository.kt` 가 **443 이하**인지 본다(부채 157).
순서 갱신을 리포지터리에 넣으면 이 상한을 넘길 수 있다.

### Task 3. 프론트 컬럼 관리 API 클라이언트 + Zod 6종 (R4~R10 기반)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/api/boards.ts`, `apps/web/src/api/boards.test.ts`]
- depends-on: [1, 2]
- jira: []

**RED**(동반 테스트). 응답 Zod 파싱 + 요청 본문 형태 6종 — `getBoard`(기존 재사용 확인) ·
`createColumn` · `deleteColumn` · `replaceColumnStates` · `updateColumn`(name·wipLimit) · `reorderColumns`.

★**learning 2026-05-30 「Zod 응답 스키마 강화가 산재한 인라인 mock 을 깬다」(PR #46) 의 자리다.**
`unmappedStates` 는 #444 가 이미 실었으므로 **스키마를 새로 조이지 않는다** — 조이면 이 PR 과
무관한 mock 이 무더기로 깨진다.

**GREEN**. 함수 6개 + 요청/응답 스키마. 컬럼 관리 클라이언트는 **현재 0개**라 전량 신설이다.

**REFACTOR**. 보드 상세 쿼리 키 무효화 헬퍼 1개로 모은다(R11).

**검증**. `cd apps/web && node_modules/.bin/vitest run src/api/boards.test.ts`
★**worktree 프론트 검증은 `apps/web` 이 cwd 여야 한다** — 루트에서 돌리면 `@/` alias 미해석,
`--root apps/web` 만 주면 소스 스캔 판별식 오탐(#422 실측).

### Task 4. 설정 라우트 + 진입 경로 + 스코프 가드 (R1·R2 · S1·S7·S8 · J7·J8·J22 · X4)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/routes/projects.$projectKey.board.settings.tsx`, `apps/web/src/routes/projects.$projectKey.board.tsx`, `apps/web/src/router.ts`, `apps/web/src/i18n/board-labels.ts`, `apps/web/src/routes/projects.$projectKey.board.settings.test.tsx`]
- depends-on: []
- jira: [J7, J8, J22]

**RED**(동반 테스트).
- `?board=` 없이 들어오면 보드 화면으로 되돌린다 (S8 — **기본 보드 규칙을 4번째로 늘리지 않는다**)
- CREATE 권한자에게만 더보기 메뉴에 「보드 설정」이 뜬다 (S7)
- 권한 없는 사용자가 URL 직접 진입하면 편집 컨트롤이 전부 비활성 + 사유 (S7)

**GREEN**.
- 라우트 어댑터 + `router.ts` 등록. **code-based 패턴**(learning 2026-05-22 — `router.ts` 는 `.ts` 라 JSX 제약이 있어 어댑터 컴포넌트로 우회한다). 기존 13개 설정 라우트와 같은 모양.
- `board.tsx` 더보기 메뉴에 항목 1개 + ★**`:392` 의 `if (!canRename && !canDelete) return null` 을 `canConfigure` 까지 포함하도록 고친다** (사전 grep 함정).
- 라벨은 `board-labels.ts` `actions` 그룹에 추가 — 문자열 리터럴을 컴포넌트에 박지 않는다.

**REFACTOR**. 권한 판정(`canCreate`)을 세 항목이 공유하게 정리. `canRename={canCreate}` 가 이미 그 형태다.

**검증**.
- 기존 E2E: `apps/web/e2e/board-manage.spec.ts` (더보기 메뉴 한 바퀴 — 항목이 늘어도 S1~S5 가 초록이어야 한다)
- 눈확인: 더보기 메뉴 펼침 + 설정 화면 진입 — **라이트/다크 양쪽**

### Task 5. Columns 탭 읽기 렌더 — 컬럼 + 미매핑 패널 (R4 · J22)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/board/settings/ColumnSettingsPanel.tsx`, `apps/web/src/components/board/settings/UnmappedStatesPanel.tsx`, `apps/web/src/components/board/settings/ColumnSettingsPanel.test.tsx`, `apps/web/src/routes/projects.$projectKey.board.settings.tsx`]
- depends-on: [3, 4]
- jira: [J22]

**RED**(동반 테스트). 컬럼을 `displayOrder` 순으로 그린다 · 미매핑 상태를 패널에 그린다 ·
**상태 0개 컬럼을 「상태 없음」으로 명시**한다(E1 — 그냥 비워 두면 사용자가 미완성임을 모른다).

**GREEN**. `GET /boards/{id}` 응답만 소비한다 — **신규 조회 API 0개**. 빈 상태는 기존
`EmptyState` 프리미티브 재사용(계약 §4).

**REFACTOR**. 파일당 줄수 래칫(N4)에 맞춰 컬럼 카드를 별 파일로 분리.

**검증**.
- 기존 E2E: `apps/web/e2e/board-kanban.spec.ts` (컬럼 렌더 계약 — 이 task 는 보드 화면을 안 건드리므로 무회귀 확인용)
- 눈확인: 컬럼 3개 + 미매핑 2건 · 상태 0개 컬럼 · 미매핑 0건 — **라이트/다크**

### Task 6. 상태 매핑 드래그 (R5 · S2·S3 · E3 · G2 · J27)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/board/settings/ColumnSettingsPanel.tsx`, `apps/web/src/components/board/settings/state-mapping-drop.ts`, `apps/web/src/components/board/settings/state-mapping-drop.test.ts`, `apps/web/src/hooks/use-replace-column-states.ts`, `apps/web/src/hooks/use-replace-column-states.test.tsx`]
- depends-on: [5]
- jira: [J27]

**RED**(동반 테스트).
- 미매핑 → 컬럼 드롭이 `PUT …/states` 를 **집합 전체**로 부른다 (S2)
- 컬럼 → 미매핑 드롭이 그 상태를 뺀 집합으로 부른다 (S3)
- **409 면 드롭을 되돌리고 전용 문구**를 보인다 (E3)
- **네트워크 실패·500 에서도 되돌린다** (G2 — 「409 만 되돌린다」가 이 판정을 통과하면 안 된다)

★ 마지막 두 판정을 **한 테스트로 합치지 않는다.** 합치면 「모든 실패에서 되돌린다」를
「409 에서 되돌린다」로 좁혀도 초록이다 — memory `invariant-satisfied-by-helptext-not-logic` 양식.

**GREEN**. 기존 `@dnd-kit` + **`KanbanBoard.tsx` 의 `buildDragAnnouncements` 재사용**
(한국어 조사 처리 완비 · 계약 §4). **새 공지 구현을 만들지 않는다.**

**REFACTOR**. 드롭 판정을 순수 함수(`state-mapping-drop.ts`)로 — `board-drop.ts` 선례와 같은 모양.

**검증**.
- 기존 E2E: 없음(신규 화면). `board-kanban.spec.ts` 는 드래그 자산 공유라 무회귀 확인
- 눈확인: 드래그 중 드롭존 하이라이트 · 실패 시 되돌림 — **라이트/다크**

### Task 7. 컬럼 추가·삭제 (R6·R7 · S5·S6 · E1 · G1 · J23·J26·J28 · X1)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/board/settings/AddColumnDialog.tsx`, `apps/web/src/components/board/settings/DeleteColumnDialog.tsx`, `apps/web/src/components/board/settings/AddColumnDialog.test.tsx`, `apps/web/src/components/board/settings/DeleteColumnDialog.test.tsx`, `apps/web/src/components/board/settings/ColumnSettingsPanel.tsx`]
- depends-on: [5]
- jira: [J23, J26, J28]

**RED**(동반 테스트).
- 추가는 이름만 받고 **`category` 를 안 보낸다** (X1 — 파생값이다)
- 추가 결과가 **상태 0개 컬럼**이고 화면이 「상태 없음」으로 표시한다 (S5·E1)
- 삭제 다이얼로그가 **영향 카드 수**를 먼저 보인다 (S6)
- 삭제 후 그 상태가 미매핑 패널로 돌아온다 (J28)
- ★**컬럼이 1개면 삭제가 비활성이고 사유가 보인다** (G1 — 부채 179 도달 차단)

**GREEN**. 다이얼로그 2종. **고유 `aria-label` 필수**(즉사 계약 §2 — strict mode 충돌 방지).

**REFACTOR**. 두 다이얼로그의 공통 실패 문구를 라벨 레지스트리로.

**검증**.
- 기존 E2E: `apps/web/e2e/board-kanban.spec.ts`(컬럼 수 변화) · `board-manage.spec.ts`
- 눈확인: 추가 다이얼로그 · 삭제 확인(카드 2장 경고) · 컬럼 1개일 때 비활성 사유 — **라이트/다크**

### Task 8. 이름 변경 · WIP 제한 편집 · 컬럼 순서 드래그 (R8·R9·R10 · S4 · J24·J25·J29 · X2)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/board/settings/ColumnSettingsCard.tsx`, `apps/web/src/components/board/settings/ColumnSettingsCard.test.tsx`, `apps/web/src/hooks/use-update-column.ts`, `apps/web/src/hooks/use-reorder-columns.ts`, `apps/web/src/hooks/use-update-column.test.tsx`, `apps/web/src/components/board/settings/ColumnSettingsPanel.tsx`]
- depends-on: [3, 5]
- jira: [J24, J25, J29]

**RED**(동반 테스트).
- 이름 인라인 편집 후 Enter 가 `PATCH` 를 부른다 (J24)
- WIP 제한에 `3` → `PATCH wipLimit=3` · **빈 값 → `null`(무제한)** (S4·J29 후단)
- 컬럼 드래그가 `PUT …/columns/order` 를 **전 컬럼 순서**로 부른다 (R10)
- **최소치 입력란이 없다** (X2 — 지라 J29 의 minimum 을 안 낸다는 것을 판정으로 박는다)

★ 마지막 판정을 빼지 않는다. 편차는 **적어 두는 것으로 지켜지지 않고** 판정으로만 지켜진다.

**GREEN**. 인라인 편집 + 숫자 입력 + `@dnd-kit` 가로 정렬. 순서 드래그는 T6 의 상태 드래그와
**다른 축**이므로 dnd context 를 분리한다(같은 컨텍스트에 두면 상태를 컬럼 헤더에 떨어뜨릴 수 있다).

**REFACTOR**. `use-update-column` 이 `name`·`wipLimit` 둘 다 다루므로 호출부가 부분 갱신을 보내게 정리.

**검증**.
- 기존 E2E: `apps/web/e2e/board-wip-swimlane.spec.ts`(WIP 배지 계약) · `board-reorder.spec.ts`(순서)
- 눈확인: 이름 편집 중 · WIP 초과 배지가 보드 화면에 반영 · 컬럼 드래그 — **라이트/다크**

### Task 9. E2E 시나리오 + 무회귀 실측

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/board-settings.spec.ts`]
- depends-on: [4, 5, 6, 7, 8]
- jira: []

**동반 테스트**. S1~S8 한 바퀴 — 진입 → 상태 매핑 → 매핑 해제 → WIP 편집 → 컬럼 추가 →
컬럼 삭제 → 권한 없음 → `?board=` 없음.

**무회귀 실측**(이 task 의 절반은 측정이다).
- `agile-planning` 모듈 전량 `--rerun-tasks`
- `apps/web` vitest 전량 · `tsc -p tsconfig.app.json --noEmit` (★루트 tsconfig 는 `files: []` 라 0개를 검사한다)
- 표적 E2E 5 spec — `board-settings`(신규) · `board-manage` · `board-kanban` · `board-wip-swimlane` · `board-reorder`
- 판별식 전량 `pnpm test:workflow` — ★**실행 건수를 함께 읽는다.** #444 가 `backend/` 에서 돌렸다가 「0건 실행 · EXIT=0」을 초록으로 오독할 뻔했다
- `build-doc-index --check` · `verify-master-plan.sh`
- **뮤테이션 짝 3건** — ①T2 집합 일치 판정 제거 → 400 판정 1건만 red ②G1 컬럼 1개 비활성 제거 → 1건만 red ③G2 「모든 실패에서 되돌림」을 409 한정으로 좁힘 → 1건만 red.
  ★**GREEN 선커밋 뒤에 돌린다** — 미커밋 원복은 소실이다(함정 정본).

## Plan 메타

- **task 수** 9 (각 TDD 사이클 1개) · **예상 wave** 7
- **wave 배치** — ① T1 · T4 → ② T2 → ③ T3 → ④ T5 → ⑤ T6 · T7 → ⑥ T8 → ⑦ T9
  T1·T2 는 `BoardController.kt`·`BoardResponses.kt` 공유로 **자동 직렬화**된다(§2 메타 계약).
  T4 는 백엔드와 파일 교집합이 0이라 wave 1 에서 T1 과 병렬 가능하다.
- **구현 규율** TDD red→green→refactor. 백엔드 T1·T2 는 `test:` 커밋이 `feat:` 보다 **먼저** —
  CI 판별식 ①d 가 대조한다. 프론트 T4~T8 은 ui 시각 검증 트랙(동반 테스트 + 눈확인).
- **추가 검증** ktlintCheck · detekt(둘 다 `--rerun-tasks`) · `tsc -p tsconfig.app.json --noEmit` ·
  vitest(cwd = `apps/web`) · Playwright 표적 5 spec · 판별식 전량(**실행 건수 확인**) ·
  `node scripts/build-doc-index.mjs --check`
- **Jira 매핑** — J7→T4 · J8→T4 · J22→T4·T5 · J23→T7 · J24→T1·T8 · J25→T2·T8 ·
  J26→T7 · J27→T6 · J28→T7 · **J29→T8(부분 채택 — 최대치만, 최소치는 X2)**.
  **채택 10 번호 전부 task 에 물렸다 — 차집합 0.** J10·J11 은 편차 X3 으로 「안 옮긴다」가 결론이라
  task 가 없는 것이 정상이고, 그 사실을 X3 이 사유와 함께 적는다.
- **장부** 이 PR 은 부채 **177 을 닫는 첫 조각**이고(7탭 중 1탭) 부채 **178 의 착수 조건을 연다**.
  **179 는 안 닫되 G1 이 새 도달 경로를 막는다.** FR-BD-03 **D6 deviation ③**(WIP 편집 UI 이연)을
  상환하므로 `docs/plan/product/agile-planning.md` §2.3 의 그 줄을 갱신 대상으로 잡는다.
- **N2 감시** — `BoardRepository.kt` 443줄 상한(부채 157). T2 가 가장 위험하다.

## 리뷰 결과 (← /bts-review-plan 채움)
