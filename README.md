# ローン返済シミュレーター

銀行ローンなどの分割払いを概算する、依存ライブラリ不要のJava製Webアプリです。借入額・年利・返済期間を入力すると、Java側が `BigDecimal` で計算し、返済総額、利息総額、月ごとの返済予定表を表示します。

## 主な機能

- 元利均等返済と元金均等返済を切り替え
- 年利0%および1〜600回（最長50年）の返済に対応
- 初回から完済までの返済予定表
- 元金と利息の割合を表示
- 返済予定表をCSVで保存
- 入力内容をサーバーに保存しない設計
- PC・スマートフォン対応、日本語の入力エラー表示

## 起動方法

JDK 21以降を用意し、このフォルダで次を実行します。

```bash
mkdir -p out
javac --release 21 --add-modules jdk.httpserver -encoding UTF-8 -d out src/*.java
java --add-modules jdk.httpserver -cp out WebServer
```

ブラウザで [http://localhost:10000](http://localhost:10000) を開きます。待受ポートは `PORT` 環境変数で変更できます。

## テスト

計算ロジックのテスト:

```bash
mkdir -p out
javac --release 21 --add-modules jdk.httpserver -encoding UTF-8 -d out src/*.java tests/InstallmentCalculatorTest.java
java -cp out InstallmentCalculatorTest
```

サーバーを起動した状態でHTTPテスト:

```bash
python3 tests/http_test.py
```

## 計算方法

### 元利均等返済

月利を `年利 ÷ 12 ÷ 100` とし、通常の返済額を次の式で求めます。

```text
返済額 = 借入額 × 月利 × (1 + 月利)^返済回数 ÷ ((1 + 月利)^返済回数 - 1)
```

年利0%の場合は、借入額を返済回数で均等に分けます。

### 元金均等返済

借入元金を返済回数で均等に分け、毎月の返済前残高に対する利息を加算します。

### 端数処理

- 毎月の利息と元利均等の通常返済額は1円単位で四捨五入します。
- 端数は最終回の返済額で調整し、返済後残高を0円にします。
- 計算途中は `BigDecimal` の10進演算（DECIMAL128）を使用します。

## 対象外の条件

手数料、保証料、日割り利息、ボーナス返済、繰上返済、変動金利の将来変化は計算に含みません。実際の返済額や端数処理は金融機関によって異なるため、結果は目安として利用してください。

## Docker / Render

```bash
docker build -t bank-installment-calculator .
docker run --rm -p 10000:10000 bank-installment-calculator
```

`render.yaml` も同梱しています。Renderでリポジトリ内のこのフォルダをRoot Directoryに指定すると、そのままデプロイできます。
