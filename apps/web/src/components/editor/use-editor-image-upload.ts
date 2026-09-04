// 에디터에 떨어진 이미지를 첨부로 올리고 본문에 참조를 꽂는 훅 (Jira 패리티 J7)
import { useCallback, useContext } from 'react'
import type { Editor } from '@tiptap/react'
import { QueryClientContext } from '@tanstack/react-query'
import type { QueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { uploadAttachment, MAX_ATTACHMENT_BYTES } from '@/api/attachments'
import { ATTACHMENTS_QUERY_KEY } from '@/api/useAttachments'
import { attachmentLabels } from '@/i18n/attachment-labels'

/** 붙여넣기·드롭에서 받아들일 이미지 MIME — 서버 sanitize 가 렌더를 허용하는 것과 같은 범위. */
const ACCEPTED_IMAGE = /^image\/(png|jpeg|gif|webp)$/i

/**
 * 파일 목록에서 이미지만 걸러낸다.
 *
 * 붙여넣기에는 이미지 말고도 온갖 것이 실려 온다(HTML 조각 · 텍스트 · 파일 경로).
 * 이미지가 하나도 없으면 빈 배열을 돌려주고, 호출부는 **기본 붙여넣기 동작에 넘긴다** —
 * 그래야 평범한 텍스트 붙여넣기가 막히지 않는다.
 */
function pickImages(files: FileList | null): File[] {
  if (files === null) return []
  return Array.from(files).filter((f) => ACCEPTED_IMAGE.test(f.type))
}

/**
 * 이미지 업로드 → 본문 삽입 훅.
 *
 * ## 흐름 (J7)
 *
 * Jira 는 본문에 넣은 이미지도 **첨부로 관리한다** — "Files … all appear together in a single
 * Attachments section". 그래서 붙여넣기는 곧 업로드다.
 *
 * 1. `POST /attachments` 로 올린다
 * 2. 응답 UUID 로 `<img src="attachment:<uuid>">` 를 커서 자리에 넣는다
 * 3. 첨부 목록 쿼리를 무효화한다 — 같은 파일이므로 아래 첨부 섹션에도 즉시 나타난다
 *
 * ## 왜 자리표시자를 두지 않았나
 *
 * 업로드 중 회색 상자를 넣었다 빼는 방식은 실패·중복·되돌리기 경로가 각각 생긴다.
 * 대신 **업로드가 끝난 뒤에만** 노드를 넣는다 — 성공하면 이미지가 나타나고, 실패하면
 * 아무것도 넣지 않고 토스트만 띄운다. 상태가 둘(있다/없다)뿐이라 되돌릴 것이 없다.
 *
 * @param issueKey 첨부를 매달 이슈 키. null 이면 업로드를 시도하지 않는다(생성 화면 등)
 * @returns `upload(editor, files)` — 이미지가 하나라도 처리됐으면 true
 */
export function useEditorImageUpload(issueKey: string | null) {
  // ★`useQueryClient()` 대신 옵셔널 훅을 쓴다. 그냥 부르면 Provider 밖에서 **throw** 하고,
  //   그러면 `RichTextEditor` 자체가 QueryClientProvider 없이는 렌더조차 못 하는 컴포넌트가
  //   된다 — 이미지 업로드를 쓰지 않는 화면과 단위 테스트까지 전부 인질이 된다.
  //   이미지 경로는 `issueKey` 가 있을 때만 도는 **선택 기능**이므로 결합도 선택이어야 한다.
  const queryClient = useQueryClientSafe()

  return useCallback(
    async (editor: Editor, files: FileList | null): Promise<boolean> => {
      if (issueKey === null) return false
      const images = pickImages(files)
      if (images.length === 0) return false

      for (const file of images) {
        if (file.size > MAX_ATTACHMENT_BYTES) {
          // 기존 라벨을 그대로 쓴다 — 드롭존(`AttachmentSection`)과 같은 문구여야
          // 사용자가 「같은 제약」임을 안다.
          toast.error(attachmentLabels.uploadFileTooLarge(file.name))
          continue
        }
        try {
          const uploaded = await uploadAttachment(issueKey, file)
          editor
            .chain()
            .focus()
            .setImage({ src: `attachment:${uploaded.id}`, alt: uploaded.filename })
            .run()
        } catch {
          toast.error(attachmentLabels.uploadDefault)
        }
      }

      // 본문 이미지도 첨부다 — 아래 첨부 섹션이 즉시 같은 파일을 보여줘야 한다(J7).
      // Provider 밖(단위 테스트 등)이면 갱신할 캐시 자체가 없으므로 건너뛴다.
      void queryClient?.invalidateQueries({ queryKey: ATTACHMENTS_QUERY_KEY(issueKey) })
      return true
    },
    [issueKey, queryClient],
  )
}

/**
 * `QueryClient` 를 **있으면** 돌려주고 없으면 null 을 준다.
 *
 * `useQueryClient()` 는 Provider 밖에서 throw 한다. 그 예외가 렌더 중에 나면 컴포넌트가
 * 통째로 죽으므로, 선택 기능인 이미지 업로드가 에디터 전체를 Provider 에 묶어 버린다.
 * 컨텍스트를 직접 읽어 존재만 확인한다 — 훅 호출 순서는 그대로다.
 */
function useQueryClientSafe(): QueryClient | null {
  return useContext(QueryClientContext) ?? null
}
