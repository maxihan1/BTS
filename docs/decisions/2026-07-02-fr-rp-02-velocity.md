<!-- FR-RP-02 벨로시티 차트 도메인/설계 결정 기록 -->
# ADR 2026-07-02 — FR-RP-02 벨로시티 차트 (Velocity Chart)

- 상태: Accepted (domain 게이트, Maxi 확정 2026-07-02)
- 관련 FR: FR-RP-02 (notification-dashboard §4.2)
- 선행: [2026-07-02-fr-rp-01-burndown-burnup], FR-EP-02 진행률
- Plan: docs/plans/2026-07-02-fr-rp-02-velocity.md

## 배경

벨로시티 차트는 여러 완료 스프린트에 걸쳐 팀이 스프린트마다 얼마나 처리하는지(계획 대비 완료)를 막대 차트로 보여주는 애자일 리포트. Jira 벨로시티 차트가 원형. 선행 FR-RP-01(번다운)이 agile-planning 모듈에 on-the-fly 계산 방식으로 구현됨.

## 결정

### D1. 지표 단위 = 추정 시간(초)
스토리포인트 필드가 코드베이스에 존재하지 않음(전수 grep 0건). 유일한 추정치 `issues.original_estimate_seconds`(초)를 사용. FR-RP-01 번다운과 동일 지표로 일관성 확보. 차트 표시 단위는 시간(h).

### D2. 막대 구성 = 계획(Commitment) vs 완료(Completed) 2막대
- **Commitment(계획량)** = 스프린트에 현재 속한 가시 이슈들의 `Σ original_estimate_seconds`.
- **Completed(완료량)** = 그중 현재 워크플로우 상태 카테고리가 `DONE`인 이슈들의 `Σ original_estimate_seconds`.
- 두 값의 스프린트별 비교로 달성률 파악. 평균 벨로시티 = 완료량 평균.

### D3. 완료 판정 = on-the-fly 현재 상태 (FR-RP-01 deviation 계승)
완료 여부는 조회 시점의 현재 상태가 DONE 카테고리인지로 판정(스냅샷/`issue_history` 미사용). 완료(`COMPLETED`) 스프린트는 역사적으로 확정돼 이슈 상태 변동이 거의 없어 현재 상태 기준이 타당. `issue_history` 기반 "스프린트 종료 시점 완료"는 FR-RP-03(CFD)/FR-RP-04(Cycle/Lead Time) 도구로 위임.

DONE 판정은 `WorkflowStateCatalog.listStates(projectKey, issueTypeKey) → category==DONE`(FR-EP-02 선례). 타입별 캐싱 N+1 차단, `WorkflowSchemeNoDefaultException` 폴백(미할당 타입 이슈 = 미완료 취급, 운영 500 차단).

### D4. 모듈 = agile-planning, 엔드포인트 = 프로젝트 스코프
`GET /api/v1/projects/{projectKey}/velocity`. Sprint 애그리거트와 co-located(FR-RP-01 선례). 논리 BC 라벨은 notification-dashboard 유지, 총 FR 카운트 불변.

### D5. 신규 cross-BC 포트 `SprintVelocityLookupPort`
기존 `SprintBurndownLookupPort.fetchBurndownSource`는 scope(전체 추정합)만 반환해 완료분을 구분하지 못함. 신규 포트를 shared-kernel에 정의하고 issue-tracking이 구현. 어댑터가 (a) 이슈별 가시성 필터, (b) `original_estimate_seconds` 합, (c) `WorkflowStateCatalog` DONE 판정을 한 곳에서 수행. 정확한 시그니처는 plan에서 확정.

### D6. PR 분할 = 백엔드/프론트 2 PR
백엔드 PR(D1~D5): 도메인 VO + 계산 + 포트 + 컨트롤러 + 테스트. 프론트 PR(D6/D7): recharts 바 차트 + E2E. FR-RP-01 선례와 동일.

## 보안
- 프로젝트 **BROWSE** 권한 필수(없으면 403).
- **이슈별 가시성 필터** 재사용(`filterVisibleIssueKeys` → `buildActiveSecureWhere`). 기밀 이슈의 추정치가 계획/완료 합에 새어들지 않도록 필터 후 집계. FR-RP-01 게이트2 C1과 동일 원칙.
- 인증 actor 추출을 리소스 조회(404)보다 먼저(existence-probe 차단, 번다운 컨트롤러 선례).

## 대안 (기각)
- **이슈 개수 기반 지표**: 이슈별 크기 차이를 무시(3분짜리 1개 = 1주짜리 1개)해 벨로시티 의미 왜곡. 추정 시간이 더 정확.
- **완료만 1막대**: 계획 대비 달성률을 못 보여줌. 2막대가 표준.
- **스냅샷 테이블 + 스케줄러**: 완료 스프린트는 확정 상태라 on-the-fly로 충분. 운영 부담만 증가(FR-RP-01 D3 deviation과 동일 판단).

## 결과
- 신규 DB 스키마 0(기존 sprints/sprint_issues/issues/워크플로우 재사용).
- 신규 포트 1개(`SprintVelocityLookupPort`).
- agile-planning에 `SprintVelocityController/Service` + 순수 계산 VO 추가.
