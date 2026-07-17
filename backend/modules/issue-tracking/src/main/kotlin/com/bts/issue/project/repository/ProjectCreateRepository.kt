// ProjectCreateRepository — projects 테이블 INSERT 전담 jOOQ Repository (FR-PJ-01)

package com.bts.issue.project.repository

import com.bts.issue.jooq.tables.references.PROJECTS
import com.bts.issue.project.domain.Project
import com.bts.issue.project.domain.ProjectKeyAlreadyExistsException
import org.jooq.DSLContext
import org.jooq.exception.IntegrityConstraintViolationException
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.SQLException

/** 23505 unique_violation SQLState 상수. */
private const val SQL_STATE_UNIQUE_VIOLATION = "23505"

/**
 * 프로젝트 생성 전담 Repository.
 *
 * jOOQ DSLContext 를 통해 `projects` 테이블에 INSERT 한다.
 * jOOQ generated 코드 접촉은 repository 레이어로 한정한다(ArchUnit 룰 2, hexagonal 경계).
 *
 * key/name 외 나머지 컬럼(key_sequence/created_at/updated_at/lead_user_id/require_2fa)은
 * DB DEFAULT 값을 그대로 사용한다(id·created_at·updated_at 은 DB 가 채운다).
 *
 * **UNIQUE 위반 변환.** `projects.key` 는 DB 전역 UNIQUE 제약(소프트 삭제 여부 무관, DATA.md §1.1).
 * 23505(unique_violation) 위반은 [ProjectKeyAlreadyExistsException] 으로 변환한다.
 * key 정규식 CHECK(23514) 위반 등 그 외 제약 위반은 원 예외를 그대로 전파한다(PJ1-6).
 *
 * Spring 의 PersistenceExceptionTranslator 가 개입하지 않을 경우(예: 통합 테스트의 plain
 * `DSL.using()`) jOOQ 는 [DataIntegrityViolationException] 대신
 * [IntegrityConstraintViolationException] 을 직접 던진다. 두 케이스를 모두 처리한다
 * ([com.bts.issue.version.application.VersionApplicationService.tryInsert] 동형 패턴).
 */
@Repository
class ProjectCreateRepository(
    private val dsl: DSLContext,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 새 프로젝트를 `projects` 테이블에 삽입하고 DB 생성 id 를 포함한 [Project] 를 반환한다.
     *
     * @param key 프로젝트 key. DB CHECK 제약(`^[A-Z][A-Z0-9]{1,9}$`)을 만족해야 한다(위반 시
     *   [IntegrityConstraintViolationException] 등 원 예외가 그대로 전파된다, PJ1-6).
     * @param name 프로젝트 이름.
     * @return DB 생성 id 를 포함한 [Project].
     * @throws ProjectKeyAlreadyExistsException key 가 이미 사용 중일 때(활성/소프트삭제 무관, PJ1-7/EC-1/EC-6).
     */
    @Transactional
    fun insert(
        key: String,
        name: String,
    ): Project =
        try {
            insertRow(key, name)
        } catch (ex: DataIntegrityViolationException) {
            translateUniqueViolation(ex, key)
        } catch (ex: IntegrityConstraintViolationException) {
            translateUniqueViolation(ex, key)
        }

    private fun insertRow(
        key: String,
        name: String,
    ): Project {
        log.debug("Inserting project key={}", key)
        val record =
            dsl.insertInto(PROJECTS)
                .set(PROJECTS.KEY, key)
                .set(PROJECTS.NAME, name)
                .returning()
                .fetchOne()
                ?: error("insert returning() returned null for key=$key")
        return Project(
            id = record.id ?: error("projects.id must not be null after DB insert"),
            key = record.key,
            name = record.name,
        )
    }

    /**
     * 제약 위반 예외가 23505(unique_violation) 에서 비롯됐으면 [ProjectKeyAlreadyExistsException] 으로
     * 변환한다. 그 외(23514 CHECK 등)는 원 예외를 그대로 re-throw 한다.
     */
    private fun translateUniqueViolation(
        ex: RuntimeException,
        key: String,
    ): Nothing {
        val sqlEx =
            generateSequence(ex.cause) { it.cause }
                .filterIsInstance<SQLException>()
                .firstOrNull()
        if (sqlEx?.sqlState == SQL_STATE_UNIQUE_VIOLATION) {
            log.warn("Duplicate project key={}", key)
            throw ProjectKeyAlreadyExistsException(key)
        }
        throw ex
    }
}
