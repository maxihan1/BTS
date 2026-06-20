# FR-TT-02 — 이슈/사용자/기간별 시간 집계 — 스펙

> slug: fr-tt-02-worklog-aggregate · BC: issue-tracking · 선행: FR-TT-01
> ADR: [docs/adr/2026-06-20-worklog-aggregate-model.md](../adr/2026-06-20-worklog-aggregate-model.md)

## 개요

FR-TT-01이 쌓은 `worklogs`를 프로젝트 단위로 이슈/사용자/기간 차원에서 집계해 보고용으로 제공한다.
신규 cross-issue 엔드포인트 `GET /api/v1/worklogs/aggregate` (읽기 전용, 실시간 jOOQ GROUP BY).

## 사용자 시나리오 (Given-When-Then)

### S1. 이슈별 집계
- **Given** `BTS` 프로젝트에 worklog가 여러 이슈에 기록되어 있고, actor는 그 프로젝트 BROWSE 권한 보유
- **When** `GET /api/v1/worklogs/aggregate?project=BTS&by=issue`
- **Then** 200 + 이슈별 `timeSpentSeconds` 합계 버킷 배열(많이 쓴 순 DESC). 각 버킷은 issueKey 라벨 동반

### S2. 사용자별 집계
- **When** `GET /api/v1/worklogs/aggregate?project=BTS&by=user`
- **Then** 200 + author별 합계 버킷(DESC). 라벨=displayName(cross-BC 조회, 미존재 시 빈 문자열)

### S3. 기간별 집계 (월 단위)
- **When** `GET /api/v1/worklogs/aggregate?project=BTS&by=period&granularity=month&from=2026-01-01&to=2026-06-30`
- **Then** 200 + 월 버킷별 합계(시간순 ASC). 데이터 있는 버킷만(sparse)

### S4. 권한 없는 프로젝트
- **Given** actor가 `SECRET` 프로젝트 BROWSE 권한 없음
- **When** `GET /api/v1/worklogs/aggregate?project=SECRET&by=issue`
- **Then** 403 (다른 프로젝트 이슈 시간이 합계에 섞이지 않음 = 누출 차단)

### S5. 미인증
- **When** 인증 없이 호출 → **Then** 401 (리소스 조회 전에 actor 추출로 차단, 존재 probe 방지)

### S6. worklog 0건
- **Given** actor 권한 있으나 프로젝트에 활성 worklog 없음
- **When** `...?project=BTS&by=issue` → **Then** 200 + `buckets: []`, `totalTimeSpentSeconds: 0`

## 기능 요구사항 (FR)

- **FR1**. `GET /api/v1/worklogs/aggregate`는 `project`(필수), `by`(필수) 쿼리 파라미터로 집계한다.
- **FR2**. `by ∈ {issue, user, period}`. 그 외 값은 400.
- **FR3**. `by=period`는 `granularity ∈ {day, week, month}` 동반(미전달 시 기본 `day`). `by≠period`면 granularity는 무시.
- **FR4**. `from`/`to`(선택, `yyyy-MM-dd`)는 `started_at` 기준 필터. from 포함, to 당일 끝까지 포함(to+1일 00:00 UTC exclusive). 미전달 시 전체 기간.
- **FR5**. 권한 — actor가 `project`에 대해 `BROWSE` 권한(`IssueScope.Project(key)`)을 보유해야 함. 미보유 403.
- **FR6**. 집계 대상은 활성 worklog만(`worklogs.deleted_at IS NULL`) + 활성 이슈만(`issues.deleted_at IS NULL`) + 활성 프로젝트(`projects.deleted_at IS NULL`).
- **FR7**. 응답에 버킷 배열 + 전체 합계(`totalTimeSpentSeconds`)를 함께 반환.
- **FR8**. 정렬 — `by=issue|user`는 `timeSpentSeconds` DESC(동률 시 라벨 ASC 안정 정렬), `by=period`는 버킷 시각 ASC.

## API 인터페이스 (REST)

```
GET /api/v1/worklogs/aggregate
  ?project={key}        (필수, 프로젝트 키 — 예: BTS)
  &by={issue|user|period} (필수)
  &granularity={day|week|month} (선택, by=period 전용, 기본 day)
  &from={yyyy-MM-dd}    (선택)
  &to={yyyy-MM-dd}      (선택)

200 OK
{
  "data": {
    "project": "BTS",
    "by": "issue",
    "granularity": null,            // by=period일 때만 채움
    "from": "2026-01-01",           // 미전달 시 null
    "to": "2026-06-30",             // 미전달 시 null
    "totalTimeSpentSeconds": 86400,
    "buckets": [
      { "key": "BTS-12", "label": "BTS-12", "timeSpentSeconds": 50400, "worklogCount": 7 },
      { "key": "BTS-3",  "label": "BTS-3",  "timeSpentSeconds": 36000, "worklogCount": 4 }
    ]
  }
}
```

### 버킷 key/label 의미 (by별)

| by | key | label | 비고 |
|---|---|---|---|
| issue | issueKey (BTS-12) | issueKey | issues JOIN으로 key 획득 |
| user | authorId (UUID) | displayName | UserLookupPort.findDisplayNamesByIds, 미존재 시 "" |
| period | 버킷 시작일 (yyyy-MM-dd) | 동일 | date_trunc(granularity, started_at) UTC 기준 |

### 에러
- 400 — `project` 누락 / `by` 누락·무효 / `granularity` 무효 / `from`·`to` 형식 무효 / `from > to`
- 401 — 미인증·익명·비-UUID·nil-UUID actor
- 403 — `project`에 BROWSE 권한 없음

## 데이터 모델 변경

- **신규 스키마 없음**(읽기 전용). `worklogs`/`issues`/`projects` 기존 테이블 JOIN.
- 집계 쿼리.
  ```
  SELECT <dimension key>, SUM(w.time_spent_seconds), COUNT(*)
  FROM worklogs w
  JOIN issues i   ON w.issue_id = i.id  AND i.deleted_at IS NULL
  JOIN projects p ON i.project_id = p.id AND p.deleted_at IS NULL
  WHERE p.key = :project AND w.deleted_at IS NULL
    [AND w.started_at >= :from] [AND w.started_at < :toExclusive]
  GROUP BY <dimension>
  ```
- 인덱스 검토. 기존 `idx_worklogs_issue_id`(issue_id, 부분), `idx_worklogs_author_started`(author_id, started_at) 활용.
  by=user+기간 필터는 author_started 인덱스 활용. by=issue/period는 issue_id 인덱스 + issues JOIN.
  **신규 인덱스는 D3 성능 측정에서 NFR 미달 시에만 추가**(불필요 마이그레이션 회피).

## 엣지 케이스

- E1. `by=period` 빈 버킷(데이터 없는 기간)은 응답에 포함 안 함(sparse). dense 채움은 프론트(D6) 책임.
- E2. `granularity` 전달 + `by≠period` → granularity 무시(400 아님, 관대 처리). 응답 granularity=null.
- E3. by=user displayName 조회 실패/누락 → 빈 문자열(fail-safe, FR-TM-02 선례). 집계 자체는 절대 차단 안 함.
- E4. 프로젝트 존재하나 worklog 0 → 200 + 빈 배열.
- E5. 존재하지 않는 project 키 → BROWSE 권한 판정 결과에 따름(권한 없으면 403). 권한 있고 프로젝트 없으면 빈 결과 200(존재 probe 방지 — 404로 존재 여부 노출 안 함).
- E6. started_at은 TIMESTAMPTZ(UTC 저장). period 버킷·from/to 모두 UTC 기준 해석(요청 타임존 반영은 후속 FR).
- E7. from/to 둘 다 미전달 → 전체 기간 집계(프로젝트 단위라 감당 가능).
- E8. 페이지네이션 없음(프로젝트 단위 집계라 버킷 수 제한적). 대규모 프로젝트 cap은 NFR 측정 후 후속 검토.

## 제약 조건

- 읽기 전용 — `@Transactional(readOnly = true)`. mutation·이벤트 발행 없음.
- BC 격리 — author displayName은 `UserLookupPort`(shared-kernel) 통해서만 조회. identity-access 직접 import 금지.
- 권한은 `IssuePermissionResolver`(shared-kernel) 통해서만. 멤버십 role 직접 조회 금지(cross-BC resolver 창구).
- actor 추출을 프로젝트/리소스 조회보다 먼저(미인증 probe 방지 — FR-TT-01 컨트롤러 패턴).

## 비기능 요구사항 (NFR)

| 항목 | 임계 | 측정 |
|---|---|---|
| 프로젝트 worklog 5,000건 집계 p95 | < 500ms | JUnit + Testcontainers |

## 측정 가능한 완료 기준

- [ ] 3개 by 차원 × (필터 유/무) 통합 테스트 그린
- [ ] 권한 없는 프로젝트 403, 미인증 401, worklog 0건 빈 배열 검증
- [ ] displayName 조회 실패 fail-safe(빈 문자열) 검증
- [ ] from/to UTC 경계(to 당일 포함) 검증
- [ ] NFR 5,000건 < 500ms 실측 기록

## Brainstorming Check (self-review)

직접 sanity check로 도출·반영한 gap.
- period sparse/dense 정책 명시(E1) · granularity 오용 관대 처리(E2) · 전체 합계 total 필드 추가(FR7)
- 프로젝트 존재 probe 방지(E5, 404 대신 빈 결과) · 페이지네이션 부재 명시(E8) · 타임존 UTC 고정(E6)
✅ 통과 (1-pass self-review, Maxi 결정 3종으로 핵심 갈림길 사전 확정)
