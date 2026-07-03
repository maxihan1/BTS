# ADR — Cycle Time / Lead Time 분포 데이터 모델 (FR-RP-04)

> 날짜: 2026-07-03
> 상태: 채택
> 관련 FR: FR-RP-04 (Cycle Time / Lead Time 분포)
> 관련 SDD: §13.5.5 (리포트), §14 (dashboard-reports)
> 선행: FR-RP-03(CFD, ADR `2026-07-03-fr-rp-03-cfd`) — 상태 이력 역산 인프라 재사용
> 관련 PR: #228

## 맥락

FR-RP-04는 프로젝트의 **완료된 이슈**들이 얼마나 걸려서 끝났는지의 **분포**를 시각화한다.
- **Lead Time** = 이슈 생성(created) → 완료까지의 총 소요.
- **Cycle Time** = 첫 작업 착수(IN_PROGRESS 카테고리 최초 진입) → 완료까지의 소요.

CFD(FR-RP-03)와 데이터 소스는 같다 — `issue_change_group`/`issue_change_item`(V018, FR-HS-01)의
status 전이 이력에서 각 이슈의 상태 타임라인을 재구성한다. 그러나 CFD가 "날짜별 카테고리 누적 카운트"를
day 단위로 계산한 것과 달리, FR-RP-04는 **이슈별 소요 기간(duration)의 분포**를 초 단위로 계산한다
(같은 날 완료된 이슈가 day 절삭으로 0이 되면 분포가 무의미해지기 때문).

엔드포인트는 `GET /api/v1/projects/{projectKey}/cycle-time?from=&to=`(프로젝트 스코프, CFD 동형).

## 결정

### D1. Cycle Time 정의 + 엣지 처리 (Maxi 확정)

- **Cycle Time** = 이슈의 **첫 IN_PROGRESS 카테고리 status 전이** 시각 → **마지막 DONE 카테고리 status 전이**(완료) 시각.
- **Lead Time** = 이슈 `created_at` → 마지막 DONE 전이 시각.
- **IN_PROGRESS 미경유**(TODO→DONE 직행, 예: 즉시 close) 이슈는 **Cycle 분포에서 제외**하고 **Lead 분포에만** 집계한다(Cycle 표본 수 < Lead 표본 수).
- **재오픈**(DONE→IN_PROGRESS→DONE)은 첫 IN_PROGRESS → **마지막** DONE으로 계산(재작업이 cycle을 늘림).
- **음수**(firstInProgress > lastDone) 이슈는 Cycle 제외(Lead 유지).

**근거**. 애자일 표준 정의(Kanban cycle time = 작업 착수→완료). 미경유 이슈에 cycle=0을 부여하면 분포가
왜곡되므로 제외가 더 정직하다.

### D2. 모집단 + 창(window) (Maxi 확정)

- **모집단** = 이슈의 **마지막 DONE 카테고리 status 전이** 시각(`completedAt`)의 UTC 날짜가 창 `[from, to]`
  안에 드는(완료된) 이슈. 소프트 삭제 이슈 제외. 뷰어 가시 이슈로 한정.
- 창은 UTC 날짜(ISO `yyyy-MM-dd`), 생략 시 기본 최근 30일, 상한 180일(Clock 주입). 잘못된 창(`from>to`·파싱 실패·상한 초과)은 400.

**근거**. "이 기간에 완료된 일들의 소요 시간" — 애자일 리포트 표준(완료일 기준 스냅샷).

### D3. 응답 형태 — 이슈별 샘플 + 서버 계산 요약 통계 (Maxi 확정)

- cycle/lead 각각 `samples: [{issueKey, seconds}]`(가시성 필터 통과 이슈만·초 오름차순) + 요약 통계
  `{count, min, max, avg, p25, p50, p75, p90}`.
- 백분위 = **nearest-rank**(오름차순 `v[0..n-1]`, `idx = clamp(ceil(p/100·n)−1, 0, n−1)`, 값 `v[idx]`). `count==0`이면 통계 필드 null.
- **p25 포함 근거**. 프론트 박스플롯 상자 = min·p25·p50·p75·max. 서버가 p25까지 제공해 프론트가 표본을
  다시 백분위 계산하지 않아도 되고 "중앙값 두 개"(서버 p50 vs 프론트 재계산) 불일치를 방지한다.

**근거**. 샘플을 주면 프론트가 히스토그램 binning을 자유롭게, 서버 통계로 박스플롯/요약 타일을 그린다.
백엔드가 계산·프론트 순수변환이라는 FR-RP-01/02/03 선례 정합. 페이로드 = O(창 내 완료 이슈 수),
v1 캡 없음(히스토그램 정확도 우선·무언의 절삭 금지), 1K 규모·180일 상한서 유계.

### D4. 모듈 배치 — issue-tracking (CFD 동형)

CFD와 동일하게 `issue-tracking` 모듈에 구현한다.

**근거**.
- 필요 데이터(`issues` + `issue_change_group`/`issue_change_item`)가 모두 issue-tracking BC 소유.
  스프린트를 읽지 않으므로 cross-BC 조회 포트가 불필요(번다운/벨로시티와 다름).
- 상태→카테고리 매핑에 필요한 `WorkflowStateCatalog`(shared-kernel SPI)는 issue-tracking이 이미
  `IsolatedWorkflowStateLookup`(REQUIRES_NEW)로 소비 중 — 재사용.
- **fr-index 논리 BC 라벨(notification-dashboard)은 유지**(물리 모듈 ≠ 논리 라벨, FR-RP-01/02/03 선례).
  notification-dashboard=14·총수 123 카운트 불변, `verify-master-plan.sh`는 BC↔모듈 매핑을 강제하지 않는다.

### D5. 보안 그레인 — 이슈별 가시성 필터 (CFD D4 동형)

프로젝트 BROWSE 통과만으로는 부족하다 — 샘플의 issueKey·개수로 기밀 이슈 존재를 추론할 수 있다.
- 2단 필터. (1) 프로젝트 BROWSE 선검사(repo 조회 전, 존재 probe 차단) + (2) 집계 전
  `IssueRepository.fetchActiveVisibleIssuesForCycleTime`가 정본 보안 술어 `buildActiveSecureWhere`를
  **재사용**(복제 금지 — isomorphic-clone-permission-guard-gap 회귀 방지)해 뷰어 가시 이슈만 조회.
- 미존재 프로젝트/무권한 모두 403(존재 여부 노출 안 함).

### D6. 공유 프리미티브 중립 패키지 추출 (CONCERN-1, 게이트1 Maxi 확정)

CFD의 상태 이력 재구성 프리미티브(`CfdStatusHistoryRepository`·`CfdCategory`·`CfdStatusChangeRow`)를
CFD 색채 없는 중립 패키지로 추출해 CFD·cycletime이 공유한다.
- `com.bts.issue.statushistory.StatusCategory`(구 `CfdCategory`).
- `com.bts.issue.statushistory.repository.StatusHistoryRepository`(구 `CfdStatusHistoryRepository`. jOOQ
  ArchUnit `..repository..` 화이트리스트 충족 — 패키지에 `.repository.` 세그먼트 포함).
- `com.bts.issue.statushistory.repository.StatusChangeRow`(구 `CfdStatusChangeRow`).

**근거**. plan-eng-review CONCERN-1 — cycletime이 `Cfd*` 접두 프리미티브를 재사용하면 이름이
feature-coupling으로 읽힌다. Maxi가 reuse-as-is 대신 지금 추출을 택함. 순수 rename/move 리팩터(행위 불변,
기존 CFD 테스트가 안전망·회귀 0)를 선행 후 cycletime이 중립 심볼 위에 얹힌다(Beck "make the change easy,
then make the easy change").

## 새 도메인 개념

- **Cycle Time** — 이슈가 첫 작업 착수(IN_PROGRESS 카테고리 최초 진입)부터 완료(DONE)까지 걸린 시간. (FR-RP-04)
- **Lead Time** — 이슈가 생성(created)부터 완료(DONE)까지 걸린 총 시간. (FR-RP-04)
- **CycleTimeStats** (VO, 영속 아님) — 초 리스트의 요약 통계 `{count, min, max, avg, p25, p50, p75, p90}`.
  nearest-rank 백분위. count=0이면 통계 null. read-model, 테이블 없음.
- **IssueDurationInput** (VO) — 서비스가 status 전이 이력을 축약한 이슈별 입력
  `{issueKey, createdAt, firstInProgressAt?, lastDoneAt?}`. 계산기 순수성 유지용.

## v1 한계 (수용)

- status 전이 이력 없는 이슈(V018 이전 생성·직접 DONE/IN_PROGRESS 생성) 제외 — 완료/착수 시각을 전이 이력에서만
  신뢰(레거시 오-계산 방지, CFD "V018 이전 이슈" 동종).
- 완료 후 창 밖에서 재완료한 이슈는 마지막 DONE(창 밖) 기준으로 제외될 수 있음(on-the-fly 철학 정합).
- `samples` 페이로드 = O(창 내 완료 이슈 수), v1 캡 없음. 장래 대용량 시 서버 사이드 binning 전환 여지.

## SDD 동기화 대상 (같은 PR 내 필수)

- **SDD §13.5.5** — 구현 상세(전이 기반 duration·초 단위·응답 샘플+백분위·엔드포인트·on-the-fly·공유 프리미티브) 반영.
- product `notification-dashboard.md §4.4` — D1~D5 마킹 + `구현 범위` 박스.
- fr-index/README/CLAUDE FR 카운트 불변(BC 라벨 유지, 95/123 불변).
