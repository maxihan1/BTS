// 버전 릴리즈 노트 Markdown 조립 순수 도메인 함수 — repository/DB/Spring 의존 없음
package com.bts.issue.version.releasenotes

import java.time.LocalDate

// ── 템플릿 상수 ────────────────────────────────────────────────────────────────

private const val TEMPLATE_TITLE_SUFFIX = "릴리즈 노트"
private const val TEMPLATE_STATUS_LABEL = "상태"
private const val TEMPLATE_RELEASE_DATE_LABEL = "릴리즈일"
private const val TEMPLATE_ISSUE_COUNT_LABEL = "포함 이슈"
private const val TEMPLATE_ISSUE_COUNT_UNIT = "건"
private const val TEMPLATE_NO_ISSUES = "포함된 이슈가 없습니다."
private const val TEMPLATE_DATE_UNSPECIFIED = "미지정"

// ── 입력 모델 ─────────────────────────────────────────────────────────────────

/**
 * 릴리즈 노트 생성기 입력 모델.
 *
 * Task 1 의 [com.bts.issue.repository.ReleaseNoteIssueRow] (repository row) 와는 별개로,
 * generator layer 에만 쓰이는 의도적인 layer 분리 모델이다.
 *
 * @property projectKey 프로젝트 키. 예: `"ATLAS"`.
 * @property versionName 버전 이름. 예: `"1.2.0"`.
 * @property versionStatus 버전 상태 문자열. 예: `"RELEASED"`.
 * @property releaseDate 릴리즈 예정일. null 이면 `미지정` 으로 표시한다.
 * @property issues 포함할 이슈 목록.
 */
data class ReleaseNotesInput(
    val projectKey: String,
    val versionName: String,
    val versionStatus: String,
    val releaseDate: LocalDate?,
    val issues: List<ReleaseNoteIssue>,
)

/**
 * 릴리즈 노트 이슈 항목 모델.
 *
 * generator 가 Markdown 항목을 조립하는 데 필요한 최소 정보만 포함한다.
 *
 * @property key 이슈 키. 예: `"ATLAS-1"`.
 * @property summary 이슈 제목. 줄바꿈 문자는 generator 가 공백으로 치환한다.
 * @property typeKey 이슈 타입 슬러그 키. 그룹핑 기준. 예: `"bug"`.
 * @property typeName 이슈 타입 표시 이름. 그룹 헤더에 사용. 예: `"Bug"`.
 * @property hierarchyLevel 이슈 유형 계층 깊이. 그룹 정렬 기준. epic=1, task/story/bug=0, subtask=-1.
 * @property resolutionName 해결 이름. null 이면 항목에 미표시. 예: `"Fixed"`.
 */
data class ReleaseNoteIssue(
    val key: String,
    val summary: String,
    val typeKey: String,
    val typeName: String,
    val hierarchyLevel: Int,
    val resolutionName: String?,
)

// ── 생성기 ────────────────────────────────────────────────────────────────────

/**
 * 버전 릴리즈 노트 Markdown 조립기.
 *
 * 순수 함수 — repository, DB, Spring 컨텍스트 의존 없음.
 * [generate] 는 [ReleaseNotesInput] 을 받아 spec §5 Markdown 템플릿에 따른 문자열을 반환한다.
 */
object ReleaseNotesGenerator {
    /**
     * spec §5 Markdown 템플릿에 따라 릴리즈 노트 문자열을 생성한다.
     *
     * **생성 규칙.**
     * - 제목: `# {projectKey} {versionName} 릴리즈 노트`
     * - 메타 3줄: 상태 / 릴리즈일(null → `미지정`) / 포함 이슈 N건
     * - 이슈 0건 → 그룹 섹션 없이 `포함된 이슈가 없습니다.` 한 줄
     * - 이슈 있음 → 타입별 섹션: 그룹 순서는 (hierarchyLevel asc, typeName asc),
     *   그룹 헤더 `## {typeName} ({건수})`, 그룹 내 이슈는 key 오름차순
     * - 이슈 항목: `- {key} {summary}` + resolutionName 있으면 ` ({resolutionName})`
     * - summary 내 줄바꿈(`\r\n` / `\n` / `\r`) → 공백 치환
     *
     * @param input 생성에 필요한 메타·이슈 목록 입력.
     * @return 생성된 Markdown 문자열.
     */
    fun generate(input: ReleaseNotesInput): String {
        val sb = StringBuilder()
        appendTitle(sb, input)
        appendMeta(sb, input)
        appendIssueBody(sb, input.issues)
        return sb.toString().trimEnd()
    }

    // ── 내부 헬퍼 ─────────────────────────────────────────────────────────────

    private fun appendTitle(
        sb: StringBuilder,
        input: ReleaseNotesInput,
    ) {
        sb.appendLine("# ${input.projectKey} ${input.versionName} $TEMPLATE_TITLE_SUFFIX")
    }

    private fun appendMeta(
        sb: StringBuilder,
        input: ReleaseNotesInput,
    ) {
        val releaseDateText = input.releaseDate?.toString() ?: TEMPLATE_DATE_UNSPECIFIED
        sb.appendLine()
        sb.appendLine("- $TEMPLATE_STATUS_LABEL: ${input.versionStatus}")
        sb.appendLine("- $TEMPLATE_RELEASE_DATE_LABEL: $releaseDateText")
        sb.appendLine(
            "- $TEMPLATE_ISSUE_COUNT_LABEL: ${input.issues.size}$TEMPLATE_ISSUE_COUNT_UNIT",
        )
    }

    private fun appendIssueBody(
        sb: StringBuilder,
        issues: List<ReleaseNoteIssue>,
    ) {
        if (issues.isEmpty()) {
            sb.appendLine()
            sb.appendLine(TEMPLATE_NO_ISSUES)
            return
        }
        val groups = buildSortedGroups(issues)
        for ((groupKey, groupIssues) in groups) {
            appendGroup(sb, groupKey.typeName, groupIssues)
        }
    }

    private fun buildSortedGroups(issues: List<ReleaseNoteIssue>): Map<TypeGroupKey, List<ReleaseNoteIssue>> {
        return issues
            .groupBy { TypeGroupKey(it.typeKey, it.typeName, it.hierarchyLevel) }
            .entries
            .sortedWith(compareBy({ it.key.hierarchyLevel }, { it.key.typeName }))
            .associate { (key, groupIssues) ->
                key to groupIssues.sortedBy { it.key }
            }
    }

    private fun appendGroup(
        sb: StringBuilder,
        typeName: String,
        issues: List<ReleaseNoteIssue>,
    ) {
        sb.appendLine()
        sb.appendLine("## $typeName (${issues.size})")
        for (issue in issues) {
            sb.appendLine(buildIssueLine(issue))
        }
    }

    /**
     * 이슈 한 줄 항목 문자열을 조립한다.
     *
     * summary 내 줄바꿈을 공백으로 치환(항목 1줄 유지).
     * resolutionName 있으면 끝에 ` ({resolutionName})` 추가.
     *
     * @param issue 이슈 항목 모델.
     * @return `- {key} {sanitizedSummary}` 또는 `- {key} {sanitizedSummary} ({resolutionName})`.
     */
    private fun buildIssueLine(issue: ReleaseNoteIssue): String {
        val sanitized = sanitizeSummary(issue.summary)
        val resolution = issue.resolutionName?.let { " ($it)" } ?: ""
        return "- ${issue.key} $sanitized$resolution"
    }

    /**
     * summary 내 줄바꿈 문자를 공백으로 치환해 항목 1줄을 유지한다.
     *
     * 치환 순서: `\r\n` → ` `, 나머지 `\r` / `\n` → ` `.
     *
     * @param summary 원본 summary 문자열.
     * @return 줄바꿈이 공백으로 치환된 문자열.
     */
    private fun sanitizeSummary(summary: String): String =
        summary
            .replace("\r\n", " ")
            .replace("\r", " ")
            .replace("\n", " ")
}

// ── 내부 그룹 키 ──────────────────────────────────────────────────────────────

/**
 * 타입 그룹 정렬을 위한 복합 키.
 *
 * groupBy 키로 사용하며 동일 typeKey 의 이슈를 한 그룹으로 묶는다.
 *
 * @property typeKey 타입 슬러그 키. 그룹핑 기준.
 * @property typeName 타입 표시 이름. 그룹 헤더 + 정렬 보조 기준.
 * @property hierarchyLevel 계층 깊이. 주 정렬 기준(오름차순).
 */
private data class TypeGroupKey(
    val typeKey: String,
    val typeName: String,
    val hierarchyLevel: Int,
)
