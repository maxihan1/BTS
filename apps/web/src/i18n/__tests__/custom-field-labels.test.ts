// 커스텀 필드 i18n 라벨 + errorCode 매핑 단위 테스트

import { describe, expect, it } from 'vitest'
import {
  customFieldErrorMessage,
  customFieldLabels,
} from '../custom-field-labels'

describe('customFieldLabels', () => {
  describe('page 그룹', () => {
    it('heading 라벨이 존재한다', () => {
      expect(customFieldLabels.page.heading).toBeTruthy()
    })

    it('description 라벨이 존재한다', () => {
      expect(customFieldLabels.page.description).toBeTruthy()
    })

    it('emptyMessage 라벨이 존재한다', () => {
      expect(customFieldLabels.page.emptyMessage).toBeTruthy()
    })

    it('loadingStatus 라벨이 존재한다', () => {
      expect(customFieldLabels.page.loadingStatus).toBeTruthy()
    })
  })

  describe('actions 그룹', () => {
    it('addButton 라벨이 존재한다', () => {
      expect(customFieldLabels.actions.addButton).toBeTruthy()
    })

    it('editButton 라벨이 존재한다', () => {
      expect(customFieldLabels.actions.editButton).toBeTruthy()
    })

    it('deleteButton 라벨이 존재한다', () => {
      expect(customFieldLabels.actions.deleteButton).toBeTruthy()
    })

    it('deleteConfirm 라벨이 존재한다', () => {
      expect(customFieldLabels.actions.deleteConfirm).toBeTruthy()
    })

    it('noPermission 라벨이 존재한다', () => {
      expect(customFieldLabels.actions.noPermission).toBeTruthy()
    })
  })

  describe('form 그룹', () => {
    const formKeys = [
      'keyLabel',
      'nameLabel',
      'descriptionLabel',
      'fieldTypeLabel',
      'requiredLabel',
      'displayOrderLabel',
      'optionsLabel',
      'addOptionButton',
    ] as const

    it.each(formKeys)('form.%s 라벨이 존재한다', (key) => {
      expect(customFieldLabels.form[key]).toBeTruthy()
    })
  })

  describe('fieldTypes 그룹 — 10종 전수', () => {
    const fieldTypeKeys = [
      'SHORT_TEXT',
      'LONG_TEXT',
      'NUMBER',
      'DATE',
      'DATETIME',
      'SINGLE_SELECT',
      'MULTI_SELECT',
      'CHECKBOX',
      'RADIO',
      'URL',
    ] as const

    it.each(fieldTypeKeys)('fieldTypes.%s 라벨이 존재한다', (key) => {
      expect(customFieldLabels.fieldTypes[key]).toBeTruthy()
    })

    it('fieldTypes 키가 정확히 10개다', () => {
      expect(Object.keys(customFieldLabels.fieldTypes)).toHaveLength(10)
    })
  })
})

describe('customFieldErrorMessage', () => {
  it('VALIDATION_FAILED → 입력값 확인 메시지', () => {
    expect(customFieldErrorMessage('VALIDATION_FAILED')).toBe(
      '입력값을 확인해주세요.',
    )
  })

  it('CUSTOM_FIELD_NOT_FOUND → 필드 없음 메시지', () => {
    expect(customFieldErrorMessage('CUSTOM_FIELD_NOT_FOUND')).toBe(
      '필드를 찾을 수 없습니다.',
    )
  })

  it('CUSTOM_FIELD_PROJECT_NOT_FOUND → 프로젝트 없음 메시지', () => {
    expect(customFieldErrorMessage('CUSTOM_FIELD_PROJECT_NOT_FOUND')).toBe(
      '프로젝트를 찾을 수 없습니다.',
    )
  })

  it('CUSTOM_FIELD_KEY_DUPLICATE → 키 중복 메시지', () => {
    expect(customFieldErrorMessage('CUSTOM_FIELD_KEY_DUPLICATE')).toBe(
      '이미 같은 키의 필드가 있습니다.',
    )
  })

  it('CUSTOM_FIELD_ACCESS_DENIED → 권한 없음 메시지', () => {
    expect(customFieldErrorMessage('CUSTOM_FIELD_ACCESS_DENIED')).toBe(
      '권한이 없습니다.',
    )
  })

  it('CUSTOM_FIELD_INVALID_DEFINITION → 정의 오류 메시지(선택형 옵션 안내 포함)', () => {
    expect(customFieldErrorMessage('CUSTOM_FIELD_INVALID_DEFINITION')).toBe(
      '필드 정의가 올바르지 않습니다. (선택형은 옵션이 1개 이상 필요)',
    )
  })

  it('CUSTOM_FIELD_IMMUTABLE_CHANGE → 불변 필드 변경 메시지', () => {
    expect(customFieldErrorMessage('CUSTOM_FIELD_IMMUTABLE_CHANGE')).toBe(
      '필드 타입과 키는 변경할 수 없습니다.',
    )
  })

  it('알 수 없는 errorCode → 기본 오류 메시지', () => {
    expect(customFieldErrorMessage('UNKNOWN_CODE')).toBe(
      '요청을 처리하지 못했습니다.',
    )
  })

  it('null → 기본 오류 메시지', () => {
    expect(customFieldErrorMessage(null)).toBe('요청을 처리하지 못했습니다.')
  })
})
