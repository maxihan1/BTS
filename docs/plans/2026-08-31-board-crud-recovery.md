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

> ⚠️ **아래 `FR-BD-01-2*` 는 정본 FR ID 가 아니라 이 plan 의 내부 식별자다.**
> 저장소 전수 grep 결과 그 번호는 이 문서에만 있고 `fr-index.md` 는 `FR-BD-01` 만 등재한다.
> 하위 번호 체계를 새로 만들지 않는 이유는 Task 9 에 적었다.

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
- ⚠️ **`canDelete` 는 보드 상세 조회의 권한 판정을 1회에서 2회로 늘린다** (plan 리뷰 발견).
  `getBoard` 는 현재 `loadBoardWithBrowse(id)`(`BoardController.kt:137`)로 **BROWSE 1회**만 부르는데,
  `canDelete` 는 `SOFT_DELETE` 라 그 호출을 재사용할 수 없다. 목록(`BoardSummaryResponse`)에는
  싣지 않으므로 증가는 **상세 조회 1건당 1회**로 한정된다. 착수 시 `IssuePermissionResolver` 가
  요청 단위 캐시를 갖는지 확인하고, 없으면 부채로 등재한다

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

| 좌표 | 어서션 | 실제 위험 (리뷰 실측으로 교정) |
|---|---|---|
| `projects.board.test.tsx:383` | `getByRole('combobox')` | **role 소멸 → 0매치** |
| `projects.board.test.tsx:513` | `getByRole('combobox')` | 〃 (`:535` 와 **한 테스트**) |
| `projects.board.test.tsx:535` | `getByRole('combobox')` | 〃 + **조건부 후퇴 = 가짜 그린** |

★ **plan 리뷰가 진단을 뒤집었다.** 애초 서술은 「combobox 가 2개가 되어 다중 매치로 즉사」였으나
세 단언이 세는 대상은 **보드 스위처 자신 하나뿐**이다 — `:517` 이
`document.querySelector('select[aria-hidden="true"]')` 로 값을 바꾸는데 이것이 **Radix Select** 의
숨은 네이티브 select 다. `>= 1` 로 완화해도 combobox 는 1개라 다중 매치는 일어나지 않는다.

**진짜 위험은 role 소멸이다.** 스위처를 `DropdownMenu` 로 교체하면 Radix DropdownMenu 는
`role="combobox"` 를 내지 않아 **3개 단언이 전부 0매치 red** 가 된다. 계약 §2 의
「`프로젝트 뷰 전환`을 Radix Tabs 로 바꾸면 `role="navigation"` 소멸로 e2e 즉사」와 **동형**이다.

**폭발 반경 — E2E 0건.** `grep -rn "combobox" apps/web/e2e/` 에 보드 관련 의존이 없다
(이슈 유형 · 로그인 provider · 워크플로우 스킴 · 커맨드 팔레트뿐). 유닛 3개 단언으로 한정된다.

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
- files: [`backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/web/BoardController.kt`, `backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/web/dto/BoardResponses.kt`, `backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/web/BoardExceptionHandler.kt`, `backend/modules/agile-planning/src/test/kotlin/com/bts/agileplanning/web/BoardControllerIntegrationTest.kt`]
- depends-on: [2]
- jira: [J3, J4, J5]

**RED**. `BoardControllerIntegrationTest` — **기존 400 승계를 먼저 고정**한다.
- `PATCH {} 는 400 이다` ← 🛑 **계약 완화 방지 앵커. 이 테스트를 가장 먼저 쓴다**
- `PATCH {"name":"버그 보드"} 는 swimlaneField 없이 200 이다`
- `PATCH {"name":"   "} 는 400 이다` ← 🛑 **현재 구조로는 500 이 난다. 아래 ★ 참조**
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
- ★ **`BoardExceptionHandler` 에 `IllegalArgumentException` → 400 매핑을 신설한다** (Task 2 가 넘긴 발견).
  **실측** — `BoardExceptionHandler` 는 `@RestControllerAdvice(assignableTypes = [BoardController, BoardQuickFilterController])`(`:69`)
  로 좁혀져 있고 그 안에 `IllegalArgumentException` 핸들러가 **없다.** `:320` 의 catch-all
  `@ExceptionHandler(Exception::class)` 가 삼켜 **500 `AGILE_INTERNAL_ERROR`** 가 된다.
  같은 매핑이 `SprintExceptionHandler.kt:150` 에 있으나 그쪽 `assignableTypes` 가 Sprint 컨트롤러 3종이라
  **Board 에는 오지 않는다.**
  → 공백 이름이 400 이 되려면 이 핸들러가 필요하다. `SprintExceptionHandler` 의 매핑을
  **400 + `AGILE_VALIDATION_FAILED`** 로 동형 복제한다.
  ⚠️ **컨트롤러가 공백을 미리 막는 우회를 택하지 않는다** — 그러면 도메인 `Board.init` 의
  `require(name.isNotBlank())` 가 dead code 가 되고, 「도달 불가 조건을 지키는 테스트 = 가짜 그린」
  양식이 된다 (메모리 `unreachable-state-fixture-is-fake-green`).
  ⚠️ `BoardExceptionHandler.kt:41-44` 주석이 「새 컨트롤러는 이 목록에 포함되어야 한다(리뷰 BLOCKER-B/C)」를
  이미 적고 있다 — 같은 종류 사고의 재발 자리다

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

🛑 **즉사 계약 봉합 (§5 사전 grep + plan 리뷰 실측 — 이 Task 범위)**.

★ **리뷰가 진단 방향을 뒤집었다.** 애초 서술은 「combobox 가 2개가 되어 다중 매치로 즉사」였으나
실측 결과 **반대**다.

- `:383` · `:513` · `:535` 의 `getByRole('combobox')` 단언 **3개**가 세는 대상은
  **보드 스위처 자신 하나뿐**이다. 근거 — `:517` 이
  `document.querySelector('select[aria-hidden="true"]')` 로 값을 바꾼다.
  이것은 **Radix Select** 의 숨은 네이티브 select 다. 즉 `>= 1` 로 완화해도 combobox 는
  여전히 1개이고 **다중 매치는 일어나지 않는다.**
- 진짜 위험은 **role 소멸**이다. 이 Task 의 GREEN 이 스위처를 `DropdownMenu` 로 교체하는데,
  Radix DropdownMenu 는 `role="combobox"` 를 내지 않는다 → **3개 단언이 전부 0매치 red**.
  `:517` 의 네이티브 select 조작 코드도 함께 못 쓰게 된다.
- 계약 §2 에 **동형 선례**가 이미 있다 — 「`프로젝트 뷰 전환`을 Radix Tabs 로 바꾸면
  `role="navigation"` 소멸로 e2e 즉사」. 같은 양식이 보드 스위처에서 재현되려던 것이다.

**폭발 반경 실측 — E2E 는 안전하다.** `grep -rn "combobox" apps/web/e2e/` 결과에
보드 관련은 **0건**이다(이슈 유형 · 로그인 provider · 워크플로우 스킴 · 커맨드 팔레트뿐).
따라서 재작성 대상은 **유닛 3개 단언**으로 한정된다.

⚠️ **`:513`~`:535` 는 별개의 두 곳이 아니라 한 테스트다.** 그 안에
`if (mockNavigate.mock.calls.length > 0) { …단언… } else { …combobox 렌더만 확인… }`
조건부 후퇴가 있어 **navigate 가 한 번도 안 불려도 통과한다** — 가짜 그린이다.
재작성 시 이 `else` 분기를 **제거하고 무조건 단언**한다.

**GREEN**.
- **실측 좌표** — `boards.length >= 2` 조건은 `:518` 이다(선행 플랜의 `:520` 은 어긋난다).
  이것을 `>= 1` 로 바꾼다. 빈 상태 분기 `:498` 은 건드리지 않는다
- `BoardSelectorDropdown` 을 `components/ui/dropdown-menu.tsx` 로 교체 —
  `DropdownMenuTrigger`(현재 보드 이름) + `DropdownMenuRadioGroup`(보드 목록) +
  `DropdownMenuSeparator` + `DropdownMenuItem`(「보드 만들기」, `canCreate` 일 때만)
- 「보드 만들기」 → 다이얼로그로 기존 `CreateBoardForm` **재사용**. 신규 폼을 만들지 않는다

**★ Task 5 가 넘긴 concern 2건 (이 task 가 닫는다).**

**C1 — 생성 다이얼로그가 「보드가 없습니다」를 띄운다 (시각 결함 · 실물 재현됨).**
`CreateBoardForm` 이 빈 상태 전용 인트로 2줄을 본문에 갖고 있다
(`CreateBoardForm.tsx:94-97` — 「보드가 없습니다」 / 「이 프로젝트에 보드를 만들어…」).
Task 5 가 그 폼을 「새 보드」 다이얼로그에 재사용하면서, **보드가 있는 상태에서 열면 뒤에 칸반이
그려진 채 「보드가 없습니다」가 뜬다.** 라이트·다크 양쪽에서 재현됐다.
→ 처방은 **선택적 prop 1개**(`showEmptyStateIntro?: boolean`, **기본 `true`**)를 더해 다이얼로그에서만 끄는 것이다.
기본값을 `true` 로 두어 기존 소비처(빈 상태 경로)는 **diff 0** 으로 유지한다.

**C2 — `BoardPage` 줄수 래칫이 엄격 일치다.**
`apps/web/src/test/lint-ratchet-baseline.ts:47` 이
`"src/routes/projects.$projectKey.board.tsx::Function 'BoardPage'": 372` 로 동결돼 있고,
그 파일 헤더가 **「늘린 것뿐 아니라 줄이고 여기를 안 낮춘 것도 red」** 라고 못박은 **엄격 일치** 래칫이다.
Task 5 는 `canCreate` 를 prop 으로 내리지 않고 드롭다운이 `useProjectPermissions` 를 직접 부르게 해
372 를 유지했으나, **이 task 가 `⋯` 메뉴를 `BoardPage` 본문에 한 줄이라도 더하면 즉시 red 다.**
→ 늘어난 실측값으로 baseline 을 **함께 갱신**한다. 줄이는 데 성공하면 그때도 낮춰 적는다.
⚠️ **값을 손대기 전에 red 를 한 번 본다** — 래칫이 실제로 무는지 확인하지 않고 숫자만 맞추면
그 래칫은 그 뒤로 아무것도 지키지 않는다.

**REFACTOR**. 라벨은 `i18n/board-labels.ts` 에. 하드코딩 금지.

**검증**.
- 기존 E2E 전수(실측 확인 7개) — `board-kanban.spec.ts` · `board-filter.spec.ts` ·
  `board-reorder.spec.ts` · `board-wip-swimlane.spec.ts` · `board-swimlane-field-change.spec.ts` ·
  `board-epic-swimlane.spec.ts` · `quick-filter.spec.ts`
- 눈확인. 보드 **1개 / 2개** 상태의 스위처 — 라이트·다크 양쪽

### Task 6. 보드 `⋯` 메뉴 — 이름 변경 · 삭제 + E2E

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/routes/projects.$projectKey.board.tsx`, `apps/web/src/routes/__tests__/projects.board.test.tsx`, `apps/web/e2e/board-manage.spec.ts`, `apps/web/src/i18n/board-labels.ts`, `apps/web/src/hooks/use-boards.ts`, `apps/web/src/hooks/use-boards.test.tsx`, `apps/web/src/components/board/CreateBoardForm.tsx`, `apps/web/src/test/lint-ratchet-baseline.ts`]
- depends-on: [5]
- jira: [J3, J4, J5]

**RED (동반 테스트)**.
- 유닛. `canCreate=false` → 「이름 변경」 부재 · `canDelete=false` → 「보드 삭제」 부재 (**disabled 아님**)
- 유닛. 삭제 확인 후 `deleteBoard` 호출 + 남은 보드로 navigate (E2)
- 유닛 **S7**. 삭제가 403/404 로 실패하면 **다이얼로그가 열린 채** `error` 가 창 안에 뜬다
- 유닛 **E7**. `confirming` 중에는 취소·Esc·오버레이로 창이 닫히지 않는다
- E2E 신규 `board-manage.spec.ts`. S1 두번째 보드 생성 → S2 전환 → S3 이름변경 → S4 삭제 →
  S5 빈 상태 복귀
- ★ **훅 단위 (Task 4 가 남긴 미검증 축 — `use-boards.test.tsx`)**
  - `useUpdateBoardName 성공 시 boardKeys 를 invalidate 한다`
  - `useDeleteBoard 성공 시 목록 키만 invalidate 한다` — 상세 키를 무효화하면 아직 마운트된
    `useBoard(boardId)` 관찰자가 즉시 404 를 재조회한다
  - `타임아웃이 지나면 isPending 이 풀리고 에러가 전달된다`
  ⚠️ **Task 4 가 이 축을 red 로 만들지 못했다** — invalidate 를 통째로 지워도 초록이었다.
  단언이 갈 파일(`use-boards.test.tsx`)이 **어느 task 의 `files` 에도 없어서** 신규 훅 2개가
  무테스트로 남았기 때문이다. 그 소유를 이 task 로 옮겼다

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
- ★ **`canDelete` 는 Zod `.optional()` 이라 `undefined` 가 올 수 있다** (Task 4 판단).
  `.default(false)` 를 쓰면 `z.infer` 출력에서 필수가 되어 선언 files 밖 픽스처가 전부 타입 에러를 낸다.
  → 소비자는 **`boardDetail.canDelete === true` 로만** 삭제 항목을 렌더한다. `undefined` 는 **fail-closed** 다.
  MSW 기본값은 `true`(mock 로그인 사용자 alice 가 `SOFT_DELETE` 보유)이므로, 권한 없는 화면을 시드하려면
  `StoredBoardDetail.canDelete: false` 를 명시한다
- ★ **pending 무한 대기 탈출구 (Maxi 게이트 1 지시 · 소비자 쪽에서 푼다).**
  `confirming` 이 취소·Esc·오버레이·X 를 **전부 잠그는데**, 그 설계는 `confirm-dialog.tsx` KDoc 이
  밝히듯 *"파괴적 조작이고 이미 확인을 누른 뒤라 **기다림은 짧다**"* 를 전제한다. 네트워크가
  끊겨 mutation 이 pending 에 머물면 전제가 깨지고 사용자가 창에 갇힌다.
  → **프리미티브를 고치지 않는다.** #410 이 세운 계약이고 손대면 소비처 전수에 영향이 간다.
  대신 **이 소비자의 delete mutation 에 타임아웃**을 걸어 일정 시간 뒤 reject 시킨다.
  그러면 `isPending` 이 풀려 `confirming` 이 내려가고, 실패 사유가 `error` prop 으로 창 안에 뜬다 —
  **이미 설계된 실패 경로(S7)로 합류**하므로 새 UI 상태가 늘지 않는다.
  RED 에 `타임아웃이 지나면 confirming 이 풀리고 error 가 창 안에 뜬다` 를 추가한다.

**검증**.
- `pnpm --filter web test` · `pnpm --filter web test:e2e`
- 눈확인. `⋯` 메뉴 열림 · 삭제 확인 다이얼로그 · **삭제 실패 상태(창 안 error)** — 라이트·다크

### Task 7. `jira-research-guard` — 헤딩 앵커로 좁힌다 (게이트 1 추가 지시)

**메타**.
- agent: `backend-engineer`
- files: [`scripts/workflow/jira-research-guard.test.ts`]
- depends-on: []
- jira: []

**배경 — 이 PR 이 실물로 밟았다.** `checkJiraSection` 이 `body.indexOf('## Jira 대조')` 로
**첫 매치**를 찾는다. 문서 본문이 그 절 이름을 **인용만 해도** 인용 지점부터 다음 `\n## ` 까지를
섹션으로 오인해, URL 이 있는 진짜 절을 못 보고 「출처 URL 이 하나도 없다」로 판정한다.
이 PR 의 plan 이 Brief 에서 절 이름을 인용했다가 red 가 됐고, 인용 표현을 바꿔 우회했다.

**RED**. 픽스처 2개를 추가한다.
- `본문이 절 이름을 인용해도 헤딩의 URL 을 찾는다` — 인용 1줄 + 진짜 헤딩(URL 포함) → **통과여야 한다**
- `헤딩이 아예 없고 인용만 있으면 여전히 실패한다` — 회귀 앵커. 우회로 뚫리지 않게 막는다

**GREEN**. `indexOf('## Jira 대조')` → 줄 시작 앵커 정규식(`/^## Jira 대조/m`)의 `match.index`.
`### ` 하위 헤딩이 섹션을 끊지 않는 현재 동작(`/\n## /`)은 그대로 둔다.

⚠️ **비-공허 확인.** 가드를 고치면 판별자가 사라질 수 있다 (CLAUDE.md §함정 4행).
고친 뒤 **일부러 URL 을 지운 픽스처로 red 1회를 눈으로 본다.**

**검증**. `node --experimental-strip-types --test scripts/workflow/jira-research-guard.test.ts`

### Task 8. `classify-task` — 부정 문맥을 신호로 치지 않는다 (게이트 1 추가 지시)

**메타**.
- agent: `backend-engineer`
- files: [`scripts/workflow/classify-task.ts`, `scripts/workflow/classify-task.test.ts`]
- depends-on: []
- jira: []

**배경 — 이 PR 이 실물로 밟았다.** 제목에 「마이그레이션 **0**」이 들어가자
`MIGRATION_KEYWORDS` 의 `'마이그레이션'` 이 매치돼 `type=migration` · `agent=db-engineer` ·
`primary_bc=null` 이 나왔다. 「마이그레이션 0」·「마이그레이션 없음」은 이 저장소 커밋 메시지의
**상용 표현**이라(`history.md` 에 반복 등장) 재발이 확실하다.

★ **범위 한정.** 같은 실행이 `tier=T1` 을 낸 것은 **결함이 아니다** — `:516-517` 이
`const tier = input.tier ?? DEFAULT_TIER` 이고 주석이 *"실측 티어는 머지 전에 `detect-tier.ts` 가
변경 경로에서 따로 낸다"* 고 밝힌다. `type` 은 제목에서, `tier` 는 경로에서 나오는 **독립 축**이다
(`/bts-review-plan` §Step 2 가 같은 말을 한다). **이 Task 는 `type` 만 고친다.**

**RED**. `classify-task.test.ts` 에 추가한다.
- `「마이그레이션 0」은 migration 이 아니다`
- `「마이그레이션 없음」은 migration 이 아니다`
- `「마이그레이션 없이」는 migration 이 아니다`
- `「Flyway 마이그레이션 추가」는 여전히 migration 이다` ← **회귀 앵커. 부정 제외가 긍정까지 삼키지 않게**
- `「V500__boards.sql」 경로는 여전히 migration 이다` ← 경로 축 회귀 앵커

**GREEN**. 키워드 매치 직후 **부정 꼬리 검사**를 한 겹 얹는다 — 매치된 키워드 바로 뒤가
`0` · `없음` · `없이` · `없다` · `불필요` 중 하나면 그 매치를 신호로 치지 않는다.
`AUTH_STRONG` 등 다른 키워드군에는 **적용하지 않는다** — 보안 축은 위험 반경이 커서
「인증 없음」이 오히려 auth 작업일 수 있다. 적용 범위를 `MIGRATION_KEYWORDS` 로 한정하고 그 사유를 주석에 남긴다.

⚠️ **비-공허 확인.** 부정 꼬리 목록을 일부러 비워 회귀 앵커가 red 되는 것을 1회 확인한다.

**검증**. `node --experimental-strip-types --test scripts/workflow/classify-task.test.ts`

### Task 9. 문서 동기화 — 정본 정정 + deviation 등재

**메타**.
- agent: (controller 직접 — plan 파일 경합을 피한다)
- files: [`docs/plan/product/agile-planning.md`, `docs/plans/2026-08-31-board-crud-recovery.md`, `TODOS.md`]
- depends-on: [3, 6]
- jira: []

**★ 착수 중 발견 — 이 task 는 원래 plan 에 없었다 (Maxi 지적으로 신설).**

**(가) 정본이 이미 거짓을 담고 있다.**
`docs/plan/product/agile-planning.md` §2.1 의 D4 가
*"백엔드 — `GET /api/v1/boards/{id}` + **보드 CRUD** + 카드 이동(전환 위임) API"* 를 **`[x]` 완료**로
표기하는데, 실측하면 **update·delete 가 없었다.** 「CRUD」라는 단어가 C·R 만 있는 상태를 덮고 있었다.
이 PR 이 그 U·D 를 만든다 → **D4 서술을 정정하고 회수 사실을 기록한다.**

**(나) `FR-BD-01-2` 는 정본에 존재하지 않는다.**
저장소 전수 grep 결과 그 번호의 유일한 등장처가 **이 plan 파일 하나뿐**이다.
`docs/plan/fr-index.md:119` 는 `FR-BD-01` 만 등재하고 하위 번호 체계가 없다.
선행 플랜(2026-08-25)이 만든 번호를 이 plan 이 검증 없이 승계했다.

→ **정본에 등재하지 않는다.** 사유 —
① `fr-index.md` 에 하위 번호 체계가 없어 새로 도입하면 **그것을 검사하는 판별식이 없다**
   (`verify-master-plan.sh` 는 `FR-XX-NN` 패턴만 세고 하위 번호를 보지 않는다 — EXIT=0 으로 실측 확인)
② 검사받지 않는 목록은 조용히 썩는다 ([[two-lists-never-check-each-other]])
③ FR 총수 불변이 목표인데 하위 번호는 총수 계산을 모호하게 만든다
→ 대신 **plan 내부 식별자**임을 이 문서에 명시하고, 정본에는 **서술**로 남긴다.

**할 일**
1. `agile-planning.md` §2.1 — D4 서술 정정(「보드 CRUD」가 실제로 덮던 범위) + 이번 PR 회수 내역 + **deviation 2건**(D-1 `SOFT_DELETE` 권한 근사 · D-2 `⋯` 메뉴 위치) 등재
2. 이 plan 의 `FR-BD-01-2a~d` 가 **정본 FR ID 가 아니라 plan 내부 식별자**임을 Brief 에 명시
3. `TODOS.md` — 남은 부채를 **「화면에서 보이는 것」 절이 아니라 성격에 맞는 절**에 등재
   (`BoardRepository.kt` 308줄 · `ConfirmDialog` pending 무한 · `classify-task` dead code `const lower`)
4. `node scripts/build-doc-index.mjs` 재생성
5. `bash scripts/verify-master-plan.sh` **EXIT=0** 확인

**현황판(`docs/progress.html`)은 이 task 대상이 아니다** — 머지 시 post-merge 훅이 자동 재생성한다
(main 이력의 `[chore] dashboard regen [skip ci]` 가 그 산출물). 이 task 는 **대시보드가 읽어가는 원본**을 고친다.

## Plan 메타

- **task 수** 9 · **예상 wave** 7 (T9 문서 동기화는 T3·T6 이 끝난 뒤 controller 가 직접 수행)
  - T1→T2→T3→T4→T5→T6 은 전부 직렬이다 — `depends-on` 이 사슬이고 T5·T6 이
    `board.tsx`·`projects.board.test.tsx`·`board-labels.ts` 를 공유해 파일 겹침으로도 직렬화된다
  - **T7·T8 은 보드 사슬과 완전히 독립**이다(`scripts/workflow/` 단독, 파일 교집합 0).
    wave 1 에 T1 과 함께 실려 병렬로 돈다 — 그래서 task 가 2개 늘어도 wave 는 6 그대로다
- **구현 규율** TDD red-first (T2). `test:` 커밋 → `feat:` 커밋 순서가 로그에서 대조된다.
  T5·T6 은 ui 시각 검증 트랙이라 RED 라벨을 「동반 테스트」 명세로 읽는다 — red-first 순서 강제 없음
- **추가 검증** typecheck · ktlint · detekt · vitest · playwright
- **마이그레이션 0 · 신규 의존성 0 · 신규 프리미티브 0 · BC 1개(agile-planning)**
- **범위 확장 1건 (Maxi 게이트 1 지시).** T7·T8 은 보드 CRUD 와 무관한 하네스 결함 2건이다.
  「한 PR = 한 관심사」에서 벗어나지만, 둘 다 **이 PR 이 실물로 밟아 재현한 결함**이고
  처방이 명확해 장부에 미루지 않고 같은 PR 에서 닫는다. 표면은 `GUARD_CI` 라 **티어는 T2 그대로**다

## 리뷰 결과

**렌즈 1종 — `plan-eng-review`** (`type=api` → `/bts-review-plan` §Step 2 표가 정한 종수).
**외부 모델(Outside Voice)은 돌리지 않았다** — 이 저장소가 2026-08-18 커밋 `592077896` 에서
「외부 모델 리뷰 상시 중단」을 확정했다. 렌즈 부재가 아니라 **결정에 따른 생략**이다.

### 발견 4건 · BLOCKER 0

신뢰도는 pre-emit 검증 게이트를 통과한 값이다 — 근거 라인을 인용하지 못한 발견은 올리지 않았다.

#### [P1] (confidence 10/10) `BoardRequests.kt` 는 존재하지 않는다 — Task 3 의 `files` 오류

**근거.** `ls backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/web/dto/` →
`BacklogResponses.kt` `BoardResponses.kt` `BurndownResponse.kt` `QuickFilterDto.kt`
`SprintRequests.kt` `SprintResponses.kt` `TimelineResponses.kt` `VelocityResponse.kt`.
요청 DTO 는 **전부 `BoardResponses.kt` 안에** 산다 —
`:34 data class CreateBoardRequest` · `:291 UpdateColumnWipLimitRequest` · `:304 UpdateBoardSwimlaneRequest`.
파일명과 내용이 어긋난 기존 관례다.

**영향.** 그대로 두면 구현이 `BoardRequests.kt` 를 새로 만들고, 같은 BC 안에서 요청 DTO 가
두 파일로 갈린다 — 기존 3개는 남고 `UpdateBoardRequest` 만 새 파일로 간다.

**처방 (적용 완료).** Task 3 `files` 에서 `BoardRequests.kt` 를 제거했다.
DTO 분리를 원하면 기존 3개를 함께 옮기는 **별도 리팩터 task** 로 — 이번 PR 범위를 넓히지 않는다.

#### [P1] (confidence 10/10) 즉사 계약의 진단 방향이 반대였다 — 다중 매치가 아니라 role 소멸

**근거.** `projects.board.test.tsx:517` —
`document.querySelector('select[aria-hidden="true"]')`. 이것은 **Radix Select** 의 숨은
네이티브 select 다. 즉 `:383` · `:513` · `:535` 의 `getByRole('combobox')` 가 세는 대상은
**보드 스위처 자신 하나뿐**이고, `>= 1` 완화로 combobox 가 2개가 되지 않는다.

진짜 위험은 Task 5 GREEN 이 스위처를 `DropdownMenu` 로 교체한다는 데 있다 —
Radix DropdownMenu 는 `role="combobox"` 를 내지 않으므로 **3개 단언이 전부 0매치 red** 가 된다.
`docs/design/jira-parity-contract.md` §2 의 「`프로젝트 뷰 전환`을 Radix Tabs 로 바꾸면
`role="navigation"` 소멸로 e2e 즉사」와 **동형 양식**이다.

**폭발 반경 (실측).** `grep -rn "combobox" apps/web/e2e/` 에 보드 관련 의존 **0건**
(이슈 유형 · 로그인 provider · 워크플로우 스킴 · 커맨드 팔레트뿐). **유닛 3개 단언으로 한정된다.**

**처방 (적용 완료).** 스펙 §즉사 계약 표와 Task 5 RED 서술을 교정했다.

#### [P2] (confidence 9/10) `canDelete` 가 보드 상세 조회의 권한 판정을 1회에서 2회로 늘린다

**근거.** `BoardController.kt:137` — `val (actor, board) = loadBoardWithBrowse(id)`.
현재 `getBoard` 는 **BROWSE 1회**만 부른다. `canDelete` 는 `SOFT_DELETE` 라 그 호출을 재사용할 수 없다.
plan NFR 은 「추가 API 왕복이 없다」만 적고 이 비용을 적지 않았다.

**처방 (적용 완료).** NFR 에 명시했다. 목록(`BoardSummaryResponse`)에는 싣지 않으므로 증가는
상세 조회 1건당 1회로 한정된다. 착수 시 `IssuePermissionResolver` 의 요청 단위 캐시 유무를 확인하고
없으면 부채로 등재한다.

#### [P2] (confidence 8/10) `confirming` 이 닫힘을 전부 잠그는데 mutation 타임아웃 근거가 없다

**근거.** `apps/web/src/components/ui/confirm-dialog.tsx` `confirming` KDoc —
*"처리 중에 못 닫게 하면 실패가 갈 곳이 구조적으로 보장된다 … 파괴적 조작이고 이미 확인을
누른 뒤라 **기다림은 짧다**."* 설계가 「기다림은 짧다」를 **전제**한다.

네트워크가 끊겨 mutation 이 pending 에 머물면 취소·Esc·오버레이·X 가 전부 잠긴 채
사용자가 창에 갇힌다. Task 6 RED 에 E7(잠김 확인)은 있으나 **잠김이 풀리지 않는 경로**는 없다.

**판정 — 이번 PR 범위 밖으로 둔다.** 이것은 `ConfirmDialog` 프리미티브 소관이고
이 PR 이 만든 문제가 아니다(#410 이 세운 계약이다). 아래 「NOT in scope」에 사유와 함께 남긴다.

### What already exists — 재사용이 옳게 잡혀 있다

| 이미 있는 것 | plan 의 처리 |
|---|---|
| `PATCH /api/v1/boards/{id}` (`BoardController.kt:222`) | **확장**한다. 새 엔드포인트를 만들지 않는다 ✅ |
| `boards.deleted_at` (`V500`) + `findById`/`findAllByProjectKey` 필터 | 그대로 쓴다. **마이그레이션 0** ✅ |
| `IssuePermissionResolver` 주입 (`BoardController.kt:79`) | `canDelete` 산출에 재사용 ✅ |
| `loadBoardWithCreate` private helper (`:302`) | `loadBoardWithSoftDelete` 를 **동형으로** 신설 ✅ |
| `updateSwimlaneField` (`BoardRepository.kt:149-166`) | Task 1 이 미러링 ✅ |
| `JsonNullable` 3-state (`web/dto/SprintRequests.kt:52-58`) + `JacksonNullableConfiguration` Bean | 선례를 따른다. **신규 의존성 0** ✅ |
| `dropdown-menu.tsx` · `confirm-dialog.tsx` · `CreateBoardForm` | 전부 재사용. **신규 프리미티브 0** ✅ |

불필요한 재구축은 **0건**이다.

### NOT in scope — 고려했고 미룬 것

| 항목 | 사유 |
|---|---|
| `ConfirmDialog` 의 pending 무한 대기 탈출구 | 프리미티브 소관이고 #410 이 세운 계약이다. 이 PR 이 만든 문제가 아니다 |
| 요청 DTO 를 `BoardRequests.kt` 로 분리 | 기존 3개를 함께 옮겨야 해 범위가 넓어진다. 별도 리팩터 |
| `BoardSummaryResponse` 에 `canDelete` | `⋯` 메뉴는 현재 보드에만 붙는다. 목록에 실을 소비처가 없다 |
| per-board 관리자 모델 | `created_by` 마이그레이션 + shared-kernel 포트 = **T3**. 로드맵 D |
| 저장 필터 기반 보드 (J6) | `project_key` 문자열 고정 + AQL·search BC 결선. 패리티 포기 후보 |
| 스프린트 편집·삭제 (A2) | 「한 PR = 한 관심사」. 로드맵 A2 |

### 권한·응답 흐름 (Task 3 이 만드는 것)

```
DELETE /api/v1/boards/{id}
   │
   ├─ actor 추출 ──────────────── 없음 → 401
   │
   ├─ loadBoardWithSoftDelete(id)
   │     ├─ findById(id)  (deleted_at IS NULL 필터) ── 없음 → 404
   │     └─ hasPermission(actor, SOFT_DELETE, Project(key)) ── 거부 → 403
   │
   └─ service.softDelete(id) ──▶ deleted_at = now() ──▶ 204

★ 순서가 뒤집히면 403 이 「존재하지 않음」을 누설한다.
  로컬은 항상 허용이라 이 뒤집힘이 보이지 않는다.

GET /api/v1/boards/{id}
   │
   ├─ loadBoardWithBrowse(id) ─── hasPermission(BROWSE)   ← 기존 1회
   └─ canDelete 산출 ──────────── hasPermission(SOFT_DELETE) ← 신규 1회 (P2)
```

이 다이어그램을 `BoardController` 의 DELETE 핸들러 KDoc 에 인라인으로 넣기를 권한다 —
권한 순서 역전은 코드만 보고는 눈에 안 띄는 종류의 결함이다.

### 실패 모드 — critical gap 0

| 새 코드경로 | 실패 시나리오 | 테스트 | 에러 처리 | 사용자에게 보이나 |
|---|---|---|---|---|
| `PATCH` present-null | `{"name":null}` 이 NPE 500 | ✅ Task 3 RED | ✅ 400 | ✅ |
| `PATCH` 빈 바디 | `{}` 가 조용히 200 | ✅ Task 3 RED (앵커) | ✅ 400 | ✅ |
| `DELETE` 권한 순서 역전 | 403 이 존재를 누설 | ✅ Task 3 RED (401 probe 차단) | ✅ | ✅ |
| `canDelete` Zod 파싱 | 백엔드 미전송 시 보드 화면 전체 사망 | ⚠️ 파싱 성공만 검사 | ❌ | ✅ (화면이 죽어 보인다) |
| 삭제 실패 (403/404) | 창이 닫혀 실패를 못 본다 | ✅ Task 6 RED S7 | ✅ `error` prop | ✅ |
| 삭제 중 pending 무한 | 창에 갇힌다 | ❌ | ❌ | ⚠️ 보이지만 탈출구 없음 |

**critical gap 0.** 마지막 행이 유일하게 「테스트 없음 + 에러 처리 없음」이지만
**silent 가 아니다**(사용자가 잠긴 창을 본다). critical gap 의 세 조건을 동시에 만족하지 않는다.
`canDelete` Zod 행은 Task 4 가 MSW fixture 동시 추가로 이미 방어한다.

### 워크트리 병렬화

**Sequential implementation, no parallelization opportunity.**
`depends-on` 이 T1→T2→T3→T4→T5→T6 사슬이고, T5·T6 이
`board.tsx` · `projects.board.test.tsx` · `board-labels.ts` 를 공유해 파일 겹침으로도 직렬화된다.

### 복잡도 체크 — 트리거됐으나 과설계 아님

plan 이 만지는 파일은 **17개**로 8개 기준을 넘는다. 내역 —
프로덕션 11(백엔드 4 · 프론트 7) + 테스트 6.
그러나 백엔드 3계층(repository → service → controller) + DTO + 프론트 3계층(api → hook → route)
+ MSW 2 + i18n + E2E 는 이 저장소의 **CRUD 표준 형태**이고, 신규 클래스는 `UpdateBoardRequest`
DTO 1개 + private helper 1개뿐이라 「2개 이상의 새 클래스/서비스」 기준에는 걸리지 않는다.
**줄일 자리를 찾지 못했다** — 신규 프리미티브 0 · 신규 API 2(요구사항 자체) · 마이그레이션 0.

### 하네스 결함 — 게이트 1 판정 결과 + ★ 리뷰 자체의 오진 2건 정정

**Maxi 판정 (게이트 1).** 「이번 PR 에 같이 수정」. 등재가 아니라 수정으로 처리한다.

**★ 그런데 착수 직전 재실측에서 후보 4건 중 2건이 이 리뷰의 오진으로 드러났다.**
자기 발견을 스스로 철회한 기록을 남긴다 — 판정을 지우면 다음 사람이 같은 오진을 반복한다.

| 후보 | 판정 | 근거 |
|---|---|---|
| `jira-research-guard` 헤딩 오인식 | **결함 확정 → Task 7** | `checkJiraSection` 이 `indexOf` 로 첫 매치를 잡는다. 이 PR 이 실물로 재현했다 |
| `classify-task` 부정 문맥 (`type`) | **결함 확정 → Task 8** | 「마이그레이션 0」이 `type=migration` 을 냈다. 이 표현은 `history.md` 의 상용 표현이라 재발이 확실하다 |
| ~~`classify-task` 의 `tier` 자기모순~~ | **오진 — 철회** | `:516-517` 이 `tier = input.tier ?? DEFAULT_TIER` 이고 주석이 *"실측 티어는 머지 전에 `detect-tier.ts` 가 변경 경로에서 따로 낸다"* 고 밝힌다. `type` 은 제목에서, `tier` 는 경로에서 나오는 **독립 축**이라 `migration`+`T1` 은 설계된 조합이다 |
| ~~구 표기 판별식 거짓 양성~~ | **오진 — 철회** | `transition-term-guard.test.ts:8` 이 **「다른 낱말의 부분문자열」을 ① 번 예외 유형으로 이미 명시**하고 예시까지 들어 둔다. 그런 자리는 동결 목록으로 관리하도록 설계돼 있고, 실패 메시지 자체가 「동결 파일을 갱신하라」고 안내한다. 설계된 동작이지 결함이 아니다 |

**`ConfirmDialog` pending 무한 대기**는 등재도 프리미티브 수정도 아닌 **제3의 길**로 처리했다 —
Task 6 안에서 **소비자 mutation 타임아웃**으로 푼다. #410 계약을 건드리지 않으므로 소비처 전수
영향이 없고, 타임아웃 실패가 이미 설계된 S7 경로로 합류해 새 UI 상태가 늘지 않는다.

## GSTACK REVIEW REPORT

| 항목 | 값 |
|---|---|
| Runs | `plan-eng-review` 1회 (`type=api` → 1종) |
| Outside Voice | **생략 — 저장소 결정** (커밋 `592077896` 「외부 모델 리뷰 상시 중단」) |
| Status | 완료 |
| Findings | **4건** — P1 2 · P2 2 · **BLOCKER 0** |
| 적용 완료 | P1 2건 + P2(권한 비용) 1건 → plan 수정 반영 |
| 이월 | 0건 — P2(pending 무한)는 게이트 1 지시로 Task 6 에 흡수 |
| ★ 자기 철회 | **2건** — 하네스 결함 후보 4건 중 `tier` 자기모순 · 구 표기 판별식 거짓 양성은 **이 리뷰의 오진**이었다 |
| 범위 확장 | Task 7·8 신설 (Maxi 게이트 1 지시 — 하네스 결함 2건을 같은 PR 에서 닫는다) |
| critical gap | **0** |
| 복잡도 체크 | 17파일로 트리거 · **과설계 아님**으로 판정 |
| 병렬화 | Sequential — 기회 없음 |

**VERDICT — 통과 (게이트 1 승인 반영).** BLOCKER 0.
보드 발견 3건은 plan 에 반영을 마쳤고, pending 무한 1건은 Maxi 지시로 Task 6 에 흡수했다.
하네스 결함은 후보 4건 중 **2건만 실재**했고(Task 7·8), 나머지 2건은 착수 직전 재실측에서
**이 리뷰 자신의 오진**으로 드러나 철회했다 — 그 판정 근거를 §리뷰 결과에 남겼다.
계약 완화 방지 앵커(`{} → 400`)와 즉사 계약 봉합이 task 에 물려 있어 착수 조건은 갖춰졌다.

NO UNRESOLVED DECISIONS
