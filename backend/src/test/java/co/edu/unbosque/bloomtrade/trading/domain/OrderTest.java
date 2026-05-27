package co.edu.unbosque.bloomtrade.trading.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests de los métodos de dominio agregados a {@link Order} por HU-F15 Lote A T1.21:
 * {@link Order#markCancelRequested()}, {@link Order#markAsCanceled()}, {@link Order#markAsExpired()},
 * {@link Order#linkAvgBuyPriceAtSubmission(BigDecimal)}, {@link Order#isCancelable()}.
 *
 * <p>Valida invariantes de transición de estado FSM (solo desde PENDING) y guard
 * {@code linkAvgBuyPriceAtSubmission} solo en SELL.
 */
class OrderTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CLIENT_ORDER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static Order pendingBuy() {
        return Order.newPending(
                USER_ID,
                CLIENT_ORDER_ID,
                "AAPL",
                OrderSide.BUY,
                OrderType.MARKET,
                5,
                new BigDecimal("200.00"),
                new BigDecimal("20.00"),
                new BigDecimal("1020.00"));
    }

    private static Order pendingSell() {
        return Order.newPending(
                USER_ID,
                CLIENT_ORDER_ID,
                "AAPL",
                OrderSide.SELL,
                OrderType.MARKET,
                5,
                new BigDecimal("200.00"),
                new BigDecimal("20.00"),
                new BigDecimal("980.00"));
    }

    /**
     * Algunos tests necesitan transicionar la orden a un status terminal previamente para verificar
     * que las mutaciones de F15 lanzan IllegalStateException. Como no hay setter público de status,
     * usamos reflection (limitado a tests).
     */
    private static void forceStatus(Order order, OrderStatus status) throws Exception {
        Field f = Order.class.getDeclaredField("status");
        f.setAccessible(true);
        f.set(order, status);
    }

    private static void forceAlpacaOrderId(Order order, String alpacaId) throws Exception {
        Field f = Order.class.getDeclaredField("alpacaOrderId");
        f.setAccessible(true);
        f.set(order, alpacaId);
    }

    // ─── markCancelRequested ──────────────────────────────────────────────────

    @Test
    void markCancelRequested_onPending_setsTimestamp() {
        Order order = pendingBuy();
        assertThat(order.getCancelRequestedAt()).isNull();

        order.markCancelRequested();

        assertThat(order.getCancelRequestedAt()).isNotNull();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void markCancelRequested_idempotent_preservesOriginalTimestamp() {
        Order order = pendingBuy();
        order.markCancelRequested();
        var first = order.getCancelRequestedAt();

        order.markCancelRequested();

        assertThat(order.getCancelRequestedAt()).isEqualTo(first);
    }

    @Test
    void markCancelRequested_onExecuted_throwsIllegalState() throws Exception {
        Order order = pendingBuy();
        forceStatus(order, OrderStatus.EXECUTED);

        assertThatThrownBy(order::markCancelRequested)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EXECUTED");
    }

    // ─── markAsCanceled ───────────────────────────────────────────────────────

    @Test
    void markAsCanceled_onPending_setsStatusAndTimestamp() {
        Order order = pendingBuy();

        order.markAsCanceled();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELED);
        assertThat(order.getCanceledAt()).isNotNull();
    }

    @Test
    void markAsCanceled_onExecuted_throwsIllegalState() throws Exception {
        Order order = pendingBuy();
        forceStatus(order, OrderStatus.EXECUTED);

        assertThatThrownBy(order::markAsCanceled).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void markAsCanceled_onCanceled_throwsIllegalState() throws Exception {
        Order order = pendingBuy();
        forceStatus(order, OrderStatus.CANCELED);

        assertThatThrownBy(order::markAsCanceled).isInstanceOf(IllegalStateException.class);
    }

    // ─── markAsExpired ────────────────────────────────────────────────────────

    @Test
    void markAsExpired_onPending_setsStatusAndTimestamp() {
        Order order = pendingBuy();

        order.markAsExpired();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(order.getExpiredAt()).isNotNull();
    }

    @Test
    void markAsExpired_onCanceled_throwsIllegalState() throws Exception {
        Order order = pendingBuy();
        forceStatus(order, OrderStatus.CANCELED);

        assertThatThrownBy(order::markAsExpired).isInstanceOf(IllegalStateException.class);
    }

    // ─── linkAvgBuyPriceAtSubmission (D13) ────────────────────────────────────

    @Test
    void linkAvgBuyPriceAtSubmission_onSell_storesValue() {
        Order order = pendingSell();
        BigDecimal avgBuy = new BigDecimal("184.6234");

        order.linkAvgBuyPriceAtSubmission(avgBuy);

        assertThat(order.getAvgBuyPriceAtSubmission()).isEqualByComparingTo(avgBuy);
    }

    @Test
    void linkAvgBuyPriceAtSubmission_onBuy_throwsIllegalState() {
        Order order = pendingBuy();

        assertThatThrownBy(
                        () -> order.linkAvgBuyPriceAtSubmission(new BigDecimal("100.00")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SELL");
    }

    // ─── isCancelable ─────────────────────────────────────────────────────────

    @Test
    void isCancelable_pendingWithAlpacaId_returnsTrue() throws Exception {
        Order order = pendingBuy();
        forceAlpacaOrderId(order, "alp-xyz-123");

        assertThat(order.isCancelable()).isTrue();
    }

    @Test
    void isCancelable_pendingWithoutAlpacaId_returnsFalse() {
        Order order = pendingBuy();
        // alpacaOrderId NULL por default.

        assertThat(order.isCancelable()).isFalse();
    }

    @Test
    void isCancelable_executed_returnsFalse() throws Exception {
        Order order = pendingBuy();
        forceAlpacaOrderId(order, "alp-xyz-123");
        forceStatus(order, OrderStatus.EXECUTED);

        assertThat(order.isCancelable()).isFalse();
    }

    @Test
    void isCancelable_canceled_returnsFalse() throws Exception {
        Order order = pendingBuy();
        forceAlpacaOrderId(order, "alp-xyz-123");
        forceStatus(order, OrderStatus.CANCELED);

        assertThat(order.isCancelable()).isFalse();
    }

    @Test
    void isCancelable_expired_returnsFalse() throws Exception {
        Order order = pendingBuy();
        forceAlpacaOrderId(order, "alp-xyz-123");
        forceStatus(order, OrderStatus.EXPIRED);

        assertThat(order.isCancelable()).isFalse();
    }
}
