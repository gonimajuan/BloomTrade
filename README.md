# Acciones ElBosque

**Plataforma de Day Trading | Ingeniería de Software 2 | Universidad El Bosque | 2026**

Sistema web que permite operar acciones en cinco mercados internacionales (NYSE, NASDAQ, LSE, TSE, ASX) con autenticación multifactor, gestión de portafolio, comisionistas asignados y trazabilidad completa de operaciones. Diseñado bajo arquitectura orientada a servicios con tácticas de Bass para rendimiento, disponibilidad, seguridad, modificabilidad e interoperabilidad.

> **Estado:** En desarrollo activo del MVP. No apto para uso productivo.

---

## Documentación del proyecto

Antes de tocar el código, leer en este orden:

1. [`ARCHITECTURE.md`](./ARCHITECTURE.md) — qué se construye (servicios, componentes, tácticas)
2. [`STACK.md`](./STACK.md) — con qué se construye (versiones, librerías, decisiones técnicas)
3. [`CONVENTIONS.md`](./CONVENTIONS.md) — cómo se construye (Git, tests, estilo de código, uso de Claude Code)
4. [`ROADMAP.md`](./ROADMAP.md) — cuándo se construye (sprints, alcance del MVP, recortes documentados)

Este `README.md` cubre únicamente **cómo levantar el proyecto en local**.

---

## Prerrequisitos

### Obligatorios

| Herramienta | Versión mínima | Verificar con |
|---|---|---|
| Docker Desktop (o Docker Engine + Compose v2) | Docker 24+, Compose v2 | `docker --version && docker compose version` |
| Git | 2.30+ | `git --version` |
| RAM disponible para Docker | **8 GB mínimo, 16 GB recomendado** | — |
| Espacio en disco | 10 GB libres | — |

> ⚠️ **El stack ELK (ElasticSearch + Logstash + Kibana) consume aproximadamente 4 GB de RAM.** Si tu máquina tiene 8 GB totales, vas a querer cerrar otras aplicaciones antes de levantar el stack. Si tiene 16 GB o más, no deberías sentirlo.

### Opcionales (solo para desarrollo fuera de contenedor)

Útiles si quieres iterar el backend o frontend con hot reload sin reconstruir contenedores:

| Herramienta | Versión |
|---|---|
| Java JDK | 21 LTS |
| Maven | 3.9+ |
| Node.js | 20 LTS |
| npm | 10+ |

---

## Setup inicial

### 1. Clonar el repositorio

```bash
git clone https://github.com/<org-o-usuario>/acciones-elbosque.git
cd acciones-elbosque
```

### 2. Configurar variables de entorno

Copiar el template y llenar con credenciales propias:

```bash
cp .env.example .env
```

Editar `.env` con un editor de texto. La sección [Servicios externos](#servicios-externos-y-credenciales) explica cómo obtener cada credencial.

> ⚠️ **`.env` está en `.gitignore` y nunca debe commitearse.** El archivo `.env.example` es seguro y sí va versionado, pero no contiene secretos reales — solo placeholders.

### 3. Verificar que Docker está corriendo

```bash
docker info
```

Si esto falla, abrir Docker Desktop o iniciar el daemon de Docker.

---

## Arrancar el sistema

### Modo completo — todo en Docker

```bash
docker compose up -d
```

La primera vez tomará varios minutos descargando imágenes y construyendo la app. Las siguientes ejecuciones son mucho más rápidas (~30 segundos).

Verificar que todos los contenedores arrancaron:

```bash
docker compose ps
```

Todos deben aparecer en estado `running` o `healthy`. Si alguno está en `unhealthy` o `exited`, ver la sección [Solución de problemas](#solución-de-problemas).

### Modo desarrollo — backend o frontend fuera de Docker

Útil cuando se está iterando rápido con hot reload. Levantar solo la infraestructura en Docker y correr la app localmente:

```bash
# Levantar solo bases de datos, caché, ELK, MailHog
docker compose up -d postgres redis elasticsearch logstash kibana mailhog

# En una terminal: backend con hot reload
cd backend
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev

# En otra terminal: frontend con HMR
cd frontend
npm install
npm run dev
```

---

## Servicios y puertos

Una vez levantado el stack completo, los servicios quedan accesibles en:

| Servicio | URL | Para qué |
|---|---|---|
| Frontend (React) | http://localhost:5173 | Aplicación web del usuario |
| Backend API | http://localhost:8080/api/v1 | Endpoints REST |
| Swagger UI | http://localhost:8080/swagger-ui.html | Documentación interactiva de la API |
| OpenAPI JSON | http://localhost:8080/v3/api-docs | Spec OpenAPI cruda (para generar tipos TS) |
| Actuator Health | http://localhost:8080/actuator/health | Estado del backend |
| Kibana | http://localhost:5601 | Búsqueda y exploración de logs de auditoría |
| MailHog UI | http://localhost:8025 | Inbox de correos enviados (códigos MFA, confirmaciones) |
| PostgreSQL | localhost:5432 | Conexión directa con cliente SQL si se necesita |
| Redis | localhost:6379 | Conexión directa con `redis-cli` si se necesita |

---

## Servicios externos y credenciales

El sistema integra cuatro servicios externos. Para correr el MVP localmente se necesitan credenciales de **modo prueba / sandbox** de cada uno (todas gratuitas).

### Alpaca Markets — ejecución de órdenes

1. Crear cuenta en https://alpaca.markets
2. Ir a **Paper Trading** (no Live Trading — el MVP solo usa el sandbox)
3. Generar API Key y API Secret
4. Llenar en `.env`:
   ```
   ALPACA_API_KEY=PK...
   ALPACA_API_SECRET=...
   ALPACA_BASE_URL=https://paper-api.alpaca.markets
   ```

### Polygon.io — datos de mercado

1. Crear cuenta en https://polygon.io
2. En el dashboard, copiar la API Key del plan **Basic (free)**
3. Llenar en `.env`:
   ```
   POLYGON_API_KEY=...
   POLYGON_BASE_URL=https://api.polygon.io
   ```

> ⚠️ El tier gratuito tiene límite de 5 requests/min y datos end-of-day. Para el MVP local es suficiente: el `PriceCache` en Redis amortigua y la demo no requiere precios real-time.

### Stripe — suscripción premium

1. Crear cuenta en https://dashboard.stripe.com
2. Confirmar que estás en **Test mode** (toggle arriba a la derecha)
3. En **Developers > API keys**, copiar la Secret Key (`sk_test_...`)
4. Crear dos productos en **Products**:
   - `Acciones ElBosque Premium - Mensual` con precio recurrente USD $12/mes
   - `Acciones ElBosque Premium - Anual` con precio recurrente USD $120/año
5. Copiar los IDs de cada Price (`price_...`)
6. Para webhooks locales, instalar [Stripe CLI](https://stripe.com/docs/stripe-cli) y ejecutar:
   ```bash
   stripe listen --forward-to localhost:8080/api/v1/webhooks/stripe
   ```
   Esto imprime un `whsec_...` que va en `STRIPE_WEBHOOK_SECRET`
7. Llenar en `.env`:
   ```
   STRIPE_SECRET_KEY=sk_test_...
   STRIPE_PRICE_MONTHLY=price_...
   STRIPE_PRICE_YEARLY=price_...
   STRIPE_WEBHOOK_SECRET=whsec_...
   ```

Para probar pagos usar las [tarjetas de prueba oficiales](https://stripe.com/docs/testing#cards): `4242 4242 4242 4242` con cualquier fecha futura y cualquier CVC.

### Twilio — SMS y WhatsApp

1. Crear cuenta en https://www.twilio.com
2. En el Console, copiar **Account SID** y **Auth Token** (modo Test/Trial)
3. Solicitar un número de teléfono de prueba (gratis con créditos de trial)
4. Para WhatsApp, activar el **WhatsApp Sandbox** en **Messaging > Try it out**
5. Llenar en `.env`:
   ```
   TWILIO_ACCOUNT_SID=AC...
   TWILIO_AUTH_TOKEN=...
   TWILIO_FROM_PHONE=+1...
   TWILIO_FROM_WHATSAPP=whatsapp:+14155238886
   ```

> ℹ️ Para el MVP la mayoría de notificaciones van por email vía MailHog. Twilio solo se ejercita si configuras un usuario con preferencia de canal SMS o WhatsApp.

### Email — MailHog (default) o SendGrid (opcional)

**MailHog** corre como contenedor en el `docker-compose.yml`, no requiere configuración. Todas las notificaciones por email caen en http://localhost:8025.

**SendGrid** es opcional para "demo en producción". Si quieres usarlo:
```
EMAIL_PROVIDER=sendgrid
SENDGRID_API_KEY=SG...
SENDGRID_FROM_EMAIL=noreply@tudominio.com
```

Si no, dejar `EMAIL_PROVIDER=mailhog` y todo se queda local.

---

## Comandos útiles

### Docker Compose

```bash
# Ver logs de todos los servicios en vivo
docker compose logs -f

# Ver logs de un servicio específico
docker compose logs -f backend

# Reiniciar un servicio sin tocar los demás
docker compose restart backend

# Reconstruir después de cambios en Dockerfile o pom.xml
docker compose up -d --build backend

# Ver estado y uso de recursos
docker compose ps
docker stats
```

### Backend

```bash
cd backend

# Construir y testear
./mvnw clean verify

# Solo tests unitarios
./mvnw test

# Tests de integración (requiere Docker corriendo para Testcontainers)
./mvnw verify -Pintegration

# Generar reporte de cobertura
./mvnw verify jacoco:report
# Abrir target/site/jacoco/index.html

# Análisis local con SonarCloud (requiere token)
./mvnw verify sonar:sonar -Dsonar.token=<tu-token>
```

### Frontend

```bash
cd frontend

# Instalar dependencias
npm install

# Dev server con HMR
npm run dev

# Tests
npm test

# Build de producción
npm run build

# Preview del build
npm run preview

# Generar tipos TS desde OpenAPI (requiere backend corriendo)
npm run gen:api

# Lint y formato
npm run lint
npm run format
```

### Base de datos

```bash
# Conectarse con psql desde dentro del contenedor
docker compose exec postgres psql -U postgres -d acciones

# Ver migraciones aplicadas
docker compose exec postgres psql -U postgres -d acciones -c "SELECT * FROM flyway_schema_history;"

# Backup rápido
docker compose exec postgres pg_dump -U postgres acciones > backup.sql

# Reset total de la BD (⚠️ borra todos los datos)
docker compose down -v postgres
docker compose up -d postgres
```

### Redis

```bash
# Conectarse a Redis CLI
docker compose exec redis redis-cli

# Ver todas las keys
docker compose exec redis redis-cli KEYS '*'

# Flush manual del caché de precios
docker compose exec redis redis-cli DEL $(docker compose exec redis redis-cli KEYS 'price:*')
```

### ElasticSearch / Kibana

```bash
# Ver índices existentes
curl http://localhost:9200/_cat/indices?v

# Ver últimos 10 eventos de auditoría
curl 'http://localhost:9200/audit-events-*/_search?pretty&size=10'
```

Para búsquedas más usables, usar Kibana en http://localhost:5601 (Discover view).

---

## Pruebas

### Suite completa antes de PR

```bash
# Backend: build, lint, tests, coverage
cd backend && ./mvnw clean verify

# Frontend: lint, tests, build
cd frontend && npm run lint && npm test && npm run build
```

Esto es exactamente lo que corre en CI. Si pasa local, debe pasar en CI.

### Pruebas de carga (JMeter)

Los planes están en `load-tests/`. Ejecutar con el sistema corriendo:

```bash
# ESC-R1: 1500 órdenes simultáneas
jmeter -n -t load-tests/loadtest_orders.jmx -l results/orders.jtl -e -o results/orders-report

# ESC-R2: 1500 dashboards simultáneos
jmeter -n -t load-tests/loadtest_dashboard.jmx -l results/dashboard.jtl -e -o results/dashboard-report
```

> ⚠️ Las pruebas de carga **no incluyen Stripe** (instrucción del PDF del proyecto). Stripe se mockea durante load tests.

---

## Solución de problemas

### `docker compose up` falla con "out of memory"

ElasticSearch necesita memoria. En `docker-compose.yml` ya está configurado con `-Xms512m -Xmx512m`. Si aún falla:

1. Aumentar memoria asignada a Docker Desktop (Settings > Resources)
2. Si no hay más RAM disponible, levantar el stack sin ELK durante desarrollo:
   ```bash
   docker compose up -d postgres redis mailhog backend frontend
   ```
   y aceptar que los logs de auditoría no se podrán explorar en Kibana hasta que se levante ES.

### Port already in use

Algún puerto está ocupado por otro proceso. Identificar y matar:

```bash
# macOS / Linux
lsof -i :8080
kill -9 <PID>

# Windows (PowerShell)
Get-Process -Id (Get-NetTCPConnection -LocalPort 8080).OwningProcess
```

O cambiar el puerto en `docker-compose.yml` (no recomendado, rompe URLs documentadas).

### Backend arranca pero responde 503 en `/actuator/health`

Probable: la BD aún no está lista cuando el backend intenta conectar. El `docker-compose.yml` tiene `depends_on` con `condition: service_healthy`, pero a veces el healthcheck de Postgres tarda. Esperar 30 segundos y reintentar.

Si persiste:
```bash
docker compose logs backend
docker compose logs postgres
```

### Stripe webhook no llega

Verificar que `stripe listen` está corriendo en otra terminal y que el `STRIPE_WEBHOOK_SECRET` en `.env` corresponde al que imprimió `stripe listen`. Si reiniciaste `stripe listen`, te dio un secret nuevo y hay que actualizar.

### MailHog no muestra correos

Verificar config de Spring en `application-dev.yml`:
```yaml
spring:
  mail:
    host: mailhog
    port: 1025
    properties:
      mail:
        smtp:
          auth: false
          starttls.enable: false
```

El backend habla con `mailhog` (el nombre del servicio en Docker), no con `localhost`.

### Tests de integración fallan con "could not connect to Testcontainers"

Asegurar que Docker está corriendo localmente. Testcontainers levanta sus propios contenedores efímeros para los tests; si Docker está caído, fallan.

### Frontend no se conecta al backend (CORS)

Confirmar que `application-dev.yml` tiene CORS habilitado para `http://localhost:5173`:
```yaml
acciones:
  cors:
    allowed-origins: http://localhost:5173
```

---

## Apagar y limpiar

```bash
# Apagar contenedores conservando datos (volúmenes)
docker compose down

# Apagar Y borrar todos los datos (BD, ES, Redis)
docker compose down -v

# Limpiar imágenes no usadas (agresivo)
docker system prune -a --volumes
```

`docker compose down -v` es lo correcto cuando se quiere arrancar **completamente desde cero** — útil para validar que el flujo de bootstrap funciona en una máquina nueva (que es exactamente lo que hará tu profesor al evaluar).

---

## Estructura del repositorio

Resumen para orientación. La descripción detallada está en [`CONVENTIONS.md`](./CONVENTIONS.md) §1.

```
acciones-elbosque/
├── *.md                    ← Documentación maestra del proyecto
├── docker-compose.yml      ← Stack completo
├── .env.example            ← Template de variables (copiar a .env)
├── backend/                ← Spring Boot 3 + Java 21
├── frontend/               ← React 18 + TypeScript + Vite
├── specs/                  ← Specs SDD por feature (HU)
├── load-tests/             ← Planes JMeter
├── docs/                   ← Diagramas C4, secuencia, despliegue, prompts
└── .github/workflows/      ← Pipelines CI/CD
```

---

## Cómo contribuir

Este es un proyecto académico de un solo desarrollador, pero todas las contribuciones (incluso las propias) siguen el flujo definido en [`CONVENTIONS.md`](./CONVENTIONS.md):

1. Crear branch desde `main` con naming `tipo/HU-FXX-descripcion`
2. Escribir spec en `specs/HU-FXX-slug/spec.md` antes de codificar
3. Implementar siguiendo el flujo SDD (spec → plan → tasks → implement)
4. Commits con Conventional Commits, incluyendo `Co-authored-by: Claude` cuando aplique
5. Abrir PR con plantilla completa
6. Esperar pipeline verde
7. Squash and merge a `main`

> 🤖 **Sobre el uso de Claude Code:** este proyecto se desarrolla con asistencia de Claude Code bajo metodología SDD. La política de uso, el registro de prompts y el flujo de trabajo están definidos en [`CONVENTIONS.md`](./CONVENTIONS.md) §11. Toda contribución asistida es trazable.

---

## Licencia y créditos

Proyecto académico — Ingeniería de Software 2, Universidad El Bosque, 2026. Sin licencia comercial.

Referencia arquitectónica principal: *Software Architecture in Practice*, Len Bass et al.
