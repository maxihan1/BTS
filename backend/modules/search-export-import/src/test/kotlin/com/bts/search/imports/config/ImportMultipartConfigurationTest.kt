// ImportMultipartConfiguration 검증 — Import 첨부 zip multipart 상한 프로그래매틱 설정 (FR-IM-01 PR4 hotfix C1)

package com.bts.search.imports.config

import jakarta.servlet.MultipartConfigElement
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.util.unit.DataSize

/**
 * [ImportMultipartConfiguration] 검증.
 *
 * [ApplicationContextRunner](Spring Test 유틸리티 — 전체 서버 기동 없이 ApplicationContext만 띄워
 * Bean 등록/값을 검증하는 경량 도구)로 부팅 앱 없이도 `MultipartConfigElement` 빈의
 * `maxFileSize`/`maxRequestSize` 상한이 기존 module-local yml(500MB/600MB)과 동일한지 확인한다.
 * [WebhookEncryptionConfigTest][com.bts.search.webhook.config.WebhookEncryptionConfigTest] 동일 패턴.
 */
class ImportMultipartConfigurationTest {
    private val runner =
        ApplicationContextRunner()
            .withUserConfiguration(ImportMultipartConfiguration::class.java)

    @Test
    fun `MultipartConfigElement 빈의 maxFileSize는 500MB이다`() {
        runner.run { ctx ->
            assertThat(ctx).hasNotFailed()
            val bean = ctx.getBean(MultipartConfigElement::class.java)

            assertThat(bean.maxFileSize).isEqualTo(DataSize.ofMegabytes(500).toBytes())
        }
    }

    @Test
    fun `MultipartConfigElement 빈의 maxRequestSize는 600MB이다`() {
        runner.run { ctx ->
            assertThat(ctx).hasNotFailed()
            val bean = ctx.getBean(MultipartConfigElement::class.java)

            assertThat(bean.maxRequestSize).isEqualTo(DataSize.ofMegabytes(600).toBytes())
        }
    }
}
