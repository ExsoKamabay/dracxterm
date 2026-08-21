#!/bin/sh
# drac-Xterm login banner. Prints the ASCII art once, centred to the terminal width and tinted
# with a smooth vertical cyberpunk gradient (cyan -> electric blue -> purple -> magenta -> neon
# pink) using 24-bit truecolor SGR. Printed a single time at login: no per-frame cost, no
# flicker/redraw, and every line is reset so the gradient never leaks into later ANSI output.
art="$HOME/.banner.art"
[ -f "$art" ] || exit 0
cols="${COLUMNS:-$(tput cols 2>/dev/null || echo 80)}"
maxw=$(awk '{ if (length > m) m = length } END { print m + 0 }' "$art")
pad=$(( (cols - maxw) / 2 ))
[ "$pad" -lt 0 ] && pad=0
awk -v pad="$pad" '
BEGIN {
  n = split("34,211,238 59,130,246 139,92,246 217,70,239 255,47,185", stops, " ")
  esc = sprintf("%c", 27)
}
{ line[NR] = $0 }
END {
  total = NR
  if (total < 1) exit
  prefix = sprintf("%*s", pad, "")
  for (i = 1; i <= total; i++) {
    t = (total > 1) ? (i - 1) / (total - 1) : 0
    seg = t * (n - 1); k = int(seg); f = seg - k
    if (k >= n - 1) { k = n - 2; f = 1 }
    split(stops[k + 1], a, ","); split(stops[k + 2], b, ",")
    r  = int(a[1] + (b[1] - a[1]) * f + 0.5)
    g  = int(a[2] + (b[2] - a[2]) * f + 0.5)
    bl = int(a[3] + (b[3] - a[3]) * f + 0.5)
    printf("%s%s[38;2;%d;%d;%dm%s%s[0m\n", prefix, esc, r, g, bl, line[i], esc)
  }
}' "$art"
