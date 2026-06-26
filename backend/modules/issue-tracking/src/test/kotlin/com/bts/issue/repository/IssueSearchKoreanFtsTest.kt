// FR-SR-04 Task 4 — 한글 전문 검색 30케이스 + EXPLAIN 인덱스 + 보안 + 인젝션 통합 테스트

package com.bts.issue.repository

import com.bts.issue.adapter.outbound.search.IssueSearchAdapter
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.shared.issue.IssueTypeId
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import com.bts.shared.permission.IssueSecurityAccess
import com.bts.shared.permission.IssueSecurityDirectory
import com.bts.shared.search.AqlField
import com.bts.shared.search.AqlNode
import com.bts.shared.search.AqlOperator
import com.bts.shared.search.AqlSort
import com.bts.shared.search.AqlValue
import com.bts.shared.search.IssueSearchPage
import com.bts.shared.search.IssueSearchQuery
import com.bts.shared.search.SortDirection
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.assertThrows
import java.sql.DriverManager
import java.util.UUID

/**
 * FR-SR-04 Task 4 — 한글 전문 검색 통합 테스트 (실DB Testcontainers).
 *
 * V032 STORED generated tsvector + GIN 인덱스(idx_issues_search_vector) +
 * trigram 보강 인덱스(idx_issues_description_trgm) 를 대상으로
 * IssueRepository.searchByAql 의 text ~ AQL 필드 변환을 end-to-end 검증한다.
 *
 * 검증 범주.
 * - GROUP A: 조사 변형 — trigram 경로 (A01~A08, 8개)
 * - GROUP B: 부분 문자열 매칭 (B01~B08, 8개)
 * - GROUP C: 영문/숫자/한글 혼용 (C01~C07, 7개)
 * - GROUP D: 다중 토큰 AND (D01~D04, 4개)
 * - GROUP NEG: 기대 미매칭 + 양성 대조군 (NEG01~NEG03, 3개)
 *   (활용형 변화로 trigram 겹침 부족 — ADR D1 의도적 trade-off)
 * - B2-EXPLAIN: GIN 인덱스명 plan 등장 단언
 * - S4 보안: BROWSE 게이트 + visibility 술어 필터
 * - EC5/C1: 미지원 연산자 + text 정렬 거부
 * - C4: SQL 인젝션 0 + LIKE 와일드카드 이스케이프 + 장문 DoS 무크래시
 */
@Suppress(
    "TooManyFunctions", // 30케이스 + EXPLAIN + 보안 + EC5 + C4 — 단일 기능 통합 테스트
    "LargeClass",       // 검증 범주 집약 — 분리 시 컨텍스트 분산으로 오히려 유지보수성 저하
)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class IssueSearchKoreanFtsTest : IssueTestcontainersBase() {

    private var taskTypeId: IssueTypeId? = null

    /** unrestricted=true 접근권한 — 보안등급 필터 미적용 빠른경로. */
    private val unrestrictedAccess =
        IssueSecurityAccess(
            unrestricted = true,
            staticLevelIds = emptySet(),
            reporterLevelIds = emptySet(),
            assigneeLevelIds = emptySet(),
        )

    @BeforeAll
    fun resolveTaskTypeId() {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "SELECT id FROM issue_types WHERE key = 'task' AND deleted_at IS NULL LIMIT 1",
            ).use { stmt ->
                stmt.executeQuery().use { rs ->
                    check(rs.next()) { "V003 마이그레이션에서 task 타입이 없습니다." }
                    taskTypeId = IssueTypeId(rs.getLong(1))
                }
            }
        }
    }

    private fun requireTaskTypeId(): IssueTypeId =
        requireNotNull(taskTypeId) { "taskTypeId 가 초기화되지 않았습니다." }

    /**
     * 테스트 이슈 삽입 헬퍼.
     *
     * securityLevelId 는 S4 보안 테스트에서만 사용한다.
     */
    private fun insertIssue(
        seq: Long,
        summary: String = "테스트 이슈 $seq",
        description: String? = null,
        securityLevelId: UUID? = null,
    ): Issue {
        val issue =
            Issue.create(
                id = IssueId(UUID.randomUUID()),
                key = IssueKey.of("TPRJ", seq),
                projectId = testProjectId,
                typeId = requireTaskTypeId(),
                summary = summary,
                reporterId = ActorId(UUID.randomUUID()),
                currentStateKey = "open",
                description = description,
                securityLevelId = securityLevelId,
            )
        return repository.insert(issue)
    }

    /**
     * text ~ AQL 조건으로 searchByAql 을 호출하는 헬퍼.
     *
     * BROWSE 게이트는 어댑터 레이어 담당이므로 unrestricted 접근권한으로 repository 직접 호출한다.
     */
    private fun searchByText(term: String): IssueSearchPage {
        val ast =
            AqlNode.Comparison(
                field = AqlField("text"),
                op = AqlOperator.CONTAINS,
                values = listOf(AqlValue.Str(term)),
            )
        return repository.searchByAql(
            projectKey = "TPRJ",
            ast = ast,
            sort = emptyList(),
            actor = UUID.randomUUID(),
            access = unrestrictedAccess,
            page = 0,
            size = 100,
        )
    }

    /**
     * EXPLAIN 실행 후 플랜 텍스트를 반환하는 헬퍼 (B2 인덱스 회귀 가드).
     *
     * SET enable_seqscan=off 금지 — 플래너가 자연히 인덱스를 선택하도록 충분한 행 시드 필요.
     * JDBC 3단계 중첩(connection/statement/resultset) 불가피.
     */
    @Suppress("NestedBlockDepth") // JDBC 3단계 중첩 불가피 — connection, statement, resultset
    private fun explainQuery(searchTerm: String): String {
        val pattern = "%$searchTerm%"
        return DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                """
                EXPLAIN
                SELECT i.id
                FROM issues i
                JOIN projects p ON i.project_id = p.id
                WHERE i.deleted_at IS NULL
                  AND p.key = 'TPRJ'
                  AND (
                    i.search_vector @@ plainto_tsquery('simple', ?)
                    OR lower(i.summary) LIKE ?
                    OR lower(i.description) LIKE ?
                  )
                """.trimIndent(),
            ).use { stmt ->
                stmt.setString(1, searchTerm)
                stmt.setString(2, pattern)
                stmt.setString(3, pattern)
                stmt.executeQuery().use { rs ->
                    buildString {
                        while (rs.next()) appendLine(rs.getString(1))
                    }
                }
            }
        }
    }

    /**
     * 대량 이슈 삽입 헬퍼 — B2 EXPLAIN 플래너가 GIN 인덱스를 선택하도록 충분한 행 제공.
     *
     * ANALYZE 를 마지막에 실행해 통계를 최신화한다.
     * JDBC 2단계 중첩(connection/statement) + 배치 삽입.
     */
    @Suppress("NestedBlockDepth") // JDBC 배치 삽입 — connection, statement 2단계
    private fun seedBulkIssues(count: Int) {
        val tid = requireTaskTypeId().value
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "INSERT INTO issues" +
                    " (id, key, project_id, type_id, summary, description, reporter_id, current_state_key, version)" +
                    " VALUES (?, ?, ?, ?, ?, ?, ?, 'open', 0)",
            ).use { stmt ->
                repeat(count) { idx ->
                    val n = idx + 1
                    stmt.setObject(1, UUID.randomUUID())
                    stmt.setString(2, "TPRJ-${n + 1000}")
                    stmt.setObject(3, testProjectId)
                    stmt.setLong(4, tid)
                    stmt.setString(5, "벌크 시드 이슈 $n")
                    stmt.setString(6, "벌크 시드 내용 $n — 다양한 일반 텍스트")
                    stmt.setObject(7, UUID.randomUUID())
                    stmt.addBatch()
                }
                stmt.executeBatch()
            }
            conn.createStatement().use { it.execute("ANALYZE issues") }
        }
    }

    // ── GROUP A: 조사 변형 — trigram 경로 (A01~A08) ────────────────────────────
    // simple tokenizer 는 조사를 분리하지 않으므로 FTS 는 미매칭.
    // pg_trgm LIKE '%어간%' 로 조사 포함 형태에서 어간 부분 매칭 → trigram 경로로 반환.

    @Test
    @Order(1)
    fun `KR-A-01 이슈 어간 검색은 이슈를 포함한 설명에서 trigram 으로 매칭된다`() {
        // "이슈를" 의 FTS 토큰은 "이슈를" 이므로 "이슈" 와 FTS 불일치 — trigram "%이슈%" 로 매칭.
        // 기본 제목 "테스트 이슈 N" 이 "이슈" 를 포함해 양쪽 모두 매칭되므로 명시적 neutral 제목 사용.
        insertIssue(seq = 1, summary = "항목 번호 1", description = "이슈를 처리해야 합니다")
        insertIssue(seq = 2, summary = "항목 번호 2", description = "관련 없는 내용입니다")

        val result = searchByText("이슈")

        assertThat(result.total).isEqualTo(1L)
        assertThat(result.items.first().summary).isEqualTo("항목 번호 1")
    }

    @Test
    @Order(2)
    fun `KR-A-02 로그인 어간 검색은 로그인을 포함한 설명에서 trigram 으로 매칭된다`() {
        insertIssue(seq = 1, description = "로그인을 시도했으나 실패했습니다")
        insertIssue(seq = 2, description = "비밀번호를 초기화했습니다")

        val result = searchByText("로그인")

        assertThat(result.total).isEqualTo(1L)
    }

    @Test
    @Order(3)
    fun `KR-A-03 사용자 어간 검색은 사용자가 포함된 설명에서 trigram 으로 매칭된다`() {
        insertIssue(seq = 1, description = "사용자가 오류를 보고했습니다")
        insertIssue(seq = 2, description = "관리자가 설정을 변경했습니다")

        val result = searchByText("사용자")

        assertThat(result.total).isEqualTo(1L)
    }

    @Test
    @Order(4)
    fun `KR-A-04 검색 어간 검색은 검색이 포함된 설명에서 trigram 으로 매칭된다`() {
        insertIssue(seq = 1, description = "검색이 제대로 동작하지 않습니다")
        insertIssue(seq = 2, description = "필터 기능이 비어 있습니다")

        val result = searchByText("검색")

        assertThat(result.total).isEqualTo(1L)
    }

    @Test
    @Order(5)
    fun `KR-A-05 오류 어간 검색은 오류가 포함된 제목에서 trigram 으로 매칭된다`() {
        // summary 의 trigram 인덱스(V031 idx_issues_summary_trgm) 경로 검증
        insertIssue(seq = 1, summary = "오류가 발생하는 버그", description = null)
        insertIssue(seq = 2, summary = "정상 동작 확인", description = null)

        val result = searchByText("오류")

        assertThat(result.total).isEqualTo(1L)
    }

    @Test
    @Order(6)
    fun `KR-A-06 버그 어간 검색은 버그가 포함된 제목에서 trigram 으로 매칭된다`() {
        insertIssue(seq = 1, summary = "버그가 보고됨")
        insertIssue(seq = 2, summary = "기능 개선 요청")

        val result = searchByText("버그")

        assertThat(result.total).isEqualTo(1L)
    }

    @Test
    @Order(7)
    fun `KR-A-07 기능 어간 검색은 기능이 포함된 설명에서 trigram 으로 매칭된다`() {
        insertIssue(seq = 1, description = "기능이 구현되지 않았습니다")
        insertIssue(seq = 2, description = "성능 문제가 발생했습니다")

        val result = searchByText("기능")

        assertThat(result.total).isEqualTo(1L)
    }

    @Test
    @Order(8)
    fun `KR-A-08 설정 어간 검색은 설정을 포함한 설명에서 trigram 으로 매칭된다`() {
        insertIssue(seq = 1, description = "설정을 변경했습니다")
        insertIssue(seq = 2, description = "연결이 끊겼습니다")

        val result = searchByText("설정")

        assertThat(result.total).isEqualTo(1L)
    }

    // ── GROUP B: 제목/본문 부분 문자열 매칭 (B01~B08) ──────────────────────────

    @Test
    @Order(11)
    fun `KR-B-01 제목에만 검색어가 있을 때 매칭된다`() {
        insertIssue(seq = 1, summary = "결제 처리 오류 수정", description = "관련 없는 본문")
        insertIssue(seq = 2, summary = "UI 개선 사항", description = "버튼 위치 변경")

        val result = searchByText("결제")

        assertThat(result.total).isEqualTo(1L)
    }

    @Test
    @Order(12)
    fun `KR-B-02 설명에만 검색어가 있을 때 매칭된다`() {
        insertIssue(seq = 1, summary = "일반 이슈", description = "결제 API 연동 오류가 발생합니다")
        insertIssue(seq = 2, summary = "다른 이슈", description = "세션 문제가 있습니다")

        val result = searchByText("결제")

        assertThat(result.total).isEqualTo(1L)
    }

    @Test
    @Order(13)
    fun `KR-B-03 제목과 설명 모두에 검색어가 있는 경우 양쪽 이슈 모두 반환된다`() {
        // 이슈 A: 제목에 "결제", 이슈 B: 설명에 "결제" — S3 시나리오
        insertIssue(seq = 1, summary = "결제 화면 버그", description = "재현 방법 확인 필요")
        insertIssue(seq = 2, summary = "회원가입 이슈", description = "결제 모듈 연동 실패")

        val result = searchByText("결제")

        assertThat(result.total).isEqualTo(2L)
    }

    @Test
    @Order(14)
    fun `KR-B-04 다중 토큰 FTS 검색은 설명 내 완전 일치 구문을 찾는다`() {
        // "토큰 만료": description 에 두 토큰이 공백으로 분리되어 FTS AND 매칭
        insertIssue(seq = 1, description = "토큰 만료 처리가 필요합니다")
        insertIssue(seq = 2, description = "세션 만료 처리 방법")

        val result = searchByText("토큰 만료")

        // "토큰 만료" 구문이 있는 seq=1 만 매칭 (seq=2 는 "세션 만료")
        assertThat(result.total).isEqualTo(1L)
    }

    @Test
    @Order(15)
    fun `KR-B-05 제목에서 다중 토큰 AND 검색이 동작한다`() {
        insertIssue(seq = 1, summary = "비밀번호 변경 기능 버그", description = null)
        insertIssue(seq = 2, summary = "프로필 사진 변경 오류", description = null)

        val result = searchByText("비밀번호 변경")

        assertThat(result.total).isEqualTo(1L)
    }

    @Test
    @Order(16)
    fun `KR-B-06 설명에서 다중 토큰 검색이 동작한다`() {
        insertIssue(seq = 1, description = "데이터베이스 연결 실패 오류")
        insertIssue(seq = 2, description = "파일 연결 문제가 발생했습니다")

        val result = searchByText("데이터베이스 연결")

        assertThat(result.total).isEqualTo(1L)
    }

    @Test
    @Order(17)
    fun `KR-B-07 조사 변형 포함 어간 검색은 제목에서도 trigram 으로 매칭된다`() {
        // "수정이" 의 FTS 토큰은 "수정이" 이나 "수정" 어간 검색은 trigram "%수정%" 으로 매칭
        insertIssue(seq = 1, summary = "수정이 필요한 이슈")
        insertIssue(seq = 2, summary = "배포 완료 확인")

        val result = searchByText("수정")

        assertThat(result.total).isEqualTo(1L)
    }

    @Test
    @Order(18)
    fun `KR-B-08 동일 검색어가 여러 이슈에서 매칭되면 모두 반환된다`() {
        insertIssue(seq = 1, summary = "인증 오류 발생")
        insertIssue(seq = 2, description = "인증을 처리하는 로직이 누락되었습니다")
        insertIssue(seq = 3, summary = "완전히 다른 이슈")

        val result = searchByText("인증")

        // seq=1 은 FTS "인증" 매칭, seq=2 는 "인증을" trigram 매칭
        assertThat(result.total).isEqualTo(2L)
    }

    // ── GROUP C: 영문/숫자/한글 혼용 (C01~C07) ─────────────────────────────────

    @Test
    @Order(21)
    fun `KR-C-01 영문 API 검색은 API 가 포함된 설명에서 매칭된다`() {
        // simple tokenizer 는 영문을 소문자화 — "API" → "api" 토큰
        insertIssue(seq = 1, description = "API 연동 오류 발생")
        insertIssue(seq = 2, description = "UI 레이아웃 문제")

        val result = searchByText("API")

        assertThat(result.total).isEqualTo(1L)
    }

    @Test
    @Order(22)
    fun `KR-C-02 영문 HTTP 검색은 HTTP 가 포함된 제목에서 매칭된다`() {
        insertIssue(seq = 1, summary = "HTTP 500 오류 발생")
        insertIssue(seq = 2, summary = "WebSocket 연결 오류")

        val result = searchByText("HTTP")

        assertThat(result.total).isEqualTo(1L)
    }

    @Test
    @Order(23)
    fun `KR-C-03 숫자와 한글 혼용 검색어는 설명에서 매칭된다`() {
        insertIssue(seq = 1, description = "3단계 인증이 필요합니다")
        insertIssue(seq = 2, description = "2단계 검증 구현")

        val result = searchByText("3단계")

        assertThat(result.total).isEqualTo(1L)
    }

    @Test
    @Order(24)
    fun `KR-C-04 버전 문자열을 포함한 혼용 검색어가 매칭된다`() {
        insertIssue(seq = 1, summary = "API v2 버전 오류")
        insertIssue(seq = 2, summary = "API v3 마이그레이션")

        val result = searchByText("v2")

        assertThat(result.total).isEqualTo(1L)
    }

    @Test
    @Order(25)
    fun `KR-C-05 한글과 영문 혼용 다중 토큰 AND 검색이 동작한다`() {
        insertIssue(seq = 1, description = "로그인 API 연동 문제")
        insertIssue(seq = 2, description = "결제 API 오류")

        val result = searchByText("로그인 API")

        // "로그인" AND "API(api)" 두 토큰 모두 포함하는 seq=1 만 매칭
        assertThat(result.total).isEqualTo(1L)
    }

    @Test
    @Order(26)
    fun `KR-C-06 영문 OAuth 검색어가 한글 설명과 혼합된 본문에서 매칭된다`() {
        insertIssue(seq = 1, description = "OAuth 인증 오류가 발생합니다")
        insertIssue(seq = 2, description = "JWT 토큰 만료 문제")

        val result = searchByText("OAuth")

        assertThat(result.total).isEqualTo(1L)
    }

    @Test
    @Order(27)
    fun `KR-C-07 영문과 한글이 혼재된 설명에서 한글 부분 검색이 동작한다`() {
        insertIssue(seq = 1, description = "null 포인터 예외 발생")
        insertIssue(seq = 2, description = "null 값 처리 누락")

        val result = searchByText("포인터")

        assertThat(result.total).isEqualTo(1L)
    }

    // ── GROUP D: 다중 토큰 AND (D01~D04) ───────────────────────────────────────
    // plainto_tsquery('simple', '토큰A 토큰B') 는 두 토큰의 AND 를 생성한다.

    @Test
    @Order(31)
    fun `KR-D-01 토큰 만료 다중 토큰 AND 검색이 정확히 매칭된다`() {
        insertIssue(seq = 1, description = "토큰 만료 처리가 필요합니다")
        insertIssue(seq = 2, description = "세션 만료 후 재연결")

        val result = searchByText("토큰 만료")

        assertThat(result.total).isEqualTo(1L)
    }

    @Test
    @Order(32)
    fun `KR-D-02 데이터베이스 연결 실패 복합 검색이 매칭된다`() {
        insertIssue(seq = 1, description = "데이터베이스 연결 실패 오류")
        insertIssue(seq = 2, description = "네트워크 연결 실패")

        val result = searchByText("데이터베이스 연결")

        assertThat(result.total).isEqualTo(1L)
    }

    @Test
    @Order(33)
    fun `KR-D-03 로그인 인증 복합 검색이 매칭된다`() {
        insertIssue(seq = 1, description = "로그인 인증 프로세스 버그")
        insertIssue(seq = 2, description = "인증 서버 연결 오류")

        val result = searchByText("로그인 인증")

        // "로그인" AND "인증" 두 토큰 모두 있는 seq=1 만 매칭
        assertThat(result.total).isEqualTo(1L)
    }

    @Test
    @Order(34)
    fun `KR-D-04 검색 결과 복합 단어가 제목에서 매칭된다`() {
        insertIssue(seq = 1, summary = "검색 결과 표시 오류")
        insertIssue(seq = 2, summary = "결과 없음 UI 개선")

        val result = searchByText("검색 결과")

        assertThat(result.total).isEqualTo(1L)
    }

    // ── GROUP NEG: 기대 미매칭 + 양성 대조군 (NEG01~NEG03) ────────────────────
    // 어간이 바뀌는 활용형은 trigram 겹침 부족으로 미매칭 (ADR D1 의도적 trade-off).
    // 각 케이스에 동일 시드로 매칭되는 양성 대조군을 짝지어 vacuous green 방지 (C2).

    @Test
    @Order(41)
    fun `KR-NEG-01 어간 변화 과거형과 현재형은 trigram 겹침 부족으로 미매칭이다`() {
        // 시드: "먹었다" (과거형) / 탐색: "먹는다" (현재형) — 공유 trigram 없음
        insertIssue(seq = 1, description = "어제 점심을 먹었다")

        val negResult = searchByText("먹는다")
        assertThat(negResult.items).describedAs("'먹는다' 는 '먹었다' 에 없어야 한다").isEmpty()

        // 양성 대조군: 동일 시드에서 정확 형태 검색 → 반드시 매칭
        val posResult = searchByText("먹었다")
        assertThat(posResult.items).describedAs("양성 대조군 '먹었다' 는 매칭돼야 한다").isNotEmpty()
    }

    @Test
    @Order(42)
    fun `KR-NEG-02 어간 변화 완료형과 진행형은 trigram 겹침 부족으로 미매칭이다`() {
        // 시드: "읽었습니다" / 탐색: "읽고있다" — 공유 trigram 없음
        insertIssue(seq = 1, description = "파일을 읽었습니다")

        val negResult = searchByText("읽고있다")
        assertThat(negResult.items).describedAs("'읽고있다' 는 '읽었습니다' 에 없어야 한다").isEmpty()

        val posResult = searchByText("읽었습니다")
        assertThat(posResult.items).describedAs("양성 대조군 '읽었습니다' 는 매칭돼야 한다").isNotEmpty()
    }

    @Test
    @Order(43)
    fun `KR-NEG-03 어간 변화 과거 완료형과 명령형은 trigram 겹침 부족으로 미매칭이다`() {
        // 시드: "받았습니다" / 탐색: "받으세요" — 공유 trigram 없음
        insertIssue(seq = 1, description = "데이터를 받았습니다")

        val negResult = searchByText("받으세요")
        assertThat(negResult.items).describedAs("'받으세요' 는 '받았습니다' 에 없어야 한다").isEmpty()

        val posResult = searchByText("받았습니다")
        assertThat(posResult.items).describedAs("양성 대조군 '받았습니다' 는 매칭돼야 한다").isNotEmpty()
    }

    // ── B2-EXPLAIN: GIN 인덱스 회귀 가드 ──────────────────────────────────────
    // SET enable_seqscan=off 금지 — 1000행 시드 + ANALYZE 로 플래너가 자연히 인덱스 선택.
    // (1) idx_issues_description_trgm: 조사변형 trigram-전용 경로 가드 (B1 fix).
    // (2) idx_issues_search_vector: FTS GIN 회귀 가드.

    @Test
    @Order(51)
    fun `B2-EXPLAIN GIN 인덱스가 플랜에 나타난다 — idx_issues_description_trgm 과 idx_issues_search_vector`() {
        // 1000행 시드 + ANALYZE → 플래너 통계 갱신 (seqscan보다 GIN 선호)
        seedBulkIssues(1000)

        val plan = explainQuery("로그인")

        // (1) description trigram GIN 인덱스 (B1 수정 회귀 가드: coalesce 없이 lower(description))
        assertThat(plan)
            .describedAs("EXPLAIN 플랜에 idx_issues_description_trgm 이 나타나야 한다")
            .contains("idx_issues_description_trgm")

        // (2) search_vector FTS GIN 인덱스
        assertThat(plan)
            .describedAs("EXPLAIN 플랜에 idx_issues_search_vector 이 나타나야 한다")
            .contains("idx_issues_search_vector")
    }

    // ── S4 보안: BROWSE 게이트 + visibility 술어 필터 ───────────────────────────

    @Test
    @Order(61)
    fun `SEC-01 BROWSE 권한 없는 actor 의 검색 요청은 SecurityException 이 발생한다`() {
        // permissionResolver 가 BROWSE = false → SecurityException (probe 차단)
        val permResolver = mockk<IssuePermissionResolver>()
        val secDirectory = mockk<IssueSecurityDirectory>()
        every { permResolver.hasPermission(any(), IssuePermission.BROWSE, any(IssueScope::class)) } returns false

        val adapter = IssueSearchAdapter(repository, secDirectory, permResolver)

        assertThrows<SecurityException> {
            adapter.search(
                IssueSearchQuery(
                    projectKey = "TPRJ",
                    ast = AqlNode.Comparison(
                        AqlField("text"), AqlOperator.CONTAINS, listOf(AqlValue.Str("이슈")),
                    ),
                    sort = emptyList(),
                    viewerUserId = UUID.randomUUID(),
                    page = 0,
                    size = 20,
                ),
            )
        }
    }

    @Test
    @Order(62)
    fun `SEC-02 접근 불가 보안등급 이슈는 text 검색 결과에서 제외된다`() {
        val restrictedLevel = UUID.randomUUID()

        // 보안 등급이 있는 이슈 시드 (매칭되지만 결과에서 제외돼야 함)
        insertIssue(seq = 1, description = "프로젝트 이슈를 확인하세요", securityLevelId = restrictedLevel)
        // 공개 이슈 시드 (매칭 + 결과 포함)
        insertIssue(seq = 2, description = "이슈 목록을 검토합니다")

        // restrictedLevel 이 어떤 접근 집합에도 없는 제한 access
        val restrictedAccess =
            IssueSecurityAccess(
                unrestricted = false,
                staticLevelIds = emptySet(),
                reporterLevelIds = emptySet(),
                assigneeLevelIds = emptySet(),
            )

        val ast =
            AqlNode.Comparison(
                field = AqlField("text"),
                op = AqlOperator.CONTAINS,
                values = listOf(AqlValue.Str("이슈")),
            )
        val result =
            repository.searchByAql(
                projectKey = "TPRJ",
                ast = ast,
                sort = emptyList(),
                actor = UUID.randomUUID(),
                access = restrictedAccess,
                page = 0,
                size = 50,
            )

        // seq=1 은 restrictedLevel 로 제외, seq=2 만 반환
        assertThat(result.total).isEqualTo(1L)
        assertThat(result.items.first().summary).isEqualTo("테스트 이슈 2")
    }

    // ── EC5/C1: 미지원 연산자 + text 정렬 거부 ─────────────────────────────────

    @Test
    @Order(71)
    fun `EC5-01 text 필드에 EQ 연산자 사용 시 IllegalArgumentException 이 발생한다`() {
        // AqlFields.isOperatorForbidden("text", EQ) == true → 어댑터 validateComparison 에서 거부
        val permResolver = mockk<IssuePermissionResolver>()
        val secDirectory = mockk<IssueSecurityDirectory>()
        every { permResolver.hasPermission(any(), IssuePermission.BROWSE, any(IssueScope::class)) } returns true
        every { secDirectory.accessibleLevels(any(), any()) } returns unrestrictedAccess

        val adapter = IssueSearchAdapter(repository, secDirectory, permResolver)

        assertThrows<IllegalArgumentException> {
            adapter.search(
                IssueSearchQuery(
                    projectKey = "TPRJ",
                    ast = AqlNode.Comparison(
                        AqlField("text"), AqlOperator.EQ, listOf(AqlValue.Str("x")),
                    ),
                    sort = emptyList(),
                    viewerUserId = UUID.randomUUID(),
                    page = 0,
                    size = 20,
                ),
            )
        }
    }

    @Test
    @Order(72)
    fun `EC5-02 text 필드 ORDER BY 사용 시 IllegalArgumentException 이 발생한다`() {
        // total > 0 이어야 buildOrderBy 가 호출되므로 매칭 이슈 먼저 삽입
        insertIssue(seq = 1, summary = "이슈 정렬 테스트", description = "이슈 설명")

        assertThrows<IllegalArgumentException> {
            repository.searchByAql(
                projectKey = "TPRJ",
                ast = AqlNode.Comparison(
                    AqlField("text"), AqlOperator.CONTAINS, listOf(AqlValue.Str("이슈")),
                ),
                sort = listOf(AqlSort(AqlField("text"), SortDirection.ASC)),
                actor = UUID.randomUUID(),
                access = unrestrictedAccess,
                page = 0,
                size = 20,
            )
        }
    }

    // ── C4: 인젝션 + LIKE 와일드카드 이스케이프 + 장문 DoS 무크래시 ─────────────

    @Test
    @Order(81)
    fun `C4-INJ-01 SQL 메타문자 포함 검색어가 인젝션 없이 안전하게 처리된다`() {
        insertIssue(seq = 1, description = "이슈 설명")

        // 단따옴표/OR/DROP TABLE/주석 포함 SQL 인젝션 시도 — JDBC 바인딩으로 무력화
        val maliciousTerm = "이슈' OR 1=1; DROP TABLE issues; --"
        val result = searchByText(maliciousTerm)

        // 인젝션 성공 시 1건 이상 반환 — 0건이면 인젝션 방어 성공
        assertThat(result.items).isEmpty()
        assertThat(result.total).isEqualTo(0L)
    }

    @Test
    @Order(82)
    fun `C4-INJ-02 LIKE 와일드카드 포함 검색어는 이스케이프되어 의도치 않은 전체 매칭이 없다`() {
        // 설명에 '_' 가 없는 이슈 3건 시드.
        // 이스케이프 미적용 시 '%_%' = 1글자 이상인 모든 행 매칭 (LIKE '_' 주입 성공 시 3건 반환).
        // 이스케이프 적용 시 '%\_%' = 리터럴 '_' 없음 → 0건.
        // FTS 경로: plainto_tsquery('simple', '_') 는 '_' lexeme 가 설명에 없어 0건.
        insertIssue(seq = 1, description = "데이터 처리 방법")
        insertIssue(seq = 2, description = "오류 수정 완료")
        insertIssue(seq = 3, description = "설정 확인 필요")

        // '_' 검색: escapeIlikePrefix 로 '_' → '\_' 리터럴화 → 설명에 리터럴 '_' 없음 → 0건
        val result = searchByText("_")

        assertThat(result.items).describedAs("언더스코어는 이스케이프되어 0건이어야 한다").isEmpty()
        assertThat(result.total).isEqualTo(0L)
    }

    @Test
    @Order(83)
    fun `C4-DOS-01 2000자 초과 장문 검색어가 예외 없이 처리된다`() {
        insertIssue(seq = 1, description = "일반 이슈 설명")

        // 3000자 한글 문자열 — 실제 DoS 가드는 컨트롤러 레이어(2000자 제한)에서 처리
        // 이 테스트는 repository 레이어가 장문 입력에서 크래시 없음을 보장한다
        val longTerm = "가".repeat(3000)
        val result = searchByText(longTerm)

        // 크래시 없이 반환 (실제로는 매칭 결과 없음)
        assertThat(result.total).isEqualTo(0L)
    }
}
