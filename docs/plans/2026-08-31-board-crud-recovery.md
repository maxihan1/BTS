# 보드 CRUD 회수 — 이름 변경 · 삭제 API + 스위처 상시 노출 (로드맵 A1)

> 티어: T2
> slug: board-crud-recovery
> type: api
> agent: backend-engineer
> 생성: 2026-08-31

## Brief

**사용자 원문.** 보드 CRUD 회수 (로드맵 A1) — 보드 이름 변경 PATCH + 보드 삭제 DELETE API 신설 ·
보드 스위처 상시 노출 + 「보드 만들기」 진입점 · 보드 `⋯` 메뉴(이름 변경·삭제) + E2E.
BC = agile-planning 단독. 마이그레이션 0.

**classify 결과.** type=`api` · agent=`backend-engineer` · tier=**T2** · primary_bc=`agile-planning`.
표면 근거 — `backend/modules/agile-planning/src/main/**`(BE_MAIN) + 신규 REST 엔드포인트 2개(API)
+ `apps/web/src`(FE_SRC) 혼합이라 최고 티어 T2 가 지배한다.

**FR.** FR-BD-01 의 미회수 조항(**FR-BD-01-2** 「수정/삭제는 후속」)을 닫는다.
**신규 FR 아님 · FR 총수 불변 143** (PR #175 「범위 확장이나 FR 총수 불변」 선례와 동형).

- **FR-BD-01-2a** 보드 이름 변경. `PATCH /api/v1/boards/{id}` 부분 갱신. 권한 CREATE
- **FR-BD-01-2b** 보드 소프트 삭제. `DELETE /api/v1/boards/{id}`. 권한 SOFT_DELETE
- **FR-BD-01-2c** 보드 생성 진입점을 보드 존재 여부와 무관하게 제공
- **FR-BD-01-2d** 권한 미보유 시 관리 액션을 **렌더하지 않는다** (disabled 아님)

**승계 출처 (재작성 금지).**
- 스펙 + Task 1~6 정본 — `~/.claude/plans/1-serialized-sketch.md` (2026-08-25)
- 상위 로드맵 + 병행 판정 — `~/.claude/plans/mellow-mixing-avalanche.md` (2026-08-31 실측)

**착수 시 반영할 실측 3건.**
1. `ConfirmDialog` 는 PR #410 으로 **제어 컴포넌트**가 됐다 — `open`/`onOpenChange` 제어이고
   `onConfirm` 이 다이얼로그를 닫지 않는다. 선행 플랜 Task 6 은 그 이전 계약을 참조하므로 갱신 대상이다.
2. `jira-research-guard` 의 `CUTOFF = '2026-08-27'` — 이 파일은 검사 대상이다.
   「Jira 대조」 절 **안에** 허용 도메인 출처 URL 이 있어야 한다.
3. `board.tsx` 분기 좌표 재실측 완료 — `boards.length === 0` 은 `:498`, `>= 2` 는 `:518`.

**병행 제약.** 다른 세션이 PR #414(FR-WF-07 D2·D4·D5 · issue-tracking BC)를 진행 중이다.
파일 교차 0 을 실측 확인했다 — `apps/web`·`agile-planning` 접촉 **0파일**.

## Jira 대조 (전 타입 필수)

**조회 방식 — 계약 §1-0 재사용 승계다. 이번 세션은 실물을 조회하지 않았다.**
아래 J1~J6 은 **선행 플랜 `1-serialized-sketch.md`(조회일 2026-08-25)** 가 남긴 근거를
계약 §1-0 「같은 표면을 두 번 조사하지 않는다」에 따라 출처·조회일 그대로 승계한 것이다.
이 세션의 실행 주체에게 web 조회 도구가 없으므로 **새 조회는 수행하지 않았다**
(`.claude/agents/frontend-engineer.md` — 「web 도구가 없으므로 직접 조회하지 말고, 근거가
부족하면 구현을 멈추고 보고한다」). A1 의 범위는 선행 플랜이 조회한 범위와 **동일**하다 —
새로 건드리는 조작이 없어 추가 조회 대상이 0건이다. **스프린트 편집·삭제는 A2 범위**이므로
이 문서가 근거를 갖지 않는다.

### 근거 표 — 전부 Cloud (company-managed)

| # | 원문 근거 | 출처 | 조회일 |
|---|---|---|---|
| **J1** | 보드는 프로젝트당 **N개** 가질 수 있다. 1개 고정은 team-managed 쪽 제약이다 | [Configure a company-managed board](https://support.atlassian.com/jira-software-cloud/docs/configure-a-company-managed-board/) | 2026-08-25 |
| **J2** | 보드 생성 진입점은 ① 사이드바 프로젝트 hover `+` ② 전역 Boards 디렉터리 **2곳**이고, 보드 존재 여부와 무관하게 상시 제공된다 | [Create a board](https://support.atlassian.com/jira-software-cloud/docs/create-a-board/) | 2026-08-25 |
| **J3** | 보드 **이름 변경**은 설정 화면에서 이름 옆 연필 인라인 편집으로 한다 | [Configure a company-managed board](https://support.atlassian.com/jira-software-cloud/docs/configure-a-company-managed-board/) | 2026-08-25 |
| **J4** | 보드 **삭제**는 Boards 디렉터리 행 `⋯` → Delete 다. 설정 화면이 아니다. **이슈는 남는다** | [How to Delete a Software Board in Jira Cloud](https://support.atlassian.com/jira/kb/how-to-delete-a-software-board-in-jira-cloud/) | 2026-08-25 |
| **J5** | 권한 — 설정은 Board admin 또는 Jira admin, 삭제는 Board admin 또는 **Project admin**. 권한이 없으면 **메뉴 항목 자체가 부재**하다(비활성이 아니다) | [Configure a company-managed board](https://support.atlassian.com/jira-software-cloud/docs/configure-a-company-managed-board/) | 2026-08-25 |
| **J6** | 저장된 필터 기반 보드 생성 — 소스로 프로젝트 또는 저장된 필터를 고른다 | [Create a board based on filters](https://support.atlassian.com/jira-software-cloud/docs/create-a-board-based-on-filters/) | 2026-08-25 |

### 채택 판정

| # | 판정 | 사유 |
|---|---|---|
| J1 | **채택** | 스위처 노출 조건을 `>= 2` 에서 `>= 1` 로 완화한다. 보드가 1개여도 N개 모델임을 드러낸다 |
| J2 | **채택 (형태 변경)** | 진입점 2곳을 **스위처 드롭다운 하나로 접는다** — X1 참조 |
| J3 | **채택 (위치 변경)** | 설정 화면이 없으므로 `⋯` 메뉴에 둔다 — X2 참조 |
| J4 | **채택 (위치 변경)** | 디렉터리가 없으므로 `⋯` 메뉴에 둔다. **「이슈는 남는다」는 그대로 채택** — 소프트 삭제 |
| J5 | **채택 (권한 근사)** | 「권한 없으면 렌더하지 않는다」를 **글자 그대로 채택**한다. 권한 주체는 X3 참조 |
| J6 | **범위 밖** | BC 격리상 `project_key` 가 문자열로 고정돼 있고 AQL·search BC 와 얽힌다. **패리티 포기 후보**로 남긴다 |

### 의도적 편차

- **X1 — 진입점을 스위처 하나로 접는다.** BTS 에는 전역 Boards 디렉터리도, 사이드바 프로젝트
  hover `+` 도 **없다**. 계약 §1-5 에 따라 Jira 를 흉내내지 않고 **ADS v2 드롭다운 패턴을 준용**한다.
- **X2 · X3 — 삭제 위치와 권한.** 선행 플랜의 deviation D-2 · D-1 을 그대로 승계한다.
  위치는 디렉터리 부재로 `⋯` 메뉴다. 권한은 BTS 에 per-board 관리자 개념이 없어
  `IssuePermission.SOFT_DELETE` 로 근사하며, 도입하려면 `created_by` 마이그레이션 +
  shared-kernel 포트라 **T3 승격**이 된다.
  ⚠️ **같은 BC 안에 비대칭이 생긴다** — `SprintApplicationService.softDelete` 는 `CREATE` 를 쓴다.
  보드만 한 단계 높은 이유는 「보드 = 여러 사람이 공유하는 뷰」이기 때문이다.
  로드맵 D 의 보드 관리자 ADR 에서 스프린트 쪽과 함께 재정렬한다.
- **X4 — Scrum/Kanban 타입 선택 없음.** 3단계(로드맵 C·D) 범위다.

### 조회하지 못한 것

없다. A1 범위 전체가 선행 플랜의 조회 범위 안에 있다.
**단, 이 문서의 근거는 2026-08-25 시점이다** — Jira Cloud 문서가 그 뒤 바뀌었는지는 확인하지 않았다.

## 도메인 정리

**BC.** `agile-planning` 단독. 다른 BC 를 import 하지 않는다.

**영향 엔티티.** `Board`(`boards` 테이블) 하나. `board_columns` · `sprint_issues` 는 무접촉이다.

**새 용어.** 없다. 「보드」·「스위처」는 `glossary.md` 에 이미 있는 용어를 그대로 쓴다.

**기존 결정 충돌.** 없다.

**관련 ADR.** **보드 CRUD 를 직접 다룬 ADR 은 0건이다.**
인접한 것으로 `docs/decisions/2026-07-30-fr-ux-08-project-switcher.md` 가 있으나 그것은
**프로젝트** 스위처이고 이 작업의 **보드** 스위처와 별개 컴포넌트다. 규약(드롭다운 · 권한 미보유 시
미렌더)만 참고하고 결정을 승계하지 않는다.

**learnings 연결.** 2026-07-17 「도메인·서비스·repo 가 다 있어도 REST 노출이 없으면 기능이 없는
것이다」의 **한 칸 뒤 버전**이다 — 이번엔 REST 까지 있는데(`PATCH /{id}` 가 이미 있다) 프론트
소비자가 없다. 보드·agile-planning 키워드로 잡히는 다른 learnings 헤딩은 0건이다.

---

## 스펙

### 사용자 시나리오 (Given-When-Then)

**S1. 두 번째 보드 생성**
Given 프로젝트 ATLAS 에 보드 「개발 보드」 1개가 있고 나는 CREATE 권한이 있다
When 보드 스위처를 열어 「보드 만들기」를 고르고 이름 「버그 보드」를 넣어 생성한다
Then 보드가 생성되고 그 보드로 이동하며, 스위처에 두 보드가 모두 보인다

**S2. 보드 전환**
Given 보드가 2개 있다
When 스위처에서 다른 보드를 고른다
Then `?board=<id>` 로 이동하고 그 보드의 컬럼·카드가 렌더된다 (기존 동작 유지)

**S3. 이름 변경**
Given 보드를 보고 있고 CREATE 권한이 있다
When 보드 `⋯` 메뉴 → 「이름 변경」 → 새 이름 제출
Then 200 + 헤더·스위처의 이름이 갱신된다

**S4. 삭제**
Given 보드가 2개 있고 나는 SOFT_DELETE 권한이 있다
When `⋯` → 「보드 삭제」 → 확인 다이얼로그에서 확인
Then 204, 목록에서 사라지고 남은 보드로 이동한다. **이슈는 삭제되지 않는다**

**S5. 마지막 보드 삭제**
Given 보드가 1개뿐이다
When 그 보드를 삭제한다
Then 보드 0개 → 기존 `CreateBoardForm` 빈 상태로 복귀한다 (신규 코드 없음)

**S6. 권한 없음**
Given 나는 BROWSE 만 있다
When 보드를 연다
Then 스위처는 보이되 「보드 만들기」·「이름 변경」·「보드 삭제」 항목이 **렌더되지 않는다** (disabled 아님)

**S7. 삭제 실패 (신규 — `ConfirmDialog` 계약 변경으로 추가)**
Given 확인 다이얼로그에서 확인을 눌렀고 서버가 403/404 를 낸다
When 응답이 도착한다
Then **다이얼로그는 열린 채**로 그 안에 실패 사유가 뜬다. 성공했을 때만 닫힌다

### 기능 요구사항 (FR)

FR-BD-01 의 미회수 조항(**FR-BD-01-2** 「수정/삭제는 후속」)을 닫는다.
**신규 FR 아님 · FR 총수 불변 143** (PR #175 「범위 확장이나 FR 총수 불변」 선례와 동형).

- **FR-BD-01-2a** 보드 이름 변경. `PATCH /api/v1/boards/{id}` 부분 갱신. 권한 CREATE
- **FR-BD-01-2b** 보드 소프트 삭제. `DELETE /api/v1/boards/{id}`. 권한 SOFT_DELETE
- **FR-BD-01-2c** 보드 생성 진입점을 보드 존재 여부와 무관하게 제공
- **FR-BD-01-2d** 권한 미보유 시 관리 액션을 **렌더하지 않는다** (disabled 아님)

### 비기능 요구사항 (NFR)

- 스위처 상시 노출로 **추가 API 왕복이 없다** — `useBoards(projectKey)` 는 이미 무조건 호출된다
- 삭제·이름변경 후 `boardKeys` **invalidate-only**. **`setQueryData` 로 덮지 않는다**
  (learnings 2026-05-30 「메타 mutation setQueryData 부분응답이 본문을 placeholder 로 덮는 플리커」)
- 기존 성능 목표(보드 200건 p95 1.5s)에 영향 없음 — 목록 쿼리 불변

### API 인터페이스 (REST)

#### `PATCH /api/v1/boards/{id}` — 부분 갱신으로 확장

**실측 (2026-08-31).** 이 엔드포인트는 **이미 존재한다** — `BoardController.kt:222`
`updateSwimlaneField`. 바디는 `UpdateBoardSwimlaneRequest` 이고, 컨트롤러가
`request.swimlaneField ?: throw ResponseStatusException(BAD_REQUEST)` 로 **null 을 400 으로 막는다.**
즉 이름만 바꾸려 해도 swimlaneField 를 보내야 하는 상태다.

→ **같은 BC 가 이미 쓰는 `JsonNullable` 3-state 패턴으로 교체**한다.
선례 실측 확인 — `web/dto/SprintRequests.kt:52-58` 의 `UpdateSprintRequest` 가
`name: JsonNullable<String> = JsonNullable.undefined()` 형태를 쓰고,
`JacksonNullableConfiguration.kt` 가 `JsonNullableModule` 을 Bean 으로 이미 등록해 뒀다.

```kotlin
data class UpdateBoardRequest(
    val name: JsonNullable<String> = JsonNullable.undefined(),
    val swimlaneField: JsonNullable<String> = JsonNullable.undefined(),
)
```

| 요청 | 응답 |
|---|---|
| `{"name":"버그 보드"}` | 200 + `BoardMetaResponse` |
| `{"swimlaneField":"ASSIGNEE"}` | 200 (기존 계약 유지) |
| `{"name":"x","swimlaneField":"NONE"}` | 200 (둘 다 반영) |
| `{}` | 400 `AGILE_VALIDATION_FAILED` — **기존 400 을 「최소 1필드」 규칙으로 승계** |
| `{"name":"   "}` · `{"name":null}` | 400 |
| `{"swimlaneField":null}` · `{"swimlaneField":"foo"}` | 400 (기존 동작 유지) |

권한 `IssuePermission.CREATE` — 기존 `loadBoardWithCreate(id)` 헬퍼 그대로 재사용.

🛑 **RED 앵커.** `{} → 400` 을 **먼저** 고정한다. `@NotBlank` 제거가 조용한 계약 완화가 되지
않게 막는 유일한 장치다. 「최소 1필드」 규칙이 없으면 빈 바디가 200 을 받는다.

#### `DELETE /api/v1/boards/{id}` — 신설

**실측 (2026-08-31).** `BoardController` 의 매핑은 `@PostMapping` · `@GetMapping("/{id}")` ·
`@GetMapping` · `@PostMapping("/{id}/cards/{issueKey}/move")` · `@PatchMapping("/{id}")` ·
`@PatchMapping("/{id}/columns/{columnId}")` **6개뿐이고 DELETE 는 없다.** 신설이 맞다.

| 상황 | 응답 |
|---|---|
| 성공 | **204 No Content** |
| 미인증 | 401 |
| SOFT_DELETE 미보유 | 403 `AGILE_ACCESS_DENIED` |
| 미존재·이미 삭제됨 | 404 `AGILE_BOARD_NOT_FOUND` |

처리 순서는 기존 계약 그대로. actor 추출(401) → 보드 메타 조회(404) → 권한(403) → 동작.
**존재 probe 차단** (`BoardController` KDoc sec CONCERN-4).
⚠️ 순서가 뒤집히면 403/404 의미가 바뀌고 로컬은 항상 허용이라 안 보인다
(메모리 `permission-assert-before-existence-makes-403-lie`).

#### `GET /api/v1/boards/{id}` — 응답에 `canDelete` 추가

삭제 항목 노출 판정에 필요한 `SOFT_DELETE` 보유 여부를 **agile-planning 이 직접 내려준다.**
`BoardController` 는 이미 `IssuePermissionResolver` 를 주입받으므로
`hasPermission(actor, SOFT_DELETE, IssueScope.Project(board.projectKey))` 한 번이면 된다.

**왜 `useProjectPermissions` 에 키를 더하지 않는가.** 그 경로는 **identity-access** 에 있어
BC 를 넘고 보안 표면을 건드린다. 보드 응답에 실으면 **한 PR = 한 BC** 가 유지된다.

⚠️ `canDelete` 는 실제로는 **프로젝트 스코프** 권한이라 보드마다 값이 같다. 의미론적 타협이고,
로드맵 D 에서 per-board 관리자 모델이 들어오면 그때 진짜 보드 스코프가 된다.
`BoardSummaryResponse`(목록)에는 **넣지 않는다** — `⋯` 메뉴는 현재 보드에만 붙는다.

### 데이터 모델 변경

**없다. 마이그레이션 0.**
`boards.deleted_at` 이 `V500` 에 이미 있고 `findById` · `findAllByProjectKey` 가 이미 필터링한다.

### 엣지 케이스

| # | 상황 | 처리 |
|---|---|---|
| E1 | 마지막 보드 삭제 | 보드 0개 → 기존 `CreateBoardForm` 빈 상태(`board.tsx:498`)로 복귀. **신규 코드 0** |
| E2 | 현재 보고 있는 보드를 삭제 | 남은 보드 중 첫 번째로 이동. 없으면 E1 |
| E3 | 이미 삭제된 보드에 DELETE | 404 (`deleted_at` 필터가 `findById` 에 이미 있다) |
| E4 | 이름을 공백으로 변경 | 400 |
| E5 | 빈 바디 `{}` | 400 — **RED 앵커** |
| E6 | 삭제 중 실패(403/404) | 다이얼로그가 **열린 채** `error` prop 으로 사유 표시. S7 |
| E7 | 삭제 중 이중 제출 | `confirming` prop 이 확인 버튼과 **닫힘 경로 전부**를 잠근다 |
| E8 | 권한 미보유 | 메뉴 항목 자체를 렌더하지 않는다. disabled 아님 (FR-BD-01-2d) |

### 제약 조건

- **BC 격리** — agile-planning 단독. identity-access 를 import 하지 않는다
- **마이그레이션 0** — 스키마 무접촉
- **신규 의존성 0** — `JsonNullable` 은 이미 있는 `jackson-databind-nullable`
- **신규 프리미티브 0** — 계약 §4 재사용 자산만 소비

### 🛑 즉사 계약 실측 결과 (§5 사전 grep — 2026-08-31 수행)

**결과가 0이 아니다. 아래 3곳은 이번 범위에 포함한다.**

| 좌표 | 어서션 | 위험 |
|---|---|---|
| `projects.board.test.tsx:383` | `getByRole('combobox')` | **이름 없는 단수 셀렉터** |
| `projects.board.test.tsx:513` | `getByRole('combobox')` | 〃 |
| `projects.board.test.tsx:535` | `getByRole('combobox')` | 〃 |

`getByRole('combobox')` 는 **단수형**이라 화면에 combobox 가 2개 이상이면 다중 매치로 즉사한다.
스위처 노출 조건을 `>= 2` 에서 `>= 1` 로 완화하면 **보드 1개인 픽스처에서도 스위처가 렌더**되므로,
그 테스트들이 지금까지 세던 combobox 가 무엇이었는지 확인하고 **이름 있는 셀렉터로 좁힌다.**

**재사용 자산(계약 §4) — 새로 만들지 않는다.**
`components/ui/dropdown-menu.tsx` · `components/ui/confirm-dialog.tsx` · `CreateBoardForm` ·
`useProjectPermissions`.

### 측정 가능한 완료 기준

1. `PATCH /api/v1/boards/{id}` 가 `{"name":"x"}` 만으로 200 을 낸다
2. 같은 엔드포인트가 `{}` 에 **400** 을 낸다 (RED 앵커가 green 으로)
3. `DELETE /api/v1/boards/{id}` 가 204 를 내고 이슈는 남는다
4. 권한 미보유 actor 에게 `⋯` 메뉴 항목이 **DOM 에 없다** (disabled 아님)
5. 보드 1개일 때 스위처가 렌더된다
6. 기존 `board-*` E2E 와 `projects.board.test.tsx` 전량 green
7. `pnpm verify` · `./gradlew :modules:agile-planning:test ktlintCheck detekt` green

---

## Sanity Check

### 선행 플랜 좌표 전수 재실측 (2026-08-31)

| 선행 플랜(8/25) 서술 | 실측 | 판정 |
|---|---|---|
| `SprintRequests.kt:52-57` | `web/dto/SprintRequests.kt:52-58` | **경로 축약** — 줄번호는 정확 |
| `IssuePermission.kt:48-54` | enum 값 `:54` · 「이름보다 넓은 의미」 KDoc `:22-24` | **KDoc 좌표 어긋남** |
| `SprintApplicationService.softDelete:192` | `:188-192` (권한 라인이 `:192`) | 정확 |
| `BoardRepository.updateSwimlaneField:148-168` | `:149-166` | 미세 차이 |
| `projects.board.test.tsx:383·513·535` | 동일 3곳 전부 실재 | **정확** |
| `board.tsx` `length===0` / `>=2` | `:498` / `:518` | **정확** |
| `PATCH /{id}` 가 swimlaneField 전용 | `BoardController.kt:222` 확인 | **정확** |
| `DELETE /{id}` 부재 | 매핑 6개 전수 확인, DELETE 0건 | **정확** |

### ❓ 발견 1건 — 스스로 보강함

**`ConfirmDialog` 계약이 PR #410 으로 전면 교체됐고, 선행 플랜은 그 이전 계약을 참조한다.**
현재 `confirm-dialog.tsx` 실측 —

- **제어 컴포넌트**다. `open` / `onOpenChange` 로 소비자가 상태를 쥔다
- **`onConfirm` 이 다이얼로그를 닫지 않는다.** 소비자가 **성공했을 때만** 닫는다.
  종전에는 `onConfirm()` 직후 자동으로 닫아서 `confirming` 이 **구조적으로 도달 불가**였다
  (메모리 `unreachable-state-fixture-is-fake-green` 과 같은 양식이 실제로 고쳐진 자리다)
- `confirming` 은 확인 버튼만이 아니라 **취소·Esc·오버레이·X 까지 전부 잠근다**.
  실패가 갈 곳을 구조적으로 보장하기 위해서다
- `error` prop 은 창 **안**에 싣는다. 소비자 화면의 배너는 모달 오버레이가 가린다
- `title` 은 **화면 내 고유**여야 한다. 중복이면 Playwright `getByRole('dialog', {name})` 가 즉사

→ **보강 결과.** 시나리오 **S7(삭제 실패)** 를 신설하고 엣지 케이스 **E6·E7** 을 추가했다.
선행 플랜에는 이 경로가 아예 없었다 — 옛 계약에서는 확인 즉시 닫혀 실패를 보여줄 자리가 없었다.

### 남은 위험 2건

1. **PATCH 계약 완화.** `@NotBlank` 제거가 조용한 완화가 되지 않도록 `{} → 400` 을
   **RED 앵커로 먼저** 고정한다. 완료 기준 2번이 그 판정이다.
2. **「보드 만들기」 라벨 중복.** `role` 이 달라 이론상 안전하나 실측 전까지 미확정이다.
   red 면 「새 보드」로 개명한다.

### ✅ 통과

gap 4항목(누락 요구사항 · 모호 표현 · 가정 누락 · 엣지 미커버) 중 **엣지 미커버 1건**을
`ConfirmDialog` 계약 변경으로 발견해 **1회 보강**했다. Maxi 결정이 필요한 항목은 없다.

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
