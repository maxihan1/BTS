# ADR — Worklog 집계 모델 (FR-TT-02)

> 날짜: 2026-06-20
> 상태: 채택
> 관련 FR: FR-TT-02 (이슈/사용자/기간별 시간 집계)
> 선행: FR-TT-01 (Worklog), [2026-06-20-worklog-time-tracking-model.md](2026-06-20-worklog-time-tracking-model.md)
> 관련 product: agile-planning §5.2

## 맥락

FR-TT-02는 FR-TT-01이 쌓은 `worklogs` 데이터를 이슈/사용자/기간 차원으로 집계한다.
FR-TT-01의 worklog 조회는 단일 이슈 종속(`/api/v1/issues/{key}/worklogs`)이라 "이슈 VIEW 권한"
한 번으로 가시성을 보장했다. 그러나 집계는 **여러 이슈·여러 프로젝트를 가로지르므로**, FR-TT-01의
이슈별 권한 모델을 그대로 쓸 수 없다. 잘못 설계하면 actor가 볼 수 없는 이슈의 시간이 합계에 섞이는
권한 누출이 발생한다(FR-NT-03 visibility 누출 선례).

명세(§5.2)는 D레벨 골격만 제시하며, (1) 집계 권한 범위, (2) 계산 방식(머티뷰 검토), (3) 집계 차원을
미정으로 둔다. 본 ADR이 이 셋을 확정한다.

## 결정

### D1. BC 배치 — issue-tracking

FR-TT-01 ADR D1을 계승한다. 집계는 `worklogs` + `issues` JOIN 읽기 전용이라 issue-tracking BC에
구현한다(agile-planning 모듈 부트스트랩 안 함). product의 agile-planning §5.2 논리 라벨은 유지.

### D2. 권한 범위 — 프로젝트 단위 + Project scope VIEW 권한

- `GET /api/v1/worklogs/aggregate`는 **`project` 파라미터를 필수**로 받는다(단일 프로젝트 집계).
- actor가 해당 프로젝트에 대해 `IssuePermissionResolver.hasPermission(actor, VIEW_ISSUE, IssueScope.Project(projectId))`
  를 만족할 때만 집계를 반환한다. 미보유 시 403.
- **개별 이슈 보안수준(IssueSecurityDecider/security level)은 집계에 반영하지 않는다.** FR-TT-01 ADR D4의
  단순성 결정("이슈 VIEW 권한만, 항목별 가시성 없음")을 계승. 집계는 본질적으로 프로젝트 관리 보고서이며,
  이슈별 security level을 반영하면 집계 쿼리가 N개 이슈 권한 평가로 변질되어 성능·복잡도가 폭증한다.
- **근거**. 누출의 핵심 위험은 cross-project 혼입이다. project scope VIEW 게이트가 이를 차단한다.
  프로젝트 내부의 미세 가시성은 1K 사내 협업 규모에서 즉시 필요성이 낮다(필요 시 후속 FR).

### D3. 계산 방식 — 실시간 SQL 집계 (머티뷰 회피)

요청 시점에 jOOQ GROUP BY로 즉석 집계한다. 머티리얼라이즈드 뷰는 채택하지 않는다.

**근거**.
- 머티뷰는 사전 집계라 권한 필터를 동적으로 적용할 수 없다(권한 무관 전체 집계 → D2 누출 차단과 충돌).
- REFRESH 시점 관리(스케줄러/트리거) 운영 부담이 현 규모 대비 과하다.
- worklog 양은 1K 사용자 규모에서 실시간 GROUP BY로 충분히 빠르다. 기존 인덱스
  `idx_worklogs_author_started (author_id, started_at)`를 활용하고, 필요 시 집계 전용 인덱스를 D3 단계에서 추가 검토.
- 신규 마이그레이션은 (추가 인덱스가 필요할 때만) 최소 범위로 한정. 머티뷰/스키마 신설 없음.

### D4. 집계 차원 — by ∈ {issue, user, period}

- `by=issue` — issue_id별 time_spent_seconds 합계.
- `by=user` — author_id별 합계.
- `by=period` — 기간 버킷별 합계. 버킷 단위는 `granularity ∈ {day, week, month}` 파라미터(started_at 기준).
- **project는 그룹 축이 아니다.** `project`는 D2의 필수 필터로 고정되므로 그룹화 차원에서 제외한다.
- 기간 범위 필터(`from`/`to`, started_at 기준)는 모든 by에 적용 가능(상세는 spec).

### D5. 엔드포인트 — 신규 cross-issue 컨트롤러

`GET /api/v1/worklogs/aggregate?project={key}&by={dim}&from=&to=&granularity=`.
FR-TT-01의 `/api/v1/issues/{key}/worklogs`는 이슈 종속이라 cross-issue 집계에 부적합 → 신규 컨트롤러.
actor 추출을 리소스 조회보다 먼저 수행(FR-TT-01 컨트롤러 패턴 — 미인증 probe 방지).

## 결과

- 신규 도메인 타입. `WorklogAggregateDimension` enum(ISSUE/USER/PERIOD), 집계 결과 VO(`WorklogAggregateBucket` 등).
- 신규 엔티티/스키마 없음(읽기 전용). 추가 인덱스는 D3 성능 검토 결과에 따라 조건부.
- 신규 컨트롤러 + 서비스 + 리포지토리 집계 쿼리. 권한은 project scope VIEW 재사용.
- 트랜잭션. 읽기 전용(`@Transactional(readOnly = true)`).

## 미해결 (spec 위임)

- 응답 DTO 구조(버킷 배열 형태, 라벨/키 표현 — 예: by=issue면 issueKey 동반 여부).
- granularity 기본값 + from/to 미전달 시 기본 기간.
- 빈 결과(worklog 0건) 처리 — 빈 배열 vs 0 버킷.
- period 버킷 경계 타임존(started_at은 TIMESTAMPTZ — UTC 기준 버킷 vs 요청 타임존).
- by=issue/user 집계에 표시용 메타(issueKey/displayName) 동반 여부 — cross-BC UserLookup 필요성.
