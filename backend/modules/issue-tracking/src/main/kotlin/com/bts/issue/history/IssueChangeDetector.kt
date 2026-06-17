// 이슈 변경 디텍터 — before/after Issue 비교로 IssueChangeItem 목록 생성하는 순수 함수

package com.bts.issue.history

import com.bts.issue.domain.Issue
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/** customFields 필드명 prefix. */
private const val CUSTOM_FIELD_PREFIX = "customField:"

/** lifecycle 필드명 상수. */
private const val FIELD_LIFECYCLE = "lifecycle"

/** lifecycle created 값. */
private const val LIFECYCLE_CREATED = "created"

/** lifecycle deleted 값. */
private const val LIFECYCLE_DELETED = "deleted"

/**
 * 이슈 변경 감지기.
 *
 * 이전([before]) 과 이후([after]) [Issue] 상태를 비교해 바뀐 필드만
 * [IssueChangeItem] 리스트로 반환하는 순수 함수 컴포넌트.
 *
 * 스레드 안전 — 내부 가변 상태 없음.
 *
 * 사용 예.
 * ```kotlin
 * val detector = IssueChangeDetector()
 * val items = detector.detect(before, after)
 * val created = detector.created(issue)
 * val deleted = detector.deleted(issue)
 * ```
 */
@Component
class IssueChangeDetector {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        /**
         * 스칼라 필드명 → Issue 값 추출 함수 매핑 테이블.
         * 새 스칼라 필드 추가 시 이 목록에만 추가하면 된다.
         */
        private val SCALAR_FIELD_EXTRACTORS: List<Pair<String, (Issue) -> String?>> =
            listOf(
                "key" to { it.key.value },
                "summary" to { it.summary },
                "type" to { it.typeId.value.toString() },
                "status" to { it.currentStateKey },
                "description" to { it.description },
                "priority" to { it.priority.toString() },
                "environment" to { it.environment },
                "impact" to { it.impact?.toString() },
                "assignee" to { it.assigneeId?.value?.toString() },
                "resolution" to { it.resolutionId?.toString() },
                "securityLevel" to { it.securityLevelId?.toString() },
            )
    }

    /**
     * 이슈 생성 시 lifecycle 마커를 반환한다.
     *
     * @param issue 새로 생성된 이슈.
     * @return lifecycle=created 마커 1개를 담은 리스트.
     */
    fun created(issue: Issue): List<IssueChangeItem> {
        log.debug("created lifecycle marker for issue={}", issue.key.value)
        return listOf(IssueChangeItem(field = FIELD_LIFECYCLE, fromValue = null, toValue = LIFECYCLE_CREATED))
    }

    /**
     * 이슈 소프트 삭제 시 lifecycle 마커를 반환한다.
     *
     * @param issue 소프트 삭제된 이슈.
     * @return lifecycle=deleted 마커 1개를 담은 리스트.
     */
    fun deleted(issue: Issue): List<IssueChangeItem> {
        log.debug("deleted lifecycle marker for issue={}", issue.key.value)
        return listOf(IssueChangeItem(field = FIELD_LIFECYCLE, fromValue = null, toValue = LIFECYCLE_DELETED))
    }

    /**
     * [before] 와 [after] 를 비교해 변경된 필드의 [IssueChangeItem] 목록을 반환한다.
     *
     * 변경 없으면 빈 리스트를 반환한다.
     * 컬렉션 필드는 정렬 후 집합 동일성으로 비교한다(순서만 다른 경우 변경 아님).
     * customFields 는 키별로 분해해 각 키마다 독립 [IssueChangeItem] 을 생성한다.
     *
     * @param before 변경 전 이슈 상태.
     * @param after 변경 후 이슈 상태.
     * @return 변경된 필드만 담은 [IssueChangeItem] 리스트.
     */
    fun detect(
        before: Issue,
        after: Issue,
    ): List<IssueChangeItem> {
        val items = mutableListOf<IssueChangeItem>()

        detectScalarFields(before, after, items)
        detectCollectionFields(before, after, items)
        detectCustomFields(before, after, items)

        return items.toList()
    }

    // ── private helpers ────────────────────────────────────────────────────────

    private fun detectScalarFields(
        before: Issue,
        after: Issue,
        items: MutableList<IssueChangeItem>,
    ) {
        SCALAR_FIELD_EXTRACTORS.forEach { (field, extract) ->
            addIfChanged(items, field, extract(before), extract(after))
        }
    }

    private fun detectCollectionFields(
        before: Issue,
        after: Issue,
        items: MutableList<IssueChangeItem>,
    ) {
        addIfCollectionChanged(items, "labels", before.labels.map { it }, after.labels.map { it })
        addIfCollectionChanged(
            items,
            "components",
            before.componentIds.map { it.toString() },
            after.componentIds.map { it.toString() },
        )
        addIfCollectionChanged(
            items,
            "affectsVersions",
            before.affectsVersionIds.map { it.toString() },
            after.affectsVersionIds.map { it.toString() },
        )
        addIfCollectionChanged(
            items,
            "fixVersions",
            before.fixVersionIds.map { it.toString() },
            after.fixVersionIds.map { it.toString() },
        )
    }

    private fun detectCustomFields(
        before: Issue,
        after: Issue,
        items: MutableList<IssueChangeItem>,
    ) {
        val allKeys = before.customFields.keys + after.customFields.keys
        for (key in allKeys) {
            val fromRaw = before.customFields[key]
            val toRaw = after.customFields[key]
            val fromStr = serializeCustomFieldValue(fromRaw)
            val toStr = serializeCustomFieldValue(toRaw)
            if (fromStr != toStr) {
                items.add(IssueChangeItem(field = "$CUSTOM_FIELD_PREFIX$key", fromValue = fromStr, toValue = toStr))
            }
        }
    }

    /**
     * 스칼라 값이 바뀌었으면 [items] 에 추가한다.
     * null 과 null-이 아닌 빈 문자열("")은 서로 다른 값으로 취급한다.
     */
    private fun addIfChanged(
        items: MutableList<IssueChangeItem>,
        field: String,
        fromValue: String?,
        toValue: String?,
    ) {
        if (fromValue != toValue) {
            items.add(IssueChangeItem(field = field, fromValue = fromValue, toValue = toValue))
        }
    }

    /**
     * 컬렉션을 정렬 후 집합 비교해 바뀌었으면 [items] 에 JSON 배열 문자열로 추가한다.
     * 순서만 다르고 원소 집합이 동일한 경우는 변경으로 취급하지 않는다.
     */
    private fun addIfCollectionChanged(
        items: MutableList<IssueChangeItem>,
        field: String,
        fromList: List<String>,
        toList: List<String>,
    ) {
        val fromSorted = fromList.sorted()
        val toSorted = toList.sorted()
        if (fromSorted != toSorted) {
            items.add(
                IssueChangeItem(
                    field = field,
                    fromValue = toJsonArray(fromSorted),
                    toValue = toJsonArray(toSorted),
                ),
            )
        }
    }

    /** 문자열 리스트를 정렬된 JSON 배열 문자열로 직렬화한다. 예: `["a","b"]`. */
    private fun toJsonArray(list: List<String>): String {
        return list.joinToString(separator = ",", prefix = "[", postfix = "]") { "\"$it\"" }
    }

    /**
     * customFields 의 단일 값을 문자열로 직렬화한다.
     * - null → null
     * - String → 값 그대로
     * - 그 외 복합 타입 → toString() (T3 이후 필요 시 JSON 직렬화로 고도화 가능)
     */
    private fun serializeCustomFieldValue(value: Any?): String? {
        return when (value) {
            null -> null
            is String -> value
            else -> value.toString()
        }
    }
}
