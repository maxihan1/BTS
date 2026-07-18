// 프로젝트 Aggregate Root — 최소 필드(id·key·name) + 생성 BC 도메인 예외 (FR-PJ-01)

package com.bts.issue.project.domain

import java.util.UUID

/**
 * 프로젝트 Aggregate Root.
 *
 * 이 PR(FR-PJ-01 프로젝트 생성) 범위는 최소 필드(id, key, name) 만 다룬다.
 * `archived_at` 등 나머지 프로젝트 필드는 이 PR 범위 밖(PR-4 후속).
 *
 * key 형식(대문자로 시작, 대문자+숫자 2~10자)은 DB CHECK 제약(`projects_key_check`)이
 * 강제한다 — 이 도메인 객체는 별도 정규식 검증을 하지 않는다(insert 시 DB CHECK 로 거부, PJ1-6).
 *
 * @property id DB PK. 신규 생성 전(DB 저장 전)에는 null 이다.
 * @property key 프로젝트 식별 접두사 (예: "BTS").
 * @property name 프로젝트 이름.
 */
data class Project(
    val id: UUID?,
    val key: String,
    val name: String,
)

/**
 * 프로젝트 생성 BC 에서 발생하는 도메인 예외의 베이스.
 *
 * sealed 로 선언하여 when 식에서 컴파일러가 완전성(exhaustiveness)을 보장한다.
 * RuntimeException 을 상속하므로 Spring `@Transactional` 롤백 트리거 대상이다.
 */
sealed class ProjectCreateDomainException(message: String) : RuntimeException(message)

/**
 * 이미 사용 중인 프로젝트 key 로 생성을 시도할 때.
 *
 * DB UNIQUE 제약(`projects.key`)은 소프트 삭제(`deleted_at`) 여부와 무관하게 영구 점유를
 * 유지한다 (DATA.md §1.1 — 프로젝트 key 영구 보존). [com.bts.issue.project.repository.ProjectCreateRepository]
 * 가 23505(unique_violation) SQLState 를 감지해 이 예외로 변환한다.
 *
 * HTTP 409 매핑은 컨트롤러/예외 핸들러 레이어(후속 task) 책임.
 *
 * @param key 중복이 발생한 프로젝트 key.
 */
class ProjectKeyAlreadyExistsException(key: String) :
    ProjectCreateDomainException("Project key already exists: $key")
