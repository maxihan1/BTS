// 템플릿 변수 상수 + i18n 라벨 드리프트 가드 테스트 (FR-TM-02 D6/D7)
import { describe, it, expect } from 'vitest'
import { TEMPLATE_VARIABLES } from '../template-variables'
import { issueTemplateLabels } from '@/i18n/issue-template-labels'

describe('TEMPLATE_VARIABLES', () => {
  it('3종이 정확히 정의되어 있다', () => {
    expect(TEMPLATE_VARIABLES).toHaveLength(3)
  })

  it('token이 백엔드 TemplateVariable enum과 정확히 일치한다 — 드리프트 가드', () => {
    // 백엔드 TemplateVariable.kt enum 값과 1:1 대응.
    // 여기가 빨간불이면 백엔드 enum을 먼저 확인할 것.
    const tokens = TEMPLATE_VARIABLES.map((v) => v.token)
    expect(tokens).toContain('{{author}}')
    expect(tokens).toContain('{{date}}')
    expect(tokens).toContain('{{project}}')
  })

  it('token 형식이 소문자·공백 없는 이중 중괄호 패턴이다', () => {
    const pattern = /^\{\{[a-z]+\}\}$/
    for (const v of TEMPLATE_VARIABLES) {
      expect(v.token, `token "${v.token}" 형식 불일치`).toMatch(pattern)
    }
  })

  it('author 항목 insertLabel이 "작성자"이다', () => {
    const author = TEMPLATE_VARIABLES.find((v) => v.token === '{{author}}')
    expect(author).toBeDefined()
    expect(author?.insertLabel).toBe('작성자')
  })

  it('date 항목 insertLabel이 "일자"이다', () => {
    const date = TEMPLATE_VARIABLES.find((v) => v.token === '{{date}}')
    expect(date).toBeDefined()
    expect(date?.insertLabel).toBe('일자')
  })

  it('project 항목 insertLabel이 "프로젝트"이다', () => {
    const project = TEMPLATE_VARIABLES.find((v) => v.token === '{{project}}')
    expect(project).toBeDefined()
    expect(project?.insertLabel).toBe('프로젝트')
  })

  it('각 항목에 비어 있지 않은 description이 있다', () => {
    for (const v of TEMPLATE_VARIABLES) {
      expect(v.description, `"${v.token}" description 누락`).toBeTruthy()
    }
  })
})

describe('issueTemplateLabels.form — 변수 관련 키', () => {
  it('variableHelpPrefix 키가 존재하고 비어 있지 않다', () => {
    expect(issueTemplateLabels.form.variableHelpPrefix).toBeTruthy()
  })

  it('variableHelpSuffix 키가 존재하고 비어 있지 않다', () => {
    expect(issueTemplateLabels.form.variableHelpSuffix).toBeTruthy()
  })

  it('variableInsertAria가 label을 포함한 문자열을 반환한다', () => {
    const result = issueTemplateLabels.form.variableInsertAria('작성자')
    expect(result).toContain('작성자')
    expect(result.length).toBeGreaterThan('작성자'.length)
  })

  it('기존 form 키(createTitle/submitButton 등)가 보존되어 있다', () => {
    expect(issueTemplateLabels.form.createTitle).toBeTruthy()
    expect(issueTemplateLabels.form.editTitle).toBeTruthy()
    expect(issueTemplateLabels.form.submitButton).toBeTruthy()
    expect(issueTemplateLabels.form.cancelButton).toBeTruthy()
  })
})
