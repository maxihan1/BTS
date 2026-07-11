// 파싱된 `/atlas` slash 명령어를 cross-BC 포트로 실행해 Block Kit 응답을 만드는 핸들러 (FR-SL-04 Task 6)

package com.bts.slack.command

import com.bts.shared.issue.IssueImportCommand
import com.bts.shared.issue.IssueImportPort
import com.bts.shared.issue.IssueImportResult
import com.bts.shared.issue.IssueUnfurlPort
import com.bts.shared.search.SlashIssueSearchPort
import com.bts.shared.search.SlashSearchOutcome
import com.bts.shared.search.SlashSearchQuery
import com.bts.slack.message.SlackBlockKitRenderer
import com.fasterxml.jackson.databind.node.ArrayNode
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * 파싱된 [SlashCommand]를 실행해 Slack ephemeral 응답용 Block Kit 블록 배열을 만든다 (FR-SL-04 Task 6).
 *
 * ## 권한은 전부 포트에 위임 (ADR D6)
 * 이 클래스는 권한 판정 로직을 직접 구현하지 않는다. [SlashCommand.View]는 [IssueUnfurlPort]가
 * fail-closed로 열람 가능한 이슈만 반환하고, [SlashCommand.Search]는 [SlashIssueSearchPort]
 * 구현체(search-export-import 어댑터→issue-tracking `IssueSearchPort`)가 SQL 수준에서
 * viewer가 볼 수 있는 이슈만 필터하며, [SlashCommand.Create]는 [IssueImportPort] 구현체가
 * `CREATE_ISSUE` 권한을 검증한다. 이 클래스가 직접 판단하는 것은 "포트 응답을 어떤 블록으로
 * 렌더할지"뿐이다.
 *
 * ## viewerUserId는 명시 파라미터로만 전달 — SecurityContext 참조 금지
 * [handle]은 [viewerUserId]를 호출자로부터 파라미터로 받아 모든 포트 호출에 그대로 전달한다.
 * `SecurityContextHolder`를 절대 참조하지 않는다 — 이 핸들러는 `@Async` 워커 스레드에서
 * 호출될 수 있고(Task 7), 서블릿 요청 스레드에 바인딩된 `SecurityContext`는 비동기 스레드로
 * 전파되지 않는다. Slack 사용자 → Atlas 사용자로의 매핑 해석(서명 검증 + DB 매핑)은 상위
 * 호출자(Task 7)의 책임이며, 이 클래스는 이미 해석된 [viewerUserId]만 받는다.
 *
 * @param issueUnfurlPort `/atlas view` 이슈 요약 카드 fail-closed 조회 cross-BC 포트.
 * @param slashIssueSearchPort `/atlas search` AQL 검색 cross-BC 포트.
 * @param issueImportPort `/atlas create` 이슈 생성 cross-BC 쓰기 포트.
 * @param renderer Slack Block Kit 렌더러.
 * @param atlasBaseUrl BTS 웹 기준 URL(`bts.atlas.base-url`). [SlackBlockKitRenderer]/
 *   `AtlasIssueUrlParser`와 동일 프로퍼티 키를 재사용한다 — `/atlas create` 성공 응답에 실을
 *   생성된 이슈 URL 조립에 쓴다.
 */
@Component
class SlashCommandHandlers(
    private val issueUnfurlPort: IssueUnfurlPort,
    private val slashIssueSearchPort: SlashIssueSearchPort,
    private val issueImportPort: IssueImportPort,
    private val renderer: SlackBlockKitRenderer,
    @param:Value("\${bts.atlas.base-url:}") private val atlasBaseUrl: String,
) {
    /**
     * 파싱된 [command]를 실행해 ephemeral 응답 블록 배열을 반환한다.
     *
     * @param command [SlashCommandParser]가 파싱한 명령. 실행 자체는 하지 않는다.
     * @param viewerUserId 명령을 실행하는 Atlas 사용자 UUID. 모든 포트 호출에 그대로 전달된다.
     * @return [SlackBlockKitRenderer]가 만든 블록 배열. `response_type=ephemeral` 응답의 `blocks`에 그대로 쓴다.
     */
    fun handle(
        command: SlashCommand,
        viewerUserId: UUID,
    ): ArrayNode =
        when (command) {
            is SlashCommand.Help -> renderer.renderHelp()
            is SlashCommand.View -> handleView(command, viewerUserId)
            is SlashCommand.Search -> handleSearch(command, viewerUserId)
            is SlashCommand.Create -> handleCreate(command, viewerUserId)
            is SlashCommand.UsageError -> renderer.renderError(command.reason)
        }

    /** [IssueUnfurlPort.getVisibleIssueCard]가 null이면(무권한/미존재) 오류 블록으로 폴백한다. */
    private fun handleView(
        command: SlashCommand.View,
        viewerUserId: UUID,
    ): ArrayNode {
        val view = issueUnfurlPort.getVisibleIssueCard(command.issueKey, viewerUserId)
        return if (view != null) renderer.renderIssueCard(view) else renderer.renderError(ISSUE_NOT_FOUND_MESSAGE)
    }

    /** [SlashIssueSearchPort.search] 결과를 목록 블록 또는 문법 오류 블록으로 렌더한다. */
    private fun handleSearch(
        command: SlashCommand.Search,
        viewerUserId: UUID,
    ): ArrayNode {
        val query =
            SlashSearchQuery(
                rawAql = command.aql,
                projectKey = command.projectKey,
                viewerUserId = viewerUserId,
                page = FIRST_PAGE,
                size = MAX_SEARCH_RESULTS,
            )
        return when (val outcome = slashIssueSearchPort.search(query)) {
            is SlashSearchOutcome.Success -> renderer.renderSearchResults(outcome.page.items, outcome.page.total)
            is SlashSearchOutcome.SyntaxError -> renderer.renderError("$SYNTAX_ERROR_PREFIX${outcome.reason}")
        }
    }

    /** [IssueImportPort.importIssue] 결과를 생성 완료 블록 또는 reasonCode별 한국어 오류 블록으로 렌더한다. */
    private fun handleCreate(
        command: SlashCommand.Create,
        viewerUserId: UUID,
    ): ArrayNode {
        val cmd =
            IssueImportCommand(
                projectKey = command.projectKey,
                requesterUserId = viewerUserId,
                summary = command.title,
            )
        return when (val result = issueImportPort.importIssue(cmd)) {
            is IssueImportResult.Success -> renderer.renderCreated(result.issueKey, issueUrl(result.issueKey))
            is IssueImportResult.Failure -> renderer.renderError(createFailureMessage(result.reasonCode))
        }
    }

    /** `{atlasBaseUrl}/issues/{issueKey}` — [SlackBlockKitRenderer]/`AtlasIssueUrlParser`와 동일 조립 규칙. */
    private fun issueUrl(issueKey: String): String = "${atlasBaseUrl.trimEnd('/')}/issues/$issueKey"

    /** [IssueImportResult.Failure.reasonCode]를 사람이 읽을 수 있는 한국어 메시지로 변환한다. */
    private fun createFailureMessage(reasonCode: String): String =
        CREATE_FAILURE_MESSAGES[reasonCode] ?: UNKNOWN_FAILURE_MESSAGE

    private companion object {
        /** `/atlas search` 한 회 응답에 담는 최대 결과 건수(Slack ephemeral 카드 가독성 상한). */
        const val MAX_SEARCH_RESULTS = 10

        /** [SlashSearchQuery.page] 요청 페이지 번호. slash 명령은 페이지네이션 UI가 없어 항상 첫 페이지만 조회한다. */
        const val FIRST_PAGE = 0

        const val ISSUE_NOT_FOUND_MESSAGE = "이슈를 찾을 수 없거나 접근 권한이 없습니다."
        const val SYNTAX_ERROR_PREFIX = "검색 문법 오류: "
        const val UNKNOWN_FAILURE_MESSAGE = "이슈 생성에 실패했습니다. 잠시 후 다시 시도해 주세요."

        /**
         * [IssueImportResult.Failure.reasonCode] → 한국어 오류 메시지 매핑.
         *
         * [IssueImportResult] companion에 정의된 reasonCode 상수 7종을 모두 포함한다. 매핑에 없는
         * (신규 추가 등으로 아직 이 맵이 갱신되지 않은) reasonCode는 [UNKNOWN_FAILURE_MESSAGE]로 폴백한다.
         */
        val CREATE_FAILURE_MESSAGES: Map<String, String> =
            mapOf(
                IssueImportResult.FORBIDDEN to "이슈를 생성할 권한이 없습니다.",
                IssueImportResult.NOT_FOUND to "프로젝트를 찾을 수 없습니다.",
                IssueImportResult.TYPE_NOT_FOUND to "이슈 유형을 찾을 수 없습니다.",
                IssueImportResult.WORKFLOW_NOT_CONFIGURED to "프로젝트에 워크플로우가 설정되어 있지 않습니다.",
                IssueImportResult.VALIDATION to "입력값이 올바르지 않습니다. 제목을 확인해 주세요.",
                IssueImportResult.ADAPTER_UNAVAILABLE to "이슈 생성 기능을 사용할 수 없습니다.",
                IssueImportResult.UNKNOWN to UNKNOWN_FAILURE_MESSAGE,
            )
    }
}
