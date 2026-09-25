import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Pure installment-loan calculation logic.
 *
 * <p>All displayed monetary values are whole yen. Interest for each month and
 * the regular annuity payment are rounded HALF_UP; the final payment clears the
 * remaining balance exactly.</p>
 */
public final class InstallmentCalculator {
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal TWELVE_HUNDRED = new BigDecimal("1200");
    private static final BigDecimal MAX_PRINCIPAL = new BigDecimal("1000000000000");
    private static final BigDecimal MAX_ANNUAL_RATE = new BigDecimal("100");
    private static final int MAX_MONTHS = 600;

    private InstallmentCalculator() {
    }

    public enum RepaymentMethod {
        EQUAL_PAYMENT("equal-payment", "元利均等"),
        EQUAL_PRINCIPAL("equal-principal", "元金均等");

        private final String apiValue;
        private final String label;

        RepaymentMethod(String apiValue, String label) {
            this.apiValue = apiValue;
            this.label = label;
        }

        public String apiValue() {
            return apiValue;
        }

        public String label() {
            return label;
        }

        public static RepaymentMethod fromApiValue(String value) {
            for (RepaymentMethod method : values()) {
                if (method.apiValue.equals(value)) {
                    return method;
                }
            }
            throw new IllegalArgumentException("返済方式を選択してください。");
        }
    }

    public record LoanTerms(
            BigDecimal principal,
            BigDecimal annualRate,
            int months,
            RepaymentMethod method,
            YearMonth firstPaymentMonth) {
    }

    public record PaymentRow(
            int number,
            YearMonth paymentMonth,
            BigDecimal payment,
            BigDecimal principal,
            BigDecimal interest,
            BigDecimal balance) {
    }

    public record Calculation(
            LoanTerms terms,
            BigDecimal regularPayment,
            BigDecimal firstPayment,
            BigDecimal lastPayment,
            BigDecimal totalPayment,
            BigDecimal totalInterest,
            List<PaymentRow> schedule) {

        public Calculation {
            schedule = List.copyOf(schedule);
        }
    }

    public static Calculation calculate(LoanTerms terms) {
        validate(terms);

        BigDecimal monthlyRate = terms.annualRate().divide(TWELVE_HUNDRED, MC);
        return switch (terms.method()) {
            case EQUAL_PAYMENT -> calculateEqualPayment(terms, monthlyRate);
            case EQUAL_PRINCIPAL -> calculateEqualPrincipal(terms, monthlyRate);
        };
    }

    private static Calculation calculateEqualPayment(LoanTerms terms, BigDecimal monthlyRate) {
        BigDecimal regularPayment;
        if (monthlyRate.compareTo(BigDecimal.ZERO) == 0) {
            regularPayment = terms.principal().divideToIntegralValue(BigDecimal.valueOf(terms.months()));
        } else {
            BigDecimal factor = BigDecimal.ONE.add(monthlyRate, MC).pow(terms.months(), MC);
            BigDecimal numerator = terms.principal().multiply(monthlyRate, MC).multiply(factor, MC);
            BigDecimal denominator = factor.subtract(BigDecimal.ONE, MC);
            regularPayment = money(numerator.divide(denominator, MC));

            BigDecimal firstInterest = money(terms.principal().multiply(monthlyRate, MC));
            if (regularPayment.compareTo(firstInterest) <= 0) {
                throw new IllegalArgumentException(
                        "この条件では円単位の返済額で元金が減りません。借入額・金利・返済期間を見直してください。");
            }
        }

        List<PaymentRow> rows = new ArrayList<>(terms.months());
        BigDecimal balance = terms.principal();
        BigDecimal totalPayment = BigDecimal.ZERO;
        BigDecimal totalInterest = BigDecimal.ZERO;

        for (int index = 1; index <= terms.months(); index++) {
            BigDecimal interest = money(balance.multiply(monthlyRate, MC));
            BigDecimal principalPart;
            BigDecimal payment;

            if (index == terms.months()) {
                principalPart = balance;
                payment = principalPart.add(interest);
            } else {
                payment = regularPayment;
                principalPart = payment.subtract(interest);

                if (principalPart.compareTo(BigDecimal.ZERO) < 0
                        || (monthlyRate.signum() > 0 && principalPart.compareTo(BigDecimal.ZERO) == 0)) {
                    throw new IllegalArgumentException(
                            "この条件では返済途中に元金が減りません。借入額・金利・返済期間を見直してください。");
                }
                if (principalPart.compareTo(balance) >= 0) {
                    throw new IllegalArgumentException(
                            "この条件では指定した回数より前に完済します。返済回数を短くしてください。");
                }
            }

            balance = balance.subtract(principalPart);
            if (index == terms.months()) {
                balance = BigDecimal.ZERO;
            }
            PaymentRow row = new PaymentRow(
                    index,
                    terms.firstPaymentMonth().plusMonths(index - 1L),
                    payment,
                    principalPart,
                    interest,
                    balance);
            rows.add(row);
            totalPayment = totalPayment.add(payment);
            totalInterest = totalInterest.add(interest);
        }

        return result(terms, regularPayment, totalPayment, totalInterest, rows);
    }

    private static Calculation calculateEqualPrincipal(LoanTerms terms, BigDecimal monthlyRate) {
        BigDecimal regularPrincipal = terms.principal()
                .divideToIntegralValue(BigDecimal.valueOf(terms.months()));
        List<PaymentRow> rows = new ArrayList<>(terms.months());
        BigDecimal balance = terms.principal();
        BigDecimal totalPayment = BigDecimal.ZERO;
        BigDecimal totalInterest = BigDecimal.ZERO;

        for (int index = 1; index <= terms.months(); index++) {
            BigDecimal interest = money(balance.multiply(monthlyRate, MC));
            BigDecimal principalPart = index == terms.months() ? balance : regularPrincipal;
            BigDecimal payment = principalPart.add(interest);
            balance = balance.subtract(principalPart);
            if (index == terms.months()) {
                balance = BigDecimal.ZERO;
            }

            PaymentRow row = new PaymentRow(
                    index,
                    terms.firstPaymentMonth().plusMonths(index - 1L),
                    payment,
                    principalPart,
                    interest,
                    balance);
            rows.add(row);
            totalPayment = totalPayment.add(payment);
            totalInterest = totalInterest.add(interest);
        }

        BigDecimal regularPayment = rows.getFirst().payment();
        return result(terms, regularPayment, totalPayment, totalInterest, rows);
    }

    private static Calculation result(
            LoanTerms terms,
            BigDecimal regularPayment,
            BigDecimal totalPayment,
            BigDecimal totalInterest,
            List<PaymentRow> rows) {
        return new Calculation(
                terms,
                regularPayment,
                rows.getFirst().payment(),
                rows.getLast().payment(),
                totalPayment,
                totalInterest,
                rows);
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(0, RoundingMode.HALF_UP);
    }

    private static void validate(LoanTerms terms) {
        Objects.requireNonNull(terms, "計算条件がありません。");
        Objects.requireNonNull(terms.principal(), "借入額を入力してください。");
        Objects.requireNonNull(terms.annualRate(), "年利を入力してください。");
        Objects.requireNonNull(terms.method(), "返済方式を選択してください。");
        Objects.requireNonNull(terms.firstPaymentMonth(), "初回支払月を入力してください。");

        if (terms.principal().stripTrailingZeros().scale() > 0) {
            throw new IllegalArgumentException("借入額は1円単位の整数で入力してください。");
        }
        if (terms.principal().compareTo(BigDecimal.ONE) < 0
                || terms.principal().compareTo(MAX_PRINCIPAL) > 0) {
            throw new IllegalArgumentException("借入額は1円〜1兆円の範囲で入力してください。");
        }
        if (terms.annualRate().compareTo(BigDecimal.ZERO) < 0
                || terms.annualRate().compareTo(MAX_ANNUAL_RATE) > 0) {
            throw new IllegalArgumentException("年利は0〜100%の範囲で入力してください。");
        }
        if (terms.months() < 1 || terms.months() > MAX_MONTHS) {
            throw new IllegalArgumentException("返済回数は1〜600回の範囲で入力してください。");
        }
    }
}
