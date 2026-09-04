// 첨부 바이너리를 blob URL 로 잡아 두는 훅 — Bearer 인증 때문에 <img src="/api/…"> 가 안 된다
import { useEffect, useState } from 'react'
import { downloadAttachment } from './attachments'
import { isPreviewable } from '@/lib/attachment-preview'

/**
 * 썸네일을 그릴 최대 원본 크기 — 5MB.
 *
 * 서버에 리사이즈 엔드포인트가 없어 **원본을 통째로 받아** CSS 로 줄인다(편차 X5).
 * 상한이 없으면 100MB 이미지 하나가 목록을 여는 것만으로 내려받힌다.
 * 초과분은 아이콘 타일 + 「미리보기」 버튼으로 남는다 — 사용자가 의도했을 때만 받는다.
 */
export const THUMBNAIL_MAX_BYTES = 5 * 1024 * 1024

/** 이 첨부에 썸네일을 그릴지 판정한다. */
export function shouldRenderThumbnail(contentType: string, sizeBytes: number): boolean {
  return isPreviewable(contentType) &&
    contentType.toLowerCase().startsWith('image/') &&
    sizeBytes <= THUMBNAIL_MAX_BYTES
}

/**
 * 첨부 바이너리를 내려받아 blob URL 로 돌려준다.
 *
 * ## 왜 blob 인가
 *
 * `apiFetch` 가 `Authorization: Bearer` 를 싣는다. `<img src="/api/v1/issues/K/attachments/ID">`
 * 는 그 헤더가 붙지 않아 **401 이 되고 깨진 이미지가 뜬다**. 그래서 fetch 로 받아
 * `URL.createObjectURL` 로 감싼다 — `AttachmentPreviewModal` 이 이미 쓰는 방식이다.
 *
 * ## 생명주기
 *
 * objectURL 은 명시적으로 해제하지 않으면 문서가 살아 있는 동안 메모리를 붙든다.
 * 언마운트·대상 변경·`enabled` 해제 모두에서 `revokeObjectURL` 한다.
 * `ignore` 플래그로 늦게 도착한 응답이 이미 정리된 상태를 덮어쓰지 못하게 막는다.
 *
 * @param issueKey 이슈 식별 키
 * @param attachmentId 첨부 UUID
 * @param enabled false 면 아무것도 받지 않는다 — 임계 초과 파일에서 요청 자체를 막는다
 * @returns blob URL. 아직 없거나 실패했으면 null
 */
export function useAttachmentBlobUrl(
  issueKey: string,
  attachmentId: string,
  enabled: boolean,
): string | null {
  const [url, setUrl] = useState<string | null>(null)

  useEffect(() => {
    if (!enabled) {
      setUrl(null)
      return
    }

    let ignore = false
    let created: string | null = null

    // ★`Promise.resolve(...)` 로 감싼다. 호출부가 이 모듈을 mock 하면 `vi.fn()` 기본값인
    //   undefined 가 돌아오고, 거기에 `.then` 을 걸면 렌더 중 TypeError 로 컴포넌트가 죽는다 —
    //   첨부 목록 테스트 12건이 실제로 그렇게 깨졌다. 감싸면 그 경우가 「blob 없음」으로 흡수된다.
    Promise.resolve(downloadAttachment(issueKey, attachmentId))
      .then((blob) => {
        if (!(blob instanceof Blob)) return
        if (ignore) return
        created = URL.createObjectURL(blob)
        setUrl(created)
      })
      .catch(() => {
        // 실패는 조용히 넘긴다 — 썸네일이 안 뜨는 것은 불편이지만, 여기서 토스트를 띄우면
        // 첨부가 여러 개일 때 실패 하나에 화면이 토스트로 덮인다.
        if (!ignore) setUrl(null)
      })

    return () => {
      ignore = true
      if (created !== null) {
        URL.revokeObjectURL(created)
        created = null
      }
      setUrl(null)
    }
  }, [issueKey, attachmentId, enabled])

  return url
}
