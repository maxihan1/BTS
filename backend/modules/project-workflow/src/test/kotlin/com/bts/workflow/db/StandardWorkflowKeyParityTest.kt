// V205 의 표준 워크플로우 키 목록이 YamlSeedService 상수의 사본으로 갈라지지 않게 막는 판별식

package com.bts.workflow.db

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * `V205__workflows_add_version_origin.sql` 의 `origin='SEED'` 대상 키 목록과
 * `YamlSeedService.standardWorkflowKeys` 를 **집합으로 대조**한다.
 *
 * ### 왜 있나
 * 두 목록은 서로를 검사하지 않는다. 5번째 표준 워크플로우가 생기면 시드 상수만 늘고 마이그레이션은
 * 그대로 남아, 그 워크플로우가 `origin='CUSTOM'` 인 채 「기본값 복원」 대상에서 조용히 빠진다.
 * 저장소가 반복해 물린 양식이다 (`two-lists-never-check-each-other`). 게이트 1 리뷰 R3.
 *
 * 컨테이너를 쓰지 않는다 — 파일 두 개의 문자열 대조다.
 */
class StandardWorkflowKeyParityTest {
    private val moduleRoot: File = findModuleRoot()

    private fun findModuleRoot(): File {
        var dir = File(".").absoluteFile
        while (dir.parentFile != null) {
            if (File(dir, "src/main/resources/db/migration/project-workflow").isDirectory) return dir
            dir = dir.parentFile
        }
        error("project-workflow 모듈 루트를 찾지 못했다 (cwd=${File(".").absolutePath})")
    }

    /** V205 SQL 의 `WHERE key IN (...)` 안 작은따옴표 리터럴을 전수 추출한다. */
    private fun migrationKeys(): Set<String> {
        val sql =
            File(moduleRoot, "src/main/resources/db/migration/project-workflow/V205__workflows_add_version_origin.sql")
                .readText()
        val inClause =
            Regex("""WHERE\s+key\s+IN\s*\(([^)]*)\)""", RegexOption.IGNORE_CASE)
                .find(sql)
                ?.groupValues
                ?.get(1)
                ?: error("V205 에서 `WHERE key IN (...)` 을 찾지 못했다 — origin='SEED' 표기 방식이 바뀌었나?")
        return Regex("'([^']+)'").findAll(inClause).map { it.groupValues[1] }.toSet()
    }

    /** YamlSeedService 의 standardWorkflowKeys 리스트 리터럴을 전수 추출한다. */
    private fun seedServiceKeys(): Set<String> {
        val source = File(moduleRoot, "src/main/kotlin/com/bts/workflow/seed/YamlSeedService.kt").readText()
        val listBlock =
            Regex("""standardWorkflowKeys\s*=\s*\n?\s*listOf\(([^)]*)\)""")
                .find(source)
                ?.groupValues
                ?.get(1)
                ?: error("YamlSeedService 에서 standardWorkflowKeys listOf(...) 를 찾지 못했다")
        return Regex("\"([^\"]+)\"").findAll(listBlock).map { it.groupValues[1] }.toSet()
    }

    @Test
    fun `V205 의 SEED 표기 대상과 YamlSeedService 의 표준 키 목록이 같다`() {
        val fromMigration = migrationKeys()
        val fromSeedService = seedServiceKeys()

        assertThat(fromMigration).isNotEmpty()
        assertThat(fromSeedService).isNotEmpty()
        assertThat(fromMigration)
            .describedAs(
                "V205 에만 있는 키 %s · YamlSeedService 에만 있는 키 %s",
                fromMigration - fromSeedService,
                fromSeedService - fromMigration,
            )
            .isEqualTo(fromSeedService)
    }
}
