# Día 0 — Plan de bootstrap del proyecto

> **Estado:** Borrador, pendiente de aprobación humana antes de ejecutar.
> **Origen:** `ROADMAP.md` §4 (Día 0) + DoD del Día 0.
> **Modalidad:** SDD asistido (Claude produce archivos, humano revisa, humano commitea).

Este documento descompone el Día 0 en subtareas ejecutables. **No se escribe ni un solo archivo de implementación hasta que este plan esté aprobado.** Cada subtarea es verificable de manera independiente.

---

## 0. Inconsistencias y decisiones — resueltas

Las tres preguntas iniciales quedaron resueltas el 2026-05-16. Se registran aquí para trazabilidad.

| # | Tema | Resolución |
|---|---|---|
| Q1 | **Paquete Java raíz** | **`co.edu.unbosque.bloomtrade`** (alineado a `STACK.md` §2.4). El humano corrigió `ARCHITECTURE.md` para reflejarlo. Se aplica en `pom.xml` (groupId), en todo el árbol de paquetes de T03, y en cualquier referencia de `application.yml`. |
| Q2 | **"8 contenedores" del DoD** | Confirmado: los 8 son los 6 de infra (postgres, redis, elasticsearch, logstash, kibana, mailhog) + `backend` + `frontend`. T10 cierra el conteo. |
| Q3 | **`README.md` ya existe** | Edición incremental. Conservar el contenido existente; agregar/actualizar la sección de setup local (prerequisitos, `cp .env.example .env`, `docker compose up`, URLs de los 8 servicios, troubleshooting de RAM de ES). Sin reescritura completa. |

Sin bloqueantes pendientes — el plan está listo para ejecutar.

---

## 1. Subtareas ordenadas

15 subtareas. La numeración no es estrictamente secuencial — el grafo de dependencias (§2) permite paralelismo limitado.

### T01 — Estructura de directorios + `.gitignore` + `.env.example`

- **Crea:** `backend/`, `frontend/`, `docs/`, `docs/prompts/`, `load-tests/`, `.github/workflows/` (vacíos o con `.gitkeep`).
- **Crea:** `.gitignore` cubriendo Java/Maven (`target/`, `*.class`), Node (`node_modules/`, `dist/`, `.vite/`), IDEs (`.idea/`, `.vscode/`, `*.iml`), env (`.env`, `.env.local`), logs.
- **Crea:** `.env.example` con placeholders: `POSTGRES_PASSWORD`, `JWT_SECRET`, `JWT_REFRESH_SECRET`, `STRIPE_API_KEY`, `STRIPE_WEBHOOK_SECRET`, `TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN`, `ALPACA_API_KEY`, `ALPACA_API_SECRET`, `POLYGON_API_KEY`.
- **Por qué:** Fundación. Bloquea todo. Regla CLAUDE.md #24 (`.env` gitignored) requiere `.gitignore` ANTES de cualquier archivo con secretos.
- **Éxito:** `git status` no propone trackear ningún archivo dentro de `target/` o `node_modules/` cuando existan; `.env.example` es legible y no contiene secretos reales.

### T02 — `docker-compose.yml` con servicios de infraestructura

- **Crea:** `docker-compose.yml` con servicios: `postgres` (16), `redis` (7), `elasticsearch` (8.x con `-Xms512m -Xmx512m` según mitigación de riesgo en ROADMAP §7), `logstash` (8.x), `kibana` (8.x), `mailhog` (1.0.x).
- **Crea:** `logstash/pipeline/logstash.conf` mínimo (input TCP 5044, output ES).
- **Sin backend ni frontend todavía.** Esto permite validar la infra aislada antes de poblar.
- **Por qué:** Aislamos el riesgo "ELK consume demasiada RAM" (ROADMAP §7) — si esto no levanta, no tiene sentido seguir.
- **Éxito:** `docker compose up -d` levanta 6 contenedores. `curl localhost:9200` → 200. Kibana en `:5601` carga. MailHog en `:8025`. Postgres acepta conexiones en `:5432`.

### T03 — Bootstrap backend (Spring Boot esqueleto)

- **Crea:** `backend/pom.xml` con deps de `STACK.md` §2.2 (starters) y §2.3 (springdoc, flyway, lombok, mapstruct, jjwt, resilience4j, logstash-logback-encoder, testcontainers, wiremock).
- **Crea:** `backend/src/main/java/<paquete-Q1>/BloomtradeApplication.java` con `@SpringBootApplication`.
- **Crea:** árbol de paquetes vacíos según `STACK.md` §2.4 (`auth/`, `trading/`, `portfolio/`, `notification/`, `dashboard/`, `admin/`, `audit/`, `integration/`, `monitoring/`, `shared/`, `config/`).
- **Crea:** `application.yml`, `application-dev.yml`, `application-test.yml` con perfiles y conexión a postgres/redis (variables `${...}` desde `.env`).
- **Por qué:** Backend compilable y arrancable, sin lógica de negocio.
- **Éxito:** `mvn -pl backend clean compile` pasa. `mvn spring-boot:run` (con perfil dev y servicios de T02 arriba) arranca sin error y `/actuator/health` → 200.

### T04 — Migración Flyway `V1__create_schemas.sql`

- **Crea:** `backend/src/main/resources/db/migration/V1__create_schemas.sql` con `CREATE SCHEMA app; CREATE SCHEMA config; CREATE SCHEMA audit;` (alineado con `STACK.md` §4.1).
- **Por qué:** Regla CLAUDE.md #12: las migraciones mergeadas son inmutables, así que la V1 debe quedar bien hecha desde el inicio. Schemas requeridos por ARCHITECTURE.md §7.
- **Éxito:** Al arrancar backend, log de Flyway muestra `Successfully applied migration V1`. `psql` confirma los 3 schemas creados.

### T05 — Spring Security + filtros JWT skeleton

- **Crea:** `config/SecurityConfig.java` (cadena de filtros, `SecurityFilterChain` bean, CORS).
- **Crea:** `config/JwtAuthenticationFilter.java` stub — extrae header `Authorization`, **deja el método de validación marcado con TODO** porque la lógica real corresponde a HU-F02 (Día 2).
- **Crea:** `auth/JwtService.java` skeleton (firmas vacías para `generateAccessToken`, `validateToken`).
- **Por qué:** ROADMAP §4 Día 0 pide "Spring Security con filtros JWT skeleton" — explícitamente skeleton, no implementación.
- **Éxito:** Backend arranca con security configurada. `/actuator/health` sigue siendo público; cualquier otra ruta devuelve 401.

### T06 — SpringDoc OpenAPI + Swagger UI

- **Modifica:** `application.yml` con `springdoc.swagger-ui.path`, info de la API.
- **Crea:** `config/OpenApiConfig.java` con `OpenAPI` bean (title, version, security scheme JWT).
- **Por qué:** Contrato API como artefacto SDD (ARCHITECTURE/CONVENTIONS).
- **Éxito:** `localhost:8080/swagger-ui.html` carga UI vacía pero funcional. `/v3/api-docs` devuelve JSON OpenAPI 3.0 válido.

### T07 — Logging JSON con `logstash-logback-encoder`

- **Crea:** `backend/src/main/resources/logback-spring.xml` con appender JSON y appender hacia logstash (TCP `logstash:5044`).
- **Por qué:** Pipeline de auditoría exigido por ARCHITECTURE §7 + ROADMAP §2.1 (toda operación emite log estructurado a ES).
- **Éxito:** Logs del backend en stdout salen en JSON. Con logstash arriba (T02), los logs aparecen indexados en ES (`curl localhost:9200/_cat/indices` muestra un índice tipo `bloomtrade-*` o `logstash-*`).

### T08 — Dockerfile backend + integración en compose

- **Crea:** `backend/Dockerfile` multi-stage (build con Maven, runtime con `eclipse-temurin:21-jre-alpine`).
- **Modifica:** `docker-compose.yml` agregando service `backend` con `depends_on: [postgres, redis, logstash]` y puerto `8080`.
- **Por qué:** Cierra el ciclo "todo en compose" exigido por DoD.
- **Éxito:** `docker compose up` arranca también el backend. `/actuator/health` desde host responde 200.

### T09 — Bootstrap frontend (Vite + React + TS)

- **Crea:** `frontend/package.json`, `frontend/vite.config.ts`, `frontend/tsconfig.json`, `frontend/tailwind.config.js`, `frontend/postcss.config.js`, dependencias de `STACK.md` §3.2.
- **Crea:** `frontend/src/main.tsx`, `frontend/src/App.tsx` con página inicial mínima ("BloomTrade — bootstrap OK") usando Tailwind.
- **Crea:** árbol vacío `pages/`, `features/`, `components/`, `hooks/`, `lib/`, `types/`, `styles/` según `STACK.md` §3.3.
- **Por qué:** Frontend compilable y servible.
- **Éxito:** `npm install && npm run dev` arranca dev server en `:5173`. La página inicial carga sin errores en consola.

### T10 — Dockerfile frontend + integración en compose

- **Crea:** `frontend/Dockerfile` (build con `node:20-alpine`, sirve con `nginx:alpine` o `vite preview` según preferencia para el MVP).
- **Modifica:** `docker-compose.yml` agregando service `frontend` con puerto `5173`.
- **Por qué:** Completa los **8 contenedores** del DoD (pendiente confirmación Q2).
- **Éxito:** `docker compose up` arranca los 8. Frontend accesible en `:5173`.

### T11 — Pipeline GitHub Actions (`ci.yml`)

- **Crea:** `.github/workflows/ci.yml` con jobs:
  - `build-backend`: setup Java 21, `mvn verify`.
  - `build-frontend`: setup Node 20, `npm ci`, `npm run build`, `npm run test`.
  - `sonar`: análisis SonarCloud (depende de T12 para credenciales).
- **Por qué:** Branch protection (T13) requiere status checks definidos por este workflow.
- **Éxito:** El workflow corre verde sobre un PR de prueba (puede ser el primero) sin el job sonar; sonar se habilita en T12.

### T12 — Configuración SonarCloud

- **Crea:** `sonar-project.properties` en raíz con keys del proyecto.
- **Modifica:** `ci.yml` activando el job sonar con `SONAR_TOKEN` (secret de GitHub).
- **Acción humana requerida:** crear proyecto en sonarcloud.io, generar `SONAR_TOKEN`, registrarlo en `Settings → Secrets` del repo.
- **Por qué:** Exigido por DoD Día 0 y por curso.
- **Éxito:** Pipeline corre el job sonar verde. Dashboard de SonarCloud muestra coverage 0% sin errores bloqueantes.

### T13 — Branch protection en `main`

- **Acción humana requerida (no edita archivos del repo):** en GitHub → Settings → Branches → Protect `main`. Activar: require PR before merging, require status checks (`build-backend`, `build-frontend`, `sonar`), require linear history.
- **Por qué:** Regla CLAUDE.md #16: nunca commits directos a `main`. ROADMAP DoD lo exige.
- **Éxito:** Intento de push directo a `main` es rechazado por el servidor.

### T14 — `README.md` actualizado para setup local

- **Modifica:** `README.md` (pendiente Q3: rewrite o solo sección setup).
- **Contenido mínimo nuevo:** prerequisitos (Docker 24+, Java 21, Node 20), `cp .env.example .env`, `docker compose up`, URLs de los 8 servicios, troubleshooting de RAM para ES.
- **Por qué:** Exigido por DoD.
- **Éxito:** Un humano que clone el repo limpio puede tener `docker compose up` corriendo en <30 min siguiendo el README.

### T15 — Commit `chore(infra): bootstrap del proyecto con stack completo` + verificación DoD

- **Acción humana (regla CLAUDE.md #18):** Yo NO commiteo. Tú haces `git add`, revisas el diff, commiteas con el mensaje exacto del ROADMAP, y agregas trailer `Co-authored-by: Claude <noreply@anthropic.com>` (regla #19).
- **Verificación DoD del Día 0** (CP-1 de ROADMAP §9):
  - [ ] `docker compose up` levanta 8 contenedores sin error.
  - [ ] Backend `/actuator/health` → 200.
  - [ ] Frontend en `:5173`.
  - [ ] Swagger UI en `:8080/swagger-ui.html`.
  - [ ] Kibana en `:5601`.
  - [ ] MailHog en `:8025`.
  - [ ] Pipeline en GitHub Actions verde.
  - [ ] SonarCloud coverage 0% sin errores.

---

## 2. Grafo de dependencias

```
T01 (estructura)
 ├── T02 (compose infra)
 │    ├── T04 (Flyway V1)     ← necesita postgres arriba para validar
 │    ├── T07 (logback JSON)  ← necesita logstash arriba para validar
 │    └── T08 (Dockerfile backend) ← agrega backend al compose
 ├── T03 (backend skeleton)
 │    ├── T04 (Flyway V1)
 │    ├── T05 (Security + JWT skeleton)
 │    ├── T06 (Swagger)
 │    ├── T07 (logback JSON)
 │    └── T08 (Dockerfile backend)
 ├── T09 (frontend skeleton)
 │    └── T10 (Dockerfile frontend)
 └── T11 (CI workflow)
      └── T12 (SonarCloud)
           └── T13 (branch protection) ← acción humana

T14 (README) depende de TODO lo anterior (documenta el estado final)
T15 (commit + verificación DoD) cierra el día
```

Paralelizable: T02 ⇄ T03 (independientes hasta T04). T09 (frontend) puede arrancar en paralelo con todo lo del backend.

---

## 3. Plan de sesiones — dónde cortar y pedir validación humana

La regla guía: **cada corte es un punto donde tú puedes verificar manualmente el progreso** antes de seguir invirtiendo tiempo. Mejor cortar de más que de menos en Día 0 — es la fundación.

### Sesión A — Estructura + infra arriba **(autocontenida, ~1.5–2h Claude)**
- T01, T02
- **Corte:** humano verifica `docker compose up` levanta los 6 servicios de infra. Si ES no arranca por RAM, parar aquí y ajustar antes de seguir.

### Sesión B — Backend esqueleto operacional **(~2–3h Claude)**
- T03, T04, T05, T06, T07
- **Corte:** humano arranca backend localmente (`mvn spring-boot:run`) contra la infra de Sesión A. Verifica:
  - `/actuator/health` → 200
  - Flyway aplica V1 sin error
  - `/swagger-ui.html` carga
  - Logs llegan a ES (visible en Kibana index pattern)
- **Por qué cortar aquí:** Si algo de Flyway/Security/Logback está mal, es mucho más fácil aislarlo sin Docker y sin frontend en medio.

### Sesión C — Containerización backend + frontend completo **(~2h Claude)**
- T08, T09, T10
- **Corte:** humano verifica los 8 contenedores arriba con `docker compose up`. Validación visual de la página inicial del frontend.

### Sesión D — CI/CD + Sonar **(~1.5h Claude + ~30min humano para acciones externas)**
- T11, T12
- **Bloqueante humano dentro de la sesión:** crear proyecto en SonarCloud, generar token, registrar secret en GitHub. Claude no puede hacer esto.
- **Corte:** humano ve el primer pipeline verde (puede ser sobre un PR de prueba o sobre `main` antes de proteger).

### Sesión E — Cierre **(<1h, casi todo humano)**
- T13 (branch protection — UI de GitHub, humano)
- T14 (README — Claude redacta, humano revisa)
- T15 (commit firmado por humano, verificación DoD completa)

---

## 4. Estimación honesta y riesgos del plan

- **Total Día 0:** ROADMAP lo presupuesta como "1 día de 6–8h". Con 5 sesiones de Claude + acciones humanas intercaladas, es realista si nada se rompe. Es **plausible que se desborde a 1.5 días** por: configuración inicial de SonarCloud (cuenta nueva), ajustes de RAM de ES, configuración de branch protection con los status checks correctos.
- **Riesgo #1 — ELK RAM:** mitigación ya planeada (T02 fija `-Xms512m -Xmx512m`). Si aún así muere, alternativa: comentar ELK del compose para Día 0 y dejar logs solo en stdout; el módulo `AuditService` lo requiere de verdad pero no hay AuditService aún en Día 0.
- **Riesgo #2 — Q1 (paquete Java):** si no decides antes de Sesión B, T03 se bloquea. Es el primer item que necesito.
- **Riesgo #3 — Sonar+CI feedback loop:** sonar a veces tarda en propagar el primer análisis. Si el job sonar falla por timing en el primer run, no es un bug del código.

---

## 5. Próximos pasos inmediatos

1. Tú respondes Q1, Q2, Q3 de la §0.
2. Si las respuestas no cambian el plan estructuralmente, apruebas y arrancamos por **Sesión A (T01 + T02)**.
3. Si alguna respuesta cambia algo, actualizo este archivo antes de tocar otros.
