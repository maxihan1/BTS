// issueDetailStrings 신규 키 존재 여부를 타입 레벨에서 검증하는 테스트

import { describe, it, expect, expectTypeOf } from 'vitest'
import { issueDetailStrings, mfaStrings, mfaErrorMessage, issueLinkStrings, linkGraphStrings, notificationSubscriptionStrings } from './ko'
import { attachmentLabels } from './attachment-labels'

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

// ── mfaStrings ──────────────────────────────────────────────────────────────

type MfaStrings = typeof mfaStrings

describe('mfaStrings — 2FA UI 문자열 키 존재 검증', () => {
  // 설정 화면
  it('settingsTitle 키가 존재한다', () => {
    expectTypeOf<MfaStrings>().toHaveProperty('settingsTitle')
  })

  it('settingsDescription 키가 존재한다', () => {
    expectTypeOf<MfaStrings>().toHaveProperty('settingsDescription')
  })

  it('statusEnabled 키가 존재한다', () => {
    expectTypeOf<MfaStrings>().toHaveProperty('statusEnabled')
  })

  it('statusDisabled 키가 존재한다', () => {
    expectTypeOf<MfaStrings>().toHaveProperty('statusDisabled')
  })

  it('enableButton 키가 존재한다', () => {
    expectTypeOf<MfaStrings>().toHaveProperty('enableButton')
  })

  it('disableButton 키가 존재한다', () => {
    expectTypeOf<MfaStrings>().toHaveProperty('disableButton')
  })

  it('qrScanGuide 키가 존재한다', () => {
    expectTypeOf<MfaStrings>().toHaveProperty('qrScanGuide')
  })

  it('secretManualGuide 키가 존재한다', () => {
    expectTypeOf<MfaStrings>().toHaveProperty('secretManualGuide')
  })

  it('codeLabel 키가 존재한다', () => {
    expectTypeOf<MfaStrings>().toHaveProperty('codeLabel')
  })

  it('codePlaceholder 키가 존재한다', () => {
    expectTypeOf<MfaStrings>().toHaveProperty('codePlaceholder')
  })

  it('enableConfirmButton 키가 존재한다', () => {
    expectTypeOf<MfaStrings>().toHaveProperty('enableConfirmButton')
  })

  it('disableCodeLabel 키가 존재한다', () => {
    expectTypeOf<MfaStrings>().toHaveProperty('disableCodeLabel')
  })

  it('disableConfirmButton 키가 존재한다', () => {
    expectTypeOf<MfaStrings>().toHaveProperty('disableConfirmButton')
  })

  // 로그인 2단계
  it('loginStepGuide 키가 존재한다', () => {
    expectTypeOf<MfaStrings>().toHaveProperty('loginStepGuide')
  })

  it('loginCodeLabel 키가 존재한다', () => {
    expectTypeOf<MfaStrings>().toHaveProperty('loginCodeLabel')
  })

  it('loginVerifyButton 키가 존재한다', () => {
    expectTypeOf<MfaStrings>().toHaveProperty('loginVerifyButton')
  })

  it('loginBackToLogin 키가 존재한다', () => {
    expectTypeOf<MfaStrings>().toHaveProperty('loginBackToLogin')
  })

  // 런타임 값 smoke — 공백이 아닌 문자열
  it('settingsTitle은 비어 있지 않은 문자열이다', () => {
    expect(mfaStrings.settingsTitle).toBeTruthy()
  })

  it('enableButton은 비어 있지 않은 문자열이다', () => {
    expect(mfaStrings.enableButton).toBeTruthy()
  })

  it('모든 문자열 값은 콜론으로 끝나지 않는다', () => {
    for (const value of Object.values(mfaStrings)) {
      if (typeof value === 'string') {
        expect(value, `"${value}" 는 콜론으로 끝나면 안 됩니다`).not.toMatch(/:$/)
      }
    }
  })
})

// ── mfaErrorMessage ─────────────────────────────────────────────────────────

describe('mfaErrorMessage — 에러 코드 → 한국어 메시지 매핑', () => {
  it('invalid_code → 코드가 올바르지 않다는 메시지를 반환한다', () => {
    expect(mfaErrorMessage('invalid_code')).toBe('코드가 올바르지 않습니다.')
  })

  it('too_many_attempts → 잠시 후 재시도 안내 메시지를 반환한다', () => {
    expect(mfaErrorMessage('too_many_attempts')).toBe(
      '시도가 너무 많습니다. 잠시 후 다시 시도하세요.',
    )
  })

  it('no_pending_setup → 진행 중인 설정 없음 메시지를 반환한다', () => {
    expect(mfaErrorMessage('no_pending_setup')).toBeTruthy()
  })

  it('already_enabled → 이미 활성화됨 메시지를 반환한다', () => {
    expect(mfaErrorMessage('already_enabled')).toBeTruthy()
  })

  it('not_enabled → 활성화되지 않음 메시지를 반환한다', () => {
    expect(mfaErrorMessage('not_enabled')).toBeTruthy()
  })

  it('알 수 없는 코드 → 일반 fallback 메시지를 반환한다', () => {
    expect(mfaErrorMessage('UNKNOWN_CODE')).toBeTruthy()
  })

  it('모든 코드의 반환 메시지는 콜론으로 끝나지 않는다', () => {
    const codes = [
      'invalid_code',
      'too_many_attempts',
      'no_pending_setup',
      'already_enabled',
      'not_enabled',
      'UNKNOWN',
    ] as const
    for (const code of codes) {
      const msg = mfaErrorMessage(code)
      expect(msg, `"${msg}" 는 콜론으로 끝나면 안 됩니다`).not.toMatch(/:$/)
    }
  })
})

// ── issueLinkStrings ──────────────────────────────────────────────────────────

describe('issueLinkStrings — 이슈 링크 패널 문자열 (FR-LK-01 D6)', () => {
  it('모든 문자열 값은 콜론으로 끝나지 않는다 (글로벌 §5)', () => {
    for (const value of Object.values(issueLinkStrings)) {
      if (typeof value === 'string') {
        expect(value, `"${value}" 는 콜론으로 끝나면 안 됩니다`).not.toMatch(/:$/)
      }
    }
  })
})

// ── linkGraphStrings ───────────────────────────────────────────────────────────

type LinkGraphStrings = typeof linkGraphStrings

describe('linkGraphStrings — 링크 그래프 UI 문자열 (FR-LK-02 D6)', () => {
  it('sectionTitle 키가 존재한다', () => {
    expectTypeOf<LinkGraphStrings>().toHaveProperty('sectionTitle')
  })

  it('expandLabel 키가 존재한다', () => {
    expectTypeOf<LinkGraphStrings>().toHaveProperty('expandLabel')
  })

  it('collapseLabel 키가 존재한다', () => {
    expectTypeOf<LinkGraphStrings>().toHaveProperty('collapseLabel')
  })

  it('depthLabel 키가 존재한다', () => {
    expectTypeOf<LinkGraphStrings>().toHaveProperty('depthLabel')
  })

  it('depthOption1 키가 존재한다', () => {
    expectTypeOf<LinkGraphStrings>().toHaveProperty('depthOption1')
  })

  it('depthOption2 키가 존재한다', () => {
    expectTypeOf<LinkGraphStrings>().toHaveProperty('depthOption2')
  })

  it('depthOption3 키가 존재한다', () => {
    expectTypeOf<LinkGraphStrings>().toHaveProperty('depthOption3')
  })

  it('emptyState 키가 존재한다', () => {
    expectTypeOf<LinkGraphStrings>().toHaveProperty('emptyState')
  })

  it('truncatedNotice 키가 존재한다', () => {
    expectTypeOf<LinkGraphStrings>().toHaveProperty('truncatedNotice')
  })

  it('loadingState 키가 존재한다', () => {
    expectTypeOf<LinkGraphStrings>().toHaveProperty('loadingState')
  })

  it('renderError 키가 존재한다', () => {
    expectTypeOf<LinkGraphStrings>().toHaveProperty('renderError')
  })

  it('loadError 키가 존재한다', () => {
    expectTypeOf<LinkGraphStrings>().toHaveProperty('loadError')
  })

  it('notFound 키가 존재한다', () => {
    expectTypeOf<LinkGraphStrings>().toHaveProperty('notFound')
  })

  it('edgeBlocks 키가 존재한다', () => {
    expectTypeOf<LinkGraphStrings>().toHaveProperty('edgeBlocks')
  })

  it('edgeRelates 키가 존재한다', () => {
    expectTypeOf<LinkGraphStrings>().toHaveProperty('edgeRelates')
  })

  it('edgeDuplicates 키가 존재한다', () => {
    expectTypeOf<LinkGraphStrings>().toHaveProperty('edgeDuplicates')
  })

  it('edgeClones 키가 존재한다', () => {
    expectTypeOf<LinkGraphStrings>().toHaveProperty('edgeClones')
  })

  it('edgeParent 키가 존재한다', () => {
    expectTypeOf<LinkGraphStrings>().toHaveProperty('edgeParent')
  })

  it('nodeAriaLabel 키가 존재한다', () => {
    expectTypeOf<LinkGraphStrings>().toHaveProperty('nodeAriaLabel')
  })

  it('모든 문자열 값은 콜론으로 끝나지 않는다 (글로벌 §5)', () => {
    for (const value of Object.values(linkGraphStrings)) {
      if (typeof value === 'string') {
        expect(value, `"${value}" 는 콜론으로 끝나면 안 됩니다`).not.toMatch(/:$/)
      }
    }
  })

  // ── C1 회귀: 그래프 엣지 라벨이 링크 패널과 일치하는지 값까지 단언 ──
  it('C1: edgeBlocks는 issueLinkStrings.linkTypeBlocks(막음)와 일치한다', () => {
    expect(linkGraphStrings.edgeBlocks).toBe(issueLinkStrings.linkTypeBlocks)
  })

  it('C1: edgeRelates는 issueLinkStrings.linkTypeRelates(관련)와 일치한다', () => {
    expect(linkGraphStrings.edgeRelates).toBe(issueLinkStrings.linkTypeRelates)
  })

  it('C1: edgeParent는 부모 이슈 컨셉(부모)와 일치한다', () => {
    expect(linkGraphStrings.edgeParent).toBe('부모')
  })

  it('C1: edgeDuplicates(중복)와 edgeClones(복제)는 변경 없이 유지된다', () => {
    expect(linkGraphStrings.edgeDuplicates).toBe(issueLinkStrings.linkTypeDuplicates)
    expect(linkGraphStrings.edgeClones).toBe(issueLinkStrings.linkTypeClones)
  })
})

// ── attachmentLabels ──────────────────────────────────────────────────────────

describe('attachmentLabels — 첨부 파일 섹션 UI 문자열 (FR-AC-01 D6)', () => {
  it('모든 문자열 값은 콜론으로 끝나지 않는다 (글로벌 §5)', () => {
    for (const value of Object.values(attachmentLabels)) {
      if (typeof value === 'string') {
        expect(value, `"${value}" 는 콜론으로 끝나면 안 됩니다`).not.toMatch(/:$/)
      }
    }
  })

  it('sectionTitle 키가 존재한다', () => {
    expectTypeOf(attachmentLabels).toHaveProperty('sectionTitle')
  })

  it('emptyState 키가 존재한다', () => {
    expectTypeOf(attachmentLabels).toHaveProperty('emptyState')
  })

  it('dropzoneHint 키가 존재한다', () => {
    expectTypeOf(attachmentLabels).toHaveProperty('dropzoneHint')
  })

  it('deleteWarning 키가 존재한다', () => {
    expectTypeOf(attachmentLabels).toHaveProperty('deleteWarning')
  })

  it('deleteButton 키가 존재한다', () => {
    expectTypeOf(attachmentLabels).toHaveProperty('deleteButton')
  })

  it('deleteConfirmButton 키가 존재한다', () => {
    expectTypeOf(attachmentLabels).toHaveProperty('deleteConfirmButton')
  })

  it('deleteCancelButton 키가 존재한다', () => {
    expectTypeOf(attachmentLabels).toHaveProperty('deleteCancelButton')
  })
})

// ── issueDetailStrings — 감시자(watcher) 신규 키 검증 (FR-WT-01 D6) ──────────

describe('issueDetailStrings — 감시자(watcher) 신규 키 존재 검증 (FR-WT-01)', () => {
  it('watchersLabel 키가 존재한다', () => {
    expectTypeOf<IssueDetailStrings>().toHaveProperty('watchersLabel')
  })

  it('watchButton 키가 존재한다', () => {
    expectTypeOf<IssueDetailStrings>().toHaveProperty('watchButton')
  })

  it('unwatchButton 키가 존재한다', () => {
    expectTypeOf<IssueDetailStrings>().toHaveProperty('unwatchButton')
  })

  it('watchersEmpty 키가 존재한다', () => {
    expectTypeOf<IssueDetailStrings>().toHaveProperty('watchersEmpty')
  })

  it('watchersLoading 키가 존재한다', () => {
    expectTypeOf<IssueDetailStrings>().toHaveProperty('watchersLoading')
  })

  it('watchersError 키가 존재한다', () => {
    expectTypeOf<IssueDetailStrings>().toHaveProperty('watchersError')
  })

  it('watchersCount 키가 존재한다', () => {
    expectTypeOf<IssueDetailStrings>().toHaveProperty('watchersCount')
  })

  it('watcherSelfSuffix 키가 존재한다', () => {
    expectTypeOf<IssueDetailStrings>().toHaveProperty('watcherSelfSuffix')
  })

  it('watchersMore 키가 존재한다', () => {
    expectTypeOf<IssueDetailStrings>().toHaveProperty('watchersMore')
  })

  it('문자열 값은 콜론으로 끝나지 않는다', () => {
    const watcherStringKeys = [
      'watchersLabel',
      'watchButton',
      'unwatchButton',
      'watchersEmpty',
      'watchersLoading',
      'watchersError',
      'watcherSelfSuffix',
    ] as const
    for (const key of watcherStringKeys) {
      const value = issueDetailStrings[key]
      expect(value, `"${value}" 는 콜론으로 끝나면 안 됩니다`).not.toMatch(/:$/)
    }
  })

  it('watchersCount(n)은 "N명" 형식의 문자열을 반환한다', () => {
    expect(issueDetailStrings.watchersCount(3)).toBe('3명')
    expect(issueDetailStrings.watchersCount(0)).toBe('0명')
  })

  it('watchersMore(n)은 "+N명 더" 형식의 문자열을 반환한다', () => {
    expect(issueDetailStrings.watchersMore(5)).toBe('+5명 더')
  })
})

// ── changelogFieldLabels — N1 (FR-MV-02) 콜론 가드 ───────────────────────────

describe('issueDetailStrings.changelogFieldLabels — 콜론 종결 가드 (N1, FR-MV-02)', () => {
  it('모든 필드 라벨 값은 콜론으로 끝나지 않는다 (글로벌 §5)', () => {
    for (const [field, label] of Object.entries(issueDetailStrings.changelogFieldLabels)) {
      expect(label, `changelogFieldLabels["${field}"] = "${label}" 는 콜론으로 끝나면 안 됩니다`).not.toMatch(/:$/)
    }
  })
})

// ── notificationSubscriptionStrings — 콜론 가드 (FR-NT-04) ───────────────────

describe('notificationSubscriptionStrings — 사용자 알림 구독 설정 문자열 콜론 종결 가드 (FR-NT-04)', () => {
  it('모든 문자열 값은 콜론으로 끝나지 않는다 (글로벌 §5)', () => {
    for (const [key, value] of Object.entries(notificationSubscriptionStrings)) {
      if (typeof value === 'string') {
        expect(value, `notificationSubscriptionStrings["${key}"] = "${value}" 는 콜론으로 끝나면 안 됩니다`).not.toMatch(/:$/)
      }
    }
  })
})
