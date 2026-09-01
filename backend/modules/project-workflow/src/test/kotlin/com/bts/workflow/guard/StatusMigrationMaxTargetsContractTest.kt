// 이관 상한 상수가 issue-tracking 의 원본과 갈라지지 않았는지 대조하는 판별식

package com.bts.workflow.guard

import com.bts.workflow.application.STATUS_MIGRATION_MAX_TARGETS
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.readText

/**
 * `STATUS_MIGRATION_MAX_TARGETS` 가 issue-tracking 의 `BULK_OPERATION_MAX_SIZE` 와 같은지 잰다.
 *
 * ## 왜 값을 복제해 두고 대조하는가
 * 상한의 주인은 issue-tracking 이다(`BulkOperationRepository` 가 그 값으로 적재를 자른다). 그런데
 * project-workflow 가 그것을 알아야 **큐잉 전에** 막을 수 있다 — 넘겨 보내면 워커가 아무것도
 * 적재하지 않고 FAILED 로 끝나 이슈는 한 건도 안 옮겨졌는데 발행은 계속 409 인 막다른 길이 된다.
 *
 * 알아내는 길은 셋이고 둘이 막혀 있다.
 * 1. **직접 import** — BC 격리 위반이다(`CLAUDE.md` §핵심 패턴).
 * 2. **포트에 조회 추가** — `IssueStatusMigrationPort` KDoc 이 「조회 메서드를 만들지 않는다」로
 *    막았고, `shared-kernel` 모듈 전체가 T3 표면이라 이 PR(T2)이 건드릴 수 없다.
 * 3. **복제 + 대조** ← 남은 길. 복제 자체는 막을 수 없으므로 **갈라진 것을 CI 가 즉시 잡게** 한다.
 *
 * 이것은 `[[two-lists-never-check-each-other]]` 의 표준 처방이다 — 두 목록을 두되 차집합을 재는
 * 짝을 함께 둔다. 프론트 `workflow-admin-error.test.ts` 가 백엔드 핸들러 `.kt` 를 직접 읽는 것과
 * 같은 형태다.
 */
class StatusMigrationMaxTargetsContractTest {
    /**
     * ★비-공허 먼저. 경로가 틀리거나 정규식이 안 맞으면 아래 대조가 **아무것도 안 세고** 통과한다.
     * 판정이 있는데 아무것도 안 재는 형태가 가장 나쁘다.
     */
    @Test
    fun `원본 파일과 상수 선언을 실제로 읽어 낸다`() {
        assertThat(Files.exists(sourceOfTruth()))
            .describedAs("issue-tracking 의 상한 정의 파일이 옮겨졌다. 이 판별식의 경로를 고칠 것: %s", sourceOfTruth())
            .isTrue()

        assertThat(declaredMaxSize())
            .describedAs("BULK_OPERATION_MAX_SIZE 선언 형태가 바뀌어 정규식이 못 읽는다. 판별식을 고칠 것")
            .isNotNull()
    }

    @Test
    fun `이관 상한이 issue-tracking 의 일괄작업 상한과 같다`() {
        assertThat(STATUS_MIGRATION_MAX_TARGETS)
            .describedAs(
                "두 값이 갈라졌다. project-workflow 가 더 크면 큐잉이 통과한 뒤 워커가 아무것도 " +
                    "적재하지 않고 FAILED 로 끝나 출구가 없어지고, 더 작으면 옮길 수 있는 것을 막는다",
            ).isEqualTo(declaredMaxSize())
    }

    /** issue-tracking 이 상한을 선언한 곳. 이 판별식이 읽는 유일한 원본이다. */
    private fun sourceOfTruth(): Path =
        repoRoot().resolve(
            "backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/bulk/domain/BulkOperation.kt",
        )

    /** `const val BULK_OPERATION_MAX_SIZE = 1000` 에서 숫자만 뽑는다. 못 읽으면 null 이다. */
    private fun declaredMaxSize(): Long? =
        Regex("""const\s+val\s+BULK_OPERATION_MAX_SIZE\s*(?::\s*\w+\s*)?=\s*(\d+)""")
            .find(sourceOfTruth().readText())
            ?.groupValues
            ?.get(1)
            ?.toLong()

    private fun repoRoot(): Path {
        var dir = Paths.get("").toAbsolutePath()
        while (!Files.isDirectory(dir.resolve("backend/modules")) && dir.parent != null) {
            dir = dir.parent
        }
        return dir
    }
}
