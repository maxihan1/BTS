// MFA 강제 등록 필요 여부 판정 — 게이트/JWT 클레임/whoami 공통 단일 출처 (FR-MF-04 Task 4)

package com.atlas.bts.identity.mfa

import com.atlas.bts.identity.project.ProjectMembershipRepository
import com.bts.shared.permission.SensitiveProjectResolver
import com.bts.shared.permission.SystemPermissionResolver
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * MFA(2FA) 강제 등록 필요 여부를 판정하는 정책 서비스 (FR-MF-04). SDD §19.7.2.
 *
 * ## 단일 출처(single source of truth)
 * 이후 게이트 필터·access JWT 클레임·whoami 응답이 모두 이 서비스의 [evaluate] 한 곳을 거친다.
 * 평가 규칙이 여러 곳에 흩어지면 한 곳만 바뀌어 drift(예: JWT 는 강제인데 게이트는 통과)가 생기므로,
 * 강제 대상 판정을 이 클래스로 단일화해 차단한다.
 *
 * ## 판정 규칙
 * ```
 * mfaRequired(uid)           = isSystemAdmin(uid) OR sensitiveResolver.anyRequiresMfa(멤버십 프로젝트들)
 * mfaEnrollmentRequired(uid) = mfaRequired(uid) AND NOT mfaService.isEnabled(uid)
 * ```
 * - 관리자(SYSTEM_ADMIN)는 무조건 강제 대상이다.
 * - 민감 프로젝트(require_2fa=true) 멤버도 강제 대상이다. 멤버십 조회는 in-BC,
 *   '민감 표시' 조회만 cross-BC([SensitiveProjectResolver] 포트 경유, BC 격리)다.
 * - 이미 MFA 를 등록(TOTP ACTIVE)한 사용자는 강제 대상이라도 등록이 끝났으므로 false(EC9).
 *
 * ## 관리자 short-circuit
 * 관리자는 멤버십·민감 프로젝트 조회 없이 단락한다 — 불필요한 in-BC 쿼리와 cross-BC 호출을 줄인다.
 *
 * ## fail-safe (fail-open 금지)
 * [sensitiveResolver] 는 prod 에서 issue-tracking 의 실제 adapter 를 assembled 로 주입받는다.
 * prod 에 실 빈이 없으면 Spring DI 가 부팅 시 loud 하게 실패하도록 둔다(silent 우회 금지).
 * non-prod 단독 부팅 가용성은 [NonProdSensitiveProjectResolver]([SensitiveProjectResolver]
 * `@Profile("!prod")` fallback, 항상 false)가 담당한다. 즉, '민감 여부 불명'은 코드 경로상
 * 발생하지 않으며(빈은 항상 존재), prod 누락은 부팅 차단으로 수렴한다
 * (learnings: crossbc-resolver-nullable-fail-open / profile-scoped-bean-boot-failure).
 *
 * ## 로그인 가용성
 * [evaluate] 는 발급·요청 경로에서 호출되므로 정상 흐름에서 예외를 던지지 않는다
 * (resolver 들이 정상 입력에 대해 boolean 만 반환). 정책 평가가 로그인을 막지 않게 한다.
 *
 * @see SensitiveProjectResolver
 * @see SystemPermissionResolver
 * @see MfaService
 */
@Service
class MfaEnforcementPolicy(
    private val systemPermissionResolver: SystemPermissionResolver,
    private val sensitiveResolver: SensitiveProjectResolver,
    private val membershipRepo: ProjectMembershipRepository,
    private val mfaService: MfaService,
) {
    /**
     * 사용자가 MFA 등록을 강제받아야 하는지(=강제 대상이지만 아직 미설정) 판정한다.
     *
     * @param userId 평가 대상 사용자.
     * @return 강제 대상([mfaRequired]) AND 미설정이면 `true`, 그 외 `false`.
     */
    fun evaluate(userId: UUID): Boolean = mfaRequired(userId) && !mfaService.isEnabled(userId)

    /**
     * 사용자가 MFA 강제 대상인지 판정한다 — 관리자거나 민감 프로젝트 멤버.
     *
     * 관리자면 멤버십·민감 프로젝트 조회 없이 단락한다.
     */
    private fun mfaRequired(userId: UUID): Boolean =
        systemPermissionResolver.isSystemAdmin(userId) ||
            sensitiveResolver.anyRequiresMfa(membershipRepo.listProjectIdsByUser(userId).toSet())
}
