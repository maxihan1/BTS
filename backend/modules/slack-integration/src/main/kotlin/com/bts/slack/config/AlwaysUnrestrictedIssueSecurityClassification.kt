// 이슈 보안등급 판정 non-prod fail-safe 스텁 — 항상 미제한(false), @Profile("!prod") 로 운영 차단 (FR-SL-06 PR-B Task 2)

package com.bts.slack.config

import com.bts.shared.issue.IssueSecurityClassificationPort
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/**
 * 이슈 보안등급 판정 non-prod 스텁.
 *
 * ## 왜 항상 false 인가 (dev/test 편의)
 * 이 구현체는 [isSecurityRestricted] 가 issueKey 와 무관하게 항상 `false`(제한 없음 → 게시 허용)를
 * 반환한다. 비-prod(dev/test) 환경에는 이슈 보안등급 디렉토리(issue-tracking)의 실 배선이 없어
 * "이 이슈가 보안등급으로 제한되어 있는가"를 실제로 판정할 방법이 없다. 이 스텁이 없으면 slack
 * 모듈이 비-prod 에서 부팅 자체에 실패한다(포트 Bean 미해소 → `BeanCreationException`).
 * `@Profile("!prod")` 로 운영(prod) profile 에서 Bean 등록 자체가 차단되므로, 이 클래스는 운영
 * 환경에서 **절대 로드되지 않는다**.
 *
 * ## fail-open 이 아니다 — prod 은 fail-closed 실판정
 * "항상 미제한(false)" 은 non-prod 국한이다. prod 에서는 issue-tracking 이 실 어댑터
 * (`@Profile("prod")`, [IssueSecurityClassificationPort] KDoc 참고)로 실판정하며, 이슈가 실제로
 * 보안등급이 설정되어 있거나 조회 자체가 불가(미존재·소프트 삭제)하면 모두 `true`(제한)로 수렴한다.
 * prod 에 이 스텁이 없고 어댑터도 부재하면 [IssueSecurityClassificationPort] Bean 이 미해소 상태로
 * 남아 Spring 이 `BeanCreationException` 으로 부팅을 차단한다(fail-closed 안전망). 두 Bean 은
 * profile 로 상호 배타적(mutually exclusive)이다.
 * - `AlwaysUnrestrictedIssueSecurityClassification` — `@Profile("!prod")` (개발/테스트/스테이징)
 * - issue-tracking 의 prod 어댑터 — `@Profile("prod")` (운영)
 *
 * ## consumer-owns-stub (소비 모듈 소유)
 * 이 스텁은 소비 BC 인 slack-integration 이 자신의 컨텍스트에 제공한다.
 * shared-kernel 포트 [IssueSecurityClassificationPort] 의 prod 어댑터는 issue-tracking 소유이며,
 * BC 격리(ArchUnit)상 issue-tracking 이 slack-integration 을 import 할 수 없어 non-prod fail-safe 를
 * 소비자 쪽에 둔다. 같은 패키지의 [AlwaysAllowSlackChannelMappingPermissionResolver] 와 동형이다.
 *
 * @see IssueSecurityClassificationPort
 * @see AlwaysAllowSlackChannelMappingPermissionResolver
 */
@Component
@Profile("!prod")
class AlwaysUnrestrictedIssueSecurityClassification : IssueSecurityClassificationPort {
    /**
     * 항상 `false`(제한 없음)를 반환한다.
     *
     * 비-prod 환경에서는 보안등급 디렉토리가 없어 판정 자체가 불가능하므로, 채널 브로드캐스트
     * 개발/테스트 흐름이 막히지 않도록 무조건 게시를 허용한다. 운영 환경에서는 이 메서드가
     * 절대 호출되지 않는다(`@Profile("!prod")`).
     *
     * @param issueKey 판정 대상 이슈 키 문자열(비-prod 스텁에서는 사용하지 않는다).
     * @return 항상 `false`(non-prod 편의). prod 실판정은 issue-tracking 어댑터가 담당한다.
     */
    override fun isSecurityRestricted(issueKey: String): Boolean = false
}
