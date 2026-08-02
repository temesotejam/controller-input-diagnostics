# Controller Input Diagnostics

PS4 / PS5 コントローラを Android の Bluetooth 設定から接続し、Android アプリへ実際に届く入力を確認するための診断用プロジェクトです。Bluetooth をアプリから直接制御せず、Android の標準入力イベントだけを監視します。

## 実装済み（v0.1.1）

- GAMEPAD / JOYSTICK として接続されたコントローラの検出
- TOUCHPAD、または外部 MOUSE として公開されたタッチパッド候補デバイスの検出
- デバイス名、ID、descriptor、入力ソース、外部デバイス判定の表示
- Android が公開する全 `MotionRange` の自動列挙
- 全軸の生値、min / max / flat / fuzz / resolution / source の表示
- ボタンの押下・解放、scanCode、repeatCount の履歴表示
- ポインタの raw 座標、正規化座標、pointer ID、tool type、pressure、button state の表示
- タッチパッドの上・下スワイプ、および左右の上端から下端への対角急停止ジェスチャー判定
- `ACTION_CANCEL` 時のジェスチャー判定破棄
- コントローラ操作が画面上のボタンや戻る操作へ流れないよう、対象イベントをアプリ側で消費
- 重要イベント、ポインタ履歴、軸履歴の分離
- MOVE は 50 ms、軸履歴は 100 ms で間引き、ボタンや接続イベントを保護
- ジェスチャー判定の JVM 単体テスト

## 開き方

1. Android Studio の **Open** でこのフォルダを選択します。
2. Gradle Sync を完了させます。
3. Android 実機を USB 接続してアプリを実行します。
4. PS4 / PS5 コントローラを Android の Bluetooth 設定画面からペアリングします。
5. アプリを前面にした状態でコントローラを操作します。

機種、Android バージョン、コントローラ、Bluetooth / USB 接続方式によって、軸の割り当てやタッチパッドの届き方は異なります。画面に表示されたデバイス情報、全軸の値、イベント履歴を基に、実際の端末での割り当てを確定してください。

## ログの見方

- **重要イベント**: 接続・切断、ボタン、ジェスチャー結果、CANCEL など
- **ポインタ履歴**: TOUCHPAD / 外部 MOUSE の座標。MOVE は 50 ms 間隔で記録
- **モーション履歴**: スティックやトリガー。100 ms 間隔で記録
- **全軸の生値**: 最後に届いた MotionEvent に対する全公開軸の現在値

正規化に必要な X / Y の `MotionRange` を取得できない場合、生座標は表示しますがジェスチャー判定は行いません。これにより、ピクセル座標を誤って 0～1 の正規化座標として扱うことを防ぎます。

## テスト

Android Studio から `GestureClassifierTest` を実行できます。GitHub Actions では Gradle 8.9 / JDK 17 を使用し、次を実行します。

```bash
gradle testDebugUnitTest assembleDebug
```

## 未実装（次段階）

入力の端末別マッピング、デッドゾーン、正規化後の操作値、左右翼への割り当て、推進速度の保持、コントローラ有効化状態、スマートフォンから ESP32 への Wi-Fi 送信、通信切断時の安全停止は、診断結果を確定してから追加します。
