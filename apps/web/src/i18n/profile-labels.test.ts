// profile-labels 단위 테스트 — 라벨 그룹 존재 + mapProfileError/mapAvatarError 매핑 검증 (FR-PR-01 D6 Task 7)
import { describe, it, expect } from 'vitest'
import { ApiError } from '@/api/client'
import { profileLabels, mapProfileError, mapAvatarError } from './profile-labels'

// ─────────────────────────────────────────────────────────────────────────────
// profileLabels — 그룹별 키 존재 + 콜론 종결 금지
// ─────────────────────────────────────────────────────────────────────────────

describe('profileLabels', () => {
  describe('page 그룹', () => {
    it('heading/description 키가 존재한다', () => {
      expect(profileLabels.page.heading).toBeTruthy()
      expect(profileLabels.page.description).toBeTruthy()
    })
  })

  describe('status 그룹', () => {
    it('loading/loadError/saveSuccess 키가 존재한다', () => {
      expect(profileLabels.status.loading).toBeTruthy()
      expect(profileLabels.status.loadError).toBeTruthy()
      expect(profileLabels.status.saveSuccess).toBeTruthy()
    })
  })

  describe('form 그룹', () => {
    it('필드 라벨 5종 + 버튼 2종 키가 존재한다', () => {
      expect(profileLabels.form.usernameLabel).toBeTruthy()
      expect(profileLabels.form.emailLabel).toBeTruthy()
      expect(profileLabels.form.displayNameLabel).toBeTruthy()
      expect(profileLabels.form.timezoneLabel).toBeTruthy()
      expect(profileLabels.form.departmentLabel).toBeTruthy()
      expect(profileLabels.form.saveButton).toBeTruthy()
      expect(profileLabels.form.savingButton).toBeTruthy()
    })
  })

  describe('avatar 그룹', () => {
    it('fileInputLabel/deleteButton/deletingButton 키가 존재한다', () => {
      expect(profileLabels.avatar.fileInputLabel).toBeTruthy()
      expect(profileLabels.avatar.deleteButton).toBeTruthy()
      expect(profileLabels.avatar.deletingButton).toBeTruthy()
    })
  })

  describe('ldapSource 그룹 (FR-PR-04)', () => {
    it('배지 2종 + 힌트 2종 + 버튼 2종 키가 존재한다', () => {
      expect(profileLabels.ldapSource.syncedBadge).toBeTruthy()
      expect(profileLabels.ldapSource.syncedHint).toBeTruthy()
      expect(profileLabels.ldapSource.overriddenBadge).toBeTruthy()
      expect(profileLabels.ldapSource.overriddenHint).toBeTruthy()
      expect(profileLabels.ldapSource.resyncButton).toBeTruthy()
      expect(profileLabels.ldapSource.resyncingButton).toBeTruthy()
    })
  })

  it('모든 최상위 그룹 문자열은 콜론으로 끝나지 않는다', () => {
    const groups = [
      profileLabels.page,
      profileLabels.status,
      profileLabels.form,
      profileLabels.avatar,
      profileLabels.ldapSource,
    ]
    for (const group of groups) {
      for (const value of Object.values(group)) {
        expect(value).not.toMatch(/:$/)
      }
    }
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// mapProfileError
// ─────────────────────────────────────────────────────────────────────────────

describe('mapProfileError', () => {
  it('PROFILE_VALIDATION_FAILED → 표시 이름 입력 안내 메시지를 반환한다', () => {
    const error = new ApiError(400, { code: 'PROFILE_VALIDATION_FAILED', message: 'ignored' })
    expect(mapProfileError(error)).toBe('표시 이름을 입력해 주세요.')
  })

  it('PROFILE_NOT_FOUND → 프로필 없음 메시지를 반환한다', () => {
    const error = new ApiError(404, { code: 'PROFILE_NOT_FOUND', message: 'ignored' })
    expect(mapProfileError(error)).toBe('프로필을 찾을 수 없습니다.')
  })

  it('백엔드 message는 무시하고 code로만 매핑한다', () => {
    const error = new ApiError(400, {
      code: 'PROFILE_VALIDATION_FAILED',
      message: '이 메시지는 절대 노출되면 안 됨',
    })
    expect(mapProfileError(error)).not.toContain('절대 노출되면 안 됨')
  })

  it('401 → 세션 만료 메시지를 반환한다(코드 무관)', () => {
    const error = new ApiError(401, {})
    expect(mapProfileError(error)).toBe('로그인이 만료되었습니다. 다시 로그인해 주세요.')
  })

  it('알 수 없는 code → 폴백 메시지를 반환한다', () => {
    const error = new ApiError(500, { code: 'UNKNOWN_CODE' })
    expect(mapProfileError(error)).toBe('요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.')
  })

  it('body가 객체가 아니어도 폴백 메시지를 반환한다', () => {
    const error = new ApiError(500, null)
    expect(mapProfileError(error)).toBe('요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.')
  })

  it('DISPLAY_NAME_NOT_LDAP_LINKED(FR-PR-04 resync 409) → LDAP 미연결 안내 메시지를 반환한다', () => {
    const error = new ApiError(409, {
      code: 'DISPLAY_NAME_NOT_LDAP_LINKED',
      message: 'ignored',
    })
    expect(mapProfileError(error)).toContain('LDAP')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// mapAvatarError
// ─────────────────────────────────────────────────────────────────────────────

describe('mapAvatarError', () => {
  it('AVATAR_VALIDATION_FAILED → 크기/형식 안내 메시지를 반환한다', () => {
    const error = new ApiError(400, { code: 'AVATAR_VALIDATION_FAILED', message: 'ignored' })
    expect(mapAvatarError(error)).toContain('5MB')
  })

  it('AVATAR_NOT_FOUND → 아바타 없음 메시지를 반환한다', () => {
    const error = new ApiError(404, { code: 'AVATAR_NOT_FOUND', message: 'ignored' })
    expect(mapAvatarError(error)).toBe('아바타를 찾을 수 없습니다.')
  })

  it('401 → 세션 만료 메시지를 반환한다', () => {
    const error = new ApiError(401, {})
    expect(mapAvatarError(error)).toBe('로그인이 만료되었습니다. 다시 로그인해 주세요.')
  })

  it('알 수 없는 code → 폴백 메시지를 반환한다', () => {
    const error = new ApiError(500, { code: 'UNKNOWN_CODE' })
    expect(mapAvatarError(error)).toBe('요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.')
  })

  it('모든 매핑 결과는 콜론으로 끝나지 않는다', () => {
    const cases = [
      new ApiError(400, { code: 'AVATAR_VALIDATION_FAILED' }),
      new ApiError(404, { code: 'AVATAR_NOT_FOUND' }),
      new ApiError(401, {}),
      new ApiError(500, { code: 'UNKNOWN' }),
    ]
    for (const error of cases) {
      expect(mapAvatarError(error)).not.toMatch(/:$/)
    }
  })
})
