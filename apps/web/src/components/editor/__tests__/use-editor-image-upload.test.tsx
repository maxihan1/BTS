// 본문 이미지 붙여넣기 경로 판별식 — 업로드→삽입→첨부목록 갱신 순서 (Jira 패리티 J7 · FR-AC-01)
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createElement, type ReactNode } from 'react'
import type { Editor } from '@tiptap/react'
import { toast } from 'sonner'
import { attachmentLabels } from '@/i18n/attachment-labels'
import { useEditorImageUpload } from '../use-editor-image-upload'

vi.mock('sonner', () => ({ toast: { error: vi.fn(), success: vi.fn() } }))

vi.mock('@/api/attachments', () => ({
  uploadAttachment: vi.fn(),
  MAX_ATTACHMENT_BYTES: 100 * 1024 * 1024,
}))
import { uploadAttachment, MAX_ATTACHMENT_BYTES } from '@/api/attachments'

/**
 * 이미지 붙여넣기 경로 판별식.
 *
 * ## 왜 이 층인가
 *
 * #449 가 넣은 경로인데 유닛으로도 e2e 로도 덮이지 않았다. 여기서 재는 것은 **순서**다 —
 * 업로드가 끝난 뒤에만 본문에 노드가 들어가는지.
 *
 * 순서가 뒤집히면 **첨부 목록에 없는 uuid 를 본문이 참조한다.** 화면에는 깨진 이미지가 남고,
 * 첨부 섹션에는 아무것도 없다. #449 는 자리표시자를 두지 않는 것으로 이 문제를 구조적으로
 * 없앴다(상태가 「있다/없다」 둘뿐이라 되돌릴 것이 없다) — 그 구조를 지키는 판별식이다.
 */

const ISSUE_KEY = 'BTS-1'
const UPLOADED_ID = '11111111-2222-3333-4444-555555555555'

/** `setImage` 호출만 관측하는 최소 에디터 스텁 — 체인 API 형태를 그대로 흉내 낸다. */
function makeEditor() {
  const setImage = vi.fn().mockReturnThis()
  const chain = { focus: vi.fn().mockReturnThis(), setImage, run: vi.fn() }
  const editor = { chain: vi.fn(() => chain) } as unknown as Editor
  return { editor, setImage, run: chain.run }
}

/** FileList 를 흉내 낸다 — jsdom 에 생성자가 없다. */
function fileList(...files: File[]): FileList {
  return Object.assign(files, { item: (i: number) => files[i] ?? null }) as unknown as FileList
}

function imageFile(name = 'shot.png', type = 'image/png', size = 1024): File {
  const f = new File(['x'], name, { type })
  Object.defineProperty(f, 'size', { value: size })
  return f
}

function renderUpload(issueKey: string | null = ISSUE_KEY) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const invalidate = vi.spyOn(queryClient, 'invalidateQueries')
  const wrapper = ({ children }: { children: ReactNode }) =>
    createElement(QueryClientProvider, { client: queryClient }, children)
  const { result } = renderHook(() => useEditorImageUpload(issueKey), { wrapper })
  return { upload: result.current, invalidate }
}

describe('useEditorImageUpload — 업로드→삽입 순서 (J7)', () => {
  beforeEach(() => { vi.clearAllMocks() })

  it('업로드가 끝난 뒤에만 본문에 노드를 넣는다 — 자리표시자를 두지 않는다', async () => {
    // 업로드를 붙잡아 두고, 그 사이에 삽입이 일어나지 않는지 본다.
    let release: (() => void) | undefined
    const pending = new Promise<void>((resolve) => { release = resolve })
    vi.mocked(uploadAttachment).mockImplementation(async () => {
      await pending
      return { id: UPLOADED_ID, filename: 'shot.png' } as never
    })

    const { editor, setImage } = makeEditor()
    const { upload } = renderUpload()
    const done = upload(editor, fileList(imageFile()))

    // 업로드가 아직 안 끝났다 — 이 시점에 노드가 들어가면 첨부 없는 uuid 를 참조하게 된다.
    await waitFor(() => { expect(uploadAttachment).toHaveBeenCalledOnce() })
    expect(setImage).not.toHaveBeenCalled()

    release?.()
    await done

    expect(setImage).toHaveBeenCalledWith({ src: `attachment:${UPLOADED_ID}`, alt: 'shot.png' })
  })

  it('업로드 성공 후 첨부 목록 쿼리를 무효화한다 — 아래 첨부 섹션이 같은 파일을 보여준다', async () => {
    vi.mocked(uploadAttachment).mockResolvedValue(
      { id: UPLOADED_ID, filename: 'shot.png' } as never,
    )

    const { editor } = makeEditor()
    const { upload, invalidate } = renderUpload()
    await upload(editor, fileList(imageFile()))

    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['attachments', ISSUE_KEY] })
  })

  it('업로드가 실패하면 본문에 아무것도 넣지 않는다 — 되돌릴 상태를 만들지 않는다', async () => {
    vi.mocked(uploadAttachment).mockRejectedValue(new Error('boom'))

    const { editor, setImage } = makeEditor()
    const { upload } = renderUpload()
    await upload(editor, fileList(imageFile()))

    expect(setImage).not.toHaveBeenCalled()
    expect(toast.error).toHaveBeenCalledWith(attachmentLabels.uploadDefault)
  })

  it('상한을 넘는 파일은 올리지도 넣지도 않는다 — 드롭존과 같은 문구로 알린다', async () => {
    const { editor, setImage } = makeEditor()
    const { upload } = renderUpload()

    await upload(editor, fileList(imageFile('huge.png', 'image/png', MAX_ATTACHMENT_BYTES + 1)))

    expect(uploadAttachment).not.toHaveBeenCalled()
    expect(setImage).not.toHaveBeenCalled()
    expect(toast.error).toHaveBeenCalledWith(attachmentLabels.uploadFileTooLarge('huge.png'))
  })

  it('이미지가 아니면 false 를 돌려준다 — 평범한 텍스트 붙여넣기를 막지 않는다', async () => {
    const { editor } = makeEditor()
    const { upload } = renderUpload()

    const handled = await upload(editor, fileList(new File(['x'], 'a.txt', { type: 'text/plain' })))

    expect(handled).toBe(false)
    expect(uploadAttachment).not.toHaveBeenCalled()
  })

  it('서버 sanitize 가 렌더를 허용하는 MIME 만 받는다 (png·jpeg·gif·webp)', async () => {
    vi.mocked(uploadAttachment).mockResolvedValue(
      { id: UPLOADED_ID, filename: 'f' } as never,
    )
    const { editor } = makeEditor()
    const { upload } = renderUpload()

    for (const type of ['image/png', 'image/jpeg', 'image/gif', 'image/webp']) {
      vi.mocked(uploadAttachment).mockClear()
      await upload(editor, fileList(imageFile('f', type)))
      expect(vi.mocked(uploadAttachment), `${type} 이 거부됐다`).toHaveBeenCalledOnce()
    }

    // 허용 목록 밖 — 서버가 렌더를 막을 형식이라 올려도 본문에 안 보인다.
    vi.mocked(uploadAttachment).mockClear()
    const handled = await upload(editor, fileList(imageFile('f.svg', 'image/svg+xml')))
    expect(handled).toBe(false)
    expect(uploadAttachment).not.toHaveBeenCalled()
  })

  it('이슈 키가 없으면 업로드 자체를 시도하지 않는다 — 생성 화면에는 매달 곳이 없다', async () => {
    const { editor } = makeEditor()
    const { upload } = renderUpload(null)

    const handled = await upload(editor, fileList(imageFile()))

    expect(handled).toBe(false)
    expect(uploadAttachment).not.toHaveBeenCalled()
  })

  it('여러 이미지를 순서대로 올리고 각각 본문에 넣는다', async () => {
    vi.mocked(uploadAttachment)
      .mockResolvedValueOnce({ id: 'id-1', filename: 'a.png' } as never)
      .mockResolvedValueOnce({ id: 'id-2', filename: 'b.png' } as never)

    const { editor, setImage } = makeEditor()
    const { upload } = renderUpload()
    await upload(editor, fileList(imageFile('a.png'), imageFile('b.png')))

    expect(setImage).toHaveBeenNthCalledWith(1, { src: 'attachment:id-1', alt: 'a.png' })
    expect(setImage).toHaveBeenNthCalledWith(2, { src: 'attachment:id-2', alt: 'b.png' })
  })

  it('한 파일이 실패해도 나머지는 계속 올라간다', async () => {
    vi.mocked(uploadAttachment)
      .mockRejectedValueOnce(new Error('boom'))
      .mockResolvedValueOnce({ id: 'id-2', filename: 'b.png' } as never)

    const { editor, setImage } = makeEditor()
    const { upload } = renderUpload()
    await upload(editor, fileList(imageFile('a.png'), imageFile('b.png')))

    expect(setImage).toHaveBeenCalledOnce()
    expect(setImage).toHaveBeenCalledWith({ src: 'attachment:id-2', alt: 'b.png' })
  })
})
