# 칸반 소속 스프린트 이관과 스프린트 시작 락 예산 (부채 165 · 166)

> FR: **FR-BD-04** 후속 (보드 종류 · 활성 스프린트 보드)
> 티어: T3 · type: migration · BC: agile-planning
> plan: [2026-09-03-kanban-sprint-move-and-lock-budget](../plans/2026-09-03-kanban-sprint-move-and-lock-budget.md)
> ADR: [2026-09-03-kanban-sprint-move-and-lock-budget](../adr/2026-09-03-kanban-sprint-move-and-lock-budget.md)

장부 부채 **165**(`TODOS.md`)·**166** 을 닫는다. 둘 다 같은 함수
`SprintApplicationService.start` 를 가리키므로 한 PR 로 간다.

## 사용자 시나리오 (Given-When-Then)

### S1 — 안 보이던 스프린트가 돌아온다 (165)

- **Given** 프로젝트 `ATLAS` 에 스크럼 보드와 칸반 보드가 있고, `V506` 이후 ~ #431 사이에 명시
  칸반 `boardId` 로 만들어진 스프린트 「9월 1주차」가 `ACTIVE` 로 남아 있다. 스크럼 보드에서는
  「Sprint 12」가 `ACTIVE` 다.
- **When** 이 PR 의 마이그레이션이 적용된다.
- **Then** 「9월 1주차」는 스크럼 보드 소속이 되고 `PLANNED` 로 내려간다. 사용자는 백로그에서
  「Sprint 12(진행 중)」 아래 「9월 1주차(계획됨) · 이슈 8건 · [스프린트 시작]」을 본다.
  「Sprint 12」의 상태와 소속은 **한 글자도 바뀌지 않는다.**

### S2 — 칸반 보드 스프린트는 시작되지 않는다 (165)

- **Given** 어떤 경로로든 칸반 보드에 소속된 `PLANNED` 스프린트가 존재한다.
- **When** `POST /api/v1/sprints/{id}/start` 를 호출한다.
- **Then** **409 `AGILE_SPRINT_BOARD_NOT_SCRUM`** 을 받는다. 상태는 `PLANNED` 그대로다.
  이전에는 200 을 받고 `ACTIVE` 가 되었지만 어느 화면에도 나타나지 않았다.

### S3 — 격리 수준이 올라가도 활성 스프린트는 1개다 (166 ①)

- **Given** 같은 스크럼 보드의 `PLANNED` 스프린트 두 건에 `start` 가 동시에 들어온다.
- **When** 트랜잭션 격리 수준이 `READ COMMITTED` 가 아니라 `REPEATABLE READ` 여도 마찬가지다.
- **Then** 정확히 1건만 성공하고 나머지는 **409 `AGILE_SPRINT_ALREADY_ACTIVE`** 다.
  보드의 `ACTIVE` 스프린트는 1개다.

### S4 — 락은 무한히 기다리지 않는다 (166 ②)

- **Given** 다른 트랜잭션이 같은 보드의 `sprint-start` advisory lock 을 쥐고 있다.
- **When** `start` 가 들어온다.
- **Then** 최대 **200ms** 만 기다리고 **503 `AGILE_UNAVAILABLE`** 로 끊는다. 연결이 무한정
  묶이지 않는다. 형제 락 `scrum-board:<projectKey>` 도 같다.

## Jira 대조 (전 타입 필수)

계약 §1-0 **재사용 승계.** J1·J5·J6·J11 은 [ADR 2026-09-01](../adr/2026-09-01-board-type-and-active-sprint.md)
에서 **출처·조회일 그대로 승계**한다(조회일 2026-09-01 · 전 행 Jira Cloud company-managed).
이번 PR 이 **새로 건드리는 조작**(스프린트를 다른 보드로 옮긴다 · 한 보드에 활성 스프린트가 둘일 때)만
추가 조회했다 — 조회일 **2026-09-03**.

| # | 원문 인용 | 출처 | Cloud/DC |
|---|---|---|---|
| **J14** | *"Sprints aren't dependent on a specific board or project. It's therefore possible for a sprint to be displayed in more than one board."* | https://support.atlassian.com/jira/kb/sprints-appearing-on-multiple-boards-in-jira-data-center-server-or-cloud/ | Cloud+DC |
| **J15** | `originBoardId` = *"the ID of the board where the sprint was originally created."* | https://support.atlassian.com/jira/kb/sprints-appearing-on-multiple-boards-in-jira-data-center-server-or-cloud/ | Cloud+DC |
| **J16** | *"The sprint was created while viewing that board... A board referencing the sprint was copied... Issues included in the board's JQL filter are associated with the sprint."* | https://support.atlassian.com/jira/kb/sprints-appearing-on-multiple-boards-in-jira-data-center-server-or-cloud/ | Cloud+DC |
| **J17** | origin board 를 **바꾸는 조작을 문서가 다루지 않는다.** 공식 답변은 「새 스프린트를 만들어 이슈를 옮겨라」 | https://support.atlassian.com/jira/kb/sprints-appearing-on-multiple-boards-in-jira-data-center-server-or-cloud/ | Cloud+DC |
| **J18** | *"If it's not intended for issue SSPA-9 to be in sprint SSPB 3, then it's suggested to disassociate sprint SSPB 3 from issue SSPA-9"* — 처방이 **스프린트 상태를 건드리지 않는다** | https://support.atlassian.com/jira/kb/multiple-active-sprints-in-agile-scrum-board-when-the-parallel-sprint-option-is-disabled/ | **DC 전용 · Cloud 아님** |

### 채택 판정

- **J14·J15·J16 — 기등재 편차 X5 재확인(패리티 포기).** Jira 는 보드-스프린트를 **필터 기반 표시**로
  풀고 소속은 origin(생성 이력)일 뿐이다. BTS 는 `sprints.board_id NOT NULL` **직접 소속**이다.
  이 PR 은 X5 를 바꾸지 않는다 — 저장 필터·AQL 은 search BC 소관이라 BC 격리상 참조 불가라는
  X1 승계 사유가 그대로 유효하다.
- **J17 — 대응 없음.** Jira 에 「스프린트를 다른 보드로 옮긴다」는 **사용자 조작이 없다.** 그러나 이 PR 의
  이관은 사용자 조작이 아니라 **데이터 정정**이고 `V506__sprint_board_id.sql:49-53` 이 이미 같은
  일(선재 스프린트 전 행 `board_id` 백필)을 했다. 선례의 확장이지 새 조작이 아니다.
- **J18 — 준용하지 않는다 (의도적 편차 `X9` · 신규).** Jira 는 ACTIVE 다중을 데이터로 막지 않고 표시로
  흡수한다. BTS 는 `SprintRepository.findActiveByBoard` 가 `orderBy(created_at asc).limit(1)` 이라
  **흡수가 불가**하다. 근거는 Jira 원문이 아니라 **BTS 자체 일관성**이다.
- **J11 승계 — 활성 스프린트 기본 1개.** parallel sprints 는 BTS 에 없다. `start` 가드가 그 1개를 지킨다.

### 조회했으나 원문을 확보하지 못한 것

**칸반 보드에서 스프린트 생성을 시도**했을 때 Jira 가 무엇을 돌려주는지. J6
(*"Active sprints are only available on Scrum boards."*)가 「스크럼 전용」까지만 적고 거부 동작을
다루지 않는다 — Jira 는 UI 자체가 없어 **도달 불가**로 보이나 원문 확증은 없다. #431 이 남긴 같은
공백을 이번에도 메우지 못했다.

## 기능 요구사항 (FR)

| # | 요구사항 |
|---|---|
| **R1** | 마이그레이션은 `board_type = 'KANBAN'` 인 보드에 소속된 모든 스프린트를 그 프로젝트의 스크럼 보드로 옮긴다 |
| **R2** | 대상 프로젝트에 스크럼 보드가 없으면 신설하고 컬럼을 복제한다 (`V506` ②③ 과 같은 형태) |
| **R3** 🔴 | **이관이 목표 보드의 `ACTIVE` 건수를 늘리지 않는다.** 초안은 「이관 후 모든 보드에서 ACTIVE 는 최대 1건」이었으나 **SQL 이 그 보장을 지지 않는다** — ADR `D2` 가 상태 변경을 「칸반에서 옮겨 오는 행」으로 한정했으므로 **선재하는 스크럼 보드의 다중 ACTIVE 는 `V507` 이 건드리지 않는다**(`V506:66-68` · PR #182 Deviation ⑤ 가 보존한 행). 선재 ACTIVE 2건짜리 보드에 칸반 스프린트가 들어오면 그 보드는 여전히 2건이다 — 이관이 3건으로 늘리지 않을 뿐이다. 잔여는 부채 **176** 이 진다 |
| **R4** | 목표 스크럼 보드에 **기존 `ACTIVE` 가 있으면** 이관 대상 `ACTIVE` 는 전부 `PLANNED` 로 내린다 |
| **R5** | 목표 스크럼 보드에 기존 `ACTIVE` 가 **없으면** 이관 대상 `ACTIVE` 중 **`(created_at, id)`** 가 가장 앞선 1건만 `ACTIVE` 를 유지하고 나머지는 `PLANNED` 로 내린다. 목표 보드 선정도 같은 `(created_at, id)` 다 — `BoardRepository.kt:263-265` 가 이미 그 규칙이고 주석이 「동점이면 id 로 가른다」를 명시한다. `created_at` 만 쓰면 동점 시 어느 스프린트가 살아남는지가 실행마다 갈려 규칙이 규칙이 아니게 된다 |
| **R6** | **기존 스크럼 보드의 원래 `ACTIVE` 스프린트는 어떤 경우에도 건드리지 않는다** |
| **R7** | 마이그레이션은 **멱등**하다 — 되돌렸다 재적용해도 스크럼 보드를 중복 생성하지 않는다 |
| **R8** | `start` 는 스프린트의 소속 보드가 `SCRUM` 이 아니면 **409** 로 거부한다 |
| **R9** | `start` 는 advisory lock 획득 **후** 스프린트를 재조회해 `status`·`version` 을 다시 판정한다 |
| **R10** | `sprint-start` 와 `scrum-board` advisory lock 은 **200ms 예산**을 갖고, 초과하면 **503** 이다 |
| **R11** | 읽기 경로(`BacklogApplicationService.resolveBoardScope` · `BoardApplicationService.getBoard`)의 종류 비대칭은 **그대로 보존**한다 |

## 비기능 요구사항 (NFR)

| # | 요구사항 | 근거 |
|---|---|---|
| **N1** | `start` 는 `@Transactional(isolation = Isolation.READ_COMMITTED)` 를 명시한다 | 지금은 PostgreSQL 기본값에 의존만 하고 코드에 안 적혀 있다. identity-access 는 37곳에서 같은 명시를 한다 |
| **N2** | 락 대기 상한은 **트랜잭션 스코프**로 건다 | `agile-planning` 테스트는 `DriverManagerDataSource`(`AgilePlanningTestcontainersConfig.kt:108`)라 풀이 없다 — `spring.datasource.hikari.*` 배선은 **무효**다 |
| **N3** | raw SQL 은 `DATA.md §5` 정식 예외 (i)+(ii) 를 둘 다 만족해야 한다 | `set_config(text,text,bool)` 은 함수이고 `?` 바인딩이 된다. `SET LOCAL` 은 리터럴만 받아 (i) 불충족 |
| **N4** | advisory lock 키 계산은 `hashtextextended(text, 0)` 를 유지한다 | 바꾸면 형제 락들과 잠금 공간이 갈린다 |
| **N5** | 락 재현 테스트는 **반드시 예산을 건 채로** 쓴다 | `agile-planning` 은 컨테이너 1개·DB 1개를 전 클래스가 공유한다 — 무한 대기 테스트는 다른 클래스를 물고 멈춘다 |
| **N6** ❓ | 락 예산은 **advisory lock statement 에만** 건다. 획득 직후 `set_config('lock_timeout','0',true)` 로 원복한다 | `set_config(...,true)` 는 **트랜잭션 스코프**라 원복하지 않으면 뒤따르는 `findActiveByBoard`·`updateStatus` 의 **행 락 대기까지** 200ms 에 끊긴다. 부채 166 이 지적한 것은 advisory lock 무한 대기뿐이고, 행 락까지 끊으면 정상 경합이 503 을 받는 **회귀**다 |
| **N7** 🔴 | 락 예산 헬퍼를 추출해도 **두 획득 지점의 `@Transactional(propagation = MANDATORY)` 를 잃지 않는다** | `SprintRepository.kt:130` KDoc 이 못박는다 — *"`REQUIRED` 로 두면 트랜잭션 **없이** 불렸을 때 자기 트랜잭션을 열고 즉시 커밋해 **락이 그 자리에서 풀리는데 예외 없이 조용히 성공한다** — 계약 위반이 침묵한다."* 헬퍼가 Spring 프록시를 안 타는 순수 함수라면 애너테이션이 아예 안 먹으므로, `MANDATORY` 는 **호출자 리포지터리 메서드에 그대로 남긴다.** 판별식 — 트랜잭션 없이 부르면 예외가 나는 테스트 |

## API 인터페이스 (REST)

`POST /api/v1/sprints/{id}/start` — 요청 바디 없음, 응답 `DataResponse<SprintResponse>`. **형태는 안 바뀐다.**
추가되는 실패 응답 2종:

| 상태 | 코드 | 조건 |
|---|---|---|
| **409** | `AGILE_SPRINT_BOARD_NOT_SCRUM` | 스프린트의 소속 보드가 `SCRUM` 이 아니다 |
| **503** | `AGILE_UNAVAILABLE` | advisory lock 을 200ms 안에 얻지 못했다 |

**409 를 쓰고 404 를 쓰지 않는 이유.** 스프린트는 **존재한다.** 404 는 거짓말이고
`permission-assert-before-existence-makes-403-lie` 와 같은 양식의 오도다. `resolveTargetBoard` 의
404 는 「요청이 지정한 보드를 못 찾음」이라 의미가 다르다.

**503 선례.** `WorkflowExceptionHandler.kt:186-198` 이 `WorkflowCacheLockTimeoutException` 을
503 `WORKFLOW_UNAVAILABLE` 로 매핑한다. 같은 형태를 `agile-planning` 에 세운다 —
이 BC 에는 락 타임아웃 매핑이 현재 **0건**이다.

기존 실패 응답은 그대로다 — 404 `AGILE_SPRINT_NOT_FOUND` · 403 · 409 `AGILE_CONFLICT`(FSM 위반) ·
409 `AGILE_SPRINT_ALREADY_ACTIVE`.

**판정 순서(계약).** 404(존재) → 403(권한) → 409 `AGILE_CONFLICT`(FSM) → **409
`AGILE_SPRINT_BOARD_NOT_SCRUM`(종류)** → 락 → 409 `AGILE_SPRINT_ALREADY_ACTIVE`(활성).
종류 가드가 FSM **뒤**인 이유 — 「잘못된 전환 요청이 남의 시작을 막아 세우지 않는다」는 기존 주석
계약(`SprintApplicationService.kt:292`)과 `SprintApplicationServiceTest.kt:1071` 을 깨지 않기 위해서다.

## 데이터 모델 변경

**스키마 DDL 변경은 없다.** `V507` 은 **데이터만** 옮긴다.

`backend/modules/agile-planning/src/main/resources/db/migration/agile-planning/V507__move_kanban_sprints_to_scrum_board.sql`

1. 칸반 소속 스프린트를 가진 프로젝트 중 SCRUM 보드가 없는 곳에 신설 + 컬럼 복제. `NOT EXISTS` 가드로 멱등 (R2·R7).
2. 대상 스프린트의 `board_id` 를 목표 스크럼 보드로 갱신 (R1).
3. R4·R5 규칙대로 `status` 를 `PLANNED` 로 내리고 `version = version + 1`, `updated_at = now()`.
4. 기존 스크럼 보드 원래 행은 `WHERE` 로 제외 (R6).

`idx_sprints_board_active`(`V506:63-64`)는 **그대로 둔다** — 일부러 UNIQUE 가 아니고,
그 사유(`V506:66-68` · PR #182 Deviation ⑤)가 여전히 유효하다. 이 PR 은 데이터를 정리하지만
스키마로 유일성을 못박지 않는다.

**컬럼 복제는 `V506` ③ 형태를 그대로 쓴다** — 가장 오래된 활성 칸반 보드에서 `LATERAL` 로
`state_key`·`name`·`category`·`display_order`·`wip_limit` 를 복제한다. 조회 경로에 컬럼 시드가
없어 0개로 두면 영원히 빈 보드가 된다(`V506:27-33` 주석).

❓ **`sprint_issues` 는 건드리지 않는다.** 이슈 할당은 `sprint_id` 기준이라 `board_id` 변경과
무관하다. **프론트 변경도 없다** — 백로그 화면은 `PLANNED` 스프린트를 이미 그리므로 이관 결과가
그대로 보인다.

❓ **`status` 만 되돌린다.** `Sprint.start()` 는 `copy(status = ACTIVE)` 뿐이고 `start_date` 를
건드리지 않는다(`Sprint.kt:76-84`). 따라서 `PLANNED` 복귀도 `status` 만 되돌리면 되고 날짜 정리가
필요 없다.

## 엣지 케이스

| # | 상황 | 처리 |
|---|---|---|
| **E1** | 대상 행이 0건 (가장 흔한 경우) | 마이그레이션이 아무것도 안 바꾸고 통과. `V506:49-53` 이 전 행을 스크럼에 붙였으므로 직후에는 0건이다 |
| **E2** | 한 프로젝트의 여러 칸반 보드에 스프린트가 흩어져 있다 | 전부 같은 스크럼 보드로 모인다. R5 가 그중 1건만 ACTIVE 로 남긴다 |
| **E3** | 프로젝트에 스크럼 보드가 둘 이상 | `findScrumBoardIdByProject` 와 같은 규칙(`created_at asc, id asc` 첫 건)을 쓴다 |
| **E4** | 이관 대상이 `COMPLETED` | `board_id` 만 옮기고 상태는 안 건드린다 |
| **E5** | 소프트 삭제된 스프린트(`deleted_at IS NOT NULL`) | 대상에서 제외한다 |
| **E6** | `start` 락 획득 후 재조회했더니 스프린트가 사라졌다 | 404 `AGILE_SPRINT_NOT_FOUND` |
| **E7** | 락 획득 후 재조회했더니 이미 `ACTIVE` | 409 `AGILE_CONFLICT`(FSM 위반) |
| **E8** ❓🔴 | 락 타임아웃이 형제 락(`ensureScrumBoard`)에서 났다 | 같은 503. 매핑을 `SprintExceptionHandler` 와 `BoardExceptionHandler` **양쪽**에 건다.<br>🔴 **초안의 사실 오류를 정정한다.** 초안은 「`ensureScrumBoard` 가 두 경로에서 도달한다」고 적었으나, 구현 중 Task 7 이 전수 grep 으로 확인한 결과 **main 호출자는 `SprintApplicationService.resolveTargetBoard`(스프린트 생성) 하나뿐**이다. `BoardController` 는 오늘 형제 락에 도달하지 않는다. `BoardExceptionHandler` 매핑은 **방어**다 — `ensureScrumBoard` 가 `BoardApplicationService` 의 public 메서드라 보드 컨트롤러가 언제든 부를 수 있고 그때 500 이 나기 때문이다. 그 결과 보드 쪽 테스트는 실제 락 경로가 아니라 **advice 매핑**을 잰다(`createBoard` stub 이 던지게 한다). 락을 잡는 보드 엔드포인트가 생기면 그쪽으로 옮기는 편이 낫다 |
| **E9** ❓ | 마이그레이션 도중 구버전 인스턴스가 `start` 를 받아 칸반 소속 ACTIVE 를 새로 만든다 | **범위 밖.** 배포는 Naver Cloud **단일 호스트**라 롤링 무중단이 아니다. 무중단 배포를 도입하면 그때 재검토한다 |
| **E10** 🔴 | **`V507` 추가가 기존 백필 테스트의 전제를 바꾼다** | `SprintBoardIdBackfillMigrationTest.kt:31-33` KDoc — *"★ 마지막 단계에 `target("506")` 을 쓰지 않는다. 버전을 숫자로 고정하면 나중에 V507 이 들어올 때 이 클래스가 통째로 죽는다."* 그 설계 덕에 클래스는 안 죽지만 **V507 까지 돌게 된다.** V506 ④ 가 전 스프린트를 스크럼에 붙이므로 V507 대상은 0건일 것이나 **가정하지 않고 그 6건이 여전히 초록임을 실측**한다 |
| **E11** 🔴 | Flyway 는 같은 버전을 두 번 적용하지 않는다 — 「재적용」 멱등을 어떻게 재나 | 선례가 이미 답을 준다. 같은 테스트가 `Flyway.configure().target("504")` → seed → `migrate()` **3단**을 쓴다. 멱등은 Flyway 밖에서 **`V507` SQL 을 JDBC 로 직접 한 번 더 실행**해 `boards` 행 수가 안 늘어남을 잰다 |

## 제약 조건

- **BC 격리.** `agile-planning` 안에서 끝난다. `WorkflowCache` 는 `project-workflow` 소유라 import 할 수
  없다 — 락 예산 헬퍼는 이 BC 안에 둔다. `shared-kernel` 승격은 토폴로지 변경이라 범위 밖이다.
- **`DATA.md §5`.** `dsl.execute(rawSql)` 은 금지이고 예외는 (i) `?` 바인딩 + (ii) jOOQ 미지원
  PostgreSQL 함수뿐이다. `set_config` 는 둘 다 만족한다.
- **`DEVELOPMENT.md §2.1`** 함수 30줄 · 파일 300줄. `BoardRepository.kt` 는 이미 439줄로 초과 상태이고
  그것은 부채 157(별건)이다 — 이 PR 은 그 파일을 **더 늘리지 않는 선**에서 고친다.
- **테스트 인프라.** `agile-planning` 은 컨테이너 1개·DB 1개 공용이다(N5).
- ❓ **보드 이름 문자열 사본이 하나 늘어난다.** `V507` 이 스크럼 보드를 신설할 때
  `project_key || ' 스크럼 보드'` 를 쓸 수밖에 없다 — SQL 은 코드 상수를 볼 수 없다. 부채 **162**
  (「스크럼 보드 이름 문자열이 SQL 과 코드에 각각 있다」)가 지적한 사본 2개가 **3개**가 된다.
  162 를 이 PR 에서 닫지 않으므로 **162 본문에 그 사실을 추가**해 장부가 실제를 따라가게 한다.
  숨기면 다음 사람이 사본 2개인 줄 알고 고친다.

## 측정 가능한 완료 기준

1. `V507` 적용 후 `SELECT count(*) FROM sprints s JOIN boards b ON b.id = s.board_id
   WHERE b.board_type = 'KANBAN' AND s.deleted_at IS NULL` **= 0**
2. **`V507` 이 어느 보드의 `ACTIVE` 건수도 늘리지 않는다** — 이관 전후로 보드별
   `status='ACTIVE' AND deleted_at IS NULL` 건수를 비교해 **증가가 0**이다.
   🔴 **「2건 이상 없다」로 읽지 마라 — 두 번 좁혔다.** ① 초안 「어느 `board_id` 에도」는 DB 전체를
   주장해 거짓이었다(구현 중 Task 2 지적). ② 1차 정정 「이관이 닿은 보드에는 2건 이상 없다」도
   **여전히 거짓**이다 — 선재 ACTIVE 2건짜리 스크럼 보드에 칸반 스프린트가 하나 들어오면 그 보드는
   「닿은 보드」인데 강등은 이관 대상에만 걸리므로(R4) ACTIVE 가 그대로 2건이다. 두 조건은 독립이라
   공존한다(게이트 2 ceo 렌즈 지적).
   선재 다중 ACTIVE 는 PR #182 Deviation ⑤ 가 허용하고 `V506:66-68` 이 인덱스를 일부러 UNIQUE 로
   만들지 않아 보존한 것이다. 정리하려면 `V506` 의 결정을 뒤집는 별개 판단이 필요하다 — 이 PR
   범위 밖이고 부채 **176** 이 그 진입점이다.
3. `V507` 을 되돌렸다 재적용해도 `boards` 행 수가 **늘지 않는다**
4. 칸반 소속 스프린트에 `start` → **409 `AGILE_SPRINT_BOARD_NOT_SCRUM`**
5. `start` 의 `@Transactional` 에 `isolation = Isolation.READ_COMMITTED` 가 있다
6. 락 뒤 재조회를 지우고 락 앞 스냅샷으로 되돌리면 **그 테스트 1건만 red** (뮤테이션 짝)
7. 락 홀더가 있을 때 `start` 가 **200ms 내에 503** 으로 끊긴다 (형제 락도 동일)
8. `SprintIntegrationTest` 동시 start 테스트가 `get(30, SECONDS)` + 실패 타입 단언으로 강화돼,
   락이 안 풀리면 **행이 아니라 실패**로 나타난다
9. `./gradlew :modules:agile-planning:test ktlintCheck detekt` BUILD SUCCESSFUL
10. `pnpm test:workflow` 전량 초록 · `verify-master-plan.sh` EXIT 0 · doc-index drift 0

## 실측 기록 — `lock_timeout` 이 advisory lock 에 걸리는가 (2026-09-03)

계획 단계에서 「걸리는지 모른다」로 남겼던 전제를 **가정하지 않고 측정**했다.
`bts-postgres-dev`(`quay.io/tembo/pg16-pgmq`) 에 홀더 세션이 `pg_advisory_xact_lock(987654321)` 을
쥔 상태에서:

| 시험 | 결과 |
|---|---|
| `SET LOCAL lock_timeout='200ms'` → `pg_advisory_xact_lock` | **206.9ms 에 취소** |
| `set_config('lock_timeout','200ms',true)` → 같은 락 (`pg_locks` 홀더 1건 확인) | **206.5ms 에 취소** |
| SQLSTATE (`VERBOSITY verbose`) | **`55P03: canceling statement due to lock timeout`** |

⇒ **1안 채택.** 폴백 2안(`pg_try_advisory_xact_lock` + 200ms 폴링)은 필요 없다.
`WorkflowCache` 의 폴링 루프보다 단순하고, 대기·취소를 DB 가 관리한다.

**아직 확정하지 않은 것.** `55P03` 이 jOOQ 의 `JooqExceptionTranslator`
(`AgilePlanningTestcontainersConfig.kt:123` 배선)를 거쳐 Spring 의 어느 예외 타입으로 도착하는지는
**추측하지 않는다** — 구현 단계에서 red-first 테스트로 실제 타입을 확인한 뒤 핸들러를 건다.

## Sanity Check

**1회 보강 완료 · gap 5건 발견 → 4건 스스로 보강 · 1건 조치 불필요.** 보강 항목은 본문에 `❓` 로 표시했다.

| # | gap | 유형 | 처리 |
|---|---|---|---|
| **G1** | `set_config('lock_timeout',...,true)` 가 **트랜잭션 스코프**라, 원복하지 않으면 락 획득 뒤의 `findActiveByBoard`·`updateStatus` **행 락 대기까지** 200ms 에 끊긴다. 부채 166 은 advisory lock 무한 대기만 지적했는데 정상 행 락 경합이 503 을 받게 되는 **신규 회귀**를 부를 뻔했다 | 가정 누락 | **N6 신설** — 락 획득 statement 직후 `'0'` 으로 원복 |
| **G2** | `V507` 이 스크럼 보드를 신설하면 `project_key \|\| ' 스크럼 보드'` 문자열의 **세 번째 사본**이 생긴다. 부채 162 가 사본 2개로 등재돼 있어 장부가 실제보다 작아진다 | 누락된 요구사항 | **제약 조건에 명시** + 장부 162 본문 갱신을 이 PR 범위에 넣는다 |
| **G3** | E8 이 「매핑이 필요하다」까지만 적고 **어느 핸들러인지**를 안 정했다. `ensureScrumBoard` 는 스프린트 **생성**과 보드 컨트롤러 **두 경로**에서 도달하므로 한쪽만 걸면 다른 쪽이 500 을 낸다 | 모호한 표현 | **E8 구체화** — 양쪽 핸들러 |
| **G4** | 마이그레이션 도중 구버전 인스턴스가 `start` 를 받으면 칸반 소속 ACTIVE 가 **새로** 생길 수 있다 | 엣지 케이스 미커버 | **E9 신설** — 단일 호스트 배포라 롤링이 아니므로 범위 밖으로 명시 |
| **G5** | `ACTIVE → PLANNED` 로 되돌릴 때 `start_date` 를 정리해야 하는지 불명 | 가정 누락 | **조치 불필요** — `Sprint.start()` 가 `copy(status = ACTIVE)` 뿐이라(`Sprint.kt:76-84`) 날짜를 안 건드린다. 데이터 모델 절에 명시 |

**남은 판단 위험.** `55P03` 이 jOOQ `JooqExceptionTranslator` 를 거쳐 도착하는 Spring 예외 타입은
문서가 아니라 **구현 단계의 red-first 테스트**로 확정한다. 여기서 타입을 적으면 그것이 검증되지
않은 두 번째 목록이 된다.
