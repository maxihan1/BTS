// SlashCommandParser 단위 테스트 — `/atlas <sub> <args>` 텍스트 파싱 케이스 검증 (FR-SL-04 Task 3)

package com.bts.slack.command

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * [SlashCommandParser] 단위 테스트 (FR-SL-04 Task 3).
 *
 * cross-BC 의존이 없는 순수 파싱 로직이라 Spring 컨텍스트 없이 직접 인스턴스화한다.
 */
class SlashCommandParserTest {
    private val parser = SlashCommandParser()

    @Test
    fun `빈 문자열은 Help로 수렴한다`() {
        assertThat(parser.parse("")).isEqualTo(SlashCommand.Help)
    }

    @Test
    fun `공백만 있는 문자열은 Help로 수렴한다`() {
        assertThat(parser.parse("   ")).isEqualTo(SlashCommand.Help)
    }

    @Test
    fun `help 서브커맨드는 Help를 반환한다`() {
        assertThat(parser.parse("help")).isEqualTo(SlashCommand.Help)
    }

    @Test
    fun `view 서브커맨드는 이슈키를 담은 View를 반환한다`() {
        assertThat(parser.parse("view PROJ-1")).isEqualTo(SlashCommand.View("PROJ-1"))
    }

    @Test
    fun `search 서브커맨드는 프로젝트키와 aql을 담은 Search를 반환한다`() {
        assertThat(parser.parse("search PROJ status=open"))
            .isEqualTo(SlashCommand.Search(projectKey = "PROJ", aql = "status=open"))
    }

    @Test
    fun `search는 프로젝트키 뒤 나머지 전체를 aql로 보존한다`() {
        assertThat(parser.parse("search PROJ status = open AND priority = high"))
            .isEqualTo(SlashCommand.Search(projectKey = "PROJ", aql = "status = open AND priority = high"))
    }

    @Test
    fun `create 서브커맨드는 여러 단어 제목을 그대로 담은 Create를 반환한다`() {
        assertThat(parser.parse("create PROJ 로그인 버튼 동작 안 함"))
            .isEqualTo(SlashCommand.Create(projectKey = "PROJ", title = "로그인 버튼 동작 안 함"))
    }

    @Test
    fun `create는 감싼 쌍따옴표를 제거한 제목을 담는다`() {
        assertThat(parser.parse("create PROJ \"따옴표 감싼 제목\""))
            .isEqualTo(SlashCommand.Create(projectKey = "PROJ", title = "따옴표 감싼 제목"))
    }

    @Test
    fun `미지원 서브커맨드는 Help로 폴백한다`() {
        assertThat(parser.parse("frobnicate does not exist")).isEqualTo(SlashCommand.Help)
    }

    @Test
    fun `search에 프로젝트키가 없으면 UsageError를 반환한다`() {
        val result = parser.parse("search")

        assertThat(result).isInstanceOf(SlashCommand.UsageError::class.java)
    }

    @Test
    fun `search에 aql이 없으면 UsageError를 반환한다`() {
        val result = parser.parse("search PROJ")

        assertThat(result).isInstanceOf(SlashCommand.UsageError::class.java)
    }

    @Test
    fun `create에 제목이 없으면 UsageError를 반환한다`() {
        val result = parser.parse("create PROJ")

        assertThat(result).isInstanceOf(SlashCommand.UsageError::class.java)
    }

    @Test
    fun `view에 이슈키가 없으면 UsageError를 반환한다`() {
        val result = parser.parse("view")

        assertThat(result).isInstanceOf(SlashCommand.UsageError::class.java)
    }

    @Test
    fun `서브커맨드 대소문자를 무시한다 - SEARCH`() {
        assertThat(parser.parse("SEARCH PROJ status=open"))
            .isEqualTo(SlashCommand.Search(projectKey = "PROJ", aql = "status=open"))
    }

    @Test
    fun `서브커맨드 대소문자를 무시한다 - Create`() {
        assertThat(parser.parse("Create PROJ 제목"))
            .isEqualTo(SlashCommand.Create(projectKey = "PROJ", title = "제목"))
    }

    @Test
    fun `서브커맨드 대소문자를 무시한다 - View`() {
        assertThat(parser.parse("View PROJ-1")).isEqualTo(SlashCommand.View("PROJ-1"))
    }

    @Test
    fun `text 길이 상한을 초과하면 UsageError를 반환한다`() {
        val tooLong = "help " + "a".repeat(4000)

        val result = parser.parse(tooLong)

        assertThat(result).isInstanceOf(SlashCommand.UsageError::class.java)
    }
}
