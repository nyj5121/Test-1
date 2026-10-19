import { expect, mock, test } from 'claude-code/testing'

test('서브에이전트가 시작되면 카드가 생기고 끝나면 끝남으로 바뀐다', async ($, on) => {
  mock.clock(on, { now: 1_000_000 })
  on('agent.spawn', () => ({ model: 'haiku', agentId: 'a1' }) as never)
  on('turn.complete', (_$, e) => ({ text: e.answer }) as never)

  await $.agent.spawn({
    tool_use_id: 't1',
    prompt: '파일 찾기',
    description: '파일 찾기',
    subagentType: 'Explore',
  } as never)

  const mount = () =>
    $.ui.mount({
      plugin: 'workers-board',
      surface: 'terminal',
      component: 'Pane',
      requestId: 'workers',
      props: {} as never,
    })

  let ui = await mount()
  expect(await ui.find({ type: 'Text', text: /\[진행 중\] 파일 찾기/ })).toBeDefined()
  await ui.unmount()

  await $.turn.complete({
    answer: '끝',
    durationMs: 10,
    isAborted: false,
    turnId: 'x',
    agentId: 'a1',
    reason: 'answer',
  } as never)

  ui = await mount()
  expect(await ui.find({ type: 'Text', text: /\[끝남\] 파일 찾기/ })).toBeDefined()
})
