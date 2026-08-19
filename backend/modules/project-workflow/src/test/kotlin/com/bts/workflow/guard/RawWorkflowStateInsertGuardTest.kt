// 테스트가 원시 SQL 로 워크플로우 상태를 심는 것을 금지하는 판별식 — 허용목록과 그 썩음을 함께 강제한다

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
 * 테스트 소스가 `workflow_states` 에 **원시 SQL 로 직접 INSERT** 하는 것을 막는다.
 *
 * ### 왜 필요한가
 * PR 3 이 읽기 경로를 `statuses` + `workflow_statuses` **2단 카탈로그**로 옮긴다. 구형 테이블에 상태를
 * 심는 테스트는 그 순간 「워크플로우에 상태가 0개」로 읽혀 조용히 무의미해진다. 이주는 한 번이면 되지만
 * **재유입은 계속 일어난다** — 다음 사람이 옆 테스트를 복사하기 때문이다. 그래서 기계가 막는다.
 *
 * mirror data(fixture·seed·mock)의 형식은 helper 호출로 **본질 차단**하고 회귀 가드는 보조로 둔다
 * (learnings 2026-05-23 fixture 옵션 B 패턴). 이 판별식이 그 보조다.
 *
 * ### 탐지 문자열을 런타임에 조립한다
 * 리터럴로 적으면 **이 파일 자신이 위반으로 잡힌다**. 자기를 허용목록에 넣으면 「판별식은 검사에서
 * 빠진다」는 구멍이 열리고, 그 자리에 진짜 원시 SQL 을 두면 아무도 못 잡는다(#356 선례).
 *
 * ### 허용목록의 썩음도 함께 막는다
 * 목록에 있는데 더 이상 위반하지 않는 파일이 남으면, 그 줄이 **미래의 신규 위반을 조용히 통과**시킨다.
 * 그래서 `stale` 을 별도로 단언한다(#356 의 MIGRATION_BASELINE 과 같은 형태).
 */
class RawWorkflowStateInsertGuardTest {
    private val needle = "INSERT INTO " + "workflow_states"

    /**
     * 구형 테이블에 직접 INSERT 하는 것이 **검증 목적 그 자체**인 파일.
     *
     * `V203ToV206MigrationTest` 가 심는 `workflow_states` 는 **V204 백필의 원본**이다.
     * 헬퍼로 감싸면 백필 검증이 통째로 사라진다.
     *
     * ★ 처음에는 여기에 5개를 적었는데 「썩은 항목」 단언이 즉시 4개를 잡아냈다 —
     * `V200MigrationTest`·`SeedStatusCatalogIntegrationTest`·`StatusCatalogParityTest`·
     * `YamlSeedServiceTest` 는 `workflow_states` 를 **언급만 하고 INSERT 는 하지 않는다**.
     * 필요 없는 예외를 목록에 두면 그 줄이 미래의 신규 위반을 조용히 통과시킨다.
     */
    private val allowed = setOf("V203ToV206MigrationTest.kt")

    @Test
    fun `테스트는 원시 SQL 로 workflow_states 에 INSERT 하지 않는다`() {
        val violations = scanTestSources().filter { it.fileName.toString() !in allowed }

        assertThat(violations.map { relativeToRepo(it) })
            .describedAs(
                "원시 SQL 로 workflow_states 를 심는 테스트가 남아 있다. " +
                    "testsupport 의 WorkflowStatusFixture 헬퍼를 쓸 것 — " +
                    "읽기 경로가 statuses + workflow_statuses 2단이라 구형 테이블에 심으면 상태 0개로 읽힌다",
            )
            .isEmpty()
    }

    @Test
    fun `허용목록에 썩은 항목이 없다`() {
        val actuallyViolating = scanTestSources().map { it.fileName.toString() }.toSet()
        val stale = allowed - actuallyViolating

        assertThat(stale)
            .describedAs(
                "허용목록에 있는데 더 이상 원시 SQL 을 쓰지 않는 파일이다. " +
                    "지우지 않으면 그 줄이 미래의 신규 위반을 조용히 통과시킨다",
            )
            .isEmpty()
    }

    @Test
    fun `판별식이 실제로 파일을 읽는다`() {
        // 비-공허 짝. 스캔 경로가 어긋나 0개 파일을 읽으면 위 두 단언이 자동으로 통과한다.
        assertThat(testSourceRoots()).describedAs("스캔할 테스트 소스 루트를 하나도 못 찾았다").isNotEmpty()
        val scanned = testSourceRoots().sumOf { root -> Files.walk(root).use { it.asSequence().count { p -> p.extension == "kt" } } }
        assertThat(scanned).describedAs("스캔한 Kotlin 테스트 파일이 0개다").isGreaterThan(100)
    }

    private fun scanTestSources(): List<Path> =
        testSourceRoots().flatMap { root ->
            Files.walk(root).use { stream ->
                stream.asSequence()
                    .filter { it.extension == "kt" }
                    .filter { it.readText().contains(needle, ignoreCase = true) }
                    .toList()
            }
        }

    private fun testSourceRoots(): List<Path> {
        val modules = repoRoot().resolve("backend/modules")
        return Files.list(modules).use { stream ->
            stream.asSequence()
                .map { it.resolve("src/test/kotlin") }
                .filter { Files.isDirectory(it) }
                .toList()
        }
    }

    private fun relativeToRepo(path: Path): String = repoRoot().relativize(path).toString()

    /** 테스트 작업 디렉터리(backend/ 또는 모듈)에서 저장소 루트를 찾는다. `ContractCoverageTest` 와 같은 방식. */
    private fun repoRoot(): Path {
        var dir = Paths.get("").toAbsolutePath()
        while (!Files.isDirectory(dir.resolve("backend/modules")) && dir.parent != null) {
            dir = dir.parent
        }
        return dir
    }
}
