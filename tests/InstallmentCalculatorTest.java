import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.Random;

/** Small dependency-free test suite, runnable with plain java. */
public final class InstallmentCalculatorTest {
    private static final YearMonth START = YearMonth.of(2026, 10);
    private static int assertions;

    public static void main(String[] args) {
        equalPaymentKnownExample();
        anotherEqualPaymentExample();
        zeroInterestUsesFinalAdjustment();
        equalPrincipalSchedule();
        singlePayment();
        halfUpBoundary();
        validatesInputs();
        randomizedSchedulesKeepFinancialInvariants();
        System.out.println("InstallmentCalculatorTest: " + assertions + " assertions passed");
    }

    private static void equalPaymentKnownExample() {
        var result = calculate("1000000", "3", 12, InstallmentCalculator.RepaymentMethod.EQUAL_PAYMENT);
        assertMoney("84694", result.regularPayment(), "regular payment");
        assertRow(result, 0, "84694", "82194", "2500", "917806");
        assertRow(result, 1, "84694", "82399", "2295", "835407");
        assertRow(result, 10, "84694", "84272", "422", "84480");
        assertRow(result, 11, "84691", "84480", "211", "0");
        assertMoney("1016325", result.totalPayment(), "total payment");
        assertMoney("16325", result.totalInterest(), "total interest");
        assertEquals(12, result.schedule().size(), "schedule size");
        assertEquals(START.plusMonths(11), result.schedule().getLast().paymentMonth(), "last month");
        assertInvariants(result);
    }

    private static void anotherEqualPaymentExample() {
        var result = calculate("3000000", "2.5", 36, InstallmentCalculator.RepaymentMethod.EQUAL_PAYMENT);
        assertMoney("86584", result.regularPayment(), "regular payment for 3m loan");
        assertRow(result, 0, "86584", "80334", "6250", "2919666");
        assertRow(result, 34, "86584", "86224", "360", "86411");
        assertRow(result, 35, "86591", "86411", "180", "0");
        assertMoney("3117031", result.totalPayment(), "3m total payment");
        assertMoney("117031", result.totalInterest(), "3m total interest");
        assertInvariants(result);
    }

    private static void zeroInterestUsesFinalAdjustment() {
        var result = calculate("1000000", "0", 12, InstallmentCalculator.RepaymentMethod.EQUAL_PAYMENT);
        assertMoney("83333", result.regularPayment(), "zero-rate regular payment");
        assertMoney("83333", result.firstPayment(), "zero-rate first payment");
        assertMoney("83337", result.lastPayment(), "zero-rate last payment");
        assertMoney("1000000", result.totalPayment(), "zero-rate total");
        assertMoney("0", result.totalInterest(), "zero-rate interest");
        assertInvariants(result);
    }

    private static void equalPrincipalSchedule() {
        var result = calculate("120000", "12", 12, InstallmentCalculator.RepaymentMethod.EQUAL_PRINCIPAL);
        assertMoney("11200", result.firstPayment(), "equal-principal first payment");
        assertMoney("10100", result.lastPayment(), "equal-principal last payment");
        assertMoney("127800", result.totalPayment(), "equal-principal total");
        assertMoney("7800", result.totalInterest(), "equal-principal interest");
        assertRow(result, 0, "11200", "10000", "1200", "110000");
        assertRow(result, 11, "10100", "10000", "100", "0");
        assertInvariants(result);
    }

    private static void singlePayment() {
        var result = calculate("100000", "12", 1, InstallmentCalculator.RepaymentMethod.EQUAL_PAYMENT);
        assertRow(result, 0, "101000", "100000", "1000", "0");
        assertMoney("1000", result.totalInterest(), "single-payment interest");
        assertInvariants(result);
    }

    private static void halfUpBoundary() {
        var result = calculate("200", "3", 2, InstallmentCalculator.RepaymentMethod.EQUAL_PAYMENT);
        assertRow(result, 0, "100", "99", "1", "101");
        assertRow(result, 1, "101", "101", "0", "0");
        assertMoney("1", result.totalInterest(), "half-up interest");
        assertInvariants(result);
    }

    private static void validatesInputs() {
        assertThrows(() -> calculate("0", "3", 12, InstallmentCalculator.RepaymentMethod.EQUAL_PAYMENT));
        assertThrows(() -> calculate("100000", "-0.1", 12, InstallmentCalculator.RepaymentMethod.EQUAL_PAYMENT));
        assertThrows(() -> calculate("100000", "3", 0, InstallmentCalculator.RepaymentMethod.EQUAL_PAYMENT));
        assertThrows(() -> calculate("100000", "3", 601, InstallmentCalculator.RepaymentMethod.EQUAL_PAYMENT));
        assertThrows(() -> calculate("100000.5", "3", 12, InstallmentCalculator.RepaymentMethod.EQUAL_PAYMENT));
    }

    private static void randomizedSchedulesKeepFinancialInvariants() {
        Random random = new Random(20260925L);
        for (int index = 0; index < 80; index++) {
            long principal = 100_000L + random.nextLong(999_900_001L);
            BigDecimal rate = BigDecimal.valueOf(random.nextInt(2_001), 2);
            int months = 1 + random.nextInt(360);
            var method = index % 2 == 0
                    ? InstallmentCalculator.RepaymentMethod.EQUAL_PAYMENT
                    : InstallmentCalculator.RepaymentMethod.EQUAL_PRINCIPAL;
            var result = calculate(Long.toString(principal), rate.toPlainString(), months, method);
            assertEquals(months, result.schedule().size(), "random schedule size");
            assertInvariants(result);
        }
    }

    private static InstallmentCalculator.Calculation calculate(
            String principal,
            String annualRate,
            int months,
            InstallmentCalculator.RepaymentMethod method) {
        return InstallmentCalculator.calculate(new InstallmentCalculator.LoanTerms(
                new BigDecimal(principal), new BigDecimal(annualRate), months, method, START));
    }

    private static void assertRow(
            InstallmentCalculator.Calculation result,
            int index,
            String payment,
            String principal,
            String interest,
            String balance) {
        var row = result.schedule().get(index);
        assertMoney(payment, row.payment(), "row " + (index + 1) + " payment");
        assertMoney(principal, row.principal(), "row " + (index + 1) + " principal");
        assertMoney(interest, row.interest(), "row " + (index + 1) + " interest");
        assertMoney(balance, row.balance(), "row " + (index + 1) + " balance");
    }

    private static void assertInvariants(InstallmentCalculator.Calculation result) {
        BigDecimal previousBalance = result.terms().principal();
        BigDecimal principalTotal = BigDecimal.ZERO;
        BigDecimal paymentTotal = BigDecimal.ZERO;
        BigDecimal interestTotal = BigDecimal.ZERO;
        for (var row : result.schedule()) {
            assertMoney(row.payment().toPlainString(), row.principal().add(row.interest()), "payment parts");
            assertMoney(row.balance().toPlainString(), previousBalance.subtract(row.principal()), "balance movement");
            previousBalance = row.balance();
            principalTotal = principalTotal.add(row.principal());
            paymentTotal = paymentTotal.add(row.payment());
            interestTotal = interestTotal.add(row.interest());
        }
        assertMoney(result.terms().principal().toPlainString(), principalTotal, "principal total");
        assertMoney("0", previousBalance, "final balance");
        assertMoney(result.totalPayment().toPlainString(), paymentTotal, "payment total invariant");
        assertMoney(result.totalInterest().toPlainString(), interestTotal, "interest total invariant");
        assertMoney(result.totalInterest().toPlainString(), result.totalPayment().subtract(result.terms().principal()),
                "total payment minus principal");
    }

    private static void assertMoney(String expected, BigDecimal actual, String label) {
        assertions++;
        if (new BigDecimal(expected).compareTo(actual) != 0) {
            throw new AssertionError(label + ": expected " + expected + " but was " + actual);
        }
    }

    private static void assertEquals(Object expected, Object actual, String label) {
        assertions++;
        if (!expected.equals(actual)) {
            throw new AssertionError(label + ": expected " + expected + " but was " + actual);
        }
    }

    private static void assertThrows(Runnable runnable) {
        assertions++;
        try {
            runnable.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("Expected IllegalArgumentException");
    }
}
