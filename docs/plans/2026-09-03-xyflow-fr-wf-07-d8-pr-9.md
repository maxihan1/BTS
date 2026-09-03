# xyflow 다이어그램 편집기 (FR-WF-07 D8 · 로드맵 PR 9)

> 티어: T2
> slug: xyflow-fr-wf-07-d8-pr-9
> type: ui
> agent: frontend-engineer
> 생성: 2026-09-03

## Brief

**FR-WF-07 D8** — 워크플로우 편집기에 `@xyflow/react` 다이어그램 모드를 얹는다. 로드맵 PR 9.
FR-WF-07 의 **유일한 미완 D 마커**이고, 이것이 닫히면 §2 FR-WF 7개가 전부 `[x]` 가 된다.

**Maxi 결정 (2026-09-03)** — 「지라 클라우드와 동일한 스펙으로」. 범위를 로드맵이 아니라 **Jira 실물이
정한다.** 아래 `## Jira 대조` 가 그 조회 기록이고, 채택 항목이 FR 을 결정했다.

**classify 원본과 교정.** `classify-task.ts` 는 `type=backend · agent=backend-engineer · tier=T1 ·
primary_bc=agile-planning` 을 냈으나 **세 축이 틀렸다**. 교정 근거는 실측이다.

- **tier T1 → T2.** `detect-tier.ts` 가 예상 변경 집합에서 `TIER: T2 · SURFACES: TEST, DEPS, DOC, FE_SRC`
  를 냈다. `DEPS`(`apps/web/package.json` · `pnpm-lock.yaml`)가 T2 표면이고, 혼합이면 최고 티어가
  지배한다(`/bts` §티어 판정 ①). **선언 T2 == 실측 T2.** 스펙 확정으로 `BE_MAIN` 도 더해졌으나
  그것 역시 T2 라 승격은 없다.
- **type backend → ui · agent → frontend-engineer.** 변경 표면의 중심이 `apps/web` 이다.
- **primary_bc agile-planning → project-workflow.** FR-WF-07 은 project-workflow BC 다.

## 도메인 정리

**BC** — `project-workflow` 단일. issue-tracking·agile-planning 은 건드리지 않는다.

**영향 엔티티**

| 엔티티 | 무엇이 바뀌나 |
|---|---|
| `workflow_statuses.layout_x` · `layout_y` | **컬럼은 이미 있다**(V203, `REAL` nullable). 지금까지 **쓰는 코드가 0** 이었고 이 PR 이 첫 소비자다 |
| `workflow_drafts.definition` (JSONB) | `states[]` 항목에 `layoutX` · `layoutY` 2필드 추가. **JSONB 라 마이그레이션 0** |
| `DraftStateDto` (Kotlin) | 4필드 → 6필드. 기본값 `null` |
| `draftStateSchema` (Zod, `.strict()`) | 4필드 → 6필드 |

**새 용어** — 없다. 「다이어그램 모드」·「노드」·「엣지」는 `glossary.md` 에 이미 있거나 일반 용어다.
`glossary.md` 갱신 대상 없음.

**관련 ADR**

- `docs/adr/2026-08-18-workflow-editor-canvas-library.md` — **채택(Active)**. `@xyflow/react` 도입 근거.
  절대 규칙 17 Maxi 승인 2026-08-18. 이 PR 이 그 ADR 의 **첫 실행**이다
- `docs/adr/2026-06-26-gantt-rendering-self-svg.md` — 「시각화는 자체 SVG」 선례. 위 ADR 이 §선례와
  갈리는 지점에서 이미 차이를 명시했고 **타임라인 영역에서는 계속 유효**하다. 이 PR 이 무효화하지 않는다

**기존 결정과의 충돌 — 1건 있고, 이 스펙이 해소한다**

로드맵(2026-08-18)은 「노드 좌표는 `workflow_statuses.layout_x/y` 에 저장」을 전제했다. 그 뒤
**FR-WF-07 D1~D7 이 편집기를 초안(JSONB) 기반으로 바꿨다**(#411·#423·#427). 초안의 새 상태는 아직
`workflow_statuses` 행이 아니라 **그 컬럼에 직접 쓸 대상이 없다.**

→ **좌표는 초안에 싣고, 발행 시 정규 테이블로 내려쓴다.** 초안이 편집의 단일 진실이라는 D1 의 결정을
좌표에도 그대로 적용하는 것이고, 로드맵의 저장 위치는 「발행 후 최종 목적지」로 읽는다. 편차 X3.

## Jira 대조

**조회일 2026-09-03 · 전부 Jira Cloud** (DC 는 구 workflow designer 라 대조 대상 아님).
§1-0 재사용 grep 결과 — 워크플로우 편집기 표면의 기존 `## Jira 대조` 절 중 **다이어그램 모드 고유
조작을 조회한 행은 0건**이었다(PR 8·10a·10b 는 전부 목록/다이얼로그 표면). 그래서 이번은 **신규 조회**다.

| # | Jira 원문 | 출처 | BTS 판정 |
|---|---|---|---|
| **J1** | "Drag to move statuses and create transitions. Select a status or transition to see its details." | [what-is-the-new-workflow-editor](https://support.atlassian.com/jira-cloud-administration/docs/what-is-the-new-workflow-editor/) | **채택** → F5·F7·F8·F9 |
| **J2** | "You can edit a workflow in text (rather than the diagram) by using the Text/Diagram toggle to switch between views." | 〃 | **채택(편차 X2)** → F1 |
| **J3** | "you can drag a line between two statuses in the diagram to create a transition." | [create-workflow-transitions](https://support.atlassian.com/jira-cloud-administration/docs/create-workflow-transitions/) | **채택** → F7 |
| **J4** | "click Add transition or drag from a node on one status to another to add a transition. Nodes appear on statuses in the workflow designer when you hover over a status." | 〃 | **채택** → F7 (핸들은 **hover 시 나타난다**) |
| **J5** | "Transitions can also loop, so the work item's status stays the same. This is useful for opening transition screens or triggering actions without changing the status." | 〃 | **채택** → F10 self-loop 렌더 |
| **J6** | "Select **From status**, then select **Any status** for the action to be available from all statuses" | 〃 | **채택** → F11 GLOBAL 렌더 |
| **J7** | "select a status or transition to edit its details in the sidebar, just as you would in the diagram." | [what-is-the-new-workflow-editor](https://support.atlassian.com/jira-cloud-administration/docs/what-is-the-new-workflow-editor/) | **채택(편차 X1)** → F8·F9. 사이드바 대신 기존 다이얼로그 |

**대응 없음 2건** — 조회했고 없었다는 기록이다. 절을 지우지 않는다.

- **A1. 노드 좌표 영속.** 위 두 문서 어디에도 좌표가 저장되는지·세션을 넘어 남는지를 다루는 문장이
  **한 줄도 없다.** ADS(Atlassian Design System) 에도 대응 패턴이 없다 — 좌표 영속은 디자인 시스템이
  아니라 데이터 모델 사안이다. → **BTS 스키마 의도를 준용한다.** V203 이 `layout_x`/`layout_y` 를 두고
  `COMMENT ON COLUMN … IS '다이어그램 편집기 노드 X 좌표. NULL 이면 자동 배치'` 라고 적었다.
  **컬럼과 그 주석이 영속을 전제한 설계 근거**다
- **A2. 자동 배치(auto-layout).** Jira 문서에 「tidy up」·「auto layout」 언급 0건. ADS 대응 없음.
  → BTS 자체 규칙 — 카테고리 열(TODO → IN_PROGRESS → DONE). V203 주석의 「NULL 이면 자동 배치」가
  근거이고 로드맵 PR 9 도 같은 규칙을 적었다

**의도적 편차 3건**

- **X1. 상세 편집을 사이드바가 아니라 다이얼로그로 한다** (J7 대비). 근거 — 10a·10b 가 만든
  `TransitionFormDialog`·`StatusPickerDialog` 를 그대로 재사용하면 편집 로직이 한 벌로 유지되고,
  즉사 계약의 `role="dialog"` 이름 4종도 안 늘어난다. 사이드바를 새로 만들면 **같은 편집 로직이 두 벌**이
  되고 그 둘이 서로를 검사하지 않는다(`[[two-lists-never-check-each-other]]` 의 양식)
- **X2. Text/Diagram 2모드가 아니라 상태·전환·다이어그램 3탭** (J2 대비). 근거 — BTS 목록 모드가
  PR 8 에서 이미 상태/전환 두 탭으로 갈렸고 `workflow-editor.spec.ts:89` 가 `getByRole('tab', {name:'전환'})`
  로 그것을 잡는다. 하나로 합치면 그 E2E 가 깨진다
- **X3. 좌표를 초안(JSONB)에 싣고 발행 시 정규 테이블에 내려쓴다.** Jira 는 초안/발행 구분이 없어
  이 축 자체가 없다. FR-WF-07 D1 이 만든 BTS 고유 구조를 따르는 것이다

## 스펙

> `## Jira 대조` 는 위 최상위 절에 있다 — `jira-research-guard` 가 `##` 수준에서만 읽는다.

### 사용자 시나리오 (Given-When-Then)

**S1. 다이어그램을 본다**
- Given 관리자가 `/admin/workflows/software-default` 편집기에 있다
- When 「다이어그램」 탭을 고른다
- Then 초안의 상태가 노드로, 전환이 화살표로 그려진다. 좌표가 없는 상태는 카테고리 열
  (TODO → IN_PROGRESS → DONE)로 자동 배치된다

**S2. 노드를 옮긴다**
- Given 다이어그램 탭이 열려 있다
- When 상태 노드를 끌어 다른 자리에 놓는다
- Then 노드가 그 자리에 남고 초안이 「저장 안 됨」으로 표시된다. 「변경 사항 저장」을 누르면
  좌표가 초안에 저장되고, 화면을 나갔다 와도 그 자리에 있다

**S3. 끌어서 전환을 만든다**
- Given 다이어그램 탭이 열려 있다
- When 상태 노드 위에 마우스를 올리면 나타나는 핸들에서 다른 상태 노드로 끈다
- Then 「전환 수정」 다이얼로그가 출발·도착이 채워진 채 열리고, 이름을 넣고 저장하면 초안에 전환이
  추가돼 화살표로 나타난다

**S4. 전환을 고친다**
- Given 다이어그램에 전환 화살표가 있다
- When 화살표를 고른다
- Then 「전환 수정」 다이얼로그가 그 전환의 값으로 열린다 (목록 탭의 「편집」과 같은 다이얼로그)

**S5. 발행하면 좌표가 남는다**
- Given 초안에 좌표가 담겨 있다
- When 발행한다
- Then `workflow_statuses.layout_x/y` 에 좌표가 반영되고, 이후 초안을 새로 뜰 때 그 좌표로 시작한다

### 기능 요구사항 (FR)

| # | 요구사항 | 근거 |
|---|---|---|
| F1 | 편집기에 **「다이어그램」 세 번째 탭**을 둔다. 기본 탭은 지금대로 「상태」 | J2 · X2 |
| F2 | 노드 = 초안의 상태. 이름 + 카테고리 색(`DESIGN.md` §C 상태 토큰) | J1 |
| F3 | 엣지 = 초안의 전환. 전환 **이름을 라벨로** 표시 | J1 |
| F4 | `layoutX`/`layoutY` 가 `null` 인 상태는 **카테고리 열 자동 배치**로 초기 좌표를 만든다 | A2 |
| F5 | 노드를 끌어 옮길 수 있다. 놓으면 초안이 dirty 가 된다 | J1 |
| F6 | 좌표 저장은 **기존 「변경 사항 저장」**을 쓴다. 별도 저장 버튼을 만들지 않는다 | X3 |
| F7 | 노드에 **hover 하면 핸들이 나타나고**, 거기서 다른 노드로 끌면 「전환 수정」 다이얼로그가 **출발·도착이 채워진 채** 열린다 | J3 · J4 |
| F8 | 노드를 고르면 그 상태의 상세(삭제 포함)를 다이얼로그로 연다 | J1 · J7 · X1 |
| F9 | 엣지를 고르면 「전환 수정」 다이얼로그가 그 전환 값으로 열린다 | J1 · J7 · X1 |
| F10 | **self-loop**(from == to) 를 겹치지 않는 곡선으로 그린다 | J5 |
| F11 | `from == null` 전환을 종류별로 가른다 — `INITIAL` 은 **시작 노드**에서, `GLOBAL` 은 **전역 표시**로. 문자열 `null` 노드를 만들지 않는다 | J6 · learnings 223 |
| F12 | 같은 상태쌍에 전환이 여럿이면 **간선을 벌려** 겹치지 않게 그린다 | FR-WF-05 다중 전환 |
| F13 | 발행 시 초안의 좌표를 `workflow_statuses.layout_x/y` 에 내려쓴다 | A1 · X3 |
| F14 | 팬·줌·「전체 보기」 컨트롤을 둔다 | xyflow 기본 |

### 비기능 요구사항 (NFR)

- **N1. 좌표 계산은 `src/lib/workflow-layout.ts` 순수 함수로 분리한다.** jsdom 이 SVG 측정
  API(`getBBox`·레이아웃 width)를 구현하지 않아 캔버스 컴포넌트를 단위 테스트로 픽셀 검증할 수 없다.
  `lib/timeline-layout.ts` 가 세운 관례이고 Gantt ADR 이 같은 처방을 적었다. **순수 함수는 vitest ·
  실제 렌더는 Playwright**
- **N2. 접근성.** 캔버스에 `aria-label` 을 준다. 노드·엣지는 키보드로 도달 가능해야 한다
- **N3. 번들.** `@xyflow/react@12.11.6` 하나만 추가한다. peer `react >=17` 이라 React 19 와 호환
  (실측). 다이어그램 탭은 **지연 로드**해 첫 화면 번들에 안 싣는다
- **N4. 라이트/다크 양쪽**에서 노드·엣지·라벨의 대비가 유지된다

### API 인터페이스 (REST)

**신규 엔드포인트 0.** 기존 계약 2곳이 필드만 늘어난다.

| 엔드포인트 | 변화 |
|---|---|
| `GET /api/v1/workflows/{key}/draft` | 응답 `definition.states[]` 에 `layoutX`·`layoutY` (`number \| null`) 추가 |
| `PUT /api/v1/workflows/{key}/draft` | 요청 `definition.states[]` 에 같은 2필드 수용 |
| `POST /api/v1/workflows/{key}/publish` | **계약 불변.** 서버가 초안에 담긴 좌표를 내려쓴다 |

### 데이터 모델 변경

- **마이그레이션 0.** `workflow_statuses.layout_x`·`layout_y` 는 V203 에 이미 있고
  `init_codegen.sql` 미러도 있다(실측). **새 SQL 파일을 만들지 않는다**
- `workflow_drafts.definition` 은 JSONB 라 필드 추가에 DDL 이 필요 없다
- ★ **기존 초안 행에는 그 두 필드가 없다.** Kotlin `DraftStateDto` 는 기본값 `null`, Zod 는
  `.nullable()` 로 받되 **`.optional()` 은 쓰지 않는다** — 응답에 항상 실리게 해서 「빠진 것」과
  「없는 것」을 가른다

### 엣지 케이스

| # | 상황 | 처리 |
|---|---|---|
| E1 | 좌표가 전혀 없는 기존 초안 | 전량 자동 배치. 저장 전까지 초안은 dirty 가 **아니다** — 자동 배치는 표시일 뿐 편집이 아니다 |
| E2 | 상태를 지우면 그 상태에 붙은 전환 | 초안 리듀서의 `removeState` 가 이미 전환을 함께 지운다(실측). 캔버스는 그 결과를 그린다 |
| E3 | 상태를 새로 추가 | 좌표 `null` → 자동 배치가 빈자리를 준다 |
| E4 | 같은 상태쌍 다중 전환 | F12 — 간선 offset. 겹치면 사용자가 어느 것을 고르는지 알 수 없다 |
| E5 | self-loop | F10 — 곡선. 직선이면 노드에 가려 안 보인다 |
| E6 | `GLOBAL` 전환 | 출발 노드가 없다. 개별 화살표를 그리지 않고 **전역 배지**로 표시한다 — 모든 노드에서 선을 뽑으면 화면이 못 읽게 된다 |
| E7 | `INITIAL` 전환 | 시작 노드(작은 원)에서 도착 상태로. mermaid 의 `[*]` 와 같은 의미 |
| E8 | 드래그 중에 다른 세션이 저장 | 저장 시 `baseVersion` CAS 가 409 를 낸다 — 기존 `DraftConflictBanner` 가 이미 처리한다. **새로 만들지 않는다** |
| E9 | 좌표가 음수·비정상값 | 저장 시 유한수(finite)만 허용. `NaN`·`Infinity` 는 400 |
| E10 | 발행 시 초안에서 빠진 상태의 좌표 | `replaceDefinition` 이 편성을 갈아 끼우므로 그 행이 사라진다. 별도 처리 없음 |
| E11 | 캔버스 탭에서 「초안 폐기」 | 기존 동작 그대로 — 폐기 후 발행본 기준으로 다시 그린다 |
| E12 | 잠긴 워크플로우(`is_locked`) | 드래그·전환 생성 비활성. 보기만 된다 |

### 제약 조건

- **컴포넌트 200줄 상한**(`DEVELOPMENT.md:73`). 캔버스는 셸/노드/엣지/레이아웃으로 나눈다
- **신규 의존성은 `@xyflow/react` 하나뿐.** 미니맵·컨트롤은 그 패키지에 포함돼 있어 추가 설치가 없다
- **즉사 계약** — 기존 `role="dialog"` 이름 4종(`워크플로우 발행` · `기본값으로 되돌리기` ·
  `워크플로우에 추가할 상태 선택` · `전환 수정`)에 **새 이름을 추가하지 않는다.** 전환 생성은
  `전환 수정` 다이얼로그를 재사용한다
- **새 탭 이름은 `'상태'`·`'전환'` 을 substring 으로 포함하면 안 된다** —
  `workflow-editor.spec.ts:89` 가 `getByRole('tab', {name:'전환'})` 로 non-exact 매칭한다.
  **「다이어그램」은 안전**(실측)
- **`WorkflowDiagram.tsx`(mermaid) 를 지우지 않는다.** `/workflows/{key}` 읽기 전용 상세에 그대로
  남는다 — 편집기는 `/admin/workflows/{key}` 라 **다른 라우트고 strict-mode 충돌이 없다**(실측)
- **worktree 에서 `pnpm install` 금지.** 새 의존성 설치 방법은 아래 「설치 절차」를 따른다

### 설치 절차 (이 PR 최대 위험 — 함정 5)

worktree 에서 `pnpm install` 을 돌리면 main 의 `node_modules/.modules.yaml` 을 덮어써 main 을
망가뜨린 전례가 있다(2026-07-17, 3일간 8회 머지). worktree 의 `node_modules` 는 **main 을 가리키는
심볼릭**이라 설치는 반드시 **main 체크아웃에서** 한다.

1. main 체크아웃에서 `pnpm --filter web add @xyflow/react`
2. 바뀐 `apps/web/package.json` · `pnpm-lock.yaml` 을 worktree 로 **복사**해 커밋
3. main 체크아웃의 그 두 파일은 **되돌린다**(`git restore`) — main 에 미커밋을 남기지 않는다
4. 설치 후 `node_modules/.modules.yaml` 이 유령 경로를 안 박았는지 확인

### 측정 가능한 완료 기준

1. **`docs/plan/product/project-workflow.md` §2.7 의 D8 체크박스 `[x]`** + 같은 파일 §2 진척 줄 갱신
2. **E2E 신규** — 「다이어그램 탭에서 노드를 끌어 옮기고 저장하면 그 자리에 남는다」 +
   「핸들에서 끌어 전환을 만들면 목록 탭에도 나타난다」 2시나리오
3. **기존 E2E 무손상** — `workflow.spec.ts`(mermaid 4건) · `workflow-editor.spec.ts`(4건) ·
   `workflow-publish.spec.ts`(5건, **P4b·D7 정본 포함**) 전량 통과
4. `pnpm verify` · `pnpm test:workflow` · doc-index · `verify-master-plan` 초록 (**종료 코드로 판정**)
5. **브라우저 눈확인 — 라이트/다크 양쪽.** 절대 규칙 14 ui 시각 검증 트랙. 볼 것 —
   ① 노드 카테고리 색 대비 ② 엣지 라벨 가독성 ③ self-loop 곡선이 노드에 안 가림
   ④ 다중 전환 간선이 벌어짐 ⑤ hover 핸들이 보임
6. **뮤테이션 3종** — 자동 배치 규칙 · self-loop 곡선 분기 · 좌표 발행 내려쓰기가 각각 「그것만」 red

## Sanity Check

**❓ 발견 1건 — 스스로 보강했다.** 초안 좌표를 **누가 자동 배치 결과로 채우는가**가 처음 스펙에
없었다. 자동 배치 결과를 곧바로 초안에 써 넣으면 **화면을 열기만 해도 초안이 dirty** 가 되고,
사용자는 아무것도 안 했는데 「저장 안 됨」을 본다. → **E1 에 「자동 배치는 표시일 뿐 편집이 아니다」를
명시**하고, 좌표는 **사용자가 실제로 끌었을 때만** 초안에 들어가게 못박았다.

**✅ 통과.** 나머지 3항목(누락 요구사항 · 모호 표현 · 가정 누락)에 gap 없음. 근거 —
① Jira 조회 7행이 전부 FR 에 물렸고 대응 없음 2건도 준용 근거를 남겼다 ② 좌표 저장 위치의 모호함은
X3 로 확정했다 ③ 「`layout_x/y` 가 있다」·「xyflow 가 React 19 와 맞는다」·「mermaid 와 라우트가 다르다」
셋 다 **추측이 아니라 실측**이다.

**즉사 계약 체크 ✅** — 새 `role="dialog"` 0개 · 새 탭 이름 「다이어그램」이 기존 탭 이름과
substring 무충돌 · mermaid 라우트 분리 확인.

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
