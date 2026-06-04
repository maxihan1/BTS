// Blob 객체를 브라우저 파일 다운로드로 트리거하는 순수 헬퍼

/**
 * Blob 객체를 브라우저 파일 다운로드로 트리거한다.
 *
 * createObjectURL로 임시 URL을 생성하고, 숨겨진 앵커 엘리먼트의
 * download 속성에 filename을 지정한 뒤 click()을 호출한다.
 * 메모리 누수 방지를 위해 finally 블록에서 반드시 revokeObjectURL을 호출한다.
 *
 * @param blob 다운로드할 Blob 객체
 * @param filename 저장 파일명 (예: "ATLAS-1.pdf")
 */
export function triggerBlobDownload(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob)
  try {
    const a = document.createElement('a')
    a.href = url
    a.download = filename
    a.click()
  } finally {
    URL.revokeObjectURL(url)
  }
}
