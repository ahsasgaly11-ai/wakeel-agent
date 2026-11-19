#!/usr/bin/env python3
"""Tiny helper for the emulator smoke test: find a visible element by its text and tap it.

    ui.py tap "<text>" [seconds to wait before tapping]
    ui.py dump                      # prints the texts on screen, one per line

Reading the screen this way briefly pauses accessibility services (Android does that for any
UI-dump tool), so `tap` waits before touching to let them reconnect.
"""
import html
import re
import subprocess
import sys
import time


def dump():
    for _ in range(3):
        out = subprocess.run(["adb", "exec-out", "uiautomator", "dump", "/dev/tty"], capture_output=True, timeout=60).stdout.decode("utf-8", "replace")
        if "<hierarchy" in out:
            return out
        time.sleep(2)
    return ""


def nodes(xml):
    for m in re.finditer(r"<node\b[^>]*>", xml):
        tag = m.group(0)
        def attr(name):
            a = re.search(r'\b%s="([^"]*)"' % name, tag)
            return html.unescape(a.group(1)) if a else ""
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', tag)
        if not b:
            continue
        x1, y1, x2, y2 = map(int, b.groups())
        yield (attr("text") or attr("content-desc")).strip(), ((x1 + x2) // 2, (y1 + y2) // 2)


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    xml = dump()
    if not xml:
        print("could not read the screen")
        return 1
    if sys.argv[1] == "dump":
        for text, _ in nodes(xml):
            if text:
                print(text)
        return 0
    want = sys.argv[2]
    wait = float(sys.argv[3]) if len(sys.argv) > 3 else 5.0
    found = [(t, c) for t, c in nodes(xml) if t == want] or [(t, c) for t, c in nodes(xml) if want in t]
    if not found:
        print("not on screen: " + want)
        return 1
    _, (x, y) = found[0]
    time.sleep(wait)
    subprocess.run(["adb", "shell", "input", "tap", str(x), str(y)], timeout=30)
    print("tapped %s at %d,%d" % (want, x, y))
    return 0


if __name__ == "__main__":
    sys.exit(main())
