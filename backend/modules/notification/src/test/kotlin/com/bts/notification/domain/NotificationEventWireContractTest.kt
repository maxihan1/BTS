// 알림 이벤트 wire 문자열 3지점(발행측 카탈로그 · enum · 정책 시드 SQL) 교차 대조 회귀 가드

package com.bts.notification.domain

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 알림 이벤트 식별 문자열(wire value)이 **서로 다른 세 지점에 중복 하드코딩**되어 있고,
 * 그 셋이 어긋나도 프로덕션이 예외 없이 조용히 실패한다. 이 스펙은 그 세 지점을 대조한다.
 *
 * ## 세 지점
 * 1. **발행측 카탈로그** — issue-tracking `IssueDomainEvent.kt` 의 `@JsonSubTypes` 등록명 + `@JsonTypeName`.
 *    pgmq 메시지의 `"type"` 필드에 실제로 실려 나가는 값이다.
 * 2. **소비측 enum** — [NotificationEventType.wireValue].
 * 3. **정책 시드 SQL** — `db/migration/notification/V4xx` 의 `notification_policies.event_type` 값.
 *
 * ## 어긋나면 왜 조용한가 (이 가드가 존재하는 이유)
 * - `NotificationWorker` 는 `fromWire(type)` 가 `null` 이면 **예외 없이 메시지를 삭제**한다
 *   (지점 1 ↔ 2 불일치 → 알림이 통째로 사라지고 전 테스트는 green).
 * - `NotificationPolicyEvaluator` 는 wireValue 로 정책을 조회하고 못 찾으면 **수신자 0명**으로 끝난다
 *   (지점 2 ↔ 3 불일치 → 이벤트는 소비되지만 아무에게도 안 간다).
 *
 * 즉 세 지점 중 하나만 오타가 나도 증상은 "알림이 안 온다" 뿐이고 실패하는 테스트가 없다.
 *
 * ## BC 격리와 지점 1
 * notification BC 는 `com.bts.issue..` 를 직접 import 할 수 없다(ArchUnit 룰 1).
 * 그래서 지점 1 은 **소스 파일을 텍스트로 읽어** 대조한다
 * (identity-access `AuthEventEmitCoverageTest` 와 동일한 방식 — 컴파일 의존이 생기지 않는다).
 *
 * ## 공허한 통과(vacuous pass) 방어
 * 추출이 0건이면 차집합은 항상 비어 통과한다. 그래서 (a) 추출 집합이 비어 있지 않다는 단언과
 * (b) 문제의 값 자체가 추출됐다는 앵커 단언을 짝으로 둔다. 나아가 추출기가 오타를 실제로
 * 잡아내는지 인라인 fixture 로 검증한다(양성 대조군 — 마지막 describe 블록).
 */
class NotificationEventWireContractTest : DescribeSpec({

    describe("wire 문자열 3지점 교차 대조") {

        val sqlEventTypes = WireCatalog.policySeedEventTypes()
        val publishedSubTypes = WireCatalog.publishedSubTypeNames()
        val publishedTypeNames = WireCatalog.publishedTypeNames()
        val enumWireValues = NotificationEventType.entries.map { it.wireValue }.toSet()

        context("지점 2 (enum) ↔ 지점 3 (정책 시드 SQL)") {

            it("시드 SQL 에서 뽑은 event_type 집합이 비어 있지 않다 (비-공허 짝)") {
                withClue("추출 0건이면 아래 차집합 단언이 전부 공허하게 통과한다") {
                    sqlEventTypes.shouldNotBeEmpty()
                }
                sqlEventTypes shouldContain "issue.comment_deleted"
            }

            it("SQL 의 모든 event_type 이 fromWire 로 역매핑된다") {
                val unmapped = sqlEventTypes.filter { NotificationEventType.fromWire(it) == null }.sorted()
                withClue(
                    "정책 시드에만 있고 enum 에 없는 event_type 이다. " +
                        "NotificationPolicyEvaluator 가 이 정책을 못 찾아 수신자 0명으로 조용히 끝난다. " +
                        "enum 상수를 추가하거나 시드 SQL 의 오타를 고쳐라: $unmapped",
                ) {
                    unmapped shouldBe emptyList()
                }
            }

            it("enum 의 모든 wireValue 가 정책 시드 SQL 에 존재한다") {
                val unseeded = (enumWireValues - sqlEventTypes).sorted()
                withClue(
                    "enum 에만 있고 전역 기본 정책 시드가 없는 wireValue 다. " +
                        "이 이벤트는 소비되지만 매칭되는 정책이 없어 수신자 0명으로 끝난다. " +
                        "db/migration/notification 에 기본 정책을 시드하라: $unseeded",
                ) {
                    unseeded shouldBe emptyList()
                }
            }
        }

        context("지점 1 (issue-tracking 이벤트 카탈로그) ↔ 지점 2 (enum)") {

            it("발행측 소스에서 뽑은 이름 집합이 비어 있지 않다 (비-공허 짝)") {
                withClue("정규식이 하나도 못 잡으면 아래 대조가 전부 공허하게 통과한다") {
                    publishedSubTypes.shouldNotBeEmpty()
                    publishedTypeNames.shouldNotBeEmpty()
                }
                publishedSubTypes shouldContain "issue.comment_deleted"
                publishedTypeNames shouldContain "issue.comment_deleted"
            }

            it("@JsonSubTypes 등록명과 @JsonTypeName 선언명이 일치한다") {
                withClue(
                    "둘이 어긋나면 Jackson 이 역직렬화 시점에 타입을 못 찾는다. " +
                        "등록명만 있는 값=${(publishedSubTypes - publishedTypeNames).sorted()}, " +
                        "선언명만 있는 값=${(publishedTypeNames - publishedSubTypes).sorted()}",
                ) {
                    publishedSubTypes shouldBe publishedTypeNames
                }
            }

            it("enum 의 issue. 접두 wireValue 가 전부 발행측 카탈로그에 존재한다") {
                // 역방향(카탈로그 → enum)은 강제하지 않는다. issue.updated / issue.soft_deleted 처럼
                // 알림 대상이 아닌 이벤트가 정상적으로 존재하기 때문이다.
                val issueScoped = enumWireValues.filter { it.startsWith(ISSUE_PREFIX) }.toSet()
                withClue("접두 필터가 전부 걸러내면 차집합이 공허하게 통과한다") {
                    issueScoped.shouldNotBeEmpty()
                }

                val notPublished = (issueScoped - publishedSubTypes).sorted()
                withClue(
                    "enum 이 기대하는 wire 값을 issue-tracking 이 발행하지 않는다. " +
                        "NotificationWorker.fromWire 가 null 을 받아 메시지를 조용히 삭제한다: $notPublished",
                ) {
                    notPublished shouldBe emptyList()
                }
            }
        }
    }

    describe("판별식 양성 대조군 — 추출기가 오타를 실제로 잡아내는가") {

        it("시드 SQL 에 오타가 있으면 역매핑 불가로 잡힌다") {
            val extracted = WireCatalog.extractPolicyEventTypes(TYPO_POLICY_SQL)
            extracted shouldBe setOf("issue.comment_delted")
            extracted.filter { NotificationEventType.fromWire(it) == null } shouldBe listOf("issue.comment_delted")
        }

        it("발행측 소스에 오타가 있으면 등록명↔선언명 불일치로 잡힌다") {
            val subTypes = WireCatalog.extractSubTypeNames(TYPO_EVENT_SOURCE)
            val typeNames = WireCatalog.extractTypeNames(TYPO_EVENT_SOURCE)
            subTypes shouldBe setOf("issue.comment_delted")
            typeNames shouldBe setOf("issue.comment_deleted")
            (subTypes == typeNames) shouldBe false
        }

        it("발행측 소스에 오타가 있으면 enum 과의 차집합으로도 잡힌다") {
            val subTypes = WireCatalog.extractSubTypeNames(TYPO_EVENT_SOURCE)
            setOf(NotificationEventType.ISSUE_COMMENT_DELETED.wireValue) - subTypes shouldBe
                setOf("issue.comment_deleted")
        }

        it("INSERT 문이 없는 SQL 에서는 아무것도 추출하지 않는다") {
            WireCatalog.extractPolicyEventTypes(DDL_ONLY_SQL) shouldBe emptySet()
        }
    }
})

private const val ISSUE_PREFIX = "issue."

/** 양성 대조군 fixture — event_type 에 오타(`delted`)가 난 정책 시드. */
private val TYPO_POLICY_SQL =
    """
    INSERT INTO notification_policies (event_type, recipient_role, channel, enabled, project_key, created_by)
    VALUES
        ('issue.comment_delted', 'COMMENT_AUTHOR', 'IN_APP', TRUE, NULL, NULL)
    ON CONFLICT DO NOTHING;
    """.trimIndent()

/** 양성 대조군 fixture — 등록명에만 오타가 난 발행측 소스. */
private val TYPO_EVENT_SOURCE =
    """
    @JsonSubTypes(
        JsonSubTypes.Type(value = IssueCommentDeleted::class, name = "issue.comment_delted"),
    )
    sealed interface IssueDomainEvent

    @JsonTypeName("issue.comment_deleted")
    data class IssueCommentDeleted(val commentId: UUID) : IssueDomainEvent
    """.trimIndent()

/** 양성 대조군 fixture — INSERT 가 없는 DDL. 컬럼 목록을 event_type 으로 오인하면 안 된다. */
private val DDL_ONLY_SQL =
    """
    CREATE TABLE notification_policies (
        event_type VARCHAR(64) NOT NULL DEFAULT 'issue.created'
    );
    COMMENT ON COLUMN notification_policies.event_type IS '이벤트 유형 (예: issue.created)';
    """.trimIndent()

/**
 * 세 지점의 wire 문자열을 소스/리소스에서 텍스트로 추출한다.
 *
 * 추출 함수(`extract*`)는 순수 함수라 인라인 fixture 로 양성 대조군을 세울 수 있고,
 * 로딩 함수는 실제 파일을 찾아 그 위에 추출을 태운다.
 */
private object WireCatalog {
    /** `INSERT INTO notification_policies` 문 시작 지점. */
    private val POLICY_INSERT = Regex("INSERT\\s+INTO\\s+notification_policies\\b", RegexOption.IGNORE_CASE)

    /** VALUES 튜플의 첫 컬럼(= event_type) 문자열 리터럴. */
    private val TUPLE_FIRST_LITERAL = Regex("\\(\\s*'([^']+)'\\s*,")

    /** `JsonSubTypes.Type(value = X::class, name = "issue.y")` 의 등록명. */
    private val SUBTYPE_NAME = Regex("JsonSubTypes\\.Type\\([^)]*\\bname\\s*=\\s*\"([^\"]+)\"")

    /** `@JsonTypeName("issue.y")` 의 선언명. */
    private val TYPE_NAME = Regex("@JsonTypeName\\(\"([^\"]+)\"\\)")

    /** notification 모듈의 Flyway 마이그레이션 SQL 전체에서 정책 event_type 을 모은다. */
    fun policySeedEventTypes(): Set<String> =
        migrationDir().walkTopDown()
            .filter { it.isFile && it.extension == "sql" }
            .flatMap { extractPolicyEventTypes(it.readText()) }
            .toSet()

    fun publishedSubTypeNames(): Set<String> = extractSubTypeNames(issueDomainEventSource().readText())

    fun publishedTypeNames(): Set<String> = extractTypeNames(issueDomainEventSource().readText())

    /**
     * `INSERT INTO notification_policies ... ;` 구간만 잘라 각 VALUES 튜플의 첫 리터럴을 뽑는다.
     *
     * 구간을 자르는 이유. 같은 파일의 DDL(`DEFAULT ('x', ...)`)이나 주석을 event_type 으로 오인하지 않기 위함이다.
     * 컬럼 목록 `(event_type, recipient_role, ...)` 은 따옴표가 없어 [TUPLE_FIRST_LITERAL] 에 걸리지 않는다.
     */
    fun extractPolicyEventTypes(sql: String): Set<String> =
        POLICY_INSERT.findAll(sql)
            .map { sql.substring(it.range.first).substringBefore(';') }
            .flatMap { statement -> TUPLE_FIRST_LITERAL.findAll(statement).map { m -> m.groupValues[1] } }
            .toSet()

    fun extractSubTypeNames(source: String): Set<String> = SUBTYPE_NAME.captures(source)

    fun extractTypeNames(source: String): Set<String> = TYPE_NAME.captures(source)

    /** 첫 캡처 그룹만 모아 집합으로 돌려준다. */
    private fun Regex.captures(source: String): Set<String> = findAll(source).map { it.groupValues[1] }.toSet()

    private fun migrationDir(): File =
        resolve(
            "notification 마이그레이션 디렉터리",
            "src/main/resources/db/migration/notification",
            "modules/notification/src/main/resources/db/migration/notification",
            "backend/modules/notification/src/main/resources/db/migration/notification",
        )

    private fun issueDomainEventSource(): File =
        resolve(
            "issue-tracking IssueDomainEvent.kt",
            "../issue-tracking/src/main/kotlin/com/bts/issue/event/IssueDomainEvent.kt",
            "modules/issue-tracking/src/main/kotlin/com/bts/issue/event/IssueDomainEvent.kt",
            "backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/event/IssueDomainEvent.kt",
        )

    /**
     * 실행 디렉터리(Gradle 은 모듈 디렉터리, IDE 는 다를 수 있음) 기준 후보 경로 중 실재하는 첫 항목.
     *
     * 못 찾으면 skip 이 아니라 [error] 다. 조용히 건너뛰면 이 스펙 전체가 공허해진다.
     */
    private fun resolve(
        what: String,
        vararg relativePaths: String,
    ): File {
        val workingDir = System.getProperty("user.dir")
        val candidates: List<Path> = relativePaths.map { Paths.get(workingDir, it) }
        return candidates.map { it.toFile() }.firstOrNull { it.exists() }
            ?: error("$what 를 찾을 수 없음. user.dir=$workingDir candidates=$candidates")
    }
}
