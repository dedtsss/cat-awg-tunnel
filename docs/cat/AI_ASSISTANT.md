# Cat Server AI Assistant

Diagnostics exposes **AI Assistant** only when the paired server's capabilities set
`features.aiGateway=true`. The Android APK contains no OpenAI/provider secret and does not select a
model unless the server policy provides one through a future protocol extension.

Questions are sent to `/api/v1/ai/chat` with optional incident context and the user's
`memoryEnabled` preference. `memoryEnabled` is a context request; it is not a promise of permanent
provider-side memory. The server returns candidate advice and the UI labels it as such. No AI result
can silently apply a tunnel or AWG setting. Deterministic local incidents and validation remain
visible when the AI gateway is disabled or unreachable.
