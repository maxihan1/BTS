// issueDetailStrings 신규 키 존재 여부를 타입 레벨에서 검증하는 테스트

import { describe, it, expect, expectTypeOf } from 'vitest'
import { issueDetailStrings, mfaStrings, mfaErrorMessage, issueLinkStrings, linkGraphStrings, notificationSubscriptionStrings, worklogStrings, epicChildrenStrings, commentStrings } from './ko'
import { attachmentLabels } from './attachment-labels'

// IssueDetailStrings 타입을 추론해서 키 존재를 검증한다.
// 키가 없으면 expectTypeOf(...).toHaveProperty() 가 타입 에러를 발생시킨다.
type IssueDetailStrings = typeof issueDetailStrings

describe('issueDetailStrings — 본문/메타필드 신규 키 존재 검증', () => {
  // ── 본문(description) 버튼 ─────────────────────────────────────────
  //
  // ★`descriptionWriteTab`/`descriptionPreviewTab` 단언은 2026-09-04 에 지웠다.
  //   WYSIWYG 전환으로 Write/Preview 탭 자체가 사라졌고, 키가 없는데 존재를 단언하면
  //   타입 에러가 난다. 되살리려면 탭 UI 부터 되살려야 한다 — 이유는 ko.ts 주석 참조.
  it('descriptionEditLabel 키가 존재한다', () => {
    expectTypeOf<IssueDetailStrings>().toHaveProperty('descriptionEditLabel')
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

  // ── 본문 편집 취소 확인 (FR-UX-11 F8 / 편차 D-1) ──────────────────────
  it('descriptionDiscardConfirm 키가 존재한다', () => {
    expectTypeOf<IssueDetailStrings>().toHaveProperty('descriptionDiscardConfirm')
  })

  it('descriptionDiscardConfirmButton 키가 존재한다', () => {
    expectTypeOf<IssueDetailStrings>().toHaveProperty('descriptionDiscardConfirmButton')
  })

  it('descriptionDiscardCancelButton 키가 존재한다', () => {
    expectTypeOf<IssueDetailStrings>().toHaveProperty('descriptionDiscardCancelButton')
  })

  /**
   * ★ 리뷰 F-2 — 확인 패널 문자열은 기존 저장/취소와 **달라야** 한다.
   * 같은 화면에 '취소' 가 둘이면 E2E strict mode violation 이 나고(learnings.md:631, PR #47
   * 「저장」 버튼 3개 실사고) 사용자도 「취소의 취소」를 이해하지 못한다.
   * 문자열이 다시 겹치면 이 단언이 막는다.
   */
  it('확인 패널 문자열은 기존 저장/취소 버튼과 부분 문자열로도 겹치지 않는다', () => {
    /**
     * **양방향 부분 일치**로 본다 — 완전 일치(`not.toBe`)만 보면 실제 실패 양식을 놓친다.
     * Playwright 의 `getByRole('button', { name })` 은 **부분 일치**라
     * `'취소하고 나가기'` 같은 값은 `not.toBe('취소')` 를 통과하면서도
     * `getByRole('button', { name: '취소' })` 를 strict mode violation 으로 깨뜨린다
     * (learnings.md:631 · PR #47 「저장」 버튼 3개 실사고와 같은 양식).
     */
    const existing = [
      issueDetailStrings.descriptionCancelButton,
      issueDetailStrings.descriptionSaveButton,
    ]
    const added = [
      issueDetailStrings.descriptionDiscardConfirmButton,
      issueDetailStrings.descriptionDiscardCancelButton,
    ]

    for (const base of existing) {
      for (const next of added) {
        expect(next).not.toContain(base)
        expect(base).not.toContain(next)
      }
    }
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

// ── worklogStrings — 워크로그/추정 시간 UI 문자열 (FR-TT-01 D6) ───────────────

type WorklogStrings = typeof worklogStrings

describe('worklogStrings — 추정 카드 키 존재 검증 (FR-TT-01 D6)', () => {
  // ── 추정(Estimate) 카드 ──────────────────────────────────────────────
  it('estimateSectionTitle 키가 존재한다', () => {
    expectTypeOf<WorklogStrings>().toHaveProperty('estimateSectionTitle')
  })

  it('originalEstimateLabel 키가 존재한다', () => {
    expectTypeOf<WorklogStrings>().toHaveProperty('originalEstimateLabel')
  })

  it('timeSpentLabel 키가 존재한다', () => {
    expectTypeOf<WorklogStrings>().toHaveProperty('timeSpentLabel')
  })

  it('remainingEstimateLabel 키가 존재한다', () => {
    expectTypeOf<WorklogStrings>().toHaveProperty('remainingEstimateLabel')
  })

  it('estimateNotSet 키가 존재한다', () => {
    expectTypeOf<WorklogStrings>().toHaveProperty('estimateNotSet')
  })

  it('estimateSaveButton 키가 존재한다', () => {
    expectTypeOf<WorklogStrings>().toHaveProperty('estimateSaveButton')
  })

  it('estimateSaveAriaLabel 키가 존재한다', () => {
    expectTypeOf<WorklogStrings>().toHaveProperty('estimateSaveAriaLabel')
  })

  it('estimateSaveError 키가 존재한다', () => {
    expectTypeOf<WorklogStrings>().toHaveProperty('estimateSaveError')
  })

  it('hoursLabel 키가 존재한다', () => {
    expectTypeOf<WorklogStrings>().toHaveProperty('hoursLabel')
  })

  it('minutesLabel 키가 존재한다', () => {
    expectTypeOf<WorklogStrings>().toHaveProperty('minutesLabel')
  })

  // ── 워크로그 섹션 ────────────────────────────────────────────────────
  it('worklogSectionTitle 키가 존재한다', () => {
    expectTypeOf<WorklogStrings>().toHaveProperty('worklogSectionTitle')
  })

  it('worklogEmptyState 키가 존재한다', () => {
    expectTypeOf<WorklogStrings>().toHaveProperty('worklogEmptyState')
  })

  it('worklogTimeHoursLabel 키가 존재한다', () => {
    expectTypeOf<WorklogStrings>().toHaveProperty('worklogTimeHoursLabel')
  })

  it('worklogTimeMinutesLabel 키가 존재한다', () => {
    expectTypeOf<WorklogStrings>().toHaveProperty('worklogTimeMinutesLabel')
  })

  it('worklogStartedAtLabel 키가 존재한다', () => {
    expectTypeOf<WorklogStrings>().toHaveProperty('worklogStartedAtLabel')
  })

  it('worklogCommentLabel 키가 존재한다', () => {
    expectTypeOf<WorklogStrings>().toHaveProperty('worklogCommentLabel')
  })

  it('worklogAdjustRemainingLabel 키가 존재한다', () => {
    expectTypeOf<WorklogStrings>().toHaveProperty('worklogAdjustRemainingLabel')
  })

  it('worklogAutoAdjustPreview 키가 존재한다', () => {
    expectTypeOf<WorklogStrings>().toHaveProperty('worklogAutoAdjustPreview')
  })

  it('worklogAddButton 키가 존재한다', () => {
    expectTypeOf<WorklogStrings>().toHaveProperty('worklogAddButton')
  })

  it('worklogAddAriaLabel 키가 존재한다', () => {
    expectTypeOf<WorklogStrings>().toHaveProperty('worklogAddAriaLabel')
  })

  it('worklogEditButton 키가 존재한다', () => {
    expectTypeOf<WorklogStrings>().toHaveProperty('worklogEditButton')
  })

  it('worklogEditAriaLabel 키가 존재한다', () => {
    expectTypeOf<WorklogStrings>().toHaveProperty('worklogEditAriaLabel')
  })

  it('worklogDeleteButton 키가 존재한다', () => {
    expectTypeOf<WorklogStrings>().toHaveProperty('worklogDeleteButton')
  })

  it('worklogDeleteAriaLabel 키가 존재한다', () => {
    expectTypeOf<WorklogStrings>().toHaveProperty('worklogDeleteAriaLabel')
  })

  it('worklogSaveButton 키가 존재한다', () => {
    expectTypeOf<WorklogStrings>().toHaveProperty('worklogSaveButton')
  })

  it('worklogAuthorLabel 키가 존재한다', () => {
    expectTypeOf<WorklogStrings>().toHaveProperty('worklogAuthorLabel')
  })

  it('worklogLoading 키가 존재한다', () => {
    expectTypeOf<WorklogStrings>().toHaveProperty('worklogLoading')
  })

  it('worklogLoadError 키가 존재한다', () => {
    expectTypeOf<WorklogStrings>().toHaveProperty('worklogLoadError')
  })

  it('worklogAddSuccess 키가 존재한다', () => {
    expectTypeOf<WorklogStrings>().toHaveProperty('worklogAddSuccess')
  })

  it('worklogAddError 키가 존재한다', () => {
    expectTypeOf<WorklogStrings>().toHaveProperty('worklogAddError')
  })

  it('worklogEditSuccess 키가 존재한다', () => {
    expectTypeOf<WorklogStrings>().toHaveProperty('worklogEditSuccess')
  })

  it('worklogEditError 키가 존재한다', () => {
    expectTypeOf<WorklogStrings>().toHaveProperty('worklogEditError')
  })

  it('worklogDeleteSuccess 키가 존재한다', () => {
    expectTypeOf<WorklogStrings>().toHaveProperty('worklogDeleteSuccess')
  })

  it('worklogDeleteError 키가 존재한다', () => {
    expectTypeOf<WorklogStrings>().toHaveProperty('worklogDeleteError')
  })

  it('모든 문자열 값은 콜론으로 끝나지 않는다 (글로벌 §5)', () => {
    for (const [key, value] of Object.entries(worklogStrings)) {
      if (typeof value === 'string') {
        expect(value, `worklogStrings["${key}"] = "${value}" 는 콜론으로 끝나면 안 됩니다`).not.toMatch(/:$/)
      }
    }
  })
})

// ── boardFilterLabels — 보드 필터 바 UI 문자열 (FR-BD-02 Task-5) ──────────────
import { boardFilterLabels } from './board-filter-labels'

describe('boardFilterLabels — 보드 필터 바 문자열 콜론 종결 가드 (FR-BD-02)', () => {
  it('filter 그룹 문자열 값은 콜론으로 끝나지 않는다 (글로벌 §5)', () => {
    for (const [key, value] of Object.entries(boardFilterLabels.filter)) {
      if (typeof value === 'string') {
        expect(value, `boardFilterLabels.filter["${key}"] = "${value}" 는 콜론으로 끝나면 안 됩니다`).not.toMatch(/:$/)
      }
    }
  })

  it('search 그룹 문자열 값은 콜론으로 끝나지 않는다 (글로벌 §5)', () => {
    for (const [key, value] of Object.entries(boardFilterLabels.search)) {
      if (typeof value === 'string') {
        expect(value, `boardFilterLabels.search["${key}"] = "${value}" 는 콜론으로 끝나면 안 됩니다`).not.toMatch(/:$/)
      }
    }
  })

  it('count.applied(2)는 "2개 적용 중" 형식의 문자열을 반환한다', () => {
    expect(boardFilterLabels.count.applied(2)).toBe('2개 적용 중')
  })

  it('chip.removeAriaLabel("bug")는 "bug 제거" 형식의 문자열을 반환한다', () => {
    expect(boardFilterLabels.chip.removeAriaLabel('bug')).toBe('bug 제거')
  })
})

// ── favoriteLabels — 즐겨찾기 UI 문자열 (FR-UX-02 D6/D7) ─────────────────────
import { favoriteLabels } from './favorite-labels'

describe('favoriteLabels — 즐겨찾기 UI 문자열 콜론 종결 가드 (FR-UX-02)', () => {
  it('addAriaLabel 키가 존재한다', () => {
    expectTypeOf(favoriteLabels).toHaveProperty('addAriaLabel')
  })

  it('removeAriaLabel 키가 존재한다', () => {
    expectTypeOf(favoriteLabels).toHaveProperty('removeAriaLabel')
  })

  it('dropdownTriggerAriaLabel 키가 존재한다', () => {
    expectTypeOf(favoriteLabels).toHaveProperty('dropdownTriggerAriaLabel')
  })

  it('dropdownTitle 키가 존재한다', () => {
    expectTypeOf(favoriteLabels).toHaveProperty('dropdownTitle')
  })

  it('emptyMessage 키가 존재한다', () => {
    expectTypeOf(favoriteLabels).toHaveProperty('emptyMessage')
  })

  it('groupIssue 키가 존재한다', () => {
    expectTypeOf(favoriteLabels).toHaveProperty('groupIssue')
  })

  it('groupDashboard 키가 존재한다', () => {
    expectTypeOf(favoriteLabels).toHaveProperty('groupDashboard')
  })

  it('groupProject 키가 존재한다', () => {
    expectTypeOf(favoriteLabels).toHaveProperty('groupProject')
  })

  it('addError 키가 존재한다', () => {
    expectTypeOf(favoriteLabels).toHaveProperty('addError')
  })

  it('removeError 키가 존재한다', () => {
    expectTypeOf(favoriteLabels).toHaveProperty('removeError')
  })

  it('모든 문자열 값은 콜론으로 끝나지 않는다 (글로벌 §5)', () => {
    for (const [key, value] of Object.entries(favoriteLabels)) {
      if (typeof value === 'string') {
        expect(value, `favoriteLabels["${key}"] = "${value}" 는 콜론으로 끝나면 안 됩니다`).not.toMatch(/:$/)
      }
    }
  })
})

// ── savedFilterLabels — 저장 필터 UI 문자열 (FR-SR-03 Task-1) ─────────────────
import { savedFilterLabels } from './saved-filter-labels'

describe('savedFilterLabels — 저장 필터 UI 문자열 콜론 종결 가드 (FR-SR-03)', () => {
  it('모든 문자열 값은 콜론으로 끝나지 않는다 (글로벌 §5)', () => {
    for (const [key, value] of Object.entries(savedFilterLabels)) {
      if (typeof value === 'string') {
        expect(value, `savedFilterLabels["${key}"] = "${value}" 는 콜론으로 끝나면 안 됩니다`).not.toMatch(/:$/)
      }
    }
  })

  it('sectionTitle 키가 존재한다', () => {
    expectTypeOf(savedFilterLabels).toHaveProperty('sectionTitle')
  })

  it('myFiltersTab 키가 존재한다', () => {
    expectTypeOf(savedFilterLabels).toHaveProperty('myFiltersTab')
  })

  it('emptyState 키가 존재한다', () => {
    expectTypeOf(savedFilterLabels).toHaveProperty('emptyState')
  })

  it('createButton 키가 존재한다', () => {
    expectTypeOf(savedFilterLabels).toHaveProperty('createButton')
  })

  it('saveButton 키가 존재한다', () => {
    expectTypeOf(savedFilterLabels).toHaveProperty('saveButton')
  })

  it('deleteButton 키가 존재한다', () => {
    expectTypeOf(savedFilterLabels).toHaveProperty('deleteButton')
  })

  it('nameConflictError 키가 존재한다', () => {
    expectTypeOf(savedFilterLabels).toHaveProperty('nameConflictError')
  })

  it('conflictError 키가 존재한다', () => {
    expectTypeOf(savedFilterLabels).toHaveProperty('conflictError')
  })
})

// ── epicChildrenStrings ──────────────────────────────────────────────────────

describe('epicChildrenStrings — 에픽 자식 이슈 섹션 문자열 (FR-EP-01 D6)', () => {
  it('sectionTitle 키가 존재한다', () => {
    expectTypeOf(epicChildrenStrings).toHaveProperty('sectionTitle')
  })

  it('emptyState 키가 존재한다', () => {
    expectTypeOf(epicChildrenStrings).toHaveProperty('emptyState')
  })

  it('addChildButton 키가 존재한다', () => {
    expectTypeOf(epicChildrenStrings).toHaveProperty('addChildButton')
  })

  it('disconnectButton 키가 존재한다', () => {
    expectTypeOf(epicChildrenStrings).toHaveProperty('disconnectButton')
  })

  it('모든 문자열 값은 콜론으로 끝나지 않는다 (글로벌 §5)', () => {
    for (const value of Object.values(epicChildrenStrings)) {
      if (typeof value === 'string') {
        expect(value, `"${value}" 는 콜론으로 끝나면 안 됩니다`).not.toMatch(/:$/)
      }
    }
  })
})

// ── FR-CO-01 댓글 문구 ────────────────────────────────────────────────────────
//
// ★이 블록이 없으면 검증이 조용히 비어 있다(vacuous). ko.test.ts 는 named import 로
// 검사 대상을 명시 열거하므로, 새 export 를 만들어도 import 목록에 넣지 않으면 아무것도
// 확인하지 않는다 — FR-CO-01 리뷰 G5.

describe('commentStrings — FR-CO-01 댓글 문구 키 존재 검증', () => {
  type CommentStrings = typeof commentStrings

  it('commentSectionTitle 키가 존재한다', () => {
    expectTypeOf<CommentStrings>().toHaveProperty('commentSectionTitle')
  })

  it('commentBodyLabel 키가 존재한다', () => {
    expectTypeOf<CommentStrings>().toHaveProperty('commentBodyLabel')
  })

  it('commentAddButton 키가 존재한다', () => {
    expectTypeOf<CommentStrings>().toHaveProperty('commentAddButton')
  })

  it('commentAddPending 키가 존재한다', () => {
    expectTypeOf<CommentStrings>().toHaveProperty('commentAddPending')
  })

  it('commentAddSuccess 키가 존재한다', () => {
    expectTypeOf<CommentStrings>().toHaveProperty('commentAddSuccess')
  })

  it('commentAddError 키가 존재한다', () => {
    expectTypeOf<CommentStrings>().toHaveProperty('commentAddError')
  })

  it('commentEmptyState 키가 존재한다', () => {
    expectTypeOf<CommentStrings>().toHaveProperty('commentEmptyState')
  })

  it('commentLoading 키가 존재한다', () => {
    expectTypeOf<CommentStrings>().toHaveProperty('commentLoading')
  })

  it('commentLoadError 키가 존재한다', () => {
    expectTypeOf<CommentStrings>().toHaveProperty('commentLoadError')
  })

  it('commentNoPermission 키가 존재한다', () => {
    expectTypeOf<CommentStrings>().toHaveProperty('commentNoPermission')
  })

  it('빈 상태와 권한 안내는 서로 다른 문구다 — 같은 증상에 원인이 둘이다', () => {
    expect(commentStrings.commentEmptyState).not.toBe(commentStrings.commentNoPermission)
    expect(commentStrings.commentEmptyState).not.toBe(commentStrings.commentLoadError)
  })
})

// ── commentStrings — 콜론 종결 가드 (FR-CO-02) ────────────────────────────────
//
// changelogFieldLabels(:488) 에는 이 가드가 있었지만 commentStrings 에는 없었다.
// FR-CO-02 가 키를 13개 늘리면서 사람이 수기로 확인했는데, 가드가 없으면 다음 사람이 어긴다.

describe('commentStrings — 콜론 종결 가드 (FR-CO-02)', () => {
  it('모든 댓글 문구 값은 콜론으로 끝나지 않는다 (글로벌 §5)', () => {
    for (const [key, value] of Object.entries(commentStrings)) {
      expect(value, `commentStrings["${key}"] = "${value}" 는 콜론으로 끝나면 안 됩니다`).not.toMatch(/:$/)
    }
  })
})

describe('issueDetailStrings — FR-CO-01 댓글 탭 라벨', () => {
  it('activityCommentTabLabel 키가 존재한다', () => {
    expectTypeOf<typeof issueDetailStrings>().toHaveProperty('activityCommentTabLabel')
  })

  it('댓글 탭 라벨은 다른 활동 탭 라벨과 겹치지 않는다 — 탭 라벨은 E2E 계약이다', () => {
    const labels = [
      issueDetailStrings.activityWorklogTabLabel,
      issueDetailStrings.activityLinksTabLabel,
      issueDetailStrings.activityHistoryTabLabel,
      issueDetailStrings.activityCommentTabLabel,
    ]
    expect(new Set(labels).size).toBe(labels.length)
  })
})
