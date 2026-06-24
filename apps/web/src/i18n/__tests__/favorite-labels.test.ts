// 즐겨찾기 i18n 라벨 키 존재 + 값 비어있지 않음 단위 테스트

import { describe, expect, it } from 'vitest'
import { favoriteLabels } from '../favorite-labels'

describe('favoriteLabels', () => {
  describe('aria 라벨 그룹', () => {
    it('addAriaLabel 키가 존재하고 비어있지 않다', () => {
      expect(favoriteLabels.addAriaLabel).toBeTruthy()
    })

    it('removeAriaLabel 키가 존재하고 비어있지 않다', () => {
      expect(favoriteLabels.removeAriaLabel).toBeTruthy()
    })

    it('dropdownTriggerAriaLabel 키가 존재하고 비어있지 않다', () => {
      expect(favoriteLabels.dropdownTriggerAriaLabel).toBeTruthy()
    })
  })

  describe('드롭다운 그룹', () => {
    it('dropdownTitle 키가 존재하고 비어있지 않다', () => {
      expect(favoriteLabels.dropdownTitle).toBeTruthy()
    })

    it('emptyMessage 키가 존재하고 비어있지 않다', () => {
      expect(favoriteLabels.emptyMessage).toBeTruthy()
    })
  })

  describe('타입별 그룹명', () => {
    it('groupIssue 키가 존재하고 비어있지 않다', () => {
      expect(favoriteLabels.groupIssue).toBeTruthy()
    })

    it('groupDashboard 키가 존재하고 비어있지 않다', () => {
      expect(favoriteLabels.groupDashboard).toBeTruthy()
    })

    it('groupProject 키가 존재하고 비어있지 않다', () => {
      expect(favoriteLabels.groupProject).toBeTruthy()
    })
  })

  describe('에러 메시지', () => {
    it('addError 키가 존재하고 비어있지 않다', () => {
      expect(favoriteLabels.addError).toBeTruthy()
    })

    it('removeError 키가 존재하고 비어있지 않다', () => {
      expect(favoriteLabels.removeError).toBeTruthy()
    })
  })

  describe('콜론 종결 가드', () => {
    it('모든 문자열 값은 콜론으로 끝나지 않는다 (글로벌 §5)', () => {
      for (const [key, value] of Object.entries(favoriteLabels)) {
        if (typeof value === 'string') {
          expect(value, `favoriteLabels["${key}"] = "${value}" 는 콜론으로 끝나면 안 됩니다`).not.toMatch(/:$/)
        }
      }
    })
  })
})
