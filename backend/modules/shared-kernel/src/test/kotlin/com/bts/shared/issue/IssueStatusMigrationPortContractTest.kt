// 상태 이관 큐잉 cross-BC 쓰기 포트 계약 검증 — fail-closed · 매핑 목록(J7) · projectKeys 범위 강제
package com.bts.shared.issue

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.jvmErasure

/**
 * [IssueStatusMigrationPort] cross-BC 포트 계약 테스트 (FR-WF-07 D2 · 로드맵 PR 7 Task 2).
 *
 * Spring 컨텍스트 없이 순수 단위 테스트로 실행한다.
 *
 * 검증 항목.
 * - [IssueStatusMigrationPort] 에 default 구현이 0개다(fail-closed) — 어댑터 미결선이면 부팅이 실패해야 한다.
 * - [StatusMigrationCommand] 는 단일 `(from, to)` 쌍이 아니라 **매핑 목록**을 갖는다(J7).
 * - [StatusMigrationCommand] 는 대상 프로젝트 범위 [StatusMigrationCommand.projectKeys] 없이 만들 수 없다(F3).
 */
class IssueStatusMigrationPortContractTest {
    // ── fail-closed — default 구현 0개 (F1) ──────────────────────────────────

    @Test
    fun `IssueStatusMigrationPort 는 default 구현이 0개이고 반환 타입이 UUID 다`() {
        val port = IssueStatusMigrationPort::class.java

        // 비-공허 짝. 같은 판별기가 default 를 실제로 가진 기존 포트에서는 반드시 켜져야 한다 —
        // 이 단언이 false 면 아래 「0개」 검사는 존재하지 않는 것을 세는 가짜 그린이다.
        assertThat(hasDefaultImplementation(IssueImportPort::class.java))
            .`as`("판별기 자체가 default 를 탐지하는지 — IssueImportPort 는 default 를 가진 포트다")
            .isTrue()

        assertThat(port.isInterface).isTrue()
        assertThat(port.declaredMethods.map { it.name })
            .`as`("계약 메서드가 실제로 선언되어야 「default 0개」가 공허하지 않다")
            .contains("enqueueStatusMigration")
        assertThat(hasDefaultImplementation(port))
            .`as`("fail-closed — 어댑터 미결선 시 silent-drop 대신 부팅 실패여야 한다")
            .isFalse()

        val enqueue = port.declaredMethods.single { it.name == "enqueueStatusMigration" }
        assertThat(enqueue.returnType)
            .`as`("shared-kernel 은 BC 내부 타입(BulkOperationId 등)을 노출하지 않는다")
            .isEqualTo(UUID::class.java)
    }

    // ── 매핑 목록 — 단일 쌍이 아니다 (J7 · F2) ───────────────────────────────

    @Test
    fun `StatusMigrationCommand 는 단일 쌍이 아니라 상태별 매핑 목록을 갖는다`() {
        val cmd =
            StatusMigrationCommand(
                actorUserId = UUID.randomUUID(),
                projectKeys = setOf("PROJ"),
                mappings =
                    listOf(
                        StatusMigrationMapping(fromStatusKey = "in_review", toStatusKey = "in_progress"),
                        StatusMigrationMapping(fromStatusKey = "blocked", toStatusKey = "todo"),
                    ),
            )

        assertThat(cmd.mappings.map { it.fromStatusKey }).containsExactly("in_review", "blocked")
        assertThat(cmd.mappings.map { it.toStatusKey }).containsExactly("in_progress", "todo")

        val commandParams = primaryConstructorParameterNames(StatusMigrationCommand::class.java)
        assertThat(commandParams)
            .`as`("J7 — 빠지는 상태마다 옮길 곳을 각각 고른다. 커맨드에 from/to 가 직접 붙으면 단일 쌍이 된다")
            .doesNotContain("fromStatusKey", "toStatusKey")
    }

    // ── 대상 프로젝트 범위 강제 (F3 · G4) ────────────────────────────────────

    @Test
    fun `StatusMigrationCommand 는 projectKeys 범위 없이 만들 수 없다`() {
        val ctor = StatusMigrationCommand::class.primaryConstructor
        assertThat(ctor).isNotNull
        val params = ctor?.parameters.orEmpty()

        assertThat(params.map { it.name }).containsExactly("actorUserId", "projectKeys", "mappings")

        val projectKeys = params.single { it.name == "projectKeys" }
        assertThat(projectKeys.isOptional)
            .`as`("기본값이 있으면 범위를 생략한 채 부를 수 있다 — 상태 키가 전역이라 남의 프로젝트 이슈가 함께 옮겨진다")
            .isFalse()
        assertThat(projectKeys.type.jvmErasure.java).isEqualTo(Set::class.java)

        val cmd =
            StatusMigrationCommand(
                actorUserId = UUID.randomUUID(),
                projectKeys = setOf("ALPHA", "BETA"),
                mappings = listOf(StatusMigrationMapping(fromStatusKey = "done", toStatusKey = "todo")),
            )
        assertThat(cmd.projectKeys).containsExactlyInAnyOrder("ALPHA", "BETA")
    }

    // ── 판별기 ───────────────────────────────────────────────────────────────

    /**
     * 인터페이스에 default 구현이 하나라도 있는지 판별한다.
     *
     * Kotlin 2.0 은 `-Xjvm-default` 미지정 시 인터페이스 본문을 `DefaultImpls` 정적 클래스로 컴파일하므로
     * `Method.isDefault` 만 보면 항상 false 가 되어 검사가 공허해진다. 두 신호를 함께 본다.
     */
    private fun hasDefaultImplementation(type: Class<*>): Boolean =
        type.declaredMethods.any { it.isDefault } ||
            type.declaredClasses.any { it.simpleName == "DefaultImpls" }

    /** 주 생성자 파라미터 이름 목록. 주 생성자가 없으면 빈 목록. */
    private fun primaryConstructorParameterNames(type: Class<*>): List<String> =
        type.kotlin.primaryConstructor?.parameters?.mapNotNull { it.name }.orEmpty()
}
