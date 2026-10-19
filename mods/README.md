# Claude Code mods

- `context-meter`: 입력창 위에 컨텍스트 게이지 한 줄 (150K 노랑, 200K 빨강, 임계값은 `hooks/register.tsx` 맨 위).
- `workers-board`: WORKERS 패널에 서브에이전트 카드. 닫으면 `/workers`로 다시 엽니다.

## 켜기 전에 검사

```
claude plugin validate mods/context-meter
claude plugin test mods/context-meter
```

## 한 세션만 켜기

```
claude --plugin-dir mods/context-meter --plugin-dir mods/workers-board
```

끄는 방법: `--plugin-dir` 없이 새 세션을 열면 됩니다.
