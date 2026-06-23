<!-- FR-BL-02 백로그→스프린트 이동 백엔드(D1~D5) 기술 스펙 -->
# FR-BL-02 — 백로그 → 스프린트 이동 (백엔드 D1~D5) 스펙

> BC: agile-planning 단독 | 관계 모델: sprint_issues 조인(ADR 2026-06-24) | issue-tracking 무변경
> 범위(Maxi 확정): 스프린트 CRUD + 이슈 할당/해제 + 상태전이. 스프린트 내 순서(rank) 이연. 동시 ACTIVE 다중 허용.

## 1. 사용자 시나리오 (Given-When-Then)

- **S1 스프린트 생성**. Given 프로젝트 관리 권한 보유, When `POST /api/v1/sprints {projectKey, name}`, Then status=PLANNED 스프린트 생성 + 201.
- **S2 백로그→스프린트 할당**. Given PLANNED/ACTIVE 스프린트, When `POST /api/v1/sprints/{id}/issues {issueKey}`, Then sprint_issues에 (sprint_id, issue_id) 추가. 그 이슈가 이미 다른 스프린트에 있으면 자동 이동(기존 연관 제거).
- **S3 스프린트→백로그 해제**. Given 할당된 이슈, When `DELETE /api/v1/sprints/{id}/issues/{issueKey}`, Then 연관 제거 → 이슈는 백로그(미할당)로 복귀.
- **S4 스프린트 시작**. Given PLANNED 스프린트, When `POST /api/v1/sprints/{id}/start`, Then status=ACTIVE(다중 ACTIVE 허용 — 기존 ACTIVE 무관).
- **S5 스프린트 종료**. Given ACTIVE 스프린트, When `POST /api/v1/sprints/{id}/complete`, Then status=COMPLETED.
- **S6 스프린트 단건 조회**. Given 스프린트, When `GET /api/v1/sprints/{id}`, Then 스프린트 메타 + 할당 이슈 목록(가시성 필터 적용, issue rank NULLS LAST·created_at 순) + 카운트.
- **S7 프로젝트 스프린트 목록**. When `GET /api/v1/sprints?projectKey=`, Then 소프트삭제 제외 status별 목록.
- **S8 스프린트 수정/삭제**. PATCH(name/goal/기간), DELETE(소프트삭제).

## 2. 기능 요구사항 (FR)

- **FR1**. 스프린트는 프로젝트(project_key) 단위. name 필수, goal·startDate·endDate 선택.
- **FR2**. 상태는 `PLANNED → ACTIVE → COMPLETED` 단방향 전이. start(PLANNED→ACTIVE), complete(ACTIVE→COMPLETED). 그 외 전이는 거부.
- **FR3**. 동시 ACTIVE 스프린트 개수 제약 없음(다중 허용).
- **FR4**. 이슈↔스프린트는 **1:N**(한 이슈는 최대 1개 스프린트). `sprint_issues.issue_id` UNIQUE로 강제. 다른 스프린트 할당 시 기존 연관을 제거하고 이동(원자적).
- **FR5**. 할당 대상 이슈는 스프린트와 **같은 프로젝트**의 **가시 이슈**여야 한다(BoardIssueLookupPort로 검증). 타 프로젝트/미존재/소프트삭제 이슈는 거부.
- **FR6**. COMPLETED 스프린트는 이슈 할당/해제 불가(종료된 스프린트 불변). 거부(409).
- **FR7**. 할당 멱등성. 이미 같은 스프린트에 속한 이슈를 재할당하면 no-op 200(중복 INSERT 아님).
- **FR8**. 모든 변경 작업은 낙관적 잠금(version)으로 동시성 보호. 단순 조회는 잠금 없음.
- **FR9**. 백로그 = sprint_issues에 없는 프로젝트 가시 이슈(파생 개념, 별도 저장 안 함). 해제 시 자동으로 백로그 복귀. 백로그 조회는 `GET /api/v1/sprints/backlog?projectKey=`(BoardIssueLookupPort로 가시 이슈 조회 → sprint_issues에 있는 issue_id 제외 → rank NULLS LAST·created_at 순).
- **FR10 (version 정책)**. 이슈 할당/해제는 `sprint_issues`만 변경하고 `sprints` row는 불변(version·updated_at no-bump, 메모리 no-bump-sidecar-version). 상태전이(start/complete)·메타 수정(PATCH)은 `sprints.version` bump + 낙관적 잠금.
- **FR11 (소프트삭제 시 연관)**. 스프린트 소프트삭제(DELETE) 시 해당 `sprint_issues` 연관을 함께 제거 → 할당돼 있던 이슈는 백로그로 복귀(gap②). 완료 스프린트 이력 보존이 아니라 백로그 복귀가 사용자 기대에 부합.
- **FR12 (종료 시 미완료 이슈)**. complete(종료) 시 할당 이슈는 그대로 유지(자동 백로그 이동/다음 스프린트 이월은 이번 범위 밖, 후속). COMPLETED 후 할당/해제만 금지(FR6).

## 3. 비기능 요구사항 (NFR)

- **NFR1**. cross-BC 통신은 shared-kernel 포트(`BoardIssueLookupPort`)만 사용. issue-tracking 직접 import 금지(ArchUnit 보장).
- **NFR2**. 스프린트 단건 조회 할당 이슈 목록은 BOARD_CARD_FETCH_LIMIT류 상한 적용(대량 이슈 truncated 신호). 기존 board 상한 패턴 재사용.
- **NFR3**. 권한 판정은 `IssuePermissionResolver`(fail-closed, non-null 주입) 재사용.

## 4. API 인터페이스 (REST) — `@RequestMapping("/api/v1/sprints")`

| 메서드 | 경로 | 권한(IssueScope.Project) | 설명 |
|---|---|---|---|
| POST | `/api/v1/sprints` | CREATE | 생성(PLANNED). body: `{projectKey, name, goal?, startDate?, endDate?}` |
| GET | `/api/v1/sprints/{id}` | BROWSE | 단건 + 할당 이슈 목록 |
| GET | `/api/v1/sprints?projectKey=` | BROWSE | 프로젝트별 목록 |
| PATCH | `/api/v1/sprints/{id}` | CREATE | 수정. body: `{name?, goal?, startDate?, endDate?}` |
| DELETE | `/api/v1/sprints/{id}` | CREATE | 소프트삭제 |
| POST | `/api/v1/sprints/{id}/start` | CREATE | PLANNED→ACTIVE |
| POST | `/api/v1/sprints/{id}/complete` | CREATE | ACTIVE→COMPLETED |
| POST | `/api/v1/sprints/{id}/issues` | CREATE | 이슈 할당. body: `{issueKey}` |
| DELETE | `/api/v1/sprints/{id}/issues/{issueKey}` | CREATE | 이슈 해제 |
| GET | `/api/v1/sprints/backlog?projectKey=` | BROWSE | 백로그(미할당 가시 이슈) 목록. rank NULLS LAST·created_at 순 (gap①) |

- 응답 봉투. board 선례 `DataResponse`. actor는 컨트롤러에서 추출(인증 추출을 리소스 조회보다 먼저 — 메모리 auth-extraction-before-resource-lookup).
- 권한 스코프. 모두 `IssueScope.Project(sprint.projectKey)` (생성은 요청 projectKey).
- 할당/해제 권한은 일단 `CREATE`(스프린트 관리 행위, board 보드편집 선례). security-engineer plan-review 시 UPDATE 여부 최종 판정.

## 5. 데이터 모델 변경 (agile-planning, V503)

```sql
CREATE TABLE sprints (
    id          UUID PRIMARY KEY,
    project_key VARCHAR(...) NOT NULL,
    name        VARCHAR(...) NOT NULL,
    goal        TEXT,
    status      VARCHAR(16) NOT NULL DEFAULT 'PLANNED'
                CHECK (status IN ('PLANNED','ACTIVE','COMPLETED')),
    start_date  DATE,
    end_date    DATE,
    version     BIGINT NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at  TIMESTAMPTZ            -- 소프트삭제
);
CREATE INDEX idx_sprints_project ON sprints(project_key) WHERE deleted_at IS NULL;

CREATE TABLE sprint_issues (
    sprint_id  UUID NOT NULL REFERENCES sprints(id) ON DELETE CASCADE,
    issue_id   UUID NOT NULL,           -- issue-tracking 느슨참조(cross-BC FK 없음)
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (sprint_id, issue_id),
    UNIQUE (issue_id)                    -- FR4: 한 이슈는 최대 1 스프린트
);
CREATE INDEX idx_sprint_issues_sprint ON sprint_issues(sprint_id);
```

- init_codegen.sql(agile-planning) 미러 필수(메모리 jooq-init-codegen-mirror).
- `sprint_id` FK는 같은 모듈(agile-planning) 내라 정상. `issue_id`는 cross-BC라 FK 없음(board 선례).
- V번호는 머지 직전 재확인(메모리 migration-vnumber, 현재 agile 최신 V502 → V503).

## 6. 엣지 케이스

- **E1** 잘못된 상태 전이(PLANNED→COMPLETED 직접 / COMPLETED→ACTIVE / 이미 ACTIVE인데 start) → 409 `InvalidSprintTransitionException`.
- **E2** 존재하지 않거나 소프트삭제된 스프린트 → 404.
- **E3** 할당 이슈가 타 프로젝트 소속 → 400.
- **E4** 할당 이슈 미존재/소프트삭제(가시 목록에 없음) → 404.
- **E5** COMPLETED 스프린트에 할당/해제 시도 → 409(FR6).
- **E6** 권한 없음 → 403(IssuePermissionResolver fail-closed).
- **E7** 멱등 재할당(이미 그 스프린트) → 200 no-op(FR7).
- **E8** 다른 스프린트에 있던 이슈 할당 → 기존 연관 제거 후 이동(원자적, FR4). 200/201.
- **E9** 해제 시 그 스프린트에 없는 이슈 → 404(또는 멱등 204 — 구현 시 택1, 기본 404).
- **E10** name 누락/공백 → 400(@Valid).
- **E11** startDate > endDate → 400(기간 역전 검증).
- **E12** 동시 할당(같은 이슈 두 스프린트로) → UNIQUE(issue_id) 위반 → 409 변환(jOOQ ExceptionTranslator, 메모리 jooq-exception-translator-409).

## 7. 제약 조건

- 한 PR = 한 BC(agile-planning). issue-tracking 무변경.
- 도메인 예외는 agile-planning 전용 ExceptionHandler(basePackages 한정 — 메모리 domain-exception-http-handler-basepackage-scope, catch-all 500 삼킴 방지 메모리 catch-all-exceptionhandler).
- 신규 cross-BC 포트 구현 빈이 전체 컨텍스트 통합테스트 부팅을 깨지 않도록 주의(메모리 fr-nt-02 stub빈 / profile-scoped-bean). BoardIssueLookupPort는 기존 빈 재사용이라 신규 부팅 리스크 낮음.

## 8. 측정 가능한 완료 기준

- [ ] 스프린트 CRUD + 상태전이 2종 + 할당/해제 9개 엔드포인트 동작.
- [ ] sprint_issues UNIQUE(issue_id)로 1:N 강제 + 이동 원자성 통합테스트.
- [ ] 상태 전이 규칙(E1) + COMPLETED 불변(E5) 단위/통합 검증.
- [ ] 권한 403(E6) + 가시성/프로젝트 경계(E3/E4) HTTP 통합 검증.
- [ ] ArchUnit: agile-planning이 issue-tracking 직접 import 0(NFR1).
- [ ] ktlint + detekt(baseline) + generateJooq 통과.

## Brainstorming Check

✅ 통과 (adversarial self-review 1회, gap 3건 발견·반영).
- gap① 백로그(미할당 이슈) 조회 API 누락 → `GET /api/v1/sprints/backlog` 추가(FR9).
- gap② 스프린트 소프트삭제 시 sprint_issues 연관 처리 미명시 → 연관 제거·백로그 복귀(FR11).
- gap③ version 동시성 정책 미구분 → 할당/해제=no-bump, 상태전이/수정=version bump(FR10).
- (보조) 종료 시 미완료 이슈 자동 이월은 이번 범위 밖 명시(FR12).
모두 스펙 보강으로 해소 — Maxi 추가 결정 불필요.
