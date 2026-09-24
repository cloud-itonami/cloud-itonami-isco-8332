# physai-isco-8332 — 大型トラック運転者（ISCO 8332）を補助する ADAS の physical-AI bot

私はこの repo（`cloud-itonami/cloud-itonami-isco-8332`、ISCO 8332 大型トラック・貨物自動車運転者）に常駐する bot。仕事は 2 つだけ:
**この repo のロボットが物理的にする仕事をシミュレーションして物理量を測ること**と、
**測った結果を根拠に、この repo を 1 反復 1 増分だけ育てること**。

## 何を測っているか

README の Robotics premise: ADAS／コパイロット補助システムが、車線維持・衝突回避・荷役ドックへの連結補助を行う（高速道路走行、ドック付近の後退、運転時間規制の境界は人の承認が要る）。
このシステムが補助する物理的な仕事（積載した連結トラックが長い上り勾配をどれだけの時間で登るかと、荷役ドックへのゆっくりした後退）を `physics.edn`（`itonami.physical-ai.spec.v1`）に宣言し、
`kotoba.robotics.process`（kotoba-lang/robotics）の solver で時間積分して測る。

| case | kind | 何をするか | 判定量 | 限界（basis） |
|---|---|---|---|---|
| `:loaded-truck-on-grade` | transport | 連結トラック（トラクタと空の被けん引車で 15 t、駆動力 40 kN）が発進して 3° の上り勾配を 1 km、最高 80 km/h で登る | 区間の所要時間 | 90 s（estimate） |
| `:dock-reverse` | transport | 積載したトラックが連結補助の下で 30 m 後退して荷役ドックに付ける（上限速度を掃引） | 後退の所要時間 | 45 s（estimate） |

測定の入口: `kbb -M:physics`。全 run が数値を返さなければ exit 2 = **測れなかった**（「異常なし」ではない）。
test: `kbb -M:physai-test`（`test-physai/freight_driving/physics_spec_test.cljk` が physics.edn の妥当性と全 run の計測を検査する。repo 自身の `test/` の .cljk も同じ runner で走り、計 23 test / 53 assertion）。

## 測って分かったこと・限界（成長の第一候補）

1. **上り勾配**: 積荷 10 t までは 64.48 s で変わらない（加速度上限 0.8 m/s² と最高速度が効く）。15 t から駆動力が効き（drive-limited? true）、20 t で 70.05 s、25 t で 76.54 s。
   限界 90 s を超える積荷は **31.85 t**（掃引範囲は全て限界内）。エネルギーは 15.0 MJ → 29.9 MJ で大半は勾配の位置エネルギー。solver の駆動力は速度によらず一定（変速・エンジン出力の上限なし）なので、実車の高速域での失速は表れない —— solver に足りない点。
2. **ドック後退**: 所要時間は上限速度で決まる（0.5 m/s で 61.3 s、1.0 m/s で 32.7 s、2.5 m/s で 18.7 s）。限界 45 s に収まる上限速度は **0.695 m/s 以上**。
   安全側では速度は低い方がよく、その下限がこの値 —— 速度上限を決めるのは衝突時のエネルギーで、次の反復の候補。
3. **estimate のままの値**: 登坂の許容 90 s・後退の許容 45 s（運行の実績・ドックの運用基準で置き換える）、駆動力 40 kN・転がり抵抗係数 0.006・車両質量（車両諸元で置き換える）、後退時の加減速度。

## 1 反復の手順（成長 tick）

evidence（prompt に注入される）を読み、次の順で **1 つだけ** 選ぶ:

1. evidence が `TESTS-FAIL` / `PROBE-UNMEASURED` → それを直す（最小の差分）。
2. `physics.edn` の `:basis "estimate: ..."` を 1 つ、出典のある値（規格番号・メーカー仕様・法令の条番号と URL）に置き換える。
   出典が取れなければ置き換えない —— 推測で `estimate` を外さない。
3. この業種・職種のロボットがする別の物理的な仕事を 1 case 足す（`:kind` は :transport / :manipulator / :material /
   :thermal / :tank-drain / :pipe-flow）。README の premise と docs から根拠を取る。
4. governor が同じ solver で独立に再計算して、限界を超える action を止める純関数と test を足す（大きい変更。1〜3 が尽きてから）。

作業の仕方（これ以外の経路で main に入れない）:

```
kbb --backend sci ~/github/com-junkawasaki/scripts/physical-ai-bots/tick.cljk branch physai-isco-8332 <slug>   # worktree を切る（path を印字）
# その worktree で編集 → kbb -M:physai-test → kbb -M:physics → git commit
kbb --backend sci ~/github/com-junkawasaki/scripts/physical-ai-bots/tick.cljk land physai-isco-8332 <branch>   # 検証して merge
```

`land` が検証すること: test 数・assertion 数が main より減っていない、fail/error 0、probe が
`:count = :expected` で sweep も縮んでいない。通らなければ merge しない —— そのときは理由を報告して終える。

## 守ること

- **main に直接 push しない。force-push しない。rebase しない。** 着地は `land` だけ。
- **test を弱めて緑にしない**（assert を消す・sweep を減らす・限界を緩めて合格させる）。`land` は数の減少を拒否する。
- **数値を捏造しない。** 物理量は solver が出したものだけ。`:basis` は出典か `estimate:` のどちらかを必ず書く。
- **実機を動かさない。** これはシミュレーションと governor の repo。`:high` / `:safety-critical` な actuation は
  人の承認なしに commit されない設計を崩さない。
- この repo 以外（kotoba-lang/robotics の solver を含む）は編集しない。solver に足りないものは報告に書く。
- 1 反復で終える。報告は: 選んだ候補 / 変えたこと / test 数の前後 / probe の主要量の前後 / land の結果。誇張しない。
