// 리치 텍스트 에디터 툴바·단축키 한국어 라벨 — 접근성 이름의 단일 출처
/**
 * 에디터 툴바 라벨.
 *
 * 각 값은 버튼의 `aria-label` 이자 `title`(툴팁)이다. 툴팁에 단축키를 함께 적어
 * 사용자가 마우스를 올렸을 때 키를 배울 수 있게 한다 — Jira 툴바와 같은 관례다.
 *
 * ★문자열을 인라인으로 흩뿌리지 않는다. e2e 가 `getByRole('button', { name })` 으로 잡으므로
 * 여기 한 곳이 바뀌면 테스트도 함께 움직인다.
 */
export const editorLabels = {
  bold: '굵게',
  italic: '기울임',
  underline: '밑줄',
  strike: '취소선',
  code: '인라인 코드',
  heading: '제목 수준',
  paragraph: '본문',
  headingLevel: (level: number) => `제목 ${level}`,
  bulletList: '글머리 목록',
  orderedList: '번호 목록',
  taskList: '체크박스 목록',
  blockquote: '인용',
  codeBlock: '코드 블록',
  horizontalRule: '구분선',
  link: '링크',
  linkPrompt: '링크 주소를 입력하세요',
  unlink: '링크 해제',
  table: '표 삽입',
  image: '이미지 삽입',
  toolbarLabel: '서식 도구 모음',
  editorLabel: '본문 편집기',
} as const
