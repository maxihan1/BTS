// 스프린트 생성 실패 문구 판정의 계약 테스트 (FR-BD-04 PR ⑤)
import { describe, it, expect } from 'vitest'
import { ApiError } from '@/api/client'
import { backlogLabels } from '@/i18n/backlog-labels'
import { sprintCreateErrorMessage } from './backlog-sprint-create-error'

describe('sprintCreateErrorMessage — FR-BD-04 PR ⑤', () => {
  it('404 는 「이 보드는 스프린트를 받지 않는다」로 옮긴다 — 재시도를 권하지 않는다', () => {
    const message = sprintCreateErrorMessage(new ApiError(404, { code: 'AGILE_BOARD_NOT_FOUND' }))

    expect(message).toBe(backlogLabels.sprintCreateBoardRejectedError)
    // 🛑 이 실패는 재시도로 절대 성공하지 않는다. 문구가 재시도를 권하면 안 된다.
    expect(message).not.toContain(backlogLabels.retry)
  })

  it('그 밖의 API 실패는 재시도를 권한다', () => {
    expect(sprintCreateErrorMessage(new ApiError(500, null))).toBe(
      backlogLabels.sprintCreateFailedError,
    )
    expect(sprintCreateErrorMessage(new ApiError(409, null))).toBe(
      backlogLabels.sprintCreateFailedError,
    )
  })

  it('ApiError 가 아닌 것(네트워크 단절 등)도 재시도를 권한다', () => {
    expect(sprintCreateErrorMessage(new TypeError('Failed to fetch'))).toBe(
      backlogLabels.sprintCreateFailedError,
    )
    expect(sprintCreateErrorMessage(undefined)).toBe(backlogLabels.sprintCreateFailedError)
  })

  it('🛑 어느 경우에도 「이슈 이동」 문구를 쓰지 않는다 — 하지도 않은 동작의 이름이다', () => {
    const cases: unknown[] = [
      new ApiError(404, null),
      new ApiError(500, null),
      new TypeError('boom'),
      null,
    ]

    for (const error of cases) {
      expect(sprintCreateErrorMessage(error)).not.toBe(backlogLabels.moveFailedError)
    }
  })
})
