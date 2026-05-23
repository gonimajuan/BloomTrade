package co.edu.unbosque.bloomtrade.admin.service;

import org.springframework.stereotype.Service;

/**
 * Stub MVP de {@code MarketScheduleManager} (HU-F09 D11).
 *
 * <p>ARCH §1 lista 5 mercados con horarios distintos (NYSE/NASDAQ ET, LSE GMT, TSE JST,
 * ASX AEST). La validación real de horarios es responsabilidad de HU-F14 Encolar orden,
 * explícitamente diferida al post-MVP (ROADMAP §3.1).
 *
 * <p>Para MVP single-user demo asumimos "mercados siempre abiertos" — {@link #isOpenNow}
 * retorna {@code true} para cualquier ticker. La estructura de clase + interfaz pública
 * queda lista para que HU-F14 hidrate la lógica sin refactor del caller ({@code TradingService}).
 */
@Service
public class MarketScheduleManager {

    /**
     * Indica si el mercado del ticker está abierto en este momento. MVP: siempre {@code true}.
     * HU-F14 lo conectará a la configuración de horarios + zona horaria por mercado.
     *
     * @param ticker símbolo del activo (los 25 permitidos). El parámetro queda para que HU-F14
     *     pueda implementar la lógica por mercado sin cambiar la firma.
     */
    @SuppressWarnings("unused") // ticker se usará en HU-F14
    public boolean isOpenNow(String ticker) {
        // TODO: HU-F14 — resolver mercado del ticker (mapping NYSE/NASDAQ/LSE/TSE/ASX) y
        // consultar horarios vigentes en config.market_schedules (tabla a crear en V6+).
        return true;
    }
}
