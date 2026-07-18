// UpdateProjectRequest DTO Jakarta Bean Validation 단위 테스트 (name NotBlank·Size max=255)

package com.bts.issue.project.web.dto

import jakarta.validation.Validation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [UpdateProjectRequest] 의 name 필드 Bean Validation 규칙을 검증한다 (§4.2 (a)안).
 *
 * lead/require_2fa 는 별도 엔드포인트 책임이라 이 DTO 는 name 하나만 다룬다.
 */
class UpdateProjectRequestTest {
    private val validator = Validation.buildDefaultValidatorFactory().validator

    @Test
    fun `빈 name은 NotBlank 위반이다`() {
        val request = UpdateProjectRequest(name = "")

        val violations = validator.validate(request)

        assertTrue(violations.isNotEmpty())
    }

    @Test
    fun `256자 name은 Size 위반이다`() {
        val request = UpdateProjectRequest(name = "a".repeat(256))

        val violations = validator.validate(request)

        assertTrue(violations.isNotEmpty())
    }

    @Test
    fun `정상 name은 위반이 없다`() {
        val request = UpdateProjectRequest(name = "My Project")

        val violations = validator.validate(request)

        assertEquals(0, violations.size)
    }
}
