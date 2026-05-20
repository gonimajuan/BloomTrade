package co.edu.unbosque.bloomtrade.config;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Habilita ejecución asíncrona. {@code notificationExecutor} respalda el {@code ThreadPool} de
 * NotificationService (ARCHITECTURE.md §4) para que el email de bienvenida sea side-effect
 * post-commit y no bloquee la respuesta del registro (spec HU-F01 §5.1 paso 14 / §5.3.6).
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "notificationExecutor")
    public Executor notificationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("notif-");
        executor.initialize();
        return executor;
    }
}
