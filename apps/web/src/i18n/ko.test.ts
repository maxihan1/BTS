// issueDetailStrings 신규 키 존재 여부를 타입 레벨에서 검증하는 테스트

import { describe, it, expectTypeOf } from 'vitest'
import { issueDetailStrings } from './ko'

// IssueDetailStrings 타입을 추론해서 키 존재를 검증한다.
// 키가 없으면 expectTypeOf(...).toHaveProperty() 가 타입 에러를 발생시킨다.
type IssueDetailStrings = typeof issueDetailStrings

describe('issueDetailStrings — 본문/메타필드 신규 키 존재 검증', () => {
  // ── 본문(description) 탭/버튼 ──────────────────────────────────────
  it('descriptionWriteTab 키가 존재한다', () => {
    expectTypeOf<IssueDetailStrings>().toHaveProperty('descriptionWriteTab')
  })

  it('descriptionPreviewTab 키가 존재한다', () => {
    expectTypeOf<IssueDetailStrings>().toHaveProperty('descriptionPreviewTab')
  })

  it('descriptionEmpty 키가 존재한다', () => {
    expectTypeOf<IssueDetailStrings>().toHaveProperty('descriptionEmpty')
  })

  it('descriptionEditButton 키가 존재한다', () => {
    expectTypeOf<IssueDetailStrings>().toHaveProperty('descriptionEditButton')
  })

  it('descriptionSaveButton 키가 존재한다', () => {
    expectTypeOf<IssueDetailStrings>().toHaveProperty('descriptionSaveButton')
  })

  it('descriptionCancelButton 키가 존재한다', () => {
    expectTypeOf<IssueDetailStrings>().toHaveProperty('descriptionCancelButton')
  })

  // ── 우선순위(priority) ──────────────────────────────────────────────
  it('priorityLabel 키가 존재한다', () => {
    expectTypeOf<IssueDetailStrings>().toHaveProperty('priorityLabel')
  })

  it('prioritySelectLabel 키가 존재한다', () => {
    expectTypeOf<IssueDetailStrings>().toHaveProperty('prioritySelectLabel')
  })

  it('priorityNames 키가 존재한다', () => {
    expectTypeOf<IssueDetailStrings>().toHaveProperty('priorityNames')
  })

  it('priorityChangeError 키가 존재한다', () => {
    expectTypeOf<IssueDetailStrings>().toHaveProperty('priorityChangeError')
  })

  // ── 영향도(impact) ─────────────────────────────────────────────────
  it('impactLabel 키가 존재한다', () => {
    expectTypeOf<IssueDetailStrings>().toHaveProperty('impactLabel')
  })

  it('impactSelectLabel 키가 존재한다', () => {
    expectTypeOf<IssueDetailStrings>().toHaveProperty('impactSelectLabel')
  })

  it('impactNames 키가 존재한다', () => {
    expectTypeOf<IssueDetailStrings>().toHaveProperty('impactNames')
  })

  it('impactUnset 키가 존재한다', () => {
    expectTypeOf<IssueDetailStrings>().toHaveProperty('impactUnset')
  })

  it('impactChangeError 키가 존재한다', () => {
    expectTypeOf<IssueDetailStrings>().toHaveProperty('impactChangeError')
  })

  // ── 환경(environment) ──────────────────────────────────────────────
  it('environmentLabel 키가 존재한다', () => {
    expectTypeOf<IssueDetailStrings>().toHaveProperty('environmentLabel')
  })

  it('environmentPlaceholder 키가 존재한다', () => {
    expectTypeOf<IssueDetailStrings>().toHaveProperty('environmentPlaceholder')
  })

  it('environmentSaveButton 키가 존재한다', () => {
    expectTypeOf<IssueDetailStrings>().toHaveProperty('environmentSaveButton')
  })

  it('environmentSaveError 키가 존재한다', () => {
    expectTypeOf<IssueDetailStrings>().toHaveProperty('environmentSaveError')
  })

  // ── 라벨(labels) ───────────────────────────────────────────────────
  it('labelsLabel 키가 존재한다', () => {
    expectTypeOf<IssueDetailStrings>().toHaveProperty('labelsLabel')
  })

  it('labelAddPlaceholder 키가 존재한다', () => {
    expectTypeOf<IssueDetailStrings>().toHaveProperty('labelAddPlaceholder')
  })

  it('labelRemoveLabel 키가 존재한다', () => {
    expectTypeOf<IssueDetailStrings>().toHaveProperty('labelRemoveLabel')
  })

  it('labelsSaveButton 키가 존재한다', () => {
    expectTypeOf<IssueDetailStrings>().toHaveProperty('labelsSaveButton')
  })

  it('labelsSaveError 키가 존재한다', () => {
    expectTypeOf<IssueDetailStrings>().toHaveProperty('labelsSaveError')
  })

  // ── 런타임 값 smoke 검증 ───────────────────────────────────────────
  it('priorityNames[1]은 문자열이다', () => {
    expectTypeOf(issueDetailStrings.priorityNames[1]).toBeString()
  })

  it('impactNames[1]은 문자열이다', () => {
    expectTypeOf(issueDetailStrings.impactNames[1]).toBeString()
  })
})
