import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;

/** Dependency-free HTTP server for the installment calculator. */
public final class WebServer {
    private static final int MAX_BODY_BYTES = 4096;
    private static final Map<String, String> ASSETS = Map.of(
            "/", "index.html",
            "/app.js", "app.js",
            "/style.css", "style.css",
            "/favicon.svg", "favicon.svg");
    private static final String CSP = "default-src 'none'; style-src 'self'; script-src 'self'; "
            + "connect-src 'self'; img-src 'self' data:; base-uri 'none'; form-action 'self'; "
            + "frame-ancestors 'none'";

    private WebServer() {
    }

    public static void main(String[] args) throws IOException {
        int port = parsePort(System.getenv("PORT"));
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 32);
        server.createContext("/", WebServer::handle);
        server.setExecutor(Executors.newFixedThreadPool(8));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(1)));
        server.start();
        System.out.println("ローン返済シミュレーター: http://localhost:" + port);
    }

    private static int parsePort(String value) {
        if (value == null || value.isBlank()) {
            return 10000;
        }
        try {
            int port = Integer.parseInt(value);
            return port >= 1 && port <= 65535 ? port : 10000;
        } catch (NumberFormatException ignored) {
            return 10000;
        }
    }

    private static void handle(HttpExchange exchange) throws IOException {
        try {
            route(exchange);
        } catch (Exception exception) {
            System.err.println("Request failed: " + exception.getClass().getSimpleName());
            if (exchange.getResponseCode() == -1) {
                String path = exchange.getRequestURI().getPath();
                if (path.startsWith("/api/")) {
                    send(exchange, 500, "application/json", errorJson("計算中に問題が発生しました。もう一度お試しください。"));
                } else {
                    send(exchange, 500, "text/plain", "ページを表示できませんでした。");
                }
            }
        } finally {
            exchange.close();
        }
    }

    private static void route(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();

        if ("/api/calculate".equals(path)) {
            handleCalculation(exchange, method);
            return;
        }

        if (!"GET".equals(method) && !"HEAD".equals(method)) {
            exchange.getResponseHeaders().set("Allow", "GET, HEAD");
            send(exchange, 405, "text/plain", "GETを使用してください。");
            return;
        }

        if ("/healthz".equals(path)) {
            send(exchange, 200, "text/plain", "ok");
            return;
        }

        String asset = ASSETS.get(path);
        if (asset == null) {
            send(exchange, 404, "text/plain", "ページが見つかりません。");
            return;
        }

        Path file = Path.of("public", asset);
        if (!Files.isRegularFile(file)) {
            send(exchange, 404, "text/plain", "ページが見つかりません。");
            return;
        }

        String contentType = asset.endsWith(".css") ? "text/css"
                : asset.endsWith(".js") ? "text/javascript"
                : asset.endsWith(".svg") ? "image/svg+xml"
                : "text/html";
        send(exchange, 200, contentType, Files.readString(file, StandardCharsets.UTF_8));
    }

    private static void handleCalculation(HttpExchange exchange, String method) throws IOException {
        if (!"POST".equals(method)) {
            exchange.getResponseHeaders().set("Allow", "POST");
            send(exchange, 405, "application/json", errorJson("POSTを使用してください。"));
            return;
        }

        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null
                || !"application/x-www-form-urlencoded".equalsIgnoreCase(contentType.split(";", 2)[0].trim())) {
            send(exchange, 415, "application/json", errorJson("フォーム形式で条件を送信してください。"));
            return;
        }

        byte[] bytes = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
        if (bytes.length > MAX_BODY_BYTES) {
            send(exchange, 413, "application/json", errorJson("入力内容が大きすぎます。"));
            return;
        }

        try {
            Map<String, String> form = parseForm(new String(bytes, StandardCharsets.UTF_8));
            InstallmentCalculator.LoanTerms terms = new InstallmentCalculator.LoanTerms(
                    parsePrincipal(required(form, "principal")),
                    parseRate(required(form, "annualRate")),
                    parseMonths(required(form, "months")),
                    InstallmentCalculator.RepaymentMethod.fromApiValue(required(form, "method")),
                    parseYearMonth(required(form, "firstPaymentMonth")));
            InstallmentCalculator.Calculation calculation = InstallmentCalculator.calculate(terms);
            send(exchange, 200, "application/json", toJson(calculation));
        } catch (IllegalArgumentException exception) {
            send(exchange, 400, "application/json", errorJson(exception.getMessage()));
        }
    }

    private static Map<String, String> parseForm(String body) {
        Map<String, String> form = new LinkedHashMap<>();
        if (body.isBlank()) {
            return form;
        }
        for (String pair : body.split("&")) {
            String[] parts = pair.split("=", 2);
            String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            String value = parts.length == 2 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
            if (form.putIfAbsent(key, value) != null) {
                throw new IllegalArgumentException("同じ入力項目が複数送信されています。");
            }
        }
        return form;
    }

    private static String required(Map<String, String> form, String name) {
        String value = form.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(switch (name) {
                case "principal" -> "借入額を入力してください。";
                case "annualRate" -> "年利を入力してください。";
                case "months" -> "返済期間を入力してください。";
                case "method" -> "返済方式を選択してください。";
                case "firstPaymentMonth" -> "初回支払月を入力してください。";
                default -> "必要な入力項目がありません。";
            });
        }
        return value;
    }

    private static java.math.BigDecimal parsePrincipal(String value) {
        if (!value.matches("[0-9]{1,13}")) {
            throw new IllegalArgumentException("借入額は1円単位の整数で入力してください。");
        }
        return new java.math.BigDecimal(value);
    }

    private static java.math.BigDecimal parseRate(String value) {
        if (!value.matches("[0-9]{1,3}(\\.[0-9]{1,6})?")) {
            throw new IllegalArgumentException("年利は0〜100%の数値で入力してください。");
        }
        return new java.math.BigDecimal(value);
    }

    private static int parseMonths(String value) {
        if (!value.matches("[0-9]{1,3}")) {
            throw new IllegalArgumentException("返済回数は1〜600回の整数で入力してください。");
        }
        return Integer.parseInt(value);
    }

    private static YearMonth parseYearMonth(String value) {
        if (!value.matches("[0-9]{4}-[0-9]{2}")) {
            throw new IllegalArgumentException("初回支払月を正しく入力してください。");
        }
        try {
            return YearMonth.parse(value);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("初回支払月を正しく入力してください。", exception);
        }
    }

    private static String toJson(InstallmentCalculator.Calculation result) {
        var terms = result.terms();
        StringBuilder json = new StringBuilder(512 + result.schedule().size() * 128);
        json.append('{')
                .append("\"method\":\"").append(terms.method().apiValue()).append("\",")
                .append("\"methodLabel\":\"").append(escapeJson(terms.method().label())).append("\",")
                .append("\"principal\":").append(terms.principal().toPlainString()).append(',')
                .append("\"annualRate\":").append(terms.annualRate().toPlainString()).append(',')
                .append("\"months\":").append(terms.months()).append(',')
                .append("\"firstPaymentMonth\":\"").append(terms.firstPaymentMonth()).append("\",")
                .append("\"regularPayment\":").append(result.regularPayment().toPlainString()).append(',')
                .append("\"firstPayment\":").append(result.firstPayment().toPlainString()).append(',')
                .append("\"lastPayment\":").append(result.lastPayment().toPlainString()).append(',')
                .append("\"totalPayment\":").append(result.totalPayment().toPlainString()).append(',')
                .append("\"totalInterest\":").append(result.totalInterest().toPlainString()).append(',')
                .append("\"schedule\":[");

        for (int index = 0; index < result.schedule().size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            var row = result.schedule().get(index);
            json.append('{')
                    .append("\"number\":").append(row.number()).append(',')
                    .append("\"month\":\"").append(row.paymentMonth()).append("\",")
                    .append("\"payment\":").append(row.payment().toPlainString()).append(',')
                    .append("\"principal\":").append(row.principal().toPlainString()).append(',')
                    .append("\"interest\":").append(row.interest().toPlainString()).append(',')
                    .append("\"balance\":").append(row.balance().toPlainString())
                    .append('}');
        }
        return json.append("]}").toString();
    }

    private static String errorJson(String message) {
        return "{\"error\":\"" + escapeJson(message == null ? "入力内容を確認してください。" : message) + "\"}";
    }

    private static String escapeJson(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }

    private static void send(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        var headers = exchange.getResponseHeaders();
        headers.set("Content-Type", contentType + "; charset=utf-8");
        headers.set("Cache-Control", "no-store");
        headers.set("Content-Security-Policy", CSP);
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
        if ("HEAD".equals(exchange.getRequestMethod())) {
            headers.set("Content-Length", Integer.toString(bytes.length));
            exchange.sendResponseHeaders(status, -1);
        } else {
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }
}
