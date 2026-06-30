// issue-tracking 모듈 OpenAPI 3.1 전역 설정 — springdoc + bearerAuth JWT 보안 스킴 정의

package com.bts.issue.config

import io.swagger.v3.oas.annotations.OpenAPIDefinition
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType
import io.swagger.v3.oas.annotations.info.Info
import io.swagger.v3.oas.annotations.security.SecurityScheme
import org.springframework.context.annotation.Configuration

/** API 제목 — Swagger UI 헤더 및 OpenAPI info.title 에 표시 */
private const val API_TITLE = "Atlas Issues API"

/** API 버전 — OpenAPI info.version 에 표시 */
private const val API_VERSION = "1.0"

/** API 설명 — OpenAPI info.description 에 표시 */
private const val API_DESCRIPTION =
    "Atlas Issues 이슈 트래커 REST API. " +
        "cursor 기반 페이지네이션(FR-API-01)과 JWT Bearer 인증을 지원한다. " +
        "인증이 필요한 모든 엔드포인트는 Authorization: Bearer <token> 헤더를 요구한다."

/**
 * bearerAuth 보안 스킴 이름 — @SecurityScheme(name=...) 과 @SecurityRequirement(name=...) 에서 공유.
 * Task 7 에서 개별 엔드포인트에 @SecurityRequirement(name = BEARER_AUTH_SCHEME) 로 참조한다.
 */
const val BEARER_AUTH_SCHEME = "bearerAuth"

/**
 * issue-tracking 모듈 OpenAPI 3.1 전역 설정.
 *
 * ## 역할
 * - [OpenAPIDefinition]: API 제목/버전/설명을 `/v3/api-docs` JSON 에 전역 반영
 * - [SecurityScheme] `bearerAuth`: JWT Bearer 인증 스킴을 전역 등록.
 *   Swagger UI 의 "Authorize" 버튼으로 JWT 토큰을 주입하면 인증이 필요한 엔드포인트를 탐색 가능
 *
 * ## 왜 `@Configuration` 에 직접 annotation 을 붙이는가
 * springdoc 은 `@OpenAPIDefinition` 과 `@SecurityScheme` 을 Spring 빈 스캔 시점에 읽는다.
 * 별도 클래스에 붙여도 되지만 `@Configuration` 클래스에 함께 선언하는 것이 단일 진실 출처를 유지한다.
 *
 * ## 관련 설정
 * - `application.yml`: `springdoc.api-docs.version=OPENAPI_3_1` (OpenAPI 3.1 명시)
 * - [OpenApiSecurityConfig]: `/v3/api-docs`·`/swagger-ui` 경로를 인증 없이 접근 가능하게 허용
 *
 * @see <a href="https://springdoc.org/#features">springdoc 공식 문서</a>
 */
@Configuration(proxyBeanMethods = false)
@OpenAPIDefinition(
    info =
        Info(
            title = API_TITLE,
            version = API_VERSION,
            description = API_DESCRIPTION,
        ),
)
@SecurityScheme(
    name = BEARER_AUTH_SCHEME,
    type = SecuritySchemeType.HTTP,
    scheme = "bearer",
    bearerFormat = "JWT",
)
class OpenApiConfig
