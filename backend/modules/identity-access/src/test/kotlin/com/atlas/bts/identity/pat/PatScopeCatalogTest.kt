// PatScopeCatalog 단위 테스트 — SUPPORTED 카탈로그 · validate(빈/미지 거부) · normalize(중복제거·순서보존)

package com.atlas.bts.identity.pat

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow

class PatScopeCatalogTest {
    // -------------------------------------------------------------------------
    // SUPPORTED 카탈로그 (ADR 확정 5종)
    // -------------------------------------------------------------------------

    @Test
    fun `SUPPORTED 는 ADR 확정 5종 scope 를 포함한다`() {
        Assertions.assertEquals(
            setOf("read:issues", "write:issues", "read:projects", "write:projects", "*"),
            PatScopeCatalog.SUPPORTED,
        )
    }

    // -------------------------------------------------------------------------
    // validate
    // -------------------------------------------------------------------------

    @Test
    fun `validate 는 알려진 scope 목록을 예외 없이 통과시킨다`() {
        assertDoesNotThrow {
            PatScopeCatalog.validate(listOf("read:issues"))
        }
    }

    @Test
    fun `validate 는 미지 scope 에 대해 UnknownScopeException 을 던진다`() {
        Assertions.assertThrows(UnknownScopeException::class.java) {
            PatScopeCatalog.validate(listOf("bogus"))
        }
    }

    @Test
    fun `validate 는 빈 scope 목록을 EmptyScopeException 으로 거부한다`() {
        Assertions.assertThrows(EmptyScopeException::class.java) {
            PatScopeCatalog.validate(emptyList())
        }
    }

    @Test
    fun `validate 는 대소문자가 다른 scope 를 미지로 취급한다 (exact match)`() {
        Assertions.assertThrows(UnknownScopeException::class.java) {
            PatScopeCatalog.validate(listOf("READ:issues"))
        }
    }

    @Test
    fun `validate 예외 message 는 미지 scope 원문을 반사하지 않는다`() {
        val leaked = "super-secret-internal-scope"
        val ex =
            Assertions.assertThrows(UnknownScopeException::class.java) {
                PatScopeCatalog.validate(listOf(leaked))
            }
        Assertions.assertFalse(
            ex.message!!.contains(leaked),
            "예외 message 에 입력 scope 원문이 반사되면 안 된다. 실제: ${ex.message}",
        )
    }

    // -------------------------------------------------------------------------
    // normalize (중복 제거 + 입력 순서 보존)
    // -------------------------------------------------------------------------

    @Test
    fun `normalize 는 중복 scope 를 하나로 정규화한다`() {
        Assertions.assertEquals(
            listOf("read:issues"),
            PatScopeCatalog.normalize(listOf("read:issues", "read:issues")),
        )
    }

    @Test
    fun `normalize 는 중복을 제거하되 입력 순서를 보존한다`() {
        Assertions.assertEquals(
            listOf("write:issues", "read:issues", "read:projects"),
            PatScopeCatalog.normalize(
                listOf("write:issues", "read:issues", "write:issues", "read:projects"),
            ),
        )
    }

    @Test
    fun `normalize 는 빈 목록에 대해 빈 목록을 반환한다`() {
        Assertions.assertEquals(emptyList<String>(), PatScopeCatalog.normalize(emptyList()))
    }
}
