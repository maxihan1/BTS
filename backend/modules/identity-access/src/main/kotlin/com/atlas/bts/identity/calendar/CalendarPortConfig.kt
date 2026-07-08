// UserCalendarLookupPort fail-safe 등록 — 실 adapter 부재 시에만 빈 결과 빈 제공 (FR-CA-01 Task 4)

package com.atlas.bts.identity.calendar

import com.bts.shared.calendar.UserCalendarLookupPort
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * [UserCalendarLookupPort] fail-safe 등록 설정 (FR-CA-01 Task 4).
 *
 * `@ConditionalOnMissingBean(UserCalendarLookupPort::class)` 으로 **실 adapter 가 부재할 때만** 인터페이스
 * default 구현(빈 결과)을 빈으로 등록한다. assembled 부팅에서는 issue-tracking 의 실 adapter
 * (`UserCalendarLookupAdapter`, Task 2)가 존재하므로 이 `@Bean` 은 등록되지 않는다(assembled-first).
 * identity-access 단독 부팅(프로파일 무관)에서는 실 빈이 부재하므로 이 fallback 이 등록되어 부팅
 * 가용성을 확보한다 — 이 빈이 없으면 [CalendarService] 가 요구하는 [UserCalendarLookupPort] 를 해소하지
 * 못해 identity-access 의 모든 full-boot 통합 테스트가 `NoSuchBeanDefinitionException` 으로 깨진다.
 *
 * ## `@Component` 가 아닌 `@Bean` 인 이유
 * `@ConditionalOnMissingBean` 은 컴포넌트 스캔된 `@Component` 에서는 빈 등록 순서에 의존해 신뢰성 있게
 * 동작하지 않는다([com.atlas.bts.identity.mfa.SensitiveProjectResolverFallbackConfig] 와 동일 원칙 —
 * `@Configuration` 의 `@Bean` 메서드에서 평가해야 다른 빈 정의가 모두 등록된 뒤 조건이 판정된다).
 *
 * @see UserCalendarLookupPort
 */
@Configuration
class CalendarPortConfig {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 실 [UserCalendarLookupPort] adapter 가 컨텍스트에 없을 때만 fallback 을 등록한다.
     *
     * 등록(=fallback 활성)되면 시작 시 한 번 WARN 으로 misassembled 를 관측 가능하게 알린다.
     *
     * @return 인터페이스 default 구현(모든 조회가 빈 페이지를 반환).
     */
    @Bean
    @ConditionalOnMissingBean(UserCalendarLookupPort::class)
    fun defaultUserCalendarLookupPort(): UserCalendarLookupPort {
        log.warn(
            "UserCalendarLookupPort 실 adapter 부재 — fallback 활성, 캘린더가 항상 빈 결과를 반환합니다. " +
                "assembled 부팅이 아니면 정상.",
        )
        return object : UserCalendarLookupPort {}
    }
}
