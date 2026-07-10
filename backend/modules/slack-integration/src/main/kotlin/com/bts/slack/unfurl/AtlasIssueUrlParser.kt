// Slack 메시지에 붙은 Atlas 이슈 URL에서 이슈키를 추출하는 파서 (FR-SL-03 Task 2)

package com.bts.slack.unfurl

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * `{atlasBaseUrl}/issues/{issueKey}` 형태의 Atlas 이슈 URL에서 이슈키를 추출한다 (FR-SL-03 스펙 S9).
 *
 * Slack `link_shared` 이벤트는 채널에 붙은 링크들을 원문 URL로 준다. 이 파서는 그 URL이 **우리 Atlas의
 * 이슈 상세 페이지**를 가리키는지 판정하고, 맞으면 이슈키를 뽑아낸다. 다른 도메인이거나 `/issues/` 경로가
 * 아니거나 이슈키 형식이 아니면 `null`(매칭 실패)로 수렴한다 — unfurl 후속 처리는 이 결과가 `null`이면
 * 해당 URL을 그냥 건너뛴다.
 *
 * ## trailing slash / 쿼리 / fragment 허용
 * `/issues/PROJ-123`, `/issues/PROJ-123/`, `/issues/PROJ-123?tab=comments`, `/issues/PROJ-123#comment-1`
 * 모두 `PROJ-123`으로 추출한다. 이슈키 정규식이 시작 지점부터 매칭되는 접두만 취하고 그 뒤 문자
 * (`/`, `?`, `#`)는 자연히 매칭 범위 밖이 되므로 별도 분기 없이 하나의 정규식으로 처리한다.
 *
 * ## base-url 미설정
 * [atlasBaseUrl]이 비어 있으면(미설정) 모든 URL이 매칭되지 않는다(`null`). Atlas 도메인을 특정할 수
 * 없는 상태에서 임의 URL을 이슈로 인식하면 오탐이므로, 미설정은 "전부 불일치"로 안전하게 수렴한다.
 *
 * @param atlasBaseUrl BTS 웹 기준 URL(`bts.atlas.base-url`). 끝 슬래시는 정규화한다.
 */
@Component
class AtlasIssueUrlParser(
    @param:Value("\${bts.atlas.base-url:}") private val atlasBaseUrl: String,
) {
    /**
     * 단일 [url]이 Atlas 이슈 URL이면 이슈키를, 아니면 `null`을 반환한다.
     */
    fun parse(url: String): String? {
        val normalizedBase = atlasBaseUrl.trimEnd('/')
        if (normalizedBase.isBlank()) return null

        val prefix = "$normalizedBase$ISSUES_PATH_SEGMENT"
        if (!url.startsWith(prefix)) return null

        val remainder = url.removePrefix(prefix)
        return ISSUE_KEY_PREFIX_REGEX.find(remainder)?.value
    }

    /**
     * [urls] 중 Atlas 이슈 URL로 매칭된 (url, issueKey) 쌍만 순서대로 반환한다. 매칭 실패 URL은
     * 결과에서 제외한다(전체 목록 크기가 아니라 매칭된 것만).
     */
    fun parseAll(urls: List<String>): List<AtlasIssueLink> =
        urls.mapNotNull { url -> parse(url)?.let { issueKey -> AtlasIssueLink(url, issueKey) } }

    private companion object {
        const val ISSUES_PATH_SEGMENT = "/issues/"

        /** [com.bts.issue.domain.IssueKey.REGEX]와 동일 형식(BC 격리로 직접 import 불가, 값만 복제). */
        val ISSUE_KEY_PREFIX_REGEX = Regex("^[A-Z][A-Z0-9]{1,9}-[1-9][0-9]*")
    }
}

/**
 * 매칭된 Atlas 이슈 URL과 그 이슈키.
 *
 * @property url 원문 URL(Slack이 보낸 그대로).
 * @property issueKey 추출된 "PROJ-123" 형태의 이슈키.
 */
data class AtlasIssueLink(
    val url: String,
    val issueKey: String,
)
