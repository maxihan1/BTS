// 이슈 타입 CRUD + 재할당 유스케이스 조율 — @Service @Transactional, 표준 불변 가드 우선

package com.bts.issue.type.application

import com.bts.issue.type.domain.IssueType
import com.bts.issue.type.domain.IssueTypeInUseException
import com.bts.issue.type.domain.IssueTypeKeyDuplicateException
import com.bts.issue.type.domain.IssueTypeKeyInvalidException
import com.bts.issue.type.domain.IssueTypeNotFoundException
import com.bts.issue.type.domain.IssueTypeReassignTargetInvalidException
import com.bts.issue.type.domain.IssueTypeStandardImmutableException
import com.bts.issue.type.repository.IssueTypeRepository
import com.bts.shared.issue.IssueTypeId
import com.bts.shared.issue.IssueTypeKey
import com.bts.workflow.scheme.application.port.IssueTypeUsagePort
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 이슈 타입 CRUD + 재할당 유스케이스를 조율하는 Application Service.
 *
 * ## 트랜잭션 경계
 * 클래스 레벨 `@Transactional` 이 모든 public 메서드에 적용된다.
 * [delete] 의 `reassignIssues` + `softDelete` 는 같은 트랜잭션 안에서 원자적으로 실행된다.
 *
 * ## fail-closed 정책
 * [IssueTypeUsagePort.countSchemeMappings] 가 예외를 던지면 삭제를 거부하고 예외를 전파한다.
 * 외부 BC(project-workflow) 의 SPI 장애가 잘못된 삭제를 허용하지 않도록 한다.
 *
 * ## 가드 순서
 * 1. 타입 존재 확인 ([IssueTypeNotFoundException])
 * 2. 표준 불변 확인 ([IssueTypeStandardImmutableException]) — 사용중/재할당 보다 우선
 * 3. 스킴 매핑 참조 확인 ([IssueTypeInUseException]) — reassignTo 로도 해소 불가
 * 4. 재할당 대상 유효성 확인 ([IssueTypeReassignTargetInvalidException])
 * 5. 이슈 사용중 확인 ([IssueTypeInUseException]) — reassignTo 없을 때
 *
 * ## 권한 가드
 * IssueType 권한은 별도 FR(FR-IS-02 후속)에서 다룬다. 현재는 AlwaysAllow 수준으로 생략.
 *
 * @param repo [IssueTypeRepository] — issue-tracking BC 내부 jOOQ Repository.
 * @param usagePort [IssueTypeUsagePort] — project-workflow BC 가 선언한 port. 스킴 매핑 카운트 조회.
 */
@Service
@Transactional
class IssueTypeApplicationService(
    private val repo: IssueTypeRepository,
    private val usagePort: IssueTypeUsagePort,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 새 이슈 타입을 생성한다.
     *
     * ## 처리 흐름
     * 1. key 형식 검증 — [IssueTypeKey] 생성 실패 시 [IssueTypeKeyInvalidException] 으로 변환
     * 2. key 중복 확인 — 이미 활성 타입이 있으면 [IssueTypeKeyDuplicateException]
     * 3. [IssueType.create] 호출 — isStandard 는 false 로 강제
     * 4. [IssueTypeRepository.insert] 호출 후 반환
     *
     * @param request 생성 요청 DTO.
     * @return DB 저장 후 id 가 할당된 [IssueType].
     * @throws IssueTypeKeyInvalidException key 형식이 URL-safe 소문자 슬러그 규칙 위반 시.
     * @throws IssueTypeKeyDuplicateException 동일 key 의 활성 타입이 이미 존재할 때.
     */
    fun create(request: CreateIssueTypeRequest): IssueType {
        val key = parseKey(request.key)
        if (repo.findByKey(key) != null) {
            throw IssueTypeKeyDuplicateException(key)
        }
        val issueType = IssueType.create(
            key = key,
            name = request.name,
            description = request.description,
            iconName = request.iconName,
            isStandard = false,
            hierarchyLevel = request.hierarchyLevel,
        )
        val saved = repo.insert(issueType)
        log.info("issue_type_created key={} id={}", key.value, saved.id?.value)
        return saved
    }

    /**
     * 커스텀 이슈 타입의 변경 가능 필드를 수정한다.
     *
     * key / isStandard 는 불변이므로 수정하지 않는다.
     * 표준 타입 수정 시도는 즉시 거부한다.
     *
     * @param id 수정할 이슈 타입 id.
     * @param request 수정 요청 DTO.
     * @throws IssueTypeNotFoundException id 에 해당하는 활성 타입이 없을 때.
     * @throws IssueTypeStandardImmutableException 대상이 표준 타입일 때.
     */
    fun update(
        id: IssueTypeId,
        request: UpdateIssueTypeRequest,
    ) {
        val existing = repo.findById(id) ?: throw IssueTypeNotFoundException(id)
        if (existing.isStandard) {
            throw IssueTypeStandardImmutableException(typeId = id, key = null)
        }
        val updated = existing.copy(
            name = request.name,
            description = request.description,
            iconName = request.iconName,
            hierarchyLevel = request.hierarchyLevel,
        )
        repo.update(updated)
        log.info("issue_type_updated id={}", id.value)
    }

    /**
     * 이슈 타입을 소프트 삭제한다.
     *
     * ## 가드 순서 (표준 불변 우선)
     * 1. 타입 존재 확인
     * 2. 표준 타입 차단
     * 3. 스킴 매핑 참조 확인 (fail-closed: usagePort 예외 → 삭제 거부)
     * 4. 재할당 대상 유효성 확인 (reassignTo 지정 시)
     * 5. 이슈 사용 중 확인 (reassignTo 없을 때)
     *
     * ## 재할당 + 소프트 삭제 원자성
     * reassignTo 가 유효하고 이슈가 존재하면 `reassignIssues(from, to)` 후 `softDelete` 를
     * 같은 `@Transactional` 내에서 순서대로 호출한다.
     *
     * ## 스킴 매핑 참조 예외
     * `usagePort.countSchemeMappings` 가 0 보다 크면 reassignTo 지정 여부와 관계없이 [IssueTypeInUseException].
     * 스킴 매핑은 issue-tracking 이 직접 수정할 수 없는 project-workflow BC 소유 데이터이기 때문이다.
     *
     * @param id 삭제할 이슈 타입 id.
     * @param reassignTo 해당 타입이 할당된 이슈를 재배정할 대상 타입 id. null 이면 이슈가 있을 때 예외.
     * @throws IssueTypeNotFoundException id 에 해당하는 활성 타입이 없을 때.
     * @throws IssueTypeStandardImmutableException 대상이 표준 타입일 때.
     * @throws IssueTypeInUseException 이슈 또는 스킴 매핑 참조가 존재하고 해소 불가능할 때.
     * @throws IssueTypeReassignTargetInvalidException reassignTo 가 자기 자신이거나 미존재/삭제된 타입일 때.
     */
    @Suppress("ThrowsCount")
    fun delete(
        id: IssueTypeId,
        reassignTo: IssueTypeId?,
    ) {
        val existing = repo.findById(id) ?: throw IssueTypeNotFoundException(id)

        // 가드 1: 표준 타입 불변 (최우선)
        if (existing.isStandard) {
            throw IssueTypeStandardImmutableException(typeId = id, key = null)
        }

        val issueCount = repo.countIssuesByTypeId(id.value)
        // fail-closed: usagePort 장애 시 예외를 그대로 전파해 삭제를 거부한다
        val schemeMappingCount = usagePort.countSchemeMappings(id.value)

        // 가드 2: 스킴 매핑 참조 — reassignTo 지정 여부와 무관하게 해소 불가
        // project-workflow BC 소유 데이터이므로 issue-tracking 이 직접 수정할 수 없다.
        if (schemeMappingCount > 0) {
            throw IssueTypeInUseException(usageCount = issueCount, schemeMappingCount = schemeMappingCount)
        }

        if (reassignTo != null) {
            // 가드 3: 재할당 대상 유효성 — 자기 자신이거나 미존재/삭제된 타입
            if (reassignTo == id) {
                throw IssueTypeReassignTargetInvalidException(
                    targetId = reassignTo,
                    reason = "재할당 대상이 삭제 대상과 동일합니다",
                )
            }
            repo.findById(reassignTo)
                ?: throw IssueTypeReassignTargetInvalidException(
                    targetId = reassignTo,
                    reason = "재할당 대상 이슈 타입이 존재하지 않거나 삭제되었습니다",
                )
            // 이슈가 있을 때만 재배정 — 없으면 불필요한 UPDATE 를 스킵한다
            if (issueCount > 0) {
                repo.reassignIssues(fromTypeId = id.value, toTypeId = reassignTo.value)
            }
        } else if (issueCount > 0) {
            // 가드 4: 이슈 사용 중이고 재할당 대상 없음
            throw IssueTypeInUseException(usageCount = issueCount, schemeMappingCount = 0L)
        }

        repo.softDelete(id)
        log.info("issue_type_deleted id={}", id.value)
    }

    // ── private helpers ─────────────────────────────────────────────────────────

    /**
     * raw String 을 [IssueTypeKey] 로 변환한다.
     *
     * [IssueTypeKey] 생성자가 [IllegalArgumentException] 을 던지면 [IssueTypeKeyInvalidException] 으로 변환한다.
     * IssueTypeKeyInvalidException 은 IssueTypeKey 를 요구하므로, 형식 위반된 원본 값을 담는
     * 별도 내부 VO 인스턴스 생성이 불가능하다. 이 경우 sentinel key("in") 로 래핑하되
     * 예외 메시지에 원본 raw 값을 포함해 운영자가 식별할 수 있도록 한다.
     */
    private fun parseKey(raw: String): IssueTypeKey {
        val key = runCatching { IssueTypeKey(raw) }.getOrElse { e ->
            log.warn("issue_type_key_invalid raw='{}' cause={}", raw, e.message)
            // IssueTypeKey VO 는 유효한 형식만 허용하므로 invalid raw 를 직접 담을 수 없다.
            // sentinel("in") 으로 감싸서 도메인 예외를 올린다. 원본 raw 는 로그에 기록된다.
            throw IssueTypeKeyInvalidException(IssueTypeKey("in"))
        }
        return key
    }
}
