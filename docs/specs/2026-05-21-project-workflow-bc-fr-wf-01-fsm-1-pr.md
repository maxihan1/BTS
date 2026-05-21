<!-- FR-WF-01 FSM 워크플로우 완제품 스펙 — D1~D7 한 PR -->

# FR-WF-01 FSM 워크플로우 — 스펙

> slug: `project-workflow-bc-fr-wf-01-fsm-1-pr`
> BC: `project-workflow`
> 범위: D1 도메인 → D7 E2E + NFR (한 PR)
> 사전 결정. Validator 용어 + SpEL 파서 + D1~D7 전체 (도메인 정리 §사전 결정 참조)
> 출처. SDD `docs/sdd/07-workflow-engine.md`, `docs/plan/product/project-workflow.md §2.1`

## §1. 사용자 시나리오 (Given-When-Then)

### S1. 표준 워크플로우로 이슈 전이 (기본 흐름)

```gherkin
Given software-default 워크플로우가 시드 적재됨 (To Do → In Progress → Review → Done)
And  이슈 PROJ-1 의 상태 = "To Do"
And  사용자 alice 가 PROJ-1 에 대해 ISSUE_EDIT 권한 보유
When alice 가 "To Do → In Progress" 전이를 발사
Then 이슈 PROJ-1 의 상태 = "In Progress" 로 변경
And  WorkflowTransitionExecuted 이벤트 발행 (pgmq, BC 격리)
And  이슈 변경 이력에 전이 기록 (issue-tracking BC가 구독)
```

### S2. Validator 실패 — 권한 부재

```gherkin
Given bug-tracking 워크플로우의 "Verified → Closed" 전이가 PermissionValidator(ISSUE_CLOSE) 보유
And  사용자 bob 는 ISSUE_CLOSE 권한 없음
When bob 가 "Verified → Closed" 전이를 발사
Then HTTP 403 반환
And  이슈 상태 변경 없음 (트랜잭션 롤백)
And  WorkflowTransitionRejected 이벤트 발행 (이유. permission_denied)
```

### S3. Validator 실패 — 필수 필드 누락

```gherkin
Given simple 워크플로우의 "Open → Closed" 전이가 RequiredFieldValidator(resolution) 보유
And  이슈 PROJ-2 의 resolution 필드 = null
When alice 가 "Open → Closed" 전이를 발사
Then HTTP 422 반환 (errors. {resolution: "required"})
And  이슈 상태 변경 없음
```

### S4. CustomExpression Validator — SpEL 평가

```gherkin
Given software-default 워크플로우의 "Review → Done" 전이에 CustomExpressionValidator 설정
And  표현식 = "#issue.priority == 'HIGH' && #user.hasRole('LEAD')"
And  이슈 priority = HIGH, 사용자 charlie 는 LEAD 역할
When charlie 가 "Review → Done" 전이를 발사
Then 표현식 평가 true → 전이 통과
```

### S5. PostAction — 필드 자동 채움 + 알림

```gherkin
Given software-default 의 "Review → Done" 전이에 두 PostAction 설정
        1. SetField(resolution = "fixed")
        2. Notify(channel = "inapp", recipients = "watchers")
And  이슈 PROJ-3 에 워처 alice, bob
When charlie 가 "Review → Done" 전이를 발사 (S1 흐름 통과)
Then 이슈 resolution = "fixed" 자동 설정
And  alice/bob 에게 인앱 알림 (notification BC가 이벤트 구독)
```

### S6. 동시 전이 (낙관적 락)

```gherkin
Given 이슈 PROJ-4 의 version = 5
When  alice 와 bob 이 동시에 "To Do → In Progress" 전이 발사
Then  먼저 도착한 쪽 성공. 늦은 쪽은 HTTP 409 (OptimisticLockException)
And  최종 이슈 version = 6, 상태 = "In Progress" (멱등성 보장 안 됨 — 클라이언트가 재시도 결정)
```

## §2. 기능 요구사항 (FR)

### §2.1 Workflow 정의 로딩

| FR-WF-01-FN-01 | seed YAML 4종 적재 |
| --- | --- |
| 입력 | 부팅 시 classpath `workflows/{software-default,bug-tracking,simple,kanban-basic}.yaml` |
| 출력 | `workflows`, `workflow_states`, `workflow_transitions`, `workflow_validators`, `workflow_post_actions` 5 테이블 적재 |
| 멱등 | YAML 해시 변경 시에만 재적재. 변경 없으면 skip |
| Validator | RequiredField, Permission, NotStatusCategory, CustomExpression 4종 |
| PostAction | SetField, AddWatcher, Notify, CallWebhook, RunAutomation 5종 |

### §2.2 전이 발사 API

| FR-WF-01-FN-02 | `POST /api/v1/workflows/{workflowKey}/transitions:plan` (검증/계산 — 적용 X) |
| --- | --- |
| 요청 | `{ "fromState": "To Do", "toState": "In Progress", "issueRef": { ... }, "actor": { ... } }` |
| Validator | 4종 모두 통과 시 통과. 1개 실패 시 즉시 reject |
| PostAction | 검증만 (실행 X — 호출자 BC가 적용). config 평가 + fieldChanges 계산 |
| 응답 | 200 (TransitionPlan — to_state + fieldChanges + emitEvents) / 403 (permission) / 422 (validator fail) |

> **이슈 상태 영속화는 호출자(issue-tracking) BC 책임** (GAP-2 결정 — 2026-05-21). WorkflowEngine 은 검증/계산 + 발행할 이벤트 명세 반환만. version 충돌(낙관적 락)은 호출자 측에서 처리.

### §2.3 워크플로우 조회 API

| FR-WF-01-FN-03 | `GET /api/v1/workflows`, `GET /api/v1/workflows/{key}` |
| --- | --- |
| 입력 | (목록) 없음 / (단건) workflow key |
| 출력 | Workflow + State + Transition + Validator + PostAction (계층 구조) |
| 캐시 | 메모리 캐시. 변경 시 명시적 invalidate (FR-WF-01-FN-04) |

### §2.4 워크플로우 정의 캐시 무효화 API (관리자 한정)

| FR-WF-01-FN-04 | `POST /api/v1/workflows/cache/invalidate` |
| --- | --- |
| 권한 | `WORKFLOW_MANAGE` (PermissionResolver — identity-access 후속 PR에서 실제 연결) |
| 효과 | 메모리 캐시 비움. 다음 조회 시 DB → 메모리 적재 |

### §2.5 타 BC 호출용 internal port

| FR-WF-01-FN-05 | `WorkflowTransitionPort` interface |
| --- | --- |
| 위치 | `backend/modules/project-workflow/src/main/kotlin/.../port/inbound/WorkflowTransitionPort.kt` |
| 시그니처 | `fun plan(req: TransitionRequest): TransitionPlan` |
| 반환 | `TransitionPlan(toState, fieldChanges: List<FieldChange>, emitEvents: List<DomainEvent>)` — **호출자가 자기 트랜잭션 안에서 적용** |
| 트랜잭션 전파 | **`Propagation.MANDATORY`** — 호출자 트랜잭션이 없으면 `IllegalTransactionStateException` 즉시 throw. 호출자(issue-tracking) 트랜잭션 안에서만 동작 |
| 예외 계약 | `WorkflowValidatorFailureException` (검증 실패), `WorkflowNotFoundException` (workflow/transition 부재), `WorkflowExpressionTimeoutException` (SpEL timeout) |
| 호출자 (예정) | issue-tracking BC (이슈 상태 변경 시), automation BC (룰 액션 시) |
| 구현 | `WorkflowEngine` 가 구현. 본 PR 범위 내 |
| 이벤트 발행 패턴 | `emitEvents` 는 호출자의 outbox 테이블 INSERT 책임 (호출자 BC 의 트랜잭션 안). pgmq 디스패치는 별도 worker. **본 PR 은 outbox 표준 인터페이스만 정의, 실제 outbox 인프라는 후속 PR (notification/messaging BC)** |

### §2.6 외부 BC 의존 추상화

| FR-WF-01-FN-06 | `PermissionResolver` interface (stub 구현) |
| --- | --- |
| 위치 | `backend/modules/project-workflow/src/main/kotlin/.../port/outbound/PermissionResolver.kt` |
| 시그니처 | `fun hasPermission(actorId: ActorId, permission: String, scope: Scope): Boolean` |
| stub 구현 | `AlwaysAllowPermissionResolver` (test profile + 부팅 가능. 운영 profile 부팅 차단 — DEVELOPMENT §1 절대 규칙 #16) |
| 실제 구현 | identity-access PR #8 머지 후 후속 PR에서 `IdentityAccessPermissionResolver` 추가 |

## §3. 비기능 요구사항 (NFR)

### §3.1 성능

> **측정 환경 고정.** Testcontainers 단일 Postgres 16, 동시 요청 50건, 캐시 워밍 후 (cache hit 100%), CPU 4 core / RAM 8GB. 차이 발생 시 spec 재 측정.

| 항목 | 임계 |
|---|---|
| `plan(TransitionRequest)` 처리 (Validator 4종 + PostAction config 평가) p95 | **100ms 미만** (`docs/plan/product/project-workflow.md` 명시) |
| 워크플로우 조회 (캐시 적중) p95 | 10ms 미만 |
| 워크플로우 조회 (cache miss → DB 재적재) p95 | 50ms 미만 |
| YAML 시드 부팅 시간 (4종 워크플로우) | 5초 미만 |
| SpEL CustomExpression 평가 timeout | **50ms 강제** (Future + ExecutorService 래퍼. 초과 시 422 `validator_error`) |

### §3.2 정확성

| 항목 | 기준 |
|---|---|
| Property-based test (Kotest property) | **표준 4종 워크플로우 × 임의 전이 시퀀스 길이 1~20 × validator 조합 무작위 × 1000건**. 불변식. (1) 도달한 상태는 워크플로우의 reachable 집합 내, (2) 모든 워크플로우의 DONE 카테고리 상태 1개 이상 도달 가능, (3) 같은 입력 → 같은 TransitionPlan (멱등) |
| 표준 4종 closed 검증 | 각 워크플로우 — 모든 state 가 시작점 (To Do/New/Open/Backlog) 에서 도달 가능 + DONE 카테고리 1개 이상 + 고아 상태 0건 |
| Validator 단위 테스트 | 4종 × {pass, fail, null/empty/whitespace edge} = 12건 |
| PostAction config 평가 단위 테스트 | 5종 × {valid config → fieldChange 계산, invalid config → exception} = 10건 |
| Validator/PostAction config jsonb 직렬화 라운드트립 | 9종 (4 validator + 5 postAction) × Jackson 라운드트립 = 9건 |
| 통합 테스트 (Testcontainers) | S1~S6 시나리오 + cache invalidate = 7건 |
| E2E (Playwright) | 표준 4종 워크플로우 각각 1 happy path = 4건 |
| 테스트 커버리지 | **신규 코드 line coverage 80% 이상** (DEVELOPMENT.md §1 절대 규칙 — 게이트 1 에서 정확한 수치 Maxi 확인) |

### §3.3 정적 분석

| 도구 | 기준 |
|---|---|
| ktlint | 0 위반. wave 별 implementer 는 본인 파일만 ktlintFormat. 부수 변경 발생 시 controller 가 wave 종료 후 일괄 chore 커밋 (learning #44) |
| detekt | 0 위반 (MaxLineLength, NestedBlockDepth 포함 — learning #44) |
| SQL/YAML 포맷 | wave 종료 후 controller 가 일괄 정리. implementer 는 본인 파일만 (learning #44 — 자동 도구 부수 변경 회귀 방지) |
| ArchUnit | `@Transactional` 메서드 보유 클래스는 `@Service` 강제 (learning #91) |
| ArchUnit | project-workflow 모듈은 identity-access / issue-tracking / automation 클래스 직접 import 0건 — **단 `*.jooq.tables.*` generated 패키지는 제외 화이트리스트** (jOOQ 코드젠 부수 영향 차단) |

### §3.4 보안

| 항목 | 기준 |
|---|---|
| SpEL sandbox | `SimpleEvaluationContext.forReadOnlyDataBinding().build()` (Spring 공식 권장. arbitrary method 호출 차단, property write 차단) |
| SpEL root 객체 | **sealed value class** — `IssueView` / `ActorView`. **getter only, 액션 메서드 0개**. hasRole 같은 권한 호출 메서드도 제거. 권한 검증은 별도 PermissionValidator 가 담당 |
| SpEL timeout | `Future` + `ExecutorService.invokeAny(timeout=50ms)` 래퍼. 초과 시 `WorkflowExpressionTimeoutException` (HTTP 422 `validator_error`). CPU 무한 루프 / ReDoS 차단 |
| SpEL injection | 표현식은 YAML/DB 정의 영역만. **사용자 입력 표현식 평가 금지** (REST API 가 표현식 본문 받지 않음). YAML/DB 변경은 `WORKFLOW_MANAGE` 권한 필요 |
| 모든 REST API | `PermissionValidator` 또는 컨트롤러 `@PreAuthorize` 보호 (운영 profile) |
| 입력 검증 | **Konform** (Kotlin DSL 검증 라이브러리. GAP-17 결정 — 2026-05-21 Maxi 승인) — TransitionRequest, YAML 시드 명세, REST body 전부 |

## §4. API 인터페이스 (REST)

### §4.1 워크플로우 조회

```http
GET /api/v1/workflows
GET /api/v1/workflows/{key}
```

응답 (단건).

```json
{
  "key": "software-default",
  "name": "Software Default",
  "states": [
    { "key": "to-do", "name": "To Do", "category": "TODO" },
    { "key": "in-progress", "name": "In Progress", "category": "IN_PROGRESS" },
    { "key": "review", "name": "Review", "category": "IN_PROGRESS" },
    { "key": "done", "name": "Done", "category": "DONE" }
  ],
  "transitions": [
    {
      "key": "start-work",
      "from": "to-do",
      "to": "in-progress",
      "validators": [{ "type": "RequiredField", "field": null }],
      "postActions": [{ "type": "SetField", "field": "started_at", "value": "${now}" }]
    }
  ]
}
```

### §4.2 전이 발사

```http
POST /api/v1/workflows/{workflowKey}/transitions
```

요청.

```json
{
  "fromState": "in-progress",
  "toState": "review",
  "issueRef": { "issueKey": "PROJ-1", "version": 5, "fields": { "resolution": null } },
  "actor": { "userId": "alice", "roles": ["DEV"] }
}
```

응답 (성공 200).

```json
{
  "fromState": "in-progress",
  "toState": "review",
  "newVersion": 6,
  "postActionsExecuted": ["AddWatcher(reviewer)"]
}
```

응답 (Validator 실패 422).

```json
{ "error": "validator_failed", "details": [{ "validator": "RequiredField", "field": "resolution" }] }
```

### §4.3 캐시 무효화 (관리자)

```http
POST /api/v1/workflows/cache/invalidate
```

응답. 200 `{ "invalidatedAt": "2026-05-21T10:30:00Z" }`

## §5. 데이터 모델 변경

신규 마이그레이션. `backend/modules/project-workflow/src/main/resources/db/migration/V001__init_workflow.sql`

```sql
-- 워크플로우 정의 (YAML 시드 + DB 정규화)
CREATE TABLE workflows (
    id UUID PRIMARY KEY,
    key TEXT UNIQUE NOT NULL,
    name TEXT NOT NULL,
    description TEXT,
    yaml_hash TEXT NOT NULL,  -- 멱등 시드 판정
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE workflow_states (
    id UUID PRIMARY KEY,
    workflow_id UUID NOT NULL REFERENCES workflows(id) ON DELETE CASCADE,
    key TEXT NOT NULL,
    name TEXT NOT NULL,
    category TEXT NOT NULL CHECK (category IN ('TODO', 'IN_PROGRESS', 'DONE')),
    UNIQUE (workflow_id, key)
);

CREATE TABLE workflow_transitions (
    id UUID PRIMARY KEY,
    workflow_id UUID NOT NULL REFERENCES workflows(id) ON DELETE CASCADE,
    key TEXT NOT NULL,
    from_state_id UUID NOT NULL REFERENCES workflow_states(id),
    to_state_id UUID NOT NULL REFERENCES workflow_states(id),
    name TEXT NOT NULL,
    UNIQUE (workflow_id, key)
);

CREATE TABLE workflow_validators (
    id UUID PRIMARY KEY,
    transition_id UUID NOT NULL REFERENCES workflow_transitions(id) ON DELETE CASCADE,
    type TEXT NOT NULL,  -- RequiredField | Permission | NotStatusCategory | CustomExpression
    config_jsonb JSONB NOT NULL,
    "order" INT NOT NULL DEFAULT 0
);

CREATE TABLE workflow_post_actions (
    id UUID PRIMARY KEY,
    transition_id UUID NOT NULL REFERENCES workflow_transitions(id) ON DELETE CASCADE,
    type TEXT NOT NULL,  -- SetField | AddWatcher | Notify | CallWebhook | RunAutomation
    config_jsonb JSONB NOT NULL,
    "order" INT NOT NULL DEFAULT 0
);

CREATE INDEX idx_workflow_states_workflow ON workflow_states(workflow_id);
CREATE INDEX idx_workflow_transitions_workflow ON workflow_transitions(workflow_id);
CREATE INDEX idx_workflow_transitions_from ON workflow_transitions(from_state_id);
CREATE INDEX idx_workflow_transitions_to ON workflow_transitions(to_state_id);
CREATE INDEX idx_workflow_validators_transition ON workflow_validators(transition_id);
CREATE INDEX idx_workflow_post_actions_transition ON workflow_post_actions(transition_id);
```

> DATA.md 절대 규칙 — `created_at`/`updated_at`은 timestamptz, soft delete 미적용 (워크플로우 정의는 hard 삭제 가능. 인스턴스 데이터는 issue-tracking BC가 소유).

## §6. 엣지 케이스

| 케이스 | 처리 |
|---|---|
| Self-transition (from = to) | 명시적 허용 (이슈 코멘트만 추가 + 알림 같은 케이스). YAML에서 명시적 정의해야 함 (자동 생성 안 함) |
| Validator 4종 부분 통과 (3 pass + 1 fail) | 1 fail로 전체 reject. 422 + 어떤 validator 가 실패했는지 모두 반환 |
| PostAction 5종 부분 실행 (2 success + 3rd fail) | 전이 + 1, 2 PostAction 모두 롤백 (트랜잭션 단위) |
| 워크플로우 정의 YAML 파싱 실패 | 부팅 차단 (FailFast). 이전 정의 유지 안 함 — 명시적 |
| YAML 해시 변경 + 마이그레이션 진행 중 동시 전이 요청 | **PostgreSQL advisory lock** (`pg_advisory_xact_lock(<workflow_id_hash>)`) — 캐시 무효화 + 새 정의 적재 동안 같은 workflow 전이 요청은 lock 대기. 대기 timeout 200ms 초과 시 503 응답 |
| 다중 인스턴스 캐시 정합성 | **본 PR 은 단일 인스턴스 가정** (Naver Cloud Docker Compose 단일 호스트). 다중 인스턴스 확장 시 cache invalidate 가 instance A → B 전파 안 됨 → 후속 PR (pgmq pub/sub 또는 LISTEN/NOTIFY) 에서 해소. spec 본문 명시. |
| CustomExpression 의 SpEL 평가 무한 루프 | SimpleEvaluationContext + 평가 timeout 50ms — 초과 시 422 (`validator_error`) |
| 표준 4종 워크플로우 수정 시도 | DB constraint 또는 application 검증으로 차단. "표준은 코드/YAML 변경으로만" 명시 |
| issueRef 의 version 누락 또는 0 | 422 (낙관적 락 불가 — 명시적 reject) |
| from State 가 issue 의 현재 State 와 불일치 | 409 (conflict — 다른 클라이언트가 먼저 전이). 클라이언트 재시도 결정 |

## §7. 제약 조건

| 제약 | 내용 |
|---|---|
| BC 격리 | identity-access / issue-tracking / automation 직접 import 0건 (jOOQ generated 패키지 제외). port 인터페이스 또는 pgmq 이벤트로만 통신. ArchUnit 검증 |
| 절대 규칙 | DEVELOPMENT.md §1 #16 (완제품 기준) 모두 만족. PoC/임시 코드 표현 금지 |
| 학습 적용 | learning #91 — `@Transactional` 메서드 클래스에 `@Service` 부착. ArchUnit 검증 |
| 학습 적용 | learning #44 — wave 별 ktlintFormat / SQL / YAML 포맷은 본인 파일만. 부수 변경 발생 시 controller 가 별도 chore 커밋 |
| 학습 적용 | learning #107 — "PoC" / "임시" / "일단 동작만" 표현 금지. 모든 코드 완제품 |
| 의존성 (신규 추가) | **Konform** (Maxi 승인 완료 — GAP-17). antlr4 추가 안 함 (SpEL 채택 — GAP-2/3 결정) |
| 의존성 (기존) | Spring Boot 3.x, Kotlin 2.x, Flyway, jOOQ, Testcontainers (`postgresql`), Kotest property |
| 단독 머지 가능성 | **본 PR 은 PR #8 (FR-AU-09) 미머지 상태에서 단독 진행 가능** — test profile + `AlwaysAllowPermissionResolver` stub. 운영 배포는 PR #8 머지 후 별도 PR (`IdentityAccessPermissionResolver` 추가, `AlwaysAllow` deprecate) |
| FR-WF-02 분리 | **`WorkflowAssignment` (프로젝트 ↔ 워크플로우 바인딩) 는 FR-WF-02 영역 — 본 PR 범위 외**. 호출자가 매번 `workflowKey` 명시. domain note `project-workflow.md` 의 WorkflowAssignment 항목은 FR-WF-02 PR 에서 정리 |

## §8. 측정 가능한 완료 기준

- [ ] V001 마이그레이션 실행 → 5 테이블 + 5 인덱스 생성
- [ ] 표준 4종 YAML 시드 → DB 5 테이블 적재 (총 ~50건 row)
- [ ] 5 도메인 entity (Workflow, State, Transition, Validator, PostAction) + repository
- [ ] WorkflowEngine 구현 + property-based test 1000건 통과
- [ ] 4 Validator + 5 PostAction 구현 + 단위 테스트 18건 (4×2 + 5×2)
- [ ] Validator/PostAction config jsonb 직렬화 라운드트립 테스트
- [ ] REST API 3개 (GET 목록/단건, POST 전이, POST 캐시 무효화)
- [ ] Testcontainers 통합 테스트 7건 (S1~S6 + cache invalidate)
- [ ] 워크플로우 다이어그램 mermaid 컴포넌트 (D6)
- [ ] E2E (Playwright) — 4 표준 워크플로우 happy path 4건 (D7)
- [ ] p95 전이 처리 측정 — 100ms 미만 + plan/product/project-workflow.md 표 갱신
- [ ] ArchUnit 룰 4건 (@Service 강제 + BC 격리 3건. id-access/issue-tracking/automation)
- [ ] ADR 4건 작성 (terminology/spel/yaml-vs-db/cross-bc-port)
- [ ] `docs/plan/product/project-workflow.md` §2.1 FR-WF-01 의 D1~D7 체크박스 [x]
- [ ] `docs/poc/dependencies.md` (레거시 PoC 디렉토리 — learning #107 적용 중 이름 정리 예정) 의 §1.1 항목을 본 PR plan 으로 흡수 마크 + deprecate 사유 적기

## §9. 결정 (게이트 1 통과 시점 기준)

| 항목 | 옵션 | 결정 |
|---|---|---|
| YAML truth source vs DB truth source | A. YAML이 truth, DB는 캐시 / B. DB가 truth, YAML 부팅 시 1회 시드 / C. 하이브리드 (표준 4종은 코드 seed) | **C** — 표준 4종은 코드/YAML seed. 커스텀 워크플로우는 FR-WF-02 영역 |
| PostAction 실패 시 트랜잭션 정책 | 전체 롤백 / PostAction만 비동기 재시도 | **계산 단계만 — 적용은 호출자 BC 트랜잭션** (GAP-2 결정 반영. 호출자가 실패 시 ROLLBACK) |
| WorkflowTransitionExecuted 이벤트 outbox 패턴 | 본 PR / 후속 PR | **본 PR — emitEvents 명세 반환만**. 실제 outbox 인프라는 호출자 BC 또는 후속 PR (messaging) |
| Konform 의존성 (입력 검증) | 본 PR / 후속 PR | **본 PR — Maxi 승인 (GAP-17, 2026-05-21)** |
| 이슈 상태 영속화 소유권 | issue-tracking / project-workflow | **issue-tracking 소유 (GAP-2, 2026-05-21)** — WorkflowEngine 은 계산만 |
| port 트랜잭션 전파 | REQUIRED / MANDATORY / REQUIRES_NEW | **MANDATORY** — 호출자 트랜잭션 강제 |
| CustomExpression 파서 | SpEL / ANTLR / 명주만 | **SpEL (도메인 단계 확정)** |
| Validator 용어 통일 | Validator / Guard / Gate | **Validator (도메인 단계 확정)** — domain note + glossary + SDD 후속 정정 |
| advisory lock 채택 | pg_advisory_xact_lock / 메모리 ReadWriteLock / DB row lock | **pg_advisory_xact_lock** — workflow_id 해시 기반. 다중 인스턴스 안전 |
| 다중 인스턴스 캐시 정합성 | 본 PR / 후속 PR | **후속 PR (pgmq pub/sub 또는 LISTEN/NOTIFY)** — 본 PR 단일 인스턴스 가정 |

---

## §10. Brainstorming Check

> Phase B brainstorming sanity check 결과 — 2026-05-21 실시 (sub-agent dispatch).
>
> **결과**. ✅ 통과. 14 sanity check 차원 중 13 차원에서 17 gap 발견 → 17건 모두 흡수 (15건 spec 본문 수정 + 2건 Maxi 결정 후 반영). 차원 10 (Validator/PostAction 확장 모델) 은 1회차에 통과.

### 17 gap 흡수 표

| ID | 차원 | gap 한 줄 | 흡수 위치 |
|---|---|---|---|
| GAP-1 | 1 | PostAction + outbox 이벤트 순서 모호 | §2.5 (port 시그니처 + 이벤트 발행 패턴) |
| GAP-2 | 1, 4 | 이슈 상태 영속화 소유권 모호 | §2.2 / §2.5 / §9 (issue-tracking 소유 결정) |
| GAP-3 | 2 | NFR p95 측정 조건 누락 | §3.1 (측정 환경 고정 한 줄 추가) |
| GAP-4 | 3, 4 | port 트랜잭션 전파 + 예외 계약 부재 | §2.5 (MANDATORY + 3 예외 타입 명시) |
| GAP-5 | 7 | SpEL timeout 50ms 강제 메커니즘 불명 | §3.1 / §3.4 (Future + ExecutorService 명시) |
| GAP-6 | 7 | SpEL root 객체 신뢰 경계 미정의 | §3.4 (sealed value class IssueView/ActorView) |
| GAP-7 | 6, 8 | ArchUnit BC 격리 룰 jOOQ generated 예외 | §3.3 (`*.jooq.tables.*` 화이트리스트) |
| GAP-8 | 9 | wave dispatch SQL/YAML 포맷 부수 변경 회귀 | §3.3 / §7 (controller 일괄 chore 정책) |
| GAP-9 | 11 | 표준 4종 그래프 closed 검증 부재 | §3.2 (closed 검증 4 워크플로우) |
| GAP-10 | 12 | 다중 인스턴스 캐시 정합성 미정의 | §6 / §9 (단일 인스턴스 가정 + 후속 PR) |
| GAP-11 | 13 | property-based generator 명세 부재 | §3.2 (시퀀스 1~20, validator 무작위, 1000건) |
| GAP-12 | 14 | PR #8 의존성 차단 정도 미명시 | §7 (단독 머지 가능 명시) |
| GAP-13 | 8 | 테스트 커버리지 수치 미정 | §3.2 (line coverage 80%+ 명시) |
| GAP-14 | 5 | YAML 마이그레이션 락 메커니즘 미명세 | §6 (pg_advisory_xact_lock) |
| GAP-15 | 1, 6 | WorkflowAssignment 범위 불명 | §7 / §9 (FR-WF-02 범위 외 명시) |
| GAP-16 | 9 | learning #107 PoC 단어 잔존 | §8 (`docs/poc/dependencies.md` 한정구) |
| GAP-17 | 4 | Konform 의존성 절대 규칙 위반 risk | §3.4 / §7 / §9 (Maxi 승인 반영) |

### 결정 가지치기 결과 (학습 회귀 방지)

- **GAP-2 결정 (issue-tracking 소유)** — port 시그니처가 `execute()` → `plan()` 으로 변경. WorkflowEngine 은 계산만 하고 호출자가 트랜잭션 안에서 적용. BC 격리 룰 완전 준수.
- **GAP-17 결정 (Konform 도입)** — DEVELOPMENT.md §1 #17 (외부 의존성 추가 시 Maxi 확인) 준수. 게이트 1 에서 Maxi 재확인 가능.

### 게이트 1 검토 항목 (Maxi 사전 알림)

- 절대 규칙 신규 추가/변경 0건
- ADR 후보 4건 (terminology / spel / yaml-vs-db / cross-bc-port)
- 신규 외부 의존성. **Konform** (GAP-17 — 이미 spec 본문 승인 반영)
- 도메인 노트 갱신 후속 작업 1건 (`Maxi_wiki/BTS/domain/project-workflow.md` — ANTLR → SpEL, Validator/PostAction 용어 정정. Obsidian 단방향 — 별도 PR 또는 sync 자동화)
- SDD 갱신 후속 작업 1건 (`docs/sdd/07-workflow-engine.md` 의 Validator/Post-function 표 갱신)
