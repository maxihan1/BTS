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
- Y축: 남은 스토리 포인트
- Ideal Line + Actual Line

### 13.5.2 번업 차트
- 누적 완료 + 총 스코프 (스코프 변경 가시화)

### 13.5.3 벨로시티 (FR-RP-02)
- 스프린트별 완료 스토리 포인트
- 직전 N 스프린트 평균

### 13.5.4 CFD (FR-RP-03)
- Cumulative Flow Diagram
- X축: 시간, Y축: 누적 이슈 수
- 카테고리별 컬러 스택

### 13.5.5 Cycle Time / Lead Time (FR-RP-04)
- Cycle Time: In Progress → Done
- Lead Time: Created → Done
- 분포 히스토그램

## 13.6 데이터 소스

번다운/벨로시티/CFD는 매일 자정 스냅샷 테이블에 저장:

```sql
CREATE TABLE sprint_burndown_snapshot (
    sprint_id BIGINT,
    snapshot_date DATE,
    remaining_points NUMERIC,
    completed_points NUMERIC,
    PRIMARY KEY (sprint_id, snapshot_date)
);
```

매일 비동기 작업으로 갱신. 실시간은 in-memory 계산.

## 13.7 다음 챕터

- 대시보드 → [14. 대시보드/리포트](14-dashboard-reports.md)
- 프론트엔드 구현 → [21. 프론트엔드 아키텍처](21-frontend.md)
