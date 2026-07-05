// 아바타 업로드 MIME 화이트리스트 + 크기 상한 정책 단위 테스트 (컨테이너 불필요)
package com.atlas.bts.identity.profile.avatar

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class AvatarTypePolicyTest {
    @ParameterizedTest
    @ValueSource(strings = ["image/jpeg", "image/png", "image/gif", "image/webp"])
    fun `허용 이미지 MIME 는 validate 통과`(mime: String) {
        // 예외가 발생하지 않으면 통과 — assert 없이 정상 반환 확인.
        AvatarTypePolicy.validate(mime, 1_024)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "application/pdf",
            "text/html",
            "image/svg+xml",
            "application/octet-stream",
            "text/plain",
        ],
    )
    fun `허용 목록 밖 MIME 는 AvatarValidationException`(mime: String) {
        assertThatThrownBy { AvatarTypePolicy.validate(mime, 1_024) }
            .isInstanceOf(AvatarValidationException::class.java)
    }

    @Test
    fun `5MB 경계는 통과하고 초과는 거부한다`() {
        AvatarTypePolicy.validate("image/png", AvatarTypePolicy.MAX_BYTES)
        assertThatThrownBy { AvatarTypePolicy.validate("image/png", AvatarTypePolicy.MAX_BYTES + 1) }
            .isInstanceOf(AvatarValidationException::class.java)
    }

    @ParameterizedTest
    @CsvSource(
        "image/png, png",
        "image/jpeg, jpg",
        "image/gif, gif",
        "image/webp, webp",
    )
    fun `extensionFor 는 허용 MIME 를 확장자로 매핑한다`(
        mime: String,
        expected: String,
    ) {
        assertThat(AvatarTypePolicy.extensionFor(mime)).isEqualTo(expected)
    }

    @Test
    fun `extensionFor 는 허용 목록 밖 MIME 에 AvatarValidationException`() {
        assertThatThrownBy { AvatarTypePolicy.extensionFor("application/pdf") }
            .isInstanceOf(AvatarValidationException::class.java)
    }
}
