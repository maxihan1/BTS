// 발급 경로 없는 authority 를 요구하는 SpEL 을 막는 판별식

package com.bts.shared.guard

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.streams.asSequence

/**
 * `hasAuthority('X')` 의 X 가 **`ROLE_` 로 시작하는지** 검사한다.
 *
 * ### 무엇을 막는가 — PR 3 이 실제로 물린 결함
 * `WorkflowController` 가 `hasAuthority('WORKFLOW_MANAGE')` 를 걸고 있었는데, 그 authority 를
 * **발급하는 경로가 저장소 어디에도 없었다.**
 *
 * ```
 *   요구:  hasAuthority('WORKFLOW_MANAGE')      ← 접두어 없음
 *   발급:  SidRevokeJwtConverter:115  setAuthorityPrefix("ROLE_")
 *          PatAuthenticationFilter:48 SimpleGrantedAuthority("ROLE_PAT")
 *          → 만들어지는 authority 는 전부 ROLE_* 이다
 * ```
 *
 * 결과는 **어떤 실제 요청으로도 통과할 수 없는 게이트**였다. 테스트는
 * `@WithMockUser(authorities = [...])` 로 authority 를 손수 심어 초록이었다
 * (`unreachable-state-fixture-is-fake-green`).
 *
 * ### 왜 `@PreAuthorize` 자체를 금지하지 않는가
 * 초안은 그랬으나 실측이 뒤집었다 — 저장소에 `@PreAuthorize` 가 **49건** 있고
 * `isAuthenticated()` · `hasRole('SYSTEM_ADMIN')` 은 정당한 사용이다. 애노테이션을 금지하면
 * 무관한 49건이 red 가 되고, 그런 판별식은 곧 꺼진다.
 *
 * ### 왜 `hasRole(` 은 검사하지 않는가
 * Spring 이 `hasRole('X')` 를 `ROLE_X` 로 자동 변환한다. 접두어를 사람이 적지 않으므로
 * 같은 결함이 생기지 않는다.
 */
class GrantedAuthorityPrefixGuardTest {
    /** 탐지 문자열은 런타임에 조립한다 — 리터럴로 적으면 이 파일 자신이 위반으로 잡힌다(#356). */
    private val needle = "hasAuthority" + "("

    private val authorityPattern = Regex("""hasAuthority\(\s*['"]([^'"]+)['"]\s*\)""")

    @Test
    fun `hasAuthority 는 ROLE_ 로 시작하는 authority 만 요구한다`() {
        val violations =
            mainSources().flatMap { file ->
                authorityPattern
                    .findAll(stripComments(file.readText()))
                    .map { it.groupValues[1] }
                    .filterNot { it.startsWith("ROLE_") }
                    .map { "${relativeToRepo(file)} → hasAuthority('$it')" }
                    .toList()
            }

        assertThat(violations)
            .describedAs(
                "이 저장소가 만드는 authority 는 전부 ROLE_ 접두어를 갖는다" +
                    "(SidRevokeJwtConverter 의 setAuthorityPrefix · PatAuthenticationFilter 의 ROLE_PAT). " +
                    "접두어 없는 authority 를 요구하면 그 게이트는 어떤 실제 요청으로도 통과할 수 없다 — " +
                    "권한 판정은 resolver 를 명시적으로 호출하는 것이 이 저장소의 관례다",
            ).isEmpty()
    }

    @Test
    fun `판별식이 실제로 main 소스를 읽는다`() {
        // 비-공허 짝. 스캔 경로가 어긋나 0개를 읽으면 위 단언이 자동으로 통과한다.
        val sources = mainSources()
        assertThat(sources).describedAs("스캔할 main 소스를 하나도 못 찾았다").isNotEmpty()
        assertThat(sources.size).describedAs("스캔한 Kotlin main 파일이 너무 적다 — 경로가 어긋났는지 확인할 것").isGreaterThan(100)
    }

    @Test
    fun `탐지 대상 문자열이 실제로 검색된다`() {
        // 정규식이 아무것도 못 잡는 형태로 썩는 것을 막는다. 이 파일 안의 예시 문자열로 자기 검증한다.
        val sample = "@PreAuthorize(\"" + needle + "'ROLE_SAMPLE')\")"
        assertThat(authorityPattern.find(sample)?.groupValues?.get(1)).isEqualTo("ROLE_SAMPLE")
    }

    /**
     * 주석을 뺀다.
     *
     * 실제로 한 번 잡혔다 — Task 8 이 `WorkflowController` KDoc 에 「종전에는
     * `hasAuthority('WORKFLOW_MANAGE')` 였다」고 **설명을 적어 둔 것**을 위반으로 셌다.
     * 결함의 이력을 코드 옆에 남기는 것은 좋은 일이므로 판별식이 그것을 막아선 안 된다.
     */
    private fun stripComments(text: String): String =
        text
            .lineSequence()
            .map { it.substringBefore("//") }
            .filterNot { it.trimStart().startsWith("*") }
            .filterNot { it.trimStart().startsWith("/*") }
            .joinToString("\n")

    private fun mainSources(): List<Path> {
        val modules = repoRoot().resolve("backend/modules")
        return Files.list(modules).use { stream ->
            stream
                .asSequence()
                .map { it.resolve("src/main/kotlin") }
                .filter { Files.isDirectory(it) }
                .toList()
        }.flatMap { root ->
            Files.walk(root).use { it.asSequence().filter { p -> p.extension == "kt" }.toList() }
        }
    }

    private fun relativeToRepo(path: Path): String = repoRoot().relativize(path).toString()

    private fun repoRoot(): Path {
        var dir = Paths.get("").toAbsolutePath()
        while (!Files.isDirectory(dir.resolve("backend/modules")) && dir.parent != null) {
            dir = dir.parent
        }
        return dir
    }
}
