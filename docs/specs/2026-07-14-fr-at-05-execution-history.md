<!-- FR-AT-05 실행 이력 + 디버깅 백엔드 스펙 — RuleExecution 영속화·조회·동기 replay -->

# FR-AT-05 실행 이력 + 디버깅 (재실행, 단계별 추적) — 스펙 (백엔드 D1~D5)

> 날짜. 2026-07-14 | BC. automation | PR. #270 | 선행. FR-AT-01·02·03 완료
> 관련. [ADR](../decisions/2026-07-14-fr-at-05-execution-history.md) · [SDD §8.6](../sdd/08-automation-engine.md) · [product §2.5](../plan/product/automation.md)

## 배경

자동화 룰은 `AutomationExecutionWorker` → `ActionExecutor.execute()` 경로로 실행되지만, 지금은 실행 결과(`ActionExecutionResult`)가 **버려지고** pgmq 아카이브에만 남아 조회할 수 없다. FR-AT-05는 이 결과를 `rule_executions` 테이블에 영속화하고, 룰별 실행 이력 조회 · 단계별 trace · 동기 재실행(replay)을 제공한다.

핵심 접지 — `ActionExecutor`가 반환하는 `ActionExecutionResult(status, outcomes[ActionOutcome(position, actionType, success, error)])`가 SDD §8.6 `AutomationRunLog.actions_executed`와 1:1 대응한다. 새 계산 로직이 아니라 **관측·영속화·조회** 계층을 얹는 작업이다.

## 결정 요약 (Maxi 확정, 2026-07-14)

- **Replay = 동기 실제 재실행**. 저장된 triggerEvent로 `ActionExecutor.execute(rule, storedTriggerEvent, dryRun=false)` 즉시 호출 → 실제 이슈 변경 + 새 이력 row + trace 즉시 반환. 루프가드 우회.
- **기록 범위 = 실행 시도분만**. `ActionExecutor.execute`가 호출된 실행만 → SUCCESS/PARTIAL/FAILED/SKIPPED. 억제창·깊이초과·룰없음·malformed는 기록 안 함.
- **PR 분할 = 백엔드 먼저 (D1~D5)**. D6/D7 UI는 후속 PR.
- **권한 = MANAGE_AUTOMATION** (기존 룰 컨트롤러 관례 재사용).

## 사용자 시나리오 (Given-When-Then)

### S1. 실행 이력 자동 기록
- **Given** enabled 룰이 이슈 이벤트로 발화해 큐에 적재됨
- **When** `AutomationExecutionWorker`가 소비해 `ActionExecutor.execute()`를 호출하고 SUCCESS로 끝남
- **Then** `rule_executions`에 row 1개 생성 — rule_id·project_key·trigger_type·trigger_event·issue_key·status=SUCCESS·outcomes(액션별)·started_at·finished_at

### S2. 부분 실패 trace
- **Given** 룰에 액션 2개(SET_FIELD 성공, ASSIGN 권한없음 실패)
- **When** 실행됨
- **Then** status=PARTIAL, outcomes=[{position:0,actionType:SET_FIELD,success:true,error:null},{position:1,actionType:ASSIGN,success:false,error:"PERMISSION_DENIED"}]

### S3. 조건불충족 SKIPPED 기록
- **Given** 룰에 조건이 있고 최신 이슈 스냅샷이 조건 불충족
- **When** 실행됨(ActionExecutor가 SKIPPED 반환)
- **Then** status=SKIPPED, outcomes=[] 로 기록 ("왜 조건에 막혔나" 관측 가능)

### S4. 억제/malformed는 미기록
- **Given** 같은 (ruleId, issueKey)가 60초 억제창 안에서 재발화 / 또는 malformed payload
- **When** 워커가 ActionExecutor 호출 전에 archive
- **Then** `rule_executions`에 row 생성 안 됨

### S5. 룰별 이력 조회
- **Given** 룰에 실행 이력 여러 건
- **When** 관리자가 `GET .../rules/{ruleId}/executions` 호출
- **Then** 최신순 요약 목록 반환(status·트리거·이슈키·시각·액션 수/성공 수). `issueKey` 필터로 특정 이슈 영향 자동화만 조회 가능(SDD "이 이슈에 영향을 준 자동화")

### S6. 단계별 trace 조회
- **When** `GET /api/v1/automation/executions/{id}` 호출
- **Then** 200 + 전체 record(outcomes[] + trigger_event 포함)

### S7. 동기 재실행
- **Given** 과거 실행 record
- **When** 관리자가 `POST /api/v1/automation/executions/{id}/replay` 호출
- **Then** 저장된 trigger_event로 룰을 dryRun=false 실제 재실행 → 이슈 변경 발생 + `replayed_from`=원본id인 새 record 생성 + 새 trace 200 즉시 반환

### S8. 권한 게이트
- **When** MANAGE_AUTOMATION 권한 없는 사용자가 조회/replay 호출
- **Then** 미인증 401 → 권한없음/타프로젝트 리소스는 존재 숨김(404 또는 403, 기존 관례 준수)

## 기능 요구사항 (FR)

### FR-1. 워커의 실행 결과 포착 + 영속화
`AutomationExecutionWorker.runExecution()`이 `ActionExecutor.execute()` 반환값을 포착해 `RuleExecutionRepository`로 `rule_executions` row를 저장한다. 저장 시각은 주입된 `Clock` 사용(결정적 테스트). `started_at`=execute 직전, `finished_at`=execute 직후.
- **fail-safe**: 이력 저장을 try/catch로 격리. 저장 예외가 pgmq archive/at-least-once 재전달을 훼손하지 않는다(저장 실패 시 경고 로그 후 정상 archive 진행).
- **trigger_type**: fire-time 값 사용 — 워커 `parsePayload`에 `triggerType` 파싱 추가(enqueuer가 이미 payload에 실음). 파싱 실패 시 `rule.triggerType` fallback.
- **project_key**: `rule.projectKey` 비정규화 저장(스코프 목록/권한에 조인 불필요).

### FR-2. RuleExecution 도메인 + 리포지토리
- 애플리케이션 레코드 `RuleExecution(id, ruleId, projectKey, triggerType, triggerEvent, issueKey, status, outcomes, replayedFrom, startedAt, finishedAt)`.
- `RuleExecutionRepository`(JdbcTemplate, 기존 automation repo와 동일하게 `NamedParameterJdbcTemplate` 스타일).
  - `save(execution): UUID`
  - `findById(id): RuleExecution?`
  - `findByRule(ruleId, issueKey?, limit, before?): List<RuleExecution>` — started_at DESC, keyset(before) 페이지네이션
- `outcomes`·`trigger_event`는 JSONB 직렬화(Jackson). BC 격리 — issue-tracking 타입 import 없음.

### FR-3. 조회 API — 룰별 이력 목록
`GET /api/v1/projects/{projectKey}/automation/rules/{ruleId}/executions`
- 쿼리: `issueKey`(옵션 필터) · `limit`(기본 50, 최대 200) · `before`(ISO Instant keyset 커서, 옵션)
- MANAGE_AUTOMATION 가드는 **path의 projectKey 기준**으로만 건다(actor 401 → 권한 403). **룰 존재(살아있음)를 요구하지 않는다** — `rule_executions`를 `rule_id = {ruleId} AND project_key = {projectKey}`로 직접 조회한다.
  - **근거(Brainstorming 발견)**: NFR-4(감사 독립성)와 정합 — 소프트삭제된 룰의 이력도 조회 가능해야 디버깅 가치가 있다. 룰 존재를 요구하면 삭제 후 이력이 막힌다.
  - **존재 숨김**: `ruleId`가 다른 프로젝트 소속이면 project_key 불일치로 빈 목록 반환(404 아님, 존재 누출 없음).
- 페이지네이션 keyset은 `(started_at, id)` 복합 커서(동일 시각 충돌 방지). `before`는 `started_at` 기준, 동시각은 id로 tie-break.
- 응답: 요약 DTO 목록(최신순) — `id·ruleId·triggerType·issueKey·status·actionCount·successCount·startedAt·finishedAt·replayedFrom`.

### FR-4. 조회 API — 실행 단건 trace
`GET /api/v1/automation/executions/{id}`
- 실행 record 로드 → `project_key`로 MANAGE_AUTOMATION 가드 → 전체 record 반환(outcomes[] + trigger_event).
- 없음/타프로젝트 = 404(존재 숨김).

### FR-5. Replay API — 동기 실제 재실행
`POST /api/v1/automation/executions/{id}/replay`
- 실행 record 로드 → `project_key`로 MANAGE_AUTOMATION 가드.
- `ruleRepository.findById(record.ruleId)` — 소프트삭제/부재 시 409 `AUTOMATION_RULE_UNAVAILABLE`(재실행 불가). enabled 여부는 불요(명시적 관리자 재실행).
- `actionExecutor.execute(rule, record.triggerEvent, dryRun=false)` 동기 호출 → 새 `RuleExecution` 저장(`replayedFrom`=원본 id, `triggerType`=원본 record의 trigger_type).
- 응답 200 + 새 실행 단건 trace.

### FR-6. 워커 KDoc stale 참조 정정
`AutomationExecutionWorker` KDoc의 "견고한 사이클 검출/감사는 FR-AT-04에 위임" 표현을 정정 — 실행 로그/감사는 FR-AT-05다(FR-AT-04는 규칙 충돌 정적 분석으로 확정됨). 사이클 검출 자체는 여전히 FR-AT-04 정적 분석 영역이므로 표현을 정확히 분리.

## 비기능 요구사항 (NFR)

- **NFR-1 fail-safe**: 이력 저장 실패가 액션 실행/큐 생명주기를 훼손하지 않음.
- **NFR-2 BC 격리**: automation은 issue-tracking 타입 미import. trigger_event/outcomes는 JSONB로만.
- **NFR-3 권한**: 모든 조회/replay는 MANAGE_AUTOMATION. 존재 숨김. 인가 순서 actor→권한→조회.
- **NFR-4 감사 독립성**: `rule_executions`는 `automation_rules`에 하드 FK를 두지 않는다(룰이 후일 하드 삭제돼도 이력 보존 — audit trail 독립). rule_id는 인덱스만.
- **NFR-5 인덱스**: `(rule_id, started_at DESC)` + `(project_key, issue_key, started_at DESC)`.
- **NFR-6 결정적 시각**: `Clock` 주입(테스트 결정성).

## API 인터페이스 (REST)

| Method | Path | 가드 | 응답 |
|---|---|---|---|
| GET | `/api/v1/projects/{projectKey}/automation/rules/{ruleId}/executions` | MANAGE_AUTOMATION | 200 요약 목록 |
| GET | `/api/v1/automation/executions/{id}` | MANAGE_AUTOMATION(project 역도출) | 200 단건 trace / 404 |
| POST | `/api/v1/automation/executions/{id}/replay` | MANAGE_AUTOMATION(project 역도출) | 200 새 trace / 404 / 409 |

에러 코드(prefix `AUTOMATION_`): `AUTOMATION_ACCESS_DENIED`(403) · `AUTOMATION_EXECUTION_NOT_FOUND`(404) · `AUTOMATION_RULE_UNAVAILABLE`(409, replay 대상 룰 부재) · `AUTOMATION_UNAUTHENTICATED`(401) · `AUTOMATION_INTERNAL_ERROR`(500).

## 데이터 모델 변경

**V305__rule_executions.sql** (신규).
```sql
CREATE TABLE rule_executions (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    rule_id       UUID        NOT NULL,                 -- 하드 FK 없음(NFR-4)
    project_key   TEXT        NOT NULL,                 -- 비정규화(스코프 목록/권한)
    trigger_type  TEXT        NOT NULL,                 -- fire-time 트리거 타입
    trigger_event JSONB       NOT NULL,                 -- 원본 payload(replay 재료)
    issue_key     TEXT,                                 -- 대상 이슈 키(SCHEDULED/WEBHOOK은 NULL)
    status        TEXT        NOT NULL,                 -- SUCCESS/PARTIAL/FAILED/SKIPPED
    outcomes      JSONB       NOT NULL DEFAULT '[]',    -- [{position,actionType,success,error}]
    replayed_from UUID,                                 -- 이 row가 replay면 원본 실행 id
    started_at    TIMESTAMPTZ NOT NULL,
    finished_at   TIMESTAMPTZ NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_rule_executions_rule ON rule_executions (rule_id, started_at DESC);
CREATE INDEX idx_rule_executions_project_issue ON rule_executions (project_key, issue_key, started_at DESC);
```
- init_codegen.sql 미러 필요([[jooq-init-codegen-mirror]]) — automation은 JdbcTemplate이라 jOOQ 코드젠 비대상일 수 있으나 스키마 테스트 카운트 정합 확인.
- V번호 머지 직전 재확인([[migration-vnumber-concurrent-branch-collision]]).

## 엣지 케이스

- **EC1 SKIPPED 기록**: 조건불충족은 outcomes=[] + status=SKIPPED. 억제창 skip과 구분(후자는 미기록).
- **EC2 이슈키 없는 트리거**(SCHEDULED/WEBHOOK): issue_key=NULL 저장. replay 시에도 동일(이슈 액션은 ISSUE_KEY_MISSING).
- **EC3 replay 대상 룰 소프트삭제**: 409 `AUTOMATION_RULE_UNAVAILABLE`.
- **EC4 replay actorUserId 변경됨**: 현재 rule.actorUserId로 실행(문서화). 원본 시점 actor 재현은 범위 밖.
- **EC5 replay가 SKIPPED였던 실행**: 재실행 시 조건 재평가 → 통과하면 액션 실행, 여전히 불충족이면 다시 SKIPPED. 정상.
- **EC6 이력 저장 실패**: 경고 로그 + 정상 archive(NFR-1). 액션은 이미 실행됨.
- **EC7 trigger_type 파싱 실패**: rule.triggerType fallback.
- **EC8 keyset 커서 `before` malformed**: 400(파싱 실패) 또는 필터 무시 — 400으로 명확 반환.

## 제약 조건

- automation 모듈은 JdbcTemplate(jOOQ 아님). 기존 repo 관례(NamedParameterJdbcTemplate) 준수.
- `ActionExecutor.execute` 시그니처 변경 없음(반환값을 이미 제공). 워커가 반환값을 쓰도록만 변경.
- replay는 큐를 거치지 않고 executor 직접 호출(신규 pgmq 큐 없음).
- 신규 cross-BC 포트 없음(기존 `IssueMutationPort`/`IssueSnapshotPort` 재사용).
- DEVELOPMENT.md 절대 규칙 준수. TDD red→green.

## 범위 밖 (후속)

- **retention/TTL** — `rule_executions`는 무제한 증가한다(1K 사용자 규모에서 룰당 다수 실행). 보존 정책(예: N일 경과 아카이브/삭제)은 본 PR 범위 밖. 후속 FR 또는 운영 배치로 위임. `created_at` 인덱스 후보를 남겨둔다(현재는 (project_key, issue_key, started_at) 인덱스에 started_at 포함).
- **D6/D7 UI + E2E** — 실행 이력/trace/replay 버튼 UI는 후속 PR.
- **trigger_event 크기 상한** — 웹훅 본문 등 큰 payload는 JSONB로 그대로 저장(pgmq 저장 크기와 동일 수준). 별도 상한은 두지 않음.

## Brainstorming Check

✅ 통과 (1회 iteration — gap 3건 발견 후 스펙 보강).
- **G1 (수정)** 룰 스코프 목록이 룰 존재를 요구하면 소프트삭제 룰 이력이 막혀 NFR-4(감사 독립성)와 모순 → FR-3을 projectKey 가드 + rule_id/project_key 직접 조회로 수정.
- **G2 (수정)** keyset 커서 `started_at` 단독은 동일 시각 충돌 → `(started_at, id)` 복합 커서로 명시.
- **G3 (범위 밖 명시)** retention/TTL 무제한 증가 → 후속 위임으로 명시.

## 측정 가능한 완료 기준

1. 워커가 룰 실행 후 `rule_executions` row 1개 생성(통합 테스트 — Testcontainers).
2. SUCCESS/PARTIAL/FAILED/SKIPPED 각각 올바른 status + outcomes 저장.
3. 억제창/malformed는 row 미생성.
4. `GET .../rules/{ruleId}/executions` 최신순 + issueKey 필터 + limit/before 동작.
5. `GET /executions/{id}` 전체 trace 반환, 없음=404.
6. `POST /executions/{id}/replay` — 실제 mutation 발생 + 새 row(replayed_from 세팅) + 200 trace, 룰 소프트삭제=409.
7. MANAGE_AUTOMATION 권한 게이트 — 미인증 401 / 권한없음·타프로젝트 404(존재 숨김).
8. 전수 동기화 — SDD §8.6(SKIPPED·issue_key)·product §2.5 D박스·fr-index·progress.html·Obsidian.
