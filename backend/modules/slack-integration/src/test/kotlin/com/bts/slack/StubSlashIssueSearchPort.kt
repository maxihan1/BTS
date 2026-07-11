// slack-integration 통합 테스트용 settable SlashIssueSearchPort stub — 다음 결과 시드 + 넘어온 쿼리 캡처 (FR-SL-04 Task 9)

package com.bts.slack

import com.bts.shared.search.IssueSearchPage
import com.bts.shared.search.SlashIssueSearchPort
import com.bts.shared.search.SlashSearchOutcome
import com.bts.shared.search.SlashSearchQuery
import org.springframework.stereotype.Component

/**
 * 통합 테스트가 `/atlas search` 결과를 명시 시드하고 넘어온 쿼리를 캡처하는 [SlashIssueSearchPort] stub
 * (FR-SL-04 Task 9).
 *
 * cross-BC AQL 검색 포트는 prod 에서 search-export-import `SlashIssueSearchAdapter` 가 제공하나 slack
 * test-boot 컨텍스트에는 실 구현이 없다. Task 6 이 추가한 `SlashCommandHandlers(@Component)` 가
 * 생성자에서 non-null [SlashIssueSearchPort] 를 요구하므로, 실 어댑터가 없으면 slack 모듈의 모든
 * `@SpringBootTest` 컨텍스트 로드가 `NoSuchBeanDefinitionException` 으로 깨진다. 이 stub 이 그 공백을 메운다.
 *
 * ## `@Component` — 컴포넌트 스캔으로 전역 등록
 * [StubIssueImportPort] 와 동일 근거다 — [SlackIntegrationTestBootApplication] 컴포넌트 스캔에 직접 잡혀
 * [SlackTestcontainersConfig] 를 `@Import` 하지 않는 [SlackContextLoadTest] 를 포함한 모든 test-boot
 * 컨텍스트가 이 빈을 얻는다. slack 모듈에 [SlashIssueSearchPort] 구현체가 이 stub 하나뿐이라 `@Primary` 는
 * 불필요하다.
 *
 * ## settable — 다음 결과 시드 + 쿼리 캡처
 * [nextOutcome] 를 테스트가 성공([SlashSearchOutcome.Success], 결과 페이지 포함) 또는 문법 오류
 * ([SlashSearchOutcome.SyntaxError])로 미리 정하고, [search] 는 넘어온 [SlashSearchQuery] 를 [lastQuery] 에
 * 캡처한 뒤 [nextOutcome] 를 돌려준다. 테스트는 캡처된 쿼리의 rawAql/projectKey/viewerUserId 가 파싱·매핑
 * 해석 결과와 일치하는지 검증한다. 기본값은 빈 결과 페이지라, 실 포트 default(fail-safe — 데이터 누출 0)와
 * 동일 방향이다.
 */
@Component
class StubSlashIssueSearchPort : SlashIssueSearchPort {
    /** [search] 가 돌려줄 결과. 테스트가 시나리오별로 시드한다(기본값 = 빈 결과 페이지). */
    @Volatile
    var nextOutcome: SlashSearchOutcome = defaultOutcome()

    /** [search] 에 마지막으로 넘어온 쿼리. 미호출이면 null. 테스트가 인자 정합을 검증한다. */
    @Volatile
    var lastQuery: SlashSearchQuery? = null

    /**
     * 넘어온 [query] 를 [lastQuery] 에 캡처하고 시드된 [nextOutcome] 를 반환한다.
     *
     * @param query 검색 쿼리. rawAql/projectKey/viewerUserId 를 테스트가 검증한다.
     * @return [nextOutcome].
     */
    override fun search(query: SlashSearchQuery): SlashSearchOutcome {
        lastQuery = query
        return nextOutcome
    }

    /** 테스트 간 상태 격리를 위해 시드/캡처를 기본값으로 되돌린다. */
    fun reset() {
        nextOutcome = defaultOutcome()
        lastQuery = null
    }

    private companion object {
        /** 기본 빈 페이지의 페이지 번호(0-base) — slash 명령은 항상 첫 페이지만 조회한다. */
        const val DEFAULT_PAGE = 0

        /** 기본 빈 페이지의 크기 — `SlashCommandHandlers.MAX_SEARCH_RESULTS` 와 동일한 ephemeral 상한. */
        const val DEFAULT_SIZE = 10

        /** 미시드 기본 결과 — 빈 결과 페이지(실 포트 default fail-safe 와 동일 방향, init/reset 공용). */
        fun defaultOutcome(): SlashSearchOutcome {
            return SlashSearchOutcome.Success(IssueSearchPage.empty(DEFAULT_PAGE, DEFAULT_SIZE))
        }
    }
}
