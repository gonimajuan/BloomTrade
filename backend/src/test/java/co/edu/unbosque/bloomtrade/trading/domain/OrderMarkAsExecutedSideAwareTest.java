package co.edu.unbosque.bloomtrade.trading.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit test del comportamiento side-aware de {@link Order#markAsExecuted} (HU-F10 T2.5).
 *
 * <p>Verifica la regla:
 * <ul>
 *   <li><b>BUY</b>: {@code executionTotal = subtotal + quotedCommission} — cobrado real.</li>
 *   <li><b>SELL</b>: {@code executionTotal = subtotal − quotedCommission} — producto neto acreditado.</li>
 * </ul>
 * Sin Spring, sin BD — solo invariante de dominio.
 */
class OrderMarkAsExecutedSideAwareTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID CLIENT_ORDER_ID = UUID.randomUUID();

    @Test
    void markAsExecuted_buy_addsCommissionToTotal() {
        Order order =
                Order.newPending(
                        USER_ID,
                        CLIENT_ORDER_ID,
                        "AAPL",
                        OrderSide.BUY,
                        OrderType.MARKET,
                        10,
                        new BigDecimal("184.5000"),
                        new BigDecimal("36.90"),
                        new BigDecimal("1881.90"));

        order.markAsExecuted("alp-buy-1", new BigDecimal("184.6200"));

        // BUY: 10 × 184.6200 + 36.90 = 1846.20 + 36.90 = 1883.10
        assertThat(order.getExecutionTotal()).isEqualByComparingTo("1883.10");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.EXECUTED);
        assertThat(order.getExecutionUnitPrice()).isEqualByComparingTo("184.6200");
        assertThat(order.getAlpacaOrderId()).isEqualTo("alp-buy-1");
    }

    @Test
    void markAsExecuted_sell_subtractsCommissionFromTotal() {
        Order order =
                Order.newPending(
                        USER_ID,
                        CLIENT_ORDER_ID,
                        "AAPL",
                        OrderSide.SELL,
                        OrderType.MARKET,
                        5,
                        new BigDecimal("190.0000"),
                        new BigDecimal("19.00"),
                        new BigDecimal("931.00"));

        order.markAsExecuted("alp-sell-1", new BigDecimal("189.9500"));

        // SELL: 5 × 189.9500 − 19.00 = 949.75 − 19.00 = 930.75
        assertThat(order.getExecutionTotal()).isEqualByComparingTo("930.75");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.EXECUTED);
    }

    @Test
    void markAsExecuted_sellWithDifferentExecutionPrice_recomputesNetProceedsCorrectly() {
        // Demuestra que SELL honra el quoted_commission del INSERT, no recalcula sobre execPrice
        // (D4 F09: el quote es la base contractual; sin slippage tolerance en MVP).
        Order order =
                Order.newPending(
                        USER_ID,
                        CLIENT_ORDER_ID,
                        "MSFT",
                        OrderSide.SELL,
                        OrderType.MARKET,
                        3,
                        new BigDecimal("420.0000"),
                        new BigDecimal("25.20"), // comisión quoted (2% de 1260)
                        new BigDecimal("1234.80"));

        order.markAsExecuted("alp-sell-2", new BigDecimal("421.5000"));

        // SELL: 3 × 421.50 − 25.20 = 1264.50 − 25.20 = 1239.30 (usa quotedCommission, no recalcula).
        assertThat(order.getExecutionTotal()).isEqualByComparingTo("1239.30");
    }
}
