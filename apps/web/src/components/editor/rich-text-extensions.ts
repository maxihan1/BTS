// TipTap 확장 구성 — Jira Cloud 에디터 서식(J8)과 서버 sanitize allowlist(V039)의 교집합
import StarterKit from '@tiptap/starter-kit'
import Image from '@tiptap/extension-image'
import { TableKit } from '@tiptap/extension-table'
import { TaskList } from '@tiptap/extension-task-list'
import { TaskItem } from '@tiptap/extension-task-item'
import { Placeholder } from '@tiptap/extension-placeholder'
import type { AnyExtension } from '@tiptap/react'
import { buildMentionExtension } from './mention-extension'

/**
 * 본문·댓글이 공유하는 TipTap 확장 목록.
 *
 * ## 서버 allowlist 와 짝이다
 *
 * 여기서 만들 수 있는 태그는 `MarkdownRenderer.SANITIZE_POLICY`(V039)가 허용하는 것과
 * **같아야 한다**. 에디터가 만들 수 있는데 서버가 지우면 사용자는 「저장했더니 서식이
 * 사라졌다」를 겪고, 반대로 서버만 허용하면 그 태그를 넣을 방법이 없다.
 *
 * | 서식 | 어디서 | 서버 태그 |
 * |---|---|---|
 * | 굵게·기울임·코드·인용·목록·제목·구분선·코드블록 | StarterKit | strong em code blockquote ul ol li h1~h6 hr pre |
 * | 취소선 | StarterKit(Strike) | s · del |
 * | 밑줄 | StarterKit(Underline) | u — 편차 X2, 마크다운 문법이 없다 |
 * | 링크 | StarterKit(Link) | a[href] (http/https/mailto) |
 * | 이미지 | Image | img[src=attachment:uuid] |
 * | 표 | TableKit | table thead tbody tr th td |
 * | 체크박스 목록 | TaskList·TaskItem | ul li input[type=checkbox] |
 *
 * TipTap 3.x StarterKit 은 Underline·Link 를 **이미 포함**한다. 따로 설치·등록하면
 * "duplicate extension" 경고가 나므로 여기서는 옵션만 조정한다.
 *
 * ## 링크 정책
 *
 * `openOnClick: false` — 편집 중 링크를 누르면 커서를 옮기지 이동하지 않는다.
 * `protocols` 는 서버 `allowUrlProtocols` 와 **같은 셋**이다. 여기서 더 열면 저장 시 서버가
 * 지워 「방금 넣은 링크가 사라진다」가 된다.
 *
 * @param placeholder 빈 에디터에 표시할 안내 문구
 * @returns TipTap 확장 배열
 */
export function buildRichTextExtensions(placeholder: string): AnyExtension[] {
  return [
    StarterKit.configure({
      // 서버가 h1~h6 을 전부 허용한다 (J8 "Heading levels from 1 through to 6").
      heading: { levels: [1, 2, 3, 4, 5, 6] },
      link: {
        openOnClick: false,
        autolink: true,
        // ★서버 allowUrlProtocols("http","https","mailto") 와 같은 셋.
        protocols: ['http', 'https', 'mailto'],
      },
    }),
    Image.configure({
      // 본문 이미지는 첨부 참조(attachment:<uuid>)만 유효하다 — 서버가 그 형태만 통과시킨다(J7).
      // base64 를 막는 이유도 같다. 붙여넣은 이미지는 PR④ 가 첨부로 업로드한 뒤 참조를 넣는다.
      allowBase64: false,
    }),
    TableKit.configure({ table: { resizable: false } }),
    TaskList,
    TaskItem.configure({ nested: true }),
    Placeholder.configure({ placeholder }),
    // @멘션 — 서버 span.mention 마크업과 왕복 호환. 후보 UI 는 기존 MentionDropdown 재사용.
    buildMentionExtension(),
  ]
}
