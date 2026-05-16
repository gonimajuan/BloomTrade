# APRENDIZAJES

> Bitácora personal de aprendizajes durante el desarrollo de BloomTrade. No es documentación del proyecto, es un registro mío para no olvidar cosas que descubrí en el camino.

---

## Día 0 — Bootstrap del proyecto (2026-05-15 / 2026-05-16)

### Docker y docker-compose

**El `.env` se interpola en `docker-compose.yml`, y si no existe, todas las variables `${...}` quedan en string vacío.** Postgres se niega a iniciar sin `POSTGRES_PASSWORD`, y como tenía `restart: unless-stopped` entró en un loop infinito de reinicio. El error real estaba escondido en `docker compose logs postgres`. Lección: **siempre revisar logs antes de asumir que algo "no anda"** — el bucle de restart oculta la causa raíz si solo miro `docker compose ps`.

**Puerto host vs puerto contenedor son cosas separadas.** Mi Postgres nativo escuchaba en `5432`, así que remapeé a `5433:5432`. Lo importante: dentro de la red Docker, otros contenedores siguen hablando a `postgres:5432` (puerto interno), pero desde mi máquina conecto a `localhost:5433`. Es una mapping del lado del host únicamente.

**Healthchecks dependen de las herramientas que tenga la imagen.** Alpine no trae `curl` por default. Para el healthcheck del backend tuve que hacer `apk add --no-cache curl` en el Dockerfile. MailHog v1 no tiene ni `curl` ni `wget`, por eso lo dejé sin healthcheck y aparece como `running` en lugar de `(healthy)`.

**`depends_on: condition: service_healthy` evita race conditions de arranque.** El backend no arranca hasta que Postgres, Redis y Logstash reportan healthy. Antes de descubrir esto, el backend intentaba conectar a Postgres mientras todavía estaba inicializando.

**`docker compose down -v` borra volúmenes; sin `-v` solo containers.** Crítico cuando algo quedó mal inicializado (como mi Postgres sin password) — limpio el volumen para arrancar de cero. Aprendí que `down -v <servicio>` **no es sintaxis válida**: `-v` aplica a todo el stack.

**Healthchecks tienen `start_period`.** Sin esto, contenedores que tardan en arrancar (ES, Kibana) son marcados unhealthy prematuramente. Para ES usé `start_period: 60s`, para Kibana `90s`.

### Git Bash en Windows

**MSYS reescribe paths Unix-style a Windows-style cuando ejecuta procesos.** Cuando hice `docker run -v ... -w /app`, Git Bash lo convirtió a `-w "C:/Program Files/Git/app"` y rompió el comando. Fix: `MSYS_NO_PATHCONV=1` como prefijo, o usar doble slash (`//app`).

### Nginx en Alpine

**Si tu `default.conf` es custom, el script `10-listen-on-ipv6-by-default.sh` no lo modifica.** Detecta que "differs from packaged version" y por diseño no toca tu config. Resultado: nginx solo escucha en IPv4, pero `localhost` dentro del contenedor a veces resuelve a IPv6 (`::1`), y los healthchecks con `wget --spider http://localhost/` fallan con "Connection refused". Fix: agregar `listen [::]:80;` explícito al config.

### Spring Boot

**Spring Boot tiene "relaxed binding" — las env vars sobrescriben cualquier property.** `SPRING_DATASOURCE_URL=jdbc:...` como env var del compose sobrescribe `spring.datasource.url` del `application-dev.yml`. Esto me permitió correr el mismo profile `dev` tanto dentro de Docker (conectándose a `postgres:5432`) como fuera (conectándose a `localhost:5433`).

**Flyway necesita que el `default-schema` exista antes de correr.** Cuando seteás `spring.flyway.schemas=app`, Flyway crea `app` automáticamente y aloja allí `flyway_schema_history`. Si solo definís `default-schema` sin `schemas`, Flyway falla porque no encuentra dónde poner la tabla de historia. La V1__create_schemas.sql puede ser idempotente con `IF NOT EXISTS` y manejar `config` + `audit` además.

**`@SpringBootTest` carga el ApplicationContext completo.** Útil como smoke test porque detecta errores de configuración de beans, mappings JPA inválidos, etc. La contracara es que es lento — un test "trivial" tarda 5-10s.

**Variables Spring con default inline:** `${POSTGRES_PASSWORD:changeme}` — el valor después de `:` es el default si la env var no existe. Hice que mis defaults coincidieran con `.env.example` para que el dev local "just works" sin tener que exportar nada.

### Vite y TypeScript

**`vite.config.ts` con sección `test` rompe la compilación de TypeScript si importás `defineConfig` desde `vite`** — porque `UserConfigExport` no conoce el campo `test` (es de Vitest). Fix: importar desde `vitest/config`:
```ts
import { defineConfig } from 'vitest/config';
```
Es el patrón oficial de Vitest cuando metés su config dentro del de Vite.

**`vite.config.ts` usa APIs de Node (`node:path`, `__dirname`)**, así que necesita `@types/node` en `devDependencies`. Sin esto, `tsc` falla con `Cannot find module 'node:path'`. Lo aprendí cuando el Docker build del frontend reventó por errores TS.

**`tsconfig.json` tiene un campo `types` que es restrictivo.** Si seteás `"types": ["vite/client", "vitest/globals"]`, **solo** esos `@types/*` se cargan globalmente. Útil porque me permite tener `@types/node` instalado para `vite.config.ts` pero **no** disponible en el código de `src/` (donde no debería usar APIs de Node). El `tsconfig.node.json` separado no tiene esa restricción.

### npm y CI

**`npm ci` exige `package-lock.json` estricto** (vs `npm install` que lo regenera si hace falta). Para builds determinísticos en CI **siempre commitear el lock**. Sin el lock, `actions/setup-node@v4` con `cache: 'npm'` falla con "Some specified paths were not resolved".

**Generar el lock sin tener Node instalado en el host:** comando docker one-shot:
```
docker run --rm -v $PWD/frontend:/app -w /app node:20-alpine npm install
```
Monta tu carpeta `frontend`, corre install dentro, deja el `package-lock.json` en tu disco. Usa la misma imagen que el Dockerfile → garantiza compatibilidad.

### SonarCloud

**Organization NAME ≠ Organization KEY.** El nombre es lo que ves en la UI ("BloomTrade"), la key es el slug (`bloomtrade`, lowercase) que usa la API. Confundirlas da `Error 404 — Organization key 'BloomTrade' does not exist`. La key se ve en la URL: `sonarcloud.io/organizations/<key>`.

**Project keys siguen el patrón `<github-user>_<repo>`** cuando se importa desde GitHub. En mi caso `gonimajuan_BloomTrade`. La parte antes del `_` viene del owner del repo, NO de la organization de SonarCloud — esto me confundió porque asumí que la org key era `gonimajuan`.

**Si usás GitHub Actions, hay que desactivar "Automatic Analysis"** en SonarCloud → Project → Administration → Analysis Method. Si no, colisionan los dos scanners.

**SonarCloud necesita `fetch-depth: 0`** en el `actions/checkout@v4`. Por defecto checkout hace shallow clone (solo el último commit), pero Sonar usa `git blame` para asignar issues por línea, y blame requiere historia completa.

### GitHub Actions

**Status checks aparecen en branch protection settings solo DESPUÉS de la primera corrida exitosa del workflow.** No los podés agregar antes — GitHub no sabe que existen hasta que los ve corriendo al menos una vez. Esto significa que necesitás:
1. Mergear el `ci.yml` a `main` primero
2. Que corra al menos una vez
3. *Después* configurar branch protection con los checks como required

**`if: env.SONAR_TOKEN != ''` permite que un step se saltee silenciosamente si el secret no está registrado.** Útil para steps "opcionales hasta que el setup externo esté listo" sin romper el pipeline.

---

## Sobre el proceso SDD (Spec-Driven Development)

**El plan ANTES del código no es burocracia, es ahorro de tiempo.** Mi `docs/day-0-bootstrap-plan.md` me obligó a:
- Detectar inconsistencias entre documentos maestros (paquete `elbosque` vs `unbosque`) *antes* de crear archivos con el nombre incorrecto.
- Identificar dependencias entre subtareas y diseñar los cortes de validación humana.
- Tener una checklist clara contra la cual marcar progreso.

**Una tarea a la vez, con validación entre cada una, evita el "deshacer cinco cosas porque la primera estaba mal".** Cuando Postgres no levantaba, paramos ahí — no seguimos a backend. Cuando el contenedor del frontend estaba unhealthy, paramos — no avanzamos a CI. Cada paso construye sobre validación.

**No "corregir silenciosamente" inconsistencias entre docs.** Cuando ARCHITECTURE.md decía `elbosque` y STACK.md decía `unbosque`, lo correcto fue *parar y preguntar*, no decidir yo cuál tenía razón. La decisión la toma el humano, no el agente.

**Documentar los recortes de alcance es preferible a entregar a medias.** Decidí saltar T13 (branch protection) por tiempo. Honesto, registrado, y explicable al profesor — mejor que improvisar.

---

## Reflexión general

El bootstrap de un proyecto "vacío" tomó dos días de trabajo intenso (sesiones A → D + ajustes). Habría tomado mucho más sin Claude Code, pero también habría tomado más con Claude Code sin un plan estructurado — la mayoría del tiempo "perdido" fue debuggeando integraciones (Postgres sin .env, nginx IPv6, types de Node, org key de SonarCloud). Esos errores no son evitables a priori, pero **detectarlos rápido sí depende del proceso**: logs primero, asumir después.

Lo que más me llamó la atención: cada herramienta del stack tiene una "cara amable" (la documentación oficial) y una "cara cruda" (los errores reales en producción/Docker/Windows). Aprender a operar la cara cruda es lo que distingue a alguien que "siguió un tutorial" de alguien que "armó la infraestructura". Día 0 fue mayoritariamente esa cara cruda.
