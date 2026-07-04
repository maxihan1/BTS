// UserMappingNormalizer 단위 테스트 — normalize trim+lowercase·6종 작성자 필드 수집·null/blank 제외·대소문자 dedup (FR-IM-02 PR-B Task 3)

package com.bts.search.imports.mapping

import com.bts.search.imports.parse.ParsedImportAttachment
import com.bts.search.imports.parse.ParsedImportChangeGroup
import com.bts.search.imports.parse.ParsedImportComment
import com.bts.search.imports.parse.ParsedImportRow
import com.bts.search.imports.parse.ParsedImportWorklog
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

/**
 * [UserMappingNormalizer] 단위 테스트.
 *
 * 검증 범위.
 * - [UserMappingNormalizer.normalize] — trim + lowercase
 * - [UserMappingNormalizer.collectIdentifiers] — reporter/assignee/댓글/worklog/첨부/changelog
 *   6종 작성자 필드를 전부 정규화 수집
 * - null/blank 식별자는 수집에서 제외
 * - 대소문자만 다른 식별자는 정규화 후 1건으로 dedup
 */
class UserMappingNormalizerTest : DescribeSpec({

    // ── normalize ────────────────────────────────────────────────────────────────

    describe("UserMappingNormalizer.normalize") {
        it("앞뒤 공백을 제거하고 소문자로 변환한다") {
            UserMappingNormalizer.normalize("  Bob@X.com ") shouldBe "bob@x.com"
        }
    }

    // ── collectIdentifiers — 6종 작성자 필드 수집 ─────────────────────────────────

    describe("UserMappingNormalizer.collectIdentifiers — 6종 작성자 필드 수집") {
        it("reporter/assignee/댓글/worklog/첨부/changelog authorEmail 을 전부 정규화 수집한다") {
            val row =
                baseRow(
                    reporterEmail = " Reporter@Example.com ",
                    assigneeEmail = "Assignee@Example.com",
                    comments = listOf(ParsedImportComment(body = "c", authorEmail = "Commenter@Example.com", createdAt = null)),
                    worklogs =
                        listOf(
                            ParsedImportWorklog(timeSpentSeconds = 60, startedAt = null, authorEmail = "Worklogger@Example.com"),
                        ),
                    attachments =
                        listOf(
                            ParsedImportAttachment(
                                filename = "a.txt",
                                authorEmail = "Uploader@Example.com",
                                created = null,
                                mimeType = null,
                                sizeBytes = null,
                            ),
                        ),
                    changelog =
                        listOf(
                            ParsedImportChangeGroup(authorEmail = "Changer@Example.com", created = null, items = emptyList()),
                        ),
                )

            UserMappingNormalizer.collectIdentifiers(row) shouldBe
                setOf(
                    "reporter@example.com",
                    "assignee@example.com",
                    "commenter@example.com",
                    "worklogger@example.com",
                    "uploader@example.com",
                    "changer@example.com",
                )
        }
    }

    // ── null/blank 제외 ──────────────────────────────────────────────────────────

    describe("UserMappingNormalizer.collectIdentifiers — null/blank 식별자 제외") {
        it("reporter/assignee 가 null 이면 결과에서 제외된다") {
            val row = baseRow(reporterEmail = null, assigneeEmail = null)

            UserMappingNormalizer.collectIdentifiers(row) shouldBe emptySet()
        }

        it("공백 문자열 식별자는 제외된다") {
            val row = baseRow(reporterEmail = "   ", assigneeEmail = "assignee@example.com")

            UserMappingNormalizer.collectIdentifiers(row) shouldBe setOf("assignee@example.com")
        }
    }

    // ── 대소문자 변형 dedup ──────────────────────────────────────────────────────

    describe("UserMappingNormalizer.collectIdentifiers — 대소문자 변형 dedup") {
        it("대소문자만 다른 식별자는 정규화 후 1건으로 합쳐진다") {
            val row = baseRow(reporterEmail = "bob@x", assigneeEmail = "Bob@X")

            UserMappingNormalizer.collectIdentifiers(row) shouldBe setOf("bob@x")
        }
    }
})

/**
 * 테스트 전용 [ParsedImportRow] 빌더. 코어 필드는 최소값으로 고정하고 작성자 관련 필드만
 * 파라미터로 받는다.
 */
private fun baseRow(
    reporterEmail: String?,
    assigneeEmail: String?,
    comments: List<ParsedImportComment> = emptyList(),
    worklogs: List<ParsedImportWorklog> = emptyList(),
    attachments: List<ParsedImportAttachment> = emptyList(),
    changelog: List<ParsedImportChangeGroup> = emptyList(),
): ParsedImportRow =
    ParsedImportRow(
        rowNumber = 1,
        summary = "테스트 이슈",
        description = null,
        typeName = null,
        priorityName = null,
        reporterEmail = reporterEmail,
        assigneeEmail = assigneeEmail,
        labels = emptyList(),
        componentNames = emptyList(),
        comments = comments,
        worklogs = worklogs,
        attachments = attachments,
        changelog = changelog,
    )
