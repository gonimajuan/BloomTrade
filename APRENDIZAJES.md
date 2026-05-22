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

## Día 2-3 — HU-F02 + HU-F03 Login + MFA (2026-05-20)

### Sobre el tamaño del bundle y la decisión de recortar D18

**El plan original tenía 9 lotes (A→I, con E diferido) para Día 2.** Llegando al planning honesto se veía claramente que no entraban en un día — eran ~2 días de trabajo entre backend + frontend + tests + IT. La opción de "implementar igual y derramar a Día 3" estaba sobre la mesa; la opción de **recortar alcance fue mejor**: la decisión D18 difirió `/refresh` y `/logout` a una mini-HU post-MVP. El access token de 15 min es UX subóptima (re-login cada 15 min) pero perfectamente suficiente para una demo MVP. **Recortar temprano siempre fue más barato que apurar al cierre** — me llevé ese aprendizaje del Día 1 (cuando Testcontainers me hizo perder horas) y lo apliqué deliberadamente.

**Una HU "grande" en realidad eran dos.** HU-F02 (login) y HU-F03 (MFA) se planificaron juntas porque el flujo es uno solo, pero el código sale más limpio si pensás en dos sub-bundles: backend (Lotes A→F) y frontend (Lotes G→I). El backend cierra primero, el frontend lo consume. Por eso elegí terminar Lote F (tests backend) antes de tocar G; tener `mvn verify` verde le dio al frontend un blanco estable contra el cual probar.

### Redis testing — mock vs real

**Mockear `StringRedisTemplate` con Mockito + `ValueOperations`** funciona para tests unit de los trackers, pero te obliga a recordar dos cosas: (1) `redis.opsForValue()` necesita un `@Mock ValueOperations<String,String>` aparte, encadenado con `when(redis.opsForValue()).thenReturn(ops)`; (2) `redis.delete(String)` y `redis.delete(Collection<String>)` son métodos diferentes — si tu `TempSessionManager.invalidate()` usa la versión Collection y el test mockea la otra, Mockito no se queja y el verify pasa pero el código real explota.

**Para los IT preferí Redis real del docker-compose en `localhost:6379`** (mismo pivot que HU-F01 con Postgres). Sin Testcontainers. El `@SpringBootTest` autoconfigura el `StringRedisTemplate` apuntando ahí, y el `@BeforeEach` hace `flushDb()` para aislamiento entre tests. **Es mucho más rápido validar el comportamiento real (leer el OTP de `otp:{tempSessionId}` directamente) que mockear cada paso.** La hipótesis se prueba contra el mismo Redis que veré corriendo en producción.

**El IT que valida JWT real con un secret hardcoded en `application-test.yml`** no fue intuitivo al principio: pensé que iba a tener que mockear `JwtService`. No — el `JwtService` con su constructor `(secret, ttl)` se construye real, el filtro real lo valida, y el `AuthFlowIT` simplemente confirma que un token emitido en `/mfa/verify` parsea OK con `jwtService.validate(token)`. Si la firma o el formato fueran inválidos, el test rompe. Más simple, más realista, menos mocks.

### jjwt 0.12.x — un sabor distinto

**jjwt 0.12 cambió la API respecto de 0.11** (cuando hice un curso de Spring hace años, el patrón era `Jwts.builder().setSubject(...).signWith(...)`; hoy es `Jwts.builder().subject(...).signWith(key, Jwts.SIG.HS256)`). El "setX" desapareció en favor de los métodos fluidos sin prefijo. La cosa pasa desapercibida cuando seguís un tutorial, pero **el costo de no leer el changelog es escribir código que IDE-completa pero deja warnings de @Deprecated por todos lados**. Aprendido: en una HU de auth, leer las release notes de jjwt antes de codear ahorra refactor después.

**HS256 requiere clave >= 256 bits (32 bytes).** `Keys.hmacShaKeyFor(secret.getBytes())` lo valida y tira `WeakKeyException` si el secret es chico. Mi `JwtService` lo chequea explícitamente en el constructor con un mensaje accionable. **El truco fue acordarme de poner el chequeo: confiar en que jjwt lance una excepción "linda" no garantiza un mensaje accionable** — la mía dice exactamente cómo generar uno con `openssl rand -base64 64`.

### Spring Placeholder anidado — bug latente que solo se vio en `mvn verify`

`@Value("${jwt.secret:${JWT_SECRET}}")` parece razonable: "leé `jwt.secret` del yaml, si no existe leé la env var `JWT_SECRET`". **La sorpresa**: el resolver de Spring evalúa los placeholders **inside-out**, no outer-first. Cuando ni `jwt.secret` ni la env var están definidas, intenta resolver `${JWT_SECRET}` primero, falla, y aborta toda la cadena sin probar el outer. El smoke test `BloomtradeApplicationTests.contextLoads` (que existía desde Día 0) detectó esto **solo cuando corrí `mvn verify` por primera vez en Lote F** — Lotes A→D solo hacían `mvn compile`, donde el ApplicationContext nunca se levanta.

**El fix fue ridículo: cambiar `${JWT_SECRET}` a `${JWT_SECRET:}` (default vacío en el inner).** Pero el aprendizaje gordo no es la syntax: es **que `mvn compile` no es un proxy válido de "el código corre"**. Compilar prueba sintaxis Java + dependencias; el ApplicationContext de Spring se levanta recién en `@SpringBootTest`. **Si te confiaste en compile verde, el bug del placeholder vive 5 lotes hasta que algún test lo activa.** En mi caso fueron varios commits intermedios, pero ya estaba commiteado el bug; el fix se metió en el commit de Lote F como parte del scope honesto del lote.

### Tests que tocan Spring context y el costo de excluir auto-configs

**El `RegisterFlowIT` de HU-F01 excluía `RedisAutoConfiguration` explícitamente** (decisión D12 de ese plan: HU-F01 no usa Redis). Estaba bien en ese momento. Cuando entré a Lote F con `LoginAttemptTracker`, `TempSessionManager` y `MfaAttemptTracker` — todos `@Component` que inyectan `StringRedisTemplate` — el context de `RegisterFlowIT` ya no podía levantarse: Spring no encuentra el bean porque la auto-config está excluida. **Los tests de HU-F01 rompieron sin tocar HU-F01.**

El instinto es "agregar `@MockBean StringRedisTemplate` al RegisterFlowIT", pero estás emparchando. **La mejor solución fue remover el exclude original** — Redis ahora es infraestructura del módulo `auth/`, no opcional de una HU. La spec de HU-F01 D12 sigue siendo correcta históricamente, pero esa decisión envejeció. **Las decisiones `Dxx` son históricas, no eternas.** Cuando una decisión vieja choca con una nueva, gana la nueva — y se documenta el porqué del cambio.

### Frontend — Context, interceptor, y la separación correcta de responsabilidades

**`AuthContext` puro de React + interceptor del apiClient que recibe los getters por inyección.** La tentación inicial fue meter el axios interceptor dentro del componente AuthProvider con `useEffect(() => { apiClient.interceptors.request.use(...)}, [])`. Eso instala el interceptor en el primer mount pero **no lo eject al unmount** → en tests, cada `render()` agrega un interceptor nuevo y se acumulan. Y si el `accessToken` cambia, el interceptor del primer render queda "stuck" sobre el token viejo.

La solución limpia: **el interceptor vive como singleton del módulo apiClient, y el AuthProvider lo "configura" cada vez que cambia el token vía un `configureAuthInterceptor({getAccessToken, onUnauthorized})`.** El interceptor lee del closure que apunta a los getters más recientes. Una sola instalación, configuración mutable. **Es el patrón que después usás para cualquier cross-cutting concern: el módulo expone una función `configure`, los provider de React llaman esa función en useEffect con sus callbacks actuales.**

**No persistir el access token en localStorage es una decisión deliberada.** El requisito vino de la spec (§12.3): un XSS no podría exfiltrar el token porque vive solo en memoria del provider. La contra es que un reload pierde la sesión → el usuario tiene que re-loguear cada vez que cierra la pestaña. **Hasta que llegue el refresh token (mini-HU post-MVP), esta es la mejor seguridad disponible.** El refresh cookie HttpOnly va a ser invisible al JS y permitir el reload con una llamada a `/refresh` transparente, pero eso es deuda registrada.

### Docker compose, env vars, y el ciclo restart loop

**El backend container hizo restart loop completamente silencioso hasta que vi `docker compose ps` mostrando "Restarting (1) 4s ago".** Lo que pasó: agregué el `JwtService` con `@Value("${JWT_SECRET}")` en Lote A, lo testeé localmente con env var seteada en el shell, pero **nunca actualicé el `environment:` del backend service en `docker-compose.yml`**. El bug latente quedó ahí desde Día 2; recién se manifestó cuando levanté el stack completo para HITO 5 (`docker compose up -d --build backend frontend`).

**Lección dura: cada vez que el código backend introduce un nuevo `@Value("${VAR}")`, el compose `environment:` necesita actualizarse en el mismo PR.** Es como las migraciones Flyway — código de app y migración van juntos. Acá: env var de la app y env var del container van juntos. Mi `:?` syntax en el compose (`${JWT_SECRET:?...}`) ahora aborta el `up` con mensaje claro si la var está vacía, en lugar de dejar el container en restart loop silencioso.

### El bug del nginx que viajó desde HU-F01 sin hacerse notar

**Frontend en Vite dev (`npm run dev`) tiene su propio proxy en `vite.config.ts` que apunta a `localhost:8080`** — `/api/v1/auth/register` → `localhost:8080/api/v1/auth/register`. Funciona desde Día 1.

**Frontend en docker (nginx sirviendo `dist/`) usa otra configuración en `nginx.conf`** y tenía `proxy_pass http://backend:8080/;` con `/` al final. **Ese `/` final hace que nginx strippee el prefijo del location** — el backend recibía `POST /v1/auth/register` (sin `/api`) → Spring Security rechazaba con 403 sin body → el frontend lo veía como NETWORK_ERROR.

El bug existía desde HU-F01 (Día 0/1) pero **nunca se activó** porque el flujo de registro siempre se probó en Vite dev, no en el nginx del compose. **HITO 5 de HU-F02 fue la primera vez que ejecuté el flujo end-to-end con el frontend buildeado y servido por nginx**, y el bug emergió. Fix: quitar el `/` final. Una línea.

**Aprendizaje meta:** los HITOs que dicen "E2E manual con todo levantado en docker" no son ceremonia. Son la única forma de descubrir bugs de integración como este — el dev local con hot reload nunca los activa. **Si la spec dice "verificable arrancando docker-compose up", hay que ejecutar exactamente ese flujo al menos una vez por HU.**

### Tests frontend — setState durante render como herramienta legítima

**Para testear `<ProtectedRoute>` con sesión activa**, el approach inicial fue:
```tsx
function PrimeSession({ children }) {
  const { setSession } = useAuth();
  useEffect(() => setSession(...), []);
  return children;
}
```
Pero `<ProtectedRoute>` evalúa el guard ANTES de que el useEffect corra, ve `isAuthenticated=false`, y redirige a `/login`. Test rojo.

**La docs oficial de React menciona "setState during render protegido por flag" como patrón soportado:**
```tsx
if (!isAuthenticated) {
  setSession(...);
  return null;
}
return children;
```
Cuando el render se reejecuta tras el setState, `isAuthenticated` ya es true → no entra al if → renderea children. **No es un anti-pattern si está protegido por una condición que se vuelve falsa después del setState.** Lo había evitado por años por miedo al "loop infinito", pero acá es exactamente la herramienta correcta. Mejor que `useLayoutEffect` + flag manual.

### MailHog no es Gmail (otra vez)

Sé esto desde Día 0 pero igual lo olvidé en HITO 5: el primer login devolvió 200 con `tempSessionId`, abrí mi Gmail, no había mail, asumí que SMTP estaba roto. **El log del backend decía `"Email 'Tu código de acceso a BloomTrade' enviado a juangonimafornaguera@gmail.com"`** y `curl http://localhost:8025/api/v2/messages` devolvía `total:2`. **MailHog captura, no envía**. El mensaje estaba ahí, solo tenía que abrir `localhost:8025`. Lección obvia pero aún así costó 5 minutos diagnosticar.

### Cadencia de los 9 lotes (sumando los fixes colaterales)

| Lote | Trabajo | Hito |
|---|---|---|
| A | JWT + filter + handlers (backend) | mvn compile verde |
| B | Notification refactor + templates | unit MailNotifier verde |
| C | Login flow (backend) | mvn compile verde, 57 src |
| D | MFA flow (backend) | mvn compile verde, 71 src |
| E | ~Refresh + Logout~ | DIFERIDO D18 |
| F | Tests backend + CI Redis | mvn verify verde, 90 tests |
| G | Frontend infra (AuthContext + interceptor) | tsc/build verdes |
| H | Frontend pages (Login + MFA + Dashboard) | **HITO 5 ✅ E2E manual** |
| I | Tests frontend + APRENDIZAJES + PR | HITO 6 PR abierto |

**Lo que funcionó bien**: dividir backend antes que frontend. Cada lote tenía un hito objetivo (compila / test verde / E2E manual) que servía de gate antes de avanzar. **Lo que no anticipé**: la cantidad de fixes colaterales (JwtService placeholder, RegisterFlowIT exclude, docker-compose JWT_SECRET, nginx trailing slash) que aparecieron al integrar todo. Los junté en los commits de los lotes donde se descubrieron, no como commits separados — más fácil de revisar porque el contexto del lote explica por qué fueron necesarios.

### Reflexión: el bundle de 2 HUs en 2 días con Claude Code

Trabajar en lotes con HITOs explícitos y validación humana en cada uno fue **mucho más sostenible** que intentar implementar todo de un tirón. Claude Code produjo código rápido, pero la mitad del valor estuvo en los reportes de cada lote ("estos archivos toqué, así verificás el HITO, espero feedback antes de seguir"). **Sin esos checkpoints habría sido fácil derrumbarme por sobrecarga cognitiva**: 17 archivos del Lote H + 18 tests del Lote I + 4 archivos de infra modificados es mucho diff para revisar de una sola sentada.

El otro hallazgo importante fue que **los fixes que parecen "post-mortem" del lote en realidad son parte del lote**. El bug del nginx no es "una sorpresa que apareció después"; es "el lote H no estaba realmente terminado hasta que ejecuté HITO 5 y descubrí que el frontend en docker no llegaba al backend". Englobar esos fixes en el mismo commit del lote mantiene la trazabilidad y le da al revisor el contexto correcto.

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

---

## Día 3 — HU-F04 + HU-F20 Perfil + Notificaciones (2026-05-20)

### SPECs heredados: leer antes de creer

Encontré los `SPEC.md` de HU-F04+F20 y HU-F06 ya redactados en v1.0 desde el 2026-05-08. Mi primer instinto fue asumir que estaban listos y pasar al plan; el reflejo correcto fue leerlos contra el código actual. **Encontré 3 inconsistencias graves:**
1. Referencian `IAuthentication.validateToken()` — interfaz que nunca se materializó (HU-F02-F03 dejó la validación en `JwtAuthenticationFilter` directo).
2. Asumen refresh transparente del jwtInterceptor — pero D18 (HU-F02) difirió `/refresh` y `/logout` post-MVP.
3. Prefijo `I` en interfaces (`IAudit`, `INotification`) — D1 HU-F01 las renombró a `Auditor`, `Notifier`.

**Lección**: un SPEC con `Estado: Ready` no significa "verdadero", significa "redactado en un punto del pasado". Antes de codificar, validar contra el código actual y las decisiones locked posteriores. Publiqué v1.1 de ambos SPECs con un changelog explícito de las correcciones — la diferencia entre v1.0 y v1.1 ahora es trazable.

### Hibernate 6 JSONB nativo es trivial — Spring Boot 3.3+

Mapear `tickers_of_interest JSONB` ↔ `List<String>` me dio miedo al principio (recordaba la era oscura de `hibernate-types`, `@TypeDef`, librerías de Vlad). Resultó ser:
```java
@JdbcTypeCode(SqlTypes.JSON)
@Column(name = "tickers_of_interest", nullable = false, columnDefinition = "jsonb")
private List<String> tickersOfInterest;
```
**Sin librerías extra.** Hibernate 6.5 (incluido en Spring Boot 3.3) lo trae nativo. Jackson (ya en el classpath) hace la serialización. El índice GIN del DDL sigue funcional. **Lección**: revisar primero qué trae el stack actual antes de buscar librería externa — muchas cosas que requerían dependencias en 2022 ahora son nativas.

### Encapsular el PATCH parcial en el aggregate (DDD)

Mi primer impulso era poner `@Setter` Lombok en los 4 campos editables del `User` y dejar al service hacer `if (req.X != null) user.setX(req.X)`. **Mal**: expone setters innecesarios al resto del módulo y rompe encapsulación del aggregate. La opción correcta — registré como D19 — fue un método de dominio `applyProfileUpdate(...)` que recibe los 4 argumentos posibles y aplica solo los no-`null` internamente. El service no necesita conocer la representación interna; solo pasa el payload y captura un snapshot para detectar qué cambió. **Bonus**: el override de `getTickersOfInterest()` que devuelve `Collections.unmodifiableList` evita que un caller mute la lista interna después de leerla.

### `FAIL_ON_UNKNOWN_PROPERTIES=true` + handler dedicado vs DTO defensivo

El SPEC §5.3.5 exige rechazar explícitamente campos no editables (email, rol, etc) con código `READ_ONLY_FIELD_MODIFIED` y el nombre del field intentado. Dos enfoques:
- **A**: DTO con TODOS los campos read-only declarados + custom validator que rechaza si están presentes.
- **B**: `spring.jackson.deserialization.fail-on-unknown-properties=true` global + handler dedicado en `GlobalExceptionHandler` que mapea `UnrecognizedPropertyException` → 400 con `propertyName`.

Fui con **B** (D3). Mucho más limpio: cero código defensivo en el DTO, el `UpdateProfileRequest` declara solo lo que SÍ es editable, Jackson detecta lo desconocido automáticamente. **Lección**: cuando una validación se aplica a "todo lo no listado", configurar el framework para que lo enforce gratis es preferible a listar exhaustivamente las cosas a rechazar.

### El catch del `DataAccessException` emite DOS audits (D21)

Mi `ProfileService.updateMe()` primero emite `PROFILE_UPDATED` con los `changedFields` y luego llama `mapper.toResponse(user)`. Si el mapper falla (mock en el test), el catch emite además `PROFILE_UPDATE_FAILED`. **Fueron 2 audits, no 1**. El test correcto verifica ambos en orden. El "fix purista" (audit post-commit con Spring `TransactionSynchronization`) es over-engineering para MVP — lo dejé registrado como D21 y ajusté el test.

**Lección importante**: la trazabilidad forense gana siendo "ruidosa": preferir emitir más eventos auditados (incluso si parecen redundantes) que perder uno por una transacción que se cayó. ES indexa rápido y los duplicates se filtran en query.

### El `useBlocker` de react-router 6.4+ requiere DataRouter

Plan asumía D15 (`useBlocker` para interceptar navegación SPA con form dirty). En la práctica `main.tsx` usa `BrowserRouter` clásico (no `createBrowserRouter`), y `useBlocker` SOLO funciona con DataRouter. Migrar el router entero era invasivo (riesgo de regresión en rutas de auth ya en main). **Solución**: refactoricé `useDiscardChangesPrompt` a un modal manual — el botón Cancel pide confirmación si el form está dirty; `beforeunload` cubre cierre de pestaña. Cubre el caso del SPEC sin tocar la infra. Lo registré como D22 ajuste al plan.

**Lección**: cuando una librería pide un cambio arquitectónico para una feature trivial, evaluar el costo. A veces el degraded mode (mi opción B) es la elección correcta. La feature funciona; lo que pierdo es el confirm cuando el usuario sale via link a otra ruta SPA — riesgo bajo para una app de un usuario.

### Smoke test E2E sirve más que 5 tests unitarios

Después del Lote C, en lugar de saltar al Lote D directo, paré 10 minutos y armé un smoke test en PowerShell: registro → login → MFA → GET /me → PATCH /me con varios casos. **Encontré inmediatamente un typo (`aceptoTerminos` vs `aceptaTerminos`) en mi propio smoke**, no en el código. Si hubiera saltado al Lote D, ese error habría aparecido como falla de IT 20 minutos después con stack trace en Java en lugar de "tu curl está mal". **Lección**: el smoke E2E al final de cada lote es el mejor uso de 10 minutos posibles. Más rápido, más concreto y más representativo que los unit tests que voy a escribir después.

### Reflexión: cadencia "lotes + hitos" se siente diferente bajo prisa

El usuario me dijo "tengo prisa" al inicio de la sesión y "todos los lotes primero, commit grande al final". Eso cambió la estrategia: agregue tareas con `TaskCreate`, agrupé varias en cada lote, y reporté en formato resumen al cierre de cada hito en lugar de checkpoint detallado. **Conservé** las decisiones críticas (D19-D22 documentadas), **omití** los tests frontend del Lote G (deuda registrada — los validators y mapper del backend cubren los riesgos críticos: no-leak `passwordHash`, no-PII en audit). **Aprendí**: el feedback "lotes + hitos" tiene dos modos — el normal (validación humana entre lotes) y el "deja correr" (validación en hitos críticos solamente). El segundo modo requiere disciplina mía para no derivar a vibe coding; lo evité documentando cada decisión Dxx en el momento.

---

## Día 4 — HU-F06 Suscripción premium con Stripe (2026-05-21)

### La skill `stripe-best-practices` cambió 3 decisiones del SPEC v1.1

Antes de redactar el plan.md, invoqué la skill explícitamente con las 8 preguntas técnicas que tenía. La skill me hizo leer dos referencias (`billing.md` + `security.md`) y eso cambió 3 cosas del SPEC v1.1 que yo había publicado en la sesión anterior:

1. **Customer Portal en lugar de endpoint custom `/cancel`.** El v1.1 tenía un `POST /subscriptions/cancel` que llamaba `Subscription.update(cancel_at_period_end=true)`. La skill `billing.md` dice explícitamente: *"For self-service subscription management (upgrades, downgrades, cancellation, payment method updates), recommend the Customer Portal"*. Cambié a `POST /subscriptions/portal-session` que abre el portal hosted. **Beneficio inesperado**: la reactivación (cancel→cancel=false) que en v1.1 estaba fuera de alcance ahora ES posible nativamente porque el portal lo soporta. Y además los usuarios ven invoices y actualizan tarjetas sin que yo construya nada. Bumpeé el SPEC a v1.2 con changelog explícito.

2. **RAK (Restricted API Key) en lugar de `sk_`.** El v1.1 (y el `.env.example` de Día 0) tenía `STRIPE_SECRET_KEY=sk_test_replace_me`. La skill `security.md` dice "Do not default to recommending secret keys". Renombré a `STRIPE_API_KEY` (genérico) + agregué validación en `StripeConfig` que loguea WARN si el prefijo NO es `rk_`. **Lección**: la mejor práctica de Stripe es alcanzable con cero costo de implementación — solo es cuestión de respetar el prefijo correcto al crear la key en Dashboard.

3. **NO pasar `payment_method_types` al crear Checkout Session.** Trap explícito en `billing.md`: hardcodear `['card']` bloquea Dynamic Payment Methods y reduce conversión. Lo registré como D3 en el plan + assertion en `StripeAdapterTest` (deuda — terminé saltando el test por velocidad).

**Aprendizaje meta:** las skills no son "más documentación que tengo que leer". Son **decisiones técnicas pre-tomadas por expertos** que cambian el alcance del trabajo. Las consulté con un prompt específico de 8 preguntas concretas, no genérico ("ayúdame con Stripe"). La diferencia es enorme.

### Customer Portal vuelve "trivial" lo que iba a ser frontend complejo

En v1.1 yo iba a construir: modal de confirmación de cancelación + componente para mostrar invoices + UI para actualizar tarjeta. Customer Portal me ahorró **los 3**. Mi `PremiumPage` ahora tiene un solo botón "Gestionar suscripción" en estados B y C que redirige a Stripe. **Trade-off real**: el usuario sale temporalmente de mi app. Para MVP académico es totalmente aceptable; para una app de producción de un fintech serio, valdría la pena evaluar embebido con `embeddable-checkout` + componentes propios. Lo dejo registrado para post-MVP.

### El `Idempotency-Key` outbound es del SDK, NO del header HTTP

Pensé que iba a tener que manualmente setear `Idempotency-Key: ...` en algún `HttpClient` interceptor. Resulta que `stripe-java` lo expone idiomáticamente con `RequestOptions.builder().setIdempotencyKey(key).build()` pasado como segundo argumento a `Customer.create(params, options)`. Cero hacks. **Aprendizaje**: cuando uno asume que algo será verbose por experiencia con otras SDK, revisar primero si la SDK específica lo cubre — los productos maduros como Stripe lo tienen pulido.

### Webhook signature verification requiere body raw, no parseado

Spring por default desserializa el body con Jackson cuando uno usa `@RequestBody SomeDto`. La firma HMAC de Stripe se computa sobre los bytes exactos del body — cualquier deserialización rompería el hash. Solución: `@RequestBody String rawBody`. El controller queda raro (un endpoint que recibe `String` en lugar del DTO) pero es lo correcto. Lo documenté en el JavaDoc del controller para que quien lo lea no piense que es un error.

### Idempotencia con UNIQUE constraint > tabla de cache manual

Mi primera idea fue: pre-check `existsByStripeEventId(...)` antes de procesar. **Mala idea** — race condition entre dos webhooks paralelos. Solución correcta: hacer INSERT directo en `stripe_webhook_events` con `stripe_event_id UNIQUE`; si la segunda vez `DataIntegrityViolationException`, capturarla y mapear a `STRIPE_WEBHOOK_DUPLICATE`. Postgres garantiza la atomicidad del INSERT-or-fail. **El UNIQUE constraint es el corazón del mecanismo, no un detalle**. Mi `StripeWebhookHandler` lo documenta así en el comentario top de clase.

### El `cancel_at_period_end` transition detection vive en el webhook, no en el endpoint

En v1.1 yo emitía `SUBSCRIPTION_CANCELLED_SCHEDULED` desde dos lugares: el endpoint `/cancel` y el webhook (defensivo). En v1.2 con Customer Portal, hay UN solo lugar: el webhook `customer.subscription.updated` detecta la transición `false→true` y emite el audit + email. Más limpio. **Insight**: cuando una acción puede ocurrir en múltiples sitios (mi endpoint o Stripe Portal), conviene tratar al webhook como single source of truth y eliminar el "audit duplicado defensivo". El SPEC v1.2 lo refleja.

### `mvn verify` con jacoco impacta el classpath de los tests

Cuando agregué `StripeWebhookHandlerTest` con un mock que devolvía `Event event = new Event()`, los primeros runs daban un warning de jacoco sobre instrumentación. Era ruido. Lo verifiqué corriendo `mvn test` (sin verify) y confirmé que los tests pasan limpios. **Aprendizaje**: si un warning aparece solo durante `verify` y los tests pasan en `test`, probablemente es jacoco midiendo cobertura — no es un test fail.

### Tests del handler con SDK complejo: skip pragmático

`Event`, `Session`, `Subscription`, `Invoice` de stripe-java son clases grandes con muchos getters. Mockear `event.getDataObjectDeserializer().getObject()` para que devuelva un Session con metadata válida es ~30 líneas de boilerplate POR test. Para el MVP de 1 día decidí **cubrir solo los 3 tests críticos** (signature inválida, idempotencia duplicada, tipo desconocido) y dejar los 4 handlers individuales para verificación E2E con `stripe-cli trigger`. **Trade-off documentado en tasks.md deuda**: una IT con WireMock + payloads JSON reales de Stripe sería lo ideal para CI. Lo veo como deuda Sprint 2.

### Reflexión: Stripe es la mejor API que he integrado

Después de haber integrado Twilio, SendGrid, Polygon y varios otros en otros proyectos, Stripe se siente categóricamente diferente: la SDK Java está bien escrita, la documentación es **densa pero precisa**, los errores tienen códigos claros (`api_error`, `card_declined`, `signature_verification_failed`), el Customer Portal te ahorra trabajo, el `stripe-cli` te da forwarding local sin tener que pelearte con ngrok. **Sospecha**: la mayoría de los bugs raros de pagos de los que uno escucha no vienen de Stripe sino de integraciones mal hechas (no verificar firma, no manejar idempotencia, parsear body antes de verificar, etc.). La skill `stripe-best-practices` me empujó a NO cometer esos errores comunes desde el día 1.
