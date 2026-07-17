// ProjectCreateApplicationService — 프로젝트 생성 + 생성자 PROJECT_ADMIN 등록을 한 트랜잭션으로 묶는다

package com.bts.issue.project.application

import com.bts.issue.project.domain.Project
import com.bts.issue.project.repository.ProjectCreateRepository
import com.bts.shared.membership.ProjectMembershipWritePort
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 프로젝트 생성 ApplicationService (FR-PJ-01).
 *
 * **흐름 — insert → addCreatorAsAdmin (같은 트랜잭션).**
 * 1. [ProjectCreateRepository.insert] 로 `projects` 테이블에 신규 행을 만든다(issue-tracking BC 소유).
 * 2. 반환된 [Project.id] 를 [ProjectMembershipWritePort.addCreatorAsAdmin] 에 전달해 생성자를
 *    PROJECT_ADMIN 으로 등록한다(identity-access BC 소유 테이블 — cross-BC 포트 위임).
 *
 * **원자성(I1) — 이 클래스가 보장하는 범위.**
 * 클래스 레벨 `@Transactional` 로 두 호출이 같은 트랜잭션에 참여한다. [ProjectMembershipWritePort]
 * 구현체는 자체 트랜잭션 경계를 선언하지 않으므로(포트 KDoc 계약) 호출자 tx 에 참여하고,
 * insert 실패 시 addCreatorAsAdmin 호출 자체가 일어나지 않아(코드 순서상 단락) 부분 커밋이 없다.
 *
 * **이 클래스가 증명하지 않는 것.** 이 클래스 자체의 단위 테스트([ProjectCreateApplicationServiceTest])는
 * 두 협력자를 MockK 로 대체하므로 "실제 DB 트랜잭션이 롤백되는지"는 검증 범위 밖이다 — 그건
 * 조립 부팅(:modules:app) Testcontainers 통합 테스트가 실증한다(원 plan T12). 여기 단위 테스트는
 * "insert 다음에 addCreatorAsAdmin 을 호출하는가 / insert 예외 시 addCreatorAsAdmin 을 안 부르는가"
 * 라는 협력 계약만 확인한다.
 *
 * DEVELOPMENT.md §1 — public service 메서드 전체 @Transactional 명시 원칙에 따라 클래스 레벨로 커버한다
 * ([com.bts.issue.project.application.ProjectLeadApplicationService] 동형 선례).
 */
@Service
@Transactional
class ProjectCreateApplicationService(
    private val projectCreateRepo: ProjectCreateRepository,
    private val membershipWritePort: ProjectMembershipWritePort,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 새 프로젝트를 생성하고 생성자를 PROJECT_ADMIN 으로 등록한다.
     *
     * @param creatorId 프로젝트를 생성하는 행위자 UUID. 생성된 프로젝트의 PROJECT_ADMIN 이 된다.
     * @param key 프로젝트 key. DB CHECK 제약을 위반하면 원 예외가 전파된다([ProjectCreateRepository.insert]).
     * @param name 프로젝트 이름.
     * @return DB 생성 id 를 포함한 [Project].
     * @throws com.bts.issue.project.domain.ProjectKeyAlreadyExistsException key 가 이미 사용 중일 때.
     */
    fun create(
        creatorId: UUID,
        key: String,
        name: String,
    ): Project {
        log.debug("ProjectCreateApplicationService.create creatorId={} key={}", creatorId, key)

        val project = projectCreateRepo.insert(key, name)
        val projectId = requireNotNull(project.id) { "insert 로 생성된 project.id 는 null 일 수 없다" }

        membershipWritePort.addCreatorAsAdmin(projectId, creatorId)

        return project
    }
}
