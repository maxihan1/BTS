<!-- 보드 종류(Scrum/Kanban) 스키마와 백엔드 — FR-BD-04 D1~D5 스펙 -->

# 보드 종류 + 활성 스프린트 보드 — 스키마·백엔드 (FR-BD-04 D1~D5)

> 티어: T3
> slug: board-scrum-schema
> type: migration
> agent: db-engineer
> BC: agile-planning 단독
> 생성: 2026-09-01
> 설계 정본: [ADR 2026-09-01 board-type-and-active-sprint](../adr/2026-09-01-board-type-and-active-sprint.md) — **채택**
> plan: [2026-09-01-board-scrum-schema](../plans/2026-09-01-board-scrum-schema.md)

## Context

ADR 이 결정한 D1~D6 중 **D1~D5(도메인·명세·마이그레이션·백엔드·백엔드 테스트)** 를 이 PR 이 낸다.
화면은 PR ②(생성 플로우) · PR ③(스크럼 보드 화면)으로 나뉜다 — Maxi 가 3분할을 확정했다.

**이 PR 이 끝나도 사용자에게 보이는 변화는 없다.** 기존 보드는 전부 `KANBAN` 으로 남고
화면은 아직 종류를 묻지 않는다. 그것이 의도다 — 마이그레이션이 든 PR 을 작게 유지한다.

## Jira 대조 (전 타입 필수)

계약 §1-0 **재사용 승계.** 이 표는 ADR `2026-09-01-board-type-and-active-sprint.md` 의 J1~J13 중
**이 PR 의 백엔드 계약을 정하는 행만** 가져온 것이다. **출처·조회일을 그대로 승계**한다
(조회일 2026-09-01 · 전 행 Jira Cloud company-managed). 이번 PR 이 새로 건드리는 조작이 없어
**추가 조회 0건**이다.

| # | 원문 인용 | 출처 | Cloud/DC |
|---|---|---|---|
| **J1** | 보드 생성 모달에서 **"Create a Scrum board"** 또는 **"Create a Kanban board"** 를 고른다 | https://support.atlassian.com/jira-software-cloud/docs/create-a-board/ | Cloud |
| **J5** | *"Your board only displays work items once you've started the sprint, and **the board displays only the work items added to the sprint you started**."* | https://support.atlassian.com/jira-software-cloud/docs/plan-a-sprint/ | Cloud |
| **J6** | 카드가 보드에 뜨는 조건 3개 중 하나가 **"is in an active sprint (for Scrum boards)"**. *"Active sprints are only available on Scrum boards."* | https://support.atlassian.com/jira-software-cloud/docs/use-active-sprints/ | Cloud |
| **J11** | *"If you want to have more than one active sprint at a time, you'll need to **enable parallel sprints**"* → 기본 활성 1개 | https://support.atlassian.com/jira-software-cloud/docs/plan-a-sprint/ | Cloud |

### 채택 판정

- **J1 채택** — `board_type` 컬럼으로 종류를 영속한다. 생성 API 가 종류를 받는다.
- **J5·J6 채택** — 스크럼 보드 조회는 활성 스프린트 이슈만 배치한다.
- **J11 채택** — `start` 에 보드당 활성 1개 가드를 건다.

### 의도적 편차

- **X1 — 보드의 소스.** Jira 보드는 저장된 필터의 뷰다. BTS 는 `project_key` 고정이고 저장 필터·AQL 은
  search BC 소관이라 BC 격리상 참조할 수 없다. **패리티 포기** (ADR X1 승계).
- **X5 — 활성 스프린트 판정 범위(이 PR 신규).** Jira 는 보드 필터로 스프린트를 고르지만 BTS 는
  `sprints.board_id` **직접 소속**으로 정한다. 필터가 없으므로 소속이 유일한 판정 근거다.

### 조회했으나 원문을 확보하지 못한 것

**보드 종류를 생성 후 바꿀 수 있는지.** ADR 조사에서도 원문을 못 잡았다.
→ 이 PR 은 **변경 API 를 만들지 않는다.** 근거 없는 기능을 만들지 않는다.

## 사용자 시나리오 (Given-When-Then)

이 PR 은 화면이 없으므로 **API 계약** 기준으로 적는다.

- **S1.** Given 워크플로우 스킴이 할당된 프로젝트
  When `POST /api/v1/boards` 에 `boardType: "SCRUM"` 을 실어 호출한다
  Then 201 이고 응답 `boardType` 이 `SCRUM` 이다.
- **S2.** Given `boardType` 을 **보내지 않고** 생성한다
  Then 201 이고 `KANBAN` 이다 — 기존 호출자가 안 깨진다.
- **S3.** Given 스크럼 보드에 활성 스프린트가 있고 그 스프린트에 이슈 3건, 스프린트 밖에 이슈 5건
  When `GET /api/v1/boards/{id}`
  Then 컬럼 카드 총합이 **3** 이다. 밖의 5건은 없다.
- **S4.** Given 스크럼 보드에 활성 스프린트가 **없다**
  When `GET /api/v1/boards/{id}`
  Then 200 이고 모든 컬럼이 비어 있으며 `activeSprint` 가 **null** 이다.
  ★ 「이슈가 0건」과 구분되어야 화면이 안내 문구를 고를 수 있다.
- **S5.** Given 칸반 보드
  When `GET /api/v1/boards/{id}`
  Then **지금과 완전히 같다** — 프로젝트 이슈 전량이 상태별로 배치된다.
- **S6.** Given 한 보드에 이미 ACTIVE 스프린트가 있다
  When 같은 보드의 다른 스프린트를 `POST /sprints/{id}/start`
  Then **409**.
- **S7.** Given 마이그레이션 전 DB 에 스프린트를 가진 프로젝트가 있다
  When 마이그레이션을 적용한다
  Then 그 프로젝트에 스크럼 보드가 **1개 생기고** 기존 스프린트 전량이 거기 붙는다.
  **기존 칸반 보드는 무변경**이다.

## 기능 요구사항 (FR)

`FR-BD-04` D1~D5. 신규 FR 없음 — **총수 144 불변**.

- **FR-1.** `boards.board_type` (`SCRUM` | `KANBAN`, 기본 `KANBAN`).
- **FR-2.** `sprints.board_id` — 보드 소속. 백필 후 `NOT NULL`.
- **FR-3.** 보드 생성 API 가 `boardType` 을 **선택 인자**로 받는다. 미지정 시 `KANBAN`.
- **FR-4.** 스크럼 보드 조회는 **그 보드의 ACTIVE 스프린트에 속한 이슈만** 배치한다.
- **FR-5.** 보드 응답에 `boardType` 과 `activeSprint`(없으면 null)를 싣는다.
- **FR-6.** `start` 는 같은 보드에 ACTIVE 스프린트가 있으면 **409**.

## 비기능 요구사항 (NFR)

- **NFR-1.** 칸반 보드 조회 경로는 **쿼리 1개도 늘지 않는다** — 종류 분기 전에 활성 스프린트를 조회하지 않는다.
- **NFR-2.** `BoardCardPlacement` 는 **순수 함수로 남는다.** 스프린트 필터링은 애플리케이션 서비스에서
  하고 도메인 함수 시그니처를 바꾸지 않는다 — 기존 단위 테스트가 그대로 산다.
- **NFR-3.** 마이그레이션은 **되돌릴 수 있다.** 두 컬럼 모두 DROP 으로 원복 가능하고,
  생성된 스크럼 보드가 남는 비대칭을 마이그레이션 주석에 적는다.

## API 인터페이스 (REST)

**신규 엔드포인트 0건.** 기존 3개의 계약만 넓힌다.

### `POST /api/v1/boards` — 종류 인자 추가

```jsonc
{ "projectKey": "ATLAS", "name": "스크럼 보드", "boardType": "SCRUM" }  // boardType 선택
```

- 미지정 → `KANBAN`. **기존 호출자 무변경**(E2E `board-manage.spec.ts` S1 이 종류 없이 부른다).
- 허용값 밖 → **400** `AGILE_BOARD_TYPE_INVALID` (named exception. `IllegalArgumentException` 통째 매핑 금지).

### `GET /api/v1/boards/{id}` — 응답 확장

```jsonc
{
  "boardType": "SCRUM",
  "activeSprint": { "sprintId": "...", "name": "Sprint 3" },  // 없으면 null
  "columns": [ /* 스크럼이면 활성 스프린트 이슈만 */ ]
}
```

### `POST /api/v1/sprints/{id}/start` — 가드 추가

같은 `board_id` 에 `status = ACTIVE` 인 스프린트가 있으면 **409** `AGILE_SPRINT_ALREADY_ACTIVE`.

## 데이터 모델 변경

**마이그레이션 2개.** `agile-planning` 최신은 `V504` 이므로 **V505 · V506**.
⚠️ 머지 직전 `origin/main` 의 최신 V 번호를 **재확인**한다(DATA.md §4.1 · 동시 브랜치 checksum 충돌 회피).

### V505 — `boards.board_type`

```sql
ALTER TABLE boards ADD COLUMN board_type VARCHAR(16) NOT NULL DEFAULT 'KANBAN'
    CHECK (board_type IN ('SCRUM', 'KANBAN'));
```

### V506 — `sprints.board_id` + 백필

```sql
ALTER TABLE sprints ADD COLUMN board_id UUID REFERENCES boards (id) ON DELETE CASCADE;
-- ① 스프린트를 가진 프로젝트마다 스크럼 보드 1개 신설 (기존 칸반 보드는 무변경)
-- ② 컬럼은 그 프로젝트 기존 보드의 컬럼을 복제 — 보드가 없으면 컬럼 없이 만들고 첫 조회에서 시드
-- ③ 기존 스프린트를 신설 보드에 연결
-- ④ NOT NULL 승격
ALTER TABLE sprints ALTER COLUMN board_id SET NOT NULL;
CREATE INDEX idx_sprints_board_active ON sprints (board_id) WHERE deleted_at IS NULL AND status = 'ACTIVE';
```

**부분 인덱스 근거.** FR-6 가드가 「이 보드에 ACTIVE 가 있나」를 매 `start` 마다 묻는다.
전체 인덱스는 COMPLETED 가 쌓일수록 커지므로 **활성만** 인덱싱한다.

## 엣지 케이스

- **E-1.** 스프린트는 있는데 **보드가 하나도 없는 프로젝트.** 신설 스크럼 보드에 복제할 컬럼이 없다.
  → 컬럼 0개로 만들고, 조회 시 기존 「워크플로우 스킴 미할당」 경로와 같게 다룬다.
- **E-2.** 같은 프로젝트에 스프린트가 여러 개인데 그중 **둘 이상이 ACTIVE** 인 기존 데이터.
  PR #182 가 다중 활성을 허용했으므로 **실재할 수 있다.** 마이그레이션은 그것을 **깨지 않는다** —
  가드는 `start` 시점에만 걸고 기존 행은 그대로 둔다. ★ 이 사실을 마이그레이션 주석에 적는다.
- **E-3.** 스크럼 보드의 활성 스프린트 이슈가 **어느 컬럼에도 매핑되지 않는 상태**를 가진 경우.
  기존 `placeCards` 가 조용히 제외한다(`BoardCardPlacement.kt:18`). 이 PR 이 그 동작을 바꾸지 않는다 —
  미매핑 상태 패널은 로드맵 C 소관이다.
- **E-4.** 보드 삭제 시 스프린트. `ON DELETE CASCADE` 라 보드를 **하드 삭제**하면 스프린트가 사라진다.
  BTS 보드 삭제는 **소프트 삭제**(`deleted_at`)이므로 실제로는 발생하지 않는다.
  ★ 그래도 CASCADE 를 두는 이유를 주석에 적는다 — 하드 삭제 경로가 생기면 고아를 남기지 않기 위함.
- **E-5.** 칸반 보드에 `sprints.board_id` 가 붙은 경우. 스키마가 막지 않는다.
  조회는 종류로 분기하므로 **무해**하지만, 백필은 스크럼 보드에만 붙인다.
- **E-6.** **한 프로젝트에 스크럼 보드가 여럿일 때 스프린트는 가장 오래된 것에만 붙는다** (PR ① 리뷰 발견).
  이 PR 이 `POST /api/v1/boards` 에 `boardType=SCRUM` 을 열어 생긴 새 경로다. 백로그가 아직 보드를
  지정하지 않으므로(`CreateSprintRequest` 에 `boardId` 없음) 스프린트 생성은 전부
  `ensureScrumBoard` → `findScrumBoardIdByProject`(`created_at ASC LIMIT 1`) 로 간다.
  → 사용자가 두 번째 스크럼 보드를 만들면 **카드 0건 · `activeSprint` null 인 빈 보드로 고정**된다.

  **한시 규칙으로 둔다.** `ADR D6` 이 다수 보드를 지원하므로 스키마 UNIQUE 로 두 번째 생성을 막지
  않는다. 「어느 보드에 붙일지」는 **PR ③ 이 백로그에서 `boardId` 를 명시로 넘기면** 사라진다.
  그때까지 「가장 오래된 것」이 규칙이며, `SprintApplicationServiceTest` 의
  `create boardId 를 주면 스크럼 보드를 찾지 않는다` 가 그 이행 경로를 미리 고정한다.

  ★ 암묵 생성의 **경쟁**은 별개이며 이미 막았다 —
  `BoardRepository.acquireProjectScrumBoardLock`(advisory lock). E-6 은 사용자가 **의도적으로** 만든
  두 번째 보드에 관한 것이다.

## 제약 조건

- **BC 격리.** `agile-planning` 단독. 다른 BC 는 건드리지 않는다.
- **jOOQ 재생성 필요** — 컬럼 2개 추가. 생성 산출물을 같은 PR 에 포함한다.
- **화면 무변경.** `apps/web/**` **0파일**. 응답에 필드가 늘지만 Zod 스키마가 미지의 키를 무시하므로
  기존 프론트가 안 깨진다. ★ 착수 시 `boards.ts` 스키마가 `.strict()` 인지 **확인**한다 —
  strict 면 필드 추가가 프론트를 즉사시킨다.
- **테스트 정본.** Testcontainers 로 **실제 마이그레이션을 적용**해 백필을 검증한다.
  「마이그레이션이 안 넣음 ≠ 데이터 없음」 — 공유 개발 DB 의 선재 행으로 가짜 그린이 나지 않게
  깨끗한 컨테이너에서 잰다(메모리 `shared-dev-db-preexisting-rows-fake-green`).

## 측정 가능한 완료 기준

1. `boardType` 없이 보드를 만들면 `KANBAN` 이고 **기존 E2E `board-manage.spec.ts` 가 그대로 통과**한다.
2. 스크럼 보드 조회가 **활성 스프린트 이슈만** 낸다 (통합 테스트 · red-first).
3. 활성 스프린트가 없는 스크럼 보드는 `activeSprint: null` + 빈 컬럼이다.
4. 같은 보드에서 두 번째 `start` 가 **409** 다.
5. **백필 검증** — 마이그레이션 전 스프린트 N건이 있는 DB 에서, 적용 후 그 스프린트가 전부
   새 스크럼 보드에 붙고 **기존 칸반 보드의 컬럼·이름이 무변경**이다 (Testcontainers).
6. `./gradlew :modules:agile-planning:test ktlintCheck detekt` EXIT=0 · `verify-master-plan.sh` EXIT=0 ·
   FR 총수 **144 불변**.
