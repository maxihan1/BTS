<!-- agile-planning BC — 보드/백로그/타임라인/Worklog/에픽/일정 14 FR + LexoRank + @dnd-kit + Gantt PoC -->

# agile-planning BC

**소속 FR**. 14개 (BD 3 + BL 2 + EP 2 + TL 3 + TT 2 + PL 2).
**책임**. 스프린트/보드/백로그/타임라인/에픽/Worklog/일정.
**SDD 참조**. 13장 (보드/백로그/타임라인), 05.13 (에픽), 05.14 (Worklog).
**다른 BC와의 경계**. issue-tracking BC의 이슈를 보드/백로그에 표시. project-workflow의 상태를 컬럼으로 매핑. notification-dashboard로 지연 알림 이벤트 발행.

## §0 진입 조건

- [ ] identity-access §4.1~§4.5 (프로젝트 권한) 완료
- [ ] issue-tracking §2.1.1 (FR-IS-01 이슈 CRUD) 완료
- [ ] project-workflow §2 (FR-WF) 완료
- [ ] §1 기술 검증 통과 (아래)

## §1 기술 검증

### §1.1 LexoRank + 1K 부하 PoC (1일)

**SDD**. 13.4. **checklist.md 위임**. §1.3. **ADR 후보**. 없음 (자체 구현).

- [ ] 자체 구현 `backend/shared/lexorank.kt`
- [ ] 1,000개 정렬 시나리오 부하 테스트 통과 (JUnit5)
- [ ] insert/move 평균 시간 < 5ms

### §1.2 @dnd-kit 1K 백로그 드래그 PoC (1일)

**SDD**. 21.3. **checklist.md 위임**. §1.9. **ADR 후보**. 없음.

- [ ] 1,000개 가상 스크롤 + 드래그앤드롭 60 FPS 유지
- [ ] Performance Observer 측정값 기록
- [ ] LexoRank 호출 시뮬레이션 (§1.1 결과 활용)

### §1.3 Gantt 차트 비교 PoC (2일)

**SDD**. 13.3. **checklist.md 위임**. §1.7. **ADR 후보**. **Gantt 라이브러리 선정** (fr-index.md §A.3 #2).

- [ ] 자체 SVG 프로토타입 (100개 막대)
- [ ] Recharts 프로토타입 (동일 시나리오)
- [ ] 성능 비교 측정값 기록 (FPS, 메모리, 번들 영향)
- [ ] ADR 작성 (`docs/adr/<date>-gantt-choice.md`)

## §2 보드 (FR-BD, 3개)

### §2.1 FR-BD-01 — 칸반 보드 (컬럼 표시, 드래그앤드롭)

**우선순위**. 필수 | **선행**. §0, §1.2 | **Plan slug**. `agile/board-kanban`

- [x] D1. 도메인 — Board / Column (Swimlane은 FR-BD-03) (책임. backend-engineer)
- [x] D2. 명세 — 컬럼=상태 매핑 (책임. backend-engineer)
- [x] D3. 데이터 모델 — `boards`, `board_columns(state_key)` (책임. db-engineer)
- [x] D4. 백엔드 — `GET /api/v1/boards/{id}` + 보드 CRUD + 카드 이동(전이 위임) API (책임. backend-engineer)
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
- [x] D2. 명세 — CRUD + 상태전이 + 할당/해제 (책임. backend-engineer) (PR #182)
- [x] D3. 데이터 모델 — `sprints` + `sprint_issues(sprint_id, issue_key)` 조인 (agile-planning, V503, issues 무변경) (책임. db-engineer) (PR #182)
- [x] D4. 백엔드 — 스프린트 CRUD + start/complete + 이슈 할당/해제 API (책임. backend-engineer + security-engineer) (PR #182)
- [x] D5. 백엔드 테스트 (책임. backend-engineer) (PR #182)
- [x] D6. 프론트 UI — @dnd-kit/core 백로그 ↔ 스프린트 보드(드래그 5시나리오 + 라이프사이클 + 권한 게이팅) (책임. frontend-engineer) (PR #183)
- [x] D7. E2E (책임. qa-engineer) (PR #183)

> **Deviation(PR #183 — FR-BL-01/02 D6/D7 통합)**. ① 백로그 조회 API = **agile-planning 소유** `GET /api/v1/projects/{key}/backlog`(백로그+스프린트별, rank 정렬, truncated). `BoardIssueLookupPort.BoardIssueView.rank` 확장(issue-tracking adapter SELECT, default null fail-safe). ② 드래그 변경은 기존 API 재사용(rank PATCH #179, sprint 할당/해제 #182) — 신규 백엔드는 조회만, 마이그레이션 0. ③ @dnd-kit/core만(sortable 미추가)·가상스크롤 미도입(board truncated 패턴)·invalidate-only. ④ 권한 게이팅 정밀화 — 이슈 UPDATE 권한을 MyProjectPermission 요약에 노출(identity-access), 스프린트=CREATE/이슈 재정렬·할당=UPDATE 분리. ⑤ 드래그 재정렬 결선 — 카드 droppable+cardFirstCollision로 dropIndex 산출(코드리뷰가 가짜그린 적발→수정). ⑥ 스프린트↔스프린트(S4) E2E는 충돌 핸들러 미구현으로 의도적 SKIP. ADR `2026-06-24-fr-bl-d6-d7-backlog-query-port-rank.md`.

> **Deviation(PR #182)**. ① 관계 모델 = **`sprint_issues(sprint_id, issue_key)` 조인**(agile-planning 단독, issues 무변경) — product 원안 `issues.sprint_id`(모델 A)는 BC 격리·회귀위험(FR-BL-01 rank 254 파급류)으로 기각(ADR 2026-06-24). ② 식별자 **issue_key**(board가 issueKey 중심, 백로그 계산 일관). ③ 권한 **할당/해제=UPDATE**·CRUD/전이=CREATE·조회=BROWSE. ④ 할당 가시성 **단건 포트 isVisibleIssue**(issue-tracking adapter read 1메서드, truncated 오거부/probe 차단). ⑤ 동시 ACTIVE **다중 허용**. ⑥ update PATCH **partial(JsonNullable 3-state)**. ⑦ 백로그 조회 API·D6/D7(프론트·E2E)은 **FR-BL-01 D6/D7과 통합 이연**(rank 정렬이 포트 확장 유발).

## §4 타임라인 (FR-TL, 3개)

### §4.1 FR-TL-01 — 타임라인/로드맵 뷰 (Gantt)

**우선순위**. 필수 | **선행**. §1.3 (ADR), §6 (FR-PL 일정) | **Plan slug**. `agile/timeline-gantt`

- [x] D1. 도메인 — TimelineItem (책임. backend-engineer) (PR #192)
- [x] D2. 명세 (책임. backend-engineer) (PR #192)
- [x] D3. 데이터 모델 — (issues.start_date, due_date 활용, 신규 스키마 0) (책임. backend-engineer) (PR #192)
- [x] D4. 백엔드 — `GET /api/v1/timeline?project=...` (책임. backend-engineer) (PR #192)
- [x] D5. 백엔드 테스트 (책임. backend-engineer) (PR #192)
- [ ] D6. 프론트 UI — ADR 선택 라이브러리/SVG (책임. designer → frontend-engineer)
- [ ] D7. E2E + NFR (책임. qa-engineer)

| 항목 | 임계 | 실측 (p95) |
|---|---|---|
| 타임라인 500건 렌더 | 2s | ___ |

> **Deviation(PR #192 — FR-TL-01 백엔드 D1~D5)**. ① 범위 **백엔드 우선**(D1~D5) — 프론트 Gantt 렌더(D6/D7) + Gantt 라이브러리 ADR(fr-index §A.3 #2)은 후속 PR(Maxi 확정 2026-06-26). ② cross-BC 데이터는 **신규 `TimelineLookupPort`(shared-kernel) + `TimelineLookupAdapter`(issue-tracking)** — `BoardIssueView` 확장 대신 전용 포트(날짜·issueType 필드 + 500 상한). ③ Epic 부모/자식 **트리 조립은 프론트(D6) 책임**, 백엔드는 평면 목록 + `epicKey` 만 반환. ④ 데이터는 FR-PL-01 `issues.start_date/due_date`(V025) 활용, 마이그레이션 0. ⑤ 타임라인 아이템 = start/due 중 1개+ 있는 가시·미삭제 이슈, BROWSE 권한, `created_at DESC` 결정적 truncation(500).

### §4.2 FR-TL-02 — 이슈 간 의존성 라인 (blocks)

**우선순위**. 필수 | **선행**. §4.1, issue-tracking §5.3.1 (FR-LK-01) | **Plan slug**. `agile/timeline-deps`

- [ ] D1. 도메인 (책임. backend-engineer)
- [ ] D2. 명세 — blocks 관계만 라인으로 (책임. backend-engineer)
- [ ] D3. 데이터 모델 — (issue_links 활용) (책임. db-engineer)
- [ ] D4. 백엔드 — `GET /api/v1/timeline/deps` (책임. backend-engineer)
- [ ] D5. 백엔드 테스트 (책임. backend-engineer)
- [ ] D6. 프론트 UI — 의존 라인 SVG 오버레이 (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

### §4.3 FR-TL-03 — 타임라인 줌 (주/월/분기)

**우선순위**. 높음 | **선행**. §4.1 | **Plan slug**. `agile/timeline-zoom`

- [ ] D1. 도메인 (책임. backend-engineer)
- [ ] D2. 명세 — 줌 레벨별 셀 크기 (책임. backend-engineer)
- [ ] D3. 데이터 모델 — (활용) (책임. db-engineer)
- [ ] D4. 백엔드 — (해당 없음, 프론트 전용) (책임. -)
- [ ] D5. 백엔드 테스트 — (해당 없음) (책임. -)
- [ ] D6. 프론트 UI — 줌 컨트롤 + 키보드 단축키 (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

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

- [ ] §2~§7 (14 FR) 모두 `[x]` 마킹
- [ ] §NFR 측정표 모든 항목 임계 통과
- [ ] Gantt ADR (§A.3 #2) 발행 완료
- [ ] CHANGELOG.md 정리
- [ ] README.md §7 변경 이력에 "agile-planning BC 완료 — YYYY-MM-DD" 추가
- [ ] Maxi 1인 선언 — "agile-planning BC 완료"
