// UserPreferencesService 단위 테스트 — 기본값 채움/부분 PATCH/enum 검증 (FR-PF-01 Task 2)

package com.atlas.bts.identity.preferences

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * [UserPreferencesService] 단위 테스트 (FR-PF-01 Task 2).
 *
 * MockK 기반 순수 단위 테스트 — [UserPreferencesRepository] mock.
 *
 * ## 테스트 시나리오
 * - getPreferences: 행 없으면 기본값(theme=system, locale=ko, dateFormat=iso), 있으면 저장된 값 그대로.
 * - patchPreferences: 부분 수정(제공 필드만 반영, 나머지 현재값/기본값 유지) → upsert 위임 + 갱신값 반환.
 * - 잘못된 enum 값(theme/locale/dateFormat) → [PreferencesValidationException], upsert 미호출(부분 적용 없음).
 * - Annotation 회귀 가드: @Service + tx 경계(readOnly 조회 / 쓰기 수정).
 */
class UserPreferencesServiceTest {
    private lateinit var repository: UserPreferencesRepository
    private lateinit var service: UserPreferencesService

    private val userId = UUID.fromString("11111111-1111-4111-8111-111111111111")

    @BeforeEach
    fun setUp() {
        repository = mockk()
        service = UserPreferencesService(repository)
    }

    private fun persisted(
        theme: String = "dark",
        locale: String = "en",
        dateFormat: String = "us",
        startPage: String = UserPreferences.DEFAULT_START_PAGE,
    ): UserPreferences = UserPreferences(userId, theme, locale, dateFormat, startPage)

    // ── getPreferences ────────────────────────────────────────────────────────

    @Test
    fun `getPreferences — 행 없으면 기본값(theme=system, locale=ko, dateFormat=iso) 반환`() {
        every { repository.findByUserId(userId) } returns null

        val prefs = service.getPreferences(userId)

        assertThat(prefs.userId).isEqualTo(userId)
        assertThat(prefs.theme).isEqualTo("system")
        assertThat(prefs.locale).isEqualTo("ko")
        assertThat(prefs.dateFormat).isEqualTo("iso")
    }

    @Test
    fun `getPreferences — 행 있으면 저장된 값 그대로 반환`() {
        every { repository.findByUserId(userId) } returns persisted()

        val prefs = service.getPreferences(userId)

        assertThat(prefs.theme).isEqualTo("dark")
        assertThat(prefs.locale).isEqualTo("en")
        assertThat(prefs.dateFormat).isEqualTo("us")
    }

    // ── patchPreferences ──────────────────────────────────────────────────────

    @Test
    fun `patchPreferences — theme만 명시하면 나머지는 현재값 유지 후 upsert`() {
        every { repository.findByUserId(userId) } returns
            persisted(theme = "light", locale = "en", dateFormat = "eu")
        every { repository.upsert(any()) } returns Unit

        val result = service.patchPreferences(userId, PreferencesPatch(theme = "dark"))

        assertThat(result.theme).isEqualTo("dark")
        assertThat(result.locale).isEqualTo("en")
        assertThat(result.dateFormat).isEqualTo("eu")
        verify(exactly = 1) { repository.upsert(UserPreferences(userId, "dark", "en", "eu")) }
    }

    @Test
    fun `patchPreferences — 행이 없을 때 일부 필드만 명시하면 나머지는 기본값`() {
        every { repository.findByUserId(userId) } returns null
        every { repository.upsert(any()) } returns Unit

        val result = service.patchPreferences(userId, PreferencesPatch(dateFormat = "kr"))

        assertThat(result.theme).isEqualTo("system")
        assertThat(result.locale).isEqualTo("ko")
        assertThat(result.dateFormat).isEqualTo("kr")
        verify(exactly = 1) { repository.upsert(UserPreferences(userId, "system", "ko", "kr")) }
    }

    @Test
    fun `patchPreferences — 잘못된 theme 값은 PreferencesValidationException, upsert 미호출(부분 적용 없음)`() {
        assertThatThrownBy { service.patchPreferences(userId, PreferencesPatch(theme = "blue")) }
            .isInstanceOf(PreferencesValidationException::class.java)

        verify(exactly = 0) { repository.findByUserId(any()) }
        verify(exactly = 0) { repository.upsert(any()) }
    }

    @Test
    fun `patchPreferences — 잘못된 locale 값은 PreferencesValidationException`() {
        assertThatThrownBy { service.patchPreferences(userId, PreferencesPatch(locale = "fr")) }
            .isInstanceOf(PreferencesValidationException::class.java)

        verify(exactly = 0) { repository.upsert(any()) }
    }

    @Test
    fun `patchPreferences — 잘못된 dateFormat 값은 PreferencesValidationException`() {
        assertThatThrownBy { service.patchPreferences(userId, PreferencesPatch(dateFormat = "yyyy-mm-dd")) }
            .isInstanceOf(PreferencesValidationException::class.java)

        verify(exactly = 0) { repository.upsert(any()) }
    }

    // ── patchPreferences — startPage (FR-PF-02) ─────────────────────────────────

    @Test
    fun `patchPreferences — startPage만 명시하면 나머지는 현재값 유지 후 upsert`() {
        every { repository.findByUserId(userId) } returns
            persisted(theme = "light", locale = "en", dateFormat = "eu", startPage = "my_issues")
        every { repository.upsert(any()) } returns Unit

        val result = service.patchPreferences(userId, PreferencesPatch(startPage = "inbox"))

        assertThat(result.theme).isEqualTo("light")
        assertThat(result.locale).isEqualTo("en")
        assertThat(result.dateFormat).isEqualTo("eu")
        assertThat(result.startPage).isEqualTo("inbox")
        verify(exactly = 1) { repository.upsert(UserPreferences(userId, "light", "en", "eu", "inbox")) }
    }

    @Test
    fun `patchPreferences — startPage 미지정이고 현재 값 있으면 유지`() {
        every { repository.findByUserId(userId) } returns persisted(startPage = "my_issues")
        every { repository.upsert(any()) } returns Unit

        val result = service.patchPreferences(userId, PreferencesPatch(theme = "dark"))

        assertThat(result.startPage).isEqualTo("my_issues")
    }

    @Test
    fun `patchPreferences — startPage 미지정이고 현재 행 없으면 기본값(dashboards)`() {
        every { repository.findByUserId(userId) } returns null
        every { repository.upsert(any()) } returns Unit

        val result = service.patchPreferences(userId, PreferencesPatch(dateFormat = "kr"))

        assertThat(result.startPage).isEqualTo(UserPreferences.DEFAULT_START_PAGE)
    }

    @Test
    fun `patchPreferences — 잘못된 startPage 값은 PreferencesValidationException, upsert 미호출(부분 적용 없음)`() {
        assertThatThrownBy { service.patchPreferences(userId, PreferencesPatch(startPage = "evil")) }
            .isInstanceOf(PreferencesValidationException::class.java)

        verify(exactly = 0) { repository.findByUserId(any()) }
        verify(exactly = 0) { repository.upsert(any()) }
    }

    // ── Annotation 회귀 가드 ──────────────────────────────────────────────────────

    @Test
    fun `서비스는 Service 애노테이션을 가진다`() {
        assertThat(UserPreferencesService::class.java.isAnnotationPresent(Service::class.java)).isTrue()
    }

    @Test
    fun `getPreferences는 readOnly Transactional, patchPreferences는 쓰기 Transactional`() {
        val getTx =
            UserPreferencesService::class.java
                .getMethod("getPreferences", UUID::class.java)
                .getAnnotation(Transactional::class.java)
        val patchTx =
            UserPreferencesService::class.java
                .getMethod("patchPreferences", UUID::class.java, PreferencesPatch::class.java)
                .getAnnotation(Transactional::class.java)

        assertThat(getTx).isNotNull()
        assertThat(getTx.readOnly).isTrue()
        assertThat(patchTx).isNotNull()
        assertThat(patchTx.readOnly).isFalse()
    }
}
