---
name: phone-video-edit
description: >-
  Edit a video that is on the user's smartphone into a fun Korean variety-show
  (예능) style clip using SsamCut (쌤컷) opened in the phone's Chrome, with
  situation captions, sound effects, zooms and stickers. Use when the user says
  "핸드폰 영상 편집해줘", "예능처럼 만들어줘", "효과음 넣어줘", "예능 자막",
  "쌤컷으로 편집", or mentions a family/kids video on their phone to edit.
---

# Phone video edit (SsamCut, 예능 style)

The user is not a developer. Reply in plain Korean. The user wants Claude to act
as a professional variety-show editor and decide the details.

## How the connection works (learned the hard way)

- Claude cannot reach files on the phone. The user loads the video into
  **SsamCut in Chrome** (https://ssamtool.com/tool/cut/), opens the project
  (timeline visible), and turns on **설정 → AI 연결**.
- On one phone, the SsamCut tab must stay on screen. Use **Android split screen**:
  SsamCut on top, the Claude app below. If SsamCut goes to the background the
  tools report "No SsamCut editor tab is connected".
- "Editor not ready" means the tab was found but is still waking up: ask the user
  to tap the SsamCut half once and wait a few seconds.
- CapCut (the app) cannot be controlled. If the screen has no Chrome address bar,
  check that it is really SsamCut, not CapCut.

## Reading frames on a phone

- `get_frame` alone often returns a stale picture. Reliable pattern, one at a
  time (never in parallel):
  `seek(t)` → `get_status` (gives the phone a moment) → `get_frame(time=t, max_size=320)`.
- While `auto_captions` runs, frames go stale; wait until it finishes.
- If frames keep repeating, stop sampling, build from what was seen, and tell the
  user the timings are estimates.

## Workflow

1. `get_project`. Note canvas size and duration.
2. Run `auto_captions(language="ko", lines=1, max_chars=14)` and poll `get_status`.
   On phones it runs at about real time. "No speech was recognized" is normal for
   sneaky/prank clips; then use situation captions only.
3. Sample frames (pattern above) every 3-5 s to find: calm start, first
   appearance, approach, reaction, ending.
4. Build the edit (the user has said "알아서 재밌게" is fine; ask only if the
   scene order is unclear):
   - **Captions** at the top (`position: "top"`, y ≈ -950 on 2160x2880) so faces
     stay clear. `font_size` **7** for up to ~9 chars, **6.3** for 10-12 chars
     (the default 8 and styles like mini-vlog at 15 overflow). Keep captions short.
   - Styles: `comic` (yellow, impact moments), `outline-white` (narration),
     `yellow-pop` (punchlines/ending), `mini-vlog` (calm opening).
   - **Stickers**: `scale` **0.25**, corner (x≈780, y≈-650). Default scale covers faces.
   - **Effects**: `zoom-in` for build-up, `zoom-punch` on the reveal,
     `speed-lines` (집중선) for the peak, `confetti` at the ending.
5. Check 1-2 frames with the longest caption and any sticker, fix overflow.
6. Summarize in a table (time, caption, sound/effect). Offer to shift timings,
   cut boring parts, or export. Export only when asked. Never post anywhere.

## Sound effect recipe (SsamCut built-ins)

| moment | sfx_id |
|---|---|
| calm opening | `sparkle` |
| something's off / build-up | `riser` |
| reveal | `boom` (+ `zoom-punch`) |
| sneaking / tension | `heartbeat`, `tick-tock` |
| right before the peak | `drum-roll` |
| funny reaction / oblivious kids | `boing`, `ba-dum-tss` |
| ending | `ta-da` then `applause` |

Do not stack effects back to back; leave ~0.3 s between captions. Keep the
original audio (kids' voices) untouched.

## Reference edit (kids + horse-mask mom prank, 45.8 s)

평화로운 저녁 🎵 → …근데 저기 뭐지? → 정체불명의 말 등장?! → 아이들은 아직 모른다… →
점점 다가온다… 😱 → 바로 뒤까지 왔다!! → 그래도 태블릿만 봄 ㅋㅋ → 집중력 무엇 👍 →
숨 막히는 대치 상황… → 과연 들킬 것인가?! → 말 아저씨…아니 엄마? → 범인은 엄마였다 🐴

## Privacy

Videos often show the user's children. If the user mentions posting publicly,
remind them once, briefly, that children's faces are visible.
