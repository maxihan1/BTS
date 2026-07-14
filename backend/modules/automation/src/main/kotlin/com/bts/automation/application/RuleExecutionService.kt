// 자동화 룰 실행 이력(RuleExecution) 조회 서비스 — 룰별 목록 + 단건 trace, MANAGE_AUTOMATION 가드 (FR-AT-05 Task 4)

package com.bts.automation.application

import com.bts.automation.adapter.RuleExecutionRepository
import com.bts.shared.permission.AutomationPermissionResolver
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * 자동화 룰 실행 이력([RuleExecution]) 조회 유스케이스 서비스 (FR-AT-05 Task 4).
 *
 * 조회 전용이라 두 public 메서드 모두 `@Transactional(readOnly = true)` 다. replay(POST, 재실행) 는 이
 * 서비스 범위가 아니다(Task 5).
 *
 * ## 두 조회의 권한 판정 축이 다르다
 * [listByRule] 은 URL 경로의 [projectKey] 를 그대로 [permissionResolver] 에 넘겨 MANAGE_AUTOMATION 을
 * 판정한다(룰 존재 여부는 확인하지 않는다 — 소프트/하드 삭제된 룰의 이력도 감사 목적으로 조회 가능해야
 * 한다, NFR-4 · [RuleExecutionRepository] 클래스 KDoc "룰 테이블 조인 없음" 참고). [getById] 는 경로에
 * `projectKey` 가 없는 전역 엔드포인트(`/api/v1/automation/executions/{id}`)라 **레코드를 먼저 읽어**
 * `record.projectKey` 로 권한을 판정한다 — 그래서 미존재와 타 프로젝트(권한 없음) 두 경우 모두
 * [RuleExecutionNotFoundException](404)으로 수렴시켜 존재를 숨긴다(다른 프로젝트 소속 실행 id 를 안다고
 * 해서 "권한 없음(403)"으로 그 존재를 확인시켜 주지 않는다).
 *
 * @param repository [RuleExecution] 영속 어댑터.
 * @param permissionResolver MANAGE_AUTOMATION 권한 평가 cross-BC 포트(fail-closed, non-null 주입 —
 *   [[crossbc-resolver-nullable-fail-open]] 회귀 방지, [AutomationRuleService] 동일 관례).
 */
@Service
class RuleExecutionService(
    private val repository: RuleExecutionRepository,
    private val permissionResolver: AutomationPermissionResolver,
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
        val execution = repository.findById(executionId) ?: throw RuleExecutionNotFoundException(executionId)
        if (!permissionResolver.hasManageAutomation(actorId, execution.projectKey)) {
            throw RuleExecutionNotFoundException(executionId)
        }
        log.debug("rule_execution_get_by_id actor={} executionId={}", actorId, executionId)
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
 * 요청한 [executionId] 에 해당하는 실행 이력이 없거나(또는 있어도 요청자가 그 소속 프로젝트에서
 * MANAGE_AUTOMATION 권한이 없어 존재를 숨겨야) 함을 나타낸다. 웹 레이어에서 404 로 매핑된다.
 *
 * @property executionId 조회를 시도한 실행 이력 id(호출자가 이미 URL 로 알고 있는 값이라 응답에
 *   노출해도 누출이 아니다, [com.bts.automation.application.AutomationRuleNotFoundException] 동일 관례).
 */
class RuleExecutionNotFoundException(
    val executionId: UUID,
) : RuntimeException("실행 이력을 찾을 수 없습니다: $executionId")
