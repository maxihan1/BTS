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

  describe('S3 — 백킹 유무에 따른 항목 게이팅 (FR-UX-08 PR-B 에서 2건 반전)', () => {
    // ⚠️ 이 블록을 통째로 지우지 말 것 (FR14-b).
    // S3 원 규칙은 "백킹 라우트·기능이 없는 항목(내 작업·최근·필터)은 포함하지 않는다.
    // 각 항목은 해당 기능 FR 에서 추가한다" 였다. FR-UX-08 PR-B 가 앞의 **둘에만**
    // 실 라우트를 부여했으므로 2건은 존재 단언으로 반전하고 2건은 부재 단언을 유지한다.
    // 블록째 삭제하면 filters·projects 가 가드를 잃는다 — "봉인은 절반만 닫힌다" 양식.

    it('myWork 키가 존재하고 "내 작업"이다 (FR-UX-08 PR-B 에서 추가 — /issues?assignee= 백킹)', () => {
      expect(Object.keys(navLabels)).toContain('myWork')
      expect(navLabels.myWork).toBe('내 작업')
    })

    it('recent 키가 존재하고 "최근 항목"이다 (FR-UX-08 PR-B 에서 추가 — /issues/$key 백킹)', () => {
      expect(Object.keys(navLabels)).toContain('recent')
      expect(navLabels.recent).toBe('최근 항목')
    })

    it('filters 키가 없다 (백킹 라우트 없음 — 가드 유지)', () => {
      expect(Object.keys(navLabels)).not.toContain('filters')
    })

    it('projects 키가 없다 (백킹 라우트 없음 — 가드 유지)', () => {
      expect(Object.keys(navLabels)).not.toContain('projects')
    })
  })
})
