# FR-RP-02 벨로시티 차트 — 스펙 (백엔드 PR, D1~D5)

> BC: notification-dashboard (모듈 agile-planning) · slug: fr-rp-02-velocity
> ADR: docs/decisions/2026-07-02-fr-rp-02-velocity.md
> 범위: **백엔드만**. 프론트 D6/D7(recharts 바 차트 + E2E)는 후속 PR.

## 사용자 시나리오 (Given-When-Then)

### S1. 완료 스프린트가 여러 개인 프로젝트
- **Given** 프로젝트 `PROJ`에 BROWSE 권한이 있는 사용자, `COMPLETED` 스프린트 3개(각 이슈에 추정 시간 설정)
- **When** `GET /api/v1/projects/PROJ/velocity`
- **Then** 200 OK, 스프린트 3개 각각의 `commitmentSeconds`(계획량)와 `completedSeconds`(완료량)가 시간순(오래된→최신)으로 반환. 평균값도 함께.

### S2. 완료 스프린트가 없는 프로젝트
- **Given** BROWSE 권한 있는 사용자, `COMPLETED` 스프린트 0개(PLANNED/ACTIVE만 존재)
- **When** 벨로시티 조회
- **Then** 200 OK, `sprints: []`, 평균 0.

### S3. 기밀 이슈 누출 차단
- **Given** 완료 스프린트에 사용자가 볼 수 없는(이슈 보안 수준) 이슈가 섞여 있음
- **When** 벨로시티 조회
- **Then** 보이지 않는 이슈의 추정 시간은 `commitmentSeconds`/`completedSeconds` 어느 쪽에도 합산되지 않음.

### S4. 권한 없음
- **Given** 프로젝트에 BROWSE 권한이 없는 사용자
- **When** 벨로시티 조회
- **Then** 403.

### S5. 미인증
- **Given** 인증 토큰 없음
- **When** 벨로시티 조회
- **Then** 401 (리소스 조회 이전에 actor 추출 실패로 거부 — 존재 probe 차단).

### S6. 미완료 판정 (워크플로우 스킴 미할당 타입)
- **Given** 완료 스프린트의 이슈 타입에 워크플로우 스킴이 할당되지 않음
- **When** 벨로시티 조회
- **Then** 500이 아니라 200. 해당 이슈는 완료로 집계되지 않음(commitment엔 포함, completed엔 미포함).

## 기능 요구사항 (FR)

- **FR1** 프로젝트의 `COMPLETED` 스프린트 중 최근 `limit`개를 대상으로 스프린트별 계획량/완료량을 집계한다.
- **FR2** 계획량(commitment) = 스프린트에 **현재 속한** 가시 이슈들의 `Σ original_estimate_seconds`(NULL=0).
  - **Deviation 명시**: Jira 정석 벨로시티의 commitment는 "스프린트 시작 시점 커밋된 범위"지만, `issue_history` 없이는 과거 시점 범위 복원 불가. on-the-fly 원칙(ADR D3)상 "현재 스프린트에 속한 이슈(`sprint_issues` 조인 현재값)"로 측정한다. 완료 스프린트는 확정 상태라 실무상 차이 미미. `issue_history` 기반 정밀 commitment는 FR-RP-03/04 도구로 위임.
- **FR3** 완료량(completed) = 그 가시 이슈 중 **현재 워크플로우 상태 카테고리가 DONE**인 이슈들의 `Σ original_estimate_seconds`.
- **FR4** 완료 판정은 조회 시점 현재 상태 기준(on-the-fly, 스냅샷/issue_history 미사용). DONE 여부는 `WorkflowStateCatalog.listStates(projectKey, issueTypeKey) → category==DONE`.
- **FR5** 응답은 스프린트를 **시간순 오름차순**(선택된 N개 중 오래된 것부터)으로 반환. 차트 X축이 좌→우로 시간 진행.
- **FR6** 응답에 전체 평균 `averageCommitmentSeconds`, `averageCompletedSeconds`(반환 스프린트 대상 산술평균, 스프린트 0개면 0) 포함.

## 비기능 요구사항 (NFR)

- **NFR1 (N+1 차단)** 스프린트별 이슈키는 배치 조회(`findIssueKeysByProject`), 포트는 전체 이슈키를 **1회 호출**로 집계. DONE 판정은 이슈 타입별 `listStates` 결과를 캐싱해 타입당 1회만 조회(FR-EP-02 선례).
- **NFR2 (성능 경계)** 대상 스프린트 수는 `limit`(기본 10, [1,50] 클램프)로 상한. 무한 스프린트 조회 없음.
- **NFR3 (보안)** 프로젝트 BROWSE + 이슈별 가시성 필터(`filterVisibleIssueKeys`→`buildActiveSecureWhere`) 재사용. 전용 보안 술어 신설 금지(구조적 상속).
- **NFR4 (BC 격리)** agile-planning은 issue/워크플로우 데이터를 shared-kernel 포트로만 접근. 직접 import 금지.
- **NFR5 (완제품)** 401/403 에러 코드·핸들러는 기존 `SprintExceptionHandler` 확장 또는 신규 컨트롤러 전용 advice로 처리. catch-all이 401/403을 500으로 변질시키지 않도록 `ResponseStatusException` passthrough 유지.

## API 인터페이스 (REST)

### `GET /api/v1/projects/{projectKey}/velocity`

- **Query**: `limit` (int, optional, default 10, [1,50] 클램프) — 대상 최근 완료 스프린트 수.
- **200 OK** — `DataResponse<VelocityResponse>`:

```json
{
  "data": {
    "projectKey": "PROJ",
    "averageCommitmentSeconds": 288000,
    "averageCompletedSeconds": 244800,
    "sprints": [
      {
        "sprintId": "…uuid…",
        "name": "Sprint 1",
        "startDate": "2026-05-01",
        "endDate": "2026-05-14",
        "commitmentSeconds": 300000,
        "completedSeconds": 250000
      }
    ]
  }
}
```

- `startDate`/`endDate`는 스프린트에 미설정 시 `null`(벨로시티는 날짜 불필요 — 번다운과 달리 422 없음).
- `sprints`는 시간순 오름차순.
- **401** 미인증 / **403** BROWSE 권한 미충족.
- 프로젝트 미존재·무권한 처리는 `BacklogApplicationService.getBacklog` 패턴을 그대로 따른다(존재 probe 차단 방식 일치).

### 응답 DTO 형태
- `VelocityResponse(projectKey: String, averageCommitmentSeconds: Long, averageCompletedSeconds: Long, sprints: List<VelocityPointResponse>)`
- `VelocityPointResponse(sprintId: UUID, name: String, startDate: LocalDate?, endDate: LocalDate?, commitmentSeconds: Long, completedSeconds: Long)`
- BurndownResponse처럼 `from(result)` companion 매핑. `DataResponse`로 래핑.

## 신규 cross-BC 포트

### `SprintVelocityLookupPort` (shared-kernel 정의, issue-tracking 구현)

시그니처(초안, plan에서 최종 확정):
```kotlin
fun fetchVelocitySource(
    issueKeysBySprint: Map<UUID, Set<String>>,  // sprintId → 이슈키 집합
    projectKey: String,
    viewerUserId: UUID,
): Map<UUID, VelocityContribution>  // sprintId → 집계 결과
// VelocityContribution(commitmentSeconds: Long, completedSeconds: Long)
```
- fail-safe default: 빈 맵 반환(포트 미구현/부재 시 운영 안전).
- 구현(issue-tracking 어댑터)이 한 곳에서 수행: (a) 전체 이슈키를 가시 이슈로 필터, (b) `original_estimate_seconds` 합(스프린트별), (c) `WorkflowStateCatalog` DONE 판정(타입별 캐싱), (d) 스프린트별 commitment/completed 집계.
- 빈 이슈키 집합 early-return(jOOQ empty-IN 함정 회피).
- 어댑터 메서드는 `@Transactional`(read-only) — `WorkflowStateCatalog.listStates`의 `MANDATORY` 전파 충족.
- **부분 반환 허용**: 포트는 데이터 있는 sprintId만 반환할 수 있고, 서비스는 반환되지 않은 sprintId를 `VelocityContribution(0, 0)`으로 기본 처리(E2 빈 스프린트 커버). 삭제된 이슈(`deleted_at`)는 집계 쿼리에서 제외(번다운 어댑터 선례).

## 데이터 모델 변경

**없음.** 기존 재사용.
- `sprints`(status COMPLETED, start/end nullable), `sprint_issues`(조인).
- `issues.original_estimate_seconds`(INT NULL, V027).
- 워크플로우 `WorkflowStateCatalog` / `StateCategory{TODO,IN_PROGRESS,DONE}`.

## 엣지 케이스

| # | 케이스 | 처리 |
|---|---|---|
| E1 | 완료 스프린트 0개 | `sprints: []`, 평균 0, 200 |
| E2 | 스프린트에 이슈 0개 | commitment 0, completed 0 |
| E3 | 이슈 추정 시간 전부 NULL | commitment 0, completed 0 (NULL=0) |
| E4 | 기밀 이슈 섞임 | 가시성 필터로 제외 후 집계(S3) |
| E5 | 스킴 미할당 타입 | 미완료 취급(commitment만), 500 차단(S6) |
| E6 | 완료 스프린트가 limit보다 많음 | 최근 limit개만, 오래된 것부터 정렬 |
| E7 | 스프린트 날짜 미설정 | start/end null로 포함(422 없음) |
| E8 | limit ≤ 0 또는 > 50 | [1,50]로 클램프 |

## 제약 조건

- 신규 DB 스키마 0. 스토리포인트 미사용(초 단위).
- 한 PR = 한 BC(agile-planning). issue/워크플로우는 포트 경유.
- 프론트/E2E는 이 PR 범위 밖.

## 측정 가능한 완료 기준

- [ ] `GET /api/v1/projects/{projectKey}/velocity` 200 응답, S1~S6 시나리오 통합 테스트 통과.
- [ ] 순수 집계 VO 단위 테스트(commitment/completed/평균 계산, NULL·빈집합 경계).
- [ ] issue-tracking 어댑터 Testcontainers 테스트(가시성 필터 + DONE 판정 + 스프린트별 집계).
- [ ] C1 기밀 이슈 제외 가드(S3) 통합 테스트.
- [ ] 모듈 `./gradlew :backend:modules:agile-planning:test :backend:modules:issue-tracking:test` + ktlint/detekt(--rerun-tasks) green.
- [ ] verify-master-plan 통과(카운트 불변, D단계 마킹).

## Brainstorming Check

✅ 통과 (1회 iteration). 자체 적대 검토로 3개 보강.
- `unit` 필드 제거(번다운 선례가 `...Seconds` 필드명으로 자기서술, 중복 제거 — Simplicity First).
- commitment="현재 스프린트 속한 이슈" deviation 명시(Jira "시작 시점 커밋"과 차이, issue_history 부재로 불가피).
- 포트 부분 반환 시 서비스가 (0,0) 기본 처리 명시(E2 빈 스프린트) + 삭제 이슈 제외 명시.
- 잔여 확인 항목(plan에서 확정): 프로젝트 미존재 404 vs 403(BacklogApplicationService 패턴 대조), 포트 최종 시그니처.
