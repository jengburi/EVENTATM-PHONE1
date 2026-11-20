---
name: jbwood-storycut
description: >-
  JBWOOD StoryCut - edit raw footage into a 60-second 9:16 vertical story video
  (Reels / Shorts) with a fixed hook, setup, development, payoff structure,
  sentence-end cuts, silence and repeated-speech removal, relevant B-roll, and
  centered two-line captions with gold emphasis. Use when the user says
  "StoryCut", "JBWOOD", "스토리컷", "릴스 편집", "쇼츠 편집", "60초로 잘라줘",
  "영상 편집해줘", or hands over footage or a transcript to turn into a short.
---

# JBWOOD StoryCut (v0.1)

All settings live in `config.json` next to this file. Read it first; if it
disagrees with this text, `config.json` wins.

Reply to the user in Korean, in plain words. The user is not a developer.

## Where the edit happens

The video is edited in **SsamCut (쌤컷)** through the SsamTool MCP tools (the ones
without a `doc_` or `shop_` prefix: `get_project`, `cut_range`, `split`,
`remove_silence`, `auto_captions`, `add_captions`, `add_text`, `add_media`,
`set_transition`, `add_sound_effect`, `get_frame`, `export_video`, `get_status`,
`undo`). Load them with ToolSearch before the first call.

- Start with `get_project`. If no editor tab is connected, ask the user to open
  https://ssamtool.com/tool/cut/ , open a project, and turn on 설정 → AI 연결.
- You cannot upload files. The user drags footage and B-roll into SsamCut; you
  place it with `add_media` by media id.
- If SsamCut is not available, still do steps 1-3 below from a transcript the
  user pastes, and hand back a cut list with timecodes they can apply by hand.

## Structure (60s, 9:16)

| part | seconds | job |
|---|---|---|
| HOOK | 0-5 | the strongest single claim or moment. No greeting, no intro. Pair with `/ig-reel` hook scoring if available. |
| SETUP | 5-15 | who, what, why it matters to the viewer. |
| DEVELOPMENT | 15-42 | the body. One idea per beat; change the frame or B-roll every beat. |
| PAYOFF | 42-60 | deliver what the hook promised, then one ask. Echo a word from the hook in the last line so the loop lands. |

Total must land at 60s or a little under. Never pad to reach 60.

## Workflow

1. **Read the material.** `get_project` for the timeline. Run `auto_captions`
   (poll `get_status`) to get the transcript with timings if there are no
   captions yet.
2. **Pick the story.** From the transcript, choose the hook moment (it may come
   from the middle of the raw footage; move it to 0s), then the setup,
   development and payoff lines. Show the user this plan as a short table
   (part, seconds, line) and ask for a yes before cutting.
3. **Cut.**
   - `cut_at_sentence_end`: every cut lands at the end of a sentence or clause,
     never mid-word.
   - `remove_repeated_speech`: when a line is said twice (a retake), keep the
     cleaner take and cut the other.
   - `detect_silence`: run `remove_silence`, but keep short natural pauses that
     carry meaning.
   - `preserve_meaning`: never cut in a way that flips or distorts what the
     speaker said. If a cut would change the meaning, keep the longer version.
4. **B-roll** (`select_relevant_broll`): only clips that show what is being said
   at that moment. No B-roll is better than unrelated B-roll.
5. **Transitions** (`avoid_excessive_transitions`): straight cuts by default.
   At most one or two soft transitions, only at part boundaries.
6. **Captions.**
   - Position: center of the safe area. On 1080x1920 keep text between y=230 and
     y=1440 and leave the right 230px clear (UI covers the rest).
   - Max 2 lines per caption.
   - Normal text `#FFFFFF`; emphasis words (numbers, key nouns, the hook claim)
     `#FFD700`. One or two emphasis words per caption, not more.
7. **Audio.** Keep the original voice untouched (`preserve_original_voice`).
   Keep natural room sound (`use_natural_sound`). Add a sound effect only where
   it matches something on screen (`effects_only_when_relevant`).
8. **Check.** `get_frame` at 0s, 5s, 15s, 42s and the last second. Confirm
   captions sit in the safe area and the hook text is up at frame one.
9. **Report** in Korean:

```
STORYCUT READY
길이:      58.4초 (목표 60초)
훅:        0-5초  "..."
구성:      셋업 5-15 / 전개 15-42 / 마무리 42-58
삭제:      무음 N곳, 반복 발화 N곳
B-roll:    N개
자막:      중앙 안전영역, 2줄 이하, 강조색 #FFD700
```

10. **Export only on request.** Run `export_video` (poll `get_status`) only when
    the user says to. Never post anywhere; the user uploads.

## Rules

- Never invent facts, numbers or quotes. Captions match what is actually said.
- Every edit is undoable with `undo`; tell the user that if they dislike a cut.
- If the footage has no clear payoff, say so instead of faking one.
