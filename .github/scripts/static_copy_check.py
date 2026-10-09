import argparse
import functools
import http.server
import os
import sys
import threading

from playwright.sync_api import sync_playwright

# width and device pixel ratio: the compact, medium and expanded layouts, 150 % is a typical Windows laptop
SCREENS = [(360, 1.5), (600, 1), (840, 1.5), (1240, 1), (1440, 1.5)]
LOCALES = {"ru": "ru-RU", "en": "en-US"}
VIEWS = ["home", "projects", "articles", "resume"]
TOLERANCE = 2
# Skia hints text to whole device pixels and Chrome does not: the same Roboto line comes out up to 4 % wider or
# narrower, small text at 100 % the most
TEXT_SLACK = 4
TEXT_SPREAD = 0.04
# the tools and settings tabs exist only in the app, the copy draws list bullets with CSS
APP_ONLY = {"Утилиты", "Настройки", "Tools", "Settings", "•"}
SHOWN = 15

APP_NODES = """() => {
    const root = [...document.querySelectorAll('*')].map(e => e.shadowRoot && e.shadowRoot.getElementById('cmp_a11y_root')).find(Boolean);
    if (!root) return [];
    return [...root.querySelectorAll('div')].map(e => ({
        text: [...e.childNodes].filter(n => n.nodeType === 3).map(n => n.textContent).join(''),
        label: e.getAttribute('aria-label') || '',
        role: e.getAttribute('role') || '',
        box: ['left', 'top', 'width', 'height'].map(k => parseFloat(e.style[k])),
    })).filter(n => (n.text.trim() || n.label.trim()) && n.box[2] > 0 && n.box[3] > 0);
}"""

COPY_NODES = """() => {
    const elements = [...document.getElementById('splash').querySelectorAll('*')].filter(e => {
        if (e.closest('svg')) return false;
        const r = e.getBoundingClientRect();
        return r.width >= 2 && r.height >= 2 && getComputedStyle(e).visibility !== 'hidden';
    });
    const index = new Map(elements.map((e, i) => [e, i]));
    return elements.map(e => {
        const style = getComputedStyle(e);
        const px = key => parseFloat(style[key]) || 0;
        const r = e.getBoundingClientRect();
        // the text alone, without an icon next to it
        const texts = [...e.childNodes].filter(n => n.nodeType === 3 && n.textContent.trim());
        const range = document.createRange();
        if (texts.length) {
            range.setStart(texts[0], 0);
            range.setEnd(texts[texts.length - 1], texts[texts.length - 1].length);
        } else {
            range.selectNodeContents(e);
        }
        const t = range.getBoundingClientRect();
        let parent = e.parentElement;
        while (parent && !index.has(parent)) parent = parent.parentElement;
        return {
            text: e.innerText || '',
            label: e.getAttribute('aria-label') || '',
            own: texts.length > 0,
            line: texts.length ? px('lineHeight') : 0,
            interactive: e.matches('a, button'),
            parent: parent ? index.get(parent) : -1,
            name: e.tagName.toLowerCase() + [...e.classList].map(c => '.' + c).join(''),
            box: [r.x, r.y, r.width, r.height],
            content: [
                r.x + px('borderLeftWidth') + px('paddingLeft'),
                r.y + px('borderTopWidth') + px('paddingTop'),
                r.width - px('borderLeftWidth') - px('paddingLeft') - px('borderRightWidth') - px('paddingRight'),
                r.height - px('borderTopWidth') - px('paddingTop') - px('borderBottomWidth') - px('paddingBottom'),
            ],
            range: [t.x, t.y, t.width, t.height],
        };
    });
}"""

# the app hides the copy once it starts; for a measurement it comes back over the canvas
SHOW_COPY = """() => {
    const splash = document.getElementById('splash');
    splash.dataset.style = splash.getAttribute('style') || '';
    splash.style.cssText += ';display:block;opacity:1;visibility:visible;transition:none';
    splash.scrollTop = 0;
    return splash.scrollHeight;
}"""
HIDE_COPY = "() => { const splash = document.getElementById('splash'); splash.setAttribute('style', splash.dataset.style); }"


class QuietHandler(http.server.SimpleHTTPRequestHandler):
    def log_message(self, *args):
        pass


def norm(text):
    return " ".join(text.split())


def keys(node):
    return {norm(node["text"]), norm(node["label"])} - {""}


def describe(node):
    text = norm(node["text"]) or norm(node["label"])
    return f'{node["role"] or "text"} "{text[:60]}"'


def rect(box):
    x, y, w, h = box
    return f"{x:.0f},{y:.0f} {w:.0f}x{h:.0f}"


def beside(box, other):
    overlap = min(box[1] + box[3], other[1] + other[3]) - max(box[1], other[1])
    return other[0] + other[2] <= box[0] + 1 and overlap > min(box[3], other[3]) / 2


# where the items above end: the lowest one in the same column and the lowest one across the page
def bottoms(boxes, box):
    over = [b for b in boxes if b[1] + b[3] <= box[1] + 1]
    column = [b for b in over if min(box[0] + box[2], b[0] + b[2]) > max(box[0], b[0])]
    return [max(b[1] + b[3] for b in group) if group else None for group in (column, over)]


def whole_lines(delta, lines):
    return any(round(abs(delta) / line) in (1, 2, 3) and abs(abs(delta) - round(abs(delta) / line) * line) < 1 for line in lines)


def match(app, copy):
    used = set()
    pairs, missing = [], []
    for node in app:
        if keys(node) & APP_ONLY:
            continue
        interactive = bool(node["role"])
        x, y, w, h = node["box"]

        # a button of the app is a link or button of the copy, a text of the app is the element holding that text;
        # a paragraph starts where its letters do, a single line is measured by its box or its letters, whichever fits
        def target(c):
            if interactive:
                return c["box"]
            content, text = c["content"], c["range"]
            if c["line"] and h > 1.5 * c["line"]:
                across = text
            else:
                across = min((content, text), key=lambda b: abs(b[0] - x) + abs(b[2] - w))
            return [across[0], content[1], across[2], content[3]]

        def score(i):
            c = copy[i]
            kind = c["interactive"] if interactive else c["own"]
            tx, ty, _, _ = target(c)
            return (0 if kind else 10000) + abs(tx - x) + abs(ty - y)

        candidates = [i for i, c in enumerate(copy) if i not in used and keys(node) & keys(c)]
        if not candidates:
            missing.append(f"missing in the copy: {describe(node)} at {rect(node['box'])}")
            continue
        best = min(candidates, key=score)
        used.add(best)
        pairs.append((node, best, node["box"], target(copy[best])))
    return pairs, used, missing


# Skia and Chrome break a few lines differently; a text that wraps into other lines and an item that moves to another
# row are listed, not failed, and the items after them are measured from their neighbours, so nothing drifts with them
def compare(app, copy):
    pairs, used, failures = match(app, copy)
    wraps = []
    lines = [set() for _ in copy]
    for i, c in enumerate(copy):
        j = i if c["line"] else -1
        while j >= 0:
            lines[j].add(c["line"])
            j = copy[j]["parent"]

    # the copy has three of the app's five tabs, and an item of the app's rail reaches to the rail's edge
    flow = []
    for node, k, a, c in pairs:
        where = f"{describe(node)}: app {rect(a)}, copy {rect(c)} <{copy[k]['name']}>"
        if node["role"] != "tab":
            flow.append((node, k, a, c, where))
        elif abs(c[1] - a[1]) > TOLERANCE or abs(c[3] - a[3]) > TOLERANCE:
            failures.append(where)

    same = [(i > 0 and beside(a, flow[i - 1][2]), i > 0 and beside(c, flow[i - 1][3])) for i, (_, _, a, c, _) in enumerate(flow)]
    rows = []
    for i, (in_app, in_copy) in enumerate(same):
        rows.append((rows[-1][0] if in_app else i, rows[-1][1] if in_copy else i))
    moved = {i for i, (in_app, in_copy) in enumerate(same) if in_app != in_copy}
    unsettled = {(side, rows[j][side]) for i in moved for j in (i - 1, i) for side in (0, 1)}

    for i, (node, k, a, c, where) in enumerate(flow):
        if i in moved:
            wraps.append(f"{where}: moves to another row")
            continue
        dw, dh = c[2] - a[2], c[3] - a[3]
        off = {}
        wrapped = abs(dh) > TOLERANCE and whole_lines(dh, lines[k])
        if wrapped:
            wraps.append(f"{where}: breaks into other lines")
        elif abs(dh) > TOLERANCE:
            off["height"] = dh
        paragraph = not node["role"] and copy[k]["line"] and a[3] > 1.5 * copy[k]["line"]
        if not wrapped and not paragraph and abs(dw) > max(TEXT_SLACK, a[2] * TEXT_SPREAD):
            off["width"] = dw

        # an item is in place where the app has it, after its neighbour in the row, or in a row whose left edge, right
        # edge or centre is in place; a centred or right-aligned item moves by its own difference in width
        if (0, rows[i][0]) not in unsettled and (1, rows[i][1]) not in unsettled:
            options = [(c[0] - a[0], TOLERANCE + abs(dw))]
            if same[i][0]:
                before_a, before_c = flow[i - 1][2], flow[i - 1][3]
                options.append(((c[0] - before_c[0] - before_c[2]) - (a[0] - before_a[0] - before_a[2]), TOLERANCE))
            else:
                row = [(p[2], p[3]) for j, p in enumerate(flow) if rows[j][0] == rows[i][0]]
                if len(row) > 1:
                    al, ar = min(b[0] for b, _ in row), max(b[0] + b[2] for b, _ in row)
                    cl, cr = min(b[0] for _, b in row), max(b[0] + b[2] for _, b in row)
                    options.append((min((cl - al, cr - ar, (cl + cr - al - ar) / 2), key=abs), TOLERANCE))
            if all(abs(d) > limit for d, limit in options):
                off["x"] = options[0][0]

        dy = c[1] - a[1]
        above = zip(bottoms([p[2] for p in flow[:i]], a), bottoms([p[3] for p in flow[:i]], c))
        options = [dy] + [(c[1] - end_c) - (a[1] - end_a) for end_a, end_c in above if end_a is not None and end_c is not None]
        if min(abs(o) for o in options) > TOLERANCE:
            off["y"] = dy

        if off:
            failures.append(where + " (" + ", ".join(f"{name} {d:+.1f}" for name, d in off.items()) + ")")

    covered = set(used)
    for i, c in enumerate(copy):
        parent = c["parent"]
        while parent >= 0:
            if parent in used:
                covered.add(i)
            if i in used:
                covered.add(parent)
            parent = copy[parent]["parent"]
    for i, c in enumerate(copy):
        if c["own"] and i not in covered and norm(c["text"]):
            failures.append(f'only in the copy: "{norm(c["text"])[:60]}" at {rect(c["box"])} <{c["name"]}>')
    return failures, wraps


# the screen is ready when nothing is loading, two readings of its tree agree and it shows every text the copy shows, or
# when nothing has changed for five seconds: Compose Resources read strings after the first frame, and on a slow runner
# a screen stands still for a second or two with its labels empty
def settle(page, pending, texts):
    last = nodes = None
    quiet = 0
    for _ in range(60):
        page.wait_for_timeout(500)
        nodes = page.evaluate(APP_NODES)
        shown = " | ".join(norm(n["text"]) + " " + norm(n["label"]) for n in nodes)
        quiet = quiet + 1 if nodes and nodes == last and not pending else 0
        if quiet and all(text in shown for text in texts) or quiet >= 10:
            return nodes
        last = nodes
    return nodes


def check(browser, base, width, ratio, lang):
    context = browser.new_context(
        viewport={"width": width, "height": 900},
        device_scale_factor=ratio,
        locale=LOCALES[lang],
        color_scheme="light",
        reduced_motion="reduce",
    )
    page = context.new_page()
    pending = set()
    page.on("request", lambda request: pending.add(request))
    page.on("requestfinished", lambda request: pending.discard(request))
    page.on("requestfailed", lambda request: pending.discard(request))
    page.goto(base + "#home", wait_until="domcontentloaded")
    page.wait_for_function("document.getElementById('splash').classList.contains('hidden')", timeout=180_000)
    results = {}
    for view in VIEWS:
        if view == "articles":
            segment = page.evaluate("""() => {
                const button = document.querySelector(`#splash .pre[lang="${document.documentElement.lang}"] .pre-segment[data-tab="articles"]`);
                button.click();
                return button.textContent.trim();
            }""")
            tab = next((n for n in settle(page, pending, [segment]) if norm(n["text"]) == segment and n["role"]), None)
            if tab is None:
                results[view] = (0, ([f'missing in the app: tab "{segment}" on the projects page'], []))
                continue
            tx, ty, tw, th = tab["box"]
            page.mouse.click(tx + tw / 2, ty + th / 2)
        else:
            page.evaluate("view => { location.hash = view }", view)
        page.wait_for_timeout(300)
        height = page.evaluate(SHOW_COPY)
        page.evaluate(HIDE_COPY)
        page.set_viewport_size({"width": width, "height": max(900, height + 100)})
        page.evaluate(SHOW_COPY)
        copy = page.evaluate(COPY_NODES)
        page.evaluate(HIDE_COPY)
        app = settle(page, pending, [norm(c["text"]) for c in copy if c["own"]])
        results[view] = (len(app), compare(app, copy))
    context.close()
    return results


def main():
    parser = argparse.ArgumentParser(description="Compares the HTML copy of the site in index.html with the pages the app draws")
    parser.add_argument("dist", help="the built site, webApp/build/dist/wasmJs/...Executable")
    parser.add_argument("--channel", help="an installed browser instead of Playwright's Chromium, e.g. msedge")
    parser.add_argument("--width", type=int, action="append", help="check only these screen widths")
    args = parser.parse_args()

    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), functools.partial(QuietHandler, directory=args.dist))
    threading.Thread(target=server.serve_forever, daemon=True).start()
    base = f"http://localhost:{server.server_port}/"

    report = []
    failed = 0
    with sync_playwright() as playwright:
        # nothing leaves the machine: the app keeps the bundled profile and counters, the ones the copy was built from
        browser = playwright.chromium.launch(channel=args.channel, args=["--host-resolver-rules=MAP * ~NOTFOUND, EXCLUDE localhost"])
        for width, ratio in SCREENS:
            if args.width and width not in args.width:
                continue
            for lang in LOCALES:
                for view, (count, (failures, wraps)) in check(browser, base, width, ratio, lang).items():
                    line = f"{view} {width}px x{ratio:g} {lang}: {count} nodes, {len(failures)} differ, {len(wraps)} wrap differently"
                    print(line, flush=True)
                    for problem in failures[:SHOWN] + ["wraps: " + wrap for wrap in wraps[:SHOWN]]:
                        print("  " + problem, flush=True)
                    if len(failures) > SHOWN:
                        print(f"  ...and {len(failures) - SHOWN} more", flush=True)
                    if failures:
                        failed += 1
                        report.append(f"- {line}: " + "; ".join(failures[:3]))
        browser.close()
    server.shutdown()

    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as out:
            out.write("### HTML copy of the site\n\n")
            out.write("\n".join(report) + "\n" if report else "Matches the app on every checked screen.\n")
    if failed:
        print(f"{failed} page views differ from the app by more than {TOLERANCE}px", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
