見るべきログ

1. cost 全探索が本当に 2 回走っているか
   [seed-debug] cost-full-start
   [seed-debug] cost-full-end

2. cost の再絞り込みに入っているか
   [seed-debug] cost-refilter-start
   [seed-debug] cost-refilter-end
   [seed-debug] cost-refilter-skip

3. clue を本当に回しているか、1候補なら skip しているか
   [seed-debug] clue-phase-check
   [seed-debug] clue-skip
   [seed-debug] clue-filter-start
   [seed-debug] clue-filter-end
   [seed-debug] clue-filter-zero
   [seed-debug] clue-filter-interrupted

4. 観測入力が何として読まれたか
   [obs-read]
   [seed-debug] obs-queued

5. 各観測の最終結果
   [item-result]

貼ってほしい範囲
- 1回の観測開始直前の [obs-read] から
- その観測に対応する最後の [item-result] まで
- もし cost-full-start が2回出ていれば、その2回分全部
