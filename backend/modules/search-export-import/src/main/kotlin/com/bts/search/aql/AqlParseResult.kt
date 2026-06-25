// AQL 파서 출력 결과 — AST와 ORDER BY 정렬 기준을 묶는 불변 DTO

package com.bts.search.aql

import com.bts.shared.search.AqlNode
import com.bts.shared.search.AqlSort

/**
 * AQL 파서([AqlParser]) 출력 결과.
 *
 * 파서가 토큰 목록을 분석해 생성한 두 가지 산출물을 담는다.
 * - [ast] — 쿼리 표현식의 추상 구문 트리(AST) 루트 노드.
 * - [sort] — ORDER BY 절에서 파싱된 정렬 기준 목록.
 *
 * 이 DTO 는 search 모듈 내부에서만 사용되며,
 * 컨트롤러가 [IssueSearchQuery][com.bts.shared.search.IssueSearchQuery] 를 조립할 때
 * [ast]와 [sort]를 각각 전달한다.
 *
 * @property ast AQL 쿼리 표현식의 루트 AST 노드. [AqlNode] sealed 계층.
 * @property sort ORDER BY 절 정렬 기준 목록.
 *   ORDER BY 가 없으면 빈 목록 — 기본 정렬(created_at DESC)은 어댑터가 결정한다.
 */
data class AqlParseResult(
    val ast: AqlNode,
    val sort: List<AqlSort>,
)
