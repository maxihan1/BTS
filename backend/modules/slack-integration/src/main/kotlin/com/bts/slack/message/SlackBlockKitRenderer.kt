// 알림 제목/이슈 링크·unfurl 카드·slash 명령 ephemeral 응답을 Slack Block Kit 으로 렌더하는 컴포넌트
// (FR-SL-02 Task 6 / FR-SL-03 Task 8 / FR-SL-04 Task 5)

package com.bts.slack.message

import com.bts.shared.issue.IssueUnfurlView
import com.bts.shared.search.IssueSearchHit
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * 알림 제목/이슈 키, Slack unfurl 카드, `/atlas` slash 명령 ephemeral 응답을 Slack Block Kit 메시지로
 * 렌더한다 (FR-SL-02 Task 6 / FR-SL-03 Task 8 / FR-SL-04 Task 5).
 *
 * [render]는 인앱 알림과 동일 수준(제목 + 이슈 링크)만 렌더한다. [renderUnfurlCard]는 Slack `link_shared`
 * unfurl용 이슈 스냅샷 카드(키·제목·상태·우선순위·담당자)를 렌더한다 — cross-BC 조회 결과([IssueUnfurlView])를
 * 그대로 소비한다.
 *
 * ## slash 명령 ephemeral 렌더 (FR-SL-04 Task 5)
 * [renderHelp]/[renderIssueCard]/[renderSearchResults]/[renderCreated]/[renderError]는 모두 `/atlas`
 * slash 명령의 응답 전용이며, [ArrayNode](블록 배열)를 반환한다 — Slack이 지원하는 `response_type`
 * 중 `ephemeral`(호출자 본인에게만 보이는 응답)만 사용하는 [SlackResponseUrlClient.post]의 `blocks`
 * 파라미터에 바로 넘길 수 있는 계약이다. [renderUnfurlCard]/[render]처럼 `{"blocks": [...]}`로 감싼
 * [ObjectNode]를 반환하지 않는다 — 소비처가 다르기 때문이다.
 *
 * ## 이슈 링크
 * `{atlasBaseUrl}/issues/{issueKey}` 로 이슈 상세 페이지를 mrkdwn 링크로 건다. `atlasBaseUrl`은
 * `bts.atlas.base-url` 로 주입한다(미설정이어도 부팅이 깨지지 않도록 빈 문자열 기본값 — 실제 발송 시점에만
 * 의미가 있고, notification 파이프라인 발화는 D6 연결 이후이므로 운영 배포 시 값을 채운다).
 *
 * ## unfurl 카드 = 정보 카드만 (ADR D3)
 * [renderUnfurlCard]는 상태 변경·댓글 같은 액션 버튼을 절대 포함하지 않는다. 버튼 상호작용은
 * FR-SL-05(인터랙티브)의 책임이라 이 메서드가 선점하면 BC 스코프가 번진다.
 *
 * @param atlasBaseUrl BTS 웹 기준 URL(`bts.atlas.base-url`). 끝 슬래시는 정규화한다.
 * @param objectMapper Block Kit JSON 직렬화용 Jackson.
 */
@Component
class SlackBlockKitRenderer(
    @param:Value("\${bts.atlas.base-url:}") private val atlasBaseUrl: String,
    private val objectMapper: ObjectMapper,
) {
    /**
     * 제목과 (선택) 이슈 키로 Block Kit 메시지를 렌더한다.
     *
     * @param title 알림 제목(폴백 text 겸 section 본문).
     * @param issueKey 이슈 키. null 이면 링크 없이 제목만 렌더한다.
     * @return 폴백 text + blocks JSON.
     */
    fun render(
        title: String,
        issueKey: String?,
    ): RenderedSlackMessage {
        val mrkdwn =
            if (issueKey != null) {
                "$title\n<${issueUrl(issueKey)}|$issueKey 보기>"
            } else {
                title
            }

        val blocks =
            objectMapper.createArrayNode().apply {
                add(sectionBlock(mrkdwnText(mrkdwn)))
            }

        return RenderedSlackMessage(
            text = title,
            blocks = objectMapper.writeValueAsString(blocks),
        )
    }

    /**
     * Slack unfurl용 이슈 스냅샷 카드를 렌더한다 (FR-SL-03 Task 8).
     *
     * 카드는 정확히 두 블록이다 — (1) 키+제목 링크 섹션, (2) 상태·우선순위·담당자 필드 섹션. 담당자가
     * 없으면(`assigneeDisplayName == null`) "미지정"으로 폴백한다. 액션 버튼은 절대 포함하지 않는다(ADR D3).
     *
     * @param view cross-BC 결합 포트([com.bts.shared.issue.IssueUnfurlPort])가 확인한 열람 가능 이슈 스냅샷.
     * @return `{"blocks": [...]}` 형태의 카드 JSON — [com.bts.slack.message.SlackUnfurlClient]가 URL 키에
     *   매핑해 `chat.unfurl`의 `unfurls`에 싣는다.
     */
    fun renderUnfurlCard(view: IssueUnfurlView): ObjectNode =
        objectMapper.createObjectNode().apply {
            set<ArrayNode>(BLOCKS_FIELD, issueSummaryBlocks(view))
        }

    /**
     * `/atlas view <이슈키>` 응답으로 이슈 요약 카드를 렌더한다 (FR-SL-04 Task 5).
     *
     * [renderUnfurlCard]와 동일한 두 블록(키+제목 링크 섹션, 상태·우선순위·담당자 필드 섹션)을
     * 만들지만, `{"blocks": [...]}`로 감싸지 않고 [ArrayNode] 그대로 반환한다 —
     * [SlackResponseUrlClient.post]의 `blocks` 파라미터 계약에 맞추기 위해서다.
     *
     * @param view cross-BC 결합 포트([com.bts.shared.issue.IssueUnfurlPort])가 확인한 열람 가능 이슈 스냅샷.
     * @return 이슈 요약 블록 배열.
     */
    fun renderIssueCard(view: IssueUnfurlView): ArrayNode = issueSummaryBlocks(view)

    /**
     * `/atlas help`, 빈 명령, 미지원 서브커맨드에 공용으로 쓰는 사용법 안내를 렌더한다 (FR-SL-04 Task 5).
     *
     * 4종 서브커맨드(`help`/`view`/`search`/`create`) 사용법을 한 section 블록에 담는다.
     *
     * @param includeAccountLinkNotice `true`면 Slack 사용자·Atlas 계정이 아직 연결되지 않아 실행을 이어갈
     *   수 없는 경우의 계정 연결 안내를 context 블록으로 덧붙인다(T6/T7이 미매핑 사용자 케이스에서 사용).
     * @return 사용법 안내 블록 배열.
     */
    fun renderHelp(includeAccountLinkNotice: Boolean = false): ArrayNode =
        objectMapper.createArrayNode().apply {
            add(sectionBlock(mrkdwnText(helpUsageText())))
            if (includeAccountLinkNotice) {
                add(contextBlock(accountLinkNoticeText()))
            }
        }

    /**
     * `/atlas search <프로젝트키> <AQL>` 응답으로 검색 결과 목록을 렌더한다 (FR-SL-04 Task 5).
     *
     * [hits]가 비어 있으면 "결과 없음" 안내 한 블록만 반환한다. 그렇지 않으면 결과 건마다 키·제목·
     * 상태를 담은 section 블록을 만들고, 마지막에 `표시 건수/전체 건수`를 알리는 context 블록을 덧붙인다.
     *
     * @param hits 검색 결과 페이지의 이슈 목록([com.bts.shared.search.IssueSearchPage.items]).
     * @param total 조건에 맞는 이슈 전체 건수([com.bts.shared.search.IssueSearchPage.total]).
     * @return 검색 결과 블록 배열.
     */
    fun renderSearchResults(
        hits: List<IssueSearchHit>,
        total: Long,
    ): ArrayNode {
        val blocks = objectMapper.createArrayNode()
        if (hits.isEmpty()) {
            blocks.add(sectionBlock(mrkdwnText(NO_RESULTS_TEXT)))
            return blocks
        }
        hits.forEach { hit -> blocks.add(sectionBlock(mrkdwnText(searchHitText(hit)))) }
        blocks.add(contextBlock("${hits.size}/$total 표시"))
        return blocks
    }

    /**
     * `/atlas create <프로젝트키> <제목>` 성공 응답을 렌더한다 (FR-SL-04 Task 5).
     *
     * @param issueKey 생성된 이슈 키.
     * @param issueUrl 생성된 이슈 상세 페이지 URL(호출부가 이미 계산해 전달 — 생성 직후 응답이라
     *   [issueUrl] 프라이빗 헬퍼를 다시 타지 않고 그대로 받는다).
     * @return 생성 완료 안내 블록 배열.
     */
    fun renderCreated(
        issueKey: String,
        issueUrl: String,
    ): ArrayNode =
        objectMapper.createArrayNode().apply {
            add(sectionBlock(mrkdwnText("이슈를 만들었습니다: <$issueUrl|$issueKey>")))
        }

    /**
     * 사용법 오류·AQL 문법 오류·무권한 등 slash 명령 실행 중 발생한 오류를 안내하는 단순 텍스트를
     * 렌더한다 (FR-SL-04 Task 5).
     *
     * @param message 사용자에게 그대로 노출 가능한 오류/안내 메시지(내부 구현 세부사항 미포함 — 호출부 책임).
     * @return 오류 안내 블록 배열.
     */
    fun renderError(message: String): ArrayNode =
        objectMapper.createArrayNode().apply {
            add(sectionBlock(mrkdwnText(message)))
        }

    /**
     * [renderUnfurlCard]/[renderIssueCard]가 공유하는 이슈 요약 두 블록을 만든다 — (1) 키+제목 링크
     * 섹션, (2) 상태·우선순위·담당자 필드 섹션. 담당자가 없으면(`assigneeDisplayName == null`)
     * "미지정"으로 폴백한다. 액션 버튼은 절대 포함하지 않는다(ADR D3).
     */
    private fun issueSummaryBlocks(view: IssueUnfurlView): ArrayNode {
        val titleSection = sectionBlock(mrkdwnText("<${issueUrl(view.issueKey)}|${view.issueKey}> ${view.summary}"))

        val assigneeLabel = view.assigneeDisplayName ?: UNASSIGNED_LABEL
        val fields =
            objectMapper.createArrayNode().apply {
                add(mrkdwnText("*상태*\n${view.statusLabel}"))
                add(mrkdwnText("*우선순위*\n${view.priorityLabel}"))
                add(mrkdwnText("*담당자*\n$assigneeLabel"))
            }
        val detailSection =
            objectMapper.createObjectNode().apply {
                put(TYPE_FIELD, SECTION_BLOCK_TYPE)
                set<ArrayNode>(FIELDS_FIELD, fields)
            }

        return objectMapper.createArrayNode().apply {
            add(titleSection)
            add(detailSection)
        }
    }

    /** [renderHelp]의 4종 서브커맨드 사용법 mrkdwn 텍스트. */
    private fun helpUsageText(): String =
        listOf(
            "*Atlas 명령어*",
            "`/atlas help` — 명령어 사용법 안내",
            "`/atlas view <이슈키>` — 이슈 요약 카드 조회",
            "`/atlas search <프로젝트키> <AQL>` — 이슈 검색",
            "`/atlas create <프로젝트키> <제목>` — 이슈 생성",
        ).joinToString("\n")

    /** [renderHelp]의 계정 연결 안내 텍스트 — `{atlasBaseUrl}/settings/slack` 링크를 포함한다. */
    private fun accountLinkNoticeText(): String =
        "Slack 계정이 Atlas 사용자와 연결되어 있지 않습니다. " +
            "<${atlasBaseUrl.trimEnd('/')}$ACCOUNT_LINK_PATH|여기서 연결>하세요."

    /** [renderSearchResults]의 결과 행 한 건 텍스트 — 키+제목 링크와 상태 키를 담는다. */
    private fun searchHitText(hit: IssueSearchHit): String =
        "<${issueUrl(hit.key)}|${hit.key}> ${hit.summary} — ${hit.currentStateKey}"

    /** `{atlasBaseUrl}/issues/{issueKey}` — base URL 끝 슬래시를 제거해 이중 슬래시를 막는다. */
    private fun issueUrl(issueKey: String): String = "${atlasBaseUrl.trimEnd('/')}/issues/$issueKey"

    /** `{"type": "section", "text": text}` — 단일 text를 담은 Block Kit section block. */
    private fun sectionBlock(text: ObjectNode): ObjectNode =
        objectMapper.createObjectNode().apply {
            put(TYPE_FIELD, SECTION_BLOCK_TYPE)
            set<ObjectNode>(TEXT_FIELD, text)
        }

    /** `{"type": "context", "elements": [mrkdwnText(text)]}` — 부가 안내용 Block Kit context block. */
    private fun contextBlock(text: String): ObjectNode =
        objectMapper.createObjectNode().apply {
            put(TYPE_FIELD, CONTEXT_BLOCK_TYPE)
            set<ArrayNode>(ELEMENTS_FIELD, objectMapper.createArrayNode().apply { add(mrkdwnText(text)) })
        }

    /** `{"type": "mrkdwn", "text": text}` — Block Kit text object. */
    private fun mrkdwnText(text: String): ObjectNode =
        objectMapper.createObjectNode().apply {
            put(TYPE_FIELD, MRKDWN_TEXT_TYPE)
            put(TEXT_FIELD, text)
        }

    private companion object {
        const val UNASSIGNED_LABEL = "미지정"
        const val NO_RESULTS_TEXT = "결과 없음"
        const val ACCOUNT_LINK_PATH = "/settings/slack"
        const val TYPE_FIELD = "type"
        const val TEXT_FIELD = "text"
        const val BLOCKS_FIELD = "blocks"
        const val FIELDS_FIELD = "fields"
        const val ELEMENTS_FIELD = "elements"
        const val SECTION_BLOCK_TYPE = "section"
        const val CONTEXT_BLOCK_TYPE = "context"
        const val MRKDWN_TEXT_TYPE = "mrkdwn"
    }
}
