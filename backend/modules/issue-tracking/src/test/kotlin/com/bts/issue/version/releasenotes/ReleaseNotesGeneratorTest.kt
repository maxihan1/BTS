// ReleaseNotesGenerator 순수 함수 단위 테스트 — Markdown 조립 결과 검증
package com.bts.issue.version.releasenotes

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.time.LocalDate

/**
 * [ReleaseNotesGenerator.generate] 단위 테스트.
 *
 * Spring 컨텍스트 없이 순수 단위 테스트로 실행한다.
 *
 * 검증 대상.
 * - spec §5 Markdown 템플릿 타이틀·메타 3줄 생성
 * - 타입별 그룹핑 + (hierarchyLevel asc, typeName asc) 그룹 순서
 * - 그룹 내 이슈 key 오름차순 정렬
 * - resolutionName 있으면 항목 끝에 ` (resolutionName)` 표시
 * - resolutionName 없으면 표시 없음
 * - 이슈 0건 → `포함된 이슈가 없습니다.` 한 줄(그룹 섹션 없음)
 * - summary 내 줄바꿈(`\r\n`/`\n`/`\r`) → 공백 치환
 * - releaseDate null → `미지정`
 * - 동일 typeKey 다수 → 한 그룹으로 집계
 */
class ReleaseNotesGeneratorTest : DescribeSpec({

    describe("ReleaseNotesGenerator.generate") {

        context("제목 및 메타 블록") {

            it("제목이 '# {projectKey} {versionName} 릴리즈 노트' 형식으로 생성된다") {
                val input = ReleaseNotesInput(
                    projectKey = "ATLAS",
                    versionName = "1.2.0",
                    versionStatus = "RELEASED",
                    releaseDate = LocalDate.of(2026, 6, 10),
                    issues = emptyList(),
                )
                val result = ReleaseNotesGenerator.generate(input)

                result.lines().first() shouldBe "# ATLAS 1.2.0 릴리즈 노트"
            }

            it("메타 3줄 — 상태·릴리즈일·포함 이슈 건수를 포함한다") {
                val input = ReleaseNotesInput(
                    projectKey = "ATLAS",
                    versionName = "1.2.0",
                    versionStatus = "RELEASED",
                    releaseDate = LocalDate.of(2026, 6, 10),
                    issues = emptyList(),
                )
                val result = ReleaseNotesGenerator.generate(input)

                result shouldContain "- 상태: RELEASED"
                result shouldContain "- 릴리즈일: 2026-06-10"
                result shouldContain "- 포함 이슈: 0건"
            }

            it("releaseDate 가 null 이면 릴리즈일을 '미지정'으로 표시한다") {
                val input = ReleaseNotesInput(
                    projectKey = "ATLAS",
                    versionName = "1.2.0",
                    versionStatus = "UNRELEASED",
                    releaseDate = null,
                    issues = emptyList(),
                )
                val result = ReleaseNotesGenerator.generate(input)

                result shouldContain "- 릴리즈일: 미지정"
            }
        }

        context("이슈 0건") {

            it("그룹 섹션 없이 '포함된 이슈가 없습니다.' 한 줄을 출력한다") {
                val input = ReleaseNotesInput(
                    projectKey = "ATLAS",
                    versionName = "1.2.0",
                    versionStatus = "UNRELEASED",
                    releaseDate = null,
                    issues = emptyList(),
                )
                val result = ReleaseNotesGenerator.generate(input)

                result shouldContain "포함된 이슈가 없습니다."
                result shouldNotContain "## "
            }
        }

        context("타입별 그룹핑") {

            it("같은 typeKey 의 이슈를 하나의 그룹으로 묶는다") {
                val input = ReleaseNotesInput(
                    projectKey = "ATLAS",
                    versionName = "1.0.0",
                    versionStatus = "RELEASED",
                    releaseDate = LocalDate.of(2026, 6, 1),
                    issues = listOf(
                        ReleaseNoteIssue(
                            key = "ATLAS-1",
                            summary = "첫 번째 버그 수정",
                            typeKey = "bug",
                            typeName = "Bug",
                            hierarchyLevel = 0,
                            resolutionName = null,
                        ),
                        ReleaseNoteIssue(
                            key = "ATLAS-2",
                            summary = "두 번째 버그 수정",
                            typeKey = "bug",
                            typeName = "Bug",
                            hierarchyLevel = 0,
                            resolutionName = null,
                        ),
                    ),
                )
                val result = ReleaseNotesGenerator.generate(input)

                // Bug 그룹이 1개만 생성돼야 한다
                result.lines().count { it.startsWith("## Bug") } shouldBe 1
                result shouldContain "## Bug (2)"
            }

            it("그룹 헤더가 '## {typeName} ({건수})' 형식으로 생성된다") {
                val input = ReleaseNotesInput(
                    projectKey = "ATLAS",
                    versionName = "1.0.0",
                    versionStatus = "RELEASED",
                    releaseDate = null,
                    issues = listOf(
                        ReleaseNoteIssue(
                            key = "ATLAS-3",
                            summary = "새 기능",
                            typeKey = "story",
                            typeName = "Story",
                            hierarchyLevel = 0,
                            resolutionName = null,
                        ),
                    ),
                )
                val result = ReleaseNotesGenerator.generate(input)

                result shouldContain "## Story (1)"
            }

            it("그룹 순서는 hierarchyLevel 오름차순, 동률은 typeName 오름차순이다") {
                val input = ReleaseNotesInput(
                    projectKey = "ATLAS",
                    versionName = "1.0.0",
                    versionStatus = "RELEASED",
                    releaseDate = null,
                    issues = listOf(
                        ReleaseNoteIssue(
                            key = "ATLAS-10",
                            summary = "에픽 이슈",
                            typeKey = "epic",
                            typeName = "Epic",
                            hierarchyLevel = 1,
                            resolutionName = null,
                        ),
                        ReleaseNoteIssue(
                            key = "ATLAS-20",
                            summary = "버그 수정",
                            typeKey = "bug",
                            typeName = "Bug",
                            hierarchyLevel = 0,
                            resolutionName = null,
                        ),
                        ReleaseNoteIssue(
                            key = "ATLAS-30",
                            summary = "서브태스크",
                            typeKey = "subtask",
                            typeName = "Subtask",
                            hierarchyLevel = -1,
                            resolutionName = null,
                        ),
                        ReleaseNoteIssue(
                            key = "ATLAS-40",
                            summary = "스토리",
                            typeKey = "story",
                            typeName = "Story",
                            hierarchyLevel = 0,
                            resolutionName = null,
                        ),
                    ),
                )
                val result = ReleaseNotesGenerator.generate(input)

                val lines = result.lines()
                val subtaskIdx = lines.indexOfFirst { it.startsWith("## Subtask") }
                val bugIdx = lines.indexOfFirst { it.startsWith("## Bug") }
                val storyIdx = lines.indexOfFirst { it.startsWith("## Story") }
                val epicIdx = lines.indexOfFirst { it.startsWith("## Epic") }

                // hierarchyLevel: Subtask(-1) < Bug(0) = Story(0) < Epic(1)
                // Bug(0) vs Story(0): typeName 오름차순 → Bug < Story
                (subtaskIdx < bugIdx) shouldBe true
                (bugIdx < storyIdx) shouldBe true
                (storyIdx < epicIdx) shouldBe true
            }
        }

        context("그룹 내 이슈 정렬") {

            it("그룹 내 이슈는 key 오름차순으로 정렬된다") {
                val input = ReleaseNotesInput(
                    projectKey = "ATLAS",
                    versionName = "1.0.0",
                    versionStatus = "RELEASED",
                    releaseDate = null,
                    issues = listOf(
                        ReleaseNoteIssue(
                            key = "ATLAS-3",
                            summary = "세 번째",
                            typeKey = "bug",
                            typeName = "Bug",
                            hierarchyLevel = 0,
                            resolutionName = null,
                        ),
                        ReleaseNoteIssue(
                            key = "ATLAS-1",
                            summary = "첫 번째",
                            typeKey = "bug",
                            typeName = "Bug",
                            hierarchyLevel = 0,
                            resolutionName = null,
                        ),
                        ReleaseNoteIssue(
                            key = "ATLAS-2",
                            summary = "두 번째",
                            typeKey = "bug",
                            typeName = "Bug",
                            hierarchyLevel = 0,
                            resolutionName = null,
                        ),
                    ),
                )
                val result = ReleaseNotesGenerator.generate(input)

                val lines = result.lines()
                val atlas1Idx = lines.indexOfFirst { it.contains("ATLAS-1") }
                val atlas2Idx = lines.indexOfFirst { it.contains("ATLAS-2") }
                val atlas3Idx = lines.indexOfFirst { it.contains("ATLAS-3") }

                (atlas1Idx < atlas2Idx) shouldBe true
                (atlas2Idx < atlas3Idx) shouldBe true
            }
        }

        context("이슈 항목 형식") {

            it("항목이 '- {key} {summary}' 형식으로 생성된다") {
                val input = ReleaseNotesInput(
                    projectKey = "ATLAS",
                    versionName = "1.0.0",
                    versionStatus = "RELEASED",
                    releaseDate = null,
                    issues = listOf(
                        ReleaseNoteIssue(
                            key = "ATLAS-5",
                            summary = "로그인 버그 수정",
                            typeKey = "bug",
                            typeName = "Bug",
                            hierarchyLevel = 0,
                            resolutionName = null,
                        ),
                    ),
                )
                val result = ReleaseNotesGenerator.generate(input)

                result shouldContain "- ATLAS-5 로그인 버그 수정"
            }

            it("resolutionName 이 있으면 항목 끝에 ' ({resolutionName})' 을 표시한다") {
                val input = ReleaseNotesInput(
                    projectKey = "ATLAS",
                    versionName = "1.0.0",
                    versionStatus = "RELEASED",
                    releaseDate = null,
                    issues = listOf(
                        ReleaseNoteIssue(
                            key = "ATLAS-5",
                            summary = "로그인 버그 수정",
                            typeKey = "bug",
                            typeName = "Bug",
                            hierarchyLevel = 0,
                            resolutionName = "Fixed",
                        ),
                    ),
                )
                val result = ReleaseNotesGenerator.generate(input)

                result shouldContain "- ATLAS-5 로그인 버그 수정 (Fixed)"
            }

            it("resolutionName 이 null 이면 접미사를 붙이지 않는다") {
                val input = ReleaseNotesInput(
                    projectKey = "ATLAS",
                    versionName = "1.0.0",
                    versionStatus = "RELEASED",
                    releaseDate = null,
                    issues = listOf(
                        ReleaseNoteIssue(
                            key = "ATLAS-6",
                            summary = "대시보드 개선",
                            typeKey = "story",
                            typeName = "Story",
                            hierarchyLevel = 0,
                            resolutionName = null,
                        ),
                    ),
                )
                val result = ReleaseNotesGenerator.generate(input)

                result shouldContain "- ATLAS-6 대시보드 개선"
                result shouldNotContain "- ATLAS-6 대시보드 개선 ("
            }
        }

        context("summary 줄바꿈 치환") {

            it("summary 내 \\n 을 공백으로 치환한다") {
                val input = ReleaseNotesInput(
                    projectKey = "ATLAS",
                    versionName = "1.0.0",
                    versionStatus = "RELEASED",
                    releaseDate = null,
                    issues = listOf(
                        ReleaseNoteIssue(
                            key = "ATLAS-7",
                            summary = "첫 줄\n두 번째 줄",
                            typeKey = "task",
                            typeName = "Task",
                            hierarchyLevel = 0,
                            resolutionName = null,
                        ),
                    ),
                )
                val result = ReleaseNotesGenerator.generate(input)

                result shouldContain "- ATLAS-7 첫 줄 두 번째 줄"
            }

            it("summary 내 \\r\\n 을 공백으로 치환한다") {
                val input = ReleaseNotesInput(
                    projectKey = "ATLAS",
                    versionName = "1.0.0",
                    versionStatus = "RELEASED",
                    releaseDate = null,
                    issues = listOf(
                        ReleaseNoteIssue(
                            key = "ATLAS-8",
                            summary = "윈도우\r\n줄바꿈",
                            typeKey = "task",
                            typeName = "Task",
                            hierarchyLevel = 0,
                            resolutionName = null,
                        ),
                    ),
                )
                val result = ReleaseNotesGenerator.generate(input)

                result shouldContain "- ATLAS-8 윈도우 줄바꿈"
            }

            it("summary 내 \\r 을 공백으로 치환한다") {
                val input = ReleaseNotesInput(
                    projectKey = "ATLAS",
                    versionName = "1.0.0",
                    versionStatus = "RELEASED",
                    releaseDate = null,
                    issues = listOf(
                        ReleaseNoteIssue(
                            key = "ATLAS-9",
                            summary = "CR만\r있는 경우",
                            typeKey = "task",
                            typeName = "Task",
                            hierarchyLevel = 0,
                            resolutionName = null,
                        ),
                    ),
                )
                val result = ReleaseNotesGenerator.generate(input)

                result shouldContain "- ATLAS-9 CR만 있는 경우"
            }
        }

        context("복합 시나리오") {

            it("여러 타입 + resolution 혼재 — spec §5 해피패스 전체 구조를 검증한다") {
                val input = ReleaseNotesInput(
                    projectKey = "ATLAS",
                    versionName = "2.0.0",
                    versionStatus = "RELEASED",
                    releaseDate = LocalDate.of(2026, 6, 10),
                    issues = listOf(
                        ReleaseNoteIssue(
                            key = "ATLAS-1",
                            summary = "크리티컬 버그 수정",
                            typeKey = "bug",
                            typeName = "Bug",
                            hierarchyLevel = 0,
                            resolutionName = "Fixed",
                        ),
                        ReleaseNoteIssue(
                            key = "ATLAS-3",
                            summary = "사용자 인증 개선",
                            typeKey = "story",
                            typeName = "Story",
                            hierarchyLevel = 0,
                            resolutionName = null,
                        ),
                        ReleaseNoteIssue(
                            key = "ATLAS-2",
                            summary = "로딩 버그 수정",
                            typeKey = "bug",
                            typeName = "Bug",
                            hierarchyLevel = 0,
                            resolutionName = null,
                        ),
                    ),
                )
                val result = ReleaseNotesGenerator.generate(input)

                result shouldContain "# ATLAS 2.0.0 릴리즈 노트"
                result shouldContain "- 상태: RELEASED"
                result shouldContain "- 릴리즈일: 2026-06-10"
                result shouldContain "- 포함 이슈: 3건"
                result shouldContain "## Bug (2)"
                result shouldContain "## Story (1)"
                result shouldContain "- ATLAS-1 크리티컬 버그 수정 (Fixed)"
                result shouldContain "- ATLAS-2 로딩 버그 수정"
                result shouldContain "- ATLAS-3 사용자 인증 개선"

                // Bug 그룹이 Story 그룹보다 앞에 위치해야 한다 (hierarchyLevel 동률, typeName: Bug < Story)
                val lines = result.lines()
                val bugIdx = lines.indexOfFirst { it.startsWith("## Bug") }
                val storyIdx = lines.indexOfFirst { it.startsWith("## Story") }
                (bugIdx < storyIdx) shouldBe true

                // Bug 그룹 내 ATLAS-1이 ATLAS-2보다 앞
                val atlas1Idx = lines.indexOfFirst { it.contains("ATLAS-1") }
                val atlas2Idx = lines.indexOfFirst { it.contains("ATLAS-2") }
                (atlas1Idx < atlas2Idx) shouldBe true
            }
        }
    }
})
