// Slack `/atlas` slash 명령어의 파싱 결과를 표현하는 값 객체(VO) 계층 (FR-SL-04 Task 3)

package com.bts.slack.command

/**
 * `/atlas <sub> <args>` 형태의 Slack slash 명령어를 파싱한 결과.
 *
 * [SlashCommandParser.parse]의 반환 타입이다. 파서는 cross-BC 의존이 없는 순수 텍스트 파싱만
 * 수행하며, 실제 실행(권한 검증·이슈 조회/생성)은 후속 핸들러(FR-SL-04 Task 6)가 shared-kernel
 * 포트를 통해 담당한다.
 *
 * @see SlashCommandParser
 */
sealed interface SlashCommand {
    /**
     * 사용법 안내. 빈 명령·`help`·미지원 서브커맨드가 모두 여기로 수렴한다(발견성 확보 — 오타를
     * 쳐도 사용자가 막다른 실패 대신 안내를 받는다, FR-SL-04 ADR D2).
     */
    data object Help : SlashCommand

    /**
     * 이슈 요약 카드 조회. `/atlas view <KEY>`.
     *
     * @property issueKey 조회할 이슈 키. 형식·존재·가시성 검증은 실행 단계(`IssueUnfurlPort`)의
     *   책임이며, 파서는 빈 문자열이 아님만 보장한다.
     */
    data class View(
        val issueKey: String,
    ) : SlashCommand

    /**
     * AQL(Atlas Query Language — BTS 이슈 검색용 쿼리 문법) 검색. `/atlas search <PROJECT> <aql>`.
     *
     * 프로젝트 스코프는 인라인 인자로 필수 지정한다(채널→프로젝트 매핑(FR-SL-06)이 아직 없는
     * 상태에서도 이 FR이 자족적으로 동작하도록 한 결정, ADR D3).
     *
     * @property projectKey 검색 대상 프로젝트 키.
     * @property aql 프로젝트 키 뒤에 남은 문자열 전체(내부 공백 보존). AQL 문법 자체의 파싱은
     *   search-export-import BC가 소유하며, 이 파서는 원문 문자열만 잘라낸다(`SlashIssueSearchPort` 경유).
     */
    data class Search(
        val projectKey: String,
        val aql: String,
    ) : SlashCommand

    /**
     * 이슈 생성. `/atlas create <PROJECT> <title>`.
     *
     * @property projectKey 생성 대상 프로젝트 키.
     * @property title 이슈 제목. 양끝을 감싼 쌍따옴표(`"..."`)는 파서가 제거한다.
     */
    data class Create(
        val projectKey: String,
        val title: String,
    ) : SlashCommand

    /**
     * 사용법 오류. 필수 인자 누락, 입력 길이 상한 초과 등 파싱 단계에서 확정할 수 있는 오류를 담는다.
     *
     * @property reason 사용자에게 그대로 노출 가능한 사용법 안내 메시지. 내부 구현 세부사항(스택트레이스 등)은
     *   담지 않는다.
     */
    data class UsageError(
        val reason: String,
    ) : SlashCommand
}
