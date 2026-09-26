"""Cat Client for Windows — dark glass dashboard (tkinter, stdlib only).

Five sections: Dashboard, Subscriptions, Scanner, Free configs and Test. All
network work runs on background threads and reports back through a queue.
"""
from __future__ import annotations

import queue
import threading
import webbrowser

import tkinter as tk
from tkinter import font as tkfont

import catcore

# ---- theme (Cat identity: purple / black / white) ----
BG = "#000000"
BG_SOFT = "#0A0710"
CARD = "#141021"
CARD_2 = "#1C1533"
WHITE = "#FFFFFF"
TEXT_2 = "#C9C6D6"
TEXT_3 = "#8E86A3"
TEAL = "#A855F7"   # primary accent (same violet the app uses)
ALERT = "#D946EF"  # fuchsia
OUTLINE = "#3B2A5E"
RADIUS = 14


def flat_btn(parent, text, command, accent=False):
    bg = "#2A1B4D" if accent else CARD_2
    fg = WHITE
    button = tk.Button(
        parent,
        text=text,
        command=command,
        bg=bg,
        fg=fg,
        activebackground="#3A2668",
        activeforeground=WHITE,
        relief="flat",
        bd=0,
        padx=12,
        pady=6,
        cursor="hand2",
        font=("Segoe UI", 10, "bold"),
        highlightthickness=1,
        highlightbackground=OUTLINE,
        highlightcolor=OUTLINE,
    )
    return button


def section_title(parent, text):
    return tk.Label(parent, text=text, bg=BG_SOFT, fg=WHITE, font=("Segoe UI Semibold", 13, "bold"), anchor="w")


def detail(parent, text, wrap=560):
    label = tk.Label(
        parent,
        text=text,
        bg=BG_SOFT,
        fg=TEXT_2,
        justify="left",
        anchor="w",
        wraplength=wrap,
        font=("Segoe UI", 9),
    )
    return label


def card(parent):
    return tk.Frame(parent, bg=CARD, highlightthickness=1, highlightbackground=OUTLINE, padx=14, pady=12)


class CatApp(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title(f"Cat Client {catcore.VERSION}")
        self.configure(bg=BG)
        self.geometry("1020x720")
        self.minsize(900, 620)
        self.messages: queue.Queue = queue.Queue()
        self.library = catcore.load_library()
        self.subs: list[tuple[str, list[str]]] = []
        self.scan_running = False
        self.scan_stop = threading.Event()
        self.scan_results: list[dict] = []
        self.free_results: list[str] = []
        try:
            self.iconbitmap("assets/cat.ico")
        except Exception:  # noqa: BLE001
            pass

        self._build_header()
        self._build_nav()

        self.content = tk.Frame(self, bg=BG_SOFT)
        self.content.pack(side="top", fill="both", expand=True)

        self.status = tk.Label(self, text="Ready", bg=BG, fg=TEXT_3, anchor="w", padx=14, pady=6, font=("Segoe UI", 9))
        self.status.pack(side="bottom", fill="x")

        self.pages = {
            "Dashboard": self.build_dashboard,
            "Subscriptions": self.build_subs,
            "Scanner": self.build_scanner,
            "Free configs": self.build_free,
            "Test": self.build_test,
        }
        self.current = None
        self.show("Dashboard")
        self.after(120, self._pump)

    # ---------- chrome ----------
    def _build_header(self):
        header = tk.Frame(self, bg=BG_SOFT, height=86, highlightthickness=1, highlightbackground=OUTLINE)
        header.pack(side="top", fill="x")
        header.pack_propagate(False)
        canvas = tk.Canvas(header, width=520, height=86, bg=BG_SOFT, bd=0, highlightthickness=0)
        canvas.pack(side="left")
        # gradient wash
        for i in range(86):
            shade = "#120C22" if i > 70 else BG_SOFT
            canvas.create_line(0, i, 520, i, fill=shade)
        # white cat face
        canvas.create_oval(28, 18, 82, 72, fill=WHITE, outline=WHITE)
        canvas.create_polygon(30, 26, 36, 2, 50, 20, fill=WHITE, outline=WHITE)
        canvas.create_polygon(80, 26, 74, 2, 60, 20, fill=WHITE, outline=WHITE)
        canvas.create_polygon(34, 24, 38, 8, 47, 20, fill="#6D28D9", outline="#6D28D9")
        canvas.create_polygon(76, 24, 72, 8, 63, 20, fill="#6D28D9", outline="#6D28D9")
        canvas.create_oval(40, 38, 48, 50, fill="#100A1C", outline="#100A1C")
        canvas.create_oval(62, 38, 70, 50, fill="#100A1C", outline="#100A1C")
        canvas.create_oval(43, 40, 46, 44, fill=WHITE, outline=WHITE)
        canvas.create_oval(65, 40, 68, 44, fill=WHITE, outline=WHITE)
        canvas.create_polygon(52, 56, 58, 56, 55, 62, fill="#D946EF", outline="#D946EF")
        # violet bolt behind
        canvas.create_polygon(86, 6, 104, 6, 94, 30, 108, 30, 84, 78, 92, 42, 78, 42, fill=TEAL, outline=TEAL)
        canvas.create_text(
            130, 43, anchor="w", text="Cat Client", fill=WHITE, font=("Segoe UI Semibold", 20, "bold")
        )
        canvas.create_text(
            252, 43, anchor="w", text="Cat · fast configs · clean IPs", fill=TEXT_3, font=("Segoe UI", 9)
        )
        flat_btn(header, "GitHub updates", lambda: webbrowser.open("https://github.com/mazodimobinhost-creator/cat-client/releases")).pack(side="right", padx=16)

    def _build_nav(self):
        nav = tk.Frame(self, bg=BG_SOFT, highlightthickness=1, highlightbackground=OUTLINE)
        nav.pack(side="top", fill="x")
        self.nav_buttons = {}
        for label in self.pages:
            button = flat_btn(nav, label, lambda l=label: self.show(l))
            button.pack(side="left", padx=6, pady=8)
            self.nav_buttons[label] = button

    def show(self, name):
        for child in self.content.winfo_children():
            child.destroy()
        self.current = name
        self.pages[name](self.content)
        self.set_status(f"{name} — Ready")

    def set_status(self, text):
        self.status.configure(text=text)

    def _pump(self):
        try:
            while True:
                kind, payload = self.messages.get_nowait()
                if kind == "status":
                    self.set_status(payload)
                elif kind == "subs":
                    self.show("Subscriptions")
                elif kind == "scan":
                    self._on_scan_result(payload)
                elif kind == "scan-done":
                    self.scan_running = False
                    self.set_status(f"Scan finished — {len(self.scan_results)} results")
                    self.show("Scanner")
                elif kind == "free":
                    self.free_results = payload
                    self.set_status(f"Fetched {len(payload)} free configs")
                    self.show("Free configs")
                elif kind == "error":
                    self.set_status(f"Error: {payload}")
        except queue.Empty:
            pass
        self.after(120, self._pump)

    # ---------- Dashboard ----------
    def build_dashboard(self, parent):
        hero = tk.Frame(parent, bg=CARD, highlightthickness=1, highlightbackground=OUTLINE, padx=22, pady=22)
        hero.pack(fill="x", padx=18, pady=18)
        tk.Label(hero, text="🐱 Cat Client for Windows", bg=CARD, fg=WHITE, font=("Segoe UI Semibold", 18, "bold"), anchor="w").pack(anchor="w")
        tk.Label(
            hero,
            text="Fast configs, clean-IP scanning, subscriptions and delay tests —\nthe full Cat workflow on the desktop. Same purple/black/white identity.",
            bg=CARD,
            fg=TEXT_2,
            justify="left",
            anchor="w",
            font=("Segoe UI", 10),
        ).pack(anchor="w", pady=(8, 14))
        row = tk.Frame(hero, bg=CARD)
        row.pack(anchor="w")
        flat_btn(row, "Open Subscriptions", lambda: self.show("Subscriptions"), accent=True).pack(side="left", padx=4)
        flat_btn(row, "Open Scanner", lambda: self.show("Scanner")).pack(side="left", padx=4)
        flat_btn(row, "Free configs", lambda: self.show("Free configs")).pack(side="left", padx=4)

        card_stats = card(parent)
        card_stats.pack(fill="x", padx=18, pady=4)
        tk.Label(card_stats, text="At a glance", bg=CARD, fg=WHITE, font=("Segoe UI Semibold", 11, "bold"), anchor="w").pack(anchor="w")
        self.stats_label = tk.Label(
            card_stats,
            text="",
            bg=CARD,
            fg=TEXT_2,
            justify="left",
            anchor="w",
            font=("Segoe UI", 10),
        )
        self.stats_label.pack(anchor="w", pady=6)
        self._refresh_stats()

        card_info = card(parent)
        card_info.pack(fill="x", padx=18, pady=12)
        tk.Label(card_info, text="Cat Panel", bg=CARD, fg=WHITE, font=("Segoe UI Semibold", 11, "bold"), anchor="w").pack(anchor="w")
        tk.Label(
            card_info,
            text="The same VLESS/Trojan panel deployed with wrangler runs your configs. Scan clean IPs here, paste them in the panel's scanner, and hand subscription links to every user who needs a config.",
            bg=CARD,
            fg=TEXT_2,
            wraplength=640,
            justify="left",
            anchor="w",
            font=("Segoe UI", 9),
        ).pack(anchor="w", pady=6)

    def _refresh_stats(self):
        label = getattr(self, "stats_label", None)
        if not label:
            return
        lines = [
            f"· Subscription links loaded: {sum(len(links) for _, links in self.subs)} ({len(self.subs)} sources)",
            f"· Clean-IP library: {len(self.library)} IPs",
            f"· Scanner results: {len(self.scan_results)}",
            f"· Free configs: {len(self.free_results)}",
        ]
        label.configure(text="\n".join(lines))

    # ---------- Subscriptions ----------
    def build_subs(self, parent):
        top = tk.Frame(parent, bg=BG_SOFT)
        top.pack(fill="x", padx=18, pady=(18, 6))
        section_title(top, "Subscriptions").pack(anchor="w")
        detail(top, "Add any v2rayNG/V2Box/Clash subscription URL or paste links directly. Every format is available for sharing.").pack(anchor="w", pady=4)
        row = tk.Frame(top, bg=BG_SOFT)
        row.pack(fill="x", pady=6)
        self.sub_entry = tk.Entry(row, bg=CARD_2, fg=WHITE, insertbackground=WHITE, relief="flat", font=("Segoe UI", 10))
        self.sub_entry.pack(side="left", fill="x", expand=True, ipady=6)
        flat_btn(row, "Fetch", self._fetch_sub, accent=True).pack(side="left", padx=6)
        flat_btn(row, "Paste links", self._paste_links).pack(side="left")
        actions = tk.Frame(top, bg=BG_SOFT)
        actions.pack(fill="x", pady=4)
        flat_btn(actions, "Copy all links", self._copy_all).pack(side="left", padx=2)
        flat_btn(actions, "Copy raw text", self._copy_raw).pack(side="left", padx=2)
        flat_btn(actions, "Copy Clash YAML", self._copy_clash).pack(side="left", padx=2)

        list_card = card(parent)
        list_card.pack(fill="both", expand=True, padx=18, pady=12)
        self.sub_list = tk.Text(list_card, bg=CARD, fg=TEXT_2, relief="flat", font=("Consolas", 9), wrap="none")
        self.sub_list.pack(fill="both", expand=True)
        self._render_subs()

    def _all_links(self):
        return [link for _, links in self.subs for link in links]

    def _render_subs(self):
        text = getattr(self, "sub_list", None)
        if not text:
            return
        text.delete("1.0", "end")
        if not self.subs:
            text.insert("end", "No subscriptions yet — fetch a URL or paste links.\n")
            return
        for name, links in self.subs:
            text.insert("end", f"▸ {name} — {len(links)} configs\n")
            for link in links[:8]:
                text.insert("end", f"    {catcore.link_name(link)}\n")
            if len(links) > 8:
                text.insert("end", f"    … {len(links) - 8} more\n")
        self._refresh_stats()

    def _fetch_sub(self):
        url = self.sub_entry.get().strip()
        if not url:
            return

        def worker():
            try:
                links = catcore.parse_subscription(catcore.http_get(url))
                self.subs.append((url, links))
                self.messages.put(("subs", None))
                self.messages.put(("status", f"Fetched {len(links)} configs"))
            except Exception as error:  # noqa: BLE001
                self.messages.put(("error", str(error)))

        threading.Thread(target=worker, daemon=True).start()
        self.set_status("Fetching subscription…")

    def _paste_links(self):
        def worker():
            try:
                body = self.clipboard_get()
                links = catcore.parse_subscription(body)
                if links:
                    self.subs.append(("Pasted", links))
                    self.messages.put(("subs", None))
                    self.messages.put(("status", f"Loaded {len(links)} pasted links"))
                else:
                    self.messages.put(("error", "no config links found on the clipboard"))
            except tk.TclError as error:
                self.messages.put(("error", str(error)))

        threading.Thread(target=worker, daemon=True).start()

    def _copy_text(self, text, label):
        self.clipboard_clear()
        self.clipboard_append(text)
        self.set_status(label)

    def _copy_all(self):
        self._copy_text("\n".join(self._all_links()), "All links copied")

    def _copy_raw(self):
        self._copy_text("\n".join(self._all_links()), "Raw subscription copied")

    def _copy_clash(self):
        lines = ["proxies:"]
        for link in self._all_links():
            name = catcore.link_name(link)
            lines.append(f"  - name: \"{name}\"")
            lines.append(f"    type: {'vless' if link.startswith('vless') else 'trojan'}")
        self._copy_text("\n".join(lines), "Clash skeleton copied (proxies list)")

    # ---------- Scanner ----------
    def build_scanner(self, parent):
        top = tk.Frame(parent, bg=BG_SOFT)
        top.pack(fill="x", padx=18, pady=(18, 6))
        section_title(top, "Clean-IP scanner").pack(anchor="w")
        detail(top, "Two-stage probe: TCP first, then TLS + /cdn-cgi/trace for the real Cloudflare location. Recommended: pick a small fast set and hand those to the panel.").pack(anchor="w", pady=4)
        form = tk.Frame(top, bg=BG_SOFT)
        form.pack(fill="x", pady=6)
        tk.Label(form, text="SNI", bg=BG_SOFT, fg=TEXT_2, font=("Segoe UI", 9)).grid(row=0, column=0, sticky="w")
        self.scan_sni = tk.Entry(form, bg=CARD_2, fg=WHITE, insertbackground=WHITE, relief="flat", width=28)
        self.scan_sni.insert(0, catcore.RECOMMENDED_SNIS[0])
        self.scan_sni.grid(row=0, column=1, padx=6, ipady=4)
        tk.Label(form, text="Port", bg=BG_SOFT, fg=TEXT_2, font=("Segoe UI", 9)).grid(row=0, column=2, sticky="w")
        self.scan_port = tk.Entry(form, bg=CARD_2, fg=WHITE, insertbackground=WHITE, relief="flat", width=6)
        self.scan_port.insert(0, "443")
        self.scan_port.grid(row=0, column=3, padx=6, ipady=4)
        tk.Label(form, text="Ranges", bg=BG_SOFT, fg=TEXT_2, font=("Segoe UI", 9)).grid(row=1, column=0, sticky="w", pady=6)
        self.scan_ranges = tk.Entry(form, bg=CARD_2, fg=WHITE, insertbackground=WHITE, relief="flat")
        self.scan_ranges.insert(0, ", ".join(catcore.DEFAULT_RANGES))
        self.scan_ranges.grid(row=1, column=1, columnspan=3, sticky="we", padx=6, ipady=4)
        self.scan_use_lib = tk.BooleanVar(value=True)
        tk.Checkbutton(
            form,
            text=f"Include clean-IP library ({len(self.library)})",
            variable=self.scan_use_lib,
            bg=BG_SOFT,
            fg=TEXT_2,
            selectcolor=CARD_2,
            activebackground=BG_SOFT,
            activeforeground=WHITE,
            font=("Segoe UI", 9),
        ).grid(row=2, column=0, columnspan=2, sticky="w", pady=4)
        actions = tk.Frame(top, bg=BG_SOFT)
        actions.pack(fill="x", pady=4)
        self.scan_start_btn = flat_btn(actions, "Start scan", self._start_scan, accent=True)
        self.scan_start_btn.pack(side="left", padx=2)
        self.scan_stop_btn = flat_btn(actions, "Stop", self._stop_scan)
        self.scan_stop_btn.pack(side="left", padx=2)
        flat_btn(actions, "Copy best 3 (Cat configs)", self._copy_best).pack(side="left", padx=2)

        list_card = card(parent)
        list_card.pack(fill="both", expand=True, padx=18, pady=12)
        self.scan_list = tk.Text(list_card, bg=CARD, fg=TEXT_2, relief="flat", font=("Consolas", 9))
        self.scan_list.pack(fill="both", expand=True)
        self._render_scan()

    def _render_scan(self):
        text = getattr(self, "scan_list", None)
        if not text:
            return
        text.delete("1.0", "end")
        if not self.scan_results:
            text.insert("end", "No results yet — start a scan.\n")
            return
        for item in self.scan_results[:60]:
            tls = "TLS OK" if item["tls_ok"] else "TCP only"
            text.insert(
                "end",
                f"{item['ip']:<16} {item['ping_ms']:>5} ms  {tls:<9} {item['flag']} {item['country']} ({item['colo'] or '—'})\n",
            )

    def _on_scan_result(self, item):
        self.scan_results.append(item)
        self.scan_results.sort(key=lambda r: (not r["tls_ok"], r["ping_ms"]))
        self._render_scan()

    def _start_scan(self):
        if self.scan_running:
            return
        sni = self.scan_sni.get().strip() or catcore.RECOMMENDED_SNIS[0]
        try:
            port = int(self.scan_port.get().strip() or "443")
        except ValueError:
            port = 443
        candidates = catcore.build_candidates(self.scan_ranges.get(), self.scan_use_lib.get(), self.library)
        self.scan_running = True
        self.scan_stop.clear()
        self.scan_results = []
        self._render_scan()
        self.set_status(f"Scanning {len(candidates)} candidates…")

        def worker():
            for ip in candidates:
                if self.scan_stop.is_set():
                    break
                result = catcore.probe_ip(ip, port=port, sni=sni)
                if result:
                    self.messages.put(("scan", result))
            self.messages.put(("scan-done", None))

        threading.Thread(target=worker, daemon=True).start()

    def _stop_scan(self):
        self.scan_stop.set()
        self.set_status("Stopping scan…")

    def _copy_best(self):
        best = [item for item in self.scan_results if item["tls_ok"]][:3]
        if not best:
            self.set_status("No TLS-OK results to copy yet")
            return
        self._copy_text("\n".join(item["ip"] for item in best), f"Copied {len(best)} best IPs")

    # ---------- Free configs ----------
    def build_free(self, parent):
        top = tk.Frame(parent, bg=BG_SOFT)
        top.pack(fill="x", padx=18, pady=(18, 6))
        section_title(top, "Free configs").pack(anchor="w")
        detail(top, "Open, well-known public sources — each with its own credit. Fetch to load them into one list you can copy or test.").pack(anchor="w", pady=4)
        actions = tk.Frame(top, bg=BG_SOFT)
        actions.pack(fill="x", pady=6)
        flat_btn(actions, "Fetch sources", self._fetch_free, accent=True).pack(side="left", padx=2)
        flat_btn(actions, "Copy all", self._copy_free).pack(side="left", padx=2)
        list_card = card(parent)
        list_card.pack(fill="both", expand=True, padx=18, pady=12)
        self.free_list = tk.Text(list_card, bg=CARD, fg=TEXT_2, relief="flat", font=("Consolas", 9))
        self.free_list.pack(fill="both", expand=True)
        self._render_free()

    def _render_free(self):
        text = getattr(self, "free_list", None)
        if not text:
            return
        text.delete("1.0", "end")
        if not self.free_results:
            text.insert("end", "No free configs loaded yet.\n")
            return
        for link in self.free_results[:200]:
            text.insert("end", catcore.link_name(link) + "\n")

    def _fetch_free(self):
        def worker():
            collected: list[str] = []
            for source in catcore.FREE_SOURCES:
                try:
                    collected.extend(catcore.fetch_free_source(source))
                except Exception:  # noqa: BLE001 — skip a dead source
                    continue
            self.messages.put(("free", collected))

        threading.Thread(target=worker, daemon=True).start()
        self.set_status("Fetching free-config sources…")

    def _copy_free(self):
        self._copy_text("\n".join(self.free_results), f"Copied {len(self.free_results)} free configs")

    # ---------- Test ----------
    def build_test(self, parent):
        top = tk.Frame(parent, bg=BG_SOFT)
        top.pack(fill="x", padx=18, pady=(18, 6))
        section_title(top, "Link test").pack(anchor="w")
        detail(top, "Paste one or more config links (one per line) to measure TCP/TLS latency before sharing them.").pack(anchor="w", pady=4)
        self.test_input = tk.Text(top, bg=CARD_2, fg=WHITE, insertbackground=WHITE, relief="flat", height=6, font=("Consolas", 9))
        self.test_input.pack(fill="x", pady=6)
        flat_btn(top, "Test links", self._run_test, accent=True).pack(anchor="w")
        result_card = card(parent)
        result_card.pack(fill="both", expand=True, padx=18, pady=12)
        self.test_list = tk.Text(result_card, bg=CARD, fg=TEXT_2, relief="flat", font=("Consolas", 9))
        self.test_list.pack(fill="both", expand=True)

    def _run_test(self):
        body = self.test_input.get("1.0", "end")
        links = catcore.parse_subscription(body)
        if not links:
            self.set_status("No config links found in the input")
            return
        self.set_status(f"Testing {len(links)} links…")

        def worker():
            output = []
            for link in links:
                result = catcore.test_link(link)
                name = catcore.link_name(link)
                if result:
                    output.append(f"{name}: {result['ping_ms']} ms  {result['flag']} {result['country']}")
                else:
                    output.append(f"{name}: unreachable")
            self.messages.put(("status", "Test finished"))
            self.after(0, lambda: self._show_test(output))

        threading.Thread(target=worker, daemon=True).start()

    def _show_test(self, lines):
        text = getattr(self, "test_list", None)
        if not text:
            return
        text.delete("1.0", "end")
        text.insert("end", "\n".join(lines) + "\n")


if __name__ == "__main__":
    CatApp().mainloop()
