// Blob 객체를 브라우저 파일 다운로드로 트리거하는 순수 헬퍼

/**
 * Blob 객체를 브라우저 파일 다운로드로 트리거한다.
 *
 * `URL.createObjectURL`로 임시 Blob URL을 생성하고,
 * 숨겨진 `<a>` 엘리먼트의 `download` 속성에 `filename`을 지정한 뒤
 * `click()`을 호출해 브라우저 다운로드 대화상자를 연다.
 *
 * **누수 보장** — click 이후 또는 예외 발생 시에도 `finally` 블록에서
 * `URL.revokeObjectURL`을 반드시 호출해 임시 URL이 메모리에 남지 않도록 한다.
 *
 * @param blob 다운로드할 Blob 객체 (예: `application/pdf`)
 * @param filename 저장 파일명 (예: `"ATLAS-1.pdf"`)
 */
export function triggerBlobDownload(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob)
  try {
    const a = document.createElement('a')
    a.href = url
    a.download = filename
    a.click()
  } finally {
    // 임시 Blob URL 즉시 해제 — 메모리 누수 방지 (예외가 발생해도 보장)
    URL.revokeObjectURL(url)
  }
}
