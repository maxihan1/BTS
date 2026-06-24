// 스프린트 REST API 요청 DTO — agile-planning BC (FR-BL-02 Task 5)

package com.bts.agileplanning.web.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.time.LocalDate

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
 */
data class CreateSprintRequest(
    @field:NotBlank
    val projectKey: String,
    @field:NotBlank
    val name: String,
    val goal: String? = null,
    val startDate: LocalDate? = null,
    val endDate: LocalDate? = null,
)

/**
 * 스프린트 수정 요청 바디.
 *
 * 모든 수정 가능 필드를 한 번에 전달한다. 필드 값 null 은 "null 로 설정"을 의미한다.
 *
 * @property name 새 스프린트 이름. 공백 불가.
 * @property goal 새 목표. null 이면 목표 없음으로 설정.
 * @property startDate 새 시작일. null 이면 미지정으로 설정.
 * @property endDate 새 종료일. null 이면 미지정으로 설정.
 * @property version 낙관적 잠금 버전. 필수.
 */
data class UpdateSprintRequest(
    @field:NotBlank
    val name: String,
    val goal: String? = null,
    val startDate: LocalDate? = null,
    val endDate: LocalDate? = null,
    @field:NotNull
    val version: Long?,
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
