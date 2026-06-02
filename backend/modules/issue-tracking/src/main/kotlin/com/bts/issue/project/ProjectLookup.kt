// projectIdOrKey(UUID 문자열 또는 projectKey) 를 활성 프로젝트 UUID 로 해석하는 애플리케이션 서비스

package com.bts.issue.project

import com.bts.issue.project.repository.ProjectLookupRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * projectIdOrKey (UUID 문자열 또는 projectKey) 를 활성 프로젝트 UUID 로 해석한다.
 *
 * - 입력이 UUID 형식이면 해당 UUID 의 활성 프로젝트를 확인하고 반환한다.
 * - 그 외에는 projectKey 로 간주해 조회한다.
 * - 활성 기준: `deleted_at IS NULL`.
 * - 미존재 또는 소프트 삭제된 경우 null 반환 (상위에서 404 처리).
 *
 * DB 접근은 [ProjectLookupRepository] 에 위임한다 — jOOQ 직접 접촉은 repository 레이어로
 * 한정(hexagonal 경계, ArchUnit 룰 2). 이 클래스는 UUID 파싱 분기만 담당한다.
 */
@Service
class ProjectLookup(
    private val repository: ProjectLookupRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * projectIdOrKey 를 활성 프로젝트 UUID 로 해석한다.
     *
     * @param projectIdOrKey UUID 문자열 또는 projectKey (예: "BTS").
     * @return 활성 프로젝트의 UUID. 미존재·소프트 삭제·잘못된 형식이면 null.
     */
    fun resolve(projectIdOrKey: String): UUID? {
        val uuid = tryParseUuid(projectIdOrKey)
        return if (uuid != null) {
            log.debug("ProjectLookup.resolve byUuid id={}", uuid)
            repository.findActiveProjectId(uuid)
        } else {
            log.debug("ProjectLookup.resolve byKey key={}", projectIdOrKey)
            repository.findActiveProjectIdByKey(projectIdOrKey)
        }
    }

    /**
     * 문자열을 UUID 로 파싱한다. 형식이 맞지 않으면 null 반환.
     */
    private fun tryParseUuid(value: String): UUID? =
        try {
            UUID.fromString(value)
        } catch (_: IllegalArgumentException) {
            // 형식 불일치는 정상 흐름 — null 반환으로 projectKey 분기로 위임
            null
        }
}
