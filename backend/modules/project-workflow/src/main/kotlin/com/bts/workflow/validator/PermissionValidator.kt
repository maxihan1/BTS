// 권한 Validator — PermissionResolver 호출 결과로 전이 가능 여부 판정

package com.bts.workflow.validator

import com.bts.workflow.domain.dto.TransitionContext
import com.bts.workflow.domain.spi.ValidatorResult
import com.bts.workflow.domain.spi.WorkflowValidator
import com.bts.workflow.port.outbound.ActorId
import com.bts.workflow.port.outbound.PermissionResolver
import com.bts.workflow.port.outbound.Scope

/**
 * Validator config 에서 scope 를 지정할 때 사용하는 열거형.
 *
 * - [ISSUE] — 이슈 단위 권한 평가. Scope.Issue(issueKey) 로 resolver 호출 (기본값).
 * - [PROJECT] — 프로젝트 단위 권한 평가. Scope.Project(workflowKey) 로 resolver 호출.
 *
 * YAML 워크플로우 정의의 `validators[].config.scope` 값과 매핑된다.
 */
enum class ValidatorScope {
    ISSUE,
    PROJECT,
}

/**
 * 권한 기반 전이 허용 여부를 판정하는 [WorkflowValidator] 구현체.
 *
 * [PermissionResolver] 를 통해 액터(요청자)가 지정된 권한을 보유하는지 확인한다.
 * resolver 가 false 를 반환하거나 예외를 던지면 전이를 차단한다 — 보안 우선 원칙.
 *
 * ## Scope 결정 규칙
 * - [ValidatorScope.ISSUE] (기본값). `Scope.Issue(ctx.request.issueKey)` 로 호출. 이슈별 정밀 권한.
 * - [ValidatorScope.PROJECT]. `Scope.Project(ctx.request.workflowKey)` 로 호출. 프로젝트 단위 권한.
 *
 * ## 보안 계약
 * - [actorId] 는 raw String 을 직접 받지 않고 [ActorId] value class 로 래핑해 빈 값을 생성 시점에 차단한다.
 * - resolver 예외는 `Fail("permission resolver error")` 로 변환 — 예외 무시(silent swallow) 금지.
 * - resolver false 반환 시 `ValidatorResult.Fail(null, "permission denied: \$permission")` 반환.
 *
 * 참조. FR-WF-01, docs/sdd §12-permissions, identity-access.md
 *
 * @param resolver 권한 평가 outbound port. 구현체는 identity-access BC 어댑터가 제공한다.
 * @param permission 검사할 권한 문자열. 예: "TRANSITION_ISSUE", "ADMIN_WORKFLOW".
 * @param scope 권한 평가 범위. 기본값 [ValidatorScope.ISSUE].
 */
class PermissionValidator(
    private val resolver: PermissionResolver,
    private val permission: String,
    private val scope: ValidatorScope = ValidatorScope.ISSUE,
) : WorkflowValidator {
    override val type: String = "permission-check"

    /**
     * 전이 요청자가 [permission] 을 보유하는지 [resolver] 에 위임해 판정한다.
     *
     * - resolver 가 true → [ValidatorResult.Pass]
     * - resolver 가 false → [ValidatorResult.Fail] (field = null, reason = "permission denied: {permission}")
     * - resolver 가 예외 → [ValidatorResult.Fail] (field = null, reason = "permission resolver error")
     *
     * @param ctx 전이 컨텍스트. actorId 와 scope 키를 여기서 추출한다.
     * @return [ValidatorResult.Pass] 또는 [ValidatorResult.Fail].
     *
     * ### Exception 포착 근거
     * [PermissionResolver] 구현체는 외부 BC(identity-access) 가 제공한다.
     * 어떤 예외를 던질지 이 BC 에서 알 수 없으므로 Exception 전체를 포착해 보안 우선 원칙을 지킨다.
     */
    @Suppress("TooGenericExceptionCaught")
    override fun validate(ctx: TransitionContext): ValidatorResult {
        val actorId = ActorId(ctx.request.actorId)
        val resolvedScope =
            when (scope) {
                ValidatorScope.ISSUE -> Scope.Issue(ctx.request.issueKey)
                ValidatorScope.PROJECT -> Scope.Project(ctx.request.workflowKey)
            }

        return try {
            val allowed = resolver.hasPermission(actorId, permission, resolvedScope)
            if (allowed) {
                ValidatorResult.Pass
            } else {
                ValidatorResult.Fail(field = null, reason = "permission denied: $permission")
            }
        } catch (ex: Exception) {
            // resolver 예외는 보안 우선 원칙에 따라 전이를 차단한다.
            ValidatorResult.Fail(field = null, reason = "permission resolver error: ${ex.message}")
        }
    }
}
