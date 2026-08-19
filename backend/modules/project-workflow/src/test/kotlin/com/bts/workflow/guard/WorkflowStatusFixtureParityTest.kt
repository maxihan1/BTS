// 두 BC 의 워크플로우 상태 픽스처 헬퍼가 갈라지지 않았는지 대조하는 판별식

package com.bts.workflow.guard

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * `WorkflowStatusFixture` **2벌**이 같은 계약을 지키는지 대조한다.
 *
 * ### 왜 2벌인가
 * 이 저장소에는 `java-test-fixtures` 소스셋 관례가 없다(실측). BC 격리 규칙상 issue-tracking 테스트가
 * project-workflow 테스트 코드를 import 할 수도 없다. 그래서 같은 헬퍼가 두 모듈에 각각 있다.
 * 근본 해소(`shared-kernel` testFixtures 도입)는 모듈 토폴로지 변경이라 `TODOS.md` 로 넘겼다.
 *
 * ### 왜 「얇게 유지한다」로는 부족한가
 * 그것은 사람의 규율이지 기계의 강제가 아니다. 두 목록이 서로를 검사하지 않으면 반드시 갈라진다 —
 * 저장소가 반복해 물린 지배 결함 양식이다. 그래서 **본문 동일성**을 단언한다.
 * `package` 줄 하나만 다를 수 있고 나머지는 한 글자도 달라선 안 된다.
 *
 * 한쪽에만 컬럼을 더하거나 시그니처를 바꾸면 즉시 red 가 된다.
 */
class WorkflowStatusFixtureParityTest {
    private val fixtures =
        listOf(
            "backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/testsupport/WorkflowStatusFixture.kt",
            "backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/testsupport/WorkflowStatusFixture.kt",
        )

    @Test
    fun `두 BC 의 픽스처 헬퍼는 package 선언만 다르고 본문이 같다`() {
        val bodies = fixtures.map { normalize(readFixture(it)) }
        assertThat(bodies[1])
            .describedAs(
                "두 BC 의 WorkflowStatusFixture 가 갈라졌다. " +
                    "한쪽만 고치면 그 BC 의 테스트가 다른 스키마를 심게 되고, " +
                    "어느 쪽이 옳은지 아무도 검사하지 않는다",
            )
            .isEqualTo(bodies[0])
    }

    @Test
    fun `판별식이 실제로 두 파일을 읽는다`() {
        // 비-공허 짝. 경로가 어긋나면 위 단언이 빈 문자열끼리 비교해 자동 통과한다.
        fixtures.forEach { rel ->
            val text = readFixture(rel)
            assertThat(text).describedAs("픽스처가 비었다: %s", rel).isNotBlank()
            assertThat(text).describedAs("픽스처에 진입점 함수가 없다: %s", rel).contains("fun insertWorkflowStatus(")
        }
    }

    /** `package` 선언 줄만 지운다. 그 한 줄이 두 파일의 유일한 합법적 차이다. */
    private fun normalize(text: String): String =
        text.lineSequence()
            .filterNot { it.startsWith("package ") }
            .joinToString("\n")
            .trim()

    private fun readFixture(relative: String): String {
        val path = repoRoot().resolve(relative)
        check(Files.isRegularFile(path)) { "픽스처 헬퍼가 없다: $relative — 옮겼다면 이 판별식의 경로도 함께 고칠 것" }
        return Files.readString(path)
    }

    private fun repoRoot(): Path {
        var dir = Paths.get("").toAbsolutePath()
        while (!Files.isDirectory(dir.resolve("backend/modules")) && dir.parent != null) {
            dir = dir.parent
        }
        return dir
    }
}
