// 알림 보관함(Inbox) i18n 라벨 키 존재 + 콜론 종결 금지 단위 테스트

import { describe, expect, it } from 'vitest'
import { inboxLabels } from '../inbox-labels'

describe('inboxLabels', () => {
  describe('페이지 그룹', () => {
    it('page.title 키가 존재하고 비어있지 않다', () => {
      expect(inboxLabels.page.title).toBeTruthy()
    })

    it('page.description 키가 존재하고 비어있지 않다', () => {
      expect(inboxLabels.page.description).toBeTruthy()
    })
  })

  describe('탭 그룹', () => {
    it('tabs.all 키가 존재하고 비어있지 않다', () => {
      expect(inboxLabels.tabs.all).toBeTruthy()
    })

    it('tabs.unread 키가 존재하고 비어있지 않다', () => {
      expect(inboxLabels.tabs.unread).toBeTruthy()
    })

    it('tabs.archived 키가 존재하고 비어있지 않다', () => {
      expect(inboxLabels.tabs.archived).toBeTruthy()
    })
  })

  describe('검색 그룹', () => {
    it('search.placeholder 키가 존재하고 비어있지 않다', () => {
      expect(inboxLabels.search.placeholder).toBeTruthy()
    })

    it('search.sender 키가 존재하고 비어있지 않다', () => {
      expect(inboxLabels.search.sender).toBeTruthy()
    })

    it('search.dateFrom 키가 존재하고 비어있지 않다', () => {
      expect(inboxLabels.search.dateFrom).toBeTruthy()
    })

    it('search.dateTo 키가 존재하고 비어있지 않다', () => {
      expect(inboxLabels.search.dateTo).toBeTruthy()
    })
  })

  describe('빈 상태 그룹', () => {
    it('empty.all 키가 존재하고 비어있지 않다', () => {
      expect(inboxLabels.empty.all).toBeTruthy()
    })

    it('empty.unread 키가 존재하고 비어있지 않다', () => {
      expect(inboxLabels.empty.unread).toBeTruthy()
    })

    it('empty.archived 키가 존재하고 비어있지 않다', () => {
      expect(inboxLabels.empty.archived).toBeTruthy()
    })
  })

  describe('항목 버튼 그룹', () => {
    it('item.markRead 키가 존재하고 비어있지 않다', () => {
      expect(inboxLabels.item.markRead).toBeTruthy()
    })

    it('item.markUnread 키가 존재하고 비어있지 않다', () => {
      expect(inboxLabels.item.markUnread).toBeTruthy()
    })

    it('item.archive 키가 존재하고 비어있지 않다', () => {
      expect(inboxLabels.item.archive).toBeTruthy()
    })

    it('item.unarchive 키가 존재하고 비어있지 않다', () => {
      expect(inboxLabels.item.unarchive).toBeTruthy()
    })
  })

  describe('일괄 그룹', () => {
    it('bulk.readAll 키가 존재하고 비어있지 않다', () => {
      expect(inboxLabels.bulk.readAll).toBeTruthy()
    })
  })

  describe('발신자 그룹', () => {
    it('sender.system 키가 존재하고 비어있지 않다', () => {
      expect(inboxLabels.sender.system).toBeTruthy()
    })

    it('sender.unknown 키가 존재하고 비어있지 않다', () => {
      expect(inboxLabels.sender.unknown).toBeTruthy()
    })
  })

  describe('Header 종 그룹', () => {
    it('bell.ariaLabel 키가 존재하고 비어있지 않다', () => {
      expect(inboxLabels.bell.ariaLabel).toBeTruthy()
    })

    it('bell.unreadBadgeScreenReader 키가 존재하고 비어있지 않다', () => {
      expect(inboxLabels.bell.unreadBadgeScreenReader).toBeTruthy()
    })
  })

  describe('페이지네이션 그룹', () => {
    it('pagination.previous 키가 존재하고 비어있지 않다', () => {
      expect(inboxLabels.pagination.previous).toBeTruthy()
    })

    it('pagination.next 키가 존재하고 비어있지 않다', () => {
      expect(inboxLabels.pagination.next).toBeTruthy()
    })
  })

  describe('콜론 종결 가드', () => {
    /** 객체를 재귀적으로 순회해 모든 문자열 리프 값을 [keyPath, value] 배열로 반환 */
    function collectStringLeaves(
      obj: Record<string, unknown>,
      prefix = '',
    ): Array<[string, string]> {
      const results: Array<[string, string]> = []
      for (const [key, value] of Object.entries(obj)) {
        const path = prefix ? `${prefix}.${key}` : key
        if (typeof value === 'string') {
          results.push([path, value])
        } else if (typeof value === 'object' && value !== null) {
          results.push(
            ...collectStringLeaves(value as Record<string, unknown>, path),
          )
        }
      }
      return results
    }

    it('모든 문자열 값은 콜론으로 끝나지 않는다 (글로벌 §5)', () => {
      const leaves = collectStringLeaves(
        inboxLabels as unknown as Record<string, unknown>,
      )
      for (const [keyPath, value] of leaves) {
        expect(
          value,
          `inboxLabels["${keyPath}"] = "${value}" 는 콜론으로 끝나면 안 됩니다`,
        ).not.toMatch(/:$/)
      }
    })
  })
})
