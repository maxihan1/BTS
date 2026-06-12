// SensitiveProjectResolver fallback 등록 — 실 adapter 부재 시에만 NonProd fallback 빈 제공 (FR-MF-04)

package com.atlas.bts.identity.mfa

import com.bts.shared.permission.SensitiveProjectResolver
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * [SensitiveProjectResolver] fallback 등록 설정 (FR-MF-04).
 *
 * `@ConditionalOnMissingBean(SensitiveProjectResolver::class)` 으로 **실 adapter 가 부재할 때만**
 * [NonProdSensitiveProjectResolver] 를 빈으로 등록한다. assembled 부팅에서는 issue-tracking 의
 * 실 adapter([com.bts.issue.project.adapter.IssueTrackingSensitiveProjectResolver])가 존재하므로
 * 이 `@Bean` 은 등록되지 않는다(assembled-first). identity-access 단독 부팅(프로파일 무관)에서는
 * 실 빈이 부재하므로 fallback 이 등록되어 부팅 가용성을 확보한다.
 *
 * ## `@Component` 가 아닌 `@Bean` 인 이유
 * `@ConditionalOnMissingBean` 은 컴포넌트 스캔된 `@Component` 에서는 빈 등록 순서에 의존해
 * 신뢰성 있게 동작하지 않는다(Spring Boot 문서가 경고하는 함정 — 단독 부팅에서도 fallback 이
 * 등록되지 않아 빈 미해소가 발생했다). `@Configuration` 의 `@Bean` 메서드에서 평가하면 다른 빈
 * 정의가 등록된 뒤에 조건이 판정되어 의도대로 동작한다.
 *
 * @see NonProdSensitiveProjectResolver
 * @see SensitiveProjectResolver
 */
@Configuration
class SensitiveProjectResolverFallbackConfig {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 실 [SensitiveProjectResolver] adapter 가 컨텍스트에 없을 때만 fallback 을 등록한다.
     *
     * 등록(=fallback 활성)되면 시작 시 한 번 WARN 으로 misassembled 를 관측 가능하게 알린다.
     * 토큰/PII 를 포함하지 않는 정적 메시지만 출력한다.
     *
     * @return [NonProdSensitiveProjectResolver] (항상 false, 안전 기본).
     */
    @Bean
    @ConditionalOnMissingBean(SensitiveProjectResolver::class)
    fun nonProdSensitiveProjectResolver(): SensitiveProjectResolver {
        log.warn(
            "SensitiveProjectResolver 실 adapter 부재 — fallback 활성, 민감 프로젝트 MFA 강제 비활성. " +
                "assembled 부팅이 아니면 정상.",
        )
        return NonProdSensitiveProjectResolver()
    }
}
