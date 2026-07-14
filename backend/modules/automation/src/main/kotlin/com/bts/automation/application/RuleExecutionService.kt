// 자동화 룰 실행 이력(RuleExecution) 조회 + replay(재실행) 서비스 — 목록/단건 trace/동기 재실행, MANAGE_AUTOMATION 가드 (FR-AT-05 Task 4/5)

package com.bts.automation.application

import com.bts.automation.adapter.AutomationRuleRepository
import com.bts.automation.adapter.RuleExecutionRepository
import com.bts.automation.domain.AutomationRule
import com.bts.shared.permission.AutomationPermissionResolver
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * 자동화 룰 실행 이력([RuleExecution]) 조회 + replay(재실행) 유스케이스 서비스 (FR-AT-05 Task 4/5).
 *
 * [listByRule]/[getById] 는 조회 전용이라 `@Transactional(readOnly = true)` 다. [replay] 는 **실제
 * 재실행**(dryRun=false 이슈 변경 위임)이라 성격이 달라 `@Transactional` 을 두지 않는다 — "replay —
 * 실행-시점 트랜잭션 비대칭" 절 참고.
 *
 * ## 두 조회의 권한 판정 축이 다르다
 * [listByRule] 은 URL 경로의 [projectKey] 를 그대로 [permissionResolver] 에 넘겨 MANAGE_AUTOMATION 을
 * 판정한다(룰 존재 여부는 확인하지 않는다 — 소프트/하드 삭제된 룰의 이력도 감사 목적으로 조회 가능해야
 * 한다, NFR-4 · [RuleExecutionRepository] 클래스 KDoc "룰 테이블 조인 없음" 참고). [getById]/[replay] 는
 * 경로에 `projectKey` 가 없는 전역 엔드포인트라 **레코드를 먼저 읽어** `record.projectKey` 로 권한을
 * 판정한다([requireVisibleExecution]) — 그래서 미존재와 타 프로젝트(권한 없음) 두 경우 모두
 * [RuleExecutionNotFoundException](404)으로 수렴시켜 존재를 숨긴다(다른 프로젝트 소속 실행 id 를 안다고
 * 해서 "권한 없음(403)"으로 그 존재를 확인시켜 주지 않는다).
 *
 * ## replay — 실행-시점 트랜잭션 비대칭 (FR-AT-05 Task 5, 코드리뷰 E1)
 * [replay] 는 저장된 원본 트리거([RuleExecution.triggerEvent])로 [actionExecutor] 를 dryRun=false 로
 * 재호출해 **실제 이슈 변경을 유발**한다. 그래서 `@Transactional` 로 감싸지 않는다 — 이유는
 * [com.bts.automation.worker.AutomationExecutionWorker] 클래스 KDoc "`@Transactional` 없음" 절과 같다.
 * [actionExecutor] 가 위임하는 [com.bts.shared.issue.IssueMutationPort] prod 어댑터
 * ([com.bts.issue.adapter.outbound.automation.AutomationIssueMutationAdapter], automation 클래스패스
 * 밖)는 **호출 시점에 앙비언트(ambient) Spring 트랜잭션이 없다는 전제**로 자체
 * [org.springframework.transaction.support.TransactionTemplate] 트랜잭션 경계를 관리한다(async 안전
 * 설계 — [com.bts.shared.issue.IssueMutationPort] KDoc 참조). [replay] 를 `@Transactional` 로 감싸면
 * 이 어댑터가 앙비언트 트랜잭션에 참여(participate)해, 커밋이 [replay] 트랜잭션 종료까지 지연되고
 * ("이슈 변경은 자체 트랜잭션 경계로 즉시 커밋된다"는 설계 불변식이 깨짐), 이후 이력 저장이 실패하면
 * dryRun 롤백 로직과 얽혀 상위 트랜잭션 전체가 오염될 위험도 생긴다
 * ([[transaction-self-invocation-requires-new]] 계열 사고와 동일 근본 원인). 그래서 [actionExecutor.execute]
 * 는 자체 트랜잭션 경계로 이미 커밋되고, 새 이력의 [repository.save] 는 그 뒤 별도의 원자적 insert
 * ([RuleExecutionRepository.save] 자체 `@Transactional`)로 처리한다 — 둘을 하나의 상위 트랜잭션으로
 * 묶어 "실행+이력 저장이 함께 롤백된다"는 거짓 원자성을 기대하지 않는다.
 *
 * [com.bts.automation.worker.AutomationExecutionWorker] 는 이력 저장 실패를 fail-safe 로 삼키지만(감사
 * 로그가 핵심 실행 파이프라인을 막지 않게), [replay] 는 **다르다** — 이 이력 record 자체가 API 응답
 * 계약의 본체이므로 저장 실패를 삼키지 않고 그대로 전파한다(→500, [replay] 구현에 try/catch 없음).
 * 이슈 변경은 이미 커밋됐으니 저장 실패를 숨기면 "실행은 됐는데 응답도 이력도 없는" 상태가 되어
 * 감사·재replay 판단이 불가능해진다.
 *
 * replay 는 **룰의 현재 정의**(저장 당시가 아니라 재실행 시점의 최신 액션/조건, [ruleRepository] 재조회)
 * 와 **원본 실행이 저장한 트리거**를 조합해 실행하고, [com.bts.automation.worker.AutomationExecutionWorker]
 * 의 루프 가드((a) 체인 깊이, (b) 억제창)를 거치지 않는다 — 관리자가 명시적으로 요청하는 재실행이라
 * automation→automation 왕복 루프 차단 대상이 아니다. enabled 여부도 확인하지 않는다(비활성 룰도
 * 명시적 관리자 재실행은 허용).
 *
 * @param repository [RuleExecution] 영속 어댑터.
 * @param permissionResolver MANAGE_AUTOMATION 권한 평가 cross-BC 포트(fail-closed, non-null 주입 —
 *   [[crossbc-resolver-nullable-fail-open]] 회귀 방지, [AutomationRuleService] 동일 관례).
 * @param ruleRepository replay 대상 [AutomationRule] 재조회 어댑터(현재 정의 기준 재실행).
 * @param actionExecutor replay 의 실제 액션 재실행 디스패처.
 * @param clock replay 의 시작/종료 시각 소스. 기본값 UTC(테스트에서 결정적 시각으로 교체,
 *   [com.bts.automation.worker.AutomationExecutionWorker] 동일 관례).
 */
@Service
class RuleExecutionService(
    private val repository: RuleExecutionRepository,
    private val permissionResolver: AutomationPermissionResolver,
    private val ruleRepository: AutomationRuleRepository,
    private val actionExecutor: ActionExecutor,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * [ruleId] 룰의 실행 이력을 최신순으로 조회한다.
     *
     * 룰이 존재하는지, 소프트 삭제됐는지는 확인하지 않는다 — 감사 독립성(NFR-4)상 이력 조회는 룰
     * 생명주기와 분리된다. `limit` 상한 clamp 는 호출자([com.bts.automation.adapter.web.AutomationExecutionController])
     * 가 이미 마친 값을 그대로 [repository] 에 위임한다.
     *
     * @param actorId 조회를 요청하는 행위자.
     * @param projectKey 룰 소속 프로젝트 키(권한 판정 스코프).
     * @param ruleId 대상 룰 id.
     * @param issueKey 지정하면 해당 이슈에 대한 실행만 반환. `null` 이면 전체.
     * @param limit 최대 반환 건수(호출자가 이미 clamp 한 값).
     * @param before 지정하면 이 시각 이전 실행만 반환(keyset 페이지네이션 커서).
     * @return 최신순 실행 이력 목록.
     * @throws AutomationForbiddenException [actorId] 가 [projectKey] 에서 MANAGE_AUTOMATION 권한이 없을 때.
     */
    @Suppress("LongParameterList") // 룰별 이력 조회 필터(actorId/projectKey/ruleId/issueKey/limit/before) 전부 필수
    @Transactional(readOnly = true)
    fun listByRule(
        actorId: UUID,
        projectKey: String,
        ruleId: UUID,
        issueKey: String?,
        limit: Int,
        before: Instant?,
    ): List<RuleExecution> {
        assertManageAutomation(actorId, projectKey)
        val executions = repository.findByRule(projectKey, ruleId, issueKey, limit, before)
        log.debug(
            "rule_execution_list_by_rule actor={} projectKey={} ruleId={} count={}",
            actorId,
            projectKey,
            ruleId,
            executions.size,
        )
        return executions
    }

    /**
     * [executionId] 실행 이력 1건을 trace 상세 조회한다.
     *
     * 클래스 KDoc "두 조회의 권한 판정 축이 다르다" 참고 — 레코드를 먼저 읽어 `record.projectKey` 로
     * 권한을 판정하고, 미존재/권한 없음 모두 같은 예외로 수렴시켜 존재를 숨긴다.
     *
     * @param actorId 조회를 요청하는 행위자.
     * @param executionId 조회할 실행 이력 id.
     * @return 조회된 실행 이력.
     * @throws RuleExecutionNotFoundException [executionId] 가 없거나, 있어도 [actorId] 가 그 소속
     *   프로젝트에서 MANAGE_AUTOMATION 권한이 없을 때(존재 숨김 — 403 이 아니다).
     */
    @Transactional(readOnly = true)
    fun getById(
        actorId: UUID,
        executionId: UUID,
    ): RuleExecution {
        val execution = requireVisibleExecution(actorId, executionId)
        log.debug("rule_execution_get_by_id actor={} executionId={}", actorId, executionId)
        return execution
    }

    /**
     * [executionId] 실행 이력을 재료로 **실제 재실행**한다(FR-AT-05 Task 5).
     *
     * 클래스 KDoc "replay — 실행-시점 트랜잭션 비대칭" 참고 — `@Transactional` 로 감싸지 않는다.
     *
     * @param actorId 재실행을 요청하는 행위자. 재실행 권한 판정에만 쓰인다 — 실제 이슈 변경의 actor 는
     *   원본 룰의 [AutomationRule.actorUserId]([actionExecutor] 내부에서 그대로 사용).
     * @param executionId 재실행 재료가 될 원본 실행 이력 id.
     * @return 새로 생성·저장된 실행 이력([RuleExecution.replayedFrom] = [executionId]).
     * @throws RuleExecutionNotFoundException [executionId] 가 없거나 [actorId] 가 그 소속 프로젝트에서
     *   MANAGE_AUTOMATION 권한이 없을 때(존재 숨김, [getById] 와 동일 정책).
     * @throws AutomationRuleUnavailableException 원본 룰이 소프트 삭제/부재라 재실행할 룰 정의를 찾을
     *   수 없을 때 — enabled 여부는 확인하지 않는다(관리자의 명시적 재실행 행위).
     */
    fun replay(
        actorId: UUID,
        executionId: UUID,
    ): RuleExecution {
        val record = requireVisibleExecution(actorId, executionId)
        val rule = ruleRepository.findById(record.ruleId) ?: throw AutomationRuleUnavailableException(record.ruleId)
        val startedAt = clock.instant()
        val result = actionExecutor.execute(rule, record.triggerEvent, dryRun = false)
        val finishedAt = clock.instant()
        val replayed = buildReplayExecution(record, rule, result, startedAt, finishedAt)
        repository.save(replayed)
        log.info(
            "rule_execution_replay actor={} sourceExecutionId={} newExecutionId={} status={}",
            actorId,
            executionId,
            replayed.id,
            replayed.status,
        )
        return replayed
    }

    /**
     * [executionId] 를 조회해 [actorId] 가 그 소속 프로젝트에서 MANAGE_AUTOMATION 권한을 가졌는지
     * 확인한다([getById]/[replay] 공용 — 클래스 KDoc "두 조회의 권한 판정 축이 다르다" 참고).
     *
     * @throws RuleExecutionNotFoundException 미존재 또는 권한 없음(존재 숨김, 403 이 아니다).
     */
    private fun requireVisibleExecution(
        actorId: UUID,
        executionId: UUID,
    ): RuleExecution {
        val execution = repository.findById(executionId) ?: throw RuleExecutionNotFoundException(executionId)
        if (!permissionResolver.hasManageAutomation(actorId, execution.projectKey)) {
            throw RuleExecutionNotFoundException(executionId)
        }
        return execution
    }

    /** MANAGE_AUTOMATION 판정 — 거부 시 일반 메시지 [AutomationForbiddenException](교훈 fr-pm-04). */
    private fun assertManageAutomation(
        actorId: UUID,
        projectKey: String,
    ) {
        if (!permissionResolver.hasManageAutomation(actorId, projectKey)) {
            throw AutomationForbiddenException()
        }
    }
}

/**
 * [record] 를 재료로 한 replay 결과([result])를 새 [RuleExecution] 레코드로 매핑한다
 * ([com.bts.automation.worker.AutomationExecutionWorker] 의 `buildRuleExecution` 최상위 함수와 동형
 * 매핑 전략 — 인스턴스 상태에 의존하지 않는 순수 변환이라 클래스 밖에 둔다). id 는 새로 발급하고,
 * [RuleExecution.triggerType]/[RuleExecution.triggerEvent]/[RuleExecution.issueKey] 는 **원본 fire-time
 * 값을 그대로 보존**한다(재실행 시점이 아니라 "무엇이 재현됐는지"가 감사 관점에서 중요하다).
 * [RuleExecution.replayedFrom] 은 원본 실행 id([record.id]).
 */
private fun buildReplayExecution(
    record: RuleExecution,
    rule: AutomationRule,
    result: ActionExecutionResult,
    startedAt: Instant,
    finishedAt: Instant,
): RuleExecution =
    RuleExecution(
        id = UUID.randomUUID(),
        ruleId = rule.id,
        projectKey = rule.projectKey,
        triggerType = record.triggerType,
        triggerEvent = record.triggerEvent,
        issueKey = record.issueKey,
        status = result.status,
        outcomes = result.outcomes,
        replayedFrom = record.id,
        startedAt = startedAt,
        finishedAt = finishedAt,
    )

/**
 * 요청한 [executionId] 에 해당하는 실행 이력이 없거나(또는 있어도 요청자가 그 소속 프로젝트에서
 * MANAGE_AUTOMATION 권한이 없어 존재를 숨겨야) 함을 나타낸다. 웹 레이어에서 404 로 매핑된다.
 *
 * @property executionId 조회를 시도한 실행 이력 id(호출자가 이미 URL 로 알고 있는 값이라 응답에
 *   노출해도 누출이 아니다, [com.bts.automation.application.AutomationRuleNotFoundException] 동일 관례).
 */
class RuleExecutionNotFoundException(
    val executionId: UUID,
) : RuntimeException("실행 이력을 찾을 수 없습니다: $executionId")

/**
 * [RuleExecutionService.replay] 대상 원본 실행 이력이 참조하는 룰([RuleExecution.ruleId])이 소프트
 * 삭제됐거나 존재하지 않아 재실행할 수 없음을 나타낸다. 웹 레이어에서 409(Conflict) 로 매핑된다 —
 * 도메인 불변식 위반이 아니라 재실행 재료(룰 정의) 부재이며, 룰이 되살아나지 않는 한 클라이언트가
 * 재시도해도 항상 같은 결과라 409 가 적절하다.
 *
 * @property ruleId 재실행하려던 원본 룰 id(호출자가 이미 알고 있는 값이라 응답 노출이 누출이 아니다,
 *   [RuleExecutionNotFoundException] 동일 관례).
 */
class AutomationRuleUnavailableException(
    val ruleId: UUID,
) : RuntimeException("자동화 룰을 사용할 수 없습니다: $ruleId")
