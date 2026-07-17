// GitHub PR 제목/본문에서 "Closes PROJ-42" 형태의 이슈 키 참조를 추출하는 순수 함수 (FR-AT-07 PR-C)

package com.bts.automation.application

/**
 * GitHub PR 제목/본문 텍스트에서 "Closes/Fixes/Resolves PROJ-42" 형태로 언급된 이슈 키를 추출한다
 * (FR-AT-07 PR-C, Git webhook `PR_MERGED` 트리거 준비 단계).
 *
 * ## 신뢰 경계
 * PR 제목·본문은 신뢰할 수 없는 외부 입력이다. GitHub 웹훅 서명(HMAC)이 증명하는 건 "GitHub이
 * 보냈다"이지 "내용이 믿을 만하다"가 아니다 — BTS 계정이 없는 외부 기여자도 PR을 열고 제목·본문을
 * 자유롭게 쓸 수 있다. 따라서 이 객체가 추출한 이슈 키는 **원문 자유 텍스트에서 정규식으로 뽑아낸
 * 값**일 뿐 존재/권한 검증이 끝난 값이 아니다. 프로젝트 스코프(추출된 prefix가 실제 대상
 * projectKey와 일치하는지) 필터링은 이 객체의 책임이 아니라 호출자(후속 Task) 몫이다.
 *
 * ## 대상 범위 — 제목 + 본문만 (커밋 메시지 제외)
 * 커밋 메시지까지 스캔하려면 GitHub API로 머지 커밋 목록을 별도 조회해야 한다. 이는 웹훅 동기 처리
 * 예산(NFR-1, 200ms)을 깨므로, 웹훅 payload에 이미 담겨 오는 PR 제목·본문만 스캔한다.
 *
 * ## 정규식 설계
 * - 키워드(`Close`/`Closes`/`Closed`, `Fix`/`Fixes`/`Fixed`, `Resolve`/`Resolves`/`Resolved`)가
 *   **반드시 선행**해야 추출한다. 키워드 없는 맨 `PROJ-42` 는 "단순 언급"과 "닫으라는 지시"를
 *   구분할 수 없으므로 추출하지 않는다.
 * - 키워드만 대소문자 무시(`(?i:...)`)하고, 이슈 키는 **대문자로 고정**한다(`proj-42` 는 미추출) —
 *   [com.bts.issue.domain.IssueKey] 가 대문자만 유효 형식으로 규정하기 때문이다.
 * - 이슈 키 뒤에 `(?![A-Za-z0-9-])`(부정 탐색, 다음에 영숫자/하이픈이 오지 않아야 함)를 둔다.
 *   이게 없으면 `Closes PROJ-42x` 에서 `PROJ-42` 를, `Closes PROJ-420` 에서도 `PROJ-42` 를
 *   그릇 추출한다(뒤에 문자가 더 이어지는데 앞부분만 잘라 매칭하는 오추출).
 *
 * ## 이슈 키 정규식 값 복제
 * 이슈 키 부분 `[A-Z][A-Z0-9]{1,9}-[1-9][0-9]*` 은 [com.bts.issue.domain.IssueKey.REGEX] 와 동일
 * 형식(prefix 2~10자, number 1 이상 · 0 불가)이다. BC 격리로 issue-tracking 모듈을 직접 import 할 수
 * 없어 값만 복제한다([com.bts.slack.unfurl.AtlasIssueUrlParser] 선례와 동형). 단, 정본 정규식은
 * `^...$` 로 앵커링된 **단일 값 전체 매칭**용이고, 이 정규식은 **자유 텍스트 본문 스캔용**이라 시작
 * 앵커 대신 `find()` 반복 탐색 + 뒤쪽 단어 경계 부정 탐색으로 오추출을 막는다.
 */
object PrIssueKeyExtractor {
    private const val PATTERN =
        """(?i:Closes?d?|Fix(?:es|ed)?|Resolves?d?)\s+([A-Z][A-Z0-9]{1,9}-[1-9][0-9]*)(?![A-Za-z0-9-])"""
    private val REGEX = Regex(PATTERN)

    /** 캡처 그룹 인덱스 — 0번은 전체 매치, 1번이 이슈 키 캡처 그룹. */
    private const val ISSUE_KEY_GROUP_INDEX = 1

    /**
     * [title] 과 [body] 를 스캔해 "Closes/Fixes/Resolves PROJ-42" 형태로 언급된 이슈 키를 추출한다.
     *
     * 제목과 본문 양쪽을 스캔하고, 같은 키가 여러 번 언급돼도 중복 없이(첫 등장 순서로) 반환한다.
     * `title`/`body` 는 각각 `null` 이거나 빈 문자열이어도 안전하다(매치 없음으로 수렴).
     *
     * @param title PR 제목(GitHub webhook payload `pull_request.title`).
     * @param body PR 본문(`pull_request.body`).
     * @return 추출된 이슈 키 목록(중복 제거). 매치가 없으면 빈 목록.
     */
    fun extract(
        title: String?,
        body: String?,
    ): List<String> =
        listOfNotNull(title, body)
            .flatMap { text -> REGEX.findAll(text).map { match -> match.groupValues[ISSUE_KEY_GROUP_INDEX] } }
            .distinct()
}
