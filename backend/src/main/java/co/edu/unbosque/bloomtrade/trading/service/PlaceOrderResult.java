package co.edu.unbosque.bloomtrade.trading.service;

import co.edu.unbosque.bloomtrade.trading.dto.OrderResponse;

/**
 * Resultado de {@code TradingService.placeOrder}. {@link #isNew} distingue ejecución fresca
 * (201 Created) de respuesta idempotente con la orden ya existente (200 OK). El controller usa
 * el flag para elegir el status HTTP sin ramificar lógica.
 */
public record PlaceOrderResult(boolean isNew, OrderResponse response) {}
