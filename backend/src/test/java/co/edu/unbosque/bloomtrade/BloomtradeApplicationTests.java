package co.edu.unbosque.bloomtrade;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

// Smoke test: verifica que el Spring context arranca sin error con perfil 'test'.
// Testcontainers provee un Postgres ephemeral (ver application-test.yml). Requiere
// Docker corriendo en la máquina/runner. Cuando se sumen tests de servicio reales,
// este test sirve como guardia ante errores de configuración de beans.
@SpringBootTest
@ActiveProfiles("test")
class BloomtradeApplicationTests {

    @Test
    void contextLoads() {
        // Nada que assertar: si el context falla al cargar, el test falla solo.
    }
}
