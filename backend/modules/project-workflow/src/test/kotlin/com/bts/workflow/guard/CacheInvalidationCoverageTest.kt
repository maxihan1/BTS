// 쓰기 유스케이스가 캐시 무효화를 빠뜨리지 않았는지 대조하는 판별식

package com.bts.workflow.guard

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.streams.asSequence

/**
 * `WorkflowCache` 를 주입받는 서비스의 **쓰기 함수**가 캐시 무효화를 부르는지 대조한다.
 *
 * ### 왜 필요한가
 * 무효화를 빠뜨리면 편집이 런타임 전환 계산에 반영되지 않는다. 그리고 그 실패는 **조용하다** —
 * 예외도 로그도 없고 「방금 고쳤는데 왜 안 바뀌지」로만 나타난다. 사람이 매번 확인할 수 없으므로
 * 기계가 센다. 저장소가 반복해 물린 「두 목록이 서로를 검사하지 않는다」의 처방이다.
 *
 * ### 대조 축
 * ```
 *   [목록 A] WorkflowCache 를 주입받는 서비스의 @Transactional(비-readOnly) 함수
 *   [목록 B] 그 함수 본문에서 invalidate 를 부르는 것
 *   A - B - 허용목록 = ∅  이어야 한다
 * ```
 *
 * ### 허용목록의 썩음도 함께 막는다
 * 목록에 있는데 이제는 무효화를 부르는 함수가 남으면, 그 줄이 **미래의 신규 누락을 조용히
 * 통과**시킨다(#356 의 `stale` 단언과 같은 형태).
 */
class CacheInvalidationCoverageTest {
    /** 탐지 문자열은 런타임에 조립한다 — 리터럴로 적으면 이 파일 자신이 대상으로 잡힌다(#356). */
    private val cacheType = "Workflow" + "Cache"

    /**
     * 무효화 경로를 탔다고 볼 신호 **2종**.
     *
     * `invalidate(` 로 좁히면 **거짓 양성**이 난다 — `invalidateWorkflowsUsing(id)` 처럼
     * 여러 워크플로우를 도는 private 헬퍼를 거치는 경우가 있다(실제로 한 번 잡혔다).
     * 주석 줄은 세지 않으므로 「주석에만 적어 두고 안 부르는」 우회는 막힌다.
     *
     * ### `withWriteLock` 도 무효화다 (FR-WF-07 에서 추가)
     * `WorkflowCache.withWriteLock(key) { ... }` 은 block 실행 **직후 반드시** `invalidate(key)` 를
     * 부른다 — 성공 경로 두 갈래(첫 시도 획득 · 재시도 획득) 모두에서 그렇다. 직접 호출보다
     * 오히려 강한 보장이다(advisory lock 으로 동시 쓰기까지 막는다).
     *
     * 이것을 신호로 인정하지 않으면 발행 경로가 「무효화를 빠뜨렸다」로 **거짓 양성**이 나고,
     * 그 오탐을 허용목록으로 덮으면 그 줄이 미래의 진짜 누락까지 함께 통과시킨다.
     * 허용목록은 「무효화가 **불필요한** 함수」의 자리이지 「다른 방식으로 무효화하는 함수」의
     * 자리가 아니다.
     */
    private val invalidateSignals =
        listOf(
            "invalid" + "ate",
            "with" + "WriteLock",
        )

    /**
     * 무효화를 부르지 않아도 되는 쓰기 함수.
     *
     * | 함수 | 왜 예외인가 |
     * |---|---|
     * | `StatusCommandService.create` | 갓 만든 상태는 **아직 어느 워크플로우에도 편성되지 않았다**. 무효화할 캐시 항목이 존재하지 않는다 |
     * | `StatusCommandService.delete` | 편성된 상태는 애초에 지울 수 없다(`StatusInUseException`). 지울 수 있는 것은 편성 0건뿐이다 |
     * | `WorkflowPublishService.migrate` | 아래 소절 |
     *
     * ### `migrate` 가 예외인 이유
     * 이관 큐잉은 **워크플로우 정의를 바꾸지 않는다** — project-workflow 는 읽기만 하고
     * 쓰기는 `bulk_operations` 한 곳뿐이다(FR-WF-07 F4). 캐시가 담는 것이 정의이고 그 내용이
     * 그대로이므로 무효화할 항목이 없다. 실제 이슈 이동은 워커가 **자기 트랜잭션에서** 하고,
     * 정의 교체는 `publish` 가 `withWriteLock` 안에서 한다 — 둘 다 이 함수 밖이다.
     */
    private val allowed =
        setOf(
            "StatusCommandService.create",
            "StatusCommandService.delete",
            "WorkflowPublishService.migrate",
        )

    @Test
    fun `캐시를 쥔 서비스의 쓰기 함수는 모두 무효화를 부른다`() {
        val missing = writeFunctionsWithoutInvalidation().filter { it !in allowed }

        assertThat(missing)
            .describedAs(
                "쓰기 함수가 캐시 무효화를 빠뜨렸다. 편집이 런타임 전환 계산에 반영되지 않고 " +
                    "그 실패는 조용하다 — 예외도 로그도 없이 「고쳤는데 안 바뀐다」로만 나타난다",
            ).isEmpty()
    }

    @Test
    fun `허용목록에 썩은 항목이 없다`() {
        val stale = allowed - writeFunctionsWithoutInvalidation().toSet()

        assertThat(stale)
            .describedAs("이제는 무효화를 부르는 함수가 허용목록에 남아 있다. 그 줄이 미래의 신규 누락을 통과시킨다")
            .isEmpty()
    }

    @Test
    fun `판별식이 실제로 서비스를 읽는다`() {
        // 비-공허 짝. 대상이 0개면 위 두 단언이 자동으로 통과한다.
        val services = cacheHoldingServices()
        assertThat(services).describedAs("WorkflowCache 를 쥔 서비스를 하나도 못 찾았다").isNotEmpty()
        assertThat(allWriteFunctions()).describedAs("쓰기 함수를 하나도 못 찾았다").isNotEmpty()
    }

    /** `WorkflowCache` 를 생성자로 받는 서비스 파일. */
    private fun cacheHoldingServices(): List<Path> {
        val root = repoRoot().resolve("backend/modules/project-workflow/src/main/kotlin")
        return Files.walk(root).use { stream ->
            stream
                .asSequence()
                .filter { it.extension == "kt" }
                .filter { it.fileName.toString().endsWith("Service.kt") }
                .filter { it.readText().contains(": $cacheType,") }
                .toList()
        }
    }

    /** 대상 서비스의 쓰기 함수 전부. `클래스.함수` 형태로 준다. */
    private fun allWriteFunctions(): List<String> = cacheHoldingServices().flatMap(::writeFunctionsOf)

    /** 무효화를 부르지 않는 쓰기 함수. */
    private fun writeFunctionsWithoutInvalidation(): List<String> =
        cacheHoldingServices().flatMap { file ->
            val className = file.fileName.toString().removeSuffix(".kt")
            writeFunctionBodies(file)
                .filterNot { (_, body) ->
                    val code = stripComments(body)
                    invalidateSignals.any { signal -> code.contains(signal) }
                }.map { "$className.${it.first}" }
        }

    private fun writeFunctionsOf(file: Path): List<String> {
        val className = file.fileName.toString().removeSuffix(".kt")
        return writeFunctionBodies(file).map { "$className.${it.first}" }
    }

    /**
     * `@Transactional`(readOnly 아님)이 붙은 public 함수의 (이름, 본문).
     *
     * 읽기 전용은 캐시를 건드리지 않으므로 대상이 아니다.
     */
    private fun writeFunctionBodies(file: Path): List<Pair<String, String>> {
        val text = file.readText()
        val result = mutableListOf<Pair<String, String>>()
        val regex = Regex("""@Transactional(\([^)]*\))?\s*\n\s*fun\s+(\w+)""")
        for (match in regex.findAll(text)) {
            if (match.groupValues[1].contains("readOnly")) continue
            result += match.groupValues[2] to bodyAfter(text, match.range.last)
        }
        return result
    }

    /**
     * 함수 본문을 **중괄호 균형**으로 자른다.
     *
     * ★ 처음에는 「다음 `@Transactional` 까지」로 잘랐는데 그러면 마지막 쓰기 함수의 본문에
     * **뒤따르는 private 헬퍼의 정의까지** 딸려 들어간다. 실제로 `StatusCommandService.delete` 가
     * 뒤에 있는 `invalidateWorkflowsUsing` **이름** 때문에 「무효화를 부른다」로 오판됐다.
     * 판별식이 자기 파싱 오차로 거짓 음성을 내면 그 순간 아무것도 지키지 못한다.
     */
    @Suppress("ReturnCount") // 균형 파싱은 early return 3개가 가장 읽기 쉽다 — 플래그 변수로 바꾸면 오히려 흐려진다
    private fun bodyAfter(
        text: String,
        signatureEnd: Int,
    ): String {
        val open = text.indexOf('{', signatureEnd)
        if (open == -1) return text.substring(signatureEnd)
        var depth = 0
        var i = open
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(open, i + 1)
                }
            }
            i++
        }
        return text.substring(open)
    }

    /** 주석 줄을 뺀다 — 무효화를 「적어만 두고」 안 부르는 우회를 막는다. */
    private fun stripComments(body: String): String =
        body
            .lineSequence()
            .map { it.substringBefore("//") }
            .filterNot { it.trimStart().startsWith("*") }
            .joinToString("\n")

    private fun repoRoot(): Path {
        var dir = Paths.get("").toAbsolutePath()
        while (!Files.isDirectory(dir.resolve("backend/modules")) && dir.parent != null) {
            dir = dir.parent
        }
        return dir
    }
}
