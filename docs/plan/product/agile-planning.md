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
- [ ] D6. 프론트 UI — 필터 칩 + 다중 선택 (책임. designer → frontend-engineer) — 후속(보드 UI=FR-BD-01 D6 선행)
- [ ] D7. E2E (책임. qa-engineer) — 후속

### §2.3 FR-BD-03 — WIP 제한 + 스윔레인

**우선순위**. 높음 | **선행**. §2.1 | **Plan slug**. `agile/board-wip-swimlane`

- [ ] D1. 도메인 (책임. backend-engineer)
- [ ] D2. 명세 — WIP 초과 시 시각 경고만 (이동 차단 옵션) (책임. backend-engineer)
- [ ] D3. 데이터 모델 — `board_columns.wip_limit`, `boards.swimlane_field` (책임. db-engineer)
- [ ] D4. 백엔드 — 카운트 + 경고 응답 (책임. backend-engineer)
- [ ] D5. 백엔드 테스트 (책임. backend-engineer)
- [ ] D6. 프론트 UI — 컬럼 헤더 경고 + 스윔레인 그룹 (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

## §3 백로그 (FR-BL, 2개)

### §3.1 FR-BL-01 — 백로그 우선순위 정렬 (LexoRank)

**우선순위**. 필수 | **선행**. §1.1 | **Plan slug**. `agile/backlog-lexorank`

- [ ] D1. 도메인 — Rank VO (책임. backend-engineer)
- [ ] D2. 명세 — LexoRank 알고리즘 + rebalance 트리거 (책임. backend-engineer)
- [ ] D3. 데이터 모델 — `issues.rank` (TEXT) + index (책임. db-engineer)
- [ ] D4. 백엔드 — `PATCH /api/v1/issues/{key}/rank` + 자동 rebalance (책임. backend-engineer)
- [ ] D5. 백엔드 테스트 — 1K 시나리오 (책임. backend-engineer)
- [ ] D6. 프론트 UI — (§3.2와 통합) (책임. frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

### §3.2 FR-BL-02 — 백로그 → 스프린트 드래그 이동

**우선순위**. 필수 | **선행**. §3.1, §1.2 | **Plan slug**. `agile/backlog-drag`

- [ ] D1. 도메인 — Sprint (책임. backend-engineer)
- [ ] D2. 명세 (책임. backend-engineer)
- [ ] D3. 데이터 모델 — `sprints`, `issues.sprint_id` (책임. db-engineer)
- [ ] D4. 백엔드 — 스프린트 CRUD + 이슈 할당 API (책임. backend-engineer + security-engineer)
- [ ] D5. 백엔드 테스트 (책임. backend-engineer)
- [ ] D6. 프론트 UI — @dnd-kit 백로그 ↔ 스프린트 (1K 가상 스크롤) (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

## §4 타임라인 (FR-TL, 3개)

### §4.1 FR-TL-01 — 타임라인/로드맵 뷰 (Gantt)

**우선순위**. 필수 | **선행**. §1.3 (ADR), §6 (FR-PL 일정) | **Plan slug**. `agile/timeline-gantt`

- [ ] D1. 도메인 — TimelineItem (책임. backend-engineer)
- [ ] D2. 명세 (책임. backend-engineer)
- [ ] D3. 데이터 모델 — (issues.start_date, due_date 활용) (책임. db-engineer)
- [ ] D4. 백엔드 — `GET /api/v1/timeline?project=...` (책임. backend-engineer)
- [ ] D5. 백엔드 테스트 (책임. backend-engineer)
- [ ] D6. 프론트 UI — ADR 선택 라이브러리/SVG (책임. designer → frontend-engineer)
- [ ] D7. E2E + NFR (책임. qa-engineer)

| 항목 | 임계 | 실측 (p95) |
|---|---|---|
| 타임라인 500건 렌더 | 2s | ___ |

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

- [ ] D1. 도메인 — Epic Aggregate (책임. backend-engineer)
- [ ] D2. 명세 — Epic ↔ Story/Task 계층 (책임. backend-engineer)
- [ ] D3. 데이터 모델 — `issues.epic_id` 또는 `issue_links` 활용 (책임. db-engineer)
- [ ] D4. 백엔드 — `POST /api/v1/issues/{key}/epic-children` (책임. backend-engineer)
- [ ] D5. 백엔드 테스트 (책임. backend-engineer)
- [ ] D6. 프론트 UI — Epic 페이지 + 자식 이슈 목록 (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

### §7.2 FR-EP-02 — Epic 진행률 자동 집계

**우선순위**. 필수 | **선행**. §7.1 | **Plan slug**. `agile/epic-progress`

- [ ] D1. 도메인 — EpicProgress (책임. backend-engineer)
- [ ] D2. 명세 — 자식 상태 비율 (책임. backend-engineer)
- [ ] D3. 데이터 모델 — (활용) (책임. db-engineer)
- [ ] D4. 백엔드 — `GET /api/v1/epics/{key}/progress` (책임. backend-engineer)
- [ ] D5. 백엔드 테스트 (책임. backend-engineer)
- [ ] D6. 프론트 UI — 진행률 막대 (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

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
