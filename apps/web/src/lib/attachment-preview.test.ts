// 미리보기 가능 MIME 타입 화이트리스트 헬퍼 단위 테스트

import { describe, expect, it } from 'vitest'
import { isPreviewable, previewCategory } from './attachment-preview'

describe('isPreviewable', () => {
  it.each([
    'image/png',
    'image/jpeg',
    'image/gif',
    'image/webp',
    'application/pdf',
    'video/mp4',
    'video/webm',
  ])('화이트리스트 MIME %s 는 true를 반환한다', (mime) => {
    expect(isPreviewable(mime)).toBe(true)
  })

  it.each([
    'image/svg+xml',
    'text/html',
    'application/zip',
    'application/octet-stream',
    '',
  ])('비화이트리스트 MIME %s 는 false를 반환한다', (mime) => {
    expect(isPreviewable(mime)).toBe(false)
  })
})

describe('previewCategory', () => {
  it('image/png 는 "image" 카테고리를 반환한다', () => {
    expect(previewCategory('image/png')).toBe('image')
  })

  it('application/pdf 는 "pdf" 카테고리를 반환한다', () => {
    expect(previewCategory('application/pdf')).toBe('pdf')
  })

  it('video/mp4 는 "video" 카테고리를 반환한다', () => {
    expect(previewCategory('video/mp4')).toBe('video')
  })

  it.each([
    'image/svg+xml',
    'text/html',
    'application/zip',
    'application/octet-stream',
    '',
  ])('비화이트리스트 MIME %s 는 null을 반환한다', (mime) => {
    expect(previewCategory(mime)).toBeNull()
  })
})
