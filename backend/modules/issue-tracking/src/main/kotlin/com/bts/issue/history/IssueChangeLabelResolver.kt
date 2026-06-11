// 이슈 변경 이력 항목의 사람이 읽기 쉬운 라벨을 채우는 리졸버 — issue-tracking 소유 필드 전담

package com.bts.issue.history

import com.bts.issue.component.repository.ComponentRepository
import com.bts.issue.resolution.repository.ResolutionRepository
import com.bts.issue.type.repository.IssueTypeRepository
import com.bts.issue.version.repository.VersionRepository
import com.bts.shared.issue.IssueTypeId
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 이슈 변경 이력의 표시 라벨(fromLabel/toLabel)을 채우는 리졸버.
 *
 * 디텍터([IssueChangeDetector])가 생성한 [IssueChangeItem] 목록은 value(ID/raw) 만 채워지고
 * label 이 null 인 상태로 전달된다. 이 클래스는 issue-tracking 소유 필드에 한해 name lookup 을
 * 수행하고 label 이 채워진 새 목록을 반환한다.
 *
 * **라벨 채우는 필드.**
 * - `type` — IssueType.name (IssueTypeRepository.findById)
 * - `resolution` — Resolution.name (ResolutionRepository.findById)
 * - `components` — 컴포넌트 이름 정렬 배열 문자열 (ComponentRepository.findById)
 * - `affectsVersions`, `fixVersions` — 버전 이름 정렬 배열 문자열 (VersionRepository.findById)
 *
 * **라벨 채우지 않는 필드 (label=null 유지).**
 * - `assignee`, `securityLevel` — identity-access BC 소유. cross-BC 조회 금지.
 * - `status` — stateKey passthrough. cross-BC 상태명 조회 금지.
 * - 스칼라 필드(`summary`, `description`, `priority`, `impact`, `environment`, `labels`) — 값이 곧 표시.
 * - `lifecycle`, `customField:*` — label 불필요.
 *
 * **lookup 실패 graceful 정책.**
 * id 에 해당하는 row 가 없거나(소프트 삭제 등), 값이 파싱 불가한 경우 해당 side(from/to)의
 * label 은 null 로 유지한다. 예외를 던지거나 원시 id 를 노출하지 않는다.
 *
 * @see IssueChangeItem
 * @see IssueChangeDetector
 */
@Service
class IssueChangeLabelResolver(
    private val issueTypeRepository: IssueTypeRepository,
    private val resolutionRepository: ResolutionRepository,
    private val componentRepository: ComponentRepository,
    private val versionRepository: VersionRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** ID 배열 JSON 파싱에 사용하는 패턴 — `"uuid"` 형태의 토큰 추출. */
    private val uuidTokenRegex = Regex(""""([0-9a-fA-F\-]{36})"""")

    /**
     * [items] 목록에서 issue-tracking 소유 필드의 라벨을 채운 새 목록을 반환한다.
     *
     * 원본 [items] 를 변경하지 않고 새 [IssueChangeItem] 인스턴스를 생성해 반환한다.
     * cross-BC 필드(assignee, securityLevel 등)는 label=null 을 그대로 유지한다.
     *
     * @param items 디텍터가 생성한 변경 항목 목록. label 은 null 이어야 한다.
     * @param projectId 소속 프로젝트 UUID. component/version lookup 에 필요하다.
     * @return label 이 채워진 [IssueChangeItem] 목록. 원본과 순서가 동일하다.
     */
    @Transactional(readOnly = true)
    fun resolveLabels(
        items: List<IssueChangeItem>,
        projectId: UUID,
    ): List<IssueChangeItem> =
        items.map { item ->
            when (item.field) {
                FIELD_TYPE -> resolveType(item)
                FIELD_RESOLUTION -> resolveResolution(item)
                FIELD_COMPONENTS -> resolveCollectionField(item, ::lookupComponentNames, projectId)
                FIELD_AFFECTS_VERSIONS -> resolveCollectionField(item, ::lookupVersionNames, projectId)
                FIELD_FIX_VERSIONS -> resolveCollectionField(item, ::lookupVersionNames, projectId)
                else -> item
            }
        }

    // ── 필드별 resolver ───────────────────────────────────────────────────────────

    private fun resolveType(item: IssueChangeItem): IssueChangeItem =
        item.copy(
            fromLabel = lookupTypeName(item.fromValue),
            toLabel = lookupTypeName(item.toValue),
        )

    private fun resolveResolution(item: IssueChangeItem): IssueChangeItem =
        item.copy(
            fromLabel = lookupResolutionName(item.fromValue),
            toLabel = lookupResolutionName(item.toValue),
        )

    /**
     * 컬렉션 필드(components, affectsVersions, fixVersions) 라벨을 조회 함수를 통해 채운다.
     * components 와 versions 가 동형 구조이므로 [lookup] 함수를 파라미터로 받아 통합한다.
     *
     * @param item 원본 변경 항목.
     * @param lookup 단일 값(raw JSON 배열)을 표시 라벨로 변환하는 함수.
     * @return 라벨이 채워진 새 [IssueChangeItem].
     */
    private fun resolveCollectionField(
        item: IssueChangeItem,
        lookup: (String?, UUID) -> String?,
        projectId: UUID,
    ): IssueChangeItem =
        item.copy(
            fromLabel = lookup(item.fromValue, projectId),
            toLabel = lookup(item.toValue, projectId),
        )

    // ── lookup 헬퍼 ──────────────────────────────────────────────────────────────

    // 라벨 lookup 실패는 예외 종류에 관계없이 label=null graceful degrade 정책 — 이력 기록을 막으면 안 됨.
    @Suppress("TooGenericExceptionCaught")
    private fun lookupTypeName(value: String?): String? {
        val id = value?.toLongOrNull() ?: return null
        return try {
            issueTypeRepository.findById(IssueTypeId(id))?.name
        } catch (e: Exception) {
            log.warn("IssueType lookup failed for id={}", id, e)
            null
        }
    }

    // 라벨 lookup 실패는 예외 종류에 관계없이 label=null graceful degrade 정책 — 이력 기록을 막으면 안 됨.
    @Suppress("TooGenericExceptionCaught")
    private fun lookupResolutionName(value: String?): String? {
        val uuid = parseUuidOrNull(value) ?: return null
        return try {
            resolutionRepository.findById(uuid)?.name
        } catch (e: Exception) {
            log.warn("Resolution lookup failed for id={}", uuid, e)
            null
        }
    }

    private fun lookupComponentNames(
        value: String?,
        projectId: UUID,
    ): String? {
        val ids = parseUuidArrayOrNull(value) ?: return null
        val names =
            ids.mapNotNull { id ->
                // 라벨 lookup 실패는 예외 종류에 관계없이 label=null graceful degrade 정책 — 이력 기록을 막으면 안 됨.
                @Suppress("TooGenericExceptionCaught")
                try {
                    componentRepository.findById(id, projectId)?.name
                } catch (e: Exception) {
                    log.warn("Component lookup failed for id={} projectId={}", id, projectId, e)
                    null
                }
            }.sorted()
        return if (names.isEmpty()) null else "[${names.joinToString(", ")}]"
    }

    private fun lookupVersionNames(
        value: String?,
        projectId: UUID,
    ): String? {
        val ids = parseUuidArrayOrNull(value) ?: return null
        val names =
            ids.mapNotNull { id ->
                // 라벨 lookup 실패는 예외 종류에 관계없이 label=null graceful degrade 정책 — 이력 기록을 막으면 안 됨.
                @Suppress("TooGenericExceptionCaught")
                try {
                    versionRepository.findById(id, projectId)?.name
                } catch (e: Exception) {
                    log.warn("Version lookup failed for id={} projectId={}", id, projectId, e)
                    null
                }
            }.sorted()
        return if (names.isEmpty()) null else "[${names.joinToString(", ")}]"
    }

    // ── 파싱 헬퍼 ─────────────────────────────────────────────────────────────────

    // UUID 파싱 실패는 정상 제어 흐름 — 형식 불일치 시 null 반환이 의도이므로 예외를 로그 없이 무시한다.
    @Suppress("SwallowedException")
    private fun parseUuidOrNull(value: String?): UUID? {
        if (value == null) return null
        return try {
            UUID.fromString(value)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private fun parseUuidArrayOrNull(value: String?): List<UUID>? {
        if (value == null) return null
        return try {
            uuidTokenRegex.findAll(value)
                .map { UUID.fromString(it.groupValues[1]) }
                .toList()
                .takeIf { it.isNotEmpty() }
        } catch (e: IllegalArgumentException) {
            log.warn("UUID array parse failed for value='{}'", value, e)
            null
        }
    }

    // ── 상수 ─────────────────────────────────────────────────────────────────────

    private companion object {
        const val FIELD_TYPE = "type"
        const val FIELD_RESOLUTION = "resolution"
        const val FIELD_COMPONENTS = "components"
        const val FIELD_AFFECTS_VERSIONS = "affectsVersions"
        const val FIELD_FIX_VERSIONS = "fixVersions"
    }
}
