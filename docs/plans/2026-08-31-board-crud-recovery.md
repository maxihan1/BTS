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

## Plan

**Jira 매핑 (§1-7 차집합 0).**
`J1→T5 · J2→T5 · J3→T1·T2·T3·T6 · J4→T1·T2·T3·T6 · J5→T3·T6 · J6 범위 밖(BC 격리상 project_key 문자열 고정 + AQL·search BC 결선 — 패리티 포기 후보)`

### Task 1. BoardRepository — updateName · softDelete

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/repository/BoardRepository.kt`, `backend/modules/agile-planning/src/test/kotlin/com/bts/agileplanning/repository/BoardRepositoryTest.kt`]
- depends-on: []
- jira: [J3, J4]

**RED**. `BoardRepositoryTest` 에 추가한다.
- `updateName 이 이름을 갱신하고 updated_at 을 올린다`
- `updateName 이 soft-deleted 보드에는 null 을 반환한다`
- `softDelete 가 deleted_at 을 채우고 이후 findById 가 null 이다`
- `softDelete 가 이미 삭제된 보드에 false 를 반환한다`
- 실패 예상. `updateName` / `softDelete` 미존재 컴파일 실패

**GREEN**. `updateSwimlaneField`(**실측 `:149-166`**)를 **그대로 미러링**한다 —
`.and(BOARDS.DELETED_AT.isNull)` 조건과 `affected == 0 → null` 관례를 동일하게.
`softDelete` 는 `Boolean` 반환.

**REFACTOR**. KDoc — soft-deleted 제외 조건을 명시.

**검증**. `./gradlew :modules:agile-planning:test --tests '*BoardRepositoryTest'`

### Task 2. BoardApplicationService — updateName · softDelete + 권한

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/application/BoardApplicationService.kt`, `backend/modules/agile-planning/src/test/kotlin/com/bts/agileplanning/application/BoardApplicationServiceTest.kt`]
- depends-on: [1]
- jira: [J3, J4]

**RED**.
- `updateName 이 공백 이름을 IllegalArgumentException 으로 거부한다`
- `updateName 이 미존재 보드에 BoardNotFoundException 을 던진다`
- `softDelete 가 미존재 보드에 BoardNotFoundException 을 던진다`

**GREEN**. `SprintApplicationService.update` 의 3-state 머지 구조를 참고하되 보드는 OCC(version)가
없으므로 단순 머지. **권한 판정은 컨트롤러가 담당**한다 — 보드의 기존 구조가 그렇다
(`loadBoardWithCreate` 가 컨트롤러 private helper 다). 스프린트와 배치가 다르니 옮기지 않는다.

**REFACTOR**. KDoc — `@throws` 전수.

**검증**. `./gradlew :modules:agile-planning:test --tests '*BoardApplicationServiceTest'`

### Task 3. BoardController — PATCH 부분 갱신 + DELETE + 권한 게이트

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/web/BoardController.kt`, `backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/web/dto/BoardRequests.kt`, `backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/web/dto/BoardResponses.kt`, `backend/modules/agile-planning/src/test/kotlin/com/bts/agileplanning/web/BoardControllerIntegrationTest.kt`]
- depends-on: [2]
- jira: [J3, J4, J5]

**RED**. `BoardControllerIntegrationTest` — **기존 400 승계를 먼저 고정**한다.
- `PATCH {} 는 400 이다` ← 🛑 **계약 완화 방지 앵커. 이 테스트를 가장 먼저 쓴다**
- `PATCH {"name":"버그 보드"} 는 swimlaneField 없이 200 이다`
- `PATCH {"name":"   "} 는 400 이다`
- `PATCH {"name":null} 은 400 이다` ← present-null 경로
- `PATCH {"swimlaneField":"ASSIGNEE"} 는 기존대로 200 이다` ← 회귀 앵커
- `PATCH {"swimlaneField":null} 은 400 이다` ← 기존 동작 유지
- `PATCH {"swimlaneField":"foo"} 는 400 이다` ← 기존 동작 유지
- `DELETE 는 204 이고 이후 GET 목록에서 사라진다`
- `DELETE 는 SOFT_DELETE 미보유 시 403 이다`
- `DELETE 는 미존재 보드에 404 이다`
- `DELETE 는 미인증 시 401 이며 보드 존재 여부를 노출하지 않는다`
- `GET /{id} 응답의 canDelete 가 SOFT_DELETE 보유자에게 true, 미보유자에게 false 다`

**GREEN**.
- **현재 상태 실측** — `PATCH /{id}` 는 `BoardController.kt:222` 에 이미 있고
  `UpdateBoardSwimlaneRequest` 를 받아 `request.swimlaneField ?: throw ResponseStatusException(BAD_REQUEST)`
  로 null 을 막는다. 이것을 부분 갱신으로 확장한다.
- `UpdateBoardSwimlaneRequest` → `UpdateBoardRequest(name: JsonNullable<String>, swimlaneField: JsonNullable<String>)`.
  선례 실측 — `web/dto/SprintRequests.kt:52-58` 의 `UpdateSprintRequest` 가 같은 3-state 를 쓰고
  `JacksonNullableConfiguration.kt` 가 `JsonNullableModule` 을 Bean 으로 이미 등록해 뒀다.
- `@field:NotBlank` 제거 — 대신 컨트롤러에서 **둘 다 absent 면 400**
- ⚠️ **`JsonNullable` present-null 주의.** `name.isPresent && name.get().isNullOrBlank()` → 400.
  presence 만 보고 통과시키면 `{"name":null}` 이 NPE 로 **500** 이 된다
  (메모리 `decorative-annotation-copied-from-sibling` — **복사 전에 red 를 본다**)
- `DELETE` 는 새 private helper `loadBoardWithSoftDelete(id)` — 기존 `loadBoardWithCreate` 와 동형.
  **순서 고정** actor(401) → 존재(404) → 권한(403). 뒤집으면 403/404 의미가 바뀌고 로컬은 항상
  허용이라 안 보인다 (메모리 `permission-assert-before-existence-makes-403-lie`)
- `BoardDetailResponse` 에 `canDelete: Boolean` 추가. `getBoard` 핸들러가
  `permissionResolver.hasPermission(actor, SOFT_DELETE, IssueScope.Project(board.projectKey))` 를 전달.
  **`BoardSummaryResponse`(목록)에는 넣지 않는다** — `⋯` 메뉴는 현재 보드에만 붙는다

**REFACTOR**. 컨트롤러 클래스 KDoc 의 엔드포인트 목록에 DELETE 추가 + 권한 표기 갱신.

**검증**. `./gradlew :modules:agile-planning:test --tests '*BoardControllerIntegrationTest'`

### Task 4. 프론트 API · 훅

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/api/boards.ts`, `apps/web/src/hooks/use-boards.ts`, `apps/web/src/mocks/board-handlers.ts`, `apps/web/src/mocks/board-fixtures.ts`, `apps/web/src/api/boards.test.ts`]
- depends-on: [3]
- jira: [J3, J4]

**RED**. `boards.test.ts` —
- `updateBoardName 이 PATCH 로 {name} 만 보낸다`
- `deleteBoard 가 204 를 처리한다`
- `404·403 이 ApiError 로 온다`
- `BoardDetail Zod 스키마가 canDelete 를 파싱한다`

⚠️ `canDelete` 를 Zod 에 **필수**로 넣으면 백엔드가 안 보낼 때 파싱이 통째로 실패해 보드 화면이
죽는다. Task 3 과 같은 PR 이라 순서 문제는 없지만 **MSW fixture 에도 같은 Task 에서** 필드를
추가해야 한다 (learnings 2026-05-30 「Zod 응답 스키마 강화가 산재한 인라인 mock 을 깬다」).

**GREEN**. **실측 정정** — 미러링 대상 함수명은 `updateSwimlane` 이 아니라
**`updateBoardSwimlane`(`api/boards.ts:409`)** 이다. 훅은 그 mutation 패턴을 따른다.
**성공 시 `boardKeys` invalidate-only** — `setQueryData` 금지 (NFR ·
learnings 2026-05-30 「메타 mutation setQueryData 부분응답이 본문을 placeholder 로 덮는 플리커」).
MSW 핸들러도 같은 Task 에서 추가한다.

**검증**. `pnpm --filter web test -- boards`

### Task 5. 보드 스위처 — 상시 노출 + DropdownMenu 교체 + 「보드 만들기」

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/routes/projects.$projectKey.board.tsx`, `apps/web/src/routes/__tests__/projects.board.test.tsx`, `apps/web/src/i18n/board-labels.ts`]
- depends-on: [4]
- jira: [J1, J2]

**RED (동반 테스트)**. `projects.board.test.tsx` — **`:383` · `:513` · `:535` 를 재작성**한다.
- 보드 1개여도 스위처가 렌더된다 ← 신규
- 스위처를 열면 보드 목록이 `role="menuitemradio"` 로 나온다
- 항목을 고르면 `navigate` 가 `?board=<id>` 로 호출된다 ← `:535` 의 조건부 후퇴를 제거하고 무조건 단언
- `canCreate=false` 면 「보드 만들기」 항목이 **DOM 에 없다** (`queryBy...toBeNull`)

🛑 **즉사 계약 봉합 (§5 사전 grep 결과 — 이 Task 범위)**.
`:383` · `:513` · `:535` 세 곳이 전부 `expect(screen.getByRole('combobox')).toBeInTheDocument()` 이다.
`getByRole` 은 **단수형**이라 화면에 combobox 가 2개 이상이면 다중 매치로 즉사한다.
스위처를 `>= 1` 로 완화하면 보드 1개 픽스처에서도 스위처가 렌더되므로,
**그 3곳이 지금 무엇을 세고 있었는지 먼저 확인하고 이름 있는 셀렉터로 좁힌다.**

**GREEN**.
- **실측 좌표** — `boards.length >= 2` 조건은 `:518` 이다(선행 플랜의 `:520` 은 어긋난다).
  이것을 `>= 1` 로 바꾼다. 빈 상태 분기 `:498` 은 건드리지 않는다
- `BoardSelectorDropdown` 을 `components/ui/dropdown-menu.tsx` 로 교체 —
  `DropdownMenuTrigger`(현재 보드 이름) + `DropdownMenuRadioGroup`(보드 목록) +
  `DropdownMenuSeparator` + `DropdownMenuItem`(「보드 만들기」, `canCreate` 일 때만)
- 「보드 만들기」 → 다이얼로그로 기존 `CreateBoardForm` **재사용**. 신규 폼을 만들지 않는다

**REFACTOR**. 라벨은 `i18n/board-labels.ts` 에. 하드코딩 금지.

**검증**.
- 기존 E2E 전수(실측 확인 7개) — `board-kanban.spec.ts` · `board-filter.spec.ts` ·
  `board-reorder.spec.ts` · `board-wip-swimlane.spec.ts` · `board-swimlane-field-change.spec.ts` ·
  `board-epic-swimlane.spec.ts` · `quick-filter.spec.ts`
- 눈확인. 보드 **1개 / 2개** 상태의 스위처 — 라이트·다크 양쪽

### Task 6. 보드 `⋯` 메뉴 — 이름 변경 · 삭제 + E2E

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/routes/projects.$projectKey.board.tsx`, `apps/web/src/routes/__tests__/projects.board.test.tsx`, `apps/web/e2e/board-manage.spec.ts`, `apps/web/src/i18n/board-labels.ts`]
- depends-on: [5]
- jira: [J3, J4, J5]

**RED (동반 테스트)**.
- 유닛. `canCreate=false` → 「이름 변경」 부재 · `canDelete=false` → 「보드 삭제」 부재 (**disabled 아님**)
- 유닛. 삭제 확인 후 `deleteBoard` 호출 + 남은 보드로 navigate (E2)
- 유닛 **S7**. 삭제가 403/404 로 실패하면 **다이얼로그가 열린 채** `error` 가 창 안에 뜬다
- 유닛 **E7**. `confirming` 중에는 취소·Esc·오버레이로 창이 닫히지 않는다
- E2E 신규 `board-manage.spec.ts`. S1 두번째 보드 생성 → S2 전환 → S3 이름변경 → S4 삭제 →
  S5 빈 상태 복귀

**GREEN**.
- 보드 이름 옆 `⋯` — `DropdownMenu` 재사용. 항목 2개, **권한별 조건부 렌더**(disabled 아님)
- 삭제 확인 = `components/ui/confirm-dialog.tsx` 재사용.
  ★ **PR #410 계약으로 사용법이 바뀌었다 — 선행 플랜의 옛 사용법을 쓰지 않는다.**
  - `open` / `onOpenChange` 로 **소비자가 상태를 쥔다**
  - **`onConfirm` 이 창을 닫지 않는다.** 소비자가 **성공했을 때만** `onOpenChange(false)` 를 부른다
  - `confirming` 을 mutation `isPending` 에 물린다 — 확인 버튼만이 아니라
    **취소·Esc·오버레이·X 까지 전부 잠긴다**. 실패가 갈 곳을 구조적으로 보장하기 위해서다
  - 실패 사유는 `error` prop 으로 **창 안에** 싣는다. 소비자 화면의 배너는 모달 오버레이가 가린다
  - `title="보드 삭제"` — **화면 내 고유**여야 한다. 중복이면 Playwright
    `getByRole('dialog', { name })` 가 strict mode 로 즉사한다
  - `description` 에 **"이슈는 삭제되지 않습니다"** · `destructive`
- 삭제 노출 판정 = `boardDetail.canDelete` (Task 3 이 추가한 필드). 이름 변경 노출 = 기존 `canCreate`.
  **`useProjectPermissions` 는 손대지 않는다** — BC 경계 무접촉

**검증**.
- `pnpm --filter web test` · `pnpm --filter web test:e2e`
- 눈확인. `⋯` 메뉴 열림 · 삭제 확인 다이얼로그 · **삭제 실패 상태(창 안 error)** — 라이트·다크

## Plan 메타

- **task 수** 6 · **예상 wave** 6 (T1→T2→T3→T4→T5→T6 전부 직렬 — `depends-on` 이 사슬이고
  T5·T6 이 `board.tsx`·`projects.board.test.tsx`·`board-labels.ts` 를 공유해 파일 겹침으로도 직렬화된다)
- **구현 규율** TDD red-first (T2). `test:` 커밋 → `feat:` 커밋 순서가 로그에서 대조된다.
  T5·T6 은 ui 시각 검증 트랙이라 RED 라벨을 「동반 테스트」 명세로 읽는다 — red-first 순서 강제 없음
- **추가 검증** typecheck · ktlint · detekt · vitest · playwright
- **마이그레이션 0 · 신규 의존성 0 · 신규 프리미티브 0 · BC 1개(agile-planning)**

## 리뷰 결과 (← /bts-review-plan 채움)
