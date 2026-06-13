// 이벤트 누락 0 커버리지 캡스톤 — AuthEventType 22종 전부 main 소스 emit 배선 회귀 가드 (FR-AU-10 Task 11 + FR-MF-01 Task 8 + FR-MF-02 Task 5 + FR-MF-05 Task 4)

package com.atlas.bts.identity.audit

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Paths

/**
 * FR-AU-10 Task 11 — "이벤트 누락 0" 커버리지 캡스톤 (회귀 가드).
 *
 * ## 목적
 * [AuthEventType] enum 22종 **전부**가 production(main) 소스에서 1개 이상 emit 경로를 가지는지
 * 검증한다. 누군가 새 이벤트 유형을 enum 에 추가하고 emit 배선을 빠뜨리면 — 즉 감사 로그에
 * 절대 기록되지 않는 "유령 이벤트"가 생기면 — 이 테스트가 **fail** 하여 누락을 막는다.
 *
 * ## 동작 원리
 * main 소스 루트(`src/main/kotlin`)의 `.kt` 파일을 walk 하며, enum 선언 파일(`AuthEventType.kt`)을
 * 제외한 곳에서 각 상수가 `AuthEventType.<NAME>` 형태로 1회 이상 등장하는지 텍스트 스캔한다.
 * 단어 경계(`\b`)로 묶어 `LOGOUT` 과 `LOGOUT_ALL_DEVICES` 같은 접두 충돌을 정확히 구분한다.
 *
 * ## vacuous(공허한 통과) 회피
 * 22종이 모두 이미 배선된 상태라 단순히 두면 항상 통과해 가드 역할을 못 한다.
 * 본 테스트는 enum 에 미배선 값을 추가하면 즉시 fail 하도록 설계됐다 — 구현 시 임시 더미 enum
 * 값을 넣어 fail 을 실측 확인했다(`archunit-vacuous-rule-silent-pass` 회귀 방지).
 *
 * ## 새 AuthEventType 추가 시 (이 테스트가 fail 한다면)
 * 1. 해당 이벤트를 발생시키는 production 코드에서 `auditLog.record(... AuthEventType.<NEW> ...)` 로 emit 배선.
 * 2. 배선 후 본 테스트는 자동으로 새 이벤트를 커버 대상에 포함한다(테스트 코드 수정 불필요).
 */
class AuthEventEmitCoverageTest {
    @Test
    fun `모든 AuthEventType 은 main 소스에 emit 배선이 존재해야 한다`() {
        val eventNames = AuthEventType.entries.map { it.name }

        val mainSourceFiles = collectMainSourceKtFiles()
        val unwired =
            eventNames.filter { name ->
                mainSourceFiles.none { file -> emitsEvent(file, name) }
            }

        assertThat(unwired)
            .withFailMessage(
                "다음 AuthEventType 이 main 소스에 emit 배선되어 있지 않습니다: %s. " +
                    "각 이벤트는 production(main) 코드에서 AuthEventType.<NAME> 형태로 1회 이상 emit 되어야 합니다. " +
                    "새 AuthEventType 을 추가했다면 (1) 해당 이벤트를 발생시키는 코드에서 record(...)로 emit 배선하면 " +
                    "(2) 본 테스트가 자동으로 그 이벤트를 커버한다(테스트 코드 수정 불필요).",
                unwired,
            )
            .isEmpty()
    }

    private fun emitsEvent(
        file: File,
        eventName: String,
    ): Boolean {
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
