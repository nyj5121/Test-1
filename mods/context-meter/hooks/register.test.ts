import { expect, test } from 'claude-code/testing'

const measure = (tokens: number) => ({
  context: { tokens, window: 1_000_000, percent: Math.round(tokens / 10_000) },
  rateLimits: [{ kind: 'five_hour', percentUsed: 23.5 }],
  changed: ['context' as const],
})

test('컨텍스트가 차면 게이지를 그리고 알림은 한 번만 띄운다', async ($, on) => {
  let toasts = 0
  on('session.measure', (_$, e) => ({ changed: e.changed }))
  on('ui.toast', () => {
    toasts += 1
    return { value: undefined } as never
  })

  await $.session.measure(measure(210_000))
  await $.session.measure(measure(220_000))
  expect(toasts).toBe(1)

  const ui = await $.ui.mount({
    plugin: 'context-meter',
    surface: 'terminal',
    component: 'AbovePrompt',
    props: { hasSurvey: false, isWorking: false, maxRows: 5, columns: 80 } as never,
  })

  expect(await ui.find({ type: 'Text', text: /220K/ })).toBeDefined()
  expect(await ui.find({ type: 'Text', text: /five_hour 24%/ })).toBeDefined()
})
