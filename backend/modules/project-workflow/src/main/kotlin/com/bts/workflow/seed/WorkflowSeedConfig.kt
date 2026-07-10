// 워크플로우 시드용 YAML ObjectMapper 빈 — 조립 컨텍스트에서 YamlSeedService 가 JSON @Primary 대신 YAML 매퍼를 받도록 명시 제공

package com.bts.workflow.seed

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * [YamlSeedService] 는 classpath:workflows 아래 워크플로우 YAML 을 역직렬화하려면 YAMLFactory 기반 ObjectMapper 가 필요하다.
 *
 * project-workflow 는 라이브러리 모듈이라 그동안 어떤 배포 앱도 `com.bts.workflow` 를 컴포넌트 스캔하지 않아
 * [YamlSeedService] 가 운영에서 인스턴스화된 적이 없었고, 이 YAML 매퍼 빈의 부재가 드러나지 않았다.
 * 배포 조립 앱이 처음으로 이 서비스를 결선하면서, 주입 대상이 Spring Boot 의 @Primary JSON ObjectMapper 로
 * 잘못 해석돼 YAML 을 JSON 으로 파싱하다 실패했다. 이 설정이 전용 YAML 매퍼를 `@Qualifier` 이름으로 제공한다.
 */
@Configuration
class WorkflowSeedConfig {
    @Bean
    fun workflowYamlObjectMapper(): ObjectMapper = ObjectMapper(YAMLFactory()).registerKotlinModule()
}
