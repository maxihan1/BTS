<!-- agile-planning BC — 보드/백로그/타임라인/Worklog/에픽/일정 15 FR + LexoRank + @dnd-kit + Gantt PoC -->

# agile-planning BC

**소속 FR**. 15개 (BD 4 + BL 2 + EP 2 + TL 3 + TT 2 + PL 2).
**책임**. 스프린트/보드/백로그/타임라인/에픽/Worklog/일정.
**SDD 참조**. 13장 (보드/백로그/타임라인), 05.13 (에픽), 05.14 (Worklog).
**다른 BC와의 경계**. issue-tracking BC의 이슈를 보드/백로그에 표시. project-workflow의 상태를 컬럼으로 매핑. notification-dashboard로 지연 알림 이벤트 발행.

## §0 진입 조건

- [x] identity-access §4.1~§4.5 (프로젝트 권한) 완료 — 2026-07-27 실측: identity-access.md L256~326 D단계 `[x]` 33 / 미완 0
- [x] issue-tracking §2.1.1 (FR-IS-01 이슈 CRUD) 완료 — 2026-07-27 실측: issue-tracking.md L28~51 D단계 `[x]` 8 / 미완 0
- [x] project-workflow §2 (FR-WF) 완료 — 2026-07-27 실측: project-workflow.md L38~80 D단계 `[x]` 18 / 미완 0
- [ ] §1 기술 검증 통과 (아래) — 미측정. §1.1 부하/지연·§1.2 FPS 실측이 기록되지 않았고 §1.3 은 ADR 로 대체됨. 이 줄은 §1 잔여 항목이 닫혀야 판정 가능

## §1 기술 검증

### §1.1 LexoRank + 1K 부하 PoC (1일)

**SDD**. 13.4. **checklist.md 위임**. §1.3. **ADR 후보**. 없음 (자체 구현).

- [x] 자체 구현 `backend/shared/lexorank.kt` — 2026-07-27 실측: 실경로는 `backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/lexorank/Rank.kt` + `RankSpaceExhaustedException.kt` (외부 라이브러리 0)
- [ ] 1,000개 정렬 시나리오 부하 테스트 통과 (JUnit5) — 미측정. `RankTest.kt` 는 단위/불변식 테스트(최대 `repeat(10)`)뿐. 1,000건 규모 부하 테스트 클래스를 새로 작성해 실행해야 한다
- [ ] insert/move 평균 시간 < 5ms — 미측정. §NFR 측정표 "LexoRank insert/move" 칸이 공란. JUnit + Testcontainers 로 평균 지연을 재서 기입해야 한다

### §1.2 @dnd-kit 1K 백로그 드래그 PoC (1일)

**SDD**. 21.3. **checklist.md 위임**. §1.9. **ADR 후보**. 없음.

- [ ] 1,000개 가상 스크롤 + 드래그앤드롭 60 FPS 유지 — 미측정. `apps/web/package.json` 에 가상 스크롤 라이브러리가 없고(`grep -i virtual` 0건) 백로그는 `@dnd-kit/sortable` 만 사용. 1K 렌더 FPS 를 재야 한다
- [ ] Performance Observer 측정값 기록 — 미측정. `grep -rn "PerformanceObserver" apps/web/src` 0건. 계측 코드부터 필요
- [ ] LexoRank 호출 시뮬레이션 (§1.1 결과 활용) — 미측정. §1.1 부하 결과가 없어 시뮬레이션 입력값이 없다

### §1.3 Gantt 차트 비교 PoC (2일)

**SDD**. 13.3. **checklist.md 위임**. §1.7. **ADR 후보**. **Gantt 라이브러리 선정** (fr-index.md §A.3 #2).

- [ ] 자체 SVG 프로토타입 (100개 막대) — ⚠️ 대체됨. 프로토타입 없이 ADR 정성 분석으로 자체 SVG/CSS 채택(`docs/adr/2026-06-26-gantt-rendering-self-svg.md` D1), 실물은 `apps/web/src/components/timeline/GanttChart.tsx`·`DependencyOverlay.tsx`. (2026-07-27 실측)
- [ ] Recharts 프로토타입 (동일 시나리오) — ⚠️ 대체됨. ADR 후보 비교표의 정성 평가로 대체(recharts 는 워크로그/번다운/사이클타임 차트에만 사용). (2026-07-27 실측)
- [ ] 성능 비교 측정값 기록 (FPS, 메모리, 번들 영향) — ⚠️ 대체됨. PR #194 Deviation 대로 성능 임계는 §4.1 FR-TL-01 NFR 표(500건 2s)에서 추적하기로 이관. (2026-07-27 실측)
- [x] ADR 작성 (`docs/adr/2026-06-26-gantt-rendering-self-svg.md`, PR #194)

> **Deviation(PR #194)**. 실측 프로토타입 비교(자체 SVG/Recharts/성능 측정)는 생략하고 ADR 정성 분석으로 직접 **자체 SVG/CSS** 결정 — 1K·≤500 이슈 규모에서 div 좌표 단순 기하로 충분, 신규 의존성 환각위험 회피(learnings 정신). 성능 임계(500건 2s)는 §4.1 NFR 표에서 실측 추적.

## §2 보드 (FR-BD, 4개)

### §2.1 FR-BD-01 — 칸반 보드 (컬럼 표시, 드래그앤드롭)

**우선순위**. 필수 | **선행**. §0, §1.2 | **Plan slug**. `agile/board-kanban`

- [x] D1. 도메인 — Board / Column (Swimlane은 FR-BD-03) (책임. backend-engineer)
- [x] D2. 명세 — 컬럼=상태 매핑 (책임. backend-engineer)
- [x] D3. 데이터 모델 — `boards`, `board_columns(state_key)` (책임. db-engineer)
- [x] D4. 백엔드 — `GET /api/v1/boards/{id}` + 보드 CRUD + 카드 이동(전환 위임) API (책임. backend-engineer)

> **★ D4 서술 정정 (2026-08-31 · PR 「보드 CRUD 회수」).**
> 위 D4 의 「보드 CRUD」는 **생성(C)·조회(R) 만 덮고 있었다.** 갱신(U)·삭제(D)는 없었는데
> 「CRUD」라는 한 단어가 그 부재를 가려 `[x]` 로 닫혀 있었다. 이번 PR 이 그 U·D 를 만든다 —
> `PATCH /api/v1/boards/{id}` 를 부분 갱신으로 확장(`JsonNullable` 3-state)하고
> `DELETE /api/v1/boards/{id}` 를 소프트 삭제로 신설했다.
> **신규 FR 이 아니다 · FR 총수 불변 143** (PR #175 「범위 확장이나 FR 총수 불변」 선례와 동형).
> 프론트 쪽으로는 보드 스위처 상시 노출(보드 1개여도 렌더) · 「새 보드」 진입점 ·
> 보드 `⋯` 메뉴(이름 변경 · 삭제)가 함께 들어갔다.
>
> **deviation 2건 (명시 기록).**
> - **D-1 삭제 권한.** Jira 는 Board admin 또는 Project admin 이다. BTS 에는 per-board 관리자
>   개념이 없고 도입하려면 `created_by` 마이그레이션 + shared-kernel 포트라 **T3 승격**이 된다.
>   1단계는 `IssuePermission.SOFT_DELETE` 로 근사한다.
>   ⚠️ **같은 BC 안에 비대칭이 생긴다** — `SprintApplicationService.softDelete` 는 `CREATE` 를 쓴다.
>   보드만 한 단계 높은 이유는 「보드 = 여러 사람이 공유하는 뷰」이기 때문이다.
>   보드 관리자 모델이 들어올 때 스프린트 쪽과 함께 재정렬한다.
> - **D-2 삭제 위치.** Jira 는 Boards 디렉터리 행의 `⋯` 에서 지운다. BTS 는 디렉터리 화면이 없어
>   보드 헤더의 `⋯` 메뉴에 뒀다.
>
> **삭제 판정은 fail-closed 다.** 응답의 `canDelete` 가 Zod `.optional()` 이라 `undefined` 가 올 수
> 있고, 프론트는 **`=== true` 일 때만** 삭제 항목을 렌더한다. 권한 미보유 시 항목은 비활성이 아니라
> **DOM 에서 빠진다**.
- [x] D5. 백엔드 테스트 (책임. backend-engineer)
- [x] D6. 프론트 UI — @dnd-kit 컬럼/카드 (책임. frontend-engineer) (PR #169)
- [x] D7. E2E + NFR (책임. qa-engineer) (PR #169)

| 항목 | 임계 | 실측 (p95) |
|---|---|---|
| 보드 200건 렌더 | 1.5s | ___ |

### §2.2 FR-BD-02 — 보드 필터 (담당자/라벨/컴포넌트)

**우선순위**. 필수 | **선행**. §2.1 | **Plan slug**. `agile/board-filter`

- [x] D1. 도메인 — 필터 SQL 푸시다운(포트 확장), 새 개념 0 (PR #168)
- [x] D2. 명세 — 필드내 OR+필드간 AND, `assignee=unassigned` 센티널 (PR #168)
- [x] D3. 데이터 모델 — 신규 0 (활용. URL query + 기존 assignee_id/labels TEXT[]/issue_components) (PR #168)
- [x] D4. 백엔드 — `GET /api/v1/boards/{id}?assignee=&label=&component=` (assignee IN/IS NULL, label && overlap, component EXISTS) (PR #168)
- [x] D5. 백엔드 테스트 — repository 필터/EC7 truncated 가드 + 파서/컨트롤러/서비스/contract (PR #168)
- [x] D6. 프론트 UI — 필터 칩 + 담당자/라벨/컴포넌트 다중 선택 (URL search params, 책임. frontend-engineer) (PR #171)
- [x] D7. E2E (책임. qa-engineer) (PR #171)

### §2.3 FR-BD-03 — WIP 제한 + 스윔레인

**우선순위**. 높음 | **선행**. §2.1 | **Plan slug**. `agile/board-wip-swimlane`

- [x] D1. 도메인 — SwimlaneField enum + BoardColumn.wipLimit + Board.swimlaneField (PR #172)
- [x] D2. 명세 — WIP 초과 시 시각 경고만(이동 차단 없음, ADR 결정 1) (PR #172)
- [x] D3. 데이터 모델 — `board_columns.wip_limit`(CHECK >0), `boards.swimlane_field`(NONE/ASSIGNEE/PRIORITY) V501 (PR #172)
- [x] D4. 백엔드 — wipLimit/wipExceeded 신호 + PATCH 2종(권한 CREATE) + swimlaneField echo (PR #172)
- [x] D5. 백엔드 테스트 — 도메인/Repository/DTO/Controller통합/Service단위 (PR #172)
- [x] D6. 프론트 UI — 컬럼 헤더 경고 + 스윔레인 그룹(컬럼 내부 레인, 세로) + 스윔레인 셀렉터(서버 저장, CREATE 게이팅) (책임. frontend-engineer) (PR #173)
- [x] D7. E2E — WIP 경고/스윔레인 전환/권한 게이팅 (책임. qa-engineer) (PR #173)

> **Deviation(PR #173)**. ① PRIORITY 스윔레인 그룹화 근거로 `BoardCardResponse.priority`를 재노출(D5 NIT-1 결정 되돌림, same-BC view-layer patch, Maxi 확정). ② 스윔레인 레이아웃은 컬럼 내부 레인 그룹(세로) 채택 — droppable column id 보존으로 드래그 이동(FR-BD-01) 회귀 0. ③ WIP 제한 '편집' UI는 후속 이연(이번엔 표시/경고 + 스윔레인 셀렉터까지).

### §2.4 FR-BD-04 — 보드 종류(스크럼/칸반) + 활성 스프린트 보드

**우선순위**. 필수 · **티어 T3** (마이그레이션 2개 + 기존 화면 의미 변경).
**설계 정본**. [ADR 2026-09-01 board-type-and-active-sprint](../../adr/2026-09-01-board-type-and-active-sprint.md)

Jira Cloud 는 보드를 만들 때 **스크럼/칸반을 먼저 고르고**, 스크럼 보드는 **시작된 스프린트의
이슈만** 보여준다. BTS 는 보드에 종류가 없고 보드 경로 4파일이 `sprint` 를 한 번도 참조하지 않아
**스프린트를 시작해도 보드가 바뀌지 않는다.** 스프린트·백로그가 보드가 아니라 프로젝트에 매달려 있어
보드를 여러 개 만들어도 계획 단위가 늘지 않는다. 이 FR 이 그 갭을 닫는다.

- [x] D1. 도메인 — `BoardType`(SCRUM/KANBAN) · 보드↔스프린트 소속 관계 (책임. backend-engineer)
- [x] D2. 명세 — 생성 플로우(종류→이름) · 활성 스프린트 보드당 1개 · 백로그 보드 스코프 (책임. backend-engineer)
- [x] D3. 마이그레이션 — `boards.board_type` · `sprints.board_id` + 백필(스프린트 보유 프로젝트마다 스크럼 보드 신설) (책임. db-engineer)
- [x] D4. 백엔드 — 생성 API 종류 인자 · 스크럼 보드 카드 배치(활성 스프린트 필터) · `start` 활성 1개 가드 (책임. backend-engineer)
- [x] D5. 백엔드 테스트 — 종류별 배치 분기 · 활성 2개 시도 409 · 백필 검증 (책임. backend-engineer)
- [x] D6. 프론트 — 「보드 만들기」 종류 선택 단계 · 스크럼 보드 화면(활성 스프린트 없으면 빈 상태) · 백로그 `?board=` 스코프 (책임. frontend-engineer) (PR #422 종류 선택 · PR #424 보드 화면·백로그 스코프)
- [x] D7. E2E — 스크럼 보드 생성 → 백로그에서 스프린트 시작 → 보드에 그 스프린트만 (책임. qa-engineer) (PR #424 `e2e/scrum-board.spec.ts`)

> **Deviation(PR #424 — D6 잔여·D7)**. ① **`truncated` × 스크럼 보드를 닫지 못했다.** PR ① 의 plan
> (`docs/plans/2026-09-01-board-scrum-schema.md:384`)이 「**PR ③ 전에 닫는다**」로 기한을 박았으나 ③ 은
> 닫는 대신 **PR ④ 로 분리**했다(`docs/plans/2026-09-02-scrum-board-screen.md:300-325`, 인계 지침 4건).
> `IssueRepository.kt:813-814` 가 `created_at DESC` 로 1,001건을 먼저 자르고 스프린트 필터가 그 뒤에 와
> **오래된 활성 스프린트 이슈가 경고 없이 증발한다** — 사용자에게는 정상 시작한 스프린트가 빈 보드다.
> **✅ PR ④(#430)가 닫았다 (2026-09-03).** `BoardCardFilter.issueKeys` 로 스프린트 술어를 LIMIT 앞으로 밀었다.
> ② **그 분리를 장부에 등재하지 못했다.** ③ 의 plan 이 「ADR 3분할 표에 ④ 행 추가를 Task 9(문서 동기화)가
> 같은 PR 에서 처리한다」(`:324-325`)고 지시했으나 그 plan 의 Task 9 는 E2E 였고(`:583`) **문서 동기화 task
> 자체가 없었다.** 결과로 ④ 는 ADR 표에도 `TODOS.md` 에도 없이 plan 산문 2곳에만 남았다
> (memory `two-lists-never-check-each-other`). ③ 이후 등재로 복구했다.
> ③ **X7 — 보드 탭·백로그 탭의 `?board=` 어긋남**을 알려진 한계로 남겼다(Maxi 확정 2026-09-02). 각 탭이
> 자기 `?board=` 를 들고 뷰 전환 nav 가 그것을 안 싣는다. PR ⑥ 이 **링크 전파까지만** 해소하고 공유 상태
> (FR-UX-07 활성 프로젝트 컨텍스트 관계 정리)는 여전히 범위 밖이다.
> ④ **칸반 보드에 스프린트가 매달리는 것을 백엔드가 막지 않는다.** `SprintApplicationService.kt:427-437`
> `resolveTargetBoard` 에 `boardType == SCRUM` 술어가 없어, 칸반 `boardId` 로 만든 스프린트는 `getBoard` 가
> SCRUM 에서만 활성 스프린트를 조회하므로 **어느 화면에도 나타나지 않는다.** 프론트가 스위처에서 스크럼만
> 노출해 가리는 것이 유일한 방어선이다(`backlog.tsx:153-161` KDoc 이 그 사실을 적는다). PR ⑤ 가 **쓰기
> 경로만** 막는다 — 읽기 경로까지 막으면 기존 시드(DEFAULT_BACKLOG 스프린트 전량이 칸반 보드 소속)가 깨진다.
> ⑤ `start` 활성 1개 가드에 **TOCTOU** 가 남았다. `idx_sprints_board_active`(V506:63-64)가 선재 다중 ACTIVE
> 행 보존을 위해 **UNIQUE 가 아니라** DB 가 막지 않는다. PR ⑤ 가 advisory lock 으로 닫는다.
> 후속 3분할(④⑤⑥)의 정본은 [ADR 「후속 3분할」](../../adr/2026-09-01-board-type-and-active-sprint.md) 이다.

> **선행 결정 무효화 (2026-09-01).** `§3.2 FR-BL-02` 의 **Deviation(PR #182) ⑤ 「동시 ACTIVE 다중 허용」**
> 을 이 FR 이 뒤집는다. Jira Cloud 는 *"If you want to have more than one active sprint at a time,
> you'll need to enable parallel sprints"* 로 **기본 1개**를 못박는다(2026-09-01 조회 · Cloud).
> 조용히 바꾸지 않고 여기 남긴다 — parallel sprints 옵션은 이번 범위 밖이다.

## §3 백로그 (FR-BL, 2개)

### §3.1 FR-BL-01 — 백로그 우선순위 정렬 (LexoRank)

**우선순위**. 필수 | **선행**. §1.1 | **Plan slug**. `agile/backlog-lexorank`

- [x] D1. 도메인 — Rank VO (책임. backend-engineer) (PR #179)
- [x] D2. 명세 — LexoRank 알고리즘 + rebalance 트리거 (책임. backend-engineer) (PR #179)
- [x] D3. 데이터 모델 — `issues.rank` VARCHAR(50) nullable(옵션 B) + index, V030 (책임. db-engineer) (PR #179)
- [x] D4. 백엔드 — `PATCH /api/v1/issues/{key}/rank` + on-demand rebalance (책임. backend-engineer) (PR #179)
- [x] D5. 백엔드 테스트 — 1K 시나리오 (책임. backend-engineer) (PR #179)
- [x] D6. 프론트 UI — (§3.2와 통합, 백로그 보드 @dnd-kit 재정렬) (책임. frontend-engineer) (PR #183)
- [x] D7. E2E — 프론트(D6) 동반, FR-BL-02와 통합 (책임. qa-engineer) (PR #183)

> **Deviation(PR #179)**. ① **옵션 B(nullable + lazy)** 채택 — 신규 이슈 rank=NULL, 드래그 시 부여, 정렬 `NULLS LAST, created_at`. 당초 NOT NULL+생성 시 자동부여가 기존 테스트 254개 파급(raw INSERT NOT NULL + service mock findMaxRank)을 일으켜 Maxi 확정으로 전환. ② rank=issue 스칼라 속성 → **issue-tracking BC** 소유(FR-PL-01 일정 필드 선례), LexoRank VO는 shared-kernel. ③ 동시성 no-bump last-write-wins, rebalance만 advisory lock. ④ V029→**V030** 리넘버(origin/main FR-SR-01 V029_filter_indexes 충돌 회피). ⑤ D6/D7(프론트·E2E)은 FR-BL-02와 통합 이연.

### §3.2 FR-BL-02 — 백로그 → 스프린트 드래그 이동

**우선순위**. 필수 | **선행**. §3.1, §1.2 | **Plan slug**. `agile/backlog-drag`

- [x] D1. 도메인 — Sprint + SprintStatus(PLANNED/ACTIVE/COMPLETED) (책임. backend-engineer) (PR #182)
- [x] D2. 명세 — CRUD + 상태전환 + 할당/해제 (책임. backend-engineer) (PR #182)
- [x] D3. 데이터 모델 — `sprints` + `sprint_issues(sprint_id, issue_key)` 조인 (agile-planning, V503, issues 무변경) (책임. db-engineer) (PR #182)
- [x] D4. 백엔드 — 스프린트 CRUD + start/complete + 이슈 할당/해제 API (책임. backend-engineer + security-engineer) (PR #182)
- [x] D5. 백엔드 테스트 (책임. backend-engineer) (PR #182)
- [x] D6. 프론트 UI — @dnd-kit/core 백로그 ↔ 스프린트 보드(드래그 5시나리오 + 라이프사이클 + 권한 게이팅) (책임. frontend-engineer) (PR #183)
- [x] D7. E2E (책임. qa-engineer) (PR #183)

> **D6 회수 (2026-09-02 · PR #418 `sprint-manage-ui`).** D6 이 `[x]` 였으나 **D4 가 낸 스프린트 API
> 두 개에 소비처가 없었다.** 없던 것을 그대로 적는다.
> ① `DELETE /api/v1/sprints/{id}` — `apps/web/src/api/backlog.ts` 에 `deleteSprint` 자체가 없었다.
> 만든 스프린트를 화면에서 지울 방법이 0 이었다.
> ② `PATCH /api/v1/sprints/{id}` — `updateSprint` 는 있었으나 **소비처가 `StartSprintDialog` 하나**였다.
> 즉 이름·목표·기간은 「스프린트를 시작하는 순간」에만 고칠 수 있었고, PLANNED 로 둔 채 또는 이미 ACTIVE 인
> 스프린트를 고치는 경로는 없었다.
> 온 것도 그 둘이다 — `SprintForm`(생성·편집 공용 폼) · `EditSprintDialog` · `SprintActionsMenu`
> (`⋯` → 편집 · 삭제, 권한 없으면 항목 부재) · `deleteSprint` / `useDeleteSprint`.
> 「D6 전량 완료」처럼 **집합 약어로 덮지 않는다** — 없던 것은 편집 · 삭제 **두 조작**이고 회수한 것도 그 둘이다.
> 드래그 · 할당/해제 · start · complete 는 PR #183 이 한 그대로다.

> **T5 이탈 (2026-09-02 · PR #418).** 위 회수 작업의 Task 5 가 자기 허용 파일 밖인
> `apps/web/src/components/backlog/BacklogBoard.tsx` 를 **+13줄** 고쳤다. 조용히 넘기지 않고 여기 남긴다.
> **사유** — 삭제/편집이 무효화할 `boardId`(`?board=` 스코프)의 출처가 T5 의 허용 5파일 안에 없었다.
> 대안 둘은 실측으로 배제했다. ① `SprintColumn` 이 `useSearch` 를 직접 읽으면
> `BacklogBoard.test.tsx:23` 의 라우터 모킹이 그 파일을 통째로 깨뜨린다. ② 선택 prop + `undefined` 기본값은
> 409 복구를 **조용히** 깨뜨린다(기본값이 스코프를 지워 무효화가 빗나가도 초록이다).
> **근본 원인은 계획 쪽이다** — T5 의 `files` 메타가 #424(백로그 보드 스코프) 머지 **전에** 쓰여
> `boardId` 의 출처를 알 수 없었다. 다음 plan 에서 `files` 를 확정할 때 선행 PR 머지 시점을 함께 잰다.

> **Deviation(PR #183 — FR-BL-01/02 D6/D7 통합)**. ① 백로그 조회 API = **agile-planning 소유** `GET /api/v1/projects/{key}/backlog`(백로그+스프린트별, rank 정렬, truncated). `BoardIssueLookupPort.BoardIssueView.rank` 확장(issue-tracking adapter SELECT, default null fail-safe). ② 드래그 변경은 기존 API 재사용(rank PATCH #179, sprint 할당/해제 #182) — 신규 백엔드는 조회만, 마이그레이션 0. ③ @dnd-kit/core만(sortable 미추가)·가상스크롤 미도입(board truncated 패턴)·invalidate-only. ④ 권한 게이팅 정밀화 — 이슈 UPDATE 권한을 MyProjectPermission 요약에 노출(identity-access), 스프린트=CREATE/이슈 재정렬·할당=UPDATE 분리. ⑤ 드래그 재정렬 결선 — 카드 droppable+cardFirstCollision로 dropIndex 산출(코드리뷰가 가짜그린 적발→수정). ⑥ 스프린트↔스프린트(S4) E2E는 충돌 핸들러 미구현으로 의도적 SKIP. ADR `2026-06-24-fr-bl-d6-d7-backlog-query-port-rank.md`.

> **Deviation(PR #182)**. ① 관계 모델 = **`sprint_issues(sprint_id, issue_key)` 조인**(agile-planning 단독, issues 무변경) — product 원안 `issues.sprint_id`(모델 A)는 BC 격리·회귀위험(FR-BL-01 rank 254 파급류)으로 기각(ADR 2026-06-24). ② 식별자 **issue_key**(board가 issueKey 중심, 백로그 계산 일관). ③ 권한 **할당/해제=UPDATE**·CRUD/전환=CREATE·조회=BROWSE. ④ 할당 가시성 **단건 포트 isVisibleIssue**(issue-tracking adapter read 1메서드, truncated 오거부/probe 차단). ⑤ 동시 ACTIVE **다중 허용** — 🛑 **2026-09-01 무효화됨**(§2.4 FR-BD-04 · [ADR](../../adr/2026-09-01-board-type-and-active-sprint.md) D5). 보드당 1개가 기본이다. 기존 다중 활성 행은 유지하고 가드는 `start` 시점에만 건다. ⑥ update PATCH **partial(JsonNullable 3-state)**. ⑦ 백로그 조회 API·D6/D7(프론트·E2E)은 **FR-BL-01 D6/D7과 통합 이연**(rank 정렬이 포트 확장 유발).

## §4 타임라인 (FR-TL, 3개)

### §4.1 FR-TL-01 — 타임라인/로드맵 뷰 (Gantt)

**우선순위**. 필수 | **선행**. §1.3 (ADR), §6 (FR-PL 일정) | **Plan slug**. `agile/timeline-gantt`

- [x] D1. 도메인 — TimelineItem (책임. backend-engineer) (PR #192)
- [x] D2. 명세 (책임. backend-engineer) (PR #192)
- [x] D3. 데이터 모델 — (issues.start_date, due_date 활용, 신규 스키마 0) (책임. backend-engineer) (PR #192)
- [x] D4. 백엔드 — `GET /api/v1/timeline?project=...` (책임. backend-engineer) (PR #192)
- [x] D5. 백엔드 테스트 (책임. backend-engineer) (PR #192)
- [x] D6. 프론트 UI — 자체 SVG/CSS Gantt (Epic 그룹 + targetDate 마일스톤) (책임. frontend-engineer) (PR #194)
- [x] D7. E2E + NFR (책임. frontend-engineer) (PR #194)

| 항목 | 임계 | 실측 (p95) |
|---|---|---|
| 타임라인 500건 렌더 | 2s | ___ |

> **Deviation(PR #194 — FR-TL-01 D6/D7 프론트 + E2E)**. ① **Gantt 라이브러리 = 자체 SVG/CSS**(의존성 0) — Recharts/frappe-gantt 대비 커스터마이징·환각위험0·1K 규모 단순성(ADR `2026-06-26-gantt-rendering-self-svg.md`, fr-index §A.3 #2 해소). ② 레이아웃 = **Epic 그룹(접기/펼치기) + start~due 막대 + targetDate ◆ 마일스톤**(SDD §13.3.1). 좌표/그룹 조립은 순수함수(`lib/timeline-layout.ts`, UTC 날짜·jsdom 안전), 컴포넌트는 렌더만. ③ 시간축 = 고정 일 단위 폭 + 가로 스크롤(줌은 FR-TL-03 범위 외). ④ 라우팅 = **code-based router.ts 등록**(file-based 아님). 진입점 = board/backlog nav에 타임라인 링크(board nav 신규 추가). ⑤ 담당자 이름 = `fetchUsers` 3-state(미배정/미지 폴백). issueType 색맵 신규. ⑥ 검증 = unit 108 + 전체 4651 + E2E(timeline 4 + board/backlog 회귀 16). 게이트2 CONCERN 4(데드 i18n·인라인 한국어·Zod .default(null) 방어·ISO직렬화는 #192 해소) 처리.

> **Deviation(PR #192 — FR-TL-01 백엔드 D1~D5)**. ① 범위 **백엔드 우선**(D1~D5) — 프론트 Gantt 렌더(D6/D7) + Gantt 라이브러리 ADR(fr-index §A.3 #2)은 후속 PR(Maxi 확정 2026-06-26). ② cross-BC 데이터는 **신규 `TimelineLookupPort`(shared-kernel) + `TimelineLookupAdapter`(issue-tracking)** — `BoardIssueView` 확장 대신 전용 포트(날짜·issueType 필드 + 500 상한). ③ Epic 부모/자식 **트리 조립은 프론트(D6) 책임**, 백엔드는 평면 목록 + `epicKey` 만 반환. ④ 데이터는 FR-PL-01 `issues.start_date/due_date/target_date`(V025) 활용, 마이그레이션 0 — start~due=간트 막대, target_date=로드맵 마일스톤 마커(포함 필터엔 미반영). ⑤ 타임라인 아이템 = start/due 중 1개+ 있는 가시·미삭제 이슈, BROWSE 권한, `created_at DESC, key ASC` 결정적 truncation(500).

### §4.2 FR-TL-02 — 이슈 간 의존성 라인 (blocks)

**우선순위**. 필수 | **선행**. §4.1, issue-tracking §5.3.1 (FR-LK-01) | **Plan slug**. `agile/timeline-deps`

- [x] D1. 도메인 (책임. backend-engineer) (PR #200)
- [x] D2. 명세 — blocks 관계만 라인으로 (책임. backend-engineer) (PR #200)
- [x] D3. 데이터 모델 — (issue_links 활용, 마이그레이션 0) (책임. backend-engineer) (PR #200)
- [x] D4. 백엔드 — `GET /api/v1/timeline/deps` (책임. backend-engineer) (PR #200)
- [x] D5. 백엔드 테스트 (책임. backend-engineer) (PR #200)
- [x] D6. 프론트 UI — 의존 라인 SVG 오버레이 (책임. designer → frontend-engineer) (PR #201)
- [x] D7. E2E (책임. qa-engineer) (PR #201)

> **Deviation(PR #200 — FR-TL-02 백엔드 D1~D5)**. ① 범위 **백엔드 우선**(D1~D5) — 프론트 의존 라인 SVG 오버레이(D6/D7)는 후속 PR(FR-TL-01 #192/#194 선례, 총 11 task로 분할 임계 초과). ② 엔드포인트 = **agile-planning** `GET /api/v1/timeline/deps?project=`(타임라인 소유자), shared-kernel `TimelineLookupPort.listBlocksDepsByProject` default 확장, issue-tracking `TimelineLookupAdapter` 구현(ADR `2026-06-28-timeline-deps-blocks-overlay.md`). FR-LK-02 그래프(`/issues/{key}/graph`, 단일중심·전체타입·mermaid)는 목적·BC·시각화 달라 재사용 안 함. ③ **누출 차단** = 단일 self-join 대신 기존 `listVisibleForTimeline` 가시 집합(최신 500 윈도우) 재사용 후 그 id 집합 내 BLOCKS만 조회 — 양끝 가시성 자동 보장(새 보안 판정 경로 0, FR-NT-03 BLOCKER 정신). deps가 타임라인과 동일 윈도우에 결합돼 렌더 가능한 엣지만 반환. ④ **마이그레이션 0** — `issue_links`(V021) 재사용, `uq_issue_links` Index Only Scan(EXPLAIN 확인). ⑤ D3 책임 db-engineer→backend-engineer(마이그 0이라 스키마 변경 없음). ⑥ 검증 = 3모듈 test+ktlint+detekt+ArchUnit BC격리 green(--rerun-tasks), S4/S5 positive control(vacuous 차단), linkRepository non-null(fail-open 제거). 적대 리뷰 INVESTIGATE-1(spec self-join↔구현 집합재사용 drift)은 코드 무변경+spec/ADR 동기화로 해소.

### §4.3 FR-TL-03 — 타임라인 줌 (주/월/분기)

**우선순위**. 높음 | **선행**. §4.1 | **Plan slug**. `agile/timeline-zoom`

- [x] D1. 도메인 — 줌은 순수 뷰, 도메인 영향 0 (PR #202)
- [x] D2. 명세 — 줌 레벨별 셀 크기(dayWidth 프리셋) + 축 눈금 단위 (PR #202)
- [x] D3. 데이터 모델 — 활용(변경 0) (PR #202)
- [x] D4. 백엔드 — 해당 없음, 프론트 전용 (PR #202)
- [x] D5. 백엔드 테스트 — 해당 없음 (PR #202)
- [x] D6. 프론트 UI — 줌 컨트롤(세그먼트+−/+) + 1/2/3 단축키 (책임. frontend-engineer) (PR #202)
- [x] D7. E2E (책임. qa-engineer) (PR #202)

> **Deviation(PR #202 — FR-TL-03 타임라인 줌)**. ① **순수 프론트 뷰**(D4/D5 백엔드 해당 없음, 마이그레이션·API 0). 줌 = `GanttChart`의 `DAY_WIDTH_PX=20` 하드코딩을 `zoomLevel('week'|'month'|'quarter') → dayWidth/축단위` 매핑으로 교체. FR-TL-01이 좌표 함수(`computeBarGeometry`/`computeDependencyLines`)·`TimelineAxis`에 `dayWidth`를 이미 인자화해 둠 → **ADR `2026-06-26-gantt-rendering-self-svg.md`의 연장(새 ADR 0)**. ② **프리셋**: week(28px, 월/일 축) · month(20px=현행 무회귀, 월/주 축) · quarter(6px, 분기/월 축). 기본=월. 의존성 라인(FR-TL-02)은 같은 dayWidth 단일 출처라 줌 시 자동 정합. ③ **컨트롤(Maxi 확정 AskUserQuestion)**: 세그먼트(주\|월\|분기) + −/+ 버튼 / 상태=localStorage `timeline-zoom`(전역, `parseZoomLevel` 화이트리스트 폴백, 스토리지 차단 환경 try/catch 안전) / 단축키 1·2·3(수식키 가드). ④ **순수 함수 분리** `lib/timeline-zoom.ts`(매핑·zoom in/out·parse·축단위)는 단위 테스트, 시각은 E2E(ADR D2 정신, jsdom getBBox 함정 회피). ⑤ **적대 리뷰 수정 4건**: C1 localStorage 크래시 가드(Medium), C3 수식키(Ctrl+1) 가드, C2 분기 줌 partial 시작 레이블(분기경계 미포함 범위), C5 의존선 줌 정합 회귀 가드. ⑥ **검증**: 단위 4950(전체) + 줌 신규 ~90, E2E 15(줌 5 + 타임라인 무회귀 10). gap-1(줌 전환 시 가로 스크롤 중심 보정)은 MVP 범위 외. **→ FR-TL 시리즈(01/02/03) 완료.**

## §5 Worklog (FR-TT, 2개)

### §5.1 FR-TT-01 — Worklog (추정/실제/잔여 시간)

**우선순위**. 필수 | **선행**. issue-tracking §2.1.1 | **Plan slug**. `agile/worklog`

- [x] D1. 도메인 — Worklog Entry (책임. backend-engineer)
- [x] D2. 명세 — 추정/실제/잔여 + 시간 단위 (책임. backend-engineer)
- [x] D3. 데이터 모델 — `worklogs(issue_id, author_id, time_spent_seconds, started_at, deleted_at)` + issues 추정 3컬럼 (V027) (책임. db-engineer)
- [x] D4. 백엔드 — `POST/GET/PATCH/DELETE /api/v1/issues/{key}/worklogs` + 추정 PATCH (책임. backend-engineer)
- [x] D5. 백엔드 테스트 (책임. backend-engineer)
- [x] D6. 프론트 UI — Worklog 입력 폼 + 잔여 시간 자동 계산 (책임. designer → frontend-engineer)
- [x] D7. E2E (책임. qa-engineer)

### §5.2 FR-TT-02 — 이슈/사용자/기간별 시간 집계

**우선순위**. 높음 | **선행**. §5.1 | **Plan slug**. `agile/worklog-aggregate`

- [x] D1. 도메인 (책임. backend-engineer)
- [x] D2. 명세 — 집계 차원 이슈/사용자/기간 (by=issue|user|period, 프로젝트는 ?project 필수 필터라 그룹축 제외) (책임. backend-engineer)
- [x] D3. 데이터 모델 — 머티뷰 미채택·실시간 SQL 집계 (ADR D3, 신규 스키마 0) (책임. backend-engineer)
- [x] D4. 백엔드 — `GET /api/v1/worklogs/aggregate?project=&by=&granularity=&from=&to=` (책임. backend-engineer)
- [x] D5. 백엔드 테스트 (책임. backend-engineer)
- [x] D6. 프론트 UI — 표 + recharts 차트 (책임. designer → frontend-engineer)
- [x] D7. E2E (책임. qa-engineer)

## §6 일정 (FR-PL, 2개)

### §6.1 FR-PL-01 — 일정 필드 (Start/Due/Target Date)

**우선순위**. 필수 | **선행**. issue-tracking §2.1.1 | **Plan slug**. `agile/dates`

- [x] D1. 도메인 (책임. backend-engineer)
- [x] D2. 명세 — 타임존 정책 (DATE = 캘린더 날짜, 타임존 무관) (책임. backend-engineer)
- [x] D3. 데이터 모델 — `issues.start_date, due_date, target_date` (DATE) (책임. db-engineer)
- [x] D4. 백엔드 — 이슈 PATCH 확장 (JsonNullable 3-state) (책임. backend-engineer)
- [x] D5. 백엔드 테스트 (책임. backend-engineer)
- [x] D6. 프론트 UI — 네이티브 input[type=date] 데이트픽커 (date-fns 미사용, 의존성 0) (책임. frontend-engineer)
- [x] D7. E2E (책임. qa-engineer)

### §6.2 FR-PL-02 — 지연/임박 자동 알림

**우선순위**. 높음 | **선행**. §6.1, notification-dashboard §2 | **Plan slug**. `agile/dates-overdue`

- [x] D1. 도메인 (책임. backend-engineer)
- [x] D2. 명세 — D-day 트리거 (스케줄러) (책임. backend-engineer)
- [x] D3. 데이터 모델 — (활용 + V026 부분 인덱스) (책임. db-engineer)
- [x] D4. 백엔드 — Spring `@Scheduled` 일 1회 + pgmq 이벤트 (책임. backend-engineer)
- [x] D5. 백엔드 테스트 (책임. backend-engineer)
- [x] D6. 프론트 UI — 알림 토스트 (FR-NT-02 제네릭 토스트 재사용, 신규 코드 0) (책임. frontend-engineer)
- [x] D7. E2E (스케줄러 시간기반 → 발행 통합테스트 + FR-NT-02 토스트 E2E로 대체) (책임. qa-engineer)

## §7 에픽 (FR-EP, 2개)

### §7.1 FR-EP-01 — Epic 이슈 타입 + 자식 이슈 연결

**우선순위**. 필수 | **선행**. issue-tracking §2.1.2 (FR-IS-02), §5.3.1 (FR-LK-01 parent-child) | **Plan slug**. `agile/epic-children`

- [x] D1. 도메인 — Epic Aggregate (책임. backend-engineer) — ADR 2026-06-22 별도 epic_id 컬럼, Issue.epicId
- [x] D2. 명세 — Epic ↔ Story/Task 계층 (책임. backend-engineer) — 불변식 5종(자식 level0·대상 Epic level1·동일프로젝트·자기참조·단일Epic)
- [x] D3. 데이터 모델 — `issues.epic_id`(V028, UUID NULL 자기참조 FK + 인덱스 + init_codegen 미러). `issue_links` 대신 별도 컬럼 채택 (책임. db-engineer)
- [x] D4. 백엔드 — `POST/DELETE/GET /api/v1/issues/{key}/epic-children` + IssueResponse.epic + 권한(UPDATE child/BROWSE epic 프로젝트)·visibility 필터 (책임. backend-engineer)
- [x] D5. 백엔드 테스트 (책임. backend-engineer) — 단위(IssueEpicServiceTest)+통합(Repository EP-1~8, Controller S1~S11)
- [x] D6. 프론트 UI — Epic 상세(이슈 상세 재사용) 자식 목록 섹션 + 자식 소속 에픽 섹션(parent 동형) + changelog "에픽" 라벨 + **보드 EPIC 스윔레인** (책임. frontend-engineer) (PR #175)
- [x] D7. E2E (책임. qa-engineer) (PR #175)

> **deviation (PR #175)**. 보드 EPIC 스윔레인은 FR-BD-03(#172/#173)이 "FR-EP 미구현"으로 이연한 enum을 FR-EP-01 완료로 활성화한 것. `SwimlaneField.EPIC` enum + V502(boards.swimlane_field CHECK 제약 EPIC 포함) + `BoardCardResponse.epicKey` view-layer self-join(동일프로젝트 필터). FR-BD-03 범위 확장이나 FR 총수 123 불변(신규 FR 아님). changelog epic 값은 IssueChangeLabelResolver가 epic key로 박제(detector 기록=UUID 불변, #174 회귀0).

### §7.2 FR-EP-02 — Epic 진행률 자동 집계

**우선순위**. 필수 | **선행**. §7.1 | **Plan slug**. `agile/epic-progress`

- [x] D1. 도메인 — EpicProgress VO (순수 집계 로직, 외부의존 0) (책임. backend-engineer) (PR #177)
- [x] D2. 명세 — 자식 상태 비율 = 카테고리(TODO/IN_PROGRESS/DONE) 분해 + donePercentage. 자식 타입별 워크플로우 기준 판정 (책임. backend-engineer) (PR #177)
- [x] D3. 데이터 모델 — (활용) `issues.epic_id`(V028, FR-EP-01) + `current_state_key`. 신규 테이블/마이그레이션 없음 (책임. backend-engineer) (PR #177)
- [x] D4. 백엔드 — `GET /api/v1/epics/{key}/progress` (IssueEpicService.progress + WorkflowStateCatalog 집계, BROWSE 게이트 + accessibleLevels visibility) (책임. backend-engineer) (PR #177)
- [x] D5. 백엔드 테스트 (단위 EpicProgress/Service + 통합 S1~S7 실 스킴 시드) (책임. backend-engineer) (PR #177)
- [x] D6. 프론트 UI — 진행률 막대 (EpicProgressBar 3색 구간, EpicChildrenSection 통합) (책임. frontend-engineer) (PR #177)
- [x] D7. E2E (epic-progress.spec.ts 2 시나리오) (책임. qa-engineer) (PR #177)

> **deviation (PR #177)**. (1) **실제 구현 BC = issue-tracking** — product §7.2는 agile-planning 분류이나 FR-EP는 ADR 2026-06-22(FR-EP-01)대로 `com.bts.issue.epic` 패키지(issue-tracking). FR-EP-02도 동일. FR 총수 123 불변. (2) **데이터 모델 = 활용 확정**(db-engineer 미관여) — `epic_id` 직속 자식 집계, 신규 스키마 0. (3) **카테고리 판정 cross-BC** — `current_state_key` → shared-kernel `WorkflowStateCatalog.listStates(projectKey, issueTypeKey)` → `WorkflowStateView.category`. 타입별 1회 캐싱(N+1 차단), `WorkflowSchemeNoDefaultException` simpleName catch 폴백(미할당 타입 자식 TODO, 운영 500 차단). (4) **visibility 모수** = 보이는 자식만(accessibleLevels SQL 푸시다운, 누출 0), S4 통합테스트가 엔드포인트 레이어에서 `total=1` 실측.

## §NFR agile-planning BC 완료 게이트

### 측정값 기록표

| 항목 | 임계 | 실측 (p95) | 비고 |
|---|---|---|---|
| 보드 200건 렌더 | 1.5s | ___ | k6 + Playwright trace |
| 타임라인 500건 렌더 | 2s | ___ | Playwright trace |
| 백로그 1K 드래그 FPS | 60 | ___ | Performance Observer |
| LexoRank insert/move | 5ms | ___ | JUnit + Testcontainers |
| 보드 카드 이동 응답 | 200ms | ___ | k6 |
| Epic 진행률 집계 (자식 100개) | 500ms | ___ | k6 |
| LCP (보드/타임라인) | 2.5s | ___ | Lighthouse CI |
| WCAG 2.1 AA | 0 violations | ___ | axe-core |

### BC 완료 조건

- [ ] §2~§7 (15 FR) 모두 `[x]` 마킹 — **2026-09-01 재개방.** 보드 종류·활성 스프린트 보드가 §2.4 로 신설되며 D1~D7 전량이 새로 열렸다.
  직전 기록(2026-07-27 실측 — 구간 헤더 14, D단계 `[x]` 98, 미완 0)은 그 시점의 참이며 지금은 아니다. 숫자만 올리면 거짓 진술이 되므로 시점을 남긴다. project-workflow 가 워크플로우 편집 신설로 같은 게이트를 재개방한 선례를 따른다.
  ⚠️ 이 줄에 `FR-<접두>` 토큰과 `N개` 를 함께 쓰지 말 것 — `verify-master-plan.sh` 의 헤더 카운트 규칙이 **헤더가 아닌 산문 줄도 스캔**해 그것을 선언으로 읽는다 (2026-09-01 실측 · 오탐 1회)
- [ ] §NFR 측정표 모든 항목 임계 통과 — 미측정. 위 측정표 8행의 "실측 (p95)" 칸이 전부 `___`. 보드 200건·타임라인 500건·1K 드래그 FPS·LexoRank 지연·카드 이동 응답·Epic 집계·LCP·axe 를 실측해 기입해야 한다
- [x] Gantt ADR (§A.3 #2) 발행 완료 — 2026-07-27 실측: `docs/adr/2026-06-26-gantt-rendering-self-svg.md` 실재(상태 채택, §A.3 #2 해소 명시). `docs/decisions/` 에는 동명 파일 없음
- [x] CHANGELOG.md 정리 — 2026-07-27 실측: 루트 `CHANGELOG.md` §BC 요약에 agile-planning 행 존재(14 FR · 2026-06-19~06-29 · PR 15건 #168~#202 · 대표 산출 5종)
- [ ] README.md §7 변경 이력에 "agile-planning BC 완료 — YYYY-MM-DD" 추가 — 🛑 Maxi 1인 선언 대기 (에이전트 수행 불가). 기입할 날짜가 아래 선언 시점이라 선언 전에는 쓸 수 없다. `docs/plan/README.md §7` 에 해당 행 없음(2026-07-27 확인)
- [ ] Maxi 1인 선언 — "agile-planning BC 완료" — 🛑 Maxi 1인 선언 대기 (에이전트 수행 불가)
