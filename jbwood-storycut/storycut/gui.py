"""JBWOOD AI StoryCut - 간단한 화면 (Tkinter, 윈도우/맥 기본 포함).

흐름: 영상 고르기 → [1. 분석하기] → 표에서 문장 확인/수정 → [2. 편집 적용] → 결과 폴더
"""
from __future__ import annotations

import os
import queue
import subprocess
import sys
import threading
import tkinter as tk
from pathlib import Path
from tkinter import filedialog, messagebox, simpledialog, ttk

from . import __version__, analyze as an, pipeline, timeline
from .config import list_presets, load_preset

MODELS = ["small", "medium", "large-v3"]


class App(tk.Tk):
    def __init__(self) -> None:
        super().__init__()
        self.title(f"JBWOOD AI StoryCut v{__version__}")
        self.geometry("1100x760")
        self.minsize(900, 600)
        self.q: queue.Queue = queue.Queue()
        self.plan_path: Path | None = None
        self.plan: dict | None = None
        self.presets = list_presets()
        self._build()
        self.after(100, self._poll)

    # ------------------------------------------------------------ 화면 구성
    def _build(self) -> None:
        pad = {"padx": 6, "pady": 4}
        top = ttk.Frame(self)
        top.pack(fill="x", **pad)

        ttk.Label(top, text="원본 영상").grid(row=0, column=0, sticky="w")
        self.video_var = tk.StringVar()
        ttk.Entry(top, textvariable=self.video_var, width=70).grid(row=0, column=1, sticky="we")
        ttk.Button(top, text="찾아보기", command=self._pick_video).grid(row=0, column=2, **pad)
        ttk.Button(top, text="저장된 계획 열기", command=self._open_plan).grid(row=0, column=3, **pad)

        ttk.Label(top, text="프리셋").grid(row=1, column=0, sticky="w")
        self.preset_var = tk.StringVar(value=list(self.presets.values())[0] if self.presets else "")
        names = [self.presets[k] for k in ("shorts_story", "vlog_talk", "making_process") if k in self.presets]
        ttk.Combobox(top, textvariable=self.preset_var, values=names, state="readonly", width=24)\
            .grid(row=1, column=1, sticky="w")
        self.preset_var.set(names[0])

        ttk.Label(top, text="인식 정확도").grid(row=2, column=0, sticky="w")
        self.model_var = tk.StringVar(value="medium")
        mf = ttk.Frame(top)
        mf.grid(row=2, column=1, sticky="w")
        for m, label in zip(MODELS, ["빠름(small)", "보통(medium)", "정확(large-v3, 느림)"]):
            ttk.Radiobutton(mf, text=label, value=m, variable=self.model_var).pack(side="left", padx=4)

        ttk.Label(top, text="강조 단어").grid(row=3, column=0, sticky="w")
        self.kw_var = tk.StringVar()
        ttk.Entry(top, textvariable=self.kw_var, width=70).grid(row=3, column=1, sticky="we")
        ttk.Label(top, text="쉼표로 구분 (예: 월넛, 도마)").grid(row=3, column=2, columnspan=2, sticky="w")
        top.columnconfigure(1, weight=1)

        btns = ttk.Frame(self)
        btns.pack(fill="x", **pad)
        self.btn_analyze = ttk.Button(btns, text="1. 분석하기 (영상은 아직 안 바뀜)", command=self._analyze)
        self.btn_analyze.pack(side="left", padx=4)
        self.btn_apply = ttk.Button(btns, text="2. 편집 적용 (MP4 + SRT 만들기)", command=self._apply, state="disabled")
        self.btn_apply.pack(side="left", padx=4)
        self.btn_folder = ttk.Button(btns, text="결과 폴더 열기", command=self._open_folder, state="disabled")
        self.btn_folder.pack(side="left", padx=4)

        self.prog = ttk.Progressbar(self, maximum=100)
        self.prog.pack(fill="x", **pad)
        self.status = tk.StringVar(value="영상을 고른 뒤 '1. 분석하기'를 누르세요.")
        ttk.Label(self, textvariable=self.status).pack(fill="x", **pad)

        # 편집 계획 표
        mid = ttk.PanedWindow(self, orient="vertical")
        mid.pack(fill="both", expand=True, **pad)
        tf = ttk.Frame(mid)
        cols = ("keep", "time", "text", "reason")
        self.tree = ttk.Treeview(tf, columns=cols, show="headings", selectmode="extended")
        for c, label, w in [("keep", "사용", 50), ("time", "원본 시간", 110), ("text", "문장 (자막)", 640),
                            ("reason", "이유 / 메모", 240)]:
            self.tree.heading(c, text=label)
            self.tree.column(c, width=w, anchor="w", stretch=(c == "text"))
        sb = ttk.Scrollbar(tf, orient="vertical", command=self.tree.yview)
        self.tree.configure(yscrollcommand=sb.set)
        self.tree.pack(side="left", fill="both", expand=True)
        sb.pack(side="right", fill="y")
        self.tree.bind("<Double-1>", lambda e: self._toggle())
        self.tree.tag_configure("off", foreground="#999999")
        self.tree.tag_configure("hook", background="#FFF5C2")
        mid.add(tf, weight=4)

        ef = ttk.Frame(mid)
        row = ttk.Frame(ef)
        row.pack(fill="x")
        ttk.Button(row, text="사용/빼기 (더블클릭)", command=self._toggle).pack(side="left", padx=3)
        ttk.Button(row, text="자막 글자 수정", command=self._edit_caption).pack(side="left", padx=3)
        ttk.Button(row, text="오프닝으로 지정", command=self._set_hook).pack(side="left", padx=3)
        ttk.Button(row, text="오프닝 해제", command=self._clear_hook).pack(side="left", padx=3)
        ttk.Button(row, text="계획 저장", command=self._save).pack(side="left", padx=3)
        self.summary = tk.StringVar()
        ttk.Label(ef, textvariable=self.summary, foreground="#225").pack(fill="x", pady=4)
        self.info = tk.Text(ef, height=7, wrap="word")
        self.info.pack(fill="both", expand=True)
        mid.add(ef, weight=1)

    # ------------------------------------------------------------ 동작
    def _preset_id(self) -> str:
        name = self.preset_var.get()
        return next(k for k, v in self.presets.items() if v == name)

    def _pick_video(self) -> None:
        p = filedialog.askopenfilename(title="원본 영상 선택",
                                       filetypes=[("영상", "*.mp4 *.mov *.mkv *.m4v *.avi"), ("모든 파일", "*.*")])
        if p:
            self.video_var.set(p)

    def _open_plan(self) -> None:
        p = filedialog.askopenfilename(title="plan.json 열기", filetypes=[("편집 계획", "plan.json")])
        if p:
            self._load_plan(Path(p))

    def _run_bg(self, fn, done) -> None:
        self.btn_analyze.config(state="disabled")
        self.btn_apply.config(state="disabled")

        def work():
            try:
                res = fn(lambda p, m: self.q.put(("prog", p, m)))
                self.q.put(("done", done, res))
            except Exception as e:  # 사용자에게 그대로 보여줌
                self.q.put(("error", str(e)))
        threading.Thread(target=work, daemon=True).start()

    def _poll(self) -> None:
        try:
            while True:
                msg = self.q.get_nowait()
                if msg[0] == "prog":
                    self.prog["value"] = msg[1] * 100
                    self.status.set(msg[2])
                elif msg[0] == "done":
                    self.btn_analyze.config(state="normal")
                    msg[1](msg[2])
                elif msg[0] == "error":
                    self.btn_analyze.config(state="normal")
                    if self.plan:
                        self.btn_apply.config(state="normal")
                    self.status.set("오류가 났어요")
                    messagebox.showerror("오류", msg[1])
        except queue.Empty:
            pass
        self.after(100, self._poll)

    def _analyze(self) -> None:
        video = self.video_var.get().strip()
        if not video or not Path(video).exists():
            messagebox.showwarning("영상 필요", "원본 영상을 먼저 골라 주세요.")
            return
        kws = [k.strip() for k in self.kw_var.get().split(",") if k.strip()]
        preset, model = self._preset_id(), self.model_var.get()
        self._run_bg(lambda pr: pipeline.analyze(video, preset, model, keywords=kws, progress=pr), self._load_plan)

    def _load_plan(self, path: Path) -> None:
        self.plan_path = Path(path)
        self.plan = pipeline.load_plan(path)
        self.video_var.set(self.plan["source"])
        self.preset_var.set(self.presets.get(self.plan["preset"], self.preset_var.get()))
        self.kw_var.set(", ".join(self.plan.get("keywords", [])))
        self._refresh()
        self.btn_apply.config(state="normal")
        self.btn_folder.config(state="normal")
        self.status.set("표에서 문장을 확인하세요. 회색 = 빼는 문장. 다 됐으면 '2. 편집 적용'")

    def _refresh(self) -> None:
        if not self.plan:
            return
        sel = self.tree.selection()
        self.tree.delete(*self.tree.get_children())
        words = self.plan["words"]
        hook = self.plan.get("hook_sentence")
        for s in self.plan["sentences"]:
            removed = [words[i]["text"] for i in s["words"] if not words[i]["keep"]]
            note = s["reason"] or (("지운 말: " + " ".join(removed)) if removed else "")
            if s["id"] == hook:
                note = "★ 오프닝 " + note
            text = s["caption"] or " ".join(words[i]["text"] for i in s["words"] if words[i]["keep"]) or s["text"]
            tags = [] if s["keep"] else ["off"]
            if s["id"] == hook:
                tags.append("hook")
            self.tree.insert("", "end", iid=str(s["id"]), tags=tags, values=(
                "✔" if s["keep"] else "✘", f"{s['start']:.1f}~{s['end']:.1f}s", text, note))
        for i in sel:
            if self.tree.exists(i):
                self.tree.selection_add(i)
        preset = load_preset(self.plan["preset"])
        clips = timeline.build_clips(self.plan, preset, self.plan.get("silences"))
        est = timeline.total_duration(clips)
        st = an.summarize(self.plan, preset)
        self.summary.set(
            f"예상 길이 {est:.1f}초 (목표 {preset['target_duration']}초) · 문장 {st['sentences_kept']}/{st['sentences_total']} 사용 · "
            f"다시 말한 문장 {st['retakes_removed']} · 말버릇 {st['fillers_removed']} · 더듬음 {st['stutters_removed']} 제거")
        self.info.delete("1.0", "end")
        lines = ["[오프닝 후보] 원하는 문장을 표에서 고르고 '오프닝으로 지정'을 누르면 맨 앞에 미리보기로 붙어요.",
                 "(원래 자리에도 그대로 남아서 이야기 순서와 사실관계는 바뀌지 않아요)"]
        for h in self.plan.get("hook_candidates", []):
            lines.append(f"  #{h['sentence_id']}  점수 {h['score']}  {h['text']}  ({', '.join(h['why'])})")
        if self.plan.get("warnings"):
            lines.append("")
            lines += ["! " + w for w in self.plan["warnings"]]
        self.info.insert("1.0", "\n".join(lines))

    def _selected(self) -> list[dict]:
        ids = {int(i) for i in self.tree.selection()}
        return [s for s in (self.plan or {}).get("sentences", []) if s["id"] in ids]

    def _toggle(self) -> None:
        for s in self._selected():
            s["keep"] = not s["keep"]
            if s["keep"] and s["reason"].startswith("다시 말함"):
                s["reason"] = "사용자가 다시 넣음"
        self._refresh()

    def _edit_caption(self) -> None:
        sel = self._selected()
        if len(sel) != 1:
            messagebox.showinfo("하나만", "자막을 고칠 문장을 하나만 골라 주세요.")
            return
        s = sel[0]
        cur = s["caption"] or " ".join(self.plan["words"][i]["text"] for i in s["words"] if self.plan["words"][i]["keep"])
        new = simpledialog.askstring("자막 수정", "화면에 보일 자막 (소리는 바뀌지 않아요)", initialvalue=cur, parent=self)
        if new is not None:
            s["caption"] = new.strip()
            self._refresh()

    def _set_hook(self) -> None:
        sel = self._selected()
        if len(sel) != 1:
            messagebox.showinfo("하나만", "오프닝으로 쓸 문장을 하나만 골라 주세요.")
            return
        self.plan["hook_sentence"] = sel[0]["id"]
        self._refresh()

    def _clear_hook(self) -> None:
        if self.plan:
            self.plan["hook_sentence"] = None
            self._refresh()

    def _save(self) -> None:
        if self.plan and self.plan_path:
            kws = [k.strip() for k in self.kw_var.get().split(",") if k.strip()]
            self.plan["keywords"] = kws
            pipeline.save_plan(self.plan, self.plan_path)
            self.status.set(f"저장됨: {self.plan_path}")

    def _apply(self) -> None:
        if not self.plan:
            return
        self._save()
        path = self.plan_path
        self._run_bg(lambda pr: pipeline.apply(path, pr), self._applied)

    def _applied(self, res: dict) -> None:
        self.btn_apply.config(state="normal")
        self.status.set(f"완성! {res['duration']:.1f}초 · 컷 {res['clips']}개 · 자막 {res['captions']}개")
        messagebox.showinfo("완성", f"완성됐어요.\n\n{res['mp4']}\n{res['srt']}\n\n"
                                  "캡컷에서 이어서 고치는 방법은 결과 폴더의 '캡컷_가져오기_안내.txt'를 보세요.")
        self._open_folder()

    def _open_folder(self) -> None:
        if not self.plan_path:
            return
        d = str(self.plan_path.parent)
        if sys.platform.startswith("win"):
            os.startfile(d)  # type: ignore[attr-defined]
        elif sys.platform == "darwin":
            subprocess.Popen(["open", d])
        else:
            subprocess.Popen(["xdg-open", d])


def main() -> None:
    App().mainloop()


if __name__ == "__main__":
    main()
