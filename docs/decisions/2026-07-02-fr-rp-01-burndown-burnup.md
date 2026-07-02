# ADR — 번다운 / 번업 차트 데이터 모델 (FR-RP-01)

> 날짜: 2026-07-02
> 상태: 채택
> 관련 FR: FR-RP-01 (번다운 / 번업 차트)
> 관련 SDD: §13.5.1 (번다운), §13.6 (데이터 소스), §6.5 (스프린트 시작+번다운 시나리오)
> 관련 PR: #219

## 맥락

FR-RP-01은 스프린트의 잔여 작업량(번다운)과 완료 누적량+총 스코프(번업)를 시간축으로 시각화한다. 엔드포인트는 `GET /api/v1/sprints/{id}/burndown`(스프린트 스코프).

설계 시점에 **정본 문서 3종이 서로 어긋난다**.

1. **SDD §13.5.1**은 Y축을 "남은 스토리 포인트"로 명시하고, §13.6은 `sprint_burndown_snapshot(sprint_id, snapshot_date, remaining_points, completed_points)` 테이블 + "매일 자정 비동기 작업" 스냅샷을 설계한다. §6.5도 "매일 자정: 스프린트별 남은 작업량 스냅샷"을 명시.
2. **실제 코드베이스**에는 스토리 포인트 컬럼이 어디에도 없다. 시간 축은 `issues.original_estimate_seconds`/`remaining_estimate_seconds`(둘 다 NULL 허용) + `worklogs(time_spent_seconds, started_at)`(FR-TT-01)뿐이다.
3. **product 문서 §4.1 D3**은 "데이터 모델 — (sprint + worklog 활용)" — 즉 재사용, 신규 스키마 없음을 시사한다.

## 결정

### D1. 모듈 배치 — agile-planning

번다운/번업은 `agile-planning` 모듈에 구현한다(엔드포인트 `GET /api/v1/sprints/{id}/burndown`, `SprintController` 이웃).

**근거**.
- SDD가 번다운을 챕터 13(board-backlog-timeline = agile-planning 도메인)에 설계한다. 챕터 14(dashboard-reports)는 `sprint_burndown` 가젯으로만 언급.
- 엔드포인트가 스프린트 스코프(`/sprints/{id}/...`) → Sprint 애그리거트(agile-planning)와 sprint_issues를 로컬로 읽는다. cross-BC 포트는 issue-tracking(worklog/estimate) 1개만 필요.
- 선례. FR-TT(Worklog)는 fr-index=agile-planning 라벨이나 issue-tracking 모듈에 구현됨(ADR 2026-06-20-worklog-time-tracking-model D1). FR-TL-01(타임라인)은 agile-planning 모듈이 issue-tracking을 `TimelineLookupPort`로 소비. classify-task도 agile-planning 판정.
- **fr-index의 논리적 BC 라벨(notification-dashboard)은 유지**한다(물리 모듈 ≠ 논리 BC 라벨, FR-TT 선례와 동일). notification-dashboard=14 카운트 변동 없음, `verify-master-plan.sh`는 BC↔모듈 매핑을 강제하지 않는다.

### D2. Y축 지표 — 잔여 추정 시간(초) (Maxi 확정)

SDD의 "남은 스토리 포인트"를 **잔여 추정 시간(초)**으로 대체한다. 스토리 포인트는 BTS에 미구현이며, FR-RP-01 범위에서 신규 도입하지 않는다.

- 총 스코프(초) = Σ `original_estimate_seconds` (스프린트 소속 이슈, NULL은 0으로 간주)
- day D 잔여(초) = 총 스코프 − Σ `time_spent_seconds` (스프린트 이슈 worklog 중 `started_at`의 날짜 ≤ D 누적)
- **한계**. 추정치(original_estimate) 미입력 이슈는 스코프 기여 0 → 번다운이 0까지 안 내려갈 수 있음. worklog로 시간 로그를 안 하는 이슈는 완료돼도 잔여가 줄지 않음. product 문서 D2 "계산 알고리즘" 명세에서 명시.

### D3. 데이터 소스 — On-the-fly in-memory 계산 (Maxi 확정, 신규 스키마 0)

SDD §13.6의 `sprint_burndown_snapshot` 테이블 + 매일 자정 @Scheduled 잡을 **채택하지 않는다**. 요청 시 worklog `started_at` 타임스탬프를 누적해 시계열을 재구성한다(SDD §13.6의 "실시간은 in-memory 계산" 절 + product D3 "활용"과 정합).

**근거**.
- 신규 마이그레이션·스케줄러 0. 모듈 첫 @Scheduled 함정(learnings 2026-06-27) 회피.
- **과거 스프린트도 소급 계산 가능**(스냅샷은 출시 후 데이터만 — backfill 갭). COMPLETED 스프린트 회고 즉시 지원.
- 1K 사용자 규모에서 스프린트 이슈 수 × worklog 수는 수백 행 수준 → 요청 시 계산 부담 무시 가능.

### D4. 시계열 구성 요소

- **번다운 Actual Line**. day D 잔여(초) — 스프린트 기간(start_date~end_date) 각 일자.
- **번다운 Ideal Line**. start_date의 총 스코프 → end_date의 0으로 선형 보간.
- **번업 Completed Line**. day D 누적 완료(초) = Σ time_spent(started_at ≤ D).
- **번업 Scope Line**. 총 스코프(현재 스프린트 멤버십 기준, 평탄). *on-the-fly는 스코프 변경 이력을 재구성할 수 없어 평탄선*. SDD §13.5.2 "스코프 변경 가시화"는 스냅샷 없이는 불가 → v1 단순화(제약 명시).
- start_date 또는 end_date가 NULL인 스프린트는 시간축 정박점 부재 → 번다운 계산 불가(명세에서 처리 정의).

### D5. cross-BC 포트 — 신규 SprintWorklog 조회 포트 (shared-kernel)

Sprint(agile-planning)는 sprint_issues에서 issue_key 집합을 얻고, 그 이슈들의 `original_estimate_seconds` + worklog(started_at, time_spent_seconds)를 issue-tracking에서 읽어야 한다. `TimelineLookupPort` 선례를 따라 shared-kernel에 조회 포트를 정의하고 issue-tracking이 어댑터로 구현, agile-planning이 소비한다. 정확한 시그니처는 plan 단계에서 확정.

## 새 도메인 개념

- **BurndownPoint** (VO, 영속 아님). 번다운 시계열의 한 점 — { date, remainingSeconds, idealSeconds }.
- **BurnupPoint** (VO). 번업 시계열의 한 점 — { date, completedSeconds, scopeSeconds }.
- 둘 다 read-model. 애그리거트/테이블 없음.

## SDD 동기화 대상 (같은 PR 내 필수)

명세 deviation이므로 §명세/범위 변경 전수 동기화 규칙에 따라 같은 PR에서 갱신.

- **SDD §13.5.1** — "Y축: 남은 스토리 포인트" → "잔여 추정 시간(초)". 스토리 포인트 미구현 명시.
- **SDD §13.6** — `sprint_burndown_snapshot` 테이블 + 매일 자정 잡 설계 → on-the-fly in-memory 채택으로 정정(스냅샷 미채택 사유 기록).
- **SDD §6.5** — "매일 자정 스냅샷" 시나리오 문구 정정.
- fr-index/README/CLAUDE FR 카운트는 변동 없음(BC 라벨 유지, D1).
