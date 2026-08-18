// 버전 관리 UI 라벨 단위 테스트 — versionLabels 키 존재 + versionErrorMessage 매핑 검증

import { describe, it, expect } from 'vitest'
import { versionLabels, versionErrorMessage } from './version-labels'

describe('versionLabels', () => {
  describe('page 그룹', () => {
    it('heading 키가 존재한다', () => {
      expect(versionLabels.page.heading).toBeTruthy()
    })

    it('description 키가 존재한다', () => {
      expect(versionLabels.page.description).toBeTruthy()
    })

    it('emptyMessage 키가 존재한다', () => {
      expect(versionLabels.page.emptyMessage).toBeTruthy()
    })

    it('loadingStatus 키가 존재한다', () => {
      expect(versionLabels.page.loadingStatus).toBeTruthy()
    })
  })

  describe('actions 그룹', () => {
    it('addButton 키가 존재한다', () => {
      expect(versionLabels.actions.addButton).toBeTruthy()
    })

    it('editButton 키가 존재한다', () => {
      expect(versionLabels.actions.editButton).toBeTruthy()
    })

    it('deleteButton 키가 존재한다', () => {
      expect(versionLabels.actions.deleteButton).toBeTruthy()
    })

    it('saveButton 키가 존재한다', () => {
      expect(versionLabels.actions.saveButton).toBeTruthy()
    })

    it('cancelButton 키가 존재한다', () => {
      expect(versionLabels.actions.cancelButton).toBeTruthy()
    })

    it('deleteConfirm 키가 존재한다', () => {
      expect(versionLabels.actions.deleteConfirm).toBeTruthy()
    })
  })

  describe('form 그룹', () => {
    it('nameLabel 키가 존재한다', () => {
      expect(versionLabels.form.nameLabel).toBeTruthy()
    })

    it('descriptionLabel 키가 존재한다', () => {
      expect(versionLabels.form.descriptionLabel).toBeTruthy()
    })

    it('startDateLabel 키가 존재한다', () => {
      expect(versionLabels.form.startDateLabel).toBeTruthy()
    })

    it('releaseDateLabel 키가 존재한다', () => {
      expect(versionLabels.form.releaseDateLabel).toBeTruthy()
    })

    it('namePlaceholder 키가 존재한다', () => {
      expect(versionLabels.form.namePlaceholder).toBeTruthy()
    })

    it('descriptionPlaceholder 키가 존재한다', () => {
      expect(versionLabels.form.descriptionPlaceholder).toBeTruthy()
    })
  })
})

describe('versionErrorMessage', () => {
  it('VERSION_NAME_DUPLICATE → 중복 이름 메시지를 반환한다', () => {
    expect(versionErrorMessage('VERSION_NAME_DUPLICATE')).toBe(
      '이미 같은 이름의 버전이 있습니다.',
    )
  })

  it('PROJECT_NOT_FOUND → 프로젝트 없음 메시지를 반환한다', () => {
    expect(versionErrorMessage('PROJECT_NOT_FOUND')).toBe(
      '프로젝트를 찾을 수 없습니다.',
    )
  })

  it('VERSION_NOT_FOUND → 버전 없음 메시지를 반환한다', () => {
    expect(versionErrorMessage('VERSION_NOT_FOUND')).toBe(
      '버전을 찾을 수 없습니다.',
    )
  })

  it('VALIDATION_FAILED → 입력값 확인 메시지를 반환한다', () => {
    expect(versionErrorMessage('VALIDATION_FAILED')).toBe(
      '입력값을 확인해 주세요.',
    )
  })

  it('VERSION_ACCESS_DENIED → 권한 없음 메시지를 반환한다', () => {
    expect(versionErrorMessage('VERSION_ACCESS_DENIED')).toBe(
      '버전을 수정할 권한이 없습니다.',
    )
  })

  it('VERSION_TRANSITION_NOT_ALLOWED → 전환 불허 메시지를 반환한다', () => {
    expect(versionErrorMessage('VERSION_TRANSITION_NOT_ALLOWED')).toBeTruthy()
  })

  it('알 수 없는 코드 → 기본 메시지를 반환한다', () => {
    expect(versionErrorMessage('UNKNOWN_CODE')).toBe(
      '요청을 처리하지 못했습니다.',
    )
  })

  it('null → 기본 메시지를 반환한다', () => {
    expect(versionErrorMessage(null)).toBe('요청을 처리하지 못했습니다.')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// versionStatusLabel / versionTransitionLabel — FR-VR-02 Task 5 RED
// ─────────────────────────────────────────────────────────────────────────────

describe('versionStatusLabel', () => {
  it('UNRELEASED 상태 한국어 라벨을 반환한다', async () => {
    const { versionStatusLabel } = await import('./version-labels')
    expect(versionStatusLabel('UNRELEASED')).toBeTruthy()
  })

  it('RELEASED 상태 한국어 라벨을 반환한다', async () => {
    const { versionStatusLabel } = await import('./version-labels')
    expect(versionStatusLabel('RELEASED')).toBeTruthy()
  })

  it('ARCHIVED 상태 한국어 라벨을 반환한다', async () => {
    const { versionStatusLabel } = await import('./version-labels')
    expect(versionStatusLabel('ARCHIVED')).toBeTruthy()
  })
})

describe('versionTransitionLabel', () => {
  it('release 전환 한국어 라벨을 반환한다', async () => {
    const { versionTransitionLabel } = await import('./version-labels')
    expect(versionTransitionLabel('release')).toBeTruthy()
  })

  it('unrelease 전환 한국어 라벨을 반환한다', async () => {
    const { versionTransitionLabel } = await import('./version-labels')
    expect(versionTransitionLabel('unrelease')).toBeTruthy()
  })

  it('archive 전환 한국어 라벨을 반환한다', async () => {
    const { versionTransitionLabel } = await import('./version-labels')
    expect(versionTransitionLabel('archive')).toBeTruthy()
  })

  it('unarchive 전환 한국어 라벨을 반환한다', async () => {
    const { versionTransitionLabel } = await import('./version-labels')
    expect(versionTransitionLabel('unarchive')).toBeTruthy()
  })
})
