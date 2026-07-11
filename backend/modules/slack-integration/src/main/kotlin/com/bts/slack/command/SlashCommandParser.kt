// Slack `/atlas <sub> <args>` slash 명령어 텍스트를 SlashCommand로 파싱하는 순수 로직 (FR-SL-04 Task 3)

package com.bts.slack.command

import org.springframework.stereotype.Component

/**
 * Slack이 `POST /slack/commands`로 보내는 `text` 필드(`/atlas` 뒤의 나머지 전체)를 [SlashCommand]로
 * 파싱한다.
 *
 * ## 순수 로직 — cross-BC 의존 0
 * 이 클래스는 문자열만 다루는 stateless 파서다. issue-tracking/search-export-import 등 다른 BC를
 * 전혀 참조하지 않는다. 프로젝트 키가 실제 존재하는지, 이슈키가 유효한지 같은 도메인 검증은
 * 후속 핸들러(FR-SL-04 Task 6)가 shared-kernel 포트로 수행한다 — 파서는 텍스트 형식만 본다.
 *
 * ## 프로젝트 스코프는 인라인 인자 필수 (ADR FR-SL-04 D3)
 * `search`/`create`는 두 번째 토큰을 프로젝트 키로 **강제**한다. 채널→프로젝트 매핑(FR-SL-06)이
 * 아직 없어, 인라인 인자가 이 FR 안에서 자족적으로 프로젝트를 특정하는 유일한 방법이다.
 *
 * ## 미지원/빈 입력은 Help로 폴백
 * 빈 문자열·공백만 있는 입력·알 수 없는 서브커맨드는 모두 [SlashCommand.Help]로 수렴한다.
 * 슬래시 명령은 오타를 쳐도 막다른 실패보다 사용법 안내를 보여주는 편이 발견성(discoverability)에
 * 유리하다(ADR D2).
 *
 * ## 인자 누락은 UsageError
 * `view`/`search`/`create`에 필요한 인자가 부족하면 [SlashCommand.UsageError]를 반환한다. Help로
 * 뭉뚱그리지 않는 이유는, 사용자가 서브커맨드까지는 올바르게 입력했으므로 "무엇이 빠졌는지"를
 * 구체적으로 알려주는 편이 더 도움이 되기 때문이다.
 *
 * ## 길이 상한 = 절단이 아닌 거부
 * `text`/`aql`/`title` 각각 상한을 넘으면 **잘라내지 않고 [SlashCommand.UsageError]로 거부**한다.
 * 절단은 AQL 조건이나 제목을 사용자 의도와 다르게 바꿔버릴 수 있어(예: AQL 조건 중간 절단으로
 * 문법상 유효하지만 의미가 달라진 쿼리), 명시적 오류로 알리는 편이 안전하다(NFR-3, DoS 방지 겸용).
 */
@Component
class SlashCommandParser {
    /**
     * Slack slash 명령어 `text`를 [SlashCommand]로 파싱한다.
     *
     * @param text `/atlas` 뒤에 붙은 나머지 전체 원문(예: `"search PROJ status=open"`).
     * @return 파싱 결과. 형식 오류 없이 항상 값을 반환한다(예외를 던지지 않음).
     */
    fun parse(text: String): SlashCommand {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return SlashCommand.Help
        if (trimmed.length > MAX_TEXT_LENGTH) {
            return SlashCommand.UsageError("입력이 너무 깁니다 (최대 ${MAX_TEXT_LENGTH}자).")
        }

        val parts = trimmed.split(WHITESPACE_REGEX, limit = 2)
        val subcommand = parts[0].lowercase()
        val rest = parts.getOrNull(1)?.trim().orEmpty()

        return when (subcommand) {
            HELP -> SlashCommand.Help
            VIEW -> parseView(rest)
            SEARCH -> parseSearch(rest)
            CREATE -> parseCreate(rest)
            else -> SlashCommand.Help
        }
    }

    private fun parseView(rest: String): SlashCommand {
        if (rest.isEmpty()) {
            return SlashCommand.UsageError("이슈 키가 필요합니다. 사용법: /atlas view <이슈키>")
        }
        val issueKey = rest.split(WHITESPACE_REGEX, limit = 2)[0]
        return SlashCommand.View(issueKey)
    }

    private fun parseSearch(rest: String): SlashCommand {
        if (rest.isEmpty()) {
            return SlashCommand.UsageError("프로젝트 키가 필요합니다. 사용법: /atlas search <프로젝트키> <검색조건>")
        }
        val parts = rest.split(WHITESPACE_REGEX, limit = 2)
        val projectKey = parts[0]
        val aql = parts.getOrNull(1)?.trim().orEmpty()
        if (aql.isEmpty()) {
            return SlashCommand.UsageError("검색 조건이 필요합니다. 사용법: /atlas search <프로젝트키> <검색조건>")
        }
        if (aql.length > MAX_AQL_LENGTH) {
            return SlashCommand.UsageError("검색 조건이 너무 깁니다 (최대 ${MAX_AQL_LENGTH}자).")
        }
        return SlashCommand.Search(projectKey, aql)
    }

    private fun parseCreate(rest: String): SlashCommand {
        if (rest.isEmpty()) {
            return SlashCommand.UsageError("프로젝트 키가 필요합니다. 사용법: /atlas create <프로젝트키> <제목>")
        }
        val parts = rest.split(WHITESPACE_REGEX, limit = 2)
        val projectKey = parts[0]
        val rawTitle = parts.getOrNull(1)?.trim().orEmpty()
        if (rawTitle.isEmpty()) {
            return SlashCommand.UsageError("제목이 필요합니다. 사용법: /atlas create <프로젝트키> <제목>")
        }
        val title = stripSurroundingQuotes(rawTitle)
        if (title.length > MAX_TITLE_LENGTH) {
            return SlashCommand.UsageError("제목이 너무 깁니다 (최대 ${MAX_TITLE_LENGTH}자).")
        }
        return SlashCommand.Create(projectKey, title)
    }

    /** 양끝이 모두 쌍따옴표(`"`)로 감싸져 있으면 제거한다. 한쪽만 있거나 없으면 원문 그대로 둔다. */
    private fun stripSurroundingQuotes(value: String): String =
        if (value.length >= 2 && value.startsWith(QUOTE) && value.endsWith(QUOTE)) {
            value.substring(1, value.length - 1)
        } else {
            value
        }

    private companion object {
        const val HELP = "help"
        const val VIEW = "view"
        const val SEARCH = "search"
        const val CREATE = "create"
        const val QUOTE = "\""

        const val MAX_TEXT_LENGTH = 4000
        const val MAX_AQL_LENGTH = 2000
        const val MAX_TITLE_LENGTH = 500

        val WHITESPACE_REGEX = Regex("\\s+")
    }
}
