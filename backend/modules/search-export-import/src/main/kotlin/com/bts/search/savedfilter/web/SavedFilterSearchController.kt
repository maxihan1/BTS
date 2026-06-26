// 저장된 필터 실행 REST 컨트롤러 — GET /api/v1/filters/{id}/search (viewer 권한 재실행, FR-SR-03)

package com.bts.search.savedfilter.web

import com.bts.search.aql.AqlLexer
import com.bts.search.aql.AqlParser
import com.bts.search.savedfilter.application.SavedFilterService
import com.bts.search.web.dto.AqlSearchHit
import com.bts.shared.search.IssueSearchPort
import com.bts.shared.search.IssueSearchQuery
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * 저장된 필터를 viewer 권한으로 재실행하는 REST 컨트롤러.
 *
 * 엔드포인트.
 * - `GET /api/v1/filters/{id}/search` — 저장된 필터의 AQL을 현재 사용자 권한으로 실행한다.
 *
 * ### 처리 흐름
 * 1. actor 추출([SavedFilterActorExtractor]) — 리소스 조회보다 먼저(probe 차단).
 * 2. 필터 로드([SavedFilterService.getByIdForOwner]) — 가시성 게이트. 비가시 시 404.
 * 3. AQL 파싱([AqlLexer]+[AqlParser]) — 저장된 쿼리를 AST로 변환.
 * 4. 검색 위임([IssueSearchPort.search]) — **viewerUserId=actor**로 실행하여 viewer의
 *    visibility 보안 술어가 자동 결합된다(권한 상승 불가, FR-SR-02 패턴 재사용).
 * 5. 응답 — `Page<AqlSearchHit>` raw Page([com.bts.search.web.SearchController]와 동형).
 *
 * 예외→HTTP 매핑은 [SavedFilterExceptionHandler](basePackages 커버)가 처리한다.
 *
 * @param service 저장된 필터 애플리케이션 서비스(필터 로드·가시성).
 * @param issueSearchPort AQL 검색 실행 포트(issue-tracking 어댑터 주입).
 */
@RestController
class SavedFilterSearchController(
    private val service: SavedFilterService,
    private val issueSearchPort: IssueSearchPort,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 저장된 필터를 viewer 권한으로 실행한다.
     *
     * @param id 실행할 필터 식별자.
     * @param page 0-base 페이지 번호.
     * @param size 페이지 크기.
     * @return 200 OK + `Page<AqlSearchHit>`.
     */
    @GetMapping("/api/v1/filters/{id}/search")
    fun search(
        @PathVariable id: UUID,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): ResponseEntity<Page<AqlSearchHit>> {
        val actorId = SavedFilterActorExtractor.extract()
        validatePaging(page, size)
        val filter = service.getByIdForOwner(id, actorId)
        log.info("SavedFilterSearchController.search id={} actor={} projectKey={}", id, actorId, filter.projectKey)

        val tokens = AqlLexer(filter.aqlQuery).tokenize()
        val parseResult = AqlParser(tokens).parse()
        val query =
            IssueSearchQuery(
                projectKey = filter.projectKey,
                ast = parseResult.ast,
                sort = parseResult.sort,
                viewerUserId = actorId,
                page = page,
                size = size,
            )

        val searchPage = issueSearchPort.search(query)
        val hits = searchPage.items.map { AqlSearchHit.from(it) }
        val result: Page<AqlSearchHit> = PageImpl(hits, PageRequest.of(page, size), searchPage.total)
        return ResponseEntity.ok(result)
    }

    /**
     * 페이지네이션 파라미터를 검증한다(DoS 방어 — spec NFR-3, SearchController와 동일 정책).
     *
     * @param page 0-base 페이지 번호(0 이상).
     * @param size 페이지 크기(1..100).
     * @throws ResponseStatusException 400 page<0 또는 size 범위 밖.
     */
    private fun validatePaging(
        page: Int,
        size: Int,
    ) {
        if (page < 0) {
            throw ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "page는 0 이상이어야 합니다.")
        }
        if (size < MIN_PAGE_SIZE || size > MAX_PAGE_SIZE) {
            throw ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST,
                "size는 ${MIN_PAGE_SIZE}~${MAX_PAGE_SIZE} 사이여야 합니다.",
            )
        }
    }

    companion object {
        /** 페이지 크기 최솟값. */
        private const val MIN_PAGE_SIZE = 1

        /** 페이지 크기 최댓값 — SearchController와 동일(클램프 아닌 거부). */
        private const val MAX_PAGE_SIZE = 100
    }
}
