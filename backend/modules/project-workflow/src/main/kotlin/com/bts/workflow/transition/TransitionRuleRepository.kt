// 워크플로우 전환 규칙(validator·post-action) 2종이 공유하는 CRUD jOOQ 리포지토리 기반 클래스

package com.bts.workflow.transition

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.Table
import org.jooq.TableField
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * 전환 규칙 테이블 공통 CRUD 리포지토리 기반 클래스.
 *
 * 전환 규칙은 2종이다 — `workflow_validators`(전환 실행 전 검증)와 `workflow_post_actions`(전환 실행 후 처리).
 * V200 실측상 두 테이블의 컬럼 구성이 **완전히 같다** —
 * `id UUID PK` · `transition_id UUID NOT NULL FK(ON DELETE CASCADE)` · `type TEXT NOT NULL` ·
 * `config JSONB NOT NULL DEFAULT '{}'` · `display_order INTEGER NOT NULL DEFAULT 0` · `created_at` · `updated_at`.
 * 모양이 같으므로 CRUD 도 하나로 모으고, 테이블·컬럼만 생성자로 주입받아 규칙별 리포지토리가 그 위에 얹힌다.
 *
 * 행 타입도 [TransitionRuleRow] 하나로 통일한다. 규칙별 리포지토리는 `typealias` 로 자기 이름을 붙인다
 * (`typealias PostActionRow = TransitionRuleRow`). 필드 이름·순서·타입이 그대로라
 * **기존 호출부(서비스·DTO·테스트)가 한 줄도 바뀌지 않는다** — 운영 코드를 옮기는 이 리팩터의 회귀 방어선이다.
 * 이름을 바꾸거나 필드를 재배치하면 그 순간 회귀 표면이 호출부 수만큼 벌어진다.
 *
 * config 는 JSONB 컬럼으로, [ObjectMapper] 로 직렬화/역직렬화한다.
 *
 * **트랜잭션 경계는 여기가 아니라 구체 `@Repository` 클래스가 갖는다.** 기반 클래스는 구현만 갖는다.
 * ArchUnit 룰 1(`@Transactional` 메서드를 가진 구체 클래스는 Spring stereotype 필수)은 interface 만 예외로 두므로,
 * 빈이 아닌 추상 클래스에 `@Transactional` 을 두면 룰 위반이고 여기에 `@Repository` 를 다는 것은 장식일 뿐이다.
 *
 * 그럼에도 CRUD 4종이 `open` 인 이유. 하위 클래스가 `override` 로 경계를 얹으려면 열려 있어야 하고,
 * kotlin-spring(all-open) 은 `@Repository` 가 붙은 **그 클래스**의 멤버만 열 뿐 상위 클래스까지 거슬러 열지 않는다.
 * 기반 메서드가 final 이면 `override` 자체가 불가능하고, 상속만 했다면 Spring CGLIB 프록시가 재정의하지 못해
 * `@Transactional` 이 조용히 무력화된다 (`javap -p` 로 non-final 임을 실측해 대조한다).
 *
 * @param dsl jOOQ DSLContext. SQL 안전 바인딩(?-파라미터)에 사용.
 * @param objectMapper config Map ↔ JSON 변환용 Jackson ObjectMapper.
 * @param table 대상 전환 규칙 테이블 (`workflow_validators` 또는 `workflow_post_actions`).
 * @param idField 대상 테이블의 `id` 컬럼.
 * @param transitionIdField 대상 테이블의 `transition_id` 컬럼.
 * @param typeField 대상 테이블의 `type` 컬럼.
 * @param configField 대상 테이블의 `config` JSONB 컬럼.
 * @param displayOrderField 대상 테이블의 `display_order` 컬럼.
 */
@Suppress(
    // 테이블 1개 + 컬럼 5개 주입 — 묶음 VO 보다 명시적 시그니처가 하위 리포지토리 선언부에서 더 명료
    "LongParameterList",
    // 추상 멤버가 없어도 직접 인스턴스화 대상이 아니다. 규칙별 @Repository 빈만 상속해 쓰는 기반 클래스
    "UnnecessaryAbstractClass",
)
abstract class TransitionRuleRepository(
    protected val dsl: DSLContext,
    private val objectMapper: ObjectMapper,
    private val table: Table<*>,
    private val idField: TableField<*, UUID?>,
    private val transitionIdField: TableField<*, UUID?>,
    private val typeField: TableField<*, String?>,
    private val configField: TableField<*, JSONB?>,
    private val displayOrderField: TableField<*, Int?>,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val mapTypeRef = object : TypeReference<Map<String, Any?>>() {}

    /** 로그 메시지 접두 — 구체 리포지토리 이름(예: `PostActionRepository`). */
    private val repositoryName: String = javaClass.simpleName

    /** 예외 메시지에 쓰는 대상 테이블 이름(예: `workflow_post_actions`). */
    private val tableName: String = table.name

    /**
     * 전환 ID 에 속한 전환 규칙 목록을 display_order ASC 순으로 반환한다.
     *
     * @param transitionId 조회할 전환의 UUID.
     * @return [TransitionRuleRow] 목록. 없으면 빈 리스트.
     */
    open fun findByTransitionId(transitionId: UUID): List<TransitionRuleRow> {
        log.debug("{}.findByTransitionId transitionId={}", repositoryName, transitionId)
        return dsl
            .select(
                idField,
                transitionIdField,
                typeField,
                configField,
                displayOrderField,
            )
            .from(table)
            .where(transitionIdField.eq(transitionId))
            .orderBy(displayOrderField.asc())
            .fetch()
            .map { record ->
                val id =
                    record.get(idField)
                        ?: error("$tableName.${idField.name} null — transitionId=$transitionId")
                val transitionId =
                    record.get(transitionIdField)
                        ?: error("$tableName.${transitionIdField.name} null")
                val type =
                    record.get(typeField)
                        ?: error("$tableName.${typeField.name} null")
                TransitionRuleRow(
                    id = id,
                    transitionId = transitionId,
                    type = type,
                    config = parseJsonb(record.get(configField)),
                    displayOrder = record.get(displayOrderField) ?: 0,
                )
            }
    }

    /**
     * 전환 규칙 행을 삽입하고 삽입된 행을 반환한다.
     *
     * @param transitionId 소속 전환 UUID.
     * @param type 규칙 타입 식별자 (예: "CALL_WEBHOOK").
     * @param config 타입별 설정 Map.
     * @param displayOrder UI 표시 순서.
     * @return 삽입된 [TransitionRuleRow].
     */
    open fun insert(
        transitionId: UUID,
        type: String,
        config: Map<String, Any?>,
        displayOrder: Int,
    ): TransitionRuleRow {
        val configJson = objectMapper.writeValueAsString(config)
        val id =
            dsl.insertInto(table)
                .set(transitionIdField, transitionId)
                .set(typeField, type)
                .set(configField, JSONB.valueOf(configJson))
                .set(displayOrderField, displayOrder)
                .returningResult(idField)
                .fetchOne()
                ?.value1()
                ?: error("$tableName INSERT 실패 — transitionId=$transitionId type=$type")

        log.debug("{}.insert id={} transitionId={} type={}", repositoryName, id, transitionId, type)
        return TransitionRuleRow(
            id = id,
            transitionId = transitionId,
            type = type,
            config = config,
            displayOrder = displayOrder,
        )
    }

    /**
     * 전환 규칙 행을 수정하고 수정된 행을 반환한다.
     *
     * @param id 수정할 전환 규칙 UUID.
     * @param type 변경할 타입.
     * @param config 변경할 config Map.
     * @param displayOrder 변경할 displayOrder.
     * @return 수정된 [TransitionRuleRow].
     * @throws IllegalStateException 해당 id 가 존재하지 않을 때.
     */
    open fun update(
        id: UUID,
        type: String,
        config: Map<String, Any?>,
        displayOrder: Int,
    ): TransitionRuleRow {
        val configJson = objectMapper.writeValueAsString(config)
        val updated =
            dsl.update(table)
                .set(typeField, type)
                .set(configField, JSONB.valueOf(configJson))
                .set(displayOrderField, displayOrder)
                .where(idField.eq(id))
                .returningResult(transitionIdField)
                .fetchOne()
                ?: error("$tableName UPDATE 실패 — id=$id 가 존재하지 않음")

        val transitionId =
            updated.value1()
                ?: error("$tableName.${transitionIdField.name} null after UPDATE")

        log.debug("{}.update id={} type={}", repositoryName, id, type)
        return TransitionRuleRow(
            id = id,
            transitionId = transitionId,
            type = type,
            config = config,
            displayOrder = displayOrder,
        )
    }

    /**
     * 전환 규칙 행을 삭제한다. 존재하지 않는 id 는 no-op.
     *
     * @param id 삭제할 전환 규칙 UUID.
     */
    open fun deleteById(id: UUID) {
        log.debug("{}.deleteById id={}", repositoryName, id)
        dsl.deleteFrom(table)
            .where(idField.eq(id))
            .execute()
    }

    // ── private ──────────────────────────────────────────────────────────────

    /** JSONB 컬럼 값을 Map 으로 역직렬화한다. null 또는 빈 값이면 빈 Map 반환. */
    private fun parseJsonb(jsonb: JSONB?): Map<String, Any?> {
        val raw = jsonb?.data()?.takeIf { it.isNotBlank() } ?: return emptyMap()
        return try {
            objectMapper.readValue(raw, mapTypeRef)
        } catch (ex: com.fasterxml.jackson.core.JsonProcessingException) {
            log.warn("{}: JSONB 파싱 실패 raw='{}' error={}", repositoryName, raw, ex.message)
            emptyMap()
        }
    }
}

/**
 * 전환 규칙(`workflow_validators` · `workflow_post_actions`) 공통 행 데이터 클래스.
 *
 * 두 테이블의 컬럼이 같아 행 타입도 하나를 공유한다. 규칙별 이름은 `typealias` 로 붙인다.
 *
 * @property id 행 UUID PK.
 * @property transitionId 소속 전환 UUID FK.
 * @property type 전환 규칙 타입 식별자.
 * @property config 타입별 설정 Map.
 * @property displayOrder UI 표시 순서.
 */
data class TransitionRuleRow(
    val id: UUID,
    val transitionId: UUID,
    val type: String,
    val config: Map<String, Any?>,
    val displayOrder: Int,
)
