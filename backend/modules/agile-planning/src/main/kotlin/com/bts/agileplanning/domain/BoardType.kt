// 보드 종류(스크럼/칸반) 값 객체 — 보드가 무엇을 담는지를 가르는 정체성 축

package com.bts.agileplanning.domain

/**
 * 보드 종류가 허용값 밖일 때의 예외.
 *
 * 맨 [IllegalArgumentException] 을 쓰지 않는 이유는 [BoardNameInvalidException] 의 KDoc 이
 * 이미 적는다 — 맨 예외를 400 으로 매핑하면 호출 사슬 어디에서 터지든 내부 버그가 400 으로 나가
 * 5xx 경보에서 사라진다. [IllegalArgumentException] 을 상속해 `require` 계열과 같은 의미 범주는 유지한다.
 */
class BoardTypeInvalidException(
    raw: String,
) : IllegalArgumentException("Board.boardType must be one of SCRUM, KANBAN. got=$raw")

/**
 * 보드 종류.
 *
 * ### 왜 설정이 아니라 정체성인가
 * Jira Cloud 는 보드를 만들 때 **"Create a Scrum board" / "Create a Kanban board"** 를 첫 질문으로
 * 묻는다(support.atlassian.com/jira-software-cloud/docs/create-a-board/ · 2026-09-01 조회).
 * 종류가 보드의 의미 자체를 가르기 때문이다 — [SCRUM] 보드는 **시작된 스프린트의 이슈만** 보여주고
 * [KANBAN] 보드는 프로젝트 이슈 전량을 상태별로 보여준다.
 *
 * 그래서 생성 후 변경 경로를 두지 않는다. Jira 문서도 변경 경로를 명시하지 않았고
 * (조회했으나 원문 미확보), **근거 없는 기능을 만들지 않는다**
 * (`docs/adr/2026-09-01-board-type-and-active-sprint.md` 의도적 편차 X3).
 */
enum class BoardType {
    /** 스크럼 보드 — 그 보드의 ACTIVE 스프린트에 속한 이슈만 배치한다. 활성 스프린트가 없으면 빈 보드다. */
    SCRUM,

    /** 칸반 보드 — 프로젝트 이슈 전량을 상태별로 배치한다. 기존 보드 전량의 종류이며 동작이 바뀌지 않는다. */
    KANBAN,
    ;

    companion object {
        /**
         * 문자열을 [BoardType] 으로 읽는다.
         *
         * @param raw 종류 문자열. **`null` 이면 [KANBAN]** — 종류를 안 보내는 기존 호출자를 깨뜨리지 않는다.
         * @throws BoardTypeInvalidException 허용값 밖일 때.
         */
        fun from(raw: String?): BoardType {
            if (raw == null) return KANBAN
            return entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                ?: throw BoardTypeInvalidException(raw)
        }
    }
}
