// 이벤트 누락 0 커버리지 캡스톤 — AuthEventType 12종 전부 main 소스 emit 배선 회귀 가드 (FR-AU-10 Task 11)

package com.atlas.bts.identity.audit

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Paths

class AuthEventEmitCoverageTest {

    @Test
    fun `모든 AuthEventType 은 main 소스에 emit 배선이 존재해야 한다`() {
        // RED 단계 — 의도적으로 존재하지 않는 가짜 이벤트명을 스캔 대상에 추가해
        // 가드가 진짜 미배선을 잡는지 증명한다 (vacuous green 회피, GREEN 에서 제거).
        val eventNames = AuthEventType.entries.map { it.name } + "__FAKE_UNWIRED_EVENT__"

        val mainSourceFiles = collectMainSourceKtFiles()
        val unwired = eventNames.filter { name ->
            mainSourceFiles.none { file -> emitsEvent(file, name) }
        }

        assertThat(unwired)
            .withFailMessage(
                "다음 AuthEventType 이 main 소스에 emit 배선되어 있지 않습니다: %s. " +
                    "각 이벤트는 production(main) 코드에서 AuthEventType.<NAME> 형태로 1회 이상 emit 되어야 합니다.",
                unwired,
            )
            .isEmpty()
    }

    private fun emitsEvent(file: File, eventName: String): Boolean {
        // AuthEventType.LOGOUT 와 AuthEventType.LOGOUT_ALL_DEVICES 를 구분하기 위해
        // 이벤트명 앞뒤를 단어 경계로 고정한다 (\b 는 '_' 를 단어문자로 보아 정확히 구분).
        val pattern = Regex("AuthEventType\\.\\b" + Regex.escape(eventName) + "\\b")
        return pattern.containsMatchIn(file.readText())
    }

    private fun collectMainSourceKtFiles(): List<File> {
        val gradleDir = System.getProperty("user.dir")
        val candidates =
            listOf(
                Paths.get(gradleDir, "src/main/kotlin"),
                Paths.get(gradleDir, "modules/identity-access/src/main/kotlin"),
                Paths.get(gradleDir, "../identity-access/src/main/kotlin"),
            )
        val mainRoot =
            candidates.map { it.toFile() }.firstOrNull { it.isDirectory }
                ?: error("identity-access main 소스 루트를 찾을 수 없음. candidates: $candidates")

        return mainRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { it.name == "AuthEventType.kt" }
            .toList()
    }
}
