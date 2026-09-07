// 리치 텍스트 본문 클래스의 단일 출처 — 에디터·본문 읽기·댓글 읽기가 같은 값을 쓴다
/**
 * 리치 텍스트 본문에 붙이는 클래스.
 *
 * ## 왜 상수인가
 *
 * 소비처가 셋이다 — 에디터 입력창(`RichTextEditor`) · 본문 읽기(`IssueDescription`) ·
 * 댓글 읽기(`CommentSection`). 문자열을 각자 적으면 하나만 오타가 나도 **그 화면만
 * 조용히 서식을 잃는다.** 클래스 이름은 그냥 문자열이라 없는 이름을 써도 오류가 나지 않기
 * 때문이다 — 종전 `prose` 가 정확히 그렇게 몇 달을 살았다(`@tailwindcss/typography` 를
 * 설치하지 않은 채 세 곳이 `prose prose-sm` 을 썼고, 빌드 산출 CSS 에 `.prose` 는 0회였다).
 *
 * ## 짝
 *
 * - 스타일 정의 — `apps/web/src/index.css` 의 `rich-text:start` ~ `rich-text:end` 블록
 * - 커버리지 — `scripts/workflow/rich-text-style-coverage.test.ts` (서버 allowlist 대조)
 * - 사용처 — `scripts/workflow/rich-text-class-usage.test.ts` (`prose` 잔존 0 · 소비처 전수)
 */
export const RICH_TEXT_CLASS = 'rich-text'
