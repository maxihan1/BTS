// UserKeymapService 단위 테스트 — effective 병합/검증우선 PATCH/예외분기 (FR-PF-03 Task 4)

package com.atlas.bts.identity.keymap

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowableOfType
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * [UserKeymapService] 단위 테스트 (FR-PF-03 Task 4).
 *
 * MockK 기반 순수 단위 테스트 — [UserKeymapRepository] mock, [KeymapValidator] 는 실제 사용.
 *
 * ## 테스트 시나리오
 * - getKeymap: override 없으면 5종 전부 기본값, 일부 override 있으면 병합(override 우선).
 * - patchKeymap: 검증 실패(VALIDATION/CONFLICT) 시 write 0회 + 카테고리별 예외 분기,
 *   정상이면 override 정규화(기본값과 다른 것만 replaceOverrides 에 전달) 후 effective 반환.
 * - Annotation 회귀 가드: @Service + tx 경계(readOnly 조회 / 쓰기 수정).
 */
class UserKeymapServiceTest {
    private lateinit var repository: UserKeymapRepository
    private lateinit var service: UserKeymapService

    private val userId = UUID.fromString("22222222-2222-4222-8222-222222222222")

    @BeforeEach
    fun setUp() {
        repository = mockk()
        service = UserKeymapService(repository)
    }

    // ── getKeymap ────────────────────────────────────────────────────────────

    @Test
    fun `getKeymap — override 없으면 5종 전부 기본값`() {
        every { repository.findByUserId(userId) } returns emptyList()

        val result = service.getKeymap(userId)

        assertThat(result).containsExactlyInAnyOrderElementsOf(KeymapAction.DEFAULT_BINDINGS)
    }

    @Test
    fun `getKeymap — 일부 override 있으면 병합(override 우선), 나머지는 기본값`() {
        every { repository.findByUserId(userId) } returns
            listOf(KeymapBinding(KeymapAction.CREATE_ISSUE.id, "n"))

        val result = service.getKeymap(userId)

        assertThat(result).hasSize(5)
        assertThat(result).contains(KeymapBinding(KeymapAction.CREATE_ISSUE.id, "n"))
        assertThat(result).contains(KeymapBinding(KeymapAction.HELP.id, KeymapAction.HELP.defaultKeyCombo))
        assertThat(result).contains(KeymapBinding(KeymapAction.SEARCH.id, KeymapAction.SEARCH.defaultKeyCombo))
        assertThat(result).contains(
            KeymapBinding(KeymapAction.GOTO_MY_ISSUES.id, KeymapAction.GOTO_MY_ISSUES.defaultKeyCombo),
        )
        assertThat(result).contains(
            KeymapBinding(KeymapAction.GOTO_DASHBOARD.id, KeymapAction.GOTO_DASHBOARD.defaultKeyCombo),
        )
    }

    // ── patchKeymap — 검증 실패(VALIDATION) ──────────────────────────────────

    @Test
    fun `patchKeymap — action 누락(화이트리스트 위반)은 KeymapValidationException, write 0회`() {
        val bindings =
            KeymapAction.entries
                .filterNot { it == KeymapAction.GOTO_DASHBOARD }
                .map { KeymapBinding(it.id, it.defaultKeyCombo) }

        assertThatThrownBy { service.patchKeymap(userId, bindings) }
            .isInstanceOf(KeymapValidationException::class.java)
        verify(exactly = 0) { repository.replaceOverrides(any(), any()) }
    }

    @Test
    fun `patchKeymap — 형식 위반(2글자 single)은 KeymapValidationException, write 0회`() {
        val bindings = defaultBindingsWith(KeymapAction.HELP to "ab")

        assertThatThrownBy { service.patchKeymap(userId, bindings) }
            .isInstanceOf(KeymapValidationException::class.java)
        verify(exactly = 0) { repository.replaceOverrides(any(), any()) }
    }

    @Test
    fun `patchKeymap — 빈 key_combo(빈값 위반)는 KeymapValidationException, write 0회`() {
        val bindings = defaultBindingsWith(KeymapAction.SEARCH to "")

        assertThatThrownBy { service.patchKeymap(userId, bindings) }
            .isInstanceOf(KeymapValidationException::class.java)
        verify(exactly = 0) { repository.replaceOverrides(any(), any()) }
    }

    // ── patchKeymap — 검증 실패(CONFLICT) ────────────────────────────────────

    @Test
    fun `patchKeymap — 완전중복은 KeymapConflictException(conflicts에 Duplicate 포함), write 0회`() {
        val bindings = defaultBindingsWith(KeymapAction.SEARCH to "c")

        val thrown =
            catchThrowableOfType(KeymapConflictException::class.java) {
                service.patchKeymap(userId, bindings)
            }

        assertThat(thrown).isNotNull()
        assertThat(thrown.conflicts).hasSize(1)
        assertThat(thrown.conflicts.single()).isInstanceOf(KeymapViolation.Duplicate::class.java)
        verify(exactly = 0) { repository.replaceOverrides(any(), any()) }
    }

    @Test
    fun `patchKeymap — leader 접두 충돌은 KeymapConflictException, write 0회`() {
        val bindings = defaultBindingsWith(KeymapAction.HELP to "g")

        assertThatThrownBy { service.patchKeymap(userId, bindings) }
            .isInstanceOf(KeymapConflictException::class.java)
        verify(exactly = 0) { repository.replaceOverrides(any(), any()) }
    }

    @Test
    fun `patchKeymap — dead leader combo(g g)는 KeymapConflictException, write 0회`() {
        val bindings = defaultBindingsWith(KeymapAction.GOTO_MY_ISSUES to "g g")

        assertThatThrownBy { service.patchKeymap(userId, bindings) }
            .isInstanceOf(KeymapConflictException::class.java)
        verify(exactly = 0) { repository.replaceOverrides(any(), any()) }
    }

    // ── patchKeymap — 정상(override 정규화) ──────────────────────────────────

    @Test
    fun `patchKeymap — 정상이면 기본값과 다른 것만 replaceOverrides에 전달 후 effective 반환`() {
        val bindings = defaultBindingsWith(KeymapAction.CREATE_ISSUE to "n")
        every { repository.replaceOverrides(userId, any()) } returns Unit

        val result = service.patchKeymap(userId, bindings)

        assertThat(result).containsExactlyInAnyOrderElementsOf(bindings)
        verify(exactly = 1) {
            repository.replaceOverrides(userId, listOf(KeymapBinding(KeymapAction.CREATE_ISSUE.id, "n")))
        }
    }

    @Test
    fun `patchKeymap — 전부 기본값이면 replaceOverrides에 빈 목록 전달(override 전체 복원)`() {
        val bindings = KeymapAction.DEFAULT_BINDINGS
        every { repository.replaceOverrides(userId, emptyList()) } returns Unit

        val result = service.patchKeymap(userId, bindings)

        assertThat(result).containsExactlyInAnyOrderElementsOf(bindings)
        verify(exactly = 1) { repository.replaceOverrides(userId, emptyList()) }
    }

    // ── Annotation 회귀 가드 ──────────────────────────────────────────────────

    @Test
    fun `서비스는 Service 애노테이션을 가진다`() {
        assertThat(UserKeymapService::class.java.isAnnotationPresent(Service::class.java)).isTrue()
    }

    @Test
    fun `getKeymap은 readOnly Transactional, patchKeymap은 쓰기 Transactional`() {
        val getTx =
            UserKeymapService::class.java
                .getMethod("getKeymap", UUID::class.java)
                .getAnnotation(Transactional::class.java)
        val patchTx =
            UserKeymapService::class.java
                .getMethod("patchKeymap", UUID::class.java, List::class.java)
                .getAnnotation(Transactional::class.java)

        assertThat(getTx).isNotNull()
        assertThat(getTx.readOnly).isTrue()
        assertThat(patchTx).isNotNull()
        assertThat(patchTx.readOnly).isFalse()
    }

    // ── helper ───────────────────────────────────────────────────────────────

    /** 기본 키맵에서 일부 action 만 [overrides] 로 교체한 5종 완비 바인딩 목록. */
    private fun defaultBindingsWith(vararg overrides: Pair<KeymapAction, String>): List<KeymapBinding> {
        val overrideMap = overrides.toMap()
        return KeymapAction.entries.map { action ->
            KeymapBinding(action.id, overrideMap[action] ?: action.defaultKeyCombo)
        }
    }
}
