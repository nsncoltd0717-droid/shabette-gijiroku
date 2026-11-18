# しゃべって議事録

会議で話した内容を文字起こしし、Claudeで議事録（議題・決定事項・宿題・要確認事項）にまとめるアプリ。

- `web/index.html` … 画面とロジック（Web版・Android版で共通の1ファイル）
- `app/` … Android版。`web/index.html` をそのまま表示し、音声認識・ファイル保存・コピー・更新確認をAndroidの機能で行う
- `.github/workflows/android.yml` … `main` に push すると APK をビルドし、GitHub Release に `shabette-gijiroku-1.<番号>.apk` を公開する

## 使い方（Android）

1. Releases から最新の APK を端末にダウンロードしてインストール（初回は「提供元不明のアプリ」の許可が必要）
2. 「設定」で Claude APIキーを入力
3. マイクを押して話す → 「議事録を作る」 → 保存・書き出し
4. 「アプリを更新」で新しい版があればダウンロードされる

## 署名

ビルドにはリポジトリの Secrets に `KEYSTORE_BASE64`（署名鍵をbase64にしたもの）と `KEYSTORE_PASSWORD` が必要。同じ鍵で署名し続けないと上書き更新できないので、鍵は別途安全に保管する。

## Web版（iPhone・パソコン）

https://nsncoltd0717-droid.github.io/shabette-gijiroku/

`src/app.html` を編集したら `python3 scripts/build_web.py` で `web/index.html` を作り直して push する。`main` への push で gh-pages にも自動公開される。
