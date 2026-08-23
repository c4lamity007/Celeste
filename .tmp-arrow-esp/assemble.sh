#!/bin/bash
set -euo pipefail
dest=src/main/java/dev/c4lam/celeste/gui/CelesteScreen.java
cat .tmp-arrow-esp/p00 .tmp-arrow-esp/p01 .tmp-arrow-esp/p02 .tmp-arrow-esp/p03 .tmp-arrow-esp/p04 .tmp-arrow-esp/p05 .tmp-arrow-esp/p06 .tmp-arrow-esp/p07 .tmp-arrow-esp/p08 .tmp-arrow-esp/p09 .tmp-arrow-esp/p10 .tmp-arrow-esp/p11 .tmp-arrow-esp/p12 .tmp-arrow-esp/p13 .tmp-arrow-esp/p14 .tmp-arrow-esp/p15 .tmp-arrow-esp/p16 .tmp-arrow-esp/p17 .tmp-arrow-esp/p18 .tmp-arrow-esp/p19 .tmp-arrow-esp/p20 .tmp-arrow-esp/p21 > $dest
wc -c $dest
echo 771d06135a1e45755569d9c5f03a6d3b36bf52afa84b6808929a7a85726952a0  $dest | sha256sum -c -
