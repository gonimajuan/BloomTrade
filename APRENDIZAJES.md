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

## Día 1 — HU-F01 Registrarse (2026-05-19)

### Spring Boot 3.3 + JPA + Hibernate 6

**`@TransactionalEventListener(phase = AFTER_COMMIT)` corre sincrónicamente al cerrar la transacción**, en el mismo thread del commit. Combinado con `@Async` en el dispatcher de email, el flujo queda elegante: el listener emite `USER_REGISTERED` al `Auditor` (sync, garantiza orden post-commit) y dispara el email asíncrono que retorna inmediato. Cuando `mockMvc.perform()` vuelve en el IT, el listener ya corrió — verifico el mock del auditor sin Awaitility.

**Anticipé un watch-point con `Instant` ↔ `TIMESTAMPTZ` bajo `ddl-auto=validate`** y resultó no ser nada. Hibernate 6 mapea `Instant` a `TIMESTAMP WITH TIME ZONE` por default (cambio respecto de Hibernate 5), así que validate pasa contra `TIMESTAMPTZ` sin tocar nada. La regla aprendida: anticipar riesgos está bien, pero no anclar la solución antes de ver el error real.

**Lombok `@Builder` sobre `record`** lo soporta Lombok 1.18.x — pero combina dos generaciones de código que no compilé localmente al principio (no había `mvn` global). Lo eliminé de `AuditEvent` y escribí un builder a mano (25 líneas). Costo bajo, previsibilidad alta. Lección: cuando no podés validar localmente, **eliminá fragilidad antes que confiar en magia**.

**Bean Validation en records** (Hibernate Validator 8 + Java 21) propaga las anotaciones del record component al accessor. `@AssertTrue boolean aceptaTerminos` — **primitivo `boolean`, no `Boolean`**: si fuera Boolean, `null` se considera "válido" por `@AssertTrue`, y un campo ausente en el JSON pasaría como aceptado. El primitivo fuerza false-por-default y bloquea el bypass.

**Constraint a nivel de clase con `addPropertyNode("campo")`** imputa el error al campo correcto del `fieldErrors[]`, no al objeto entero. Sin esto, el cross-field `@ConsistentDocumentNumber` reportaría error sin nombre de campo y mi heurística D14 (un solo fieldError → su código sube al `error` top-level) no funcionaría.

### Spring Security / BCrypt

**El default de `BCryptPasswordEncoder()` es strength 10**, no 12. La spec exigía `$2a$12$`. Tenía que pasarlo explícito: `new BCryptPasswordEncoder(12)`. **No confiar en defaults cuando la spec fija un número** — me podría haber pasado el test diciendo "BCrypt OK" y el hash empezando con `$2a$10$`. La aserción específica del prefix (`startsWith("$2a$12$")`) en el IT es lo que cierra el círculo.

### Tests — el rabbit hole de Testcontainers en Windows

**Docker Desktop reciente expone el engine Linux en el pipe `dockerDesktopLinuxEngine`**, pero el `NpipeSocketClientProviderStrategy` de `docker-java 1.19.x` (el que viene con Testcontainers vía Spring Boot 3.3.6) sigue probando el legacy `docker_engine`. `.testcontainers.properties` con `docker.host=npipe:////./pipe/dockerDesktopLinuxEngine`, `DOCKER_HOST` como env var de usuario, `DOCKER_HOST` inline — **nada surtió efecto en tres intentos**. El log devolvía el mensaje cacheado "Previous attempts to find a Docker environment failed", sin filtrar la causa raíz original.

**Pivot pragmático**: dropear Testcontainers para HU-F01 y usar el Postgres del `docker-compose` ya levantado en `localhost:5433` sobre BD separada `bloomtrade_test`. Tests sub-segundo, sin pelea con docker-java. La JDBC URL mágica (`jdbc:tc:postgresql:16-alpine:///...`) queda como deuda para retomar cuando docker-java soporte el pipe nuevo. **El tiempo de cierre del MVP no es momento para inversiones de infra que no entregan valor al deliverable**.

**`@MockBean JavaMailSender` choca con `MailHealthContributor`** ("Beans must not be empty"). El actuator construye el contributor con `Map<String, JavaMailSender>` que queda vacío cuando `@MockBean` reemplaza el bean autoconfigurado. Patrón conocido que no me había topado. Fix limpio: `management.health.mail.enabled=false` en el perfil `test` — el health del mail no aporta valor en tests; el dispatcher se prueba directamente con un test unitario dedicado.

**Surefire ≠ Failsafe**. Surefire corre `*Test`; Failsafe corre `*IT` en fase `integration-test`/`verify`. **El `spring-boot-starter-parent` NO bindea Failsafe por default** — hay que declarar el plugin. CONVENTIONS pedía el sufijo `IT`, pero el `pom` de Día 0 solo tenía Surefire, así que mis ITs nunca corrían hasta agregar el plugin. Diez líneas de pom me costaron 10 minutos de no entender por qué Surefire los ignoraba.

### Maven en Windows + Claude Code

**Maven Wrapper "only-script"** (`mvn -N wrapper:wrapper -Dtype=only-script`) es la forma limpia para repos sin binarios commiteados: el script descarga Maven la primera vez y queda cacheado en `~/.m2/wrapper`. Para SDD/CI es ideal — cualquier clon con JDK puede compilar sin instalar Maven globalmente. Lo agregué en HU-F01 porque `mvn` no estaba en mi PATH y me di cuenta tarde (Día 0 había usado Maven solo en CI).

**El shell de Claude Code no relee variables de entorno del registro de Windows.** Cuando hago `[Environment]::SetEnvironmentVariable('JAVA_HOME', ..., 'User')`, mis terminales nuevas sí lo ven, pero los `PowerShell` que Claude spawnea heredan un environment cacheado al inicio de la sesión. **Para que Claude pueda correr `mvn` ahí: ruta absoluta + `$env:JAVA_HOME='...'` inline en cada comando.** Detalle operacional importante para no asumir que "ya está configurado".

### Frontend — React Hook Form + zod

**`mode: 'onChange'` en RHF** es lo que hace que el botón submit reaccione al estado de validez sin esperar al primer submit. El default `'onSubmit'` deja `isValid` indefinido hasta que el usuario apriete enviar — UX confuso. `onChange` re-valida en cada cambio y el botón pasa de disabled a enabled visualmente.

**D14 espejado en el cliente**: la decisión "un solo fieldError → su código sube al `error` top-level + queda en `fieldErrors[]`" la implementé en backend (GlobalExceptionHandler) y en frontend (`parseError` indexa por campo). El `useEffect` del form aplica los `fieldErrors` del servidor al campo correspondiente vía `setError(field, ...)` — mismo flujo visual que los errores client-side de zod.

**`z.literal(true)` infiere el tipo TS `true`, no `boolean`** — incompatible con `defaultValues: { aceptaTerminos: false }` en `useForm`. La forma correcta: `z.boolean().refine(v => v === true, { message: 'TERMS_NOT_ACCEPTED' })`. Misma semántica, tipo correcto.

**`userEvent.type` posiciona el cursor al final del valor existente** en testing-library/user-event v14 + jsdom. Por eso pude probar el `PhoneInput` con default `+57` simplemente tipeando `'3001234567'` — el resultado es `+573001234567`. Detalle chico pero útil de saber para no escribir tests defensivos innecesarios.

### SDD en práctica

**Las decisiones D1-D15 acumuladas en `plan.md` son evidencia académica**. Cada `D` resuelve una pregunta abierta del proceso (naming de interfaces ARCHITECTURE vs CONVENTIONS, motor de plantillas de email, regla de propagación de errores, regex de teléfono inconsistente entre §6.1 y §11 de la spec). Es trabajoso registrarlas pero es exactamente lo que el curso quiere ver: **el rastro auditable de las decisiones, no solo el código final**.

**Inconsistencias entre docs maestros las paro y pregunto**. ARCHITECTURE.md §5 nombraba las interfaces inter-módulo con prefijo `I` (`IAudit`); CONVENTIONS.md §5.3 prohíbe el prefijo. La decisión la firmó el humano y quedó como D1. El instinto de "lo arreglo silenciosamente porque sé cuál tiene razón" es exactamente lo que CLAUDE.md prohíbe — y con razón: la trazabilidad se rompe si los desvíos no se documentan.

**Pivot que toca un archivo de Día 0**. Bajo el alcance puro de HU-F01, `application-test.yml` no debería tocarse. Pero el rabbit hole de Testcontainers me forzó a cambiarlo (perfil test pivota a Postgres del compose). Lo documenté en el comment del yaml + acá + en `tasks.md` como deuda nueva. **El SDD no es "nunca tocar nada que no sea de tu feature"; es "si tocás algo de otra capa, lo explicás"**. La diferencia es de procedimiento, no de pureza.

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
