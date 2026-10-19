export type Reading = {
  tokens: number | null
  window: number
  percent: number | null
  limits: { kind: string; percent: number }[]
}

declare module 'claude-code' {
  interface PluginState {
    'context-meter': { reading: Reading | null; hasWarned: boolean }
  }
}
