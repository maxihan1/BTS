// Slack slash 명령어 AQL 검색 어댑터 — SlashIssueSearchPort 구현, raw AQL 파싱 후 IssueSearchPort 위임 (FR-SL-04 Task 2)

package com.bts.search.slash

import com.bts.search.aql.AqlLexException
import com.bts.search.aql.AqlLexer
import com.bts.search.aql.AqlParser
import com.bts.search.aql.AqlSyntaxException
import com.bts.shared.search.IssueSearchPort
import com.bts.shared.search.IssueSearchQuery
import com.bts.shared.search.SlashIssueSearchPort
import com.bts.shared.search.SlashSearchOutcome
import com.bts.shared.search.SlashSearchQuery
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * [SlashIssueSearchPort]의 search-export-import BC 구현체 (FR-SL-04 Task 2).
 *
 * slack-integration BC는 BC 격리 원칙상 AQL 렉서/파서([AqlLexer]/[AqlParser])를 직접
 * gradle 의존할 수 없다. 이 어댑터가 [SlashSearchQuery.rawAql] 원문 문자열을 파싱해
 * [IssueSearchQuery]로 변환한 뒤 기존 [IssueSearchPort](issue-tracking 어댑터가 런타임 주입)에
 * 위임한다.
 *
 * ### 의존 방향
 *
 * ```
 * slack ──(port)──▶ shared-kernel ◀──(impl: 이 어댑터)── search-export-import ──(IssueSearchPort)──▶ issue-tracking
 * ```
 *
 * ### visibility 필터 책임 — 이 어댑터가 아니라 IssueSearchPort 구현체
 *
 * 이 어댑터는 raw AQL을 파싱해 [IssueSearchQuery]로 변환하고 [IssueSearchPort]에 그대로
 * 위임할 뿐, BROWSE 권한 검증이나 visibility 보안 술어 결합을 직접 수행하지 않는다.
 * 그 책임은 [IssueSearchPort] 구현체(issue-tracking 어댑터,
 * `com.bts.issue.adapter.outbound.search.IssueSearchAdapter`)가 SQL 수준에서 담당한다.
 *
 * ### 문법 오류는 예외가 아닌 결과 타입으로 — 두 예외 계층 모두 catch
 *
 * AQL 파싱은 렉서 단계([AqlLexer] → [AqlLexException])와 파서 단계([AqlParser] →
 * [AqlSyntaxException])로 나뉘며 두 예외는 공통 상위 타입이 없는 별개 RuntimeException
 * 계층이다([SearchController][com.bts.search.web.SearchController]가 겪은 B1 회귀와
 * 동일한 함정). 포트 계약([SlashIssueSearchPort] KDoc)이 "문법 오류는 예외가 아닌
 * [SlashSearchOutcome.SyntaxError] 값으로 전달"을 명시하므로 두 예외를 모두 catch해
 * 어댑터 밖으로 전파시키지 않는다. 예외 클래스명 문자열 매칭이 아닌 타입 catch를
 * 사용한다(같은 모듈 내부 타입이라 직접 접근 가능).
 *
 * ### 파싱 흐름 재사용, 공용 헬퍼 추출 보류
 *
 * [SearchController.search][com.bts.search.web.SearchController.search]와 동일한
 * `AqlLexer → AqlParser → IssueSearchQuery 조립` 흐름을 사용하지만 공용 헬퍼로 추출하지
 * 않았다. SearchController는 REST 요청/DTO/actor 인증 맥락(파싱 실패 시 예외를 던져
 * `SearchExceptionHandler`가 400으로 변환)이고, 이 어댑터는 cross-BC 포트 커맨드 객체
 * 맥락(파싱 실패를 예외가 아닌 결과 타입으로 반환)으로 오류 처리 계약 자체가 다르다.
 * 파싱 3줄(Lexer→Parser→Query 조립) 자체를 헬퍼로 뽑아도 두 호출부는 여전히 서로 다른
 * try/catch 블록을 감싸야 해 간접 참조 비용이 추출 이득보다 커 과설계로 판단해 보류한다.
 *
 * @param issueSearchPort AQL 검색 실행 포트(issue-tracking 어댑터가 런타임 주입).
 * @see SlashIssueSearchPort
 * @see IssueSearchPort
 */
@Component
class SlashIssueSearchAdapter(
    private val issueSearchPort: IssueSearchPort,
) : SlashIssueSearchPort {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Slack slash 커맨드 객체의 raw AQL 문자열을 파싱해 검색 결과를 반환한다.
     *
     * [SlashSearchQuery.rawAql]을 렉싱/파싱한 뒤 [IssueSearchPort.search]에 위임한다.
     * 파싱 실패(렉서·파서 모두)는 예외를 전파하지 않고 [SlashSearchOutcome.SyntaxError]로 반환한다.
     *
     * @param query slash 커맨드 객체. rawAql, projectKey, viewerUserId, page, size 포함.
     * @return 파싱/검색 성공 시 [SlashSearchOutcome.Success], AQL 문법 오류 시
     *   [SlashSearchOutcome.SyntaxError].
     */
    override fun search(query: SlashSearchQuery): SlashSearchOutcome =
        try {
            val tokens = AqlLexer(query.rawAql).tokenize()
            val parseResult = AqlParser(tokens).parse()
            val issueSearchQuery =
                IssueSearchQuery(
                    projectKey = query.projectKey,
                    ast = parseResult.ast,
                    sort = parseResult.sort,
                    viewerUserId = query.viewerUserId,
                    page = query.page,
                    size = query.size,
                )
            SlashSearchOutcome.Success(issueSearchPort.search(issueSearchQuery))
        } catch (e: AqlSyntaxException) {
            toSyntaxError(query.projectKey, e.message)
        } catch (e: AqlLexException) {
            toSyntaxError(query.projectKey, e.message)
        }

    /**
     * AQL 파싱 예외를 로그로 남기고 [SlashSearchOutcome.SyntaxError]로 변환한다.
     *
     * [AqlSyntaxException]과 [AqlLexException] 두 예외 타입이 공유하는 변환 로직이다.
     * rawAql 원문(사용자 입력)은 로그에 남기지 않는다 — 사유(오류 메시지)만 기록한다.
     *
     * @param projectKey 검색 대상 프로젝트 키.
     * @param reason 파싱 예외가 보고한 오류 메시지(nullable).
     * @return 사유가 채워진 [SlashSearchOutcome.SyntaxError].
     */
    private fun toSyntaxError(
        projectKey: String,
        reason: String?,
    ): SlashSearchOutcome.SyntaxError {
        log.info(
            "SLASH_SEARCH_SYNTAX_ERROR projectKey={} reason='{}'",
            projectKey,
            reason,
        )
        return SlashSearchOutcome.SyntaxError(reason ?: DEFAULT_SYNTAX_ERROR_REASON)
    }

    companion object {
        /** 파싱 예외 메시지가 없을 때 사용하는 기본 사유 문구. */
        private const val DEFAULT_SYNTAX_ERROR_REASON = "AQL 문법 오류"
    }
}
