package co.edu.unbosque.bloomtrade.trading.domain;

/**
 * Tipo de orden. Para MVP solo {@code MARKET}. Limit/StopLoss/TakeProfit son post-MVP
 * (Sprint 3 original — ROADMAP §3.2). El CHECK constraint en {@code app.orders}
 * también restringe a {@code MARKET}.
 */
public enum OrderType {
    MARKET
}
