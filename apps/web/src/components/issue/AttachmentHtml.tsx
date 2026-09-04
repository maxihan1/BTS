// 서버 정화 HTML 을 렌더하며 attachment:<uuid> 이미지를 blob 으로 바꿔 끼운다 (J7)
import type { JSX } from 'react'
import { useEffect, useRef } from 'react'
import { downloadAttachment } from '@/api/attachments'

/** 본문에 실리는 이미지 참조 형태 — 서버 sanitize 가 이 형태만 통과시킨다(V039). */
const ATTACHMENT_SRC = /^attachment:([0-9a-fA-F-]{36})$/

interface AttachmentHtmlProps {
  /** 백엔드가 정화한 HTML. null 이면 아무것도 그리지 않는다. */
  html: string | null
  /** 첨부를 내려받을 이슈 키 */
  issueKey: string
  /** 컨테이너 className */
  className?: string
  /** 테스트·e2e 셀렉터 */
  testId?: string
}

/**
 * 첨부 이미지가 섞인 본문/댓글 HTML 을 렌더한다.
 *
 * ## 왜 문자열 치환이 아닌가
 *
 * `html.replace('attachment:…', blobUrl)` 는 쉬워 보이지만 **주입 표면을 새로 만든다** —
 * 치환 대상이 태그 안인지 텍스트인지 구분하지 못하고, 사용자가 본문에 `attachment:uuid` 를
 * 평문으로 써 넣으면 그것도 바뀐다. 그래서 서버 HTML 을 그대로 넣고(정화는 서버가 이미 했다),
 * **마운트 후 DOM 에서 `<img>` 노드를 찾아 `src` 만 바꿔 끼운다.**
 *
 * ## 생명주기
 *
 * 만든 objectURL 을 전부 모아 두었다가 언마운트·HTML 변경 시 해제한다. 해제를 빠뜨리면
 * 이슈를 넘겨 볼 때마다 blob 이 쌓여 메모리를 붙든다.
 *
 * `ignore` 플래그로 늦게 도착한 응답이 이미 교체된 DOM 을 건드리지 못하게 막는다 —
 * 모달에서 이슈를 빠르게 갈아탈 때 실제로 일어난다.
 */
export function AttachmentHtml({
  html,
  issueKey,
  className,
  testId,
}: AttachmentHtmlProps): JSX.Element | null {
  const containerRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    const container = containerRef.current
    if (container === null || html === null) return

    let ignore = false
    const created: string[] = []

    const images = container.querySelectorAll<HTMLImageElement>('img[src^="attachment:"]')
    for (const img of images) {
      const match = ATTACHMENT_SRC.exec(img.getAttribute('src') ?? '')
      if (match === null) continue
      const attachmentId = match[1]
      if (attachmentId === undefined) continue

      downloadAttachment(issueKey, attachmentId)
        .then((blob) => {
          if (ignore) return
          const url = URL.createObjectURL(blob)
          created.push(url)
          img.src = url
        })
        .catch(() => {
          // 실패하면 참조 그대로 둔다 — 깨진 이미지 아이콘이 뜨지만, 여기서 토스트를 띄우면
          // 이미지가 여럿일 때 화면이 토스트로 덮인다.
        })
    }

    return () => {
      ignore = true
      created.forEach((url) => { URL.revokeObjectURL(url) })
    }
  }, [html, issueKey])

  if (html === null || html === '') return null

  return (
    <div
      ref={containerRef}
      data-testid={testId}
      className={className}
      // NFR1: 백엔드 정화 HTML만 dangerouslySetInnerHTML로 렌더.
      // biome-ignore lint/security/noDangerouslySetInnerHtml: 백엔드 OWASP 정화 HTML만 허용
      dangerouslySetInnerHTML={{ __html: html }}
    />
  )
}
