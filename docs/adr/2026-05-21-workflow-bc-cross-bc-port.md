<!-- ADR: 워크플로우 BC 간 호출 — Hexagonal port-adapter + plan() 시그니처 + Propagation.MANDATORY -->

# ADR — workflow-bc-cross-bc-port

**일자**. 2026-05-21
**상태**. Accepted
**관련 PR**. `project-workflow-bc-fr-wf-01-fsm-1-pr`
**작성자**. Maxi + Claude (backend-engineer)

## 컨텍스트

BTS는 BC (Bounded Context — 책임 범위로 나눈 도메인 단위) 격리 룰을 헌법 수준으로 강제한다 (CLAUDE.md §핵심 패턴).

```
한 PR = 한 BC. 다른 BC 호출은 이벤트 발행만 (pgmq), 직접 import 금지.
```

project-workflow BC는 세 타 BC와 상호작용해야 한다.

| 상호작용 | 방향 | 목적 |
|---|---|---|
| identity-access | project-workflow → identity-access | PermissionValidator 평가 시 권한 시스템 조회 |
| issue-tracking | issue-tracking → project-workflow | 이슈 상태 변경 시 워크플로우 전환 실행 |
| automation | automation → project-workflow | 자동화 룰이 워크플로우 전환을 트리거 |

이 상호작용을 "BC 격리 룰" 안에서 어떻게 구현할 것인가가 이 ADR의 핵심 결정 사항이다.

### 고려한 호출 패턴

**옵션 A — 직접 import.** 타 BC 클래스를 직접 import해 호출. BC 격리 룰 위반. 즉시 탈락.

**옵션 B — REST API 호출.** BC 간 HTTP 요청. 단일 트랜잭션 보장 불가. 네트워크 장애 시 데이터 불일치 발생. 모놀리스 구조에서 과도한 복잡성.

**옵션 C — Hexagonal Architecture (port-adapter) 패턴.** 각 BC가 interface (port)를 정의하고, 실제 구현체(adapter)는 해당 BC가 제공. 호출자는 interface만 의존. 직접 import 0건 달성 가능.

### plan() vs execute() 시그니처 결정 (2026-05-21)

WorkflowEngine이 이슈 상태를 직접 영속화(저장)하면 이슈 소유권이 issue-tracking BC에 있으면서 project-workflow BC도 이슈 DB를 건드리는 BC 경계 침범이 발생한다.

- **execute()**: 전환 계산 + 이슈 영속화까지 전부 project-workflow가 담당. BC 소유권 위반.
- **plan()**: 전환 계산만 수행, `TransitionPlan` (toState + fieldChanges + emitEvents) 반환. 이슈 영속화와 이벤트 발행은 호출자(issue-tracking) 책임. BC 소유권 보존.

## 결정

**Hexagonal port-adapter 패턴 채택 (옵션 C). plan() 시그니처 + Propagation.MANDATORY 조합.**

### inbound port — WorkflowTransitionPort

issue-tracking BC와 automation BC가 project-workflow BC를 호출할 때 사용하는 진입 interface.

```kotlin
// project-workflow BC가 정의, issue-tracking/automation이 호출
interface WorkflowTransitionPort {
    @Transactional(propagation = Propagation.MANDATORY)
    fun plan(req: TransitionRequest): TransitionPlan
}
```

- **TransitionRequest**: `issueKey`, `fromStateKey`, `transitionKey`, `actorId`, `version`, `issueFields` 포함
- **TransitionPlan**: `toState`, `fieldChanges: List<FieldChange>`, `emitEvents: List<DomainEvent>` 반환
- **이슈 영속화**: TransitionPlan을 받은 호출자(issue-tracking BC)가 자신의 트랜잭션 안에서 적용. project-workflow는 DB에 이슈를 쓰지 않음.
- **emitEvents 적용**: 호출자의 outbox(메시지 발송 대기열) INSERT 책임. pgmq enqueue는 호출자가 수행.

### outbound port — PermissionResolver

project-workflow BC가 identity-access BC의 권한 시스템을 조회할 때 사용하는 탈출 interface.

```kotlin
// project-workflow BC가 정의, identity-access가 구현체(adapter) 제공
interface PermissionResolver {
    fun hasPermission(actorId: ActorId, permission: String, scope: Scope): Boolean
}

sealed interface Scope {
    object Global : Scope
    data class Project(val key: String) : Scope
    data class Issue(val key: String) : Scope
}
```

### 트랜잭션 전파 — Propagation.MANDATORY

`WorkflowTransitionPort.plan()`에 `@Transactional(propagation = Propagation.MANDATORY)` 적용.

- MANDATORY 의미: 호출자가 이미 시작한 트랜잭션 안에서만 동작. 트랜잭션 없이 호출하면 `IllegalTransactionStateException` 즉시 발생.
- 이 규칙을 통해 "이슈 영속화 + 워크플로우 계산이 반드시 단일 트랜잭션으로 묶임"을 컴파일/런타임 수준에서 보장.
- 호출자(issue-tracking)가 `@Transactional`을 빠뜨리면 즉시 예외로 발각 — 묵시적 데이터 불일치 방지.

### ArchUnit 강제 (Task 34)

직접 import 0건 규칙은 사람이 지키는 것이 아니라 빌드 단계에서 기계가 강제한다.

```
project-workflow → identity-access/issue-tracking/automation 클래스 직접 import 시 빌드 실패
```

ArchUnit (아키텍처 규칙을 테스트 코드로 작성하는 라이브러리) 커스텀 룰로 Task 34에서 구현.

## 결과

### 긍정

- **BC 소유권 보존**. project-workflow는 워크플로우 계산만 담당. 이슈/이벤트 영속화는 각 소유 BC 책임.
- **단일 트랜잭션 일관성**. Propagation.MANDATORY로 호출자-피호출자가 동일 트랜잭션 내 실행. DB 불일치 위험 제거.
- **직접 import 0건**. interface만 의존 → ArchUnit 룰로 빌드 시 강제 검증.
- **점진적 연결**. 각 타 BC 구현체(adapter)는 해당 BC의 PR에서 독립적으로 제공. 의존성 차단 없음.

### 과도기 처리 (본 PR)

| 연결 | 본 PR 처리 | 후속 처리 |
|---|---|---|
| project-workflow → identity-access | `AlwaysAllowPermissionResolver` stub (`@Profile("!prod")`) | identity-access PR #8 머지 후 `IdentityAccessPermissionResolver` 어댑터 추가 PR |
| project-workflow ← issue-tracking | `WorkflowTransitionPort` interface 노출 | issue-tracking BC PR에서 호출자 측 구현 |
| project-workflow ← automation | 동일 inbound port 재사용 | automation BC PR에서 호출자 측 구현 |

`AlwaysAllowPermissionResolver`는 `@Profile("!prod")`로 운영(prod) 환경 부팅을 차단한다. identity-access 연결 전 운영 배포 불가를 런타임이 보장.

### 부정 / 위험

- **Propagation.MANDATORY 학습 곡선**. 호출자가 `@Transactional` 누락 시 런타임 예외. 초기 통합 테스트에서 발견 가능하도록 통합 테스트 작성 필수.
- **인터페이스 계약 변경 비용**. `TransitionRequest` / `TransitionPlan` DTO 변경 시 모든 호출자 BC 동시 수정 필요. 버전 관리 정책은 FR-WF 후속 PR에서 결정.
- **AlwaysAllowPermissionResolver 운영 노출 위험**. `@Profile("!prod")` 적용으로 기계적으로 차단하지만, profile 설정 오류 시 stub이 운영에 노출될 수 있음. prod 배포 전 profile 검증 체크리스트 필요.

## 정정 이력

2026-05-26 PR #23. 본 ADR 의 BC 격리 본질 강화 보강. WorkflowTransitionPort 시그니처가 throws → sealed Result 로 진화. 자세한 결정 근거. [2026-05-26-workflow-transition-port-result-sealed.md](2026-05-26-workflow-transition-port-result-sealed.md). 본 ADR 의 핵심 결정 (Hexagonal port-adapter + plan() + Propagation.MANDATORY) 은 그대로 유효.

## 관련

- `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/port/inbound/WorkflowTransitionPort.kt`
- `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/port/outbound/PermissionResolver.kt`
- `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/adapter/AlwaysAllowPermissionResolver.kt`
- `docs/plans/2026-05-21-project-workflow-bc-fr-wf-01-fsm-1-pr.md` §타 BC 의존성
- CLAUDE.md §핵심 패턴 — BC 격리
- DEVELOPMENT.md §1 절대 규칙
- [docs/sdd/07-workflow-engine.md](../sdd/07-workflow-engine.md) §7.1
