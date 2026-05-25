package co.edu.unbosque.bloomtrade.integration.alpaca.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * Respuesta de {@code GET /v2/stocks/{symbol}/bars} de Alpaca Data API (HU-F18 Lote A).
 *
 * <p>Shape (single symbol endpoint anidado bajo key del ticker):
 * <pre>{@code
 * { "bars": { "AAPL": [{...bar1...}, {...bar2...}] },
 *   "next_page_token": null }
 * }</pre>
 *
 * <p>{@code nextPageToken} se ignora en V1 (limit=50 cubre el día intradía completo).
 * Si Alpaca no encuentra barras para el ticker en la ventana, retorna {@code "bars": {}}
 * (sin la key), por eso el adapter chequea {@code bars().get(ticker)} con null-safety.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AlpacaBarsResponse(
        @JsonProperty("bars") Map<String, List<AlpacaBar>> bars,
        @JsonProperty("next_page_token") String nextPageToken) {}
