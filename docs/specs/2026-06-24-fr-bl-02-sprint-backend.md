<!-- FR-BL-02 백로그→스프린트 이동 백엔드(D1~D5) 기술 스펙 -->
# FR-BL-02 — 백로그 → 스프린트 이동 (백엔드 D1~D5) 스펙

> BC: agile-planning 단독 | 관계 모델: sprint_issues 조인(ADR 2026-06-24) | issue-tracking 무변경
> 범위(Maxi 확정): 스프린트 CRUD + 이슈 할당/해제 + 상태전환. 스프린트 내 순서(rank) 이연. 동시 ACTIVE 다중 허용.

## 1. 사용자 시나리오 (Given-When-Then)

- **S1 스프린트 생성**. Given 프로젝트 관리 권한 보유, When `POST /api/v1/sprints {projectKey, name}`, Then status=PLANNED 스프린트 생성 + 201.
- **S2 백로그→스프린트 할당**. Given PLANNED/ACTIVE 스프린트, When `POST /api/v1/sprints/{id}/issues {issueKey}`, Then sprint_issues에 (sprint_id, issue_key) 추가. 그 이슈가 이미 다른 스프린트에 있으면 자동 이동(기존 연관 제거).
- **S3 스프린트→백로그 해제**. Given 할당된 이슈, When `DELETE /api/v1/sprints/{id}/issues/{issueKey}`, Then 연관 제거 → 이슈는 백로그(미할당)로 복귀.
- **S4 스프린트 시작**. Given PLANNED 스프린트, When `POST /api/v1/sprints/{id}/start`, Then status=ACTIVE(다중 ACTIVE 허용 — 기존 ACTIVE 무관).
- **S5 스프린트 종료**. Given ACTIVE 스프린트, When `POST /api/v1/sprints/{id}/complete`, Then status=COMPLETED.
- **S6 스프린트 단건 조회**. Given 스프린트, When `GET /api/v1/sprints/{id}`, Then 스프린트 메타 + 할당 이슈 목록(가시성 필터 적용, sprint_issues.created_at 순 — **rank 정렬은 포트가 rank 미노출이라 D6에서 결정**, eng-N2) + 카운트.
- **S7 프로젝트 스프린트 목록**. When `GET /api/v1/sprints?projectKey=`, Then 소프트삭제 제외 status별 목록.
- **S8 스프린트 수정/삭제**. PATCH(name/goal/기간), DELETE(소프트삭제).

## 2. 기능 요구사항 (FR)

- **FR1**. 스프린트는 프로젝트(project_key) 단위. name 필수, goal·startDate·endDate 선택.
- **FR2**. 상태는 `PLANNED → ACTIVE → COMPLETED` 단방향 전환. start(PLANNED→ACTIVE), complete(ACTIVE→COMPLETED). 그 외 전환은 거부.
- **FR3**. 동시 ACTIVE 스프린트 개수 제약 없음(다중 허용).
- **FR4**. 이슈↔스프린트는 **1:N**(한 이슈는 최대 1개 스프린트). `sprint_issues.issue_key` UNIQUE로 강제. 다른 스프린트 할당 시 기존 연관을 제거하고 이동(원자적).
- **FR5**. 할당 대상 이슈는 스프린트와 **같은 프로젝트**의 **가시 이슈**여야 한다. 검증은 **단건 가시성 포트** `BoardIssueLookupPort.isVisibleIssue(projectKey, issueKey, viewerUserId): Boolean`(Maxi 게이트1 확정 — 신규 default 메서드 + issue-tracking adapter 구현, truncated 오거부 회피). false면 거부.
- **FR5-1 (probe 차단, security-B2)**. 타 프로젝트·미존재·소프트삭제·미가시를 **모두 단일 404로 수렴**(존재 probe oracle 제거 — E3/E4 분리 폐기). 권한(403)은 가시성 검증보다 먼저라 미인증/무권한자는 404 표면에 도달 못 함.
- **FR6**. COMPLETED 스프린트는 이슈 할당/해제 불가(종료된 스프린트 불변). 거부(409).
- **FR7**. 할당 멱등성. 이미 같은 스프린트에 속한 이슈를 재할당하면 no-op 200(중복 INSERT 아님).
- **FR8**. 모든 변경 작업은 낙관적 잠금(version)으로 동시성 보호. 단순 조회는 잠금 없음.
- **FR9**. 백로그 = sprint_issues에 없는 프로젝트 가시 이슈(파생 개념, 별도 저장 안 함). 해제 시 자동으로 백로그 복귀. **백로그 조회 API 자체는 D6 이연**(rank 정렬이 본질 → 포트 확장 회피, 위 API 표 주석). 이번 범위에서 할당/해제로 sprint_issues 연관만 정확히 관리.
- **FR10 (version 정책)**. 이슈 할당/해제는 `sprint_issues`만 변경하고 `sprints` row는 불변(version·updated_at no-bump, 메모리 no-bump-sidecar-version). 상태전환(start/complete)·메타 수정(PATCH)은 `sprints.version` bump + 낙관적 잠금. **(eng-C6)** 할당/해제가 no-bump이므로 이슈 목록 변경은 `sprints.version`으로 감지 불가 — 클라이언트(D6)는 할당/해제 뮤테이션 후 명시적 재조회 필요(version 기반 캐시 무효화에 의존 금지).
- **FR11 (소프트삭제 시 연관)**. 스프린트 소프트삭제(DELETE) 시 해당 `sprint_issues` 연관을 함께 제거 → 할당돼 있던 이슈는 백로그로 복귀(gap②). 완료 스프린트 이력 보존이 아니라 백로그 복귀가 사용자 기대에 부합.
- **FR12 (종료 시 미완료 이슈)**. complete(종료) 시 할당 이슈는 그대로 유지(자동 백로그 이동/다음 스프린트 이월은 이번 범위 밖, 후속). COMPLETED 후 할당/해제만 금지(FR6).

## 3. 비기능 요구사항 (NFR)

- **NFR1**. cross-BC 통신은 shared-kernel 포트(`BoardIssueLookupPort`)만 사용. agile-planning은 issue-tracking 직접 import 금지(ArchUnit 보장). 포트 확장(`isVisibleIssue` default 메서드 + issue-tracking `BoardIssueLookupAdapter` 구현)은 **read-only view 확장**(issues 테이블/도메인 무변경) — agile은 여전히 포트 인터페이스만 의존(adapter 직접 import 0). BC 경계 예외: 이 PR은 agile-planning 주작업 + issue-tracking adapter 1메서드(read) 포함.
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
| POST | `/api/v1/sprints/{id}/issues` | **UPDATE** | 이슈 할당. body: `{issueKey}` |
| DELETE | `/api/v1/sprints/{id}/issues/{issueKey}` | **UPDATE** | 이슈 해제 |

> **백로그(미할당 이슈) 조회 API는 D6로 이연.** `BoardIssueView`가 rank를 미노출하므로 rank 정렬 백로그는 포트 확장(→issue-tracking 변경, 두 BC)을 부른다. 백로그 조회는 FR-BL-01 rank 정렬이 본질이므로 D6(@dnd-kit 백로그↔스프린트 드래그)에서 FR-BL-01과 통합 설계 — 그 시점에 포트 확장 여부를 결정한다. 이번 백엔드는 스프린트 CRUD/할당/상태전환에 집중(Maxi 확정 범위와 일치).

- 응답 봉투. board 선례 `DataResponse`.
- **권한 판정 순서(security-C1)**. board `loadBoardWith*` 동형 — actor 추출(401) → **sprint 메타 조회로 projectKey 확보(404)** → `IssueScope.Project(sprint.projectKey)` 권한 판정(403) → 동작. 생성(POST)만 리소스 부재라 요청 body projectKey로 scope(board create 선례). request/path의 projectKey를 권한 scope로 신뢰 금지(타프로젝트 우회 차단).
- **권한 코드(Maxi 게이트1 확정)**. 스프린트 CRUD/start/complete = `CREATE`(board 보드생성 선례). **이슈 할당/해제 = `UPDATE`**(이슈 소속 메타 변경 = 이슈 UPDATE 동급, board 카드이동 BROWSE+TRANSITION과 동형이나 스프린트엔 전환강제 없어 UPDATE 채택). 조회 = `BROWSE`.

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
    issue_key  VARCHAR(...) NOT NULL,   -- issue-tracking 느슨참조(키 중심, cross-BC FK 없음)
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (sprint_id, issue_key),
    UNIQUE (issue_key)                   -- FR4: 한 이슈는 최대 1 스프린트
);
CREATE INDEX idx_sprint_issues_sprint ON sprint_issues(sprint_id);
```

- init_codegen.sql(agile-planning) 미러 필수(메모리 jooq-init-codegen-mirror).
- `sprint_id` FK는 같은 모듈(agile-planning) 내라 정상. `issue_key`는 cross-BC라 FK 없음(board 선례 — board도 issueKey 중심).
- **식별자=issue_key 사유**(ADR): board 표면 전체가 issueKey 중심(`BoardIssueView.key`/`BoardTransitionCommand.issueKey`)이고 `BoardIssueLookupAdapter`가 issue-tracking 소유 → UUID 저장 시 포트 확장(두 BC). key 저장으로 포트 무확장·단일 BC 유지 + 백로그 계산 key↔key 일관.
- V번호는 머지 직전 재확인(메모리 migration-vnumber, 현재 agile 최신 V502 → V503).

## 6. 엣지 케이스

- **E1** 잘못된 상태 전환(PLANNED→COMPLETED 직접 / COMPLETED→ACTIVE / 이미 ACTIVE인데 start) → 409 `InvalidSprintTransitionException`.
- **E2** 존재하지 않거나 소프트삭제된 스프린트 → 404.
- **E3** 할당 이슈가 미가시/타프로젝트/미존재/소프트삭제 → **단일 404**(probe 차단, FR5-1). isVisibleIssue=false. (E4 통합 — 400/404 분리 폐기.)
- **E5** COMPLETED 스프린트에 할당/해제 시도 → 409(FR6). **TOCTOU 차단(eng-B3)**: 조건부 DML(`WHERE (SELECT status FROM sprints WHERE id=?) <> 'COMPLETED'`), affected=0이면 스프린트 status 재조회로 E5(409) vs E2(404) 구분.
- **E6** 권한 없음 → 403(IssuePermissionResolver fail-closed). 가시성 검증보다 먼저.
- **E7** 멱등 재할당(이미 그 스프린트) → 200 no-op(FR7).
- **E8** 다른 스프린트에 있던 이슈 할당 → 기존 연관 제거 후 이동. **delete-then-insert 단일 트랜잭션(eng-B2 확정, UPSERT 아님)** → E12 동시 409 실검증 유지.
- **E9** 해제 시 그 스프린트에 없는 이슈 → **멱등 204**(security-N2, probe 표면 최소).
- **E10** name 누락/공백 → 400(@Valid).
- **E11** startDate > endDate → 400(기간 역전 검증).
- **E12** 동시 할당(같은 이슈 두 스프린트로) → UNIQUE(issue_key) 위반 → 409 변환(jOOQ ExceptionTranslator 의존, Spring DuplicateKey + native SQLState 23505 두 경로 catch, 메모리 jooq-exception-translator-409).
- **E13 (eng-C1, 범위 밖 명시)** 이슈 프로젝트 이동(FR-MV)으로 key 변경 시 sprint_issues orphan 발생 — 이번 범위 밖. 후속 `IssueMovedEvent` 핸들러에서 sprint_issues 동기화(board의 동일 약점). 구현자 인지용.

## 7. 제약 조건

- 한 PR = 한 BC(agile-planning). issue-tracking 무변경.
- 도메인 예외는 agile-planning 전용 ExceptionHandler(basePackages 한정 — 메모리 domain-exception-http-handler-basepackage-scope, catch-all 500 삼킴 방지 메모리 catch-all-exceptionhandler).
- 신규 cross-BC 포트 구현 빈이 전체 컨텍스트 통합테스트 부팅을 깨지 않도록 주의(메모리 fr-nt-02 stub빈 / profile-scoped-bean). BoardIssueLookupPort는 기존 빈 재사용이라 신규 부팅 리스크 낮음.

## 8. 측정 가능한 완료 기준

- [ ] 스프린트 CRUD + 상태전환 2종 + 할당/해제 9개 엔드포인트 동작.
- [ ] sprint_issues UNIQUE(issue_key)로 1:N 강제 + delete-then-insert 이동 원자성 + E12 동시 409 통합테스트.
- [ ] 상태 전환 규칙(E1) + COMPLETED 불변·TOCTOU 조건부 DML(E5) 단위/통합 검증.
- [ ] 권한 — CRUD/전환=CREATE, 할당/해제=UPDATE, 조회=BROWSE. 403(E6) + 가시성 단일 404 probe 차단(E3) HTTP 통합 검증.
- [ ] 단건 가시성 포트 `isVisibleIssue` + adapter 구현 + adapter 빈 페이지 시 할당 거부(fail-closed, security-C3).
- [ ] ArchUnit: agile-planning이 issue-tracking 직접 import 0(NFR1, vacuous 아님 확인) + 멤버십 role 직접조회 0(codereview, security-C4).
- [ ] ktlint + detekt(baseline) + generateJooq 통과.

## Brainstorming Check

✅ 통과 (adversarial self-review 1회, gap 3건 + 설계 제약 2건 발견·반영).
- gap① 백로그 조회 → 검토 결과 rank 정렬이 본질이고 `BoardIssueView`가 rank 미노출 → 포트 확장(두 BC)을 부름. **D6(FR-BL-01 통합)로 이연** 재조정(Maxi 범위와 일치).
- gap② 스프린트 소프트삭제 시 sprint_issues 연관 처리 미명시 → 연관 제거·백로그 복귀(FR11).
- gap③ version 동시성 정책 미구분 → 할당/해제=no-bump, 상태전환/수정=version bump(FR10).
- 제약A 식별자 — board 표면이 issueKey 중심 + adapter가 issue-tracking 소유 → `sprint_issues`는 `issue_key` 저장(포트 무확장·단일 BC 유지). ADR 정정.
- 제약B 종료 시 미완료 이슈 자동 이월은 이번 범위 밖 명시(FR12).
모두 스펙/ADR 보강으로 해소 — Maxi 추가 결정 불필요(게이트1 검토).
