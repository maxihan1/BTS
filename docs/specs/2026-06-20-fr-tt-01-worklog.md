# FR-TT-01 — Worklog (추정/실제/잔여 시간) — 스펙

> BC: issue-tracking | type: backend | 범위: D1~D5 (프론트 D6/D7 후속 PR)
> 관련: ADR `docs/adr/2026-06-20-worklog-time-tracking-model.md`, SDD §5.9, product agile-planning §5.1
> 작성 방식: 직접 기술 스펙 (도메인 확정 + Maxi 3개 결정 완료, office-hours 부적합 — [[bts-spec-office-hours-mismatch]])

## 개요

이슈에 작업 시간을 기록(Worklog)하고, 추정/실제/잔여 시간을 추적한다.

- **Original Estimate (원 추정)**. 사용자가 설정하는 이슈의 예상 소요 시간.
- **Time Spent (실제 소요)**. 해당 이슈의 worklog 항목 `time_spent_seconds`의 합 = 파생 값(SoT는 worklogs 테이블).
- **Remaining Estimate (잔여 추정)**. 남은 예상 시간. Log Work 시 자동 차감(또는 수동 override), 별도 PATCH로 직접 설정 가능.

## 사용자 시나리오 (Given-When-Then)

### S1. 작업 시간 기록 (자동 차감)
- **Given** PROJ-1 이슈의 remaining = 8h(28800s), time_spent = 0.
- **When** 사용자가 `POST /api/v1/issues/PROJ-1/worklogs { timeSpentSeconds: 7200, startedAt }` 호출.
- **Then** worklog 1건 생성, issue.time_spent = 7200, issue.remaining = max(0, 28800−7200) = 21600. 201 + worklog 응답.

### S2. 작업 시간 기록 (수동 override)
- **Given** S1 이후 remaining = 21600.
- **When** `POST .../worklogs { timeSpentSeconds: 3600, startedAt, newRemainingEstimateSeconds: 14400 }`.
- **Then** worklog 추가, time_spent = 10800(7200+3600), remaining = 14400(override 값). 201.

### S3. 원 추정/잔여 추정 수동 설정
- **When** `PATCH /api/v1/issues/PROJ-1 { originalEstimateSeconds: 36000, remainingEstimateSeconds: 18000 }`.
- **Then** issue.original = 36000, remaining = 18000. 변경 이력(changelog)에 두 필드 변경 기록. 200.

### S4. worklog 목록 조회
- **When** `GET /api/v1/issues/PROJ-1/worklogs`.
- **Then** 해당 이슈의 worklog 배열(started_at 내림차순) + 이슈의 original/time_spent/remaining 요약. VIEW 권한 필요.

### S5. worklog 수정 (본인 항목)
- **Given** 사용자 A가 작성한 worklog W1(1h).
- **When** A가 `PATCH .../worklogs/{W1} { timeSpentSeconds: 5400 }`.
- **Then** W1 = 1.5h, issue.time_spent 재집계(SUM). remaining은 자동 조정 안 함(수정은 차감 재적용 안 함 — S-edge 참조). 200.

### S6. worklog 삭제 (본인 항목)
- **When** A가 `DELETE .../worklogs/{W1}`.
- **Then** W1 하드 삭제(WHERE 명시), issue.time_spent 재집계. remaining 자동 복원 안 함. 204.

### S7. 권한 거부
- **When** UPDATE 권한 없는 사용자가 worklog POST.
- **Then** 403 (이슈 조회 정보 누출 없음 — actor 추출 → 권한 → 조회 순).

## 기능 요구사항 (FR)

| ID | 요구사항 |
|---|---|
| FR1 | `POST /api/v1/issues/{key}/worklogs` — worklog 생성. body: timeSpentSeconds(필수,>0), startedAt(필수), comment(선택), newRemainingEstimateSeconds(선택). author = 현재 사용자. |
| FR2 | worklog 생성 시 issue.time_spent = SUM(worklogs.time_spent_seconds) 재집계. |
| FR3 | worklog 생성 시 remaining 자동 차감: override 없으면 `remaining = max(0, remaining − timeSpent)`(remaining이 NULL이면 NULL 유지), override 있으면 그 값으로 설정. |
| FR4 | `PATCH /api/v1/issues/{key}/worklogs/{worklogId}` — 본인 worklog 수정(timeSpentSeconds/startedAt/comment). time_spent 재집계. remaining 자동 조정 없음. |
| FR5 | `DELETE /api/v1/issues/{key}/worklogs/{worklogId}` — 본인 worklog 하드 삭제(WHERE 명시). time_spent 재집계. remaining 자동 복원 없음. |
| FR6 | `GET /api/v1/issues/{key}/worklogs` — 해당 이슈 worklog 목록(started_at desc) + 이슈 시간 요약(original/time_spent/remaining). |
| FR7 | `PATCH /api/v1/issues/{key}` 확장 — originalEstimateSeconds, remainingEstimateSeconds (JsonNullable Int 3-state: 미변경/null해제/값설정). FR-PL-01 DatePatch 패턴 모방. |
| FR8 | IssueResponse에 originalEstimateSeconds(Int?), timeSpentSeconds(Int, 기본0), remainingEstimateSeconds(Int?) 노출. timeSpent는 읽기 전용(PATCH 불가). |
| FR9 | original/remaining 변경(수동 PATCH + worklog 자동 차감 모두)은 이슈 변경 이력에 기록. time_spent(파생 합)는 이력 미기록(noise 회피). SCALAR_FIELD_EXTRACTORS에 originalEstimate/remainingEstimate 추가. |
| FR10 | 권한: POST = IssuePermission.UPDATE, PATCH/DELETE worklog = UPDATE + 본인(author) 한정, GET = VIEW, 추정 PATCH = UPDATE(기존 이슈 PATCH 게이트 재사용). 모두 IssueScope.Issue(key) + permissionResolver.hasPermission. |

## 비기능 요구사항 (NFR)

| ID | 요구사항 |
|---|---|
| NFR1 | worklog write(생성/수정/삭제) + issue 시간 컬럼 갱신 + 이력 기록은 단일 트랜잭션(issue-tracking 절대 규칙). |
| NFR2 | worklog의 issue 시간 컬럼 갱신은 **no-bump**(issue.version 증가 안 함, expectedVersion 미요구) — 동시 이슈 필드 편집과 OCC 충돌 회피([[no-bump-sidecar-version-double-bump]]). |
| NFR3 | time_spent는 항상 SUM 재계산(증분 += 금지) — 동시 worklog 생성에도 정합. |
| NFR4 | worklog 100건 목록 조회 p95 < 200ms. |
| NFR5 | BC 격리 — author_id/issue_id UUID, users FK 미적용. author = 인증된 현재 사용자라 존재 검증 불요. |

## API 인터페이스 (REST)

```
POST   /api/v1/issues/{key}/worklogs           → 201 WorklogResponse
GET    /api/v1/issues/{key}/worklogs           → 200 { worklogs: [...], summary: { originalEstimateSeconds, timeSpentSeconds, remainingEstimateSeconds } }
PATCH  /api/v1/issues/{key}/worklogs/{worklogId} → 200 WorklogResponse
DELETE /api/v1/issues/{key}/worklogs/{worklogId} → 204
PATCH  /api/v1/issues/{key}                     → 200 IssueResponse  (originalEstimateSeconds, remainingEstimateSeconds 추가)
```

**WorklogResponse**: `{ id, issueKey, authorId, timeSpentSeconds, startedAt, comment, createdAt, updatedAt }`

**POST body**: `{ timeSpentSeconds: Int(>0), startedAt: Instant, comment?: String, newRemainingEstimateSeconds?: Int(>=0) }`

**PATCH worklog body**: `{ timeSpentSeconds?: Int(>0), startedAt?: Instant, comment?: JsonNullable<String> }`

## 데이터 모델 변경 (V027, issue-tracking 하위 폴더)

```sql
-- worklogs 테이블 신설
CREATE TABLE worklogs (
    id                 UUID         PRIMARY KEY,
    issue_id           UUID         NOT NULL REFERENCES issues(id) ON DELETE CASCADE,
    author_id          UUID         NOT NULL,
    time_spent_seconds INT          NOT NULL CHECK (time_spent_seconds > 0),
    started_at         TIMESTAMPTZ  NOT NULL,
    comment            TEXT         NULL,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_worklogs_issue_id ON worklogs(issue_id);
CREATE INDEX idx_worklogs_author_started ON worklogs(author_id, started_at);

-- issues 시간 컬럼 추가
ALTER TABLE issues
    ADD COLUMN original_estimate_seconds  INT NULL,
    ADD COLUMN time_spent_seconds         INT NOT NULL DEFAULT 0,
    ADD COLUMN remaining_estimate_seconds INT NULL;
```

- **init_codegen.sql 미러 필수**([[jooq-init-codegen-mirror]]) — worklogs CREATE + issues 3컬럼 ADD를 init_codegen.sql에도 반영(jOOQ 코드젠).
- V번호는 머지 직전 재확인([[migration-vnumber-concurrent-branch-collision]]).

## 엣지 케이스

| # | 케이스 | 처리 |
|---|---|---|
| E1 | timeSpentSeconds ≤ 0 | 400 (DB CHECK + 요청 검증) |
| E2 | startedAt 미래 시각 | 허용(Jira 동일, 제약 없음) |
| E3 | 이슈 미존재 | 401(미인증) → 403(권한) → 404(존재) 순. actor 추출이 findByKey보다 먼저([[auth-extraction-before-resource-lookup]]) |
| E4 | worklogId가 다른 이슈 소속 | 404 (issue_id ≠ key의 이슈) |
| E5 | 타인 worklog 수정/삭제 | 403 (author_id ≠ actor) |
| E6 | remaining=NULL인데 override 없이 POST | remaining NULL 유지(차감 대상 없음), time_spent는 증가 |
| E7 | newRemainingEstimateSeconds < 0 또는 originalEstimate < 0 | 400 |
| E8 | 소프트 삭제된 이슈(deleted_at) | 404 (모든 worklog 작업 차단) |
| E9 | 동시 worklog POST | time_spent SUM 재계산이라 정합. remaining 자동차감은 각 트랜잭션 직렬화(no-bump이라 issue row lock은 UPDATE가 처리) |
| E10 | comment 길이 초과(예: > 5000자) | 400 (기존 코멘트/설명 길이 정책 준용 — plan에서 상한 확정) |

## 제약 조건

- DatePatch 패턴 모방: 추정 Int 3-state는 신규 sealed interface(예: EstimatePatch) 또는 JsonNullable<Int> 직접 처리. FR-PL-01 `toDatePatch` 헬퍼 구조 재사용.
- pgmq 이벤트 발행 없음(FR-TT-01에 알림 요구 없음 — YAGNI).
- 새 권한 코드 추가 없음 — 기존 IssuePermission.VIEW/UPDATE 재사용(권한 시드/마이그레이션 테스트 카운트 무영향, [[fr-pm-permission-seed-migration-test-coupling]] 회피).
- worklog는 하드 삭제(WHERE 명시) — 외부 참조 없는 자식 엔티티, watcher/attachment 선례.
- **IssueResponse 필드 추가 팬아웃(G1)**. originalEstimate/timeSpent/remaining 3필드를 IssueResponse에 추가하면 모든 IssueResponse 생성 지점(단건 detail·목록·clone·move preview 등, [[fr-vr-03]] 선례 93건 규모)과 해당 단위/통합 테스트를 전수 갱신해야 한다. plan에서 `IssueResponse(` / `IssueResponse.from` grep 전수 + 컴파일 검증 task 필수.
- **Instant 직렬화(G5)**. startedAt은 Instant — 기존 WebMvcConfigurer ISO 설정 재사용([[fr-vr-04-release-notes-done]]). 신규 컨버터 추가 금지.
- **범위 외(G3)**. 부모/에픽으로의 시간 롤업은 FR-TT-01 범위 아님(FR-TT-02 집계에서 처리).
- **페이지네이션(G4)**. GET worklogs는 이슈당 건수가 제한적이라 전체 반환(페이지네이션 없음).
- **comment 상한(G6)**. plan에서 기존 코멘트/설명 길이 정책 확인 후 확정.

## 측정 가능한 완료 기준

- [ ] worklog POST/GET/PATCH/DELETE 4개 엔드포인트 — 권한·검증·트랜잭션 충족
- [ ] issue 추정 필드(original/remaining) PATCH 가능, time_spent 파생·읽기전용
- [ ] remaining 자동 차감(POST) + 수동 override 동작
- [ ] original/remaining 변경이 이슈 changelog에 기록(SCALAR_FIELD_EXTRACTORS)
- [ ] V027 마이그레이션 + init_codegen.sql 미러 + jOOQ 재생성
- [ ] 백엔드 단위 + 통합 테스트(Testcontainers) 그린, ktlint/detekt 통과
- [ ] D1~D5만(프론트 D6/D7은 후속 PR)

## Brainstorming Check

✅ 통과 (1회 자체 적대적 갭 분석 — backend 명확 스펙, 대화형 brainstorming 대신 직접 분석)

발견 갭과 처리.
- **G1 (중요, 반영)**: IssueResponse 3필드 추가 → 전 생성지점/테스트 팬아웃. 제약 조건 + plan task로 반영.
- **G2 (게이트1 위임)**: 추정 changelog 통합. **기본값** — original/remaining 변경(수동 PATCH + worklog 자동차감)을 SCALAR_FIELD_EXTRACTORS로 이력 기록(FR9, 일관성·FR-PL-01 선례·적대리뷰 회피). **대안** — 추정 changelog 미통합(time tracking 별도 감사면, worklog→history 결합 제거). 게이트1에서 Maxi 확정.
- **G3~G6 (반영)**: 롤업 범위 외, 페이지네이션 없음, Instant 재사용, comment 상한 plan 확정 — 제약 조건에 명시.
- 권한·트랜잭션·OCC·동시성·404순서 엣지: 엣지 케이스 표 E1~E10으로 커버.
