// JsonNullable<T> 직렬화 모듈을 Spring Jackson 컨텍스트에 등록하는 설정 (FR-PM-06 PR-B)

package com.bts.issue.adapter.inbound.rest

import org.openapitools.jackson.nullable.JsonNullableModule
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * `jackson-databind-nullable` 의 [JsonNullableModule] 을 Spring 컨텍스트에 Bean 으로 등록한다.
 *
 * Spring Boot 의 JacksonAutoConfiguration 이 컨텍스트의 모든 `com.fasterxml.jackson.databind.Module`
 * Bean 을 ObjectMapper 에 자동 등록하므로, 이 Bean 으로 [JsonNullable] 의
 * presence(undefined vs null) 역직렬화가 활성화된다.
 *
 * FR-PM-06 PR-B — [UpdateIssueRequest.securityLevelId] 의 3-state(무변경/해제/지정) 구분에 필요하다.
 * 이 모듈이 없으면 Jackson 이 JsonNullable 의 부재와 명시 null 을 동일하게 취급해 3-state 가 깨진다.
 */
@Configuration
open class JacksonNullableConfiguration {
    /**
     * [JsonNullableModule] 을 Bean 으로 노출한다.
     *
     * @return Jackson 에 등록할 JsonNullable 직렬화 모듈.
     */
    @Bean
    open fun jsonNullableModule(): JsonNullableModule = JsonNullableModule()
}
