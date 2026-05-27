package co.edu.unbosque.bloomtrade.integration.alpaca.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Respuesta de {@code GET /v2/stocks/{symbol}/bars} de Alpaca Data API (HU-F18 Lote A).
 *
 * <p>Shape del endpoint <strong>single-symbol</strong>:
 * <pre>{@code
 * { "bars": [{...bar1...}, {...bar2...}],
 *   "symbol": "AAPL",
 *   "next_page_token": null }
 * }</pre>
 *
 * <p>Bug raíz Día 10: el DTO original usaba {@code Map<String, List<AlpacaBar>>} (shape del
 * endpoint <em>multi-symbol</em> {@code /v2/stocks/bars?symbols=...}), pero el adapter llama
 * al single-symbol que retorna un array directo. La diferencia hacía que Jackson fallara
 * con {@code "Error while extracting response"} contra Alpaca real, aunque los IT pasaban
 * porque sus stubs reproducían el shape incorrecto del DTO.
 *
 * <p>{@code nextPageToken} se ignora en V1 (limit=50 + ventana 7 días cubre cualquier sparkline).
 * Si Alpaca no encuentra barras (mercado cerrado prolongado, ticker delisted o no cubierto),
 * retorna {@code "bars": []} y el adapter mapea a {@link List#of()}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AlpacaBarsResponse(
        @JsonProperty("bars") List<AlpacaBar> bars,
        @JsonProperty("next_page_token") String nextPageToken) {}
