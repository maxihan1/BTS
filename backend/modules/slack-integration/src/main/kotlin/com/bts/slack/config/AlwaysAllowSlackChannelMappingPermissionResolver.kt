// Slack 채널 매핑 관리 권한 non-prod fail-safe 스텁 — 항상 허용, @Profile("!prod") 로 운영 차단 (FR-SL-06 Task 5)

package com.bts.slack.config

import com.bts.shared.permission.SlackChannelMappingPermissionResolver
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Slack 채널 매핑 관리 권한 평가 non-prod 스텁.
 *
 * ## 왜 항상 true 인가 (dev/test 편의)
 * 이 구현체는 [hasManageChannelMapping] 이 actor·projectKey 와 무관하게 항상 `true` 를 반환한다.
 * 개발/테스트/스테이징에서 실제 RBAC(identity-access) 배선 없이 채널 매핑 CRUD 를 검증할 수 있게 하기
 * 위함이다. `@Profile("!prod")` 로 운영(prod) profile 에서 Bean 등록 자체가 차단되므로, 이 클래스는
 * 운영 환경에서 **절대 로드되지 않는다**.
 *
 * ## fail-open 이 아니다 — prod 은 fail-closed 실판정
 * "항상 허용" 은 non-prod 국한이다. prod 에서는 identity-access 가
 * [com.atlas.bts.identity.permission.IdentityAccessSlackChannelMappingPermissionResolver]
 * (`@Component @Profile("prod")`, FR-SL-06 Task 8, PROJECT_ADMIN 직접 확인)로 실판정하며,
 * 미해석 프로젝트 키·비멤버·비관리자는 모두 `false`(거부)로 수렴한다.
 * prod 에 이 스텁이 없고 어댑터도 부재하면 [SlackChannelMappingPermissionResolver] Bean 이
 * 미해소 상태로 남아 Spring 이 `BeanCreationException` 으로 부팅을 차단한다(fail-closed 안전망).
 * 두 Bean 은 profile 로 상호 배타적(mutually exclusive)이다.
 * - `AlwaysAllowSlackChannelMappingPermissionResolver` — `@Profile("!prod")` (개발/테스트/스테이징)
 * - `IdentityAccessSlackChannelMappingPermissionResolver` — `@Profile("prod")` (운영)
 *
 * ## consumer-owns-stub (소비 모듈 소유)
 * 이 스텁은 소비 BC 인 slack-integration 이 자신의 컨텍스트에 제공한다.
 * shared-kernel 포트 [SlackChannelMappingPermissionResolver] 의 prod 어댑터는 identity-access 소유이며,
 * BC 격리(ArchUnit)상 identity-access 가 slack-integration 을 import 할 수 없어 non-prod fail-safe 를
 * 소비자 쪽에 둔다. project-workflow 의 `AlwaysAllowWorkflowSchemePermissionResolver` 및 automation 의
 * 소비자 스텁과 동형이다.
 *
 * ## ArchUnit 강제
 * slack service/controller 계층은 [SlackChannelMappingPermissionResolver] interface 만 의존한다.
 * 이 구현체를 직접 import 하면 빌드 실패.
 *
 * @see SlackChannelMappingPermissionResolver
 * @see com.bts.shared.permission.WorkflowSchemePermissionResolver
 */
@Component
@Profile("!prod")
class AlwaysAllowSlackChannelMappingPermissionResolver : SlackChannelMappingPermissionResolver {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 항상 `true` 를 반환한다 (거부하지 않는다).
     *
     * 개발/테스트/스테이징에서 권한 검사 없이 채널 매핑 CRUD 를 검증할 수 있도록 모든 요청을 허용한다.
     * 운영 환경에서는 이 메서드가 절대 호출되지 않는다(`@Profile("!prod")`).
     *
     * 매 호출마다 WARN 로그를 남겨 비-prod 환경에서 권한 우회 빈도를 관측할 수 있게 한다.
     * [actorId](UUID — PII 아님)와 [projectKey](프로젝트 키 — PII 아님)만 기록한다.
     *
     * @param actorId 권한 평가 대상 행위자 UUID.
     * @param projectKey 채널 매핑이 속한 프로젝트의 키(예. "ATLAS").
     * @return 항상 `true`(non-prod 편의). prod 실판정은 identity-access 어댑터(Task 8)가 담당한다.
     */
    override fun hasManageChannelMapping(
        actorId: UUID,
        projectKey: String,
    ): Boolean {
        log.warn(
            "AlwaysAllow Slack 채널 매핑 권한 stub 사용 중: actor={} projectKey={} — non-prod fail-safe. " +
                "prod 은 identity-access 어댑터가 실판정(FR-SL-06 Task 8)",
            actorId,
            projectKey,
        )
        return true
    }
}
