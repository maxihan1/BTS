// 사용자 실재 검증 cross-BC 포트 — issue-tracking 이 assignee 존재 확인 및 멘션 username 해석에 사용

package com.bts.shared.user

import java.util.UUID

/**
 * 사용자 존재 여부 cross-BC 조회 포트 (FR-IS-03 Task 3 / FR-MN-01 Task 3 / ADR 2026-06-01-issue-assignee-user-lookup-port).
 *
 * issue-tracking BC 가 assignee 배정 전 사용자 실재를 검증하거나, 멘션된 username 을 userId 로 일괄 해석할 때
 * 이 인터페이스를 호출한다.
 * 구현체는 identity-access BC 가 제공하며, 양쪽 BC 는 shared-kernel 을 통해 간접 의존한다.
 * issue-tracking 은 identity-access 를 직접 gradle 의존하지 않는다.
 *
 * ### 의존 방향
 * ```
 * issue-tracking  ──(implementation)──▶  shared-kernel ◀──(implementation)──  identity-access
 * ```
 *
 * ### "실재" 정의
 * users 테이블에 해당 id 행이 존재하면 실재로 간주한다.
 * is_active / deleted_at 같은 소프트 삭제 컬럼은 users 테이블에 없으므로 행 존재 여부만 확인한다
 * (V001 스키마 기준 — id / username / email / display_name / created_at / updated_at).
 */
interface UserLookupPort {
    /**
     * 주어진 사용자 UUID 가 실재하는지(users 테이블 행 존재) 확인한다.
     *
     * 이 메서드는 읽기 전용이며 부수 효과가 없다.
     * 호출자는 트랜잭션 컨텍스트 없이도 호출할 수 있다.
     *
     * @param userId 확인할 사용자의 UUID
     * @return 행이 존재하면 true, 없으면 false
     */
    fun exists(userId: UUID): Boolean

    /**
     * 주어진 username 집합을 실재 사용자 id 로 일괄 해석한다.
     *
     * users 테이블에서 username 으로 조회해 `username -> UUID` 맵을 반환한다.
     * 미존재 username 은 결과 맵에서 제외된다 — 호출자가 명시적으로 드롭 처리.
     * 빈 입력 시 DB 쿼리 없이 빈 맵을 즉시 반환한다.
     *
     * ### 대소문자 무시(case-insensitive) 매칭
     * 구현체는 `WHERE LOWER(username) IN (:names)` LOWER 비교로 매칭하므로,
     * "Bob" 입력이 DB 의 "bob" 을 찾는다.
     * username UNIQUE 제약이 대소문자를 구분하므로 "Carol"/"carol" 이 동시에 존재할 수 있고,
     * 이 경우 "carol" 조회 시 두 row 가 모두 매칭된다(과다매칭). 이 동작은 알려진 트레이드오프로 고정한다.
     *
     * ### 성능
     * 멘션은 cap 50 으로 N 이 매우 작아 LOWER() 함수 인덱스 우회를 허용한다. 대량 호출 아님(cap 50).
     *
     * ### 실제 구현
     * [com.atlas.bts.identity.user.UserLookupAdapter] 가
     * `SELECT id, username FROM users WHERE LOWER(username) IN (:names)`
     * 단일 쿼리로 구현한다 (NamedParameterJdbcTemplate 컬렉션 바인딩). production 환경에서 이 default 구현이 호출되면 안 된다.
     *
     * ### 기본값 = emptyMap() 의 의미
     * 기존 테스트 파일 ~35 개가 `object : UserLookupPort { override fun exists(...) }` 인라인으로
     * 구현 중이다. 새 추상 메서드로 추가하면 이 파일들이 전부 컴파일 에러가 난다.
     * default `emptyMap()` 은 기존 fake 들을 보호하는 fail-safe 이며, 멘션 0건(미발행)으로 안전하게 동작한다.
     * production 유일 구현체는 [com.atlas.bts.identity.user.UserLookupAdapter] 로 override 한다.
     *
     * @param usernames 해석할 username 집합 (대소문자 무시 매칭 — LOWER 비교)
     * @return 실재하는 username 만 포함한 `username -> UUID` 맵 (순서 미보장, 키는 DB 원문 케이스)
     */
    fun findIdsByUsernames(usernames: Set<String>): Map<String, UUID> = emptyMap()

    /**
     * 주어진 사용자 UUID 집합을 표시명(display_name)으로 역방향 일괄 조회한다.
     *
     * 이슈 변경 이력(audit trail) 기록 시점에 assignee 표시명을 박제(스냅샷)하기 위해 사용된다.
     * 미존재 id 는 결과 맵에서 제외된다.
     *
     * 기본 구현은 빈 맵을 반환한다(fail-safe).
     * 빈 맵 반환은 표시명 미박제를 의미하며, 보안 판단에 영향을 주지 않는다.
     * production 환경에서는 반드시 override 해야 한다.
     *
     * 기존 fake(약 35개)가 exists 만 구현한 anonymous object 형태이므로
     * default 구현으로 추가해 컴파일 에러 없이 보호한다.
     *
     * @param ids 표시명을 조회할 사용자 UUID 집합
     * @return 실재하는 id 만 포함한 [UUID] to display_name 맵 (순서 미보장)
     */
    fun findDisplayNamesByIds(ids: Set<UUID>): Map<UUID, String> = emptyMap()

    /**
     * 주어진 사용자 UUID 의 이메일 주소를 조회한다.
     *
     * 알림(notification) BC 가 수신자 userId 로 이메일을 cross-BC 조회할 때 사용한다.
     * 미존재 id 이거나 이메일이 없으면 null 을 반환한다(fail-safe).
     * null 반환은 이메일 발송 생략을 의미하며, 보안 판단에 영향을 주지 않는다.
     *
     * ### 기본값 = null 의 의미
     * 기존 fake(약 35개)가 exists 만 구현한 anonymous object 형태이므로
     * default 구현으로 추가해 컴파일 에러 없이 보호한다.
     * production 환경에서 이 default 구현이 호출되면 안 된다.
     * production 유일 구현체는 [com.atlas.bts.identity.user.UserLookupAdapter] 가 override 한다.
     *
     * @param userId 이메일을 조회할 사용자 UUID
     * @return 해당 사용자의 이메일 주소, 미존재 시 null
     */
    fun findEmailById(userId: UUID): String? = null
}
