# Pruebas de Carga — BloomTrade

Planes JMeter para los escenarios de calidad de **Rendimiento** definidos en `ARCHITECTURE.md` §13:

| ID | Escenario | Medida (SLO) | Plan |
|---|---|---|---|
| **ESC-R1** | 1500 órdenes simultáneas en apertura NYSE | Cada orden confirmada en <5s | `escenario-r1-orders.jmx` |
| **ESC-R2** | 1500 usuarios cargan dashboard simultáneamente | Dashboard carga en <2s | `escenario-r2-dashboard.jmx` |

> **Estado:** planes generados como esqueleto base. **No ejecutados aún** — pendiente del Día 10 según `ROADMAP.md` §3.

---

## 1. Pre-requisitos

| Requisito | Versión | Verificar |
|---|---|---|
| Apache JMeter | 5.6.x | `jmeter --version` |
| Java JRE | 17+ | `java --version` |
| BloomTrade backend | corriendo en `http://localhost:8080` | `curl http://localhost:8080/actuator/health` |
| MailHog | corriendo en `http://localhost:8025` | navegar UI |
| PostgreSQL | con seed de usuarios de loadtest | ver §3 |

> **Nota:** PostgreSQL nativo en :5432 (no contenedor) según el setup del proyecto.

### Instalación JMeter (Windows)

```powershell
# Opción 1 — chocolatey
choco install jmeter

# Opción 2 — manual
# Descargar https://jmeter.apache.org/download_jmeter.cgi
# Extraer a C:\apache-jmeter-5.6.3 y agregar bin/ al PATH
```

---

## 2. Estructura

```
load-tests/
├── README.md                       ← este archivo
├── users.csv.example               ← formato del CSV de usuarios + tokens
├── tickers.csv                     ← los 25 tickers válidos para randomizar órdenes
├── escenario-r1-orders.jmx         ← Plan ESC-R1
├── escenario-r2-dashboard.jmx      ← Plan ESC-R2
├── scripts/
│   └── prepare-tokens.ps1          ← Autogenera users.csv leyendo OTPs de MailHog
└── results/                        ← .jtl + reporte HTML (gitignored)
```

> `users.csv` y `results/` están gitignored — no se commitean tokens reales ni reportes pesados.

---

## 3. Setup de usuarios seed

Para que ESC-R1 no agote saldos prematuramente, **cada hilo idealmente tiene su propio usuario** con balance inicial de USD 10,000 (default del `BalanceInitializer`).

Hay tres opciones para producir el `users.csv`:

### Opción A — 1500 usuarios seed reales (recomendada para informe final)

1. Crear migración Flyway de seed `V900__seed_loadtest_users.sql` (solo en perfil `load-test`, gateada — sin tocar prod).
2. Cada usuario con email `loadtest+0001@bloomtrade.local` … `loadtest+1500@bloomtrade.local`, password BCrypt(`Loadtest!2026`), `estado=ACTIVE`.
3. Correr `scripts/prepare-tokens.ps1` que itera login → MFA → guarda token en `users.csv`.

Tiempo estimado de preparación: ~5 min para los 1500 logins (limitado por SMTP cooldown y MFA TTL).

### Opción B — Pocos usuarios reutilizando tokens (rápido, menos realista)

1. Crear manualmente 5–10 usuarios desde la UI de registro o vía `curl POST /api/v1/auth/register`.
2. Correr `scripts/prepare-tokens.ps1 -UserCount 10`.
3. Los .jmx leen `users.csv` en modo **cyclic** (`recycle on EOF = true`) — un mismo token se reusa por múltiples hilos.

**Trade-off:** mide throughput de la API pero **no** valida aislamiento de usuario ni concurrencia sobre balance distinto. Los 4xx por "saldo insuficiente" tras N órdenes contra el mismo usuario son esperados y deben filtrarse del análisis. Documentar en `docs/load-tests/results.md`.

### Opción C — Token único de larga duración (degradada, para smoke test)

Usar el JWT de un único usuario administrativo con TTL elevado (config local `jwt.access-token.ttl=PT8H`). Solo para validar que el .jmx corre.

---

## 4. Generación de `users.csv`

```powershell
# Desde load-tests\
.\scripts\prepare-tokens.ps1 `
  -BackendUrl http://localhost:8080 `
  -MailhogUrl http://localhost:8025 `
  -EmailPrefix loadtest `
  -Password "Loadtest!2026" `
  -UserCount 1500 `
  -OutputCsv .\users.csv
```

Salida esperada (`users.csv`):

```csv
userId,email,accessToken
4f7a...,loadtest+0001@bloomtrade.local,eyJhbGciOiJIUzI1NiJ9...
...
```

> Si el script falla por OTP expirado, reducir `-UserCount` o aumentar paralelismo del script (que reusa la sesión temporal en <5min). Ver troubleshooting §7.

---

## 5. Ejecución

### Modo GUI (solo para debug, NO para mediciones reales)

```powershell
jmeter -t escenario-r1-orders.jmx
```

> JMeter en GUI consume mucho heap y distorsiona la medición. Solo usar para validar el plan, **nunca** para tomar resultados oficiales.

### Modo CLI (recomendado para mediciones)

```powershell
# ESC-R1 — órdenes
jmeter -n -t escenario-r1-orders.jmx `
       -l results\esc-r1.jtl `
       -e -o results\esc-r1-html `
       -Jbackend.host=localhost `
       -Jbackend.port=8080 `
       -Jusers.csv=users.csv `
       -Jtickers.csv=tickers.csv `
       -Jthreads=1500 `
       -Jrampup=1

# ESC-R2 — dashboard
jmeter -n -t escenario-r2-dashboard.jmx `
       -l results\esc-r2.jtl `
       -e -o results\esc-r2-html `
       -Jbackend.host=localhost `
       -Jbackend.port=8080 `
       -Jusers.csv=users.csv `
       -Jthreads=1500 `
       -Jrampup=1
```

Parámetros `-J*` overrides:
- `backend.host`, `backend.port` — destino del backend.
- `threads` — número de hilos concurrentes (1500 = SLO oficial; bajar a 100–500 para smoke).
- `rampup` — segundos para alcanzar los `threads` (1 = pico simultáneo).
- `users.csv`, `tickers.csv` — paths relativos al working dir.

### Interpretación de resultados

Abrir `results/esc-r1-html/index.html` (reporte autogenerado). Métricas clave:

| Métrica | ESC-R1 SLO | ESC-R2 SLO |
|---|---|---|
| **p95 response time** | < 5000 ms | < 2000 ms |
| **Error %** | 0% (excepto 409 "saldo insuficiente" si Opción B) | 0% |
| **Throughput** | ≥ 300 req/s sostenido | ≥ 750 req/s sostenido |

> El SLO oficial mide **cada orden** individual. Si el p95 baja del threshold pero el max está por encima, documentar la cola del histograma en `results.md`.

---

## 6. Documentación de resultados

Tras cada ejecución, escribir en `docs/load-tests/results.md`:

- Fecha y hora de la corrida.
- Hardware del host (CPU, RAM, SSD).
- Configuración del backend (heap, perfil, índices DB).
- Opción de usuarios usada (A/B/C).
- Capturas/exportes de las gráficas clave del reporte HTML.
- Veredicto: ¿se cumple el SLO? Si no, hipótesis del bottleneck (DB, JVM, red, Alpaca, Redis).

Este archivo es entregable del informe final.

---

## 7. Troubleshooting

| Síntoma | Causa probable | Acción |
|---|---|---|
| `prepare-tokens.ps1` falla con `OTP expirado` | TTL 5 min agotado antes de leer todos los emails | Bajar `-UserCount` o paralelizar el script |
| Mucho 401 en `users.csv` recién generado | JWT expiró durante la corrida | Reducir `expiresIn=900` en `application.yml` no aplica para load — ejecutar `.jmx` dentro de los 15 min de generado el CSV |
| 409 `INSUFFICIENT_FUNDS` masivos | Opción B con pocos usuarios — saldos agotados | Documentar en `results.md` y filtrar del análisis, o cambiar a Opción A |
| Backend devuelve 502 `ALPACA_UNAVAILABLE` | Rate limit de Alpaca paper (200 req/min en free tier) | Esperar a que se reseteé el cuota, o stubear Alpaca con WireMock para load test puro de stack interno |
| JMeter consume 8 GB heap y muere | Resultados acumulados en memoria | Quitar `View Results Tree` del plan, usar solo `Simple Data Writer` a JTL |
| p95 cumple SLO pero el max está en 12s | GC pause o cold cache en arranque | Hacer un "warm-up run" de 100 hilos antes y descartar; medir solo la corrida sostenida |

---

## 8. Referencias

- `ARCHITECTURE.md` §13 — escenarios de calidad ESC-R1, ESC-R2.
- `ROADMAP.md` §3 Día 10 — plan de ejecución.
- `STACK.md` §1 — JMeter 5.x es la herramienta oficial.
- [JMeter User Manual](https://jmeter.apache.org/usermanual/index.html).
- [MailHog API v2](https://github.com/mailhog/MailHog/blob/master/docs/APIv2.md) — endpoint que usa `prepare-tokens.ps1`.
