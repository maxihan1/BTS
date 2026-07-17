// POST /api/v1/projects 요청 바디 DTO — Jakarta Validation (key 정규식·name) (FR-PJ-01 Task 7)

package com.bts.issue.project.web.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

/** 프로젝트 key 형식 — 대문자로 시작, 대문자+숫자 2~10자. DB CHECK(`projects_key_check`)와 정합. */
const val PROJECT_KEY_REGEX = "^[A-Z][A-Z0-9]{1,9}$"

/** 프로젝트 이름 최대 길이. */
private const val PROJECT_NAME_MAX = 255

/**
 * 프로젝트 생성 REST 요청 바디 (FR-PJ-01).
 *
 * Jakarta Bean Validation 으로 입력값을 검증한다. [key] 정규식([PROJECT_KEY_REGEX])은 DB CHECK
 * 제약(`projects_key_check`)과 정합하며, 위반 시 컨트롤러 메서드 본문 진입 전 400 으로 거부한다(PJ1-6).
 * `@field:Pattern` 은 null 을 통과시키므로 `@field:NotBlank` 로 null/blank 를 함께 막는다.
 *
 * @property key 프로젝트 식별 접두사. 대문자로 시작, 대문자+숫자 2~10자.
 * @property name 프로젝트 이름. 공백 불가, 255자 이하.
 */
data class CreateProjectRequest(
    @field:NotBlank(message = "key는 비어 있을 수 없습니다.")
    @field:Pattern(
        regexp = PROJECT_KEY_REGEX,
        message = "key는 대문자로 시작하는 대문자+숫자 2~10자여야 합니다.",
    )
    val key: String,
    @field:NotBlank(message = "name은 비어 있을 수 없습니다.")
    @field:Size(max = PROJECT_NAME_MAX, message = "name은 255자 이하이어야 합니다.")
    val name: String,
)
