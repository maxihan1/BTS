// Import 첨부 zip multipart 상한 프로그래매틱 설정(부팅 조립 시 재조정 필요, FR-IM-01 PR4 C1)

package com.bts.search.imports.config

import jakarta.servlet.MultipartConfigElement
import org.springframework.boot.web.servlet.MultipartConfigFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.util.unit.DataSize

/** 개별 multipart part 상한(MB) — 두 part(매니페스트/첨부 zip) 중 더 큰 zip(500MB) 기준. */
private const val MAX_FILE_SIZE_MB: Long = 500

/** 전체 요청 상한(MB) — 매니페스트(최대 50MB) + 첨부 zip(최대 500MB) 합(최대 550MB)보다 여유 있게 600MB. */
private const val MAX_REQUEST_SIZE_MB: Long = 600

/** 개별 multipart part 상한 — [MAX_FILE_SIZE_MB] 참조. */
private val MAX_FILE_SIZE: DataSize = DataSize.ofMegabytes(MAX_FILE_SIZE_MB)

/** 전체 요청 상한 — [MAX_REQUEST_SIZE_MB] 참조. */
private val MAX_REQUEST_SIZE: DataSize = DataSize.ofMegabytes(MAX_REQUEST_SIZE_MB)

/**
 * `POST /api/v1/imports` 의 multipart 상한을 프로그래매틱 `MultipartConfigElement` 빈으로 설정한다.
 *
 * ### 왜 yml 이 아니라 @Bean 인가
 *
 * `MultipartConfigElement` 빈은 Spring Boot `MultipartAutoConfiguration` 이
 * `@ConditionalOnMissingBean` 으로 조건화하므로, yml(`spring.servlet.multipart.*`) 기반 자동
 * 설정을 **classpath 순서에 무관하게 결정적으로 override** 한다. 또한 yml과 달리 부팅 앱 없이도
 * 순수 단위테스트([com.bts.search.imports.config.ImportMultipartConfigurationTest])로 상한 값을
 * 직접 어서트할 수 있다.
 *
 * 기존에는 module-local `application-dev.yml`/`application-test.yml` 에 동일 값을 뒀으나,
 * search-export-import 는 `@SpringBootApplication` 부팅 앱이 없는 라이브러리 모듈이라 이 yml을
 * 로드하는 부팅 앱이 전무했고(교훈 no-cross-bc-deployment-assembly), 동명 `application-dev.yml`이
 * issue-tracking(100MB)·identity-access(미설정) 등 다른 모듈에도 존재해 Spring이
 * `classpath:/application-dev.yml`을 단일 리소스로 해석하는 특성상 향후 배포 조립 시 승자가
 * 비결정적이었다. 이 문제를 이 @Bean 이 해소한다.
 *
 * ### 정직한 한계
 *
 * search-export-import 는 여전히 `@SpringBootApplication` 부팅 앱이 없는 라이브러리 모듈이다.
 * 이 빈은 **배포 조립 시 실제로 이 모듈을 포함하는 부팅 앱의 ApplicationContext 에 등록되어야만**
 * 효력이 있다 — end-to-end 실효성은 그 조립 시점에만 검증 가능하다.
 *
 * **경고**. 전역 multipart 상한은 모듈 간 공유 자원이다. issue-tracking 은 첨부파일 상한을 100MB로
 * 별도 관리한다([com.bts.search.imports.config] 밖). 이 빈의 [MAX_FILE_SIZE]/[MAX_REQUEST_SIZE] 를
 * 조용히 올리면(예: 전역 600MB) issue-tracking 의 100MB 첨부 상한 게이트가 서블릿 레벨에서
 * 느슨해질 수 있다 — 배포 조립 시 두 모듈이 하나의 부팅 앱에 함께 실린다면 상한을 재조정하거나
 * (별도 부팅 앱 분리) part 단위 애플리케이션 레벨 검증([com.bts.search.imports.job.application.ImportJobService]
 * 등)으로 이관해야 한다.
 */
@Configuration(proxyBeanMethods = false)
class ImportMultipartConfiguration {
    @Bean
    fun multipartConfigElement(): MultipartConfigElement {
        val factory = MultipartConfigFactory()
        factory.setMaxFileSize(MAX_FILE_SIZE)
        factory.setMaxRequestSize(MAX_REQUEST_SIZE)
        return factory.createMultipartConfig()
    }
}
