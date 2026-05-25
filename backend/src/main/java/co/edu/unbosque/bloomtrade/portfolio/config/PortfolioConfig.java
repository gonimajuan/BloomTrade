package co.edu.unbosque.bloomtrade.portfolio.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuración del módulo Portfolio (HU-F16 Lote B — plan D1, D16).
 *
 * <p>Aísla el thread pool del fan-out a Alpaca data API del thread pool de Tomcat y del
 * {@code ForkJoinPool.commonPool()}. Threads daemon para que el shutdown del backend no
 * bloquee si {@code destroyMethod} fallara.
 */
@Configuration
public class PortfolioConfig {

    /** Cantidad de threads del pool de fan-out market data. Cubre portafolios típicos &lt;20 tickers. */
    public static final int MARKET_DATA_THREAD_POOL_SIZE = 8;

    /**
     * Pool dedicado al fan-out de {@code MarketDataAdapter.getLatestPrice} para
     * {@code GET /api/v1/portfolio/positions} (plan D1). {@code destroyMethod = "shutdown"}
     * asegura cleanup limpio en el cierre del contexto Spring (plan D16).
     */
    @Bean(name = "marketDataExecutor", destroyMethod = "shutdown")
    public ExecutorService marketDataExecutor() {
        return Executors.newFixedThreadPool(
                MARKET_DATA_THREAD_POOL_SIZE,
                runnable -> {
                    Thread thread = new Thread(runnable, "market-data-fanout");
                    thread.setDaemon(true);
                    return thread;
                });
    }
}
