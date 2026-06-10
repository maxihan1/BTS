// MentionParser 단위 테스트 — 이메일 회피·코드스팬 제거·문장부호 경계·dedup 케이스 검증

package com.bts.issue.mention

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

class MentionParserTest : DescribeSpec({

    describe("MentionParser.extract") {

        describe("기본 멘션 추출") {
            it("단순 @username 멘션을 추출한다") {
                MentionParser.extract("@bob 검토") shouldBe setOf("bob")
            }

            it("null 입력이면 빈 집합을 반환한다") {
                MentionParser.extract(null) shouldBe emptySet()
            }

            it("빈 문자열이면 빈 집합을 반환한다") {
                MentionParser.extract("") shouldBe emptySet()
            }

            it("공백만 있으면 빈 집합을 반환한다") {
                MentionParser.extract("   ") shouldBe emptySet()
            }
        }

        describe("이메일 회피 (EC-6)") {
            it("이메일 주소의 @ 는 멘션으로 추출하지 않고, 뒤에 오는 @username 만 추출한다") {
                MentionParser.extract("contact alice@corp.com or @bob") shouldBe setOf("bob")
            }
        }

        describe("코드 스팬 제거 (EC-8)") {
            it("인라인 코드 스팬 내 @username 은 멘션으로 추출하지 않는다") {
                MentionParser.extract("`@bob`") shouldBe emptySet()
            }

            it("펜스 코드 블록 내 @username 은 멘션으로 추출하지 않는다") {
                MentionParser.extract("```\n@bob\n```") shouldBe emptySet()
            }

            it("펜스 코드 블록 바깥의 멘션은 코드 블록 제거 후에도 추출된다") {
                MentionParser.extract("@alice\n```\n@bob\n```") shouldBe setOf("alice")
            }
        }

        describe("중복 제거 (EC-2)") {
            it("같은 username 이 여러 번 나와도 집합으로 dedup 된다") {
                MentionParser.extract("@bob @bob") shouldBe setOf("bob")
            }
        }

        describe("문장부호 경계 (EC-10)") {
            it("@alice. 처럼 마침표로 끝나면 alice 만 추출한다") {
                MentionParser.extract("@alice.") shouldBe setOf("alice")
            }

            it("하이픈을 포함한 username @alice-bob 을 그대로 추출한다") {
                MentionParser.extract("@alice-bob") shouldBe setOf("alice-bob")
            }

            it("점과 밑줄을 포함한 @x.y_z 를 그대로 추출한다") {
                MentionParser.extract("@x.y_z") shouldBe setOf("x.y_z")
            }

            it("@alice-bob 과 @x.y_z 를 함께 추출한다") {
                MentionParser.extract("@alice-bob @x.y_z") shouldBe setOf("alice-bob", "x.y_z")
            }
        }
    }
})
