// BoardIssueView 커스텀 필드 확장 계약 테스트 — customFields 기본값 + 기존 필드 집합 보존 (스펙 C-5 ②)

package com.bts.shared.board

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.reflect.full.primaryConstructor

/**
 * [BoardIssueView] 커스텀 필드 확장 계약 테스트.
 *
 * Spring 컨텍스트 없이 순수 단위 테스트로 실행한다.
 *
 * 검증 항목.
 * - [BoardIssueView.customFields] 필드가 존재하고 미지정 시 기본값이 **빈 맵**이다.
 * - customFields 를 명시 지정하면 값(널 값 포함)을 그대로 보존한다.
 * - **기존 필드 집합이 그대로 살아 있다** — 이름·선언 순서가 유지되고 customFields 만 맨 뒤에 붙는다.
 * - 기존 필수 파라미터 집합이 늘지 않는다(customFields 는 optional) — 기존 소비자 회귀 0.
 */
class BoardIssueViewContractTest {
    /** customFields 도입 이전부터 존재하던 필드의 선언 순서. 이 순서가 바뀌면 위치 인자 호출자가 깨진다. */
    private val legacyParameterNames =
        listOf(
            "key",
            "summary",
            "currentStateKey",
            "assigneeId",
            "priority",
            "version",
            "typeKey",
            "epicKey",
            "rank",
            "labels",
            "originalEstimateSeconds",
        )

    /** customFields 도입 이전부터 기본값이 없던(=호출자가 반드시 넘겨야 하는) 파라미터 집합. */
    private val legacyRequiredParameterNames =
        setOf("key", "summary", "currentStateKey", "assigneeId", "priority", "version", "typeKey")

    private fun minimalView(key: String = "PROJ-1") =
        BoardIssueView(
            key = key,
            summary = "제목",
            currentStateKey = "open",
            assigneeId = null,
            priority = 1,
            version = 0L,
            typeKey = "task",
        )

    // ── customFields 기본값 ──────────────────────────────────────────────────

    @Test
    fun `customFields 를 지정하지 않으면 기본값 빈 맵이다`() {
        // 기본값이 없으면 기존 소비자(issue-tracking adapter 등) 생성 호출이 컴파일되지 않는다.
        val view = minimalView()

        assertThat(view.customFields).isEmpty()
    }

    @Test
    fun `customFields 를 명시 지정하면 널 값을 포함해 그대로 보존한다`() {
        // 값 미입력 커스텀 필드는 키를 유지한 채 null 로 실린다 — 키 자체가 사라지지 않는다.
        val customFields: Map<String, Any?> =
            mapOf(
                "story-points" to 5,
                "team" to "platform",
                "due-quarter" to null,
            )

        val view = minimalView(key = "PROJ-2").copy(customFields = customFields)

        assertThat(view.customFields).containsOnlyKeys("story-points", "team", "due-quarter")
        assertThat(view.customFields["story-points"]).isEqualTo(5)
        assertThat(view.customFields["team"]).isEqualTo("platform")
        assertThat(view.customFields["due-quarter"]).isNull()
    }

    @Test
    fun `customFields 는 생성자 인자로도 지정할 수 있다`() {
        val view =
            BoardIssueView(
                key = "PROJ-3",
                summary = "커스텀 필드 이슈",
                currentStateKey = "in-progress",
                assigneeId = UUID.randomUUID(),
                priority = 2,
                version = 3L,
                typeKey = "story",
                customFields = mapOf("severity" to "high"),
            )

        assertThat(view.customFields).containsEntry("severity", "high")
    }

    // ── 기존 필드 집합 보존 (스펙 C-5 ② — 다른 소비자 회귀 0) ─────────────────

    @Test
    fun `기존 필드 선언 순서가 유지되고 customFields 만 맨 뒤에 추가된다`() {
        val parameterNames =
            requireNotNull(BoardIssueView::class.primaryConstructor) {
                "BoardIssueView 는 data class 주 생성자를 가져야 한다"
            }.parameters.map { it.name }

        assertThat(parameterNames).isEqualTo(legacyParameterNames + "customFields")
    }

    @Test
    fun `customFields 는 optional 이고 기존 필수 파라미터 집합은 늘지 않는다`() {
        val parameters =
            requireNotNull(BoardIssueView::class.primaryConstructor) {
                "BoardIssueView 는 data class 주 생성자를 가져야 한다"
            }.parameters

        val requiredNames = parameters.filterNot { it.isOptional }.map { it.name }

        assertThat(requiredNames).containsExactlyInAnyOrderElementsOf(legacyRequiredParameterNames)
        assertThat(parameters.single { it.name == "customFields" }.isOptional).isTrue()
    }

    @Test
    fun `기존 필드는 customFields 추가 후에도 값을 그대로 보존한다`() {
        val assigneeId = UUID.randomUUID()
        val view =
            BoardIssueView(
                key = "PROJ-4",
                summary = "로그인 버그",
                currentStateKey = "open",
                assigneeId = assigneeId,
                priority = 3,
                version = 1L,
                typeKey = "bug",
                epicKey = "PROJ-100",
                rank = "0|hzzzzz:",
                labels = listOf("urgent", "api"),
                originalEstimateSeconds = 3600,
                customFields = mapOf("severity" to "high"),
            )

        assertThat(view.key).isEqualTo("PROJ-4")
        assertThat(view.summary).isEqualTo("로그인 버그")
        assertThat(view.currentStateKey).isEqualTo("open")
        assertThat(view.assigneeId).isEqualTo(assigneeId)
        assertThat(view.priority).isEqualTo(3)
        assertThat(view.version).isEqualTo(1L)
        assertThat(view.typeKey).isEqualTo("bug")
        assertThat(view.epicKey).isEqualTo("PROJ-100")
        assertThat(view.rank).isEqualTo("0|hzzzzz:")
        assertThat(view.labels).containsExactly("urgent", "api")
        assertThat(view.originalEstimateSeconds).isEqualTo(3600)
    }

    @Test
    fun `기존 기본값 규약이 유지된다 — epicKey rank 는 null 이고 labels 는 빈 리스트다`() {
        val view = minimalView(key = "PROJ-5")

        assertThat(view.epicKey).isNull()
        assertThat(view.rank).isNull()
        assertThat(view.labels).isEmpty()
        assertThat(view.originalEstimateSeconds).isNull()
    }
}
