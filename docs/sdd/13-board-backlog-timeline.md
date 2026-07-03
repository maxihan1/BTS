# 13. 보드 / 백로그 / 타임라인

## 13.1 칸반 보드 (FR-BD)

### 13.1.1 구성
- 컬럼: 워크플로우 카테고리 (TODO / IN_PROGRESS / DONE)
- 카드: 이슈
- 드래그앤드롭: @dnd-kit
- WIP 제한 (FR-BD-03): 컬럼당 최대 이슈 수

### 13.1.2 퀵 필터 (FR-UX-01)
보드 상단에 즉시 필터 토글:
- "내 이슈만"
- "최근 7일"
- "우선순위 High+"

### 13.1.3 스윔레인 (FR-BD-03)
가로 분리:
- 담당자별
- Epic별
- 우선순위별

## 13.2 백로그 (FR-BL)

### 13.2.1 LexoRank 정렬
- VARCHAR(50) 알파벳 키
- 드래그 시 위/아래 이웃의 중간값
- 주 1회 백그라운드 rebalance

### 13.2.2 백로그 → 스프린트 이동
- 드래그앤드롭
- 다중 선택 가능
- 스프린트 용량 표시 (스토리 포인트 합산)

## 13.3 타임라인/로드맵 (FR-TL)

### 13.3.1 Gantt 뷰
- 이슈를 시작일~종료일 기간으로 표시
- Epic을 부모 라인으로
- Story/Task는 자식 라인

### 13.3.2 의존성 라인 (FR-TL-02)
- `blocks` 관계의 이슈를 화살표로 연결
- 클릭 시 강조

### 13.3.3 줌 레벨 (FR-TL-03)
- 주 / 월 / 분기 단위

### 13.3.4 구현 (PoC 결정)
- 옵션 A: frappe-gantt (React 통합 부족)
- 옵션 B: 자체 SVG (의존성 라인 정확)
- Phase 0 PoC에서 결정

## 13.4 스프린트

### 13.4.1 상태
- PLANNED → ACTIVE → COMPLETED

### 13.4.2 시작 시 스냅샷
- 시작 시점의 이슈 + 스토리 포인트
- 번다운 차트의 기준선

### 13.4.3 회고 데이터
- 계획 대비 실제 완료
- 추가/제거된 이슈
- 평균 Cycle Time

## 13.5 리포트 (FR-RP)

### 13.5.1 번다운 차트 (FR-RP-01)
- X축: 스프린트 일자
- Y축: 잔여 추정 시간(초) — 스토리 포인트는 BTS에 미구현이라 이슈 추정 시간(`original_estimate_seconds`)으로 대체
- Ideal Line + Actual Line

### 13.5.2 번업 차트
- 누적 완료 + 총 스코프 (스코프 변경 가시화)

### 13.5.3 벨로시티 (FR-RP-02)
- 스프린트별 완료 스토리 포인트
- 직전 N 스프린트 평균

### 13.5.4 CFD (FR-RP-03) — 구현됨 (백엔드 D1~D5 PR #225 + 프론트 D6/D7 PR #227, 전체 완료)
- Cumulative Flow Diagram (누적 흐름 다이어그램)
- X축: 시간(일 단위, UTC), Y축: 상태 카테고리별 순간 이슈 수를 stacked
- **카테고리 3띠**(TODO/IN_PROGRESS/DONE) — 워크플로우 상태별 N띠 아님(프로젝트 무관 균일, ADR D1 확정)
- **데이터 = on-the-fly 역산**: `issue_change` status 전이 이력에서 각 이슈 상태 타임라인 재구성(스냅샷/스케줄러 0, ADR D2)
- 엔드포인트 `GET /api/v1/projects/{projectKey}/cfd?from=&to=`(모듈 issue-tracking, 프로젝트 BROWSE + 이슈별 가시성 필터)
- **프론트(PR #227)**: recharts 누적 영역 차트(`AreaChart` 3 stacked `Area`, 아래→위 DONE→IN_PROGRESS→TODO), 전용 라우트 `/projects/$projectKey/reports/cfd`(백로그 nav 진입), 순수변환 `toCfdSeries`+`isCfdEmpty` 단위·실렌더 E2E 위임. 날짜 피커 v1 미노출(백엔드 기본 30일). FR-RP-02 미러.
- ADR [CFD 데이터 모델](../decisions/2026-07-03-fr-rp-03-cfd.md)

### 13.5.5 Cycle Time / Lead Time (FR-RP-04)
- Cycle Time: In Progress → Done
- Lead Time: Created → Done
- 분포 히스토그램

## 13.6 데이터 소스

**번다운(FR-RP-01)은 on-the-fly in-memory 계산을 채택한다**(ADR [FR-RP-01 번다운/번업 차트 데이터 모델](../decisions/2026-07-02-fr-rp-01-burndown-burnup.md)). 요청마다 스프린트 소속 이슈의 `original_estimate_seconds`(총 스코프) + worklog `started_at` 일자별 누적을 조회해 시계열을 즉시 재구성한다.

- 신규 스냅샷 테이블·스케줄러 없음
- 과거 스프린트도 소급 계산 가능 (스냅샷 방식은 도입 이후 데이터만 쌓여 과거 소급이 불가능)
- 1,000명 규모에서 스프린트당 이슈·worklog 행 수가 적어 요청 시 계산 부담은 무시 가능

아래는 최초 설계 시점의 스냅샷 테이블 안이며, 번다운(FR-RP-01)은 위 결정으로 대체됐다. **벨로시티(FR-RP-02, ADR [벨로시티](../decisions/2026-07-02-fr-rp-02-velocity.md))·CFD(FR-RP-03, ADR [CFD](../decisions/2026-07-03-fr-rp-03-cfd.md))도 스냅샷 테이블을 채택하지 않고 on-the-fly 재구성으로 구현됐다** — 벨로시티는 현재 상태 집계, CFD는 `issue_change` status 전이 이력에서 상태 타임라인을 소급 재구성. 스냅샷 안은 셋 다 미채택.

```sql
-- 미채택 설계(참고). 벨로시티/CFD 구현 시 재검토 대상
CREATE TABLE sprint_burndown_snapshot (
    sprint_id BIGINT,
    snapshot_date DATE,
    remaining_points NUMERIC,
    completed_points NUMERIC,
    PRIMARY KEY (sprint_id, snapshot_date)
);
```

## 13.7 다음 챕터

- 대시보드 → [14. 대시보드/리포트](14-dashboard-reports.md)
- 프론트엔드 구현 → [21. 프론트엔드 아키텍처](21-frontend.md)
