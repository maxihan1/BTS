// 컴포넌트 관리 UI 라벨 단위 테스트 — componentLabels 키 존재 + componentErrorMessage 매핑 검증

import { describe, it, expect } from 'vitest'
import { componentLabels, componentErrorMessage } from './component-labels'

describe('componentLabels', () => {
  describe('page 그룹', () => {
    it('heading 키가 존재한다', () => {
      expect(componentLabels.page.heading).toBeTruthy()
    })

    it('description 키가 존재한다', () => {
      expect(componentLabels.page.description).toBeTruthy()
    })

    it('emptyMessage 키가 존재한다', () => {
      expect(componentLabels.page.emptyMessage).toBeTruthy()
    })

    it('loadingStatus 키가 존재한다', () => {
      expect(componentLabels.page.loadingStatus).toBeTruthy()
    })
  })

  describe('actions 그룹', () => {
    it('addButton 키가 존재한다', () => {
      expect(componentLabels.actions.addButton).toBeTruthy()
    })

    it('editButton 키가 존재한다', () => {
      expect(componentLabels.actions.editButton).toBeTruthy()
    })

    it('deleteButton 키가 존재한다', () => {
      expect(componentLabels.actions.deleteButton).toBeTruthy()
    })

    it('saveButton 키가 존재한다', () => {
      expect(componentLabels.actions.saveButton).toBeTruthy()
    })

    it('cancelButton 키가 존재한다', () => {
      expect(componentLabels.actions.cancelButton).toBeTruthy()
    })

    it('deleteConfirm 키가 존재한다', () => {
      expect(componentLabels.actions.deleteConfirm).toBeTruthy()
    })
  })

  describe('form 그룹', () => {
    it('nameLabel 키가 존재한다', () => {
      expect(componentLabels.form.nameLabel).toBeTruthy()
    })

    it('descriptionLabel 키가 존재한다', () => {
      expect(componentLabels.form.descriptionLabel).toBeTruthy()
    })

    it('leadLabel 키가 존재한다', () => {
      expect(componentLabels.form.leadLabel).toBeTruthy()
    })

    it('namePlaceholder 키가 존재한다', () => {
      expect(componentLabels.form.namePlaceholder).toBeTruthy()
    })

    it('descriptionPlaceholder 키가 존재한다', () => {
      expect(componentLabels.form.descriptionPlaceholder).toBeTruthy()
    })

    it('leadUnassigned 키가 존재한다', () => {
      expect(componentLabels.form.leadUnassigned).toBeTruthy()
    })
  })
})

describe('componentErrorMessage', () => {
  it('COMPONENT_NAME_DUPLICATE → 중복 이름 메시지를 반환한다', () => {
    expect(componentErrorMessage('COMPONENT_NAME_DUPLICATE')).toBe(
      '이미 같은 이름의 컴포넌트가 있습니다.',
    )
  })

  it('COMPONENT_LEAD_NOT_FOUND → 리드 사용자 없음 메시지를 반환한다', () => {
    expect(componentErrorMessage('COMPONENT_LEAD_NOT_FOUND')).toBe(
      '선택한 리드 사용자를 찾을 수 없습니다.',
    )
  })

  it('PROJECT_NOT_FOUND → 프로젝트 없음 메시지를 반환한다', () => {
    expect(componentErrorMessage('PROJECT_NOT_FOUND')).toBe(
      '프로젝트를 찾을 수 없습니다.',
    )
  })

  it('VALIDATION_FAILED → 입력값 확인 메시지를 반환한다', () => {
    expect(componentErrorMessage('VALIDATION_FAILED')).toBe(
      '입력값을 확인해 주세요.',
    )
  })

  it('알 수 없는 코드 → 기본 메시지를 반환한다', () => {
    expect(componentErrorMessage('UNKNOWN_CODE')).toBe(
      '요청을 처리하지 못했습니다.',
    )
  })

  it('null → 기본 메시지를 반환한다', () => {
    expect(componentErrorMessage(null)).toBe('요청을 처리하지 못했습니다.')
  })
})
