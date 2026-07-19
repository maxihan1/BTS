// 사이드바/상단바 nav 라벨 상수 단위 테스트 — e2e 계약 문자열 4종 고정 + S3 제외 항목 회귀 가드

import { describe, expect, it } from 'vitest'
import { navLabels } from '../nav-labels'

describe('navLabels', () => {
  describe('🔒 e2e 계약 문자열 (글자 변경 금지)', () => {
    it('mainNav은 정확히 "메인 메뉴"이다', () => {
      expect(navLabels.mainNav).toBe('메인 메뉴')
    })

    it('adminNav은 정확히 "관리 메뉴"이다', () => {
      expect(navLabels.adminNav).toBe('관리 메뉴')
    })

    it('projectViewNav은 정확히 "프로젝트 뷰 전환"이다', () => {
      expect(navLabels.projectViewNav).toBe('프로젝트 뷰 전환')
    })

    it('search는 정확히 "검색"이다', () => {
      expect(navLabels.search).toBe('검색')
    })
  })

  describe('사이드바/상단바 라벨', () => {
    it('issues 라벨이 존재한다', () => {
      expect(navLabels.issues).toBeTruthy()
    })

    it('dashboards 라벨이 존재한다', () => {
      expect(navLabels.dashboards).toBeTruthy()
    })

    it('calendar 라벨이 존재한다', () => {
      expect(navLabels.calendar).toBeTruthy()
    })

    it('starred 라벨이 존재한다', () => {
      expect(navLabels.starred).toBeTruthy()
    })

    it('create 라벨이 존재한다', () => {
      expect(navLabels.create).toBeTruthy()
    })

    it('admin 라벨이 존재한다', () => {
      expect(navLabels.admin).toBeTruthy()
    })

    it('collapseSidebar 라벨이 존재한다', () => {
      expect(navLabels.collapseSidebar).toBeTruthy()
    })

    it('expandSidebar 라벨이 존재한다', () => {
      expect(navLabels.expandSidebar).toBeTruthy()
    })
  })

  describe('S3 — 백킹 없는 항목 제외 회귀 가드', () => {
    it('myWork 키가 없다', () => {
      expect(Object.keys(navLabels)).not.toContain('myWork')
    })

    it('recent 키가 없다', () => {
      expect(Object.keys(navLabels)).not.toContain('recent')
    })

    it('filters 키가 없다', () => {
      expect(Object.keys(navLabels)).not.toContain('filters')
    })

    it('projects 키가 없다', () => {
      expect(Object.keys(navLabels)).not.toContain('projects')
    })
  })
})
