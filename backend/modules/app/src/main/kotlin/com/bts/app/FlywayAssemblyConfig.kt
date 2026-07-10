// 조립 앱 Flyway 다중 이력 마이그레이션 — BC 별로 독립 이력 테이블에 마이그레이션을 실행해 V번호 충돌 회피

package com.bts.app

import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.InitializingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import javax.sql.DataSource

/**
 * 8개 BC 를 하나의 DB(public 스키마)로 조립할 때, identity-access(V001~V033)와 issue-tracking(V001~V035)의
 * Flyway 버전 번호가 충돌한다(둘 다 V001 부터). 나머지 BC 는 대역 분리(V200/V400/V500/V600/V700)라 안전하다.
 *
 * Spring Boot 의 단일 Flyway auto-config(`spring.flyway.enabled: false` 로 비활성)를 대신해,
 * 이 설정이 **BC 마다 독립 이력 테이블**(`flyway_history_<bc>`)에 자기 location 만 마이그레이션한다.
 * 이력 테이블이 분리되므로 동일 V번호가 있어도 충돌하지 않는다.
 *
 * 실행 순서는 cross-BC FK 의존을 고려한다: project-workflow(V202)가 issue-tracking 의 projects 를 FK 참조하므로
 * issue 를 workflow 보다 먼저 실행한다.
 */
@Configuration
class FlywayAssemblyConfig {
    private val log = LoggerFactory.getLogger(FlywayAssemblyConfig::class.java)

    /** (이력 테이블 접미사, classpath location) — 위에서 아래로 순차 마이그레이션 */
    private val modules =
        listOf(
            "identity" to "classpath:db/migration/identity-access",
            "issue" to "classpath:db/migration/issue-tracking",
            "workflow" to "classpath:db/migration/project-workflow",
            "notification" to "classpath:db/migration/notification",
            "agile" to "classpath:db/migration/agile-planning",
            "search" to "classpath:db/migration/search-export-import",
            "slack" to "classpath:db/migration/slack-integration",
        )

    /**
     * 컨텍스트 초기화 시점에 전 모듈 마이그레이션을 실행한다. DB 를 읽는 다른 빈보다 먼저 완료돼야 하므로
     * [InitializingBean.afterPropertiesSet] 에서 즉시 수행한다(startup 시 DB 를 조회하는 빈은 `@DependsOn`
     * 으로 이 빈에 의존시킨다).
     */
    @Bean
    fun assemblyFlywayMigrator(dataSource: DataSource): InitializingBean =
        InitializingBean {
            modules.forEach { (name, location) ->
                val result =
                    Flyway.configure()
                        .dataSource(dataSource)
                        .locations(location)
                        .table("flyway_history_$name")
                        .baselineOnMigrate(true)
                        // ${...} 를 Flyway 변수 치환으로 오인하지 않도록 비활성 (모듈 dev yml 과 동일).
                        .placeholders(emptyMap())
                        .placeholderReplacement(false)
                        .load()
                        .migrate()
                log.info("Flyway[{}] 마이그레이션 적용 {}건 (location={})", name, result.migrationsExecuted, location)
            }
        }
}
