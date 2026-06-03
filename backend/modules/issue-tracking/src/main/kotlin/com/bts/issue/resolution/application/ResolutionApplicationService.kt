// 활성 Resolution 목록 조회 유스케이스 — ResolutionRepository.findAllActive 위임 (FR-IS-07 Task B4)

package com.bts.issue.resolution.application

import com.bts.issue.resolution.domain.Resolution
import com.bts.issue.resolution.repository.ResolutionRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Resolution 조회 유스케이스를 조율하는 Application Service.
 *
 * ## 트랜잭션 경계
 * [listActive] 는 읽기 전용 트랜잭션([Transactional.readOnly] = true)으로 실행된다.
 * [ResolutionRepository.findAllActive] 가 이미 `readOnly = true` 트랜잭션을 선언하지만,
 * Application Service 레이어에서도 명시하여 서비스 레이어 트랜잭션 정책을 일관되게 유지한다.
 *
 * @param repository 활성 Resolution 목록 + 단건 조회 Repository
 */
@Service
class ResolutionApplicationService(
    private val repository: ResolutionRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 활성 Resolution 목록을 displayOrder ASC 순으로 반환한다.
     *
     * `deleted_at IS NULL` 인 row 만 반환한다.
     * V011 seed 직후에는 표준 5종(fixed/wontfix/duplicate/cannotreproduce/done)이 반환된다.
     *
     * @return 활성 [Resolution] 리스트. 비어있을 수 있다.
     */
    @Transactional(readOnly = true)
    fun listActive(): List<Resolution> {
        log.debug("ResolutionApplicationService.listActive")
        return repository.findAllActive()
    }
}
