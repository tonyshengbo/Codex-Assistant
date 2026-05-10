# Aura Code

[English](README.md) | [中文](README.zh.md) | [日本語](README.ja.md) | [한국어](README.ko.md)

Aura Code は、Codex と Claude を 1 つのネイティブ IDE ワークフローへまとめる IntelliJ IDEA プラグインです。マルチセッション会話、計画、承認、ファイル文脈付きの実行、ランタイム管理、ローカルツール制御を 1 つの作業面に集約し、ターミナル、ブラウザ、IDE を行き来する負担を減らします。

![Aura Code Preview](docs/img.png)
![Aura Code Preview](docs/img_1.png)

## 製品ポジション

Aura Code は IntelliJ IDEA 向けのデュアルエンジン AI アシスタントです。

- 同じツールウィンドウから Codex と Claude を利用可能
- プロジェクト単位のセッション、履歴、編集ファイルを IDE 内に保持
- ローカル CLI ワークフローの制御性を保ったまま、承認、文脈制御、Diff レビューを統合
- ランタイム、Skills、MCP サーバー、Token 使用状況を同じ画面で管理

## ベータ配布

`1.0.0-beta.4` は現在 GitHub prerelease ZIP と手動アップロードされた Marketplace ビルドとして配布されています。

- GitHub Release の ZIP を利用するか、`./gradlew buildPlugin` でローカルビルド
- `Settings -> Plugins -> Install Plugin from Disk...` からインストール
- 現在のリポジトリは Marketplace 公開を自動化していないため、必要に応じて生成 ZIP を手動アップロードしてください

## 主な機能

- ネイティブ `Aura Code` ツールウィンドウ内で Codex と Claude のセッションを統合
- ローカル保存、リモート再開、履歴エクスポートに対応したプロジェクト単位のマルチタブ会話
- ストリーミング応答、バックグラウンド実行の把握、完了通知
- `@` ファイル参照、ファイル / 画像添付、`#` 保存済み Agent、`/plan`、`/auto`、`/init`、`/new`、`/tab` などのスラッシュコマンド
- 会話フローに組み込まれた Plan モード、承認プロンプト、ツール入力、実行中プランのフィードバック
- 変更ファイル集約と Diff 表示、オープン、反映、巻き戻し
- Codex CLI、Claude CLI、必要時の Node を個別に管理できるランタイム設定
- CLI バージョン表示、更新確認、アップグレード導線
- ローカル Skills の検出、インポート、有効化 / 無効化、Slash 公開、アンインストール
- `stdio` と streamable HTTP を扱う MCP サーバー管理
- エンジン、期間、モデルごとの履歴 Token 使用状況
- IntelliJ Problems の `Ask Aura` から直接渡せるビルドエラー解析
- 中国語、英語、日本語、韓国語 UI とテーマ / UI スケール設定

## アーキテクチャ概要

現在のプラグインは、単一ランタイムの橋渡しではなく、デュアルエンジンのセッションパイプラインを中心に構成されています。

- `provider/codex`、`provider/claude`、`provider/runtime` がエンジン起動、プロトコル解析、バージョン確認、環境解決を担当
- `session/kernel`、`session/normalizer`、`session/projection` が Provider イベントを安定したセッション状態と UI 投影へ変換
- `persistence/chat` が SQLite ベースのプロジェクトローカルな会話履歴と Token ledger を保持
- `toolwindow/submission`、`toolwindow/conversation`、`toolwindow/execution`、`toolwindow/sessions`、`toolwindow/history`、`toolwindow/settings` が Compose ベースのネイティブ UI を構成
- `settings/skills` と `settings/mcp` がローカル Skills と MCP サーバー設定を管理
- `integration/build` と `integration/ide` がビルドエラー連携や IDE コンテキスト連携を提供

## 要件

- IntelliJ IDEA を実行できる macOS / Linux / Windows
- JDK 17
- プラグイン `sinceBuild = 233` に対応する IntelliJ IDEA
- `PATH` 上、または `Settings -> Aura Code -> Runtime` に設定された `codex` と / または `claude`
- 選択した Codex ランタイムフローが必要とする場合の `node`

## ローカルインストール

1. プラグイン ZIP をビルドします。

```bash
./gradlew buildPlugin
```

2. 生成物は `build/distributions/` にあります。
3. IntelliJ IDEA で `Settings -> Plugins -> Install Plugin from Disk...` を開きます。
4. 生成された ZIP を選択します。
5. `Settings -> Aura Code -> Runtime` を開き、Codex CLI、Claude CLI、必要に応じて Node のパスを確認します。

## 開発実行

プラグインを読み込んだサンドボックス IDE を起動します。

```bash
./gradlew runIde
```

よく使うコマンド:

```bash
./gradlew test
./gradlew buildPlugin
./gradlew verifyPlugin
```

## 主要ワークフロー

### セッションとエンジン

- プラグイン内で Codex と Claude を切り替えつつ、プロジェクト単位のセッション状態を保持
- 複数タブを開き、フォーカスを移してもバックグラウンド実行を継続
- アクティブなエンジンが対応する場合、ローカル履歴とリモート会話 ID から作業を再開

### 計画と実行

- コンポーザーから `Plan`、`Auto`、承認中心のフローを直接利用
- 実行中プラン、プラン修正プロンプト、構造化されたツール入力を同じタイムライン上で確認
- 生の CLI 出力へ戻らずに、IDE 内で実行判断を完結

### コンテキスト、ファイル、履歴

- アクティブなエディタファイルや選択テキストを自動追従
- より細かい制御が必要な場合は、手動ファイル文脈、添付、保存済み Agent を追加
- 変更ファイル、Diff、メッセージコピー、Markdown エクスポートを 1 つのワークフローで扱う

### ランタイム、Skills、MCP

- Runtime 設定ページで Codex CLI と Claude CLI を個別に管理
- バージョン状態、更新確認、対応するインストール元でのアップグレードを確認
- IntelliJ IDEA を離れずにローカル Skills と MCP サーバーを管理
- エンジン、期間、モデルごとの履歴 Token 使用状況を確認

## プロジェクト構造

```text
src/main/kotlin/com/auracode/assistant/
  actions/            クイックオープンやビルドエラー引き渡しなどの IntelliJ エントリポイント
  provider/           Codex、Claude、runtime、および provider-session 統合
  session/            セッションカーネル、イベント正規化、UI 投影レイヤー
  persistence/chat/   SQLite ベースの会話履歴と Token 使用状況の保存
  toolwindow/         入力、会話、実行、履歴、セッションタブ、設定の Compose UI
  settings/           永続化設定と Skills / MCP サポート
  integration/        ビルドエラーと IDE コンテキスト連携
  protocol/           共通 Provider プロトコルモデル
src/test/kotlin/com/auracode/assistant/
  ...                 Provider、サービス、Store、ワークフロー挙動のテスト
```

## デバッグのヒント

ランタイムを起動できない場合:

- `codex` と / または `claude` が実行可能か確認
- 現在の Codex フローで `node` を使う場合は、その実行可否も確認
- `Settings -> Aura Code -> Runtime` で実行パス設定を確認
- `Help -> Show Log in Finder/Explorer` から IDE ログを確認

履歴や再開が正しく見えない場合:

- 現在のランタイムがプラグイン外で認証済みか確認
- 同じエンジンでセッションを再開しているか確認
- リモート履歴読み込みとローカル永続化を切り分けて確認

## オープンソース状況

- 現在のリポジトリは IntelliJ IDEA サポートに注力しています
- GitHub prerelease ZIP 配布とローカル ZIP インストールに対応しています
- Marketplace 提供は現在も生成 ZIP の手動アップロード前提で、リポジトリ内の自動公開フローには接続されていません

## ライセンス

Aura Code は Apache License 2.0 の下で公開されています。完全なライセンス本文はプロジェクトルートの `LICENSE` ファイルを参照してください。
