// AQL 이슈 검색 어댑터 — IssueSearchPort 구현, AST→jOOQ 변환 + BROWSE 게이트 + visibility 술어 AND 결합

package com.bts.issue.adapter.outbound.search

import com.bts.issue.repository.IssueRepository
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import com.bts.shared.permission.IssueSecurityDirectory
import com.bts.shared.search.AqlField
import com.bts.shared.search.AqlNode
import com.bts.shared.search.AqlSort
import com.bts.shared.search.IssueSearchPage
import com.bts.shared.search.IssueSearchPort
import com.bts.shared.search.IssueSearchQuery
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/** MVP 에서 지원하는 AQL 필드 목록. */
private val SUPPORTED_FIELDS = setOf("status", "label", "summary", "priority")

/** 후속 PR 에서 지원 예정인 필드 목록 — 사용 시 명시적 오류 메시지 제공. */
private val FUTURE_FIELDS = setOf("assignee", "reporter", "component", "project")

/**
 * [IssueSearchPort] 의 issue-tracking BC 구현 (FR-SR-02 Task 5).
 *
 * AQL AST 를 jOOQ Condition 으로 변환하고 visibility 보안 술어를 최상위 AND 로 결합해 이슈를 검색한다.
 *
 * ### 보안 불변식
 *
 * 1. **BROWSE 게이트**: [IssuePermissionResolver] 로 actor 의 BROWSE 권한을 확인한다.
 *    권한 없으면 [SecurityException] — 이슈 존재 여부를 노출하지 않는다(probe 차단).
 * 2. **visibility 보안 술어**: [IssueRepository.searchByAql] 이 `buildActiveSecureWhere` 를 최상위 AND 로 결합.
 *    사용자 AST 는 보안 술어 밖에서 감쌀 수 없으므로 OR/NOT 우회가 구조적으로 불가능하다.
 *
 * @see IssueSearchPort
 * @see IssueRepository.searchByAql
 */
@Component
class IssueSearchAdapter(
    private val issueRepository: IssueRepository,
    private val securityDirectory: IssueSecurityDirectory,
    private val permissionResolver: IssuePermissionResolver,
) : IssueSearchPort {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * AQL 커맨드 객체를 받아 가시 이슈 검색 결과를 반환한다.
     *
     * 처리 순서.
     * 1. BROWSE 권한 확인 — 없으면 [SecurityException].
     * 2. [IssueSecurityDirectory.accessibleLevels] 로 보안 등급 접근 집합 1회 조회.
     * 3. AST 필드/연산자 사전 검증 (지원 여부).
     * 4. [IssueRepository.searchByAql] 위임 — visibility 보안 술어 + AST Condition + 정렬 + 페이지.
     *
     * @param query AQL 커맨드 객체.
     * @return 검색 결과 페이지. BROWSE 없으면 예외.
     * @throws SecurityException actor 가 projectKey 에 BROWSE 권한이 없는 경우.
     * @throws IllegalArgumentException 미지원 필드 또는 연산자 조합인 경우.
     */
    @Transactional(readOnly = true)
    override fun search(query: IssueSearchQuery): IssueSearchPage {
        // 1. BROWSE 게이트 — visibility 이전에 먼저 확인(probe 차단, 교훈 auth-extraction-before-resource-lookup)
        val hasBrowse =
            permissionResolver.hasPermission(
                query.viewerUserId,
                IssuePermission.BROWSE,
                IssueScope.Project(query.projectKey),
            )
        if (!hasBrowse) {
            log.warn(
                "BROWSE 권한 없는 actor={} 의 AQL 검색 차단 projectKey={}",
                query.viewerUserId,
                query.projectKey,
            )
            throw SecurityException("BROWSE 권한이 없습니다. projectKey=${query.projectKey}")
        }

        // 2. 보안 등급 접근 집합 — 목록당 1회 cross-BC 호출
        val access = securityDirectory.accessibleLevels(query.viewerUserId, query.projectKey)

        // 3. AST 사전 검증 — 미지원 필드/연산자 조합은 즉시 거부
        validateAst(query.ast)

        log.debug(
            "AQL 검색 projectKey={} actor={} page={} size={}",
            query.projectKey,
            query.viewerUserId,
            query.page,
            query.size,
        )

        // 4. repository 위임 — buildActiveSecureWhere + AST Condition + 정렬 + 페이지
        return issueRepository.searchByAql(
            projectKey = query.projectKey,
            ast = query.ast,
            sort = query.sort,
            actor = query.viewerUserId,
            access = access,
            page = query.page,
            size = query.size,
        )
    }

    /**
     * AST 전체를 재귀 순회하여 지원하지 않는 필드나 연산자 조합을 즉시 거부한다.
     *
     * @param node 검증할 AST 노드.
     * @throws IllegalArgumentException 미지원 필드 또는 연산자 조합인 경우.
     */
    private fun validateAst(node: AqlNode) {
        when (node) {
            is AqlNode.And -> {
                validateAst(node.left)
                validateAst(node.right)
            }
            is AqlNode.Or -> {
                validateAst(node.left)
                validateAst(node.right)
            }
            is AqlNode.Not -> validateAst(node.child)
            is AqlNode.Comparison -> validateComparison(node.field, node.op)
        }
    }

    /**
     * 단일 비교 노드의 필드와 연산자 조합을 검증한다.
     *
     * @param field AQL 필드 식별자.
     * @param op AQL 비교 연산자.
     * @throws IllegalArgumentException 미지원 필드 또는 연산자 조합인 경우.
     */
    private fun validateComparison(
        field: AqlField,
        op: com.bts.shared.search.AqlOperator,
    ) {
        val fieldName = field.value.lowercase()

        if (fieldName in FUTURE_FIELDS) {
            throw IllegalArgumentException(
                "후속 지원 예정 필드입니다: $fieldName. " +
                    "현재 MVP 에서는 사용할 수 없습니다.",
            )
        }

        if (fieldName !in SUPPORTED_FIELDS) {
            throw IllegalArgumentException(
                "지원하지 않는 필드입니다: $fieldName. " +
                    "지원 필드: ${SUPPORTED_FIELDS.sorted().joinToString(", ")}",
            )
        }

        // priority 는 SMALLINT — ~ (CONTAINS) 연산자 불가
        if (fieldName == "priority" && op == com.bts.shared.search.AqlOperator.CONTAINS) {
            throw IllegalArgumentException(
                "priority 필드에는 ~ 연산자를 사용할 수 없습니다. " +
                    "priority 는 정수(SMALLINT) 필드입니다.",
            )
        }
    }
}
