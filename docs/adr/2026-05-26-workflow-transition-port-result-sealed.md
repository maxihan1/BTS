<!-- ADR: WorkflowTransitionPort sealed Result 도입 — BC 격리 본질 강화 -->

# ADR — workflow-transition-port-result-sealed

**일자**. 2026-05-26
**상태**. Accepted
**관련 PR**. `issue-tracking-bc-fr-is-01-cleanup-tests` (PR #23)
**작성자**. Maxi + Claude (backend-engineer)

## 컨텍스트

PR #17 (FR-IS-01 D1~D3) codereview CONCERN-2 에서 다음 문제가 제기되었다.

`IssueApplicationService.transitionIssue` 가 `WorkflowTransitionPort.plan()` 을 try/catch 로 감싸며 `WorkflowValidatorFailureException` 을 직접 catch 한다. 이 exception 타입은 `com.bts.workflow.domain.exception` 패키지에 속하므로 issue-tracking BC 가 project-workflow BC 의 내부 도메인 exception 을 직접 import 하는 상황이 된다.

CLAUDE.md §핵심 패턴 — BC 격리 룰은 다음을 명시한다.

```
다른 BC 호출은 이벤트 발행만 (pgmq), 직접 import 금지.
```

port interface 를 통해 메서드 시그니처는 격리했으나, exception 타입 import 를 통해 BC 격리가 우회되는 문제다. BTS learnings (2026-05-26 "BC 격리 wrapper exception 패턴") 는 이 상황의 후속 후보 (3) 으로 "port 시그니처를 sealed Result 로 변경하면 catch 자체도 제거 가능" 을 명시했다.

## 대안 검토

**옵션 A — throws 유지 (현 상태, BC 경계 누출).**
- `WorkflowTransitionPort.plan()` 이 `WorkflowValidatorFailureException` 등을 throws 선언 (또는 unchecked 방치).
- 호출자(issue-tracking BC)가 `com.bts.workflow.domain.exception.*` 를 직접 import 해야 한다.
- 단점. BC 격리 위반 — issue-tracking 이 project-workflow 내부 domain exception 에 의존. ArchUnit 룰 추가 시 빌드 실패 대상.

**옵션 B — wrapper exception adapter 측 (PR #17 채택, 호출자 try/catch 잔존).**
- project-workflow 측 adapter 가 내부 exception 을 BTS 공통 exception (예. `WorkflowCallException`) 으로 래핑해 rethrow.
- 호출자는 공통 exception 만 catch — domain exception 직접 import 0건.
- 단점. 호출자 측 try/catch 블록 자체는 잔존. 새 분기 추가 시 호출자 catch 블록도 갱신 필요 (exhaustive 강제 없음).

**옵션 C — sealed Result 반환 (본 PR 채택, exception import 0건).**
- `WorkflowTransitionPort.plan()` 반환 타입을 `TransitionResult` (sealed interface) 로 변경.
- adapter 내부에서 try/catch + Result 매핑. 호출자는 Kotlin exhaustive `when` 분기로 처리.
- 장점. 호출자 import 에서 `com.bts.workflow.domain.exception.*` 완전 제거. 새 분기 추가 시 when 블록 컴파일 에러로 자동 탐지.
- 단점. 기존 WorkflowEngine 의 throws 기반 시그니처를 adapter 계층에서 흡수해야 하므로 adapter 1개 신규 작성 필요.

## 결정

**옵션 C — sealed Result 반환 채택.**

`WorkflowTransitionPort.plan(req: TransitionRequest): TransitionResult` 로 시그니처 변경. `TransitionResult` 는 4개 case 를 가진 sealed interface 다.

### TransitionResult sealed interface

```kotlin
// 워크플로우 전환 결과 — sealed interface 로 호출자 BC 가 when exhaustive 분기 처리
sealed interface TransitionResult {
    data class Success(val plan: TransitionPlan) : TransitionResult
    data class ValidatorFailure(val message: String) : TransitionResult
    data class WorkflowNotFound(val key: String) : TransitionResult
    data class ExpressionTimeout(val message: String) : TransitionResult
}
```

위치. `com.bts.workflow.domain.dto.TransitionResult`

### WorkflowTransitionAdapter 신규 (port 구현체)

project-workflow BC 내부에 `WorkflowTransitionAdapter` (신규) 를 두고, 이 adapter 가 `WorkflowEngine.plan()` 을 내부적으로 try/catch 한 뒤 TransitionResult 로 매핑한다. `com.bts.workflow.domain.exception.*` import 는 이 adapter 파일 안에서만 허용된다.

```kotlin
// WorkflowTransitionAdapter.kt (com.bts.workflow.adapter.inbound)
@Component
class WorkflowTransitionAdapter(
    private val workflowEngine: WorkflowEngine
) : WorkflowTransitionPort {

    @Transactional(propagation = Propagation.MANDATORY)
    override fun plan(req: TransitionRequest): TransitionResult = try {
        TransitionResult.Success(workflowEngine.plan(req))
    } catch (e: WorkflowValidatorFailureException) {
        TransitionResult.ValidatorFailure(e.message ?: "")
    } catch (e: WorkflowNotFoundException) {
        TransitionResult.WorkflowNotFound(e.workflowKey)
    } catch (e: WorkflowExpressionTimeoutException) {
        TransitionResult.ExpressionTimeout(e.message ?: "")
    }
}
```

### IssueApplicationService 변경

호출자(issue-tracking BC) 는 `when (result)` exhaustive 분기로 TransitionResult 처리. `com.bts.workflow.domain.exception.*` import 0건.

```kotlin
when (val result = workflowPort.plan(transitionReq)) {
    is TransitionResult.Success -> result.plan
    is TransitionResult.ValidatorFailure ->
        throw IssueTransitionNotAllowedException(result.message)
    is TransitionResult.WorkflowNotFound ->
        throw IssueTransitionNotAllowedException("워크플로우 없음: ${result.key}")
    is TransitionResult.ExpressionTimeout ->
        throw IssueTransitionNotAllowedException("워크플로우 평가 시간 초과: ${result.message}")
}
```

새 TransitionResult case 추가 시 Kotlin 컴파일러가 `when` 블록 exhaustive 검사로 즉시 컴파일 에러를 발생시킨다. silent fail 차단.

## 결과

### 긍정

- **BC 격리 본질 달성**. issue-tracking BC 코드에서 `com.bts.workflow.domain.exception.*` import 가 0건이 된다. ArchUnit 룰 `IssueBcArchTest` 로 빌드 시 기계적 검증.
- **exhaustive 강제**. sealed interface + Kotlin `when` 조합으로 신규 분기 추가 시 호출자가 반드시 처리해야 함을 컴파일 타임에 보장. 런타임 미처리 분기 불가.
- **exception 계층 단순화**. project-workflow BC 의 domain exception 은 adapter 1곳에서만 처리. 호출자 BC 가 exception 계층을 알 필요 없다.

### 부정

- **adapter 계층 신규 도입 비용**. WorkflowEngine 의 기존 throws 기반 시그니처를 흡수하기 위해 `WorkflowTransitionAdapter` 1개를 새로 작성해야 한다. 기존 WorkflowEngine 자체 시그니처 변경을 피하기 위한 절충이므로 영향 범위는 adapter 1파일로 제한된다.
- **TransitionResult DTO 진화 비용**. 새 exception 종류가 생기면 TransitionResult case 추가 + adapter catch 추가 + 호출자 when 분기 추가 3곳 동시 갱신 필요. 단, Kotlin 컴파일러가 when 분기 누락을 자동 탐지하므로 누락 자체는 불가능하다.

### 위험

- **Propagation.MANDATORY 맥락 유지**. adapter 의 `@Transactional(propagation = Propagation.MANDATORY)` 는 기존 port 계약을 그대로 계승한다. 호출자가 트랜잭션 없이 adapter 를 호출하면 `IllegalTransactionStateException` 발생. 통합 테스트에서 트랜잭션 누락 시나리오 검증 필수 (기존 ADR `2026-05-21-workflow-bc-cross-bc-port.md` 의 리스크 동일).
- **automation BC 추후 호출 시 when 분기 중복**. 현재 호출자가 issue-tracking 1개이므로 helper (`TransitionResult.unwrapOrThrow()`) 도입은 Y-AGNI. FR-AUTO-01 시점에 재검토 (plan §F10 deferred).

## 관련

- `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/domain/dto/TransitionResult.kt`
- `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/adapter/inbound/WorkflowTransitionAdapter.kt`
- `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/port/inbound/WorkflowTransitionPort.kt`
- `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/application/IssueApplicationService.kt`
- `docs/adr/2026-05-21-workflow-bc-cross-bc-port.md` — port-adapter 원형 + Propagation.MANDATORY 결정
- CLAUDE.md §핵심 패턴 — BC 격리
- DEVELOPMENT.md §1 절대 규칙
