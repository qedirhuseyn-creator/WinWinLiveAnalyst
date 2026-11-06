# WinWinBet Live Analyst — Android source MVP

## Included in this iteration
- User-approved Android MediaProjection screen capture.
- Floating overlay with stop control.
- Foreground notification while capture is active.
- On-device ML Kit OCR.
- Conservative recognition of visible score, minute, decimal odds and common market labels.
- Approximate bookmaker overround calculation when at least two visible odds are available.
- Basic hedge-stake and scenario-net calculation helpers.
- OCR refresh throttling and safer bitmap / projection cleanup.

## Limitations
- Reads only content visible in the screen capture explicitly approved by the user.
- Does not access private WinWinBet APIs, credentials, hidden screens, or place bets.
- Can inspect only markets currently visible; it cannot automatically open every bookmaker market.
- OCR can misread labels and odds. Verify extracted values.
- Odds alone do not establish a profitable bet. No guaranteed-win instructions are generated.
- A reliable value model requires independent probability estimates, complete outcomes, settlement rules and verified current odds. Missing data is marked incomplete, not guessed.
- Android can stop capture when the user ends screen sharing or the OS revokes it.

## Build
Requires Android Studio, Android SDK 35 and JDK 17. This archive is source code only; no APK has been built or device-tested in this environment.

Package: `az.liveanalyst`
Min Android: API 26
