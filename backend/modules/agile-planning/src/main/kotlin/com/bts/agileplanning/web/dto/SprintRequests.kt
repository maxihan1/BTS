// 스프린트 REST API 요청 DTO — agile-planning BC (FR-BL-02 Task 5)

package com.bts.agileplanning.web.dto

import jakarta.validation.constraints.NotBlank
import org.openapitools.jackson.nullable.JsonNullable
import java.time.LocalDate
import java.util.UUID

/**
 * 스프린트 생성 요청 바디.
 *
 * actor 는 SecurityContext 에서 추출한다 — body 로 받지 않는다(actor 위조 차단).
 *
 * @property projectKey 스프린트를 생성할 프로젝트 키. 공백 불가.
 * @property name 스프린트 이름. 공백 불가.
 * @property goal 스프린트 목표 설명. null 허용.
 * @property startDate 스프린트 시작일. null 이면 미지정.
 * @property endDate 스프린트 종료일. null 이면 미지정.
 * @property boardId 스프린트를 붙일 보드 UUID. null 이면 서비스가 그 프로젝트의 스크럼 보드로 폴백한다.
 */
data class CreateSprintRequest(
    @field:NotBlank
    val projectKey: String,
    @field:NotBlank
    val name: String,
    val goal: String? = null,
    val startDate: LocalDate? = null,
    val endDate: LocalDate? = null,
    val boardId: UUID? = null,
)

/**
 * 스프린트 수정 요청 바디 — partial update (3-state).
 *
 * [JsonNullable] presence 로 3-state 를 구분한다.
 * - 필드 부재(undefined, 미전송) = 무변경
 * - 명시 null = 해당 값을 null/공백으로 클리어
 * - 값 전송 = 해당 값으로 설정
 *
 * version 은 non-nullable Long 으로 선언한다. JSON 에서 누락 또는 null 을 전달하면
 * Jackson 이 역직렬화 실패([org.springframework.http.converter.HttpMessageNotReadableException])를 던져
 * [SprintExceptionHandler] 가 400 으로 처리한다.
 *
 * ### name 규칙
 * name 은 전송된 경우 공백이면 도메인 Sprint.init require 에 의해 400 을 반환한다.
 * 미전송이면 기존 name 을 유지한다.
 *
 * @property name 새 스프린트 이름. 미전송이면 기존 이름 유지. 전송 시 공백 불가 (도메인 Sprint.init require 로 검증).
 * @property goal 스프린트 목표. 미전송=무변경, null=목표 제거, 값=설정.
 * @property startDate 시작일. 미전송=무변경, null=날짜 해제, 값=설정.
 * @property endDate 종료일. 미전송=무변경, null=날짜 해제, 값=설정.
 * @property version 낙관적 잠금 버전. 필수 — 누락 시 400.
 */
data class UpdateSprintRequest(
    val name: JsonNullable<String> = JsonNullable.undefined(),
    val goal: JsonNullable<String?> = JsonNullable.undefined(),
    val startDate: JsonNullable<LocalDate?> = JsonNullable.undefined(),
    val endDate: JsonNullable<LocalDate?> = JsonNullable.undefined(),
    val version: Long,
)

/**
 * 이슈 스프린트 할당 요청 바디.
 *
 * @property issueKey 할당할 이슈 키. 예: `"BTS-1"`. 공백 불가.
 */
data class AssignIssueRequest(
    @field:NotBlank
    val issueKey: String,
)
