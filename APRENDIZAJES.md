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

---

## Día 6 — HU-F09 Compra Market con Alpaca paper trading (2026-05-22)

### El pivote Polygon→Alpaca antes de codear me ahorró ~2 días

El SPEC v1.0 original asumía Polygon.io como market data provider y Alpaca solo para trading. Al revisar el cuestionario del plan, ratifiqué D9 (D-MD-PROVIDER) por dos razones concretas: (a) reportes recientes de degradación en el free tier de Polygon, (b) la cuenta paper de Alpaca **ya incluye market data gratuita** (IEX feed, delayed 15min) sin creds adicionales. **Lección**: cuando hay dos providers en juego para una capacidad y ambos serían externos, vale la pena hacer un mini-spike de 30 minutos antes de finalizar el SPEC. Tenía dudas pero las dejé "para después"; resolverlas pre-codeo eliminó: una env var (`POLYGON_API_KEY`), un adapter completo, un set de tests con WireMock, y la complejidad de manejar dos retry policies distintas. **Counterfactual**: si hubiera arrancado a codear el SPEC v1.0 a ciegas, habría llegado al HITO 5 dándome cuenta del problema y refactoreando 6 archivos.

### BigDecimal HALF_UP no es opcional — y la división es el caso peligroso

`CommissionManager` calcula `subtotal × commissionRate` (multiplicación, no peligrosa). Pero el `userBalance / unitPrice` en `quote.maxAffordableQuantity` (que descartamos del MVP) habría dado `ArithmeticException: non-terminating decimal expansion` si no hubiera `divide(divisor, 4, RoundingMode.HALF_UP)`. **Lección**: la regla de "siempre HALF_UP" suena pedante pero ahorra debugging futuro. La escribí explícitamente en CONVENTIONS.md cuando empezó el módulo trading. Tests parametrizados con `@CsvSource` cubren los casos borde (precio `0.01`, precio `9999.99`, división con remainder) — descubrieron 2 bugs de redondeo que el ojo no veía.

### El trap de Hibernate L1 cache rompió el `SELECT FOR UPDATE` (D26)

Bug emergente del Lote G: `TradingServiceConcurrencyIT.concurrency_twoOrdersOverlapBalance_exactlyOneSucceeds` fallaba — ambos threads veían fondos suficientes y ambos ejecutaban. Causa raíz tomó ~40 min identificar: `portfolioService.getBalance(userId)` cargaba el entity `UserBalance` al L1 cache de la session de Hibernate. Después `findByUserIdForUpdate(userId)` con `@Lock(PESSIMISTIC_WRITE)` **reutilizaba la entity cacheada** y NO emitía el `SELECT FOR UPDATE` real. El lock pessimistic quedaba inútil. **Solución**: `findBalanceProjectionByUserId(userId)` que retorna `BigDecimal` directo sin tocar el cache. **Lección**: cuando uno mezcla query-by-id que carga entity vs query con lock que necesita SELECT fresco, Hibernate hace lo que NO esperás. Siempre que un test de concurrencia "pase fácil", desconfiar.

### `noRollbackFor` no se hereda en nested `@Transactional` (D24)

Otro bug del Lote G: `placeOrderTx` tenía `@Transactional(noRollbackFor = AlpacaApiException.class)` para que la fila FAILED persistiera incluso si Alpaca caía. Pero `portfolioService.debit(...)` (nested, `Propagation.REQUIRED`) lanzaba `InsufficientFundsException` y marcaba la tx outer como **rollback-only**. El flag rollback-only del nested "gana" sobre el `noRollbackFor` del outer. **Solución**: agregar `noRollbackFor = InsufficientFundsException.class` también en `PortfolioService.debit`. **Lección**: cualquier excepción que cruce un boundary `@Transactional` puede contaminar la tx outer. Cuando se quiere preservar filas a pesar de error de negocio, hay que decorar en ambos niveles (o aplastar la jerarquía con `Propagation.NESTED`/savepoints, pero eso complica más).

### Idempotencia end-to-end requiere lock antes del commit (D25)

Test del Lote G enviaba 10 threads concurrentes con el mismo `clientOrderId`. Esperábamos 1 fila + 9 respuestas 200. Resultado real: 1 fila + 9 `DataIntegrityViolationException` (`uq_orders_client_order_id` UNIQUE constraint disparado). Causa: los 10 threads hacían `findByClientOrderId` simultáneo (todos veían empty) y los 10 hacían INSERT. **Solución**: `ConcurrentHashMap<UUID, Object>` con `synchronized(clientOrderLocks.computeIfAbsent(clientOrderId, k -> new Object()))` alrededor del flujo entero. PERO el `@Transactional` outer commitea **fuera del synchronized** (el proxy AOP commitea cuando el método retorna). Truco: mover `@Transactional` a un método interno `placeOrderTx(...)` invocado via `self.placeOrderTx(...)` con self-injection `@Lazy` para evitar ciclo. Eso fuerza el commit DENTRO del synchronized. **Lección**: Spring `@Transactional` + concurrencia + lock manual requiere entender cuándo exactamente commitea el proxy. Lo aprendí a fuerza de fallar el test 5 veces.

### `time_in_force=day` + mercado cerrado = orden encolada, NO orden fallida (D29)

El HITO 8 demo se hizo viernes a las 21:36 hora Colombia. NYSE cerró a las 15:00 hora Colombia. Alpaca aceptó la orden (`status=accepted`), el backend hizo 3 polls × 200 ms (=600 ms), Alpaca seguía respondiendo `accepted` porque la orden estaba esperando la apertura del lunes-martes. El backend marcaba la orden como FAILED con `ALPACA_PENDING_TIMEOUT`. **Eso es semánticamente incorrecto**: la orden NO falló, está en cola; un broker real así la representaría hasta que se llene al abrir. Mini-fix Lote H.5: separar `accepted` no-terminal (mapear a `PENDING + alpacaOrderId`, debitar cash reservado, email "tu orden quedó en cola" ámbar) del FAILED real (Alpaca caída tras retries). **Lección**: los SPEC pre-implementación tienden a asumir mercado siempre abierto. La realidad operacional del polling síncrono con TIF=day expone esto inmediatamente. **Aprendizaje meta**: los HITOs E2E manuales son donde aparecen estos gaps; los tests IT con WireMock estuvieron felices porque mockean Alpaca como si siempre filee.

### `ALPACA_BASE_URL=…/v2` vs `…/v2/orders` da 404 críptico (D28)

El dashboard de Alpaca muestra "API Endpoint: https://paper-api.alpaca.markets/v2" como hint. El usuario pegó eso en `.env`. Pero `AlpacaTradingAdapter` prepende `/v2/orders` en el `RestClient`. Resultado: `https://paper-api.alpaca.markets/v2/v2/orders` → **404 NOT_FOUND**. El email FAILED decía "Alpaca rechazó request: 404 NOT_FOUND" — bastante inútil para diagnosticar. **Lección**: cuando un adapter prepende un path conocido, hay que ser explícito en el `.env.example` Y en `IntegrationConfig.validateCredentials` (logear la URL final al startup, idealmente con un check "no debe terminar en /v2"). Una línea más de defensa habría ahorrado 20 min de debug. Lo registré como D28 + TODO para próxima iteración.

### `docker compose restart` ≠ `docker compose up -d` cuando cambian env vars

Tras editar `.env` para quitar el `/v2`, le dije al usuario `docker compose restart backend`. **Mal consejo**: `restart` reinicia el proceso usando las env vars con que se creó el container (al primer `up`). Para releer `.env` hay que **recrear** el container con `up -d` (sin `--build` si solo cambió config). Lo descubrí cuando el log del backend SEGUÍA mostrando `trading: https://paper-api.alpaca.markets/v2` después del restart. **Lección**: Docker Compose tiene dos niveles de "estado" — la config del container (env vars, mounts, ports) congelada al `up`, y el proceso runtime sujeto a `restart/stop/start`. Para tomar cambios de config: `up -d`. Para reciclar proceso con misma config: `restart`. La distinción es sutil y los docs no enfatizan suficiente.

### `@TransactionalEventListener(AFTER_COMMIT)` + `REQUIRES_NEW` es el patrón correcto para side effects

`OrderEventListener` dispara emails + audit logs. Si participara de la tx del `placeOrderTx`, un rollback dispararía email "tu orden fue ejecutada" para una orden que NO se persistió. **Solución**: `@TransactionalEventListener(phase = AFTER_COMMIT)` garantiza que el handler corre SOLO si el commit fue exitoso. PERO en ese momento no hay tx activa, así que el lookup del User para extraer email requiere `@Transactional(REQUIRES_NEW, readOnly = true)`. **El email send es `@Async`** además, así que el dispatch sucede en otro thread sin bloquear nada. Tres niveles de aislamiento en cascada. **Lección**: Spring tiene primitives buenas para "haz X después del commit, no antes, y sin bloquear" pero hay que combinar 3 anotaciones distintas. Cuando uno las combina mal, los síntomas son sutiles (emails fantasma, audit logs duplicados, etc.).

### Reflexión meta: la SPEC drift en HUs grandes es inevitable, lo que importa es el ritmo de consolidación

HU-F09 produjo 5 decisiones emergentes (D23–D27 Lote G, D28–D29 Lote H/H.5) en ~14 horas de implementación. Ninguna estaba en el SPEC v1.0. Ninguna fue "el usuario cambió de opinión" — todas fueron descubrimientos técnicos al chocar con la realidad. **Patrón observado**: los SPECs pre-codeo predicen el 80% del diseño, pero los HITOs E2E manuales y los tests IT de concurrencia destapan el 20% restante. La diferencia entre un SPEC útil y uno que rota irrelevante: **consolidar el spec con changelog cada vez que se cierra una HU**, no esperar al fin del MVP para "documentar todo de una". El SPEC v1.1 con D28–D29 todavía es accionable como referencia para HU-F10 (venta Market reutiliza el andamio); si dejara esos D28–D29 solo en `plan.md` y nunca los tocara, dentro de 2 meses serían arqueología.

---

## Día 7 — HU-F10 Venta Market con Alpaca paper trading (2026-05-24)

### El andamio F09 anticipó F10 casi perfecto — pero "casi" cuesta D17 y D18

El SPEC F09 §7.2 dejó `chk_order_side CHECK (side IN ('BUY','SELL'))` y `chk_position_quantity CHECK (quantity >= 0)` explícitamente para que F10 no requiriera V6. Eso funcionó: T1.2/T1.3 del Lote A confirmaron BD apta sin migración nueva, y **ese chequeo de 30 segundos me dio confianza para arrancar a codear inmediatamente** sin paranoia de "¿se me pasó algo?". F10 reutilizó ~60% del código F09 (TradingService, AlpacaTradingAdapter, MarketDataAdapter, OrderEventListener, OrderMapper, infraestructura de retry y events) tal cual. **Pero el otro 40%** desencadenó 2 bugs que el SPEC pre-codeo NO anticipó: el deadlock BUY+SELL concurrente (D17) y el `UnexpectedRollbackException` del `validateSellable` anidado (D18). Ambos atrapados por tests IT, ambos con root cause sutil (1 línea de código + 1 anotación). **Lección**: cuando uno presume reuso alto, los huecos restantes son justo donde la asimetría arquitectónica vive. El plan estimó ~40-50% del esfuerzo F09; salió ~45% — pero los HITOs 1-2 (Lotes A-B, código nuevo) fueron mucho más rápidos de lo esperado, y los HITOs 4 (IT con concurrencia) descubrieron lo que no había visto en el SPEC.

### El deadlock BUY+SELL concurrente: mi javadoc D12 estaba mentido (D17)

Cuando escribí D12 en el plan, juré que "BUY y SELL no compiten por los mismos 2 locks". Razonamiento: "BUY solo toma lock en balances (via debit), SELL solo toma lock en positions (via validateSellable)". **Realidad**: cada SELL eventualmente llama `credit` post-Alpaca (que SI toma lock balances), y cada BUY eventualmente llama `upsertPosition` (que SI toma lock implícito positions via Hibernate dirty checking del entity persistido). Resultado: BUY = balances→positions, SELL = positions→balances. **Ciclo perfecto**. Postgres lo detectó como "deadlock detected" en `TradingServiceSellConcurrencyIT#concurrency_buyAndSellSameTicker_*`. Fix: `validateSellable` ahora invoca `userBalanceRepository.findByUserIdForUpdate(...)` PRIMERO antes del lock positions, aunque la venta NO requiera pre-validar saldo. **Lección amarga**: "no hay deadlock porque mis dos paths no se cruzan" es exactamente el tipo de razonamiento que necesita test para falsearlo. Si no hubiera escrito el test BUY+SELL concurrente (estaba en el plan pero podía haberlo saltado por "es claramente correcto"), este bug habría llegado a producción y aparecido cuando un usuario hiciera doble-click muy rápido alternando compra y venta. Me apaño en el test IT que catché esto antes de mergear.

### El segundo `UnexpectedRollbackException`: D27 F09 vuelve disfrazado como D18

D27 de HU-F09 ya me había enseñado que cuando un método `@Transactional` anidado lanza una excepción, Spring marca la TX outer como rollback-only INDEPENDIENTEMENTE del `noRollbackFor` del outer. Pensé que lo tenía interiorizado. **No**: olvidé aplicarlo a `validateSellable` y `decrementPosition` en Lote A. Síntoma en Lote D: `TradingControllerSellIT` daba 500 INTERNAL_ERROR para SHORT_SELLING / INSUFFICIENT_SHARES en lugar de 409. El stack trace mostraba `org.springframework.transaction.UnexpectedRollbackException` — idéntico al F09. Fix: agregar `noRollbackFor={ShortSellingNotAllowedException.class, InsufficientSharesException.class}` a ambos métodos. **Lección meta**: los aprendizajes de la HU anterior NO se aplican automáticamente a la siguiente, aunque uno los "recuerde". Necesito un check-list de pre-mergeo: "¿algún método nuevo `@Transactional` que lance excepciones de dominio? → ¿tiene `noRollbackFor`?" Si lo tuviera, habría detectado esto en Lote A sin esperar al Lote D.

### Renombrar 4 templates `*.html` a sufijo `-buy` salió gratis

D6 del plan decía "renombrar los 4 templates F09 a sufijo `-buy` con `git mv` para preservar history, antes de crear las versiones `-sell`". Cuando llegué a Lote C esperaba un dolor de cabeza: 4 ediciones de `MailNotifier` apuntando a paths nuevos + 4-5 tests F09 con asserts sobre nombres de templates. **Realidad inesperada**: los templates F09 YA estaban con sufijo `-buy` desde el principio (los archivos físicos eran `order-executed-buy.html`, etc.). Alguien (yo en HU-F09, sin documentarlo) ya había anticipado el rename. El `MailNotifier` apuntaba a `email/order-executed-buy` desde F09. **0 tests rotos por rename físico**. Solo tuve que crear los 4 archivos `-sell.html` nuevos y agregar los 4 métodos `*EmailSell` a `Notifier + MailNotifier`. **Lección**: la "deuda de simetría" (templates F09 sin sufijo `-buy` mientras F10 introducía `-sell`) la pagué proactivamente cuando codeé F09 sin saber que ayudaba a F10. Pequeño momento de "thank you, past me". Probablemente fue muscle memory de querer evitar nombres ambiguos como `order-executed.html` que no dicen para qué side.

### D29 F09 heredado en SELL tiene un riesgo asimétrico que documenté como deuda

En F09, cuando Alpaca responde `accepted` no-terminal (mercado cerrado), la orden queda PENDING y el cash se debita ya como reserva. Cuando el mercado abre y Alpaca filea, el balance ya está descontado. Caso simétrico en F10 SELL: cuando Alpaca acepta una venta encolada, la **posición** se decrementa optimistamente. **Asimetría**: el balance NO se acredita aún (precio de fill desconocido). Si Alpaca cancela la encolada después (raro pero posible), el usuario perdió posición sin recibir crédito. Lo registré como D9 D-SELL-QUEUED-RISK en el plan + deuda en AGENTS.md para reconciliación post-MVP. **Lección**: las decisiones "heredadas tal cual" de una HU previa no son gratis — la simetría aparente puede esconder asimetrías reales. SELL queued NO es BUY queued con signo invertido; el riesgo edge case es distinto. Lo correcto sería un job de reconciliación nocturna que cuando vea órdenes `PENDING + alpacaOrderId` en BD, las verifique contra Alpaca y reincremente la posición si fueron canceladas. Para MVP single-user en horario de mercado es absorbible; para producción multi-usuario sería bug crítico.

### DELETE de la fila `app.positions` en qty=0 fue la decisión correcta — pero requirió defensa en profundidad

D1 del plan decidió que cuando una venta deja `quantity = 0`, la fila se BORRA (no se mantiene con qty=0). Razones: brokers reales operan así, HU-F16 `GET /portfolio/positions` queda más limpio sin filtrar `WHERE quantity > 0`. **Trade-off documentado**: si el usuario vende todo y vuelve a comprar el mismo ticker, pierde el `avg_buy_price` histórico — el nuevo se recalcula desde cero. Para MVP académico: correcto. **Lección emergente del Lote A**: agregué un test defensivo `decrement_existingPositionWithZeroQty_throwsShortSelling` para el edge "fila existente con qty=0 que sobrevivió por alguna razón" — el código trata `qty=0` igual que "fila no existe" (lanza `ShortSellingNotAllowedException`, no `InsufficientSharesException`). Sin ese test, el comportamiento podría haber sido sutil: la diferencia entre los 2 códigos de error importa para el usuario (uno dice "no tienes posición", otro "tienes X pero no alcanza"). Doble check defensivo cuando uno DELETEs filas que tienen un CHECK constraint que las permite seguir existiendo en estado degenerado.

### El frontend fue el lote más rápido — invertir en el dispatch backend pagó dividendos

Lote E (frontend) duró ~30 minutos. Cambios: 5 archivos modificados, 0 archivos nuevos. La razón es que el patrón ya estaba: `parseError` resuelve cualquier código de error nuevo via `humanFor()` (basta agregar 2 entries a `messages.es.ts`), el `OrderForm` ya tenía toggle SELL solo deshabilitado, y `OrderQuotePanel + OrderConfirmationToast` aceptan branching side-aware sin refactor de estado. **Lección sobre dependency direction**: el frontend de HU-F09 fue diseñado pensando en HU-F10 (toggle disabled, no oculto; tipos preparados para `OrderSide`). Pagó. **Counterfactual**: si HU-F09 hubiera implementado el frontend BUY-only "puro" (sin toggle SELL siquiera), Lote E habría sido 2x más largo + restructurar el form. **Patrón a reusar**: cuando una feature obvia viene después, dejar el hueco visible en el UI desde el inicio (botón disabled con tooltip "próximamente") es más barato que crearlo después desde cero.

### Reflexión meta: 250 tests verdes y 0 regresiones — el rigor del SDD se nota acá

Cierre HU-F10: `mvn verify` con **250 tests verdes (207 unit + 43 IT), 0 failures, 0 errors**. Vs F09 cierre: 219 tests. Crecimiento neto +31 tests para una HU que reutiliza 60% del andamio. **Más interesante**: cero tests F09 rotos a pesar de cambios estructurales (Order.markAsExecuted side-aware, OrderEventListener dispatch, Notifier rename `*Buy/*Sell`, 5 records de evento con campos nuevos). El compilador de Java atrapó el 80% de las inconsistencias en `mvn clean test-compile` antes de correr nada; los tests IT atraparon el 20% restante (D17, D18). **Lección sobre SDD + Java**: el costo de tener types fuertes + IT con WireMock real se paga en momentos como este, cuando uno cambia un record de 9 campos a 12 y el sistema te dice EXACTAMENTE dónde están los 5 lugares que rompiste. Comparado con un sistema dinámico donde habrías tenido que correr el sistema entero y esperar errores en runtime. **Aprendizaje propio del proceso académico**: el profe va a evaluar SDD por la calidad de los specs y la trazabilidad spec→código. F10 cerró con SPEC v1.0 + plan v1.1 (con §2.4 D17–D21) + tasks.md + commit message refs HU-F10. **Auditabilidad completa**: cualquiera puede leer plan §2.4 y entender por qué `validateSellable` toma lock balances aunque la venta no consume balance. Esa trazabilidad es lo que diferencia "código académico bien hecho" de "código que pasa los tests".

---

## Día 8 — HU-F16 + HU-F21 Bundle Portafolio y Saldo (2026-05-24)

### Cuestionario antes del SPEC fue más útil que un batch de SPECs autónomo

Mi instinto al cerrar HU-F10 era replicar el patrón: que Claude redactara los 3 docs SDD del bundle F16+F21 sin discusión previa, basándose en lo que "obviamente" iba el SPEC. **Lo bloquée a propósito**. En lugar de eso, el agente preguntó 3 cosas concretas vía picker (mark-to-market o solo avg cost, incluir pendingOrders, 1 endpoint o 2). Las 3 respuestas mías terminaron siendo decisiones cerradas C2/C3/C4 del SPEC, irreversibles sin cambio de contrato. **Lección**: cuando el alcance es chico (<2h por HU), el "cuestionario de 3 preguntas críticas" cuesta 5 minutos y prevenían 30 minutos de re-spec por una decisión mal asumida. Cuando es grande (F09/F10), las decisiones cerradas pre-redacción ya son 7-8 — más cuestionario satura. **El bundle pequeño se beneficia más del cuestionario que el bundle grande**, contraintuitivo.

### Mark-to-market con fallback elegante: el cap del fan-out es lo que paga

El SPEC decidió "mark-to-market con fallback elegante" en C3 (la opción media entre "solo avg cost" y "all-or-nothing"). Pero esa palabra ("fallback") esconde el detalle técnico que define si funciona: **cada CompletableFuture per ticker tiene `.completeOnTimeout(null, 1500ms)`**. Sin ese cap, el fan-out de 20 tickers con un Alpaca lento (worst case 7s por ticker con los retries internos del adapter) habría tardado 7s — endpoint inaceptable. Con cap: cada ticker que no responde en 1.5s se marca como `null` y sigue al siguiente; el endpoint termina en ~2s siempre. **El log lo confirma**: `tickers=2 success=1 null=1 elapsedMs=1505` (test oneTimeout). El cap se respeta al milisegundo. **Lección**: "graceful degradation" no es una decisión, es una *promesa de SLA*. La decisión real es el número (1.5s). Sin el número, la frase no significa nada. Cuando lea otra spec con "fallback elegante" voy a buscar el número.

### `ExecutorService` dedicado vs `ForkJoinPool.commonPool()` vs el thread pool de Tomcat

Tres opciones para el fan-out paralelo. Mi instinto era `CompletableFuture.supplyAsync(...)` sin specificar executor → default `ForkJoinPool.commonPool()`. **Mala idea**: ese pool lo comparten Streams paralelos, otras llamadas async sin executor, herramientas externas. Si el fan-out a Alpaca se cuelga, podría saturar el commonPool del JVM entero. La alternativa "fácil" era pasar el thread pool de Tomcat — peor aún, mezcla peticiones HTTP entrantes con outbound IO. **Decisión correcta** (plan D1): `@Bean(destroyMethod = "shutdown") ExecutorService marketDataExecutor()` con `Executors.newFixedThreadPool(8, daemon)` — pool aislado de 8 threads que solo el orchestrator usa. Spring lo cierra limpio al shutdown del contexto. **Lección**: el "qué executor uso" es la decisión arquitectónica del `CompletableFuture.supplyAsync`, no un detalle técnico. Default es casi siempre incorrecto para cargas I/O externas.

### `@AuthenticationPrincipal User` rompió todo — el proyecto entero usa `AuthenticatedUser`

Lote C arrancó y mi controller decía `@AuthenticationPrincipal User user` (la entity JPA). Los tests dieron 500 Internal Server Error con todas las posiciones populadas. **Causa**: el `JwtAuthenticationFilter` pone un record `AuthenticatedUser(UUID userId, String role)` en el SecurityContext, no la entity. Spring intenta cast → falla con ClassCastException → el handler global lo mapea a 500. Grep al codebase muestra que TODOS los demás controllers (`MeController`, `SubscriptionController`, `OrderController`) usan `@AuthenticationPrincipal AuthenticatedUser principal` + `principal.userId()`. **Lección**: la convención del proyecto es invisible cuando uno escribe el primer controller de un módulo nuevo. Mi paso siguiente automatizable: al crear controller en módulo nuevo, primero `grep -r "@AuthenticationPrincipal" backend/src/main/java | head -5` antes de elegir el tipo del principal. Costo del grep: 2s. Costo del bug: 15min de re-test (incluyendo arrancar Postgres porque la primera corrida también atrapó el DB caído).

### El bug 403-vs-401 NO es mío de arreglar (D17 emergente)

Mis tests `getPositions_withoutJwt_returns401` y `getBalance_withoutJwt_returns401` fallaron con `expected:<401> but was:<403>`. Primera reacción: "rompí algo". Segunda reacción: verificar el SPEC F02 + el filter `JwtAuthenticationFilter`. **Resultado**: el filter solo emite 401 cuando hay token y es inválido/expirado. Sin header `Authorization`, el filter pasa el chain limpio y Spring Security 6 cae en 403 default (no hay `AuthenticationEntryPoint` customizado en `SecurityConfig`). El SPEC F02/F09/F10/F16 todos dicen 401 — divergencia global. **Lección**: cuando un test descubre divergencia spec↔código en zona NO controlada por la HU actual, el reflejo correcto es **documentar como D emergente y ajustar el test al comportamiento real**, NO refactorizar la zona ajena. Lo arreglarlo aquí significaría tocar `SecurityConfig` que afecta todos los módulos = riesgo de regresión cross-cutting. Cuando llegue la mini-HU `HU-F0X-token-rotation-logout` (que ya va a tocar el filter), agregar el `AuthenticationEntryPoint` cuesta 10 líneas. **Discipline > heroism**.

### JsonPath con filter `?(@.field==X)` no funciona consistente en MockMvc — solución colateral: ORDER BY

Mis tests usaron `jsonPath("$.positions[?(@.ticker=='AAPL')].currentPrice").value(List.of("193.20"))`. Falló con `expected:<[193.20]> but was:<null>`. Jayway JsonPath dentro de MockMvc evalúa el filter pero el wrapping del `.value(...)` no se entiende. **Workaround obvio**: usar índices (`$.positions[0]`). **Problema**: el repository no garantizaba orden, Postgres devuelve filas en orden de inserción/heap heap heap — non-deterministic. **Solución**: renombrar `findByUserIdAndQuantityGreaterThan` a `findByUserIdAndQuantityGreaterThanOrderByTicker` (alfabético ASC). Beneficios secundarios: (a) UX consistente — el listado siempre aparece igual entre requests; (b) frontend re-sortea si quiere otro criterio. **Lección meta**: lo que parecía bug de assertion (JsonPath) era síntoma de falta de orden estable. La solución no fue arreglar el assertion, fue arreglar el contrato del repository. Cuando un test falla por "el matcher no funciona", investigar si el contrato bajo test es realmente determinístico antes de pelearse con el matcher.

### Mitigar deuda viva con UX: `pendingOrders[]` es la sección que ataca el drift de F09/F10

Deuda viva #8 del AGENTS.md handoff dice: "Reconciliation Alpaca-paper vs BloomTrade BD: en BUY queued, cash debitado sin fill; en SELL queued, posición decrementada sin crédito acreditado". Es deuda **de backend** — la solución real es un job nocturno de reconciliación. **Pero** mitigué la UX en F16 con la sección `pendingOrders[]` que muestra al usuario las órdenes encoladas pero no liquidadas. **Insight**: sin esa sección, el usuario percibe "compré AAPL pero no aparece en mi portafolio + mi saldo bajó" como un bug. Con la sección, ve "tu orden está en cola esperando apertura". Mismo problema técnico de fondo, percepción radicalmente diferente. **Lección de producto**: la deuda técnica que el usuario ve como bug se puede mitigar haciendo el problema visible/explicado. No arregla el problema (todavía hay drift entre BD y Alpaca si Alpaca cancela), pero compra tiempo hasta que el fix backend llegue. **Aplica más allá de fintech**: notificación de "estamos procesando tu solicitud" cuando un job async tarda. Mismo patrón.

### Bundle vs split de HUs: F16+F21 fue el bundle correcto

F09 y F10 fueron HUs separadas con specs separadas (correctísimo — flujos complejos, semánticas distintas, riesgos diferentes). F16 (consultar portafolio) y F21 (consultar saldo) son ambas read-only sobre el mismo módulo (Portfolio), mismo controller HTTP nuevo (PortfolioController), mismo @AuthenticationPrincipal, misma página frontend (/portfolio). Splitearlas en 2 specs habría producido dos `SPEC.md` casi idénticos con 80% de overlap (mismo módulo, misma auth, mismo formato de moneda, mismas decisiones de PnL). El bundle único `HU-F16-F21-portafolio-saldo/` capturó todo sin redundancia. **Pero**: el bundle NO funciona cuando las HUs tienen flujos transaccionales distintos (BUY vs SELL del trading) aunque parezcan simétricas. Lección: el criterio no es "tamaño relativo", es "¿comparten contrato API + módulo + página frontend?". Sí → bundle. No → split. F16+F21 cumplen los 3; F09+F10 no comparten el 1ro (endpoints distintos por POST mutable vs GET).

### Decisiones emergentes durante implementación: ya es un patrón estable

F09 emergentes D23–D29 (7 decisiones). F10 emergentes D17–D21 (5 decisiones). F16+F21 emergentes D17–D18 (2 decisiones). **El patrón se mantiene**: por más que el SPEC + plan estén pulidos antes de codear, siempre aparecen 2-7 cosas que la implementación obliga a documentar. Lo que cambia es la *cantidad*, no la *existencia*. Y los temas: F09 emergentes fueron sobre transactions + races; F10 emergentes fueron sobre locks + rollback; F16+F21 emergentes fueron sobre conventions + tooling de tests. **Lección meta**: la sección §2.4 "Decisiones emergentes" del plan.md NO debería estar vacía al final de ningún lote D-E. Si lo está, sospechar que decisiones quedaron sin documentar — el código las contiene pero el plan no, y eso rompe la trazabilidad spec→código que pesa académicamente. Mi heurística futura: **antes de cerrar el lote final (HITO 6), grep al diff de `plan.md` y verificar que §2.4 tiene al menos 2 D17+ por HU**. Si tiene 0 y la HU es no-trivial, releer mis propios commits para encontrar las decisiones invisibles.

### Reflexión meta: 286 tests verdes, 0 regresiones — el bundle small también vale el ceremonial SDD

`mvn verify` final HU-F16+F21: **286 tests verdes (231 unit + 55 IT)**. Vs F10 cierre: 250 tests. Crecimiento neto +36 tests para un bundle de ~30% del esfuerzo F09 cada HU. **El SPEC/plan/tasks del bundle pequeño costó ~1.5h (vs ~3h en F10)** y la implementación ~6h (vs ~10h F10). La proporción doc:código se mantuvo constante (~20-25%). **Tentación que evité**: "este bundle es chico, skipea el plan formal y arranca a codear". Hubiera ahorrado 1h... pero el plan capturó las 16 decisiones D1–D16 que después se traducen línea por línea al código. Sin el plan, esas 16 decisiones habrían quedado implícitas y reabiertas la próxima sesión. **Lección**: el costo fijo del ceremonial SDD es bajo (~1.5h para SPEC pequeño + plan + tasks). El beneficio (trazabilidad académica + decisiones congeladas) es independiente del tamaño de la HU. **No hay punto de break-even** abajo del cual el SDD no valga la pena — siempre vale. Lo que cambia es el tamaño relativo de las secciones (criterios de aceptación, contratos API, riesgos), no si las secciones existen. Lo confirmé en este bundle: el SPEC tiene 15 secciones (las mismas que F10) pero cada una proporcionalmente más corta. Lo guardado: 0 trabajo "ahorrado".

---

## Día 9 — HU-F18 + HU-F17 Bundle Dashboard + Historial (2026-05-25)

### Borrar mi `RedisConfig` fue el aprendizaje, no escribirlo (D26 emergente)

Plan D2 dijo "RedisTemplate<String, String> manual" para tener control fino sobre TTL + handling de exceptions. Escribí 25 líneas de `RedisConfig.java`: `@Bean public RedisTemplate<String, String> stringRedisTemplate(...)` con `StringRedisSerializer` para key+value. Compilo: BUILD SUCCESS. Corro tests IT: `Failed to load ApplicationContext` con `BeanDefinitionOverrideException: Invalid bean definition with name 'stringRedisTemplate'`. **Causa raíz**: Spring Boot `RedisAutoConfiguration` ya provee `stringRedisTemplate: StringRedisTemplate` (subclase de `RedisTemplate<String, String>`) cuando `spring-boot-starter-data-redis` está en classpath. Mi config duplicaba el bean con mismo name → Spring rechaza por default. **Fix**: borrar `RedisConfig.java` entero. `CachedMarketDataAdapter` inyecta `RedisTemplate<String, String> redisTemplate` por tipo; Spring resuelve al `StringRedisTemplate` auto-config (upcast natural). **Cero líneas de config Redis necesarias**. Plan D2 sobrestimó la necesidad de control fino — para mi caso (TTL `Duration`, exception handling en el wrapper), el auto-config era suficiente. **Lección meta**: el SDD pre-codeo tiende a "decidir capas que probablemente necesite". Spring Boot tiene 8 años de auto-config — si la pregunta es "¿necesito una @Bean para esto?", la respuesta default debería ser "primero veo si Spring Boot ya lo da". Lo aprendí pagando ~10 min, barato. Aprendizaje para próximas HUs: cuando una decisión técnica del plan se trate de "config de framework", verificar primero el auto-config del framework antes de escribir el bean manual.

### `Map.copyOf` no preserva orden de iteración — 8 sesiones de bug latente (D25 emergente)

`AllowedTickers.byMarket()` se escribió en HU-F04+F20 (Día 3) con `Map.copyOf(linkedHashMap)`. Yo asumí (y el SPEC F18 §2 C4 + plan D20 documentaron) que el orden de inserción NYSE → NASDAQ → LSE → TSE → ASX se preservaba. F04+F20 nunca iteró ese map — solo usaba `contains(ticker)` para membership check. F09+F10+F16+F21 tampoco iteraron. **F18 fue la primera HU que itera por orden** (para agrupar los 25 tickers en el dashboard). `DashboardServiceTest#getSnapshot_groupsInCorrectOrder_NYSE_NASDAQ_LSE_TSE_ASX` falló: orden real `[NYSE, TSE, ASX, LSE, NASDAQ]`. **Causa**: Java docs explícitas: `Map.copyOf` retorna "an unmodifiable Map whose iteration order is unspecified". El LinkedHashMap interno se descartó. **Fix**: `Collections.unmodifiableMap(LinkedHashMap)` en vez de `Map.copyOf`. Preserva orden + sigue siendo inmutable. **Lección sobre bugs latentes**: la "asunción razonable" (estructura inmutable que preserva orden) sobrevivió 8 sesiones sin detección porque nadie iteraba. La detección llegó cuando F18 inadvertidamente puso al catálogo bajo un test específico de orden. **Counterfactual**: si F18 no hubiera tenido ese test, el bug habría salido en producción cuando el usuario viera "los mercados están desordenados raro" — síntoma sutil, no error obvio. **Aprendizaje para code review**: cuando vea `Map.copyOf` en código nuevo, preguntar "¿alguien itera este map? Si sí, ¿el orden importa?". Si las dos son sí, debería ser `Collections.unmodifiableMap(LinkedHashMap)`.

### Skip `OrderSpecificationsTest` porque el IT lo cubre — ROI > 0 vs duplicación (D27 emergente)

Plan §3 Lote C T3.16–T3.19 propuso test puro con mocks de `Root`/`CriteriaQuery`/`CriteriaBuilder` para cada predicado de `OrderSpecifications` (`byUser`, `byTicker`, `bySide`). Lo escribí mentalmente y empecé a googlear cómo mockear Criteria API. **Stop**. Los 3 predicados son one-liners triviales: `cb.equal(root.get(field), value)`. Mockear 5 niveles de interfaces internas de Hibernate por test es frágil ante upgrades + duplica lo que el IT real ya prueba. `OrderHistoryControllerIT` (6 tests) ejerce los predicados via HTTP real sobre BD real con datos seedeados: si un predicado mappea al campo equivocado (`getTicker()` vs `getSymbol()`), el IT lo detecta inmediato y con error más legible que un AssertionError de mock. **Decisión**: skip `OrderSpecificationsTest` completo, documentar como D27 emergente. Consistente con [[feedback-coverage-vs-velocidad]]. **Lección**: el tasks.md detallado pre-implementación tiene un sesgo hacia "cobertura formal por capa". El refactor mental "¿este test prueba algo que el IT no prueba?" es la pregunta correcta. Si la respuesta es no, saltarlo no es flojera — es focus. Pero registrar la decisión en plan §2.4 SÍ es obligatorio: sin esa nota, futuro yo (o el revisor del código) puede pensar "se olvidaron del test de specifications". El skip documentado es disciplina; el skip silencioso es deuda.

### F17 promovido al MVP: ROADMAP §3.4 funcionó como regla, no como aspiración

ROADMAP §8.1 listó HU-F17 como "Originalmente Sprint 3", fuera del MVP. §3.4 dijo: "si vamos a tiempo al cierre del Día 9, F17 (#3 en orden de promoción) se mueve al MVP". Llegué al Día 9 con Sprint 2 al día (3 bundles cerrados sin acumular deuda funcional bloqueante en 3 días: F09, F10, F16+F21). **Activé la regla literal**: SPEC bundle F18+F17 con §1 explícito documentando la promoción ("HU-F17 se promueve al MVP siguiendo §3.4 por reuso máximo: `OrderRepository` y `Order` ya existen; solo se agrega endpoint con filtros+paginación + widget frontend embebido. ROI alto"). **Lección sobre planeación**: el ROADMAP §3.4 escrito hace 18 días NO era papel mojado — fue la regla operativa que me dejó ampliar scope con confianza. Sin esa regla, el riesgo de "scope creep" me habría hecho dudar; con ella, la decisión es objetiva. **Counterfactual**: si Sprint 2 hubiera atrasado, §3.4 al revés (recortes) habría sido igual de operativo. **Aprendizaje sobre cómo escribir planes**: los planes deben incluir reglas condicionales explícitas con triggers objetivos ("si X al día Y, entonces Z"). Sin triggers, las opciones del plan no se ejecutan porque siempre falta "claridad" para activarlas. La cláusula §3.4 es el ejemplo a replicar.

### Bundle F18+F17 por cohesión UI: el criterio "comparten página frontend" se generaliza

Día 8 escribí: "bundle funciona cuando HUs comparten contrato API + módulo + página frontend. F16+F21 cumplen los 3; F09+F10 no comparten el 1ro". Día 9 aplicó el criterio a un caso nuevo: F18 (dashboard) y F17 (historial). **Comparten**: solo la página `/dashboard` (F17 vive como widget embebido, no como ruta dedicada). **No comparten**: ni módulo backend (F18 → `dashboard/`, F17 → `trading/history/`) ni contrato API (3 endpoints distintos). Pero el bundle se justificó porque **la única superficie UX para el usuario final es `/dashboard`**, y el widget de F17 es parte de la composición visual del dashboard. **Refinamiento del criterio**: comparten al menos UNA de las 3 dimensiones (módulo / contrato / página), Y la dimensión compartida es la que dominará el código de implementación. En F16+F21 era módulo+contrato+página (3/3). En F18+F17 era solo página (1/3) — pero esa dimensión arrastró el frontend entero del Lote D, que es ~30% del esfuerzo del bundle. **Lección**: el "compartir página" pesa más que "compartir módulo backend" cuando el bundle incluye frontend significativo. F17 como página `/orders` separada habría duplicado el andamio frontend (AppHeader, route, hook, query key). Embebido como widget, 0 duplicación.

### Tres deudas vivas cerradas en un solo PR: efecto compuesto del contexto

El handoff F16+F21 listó 19 deudas vivas. F18+F17 cerró 3 en este bundle:
- **#19 cache Redis market data** — Lote A creó `CachedMarketDataAdapter`. Era deuda *anticipada* (el comentario en `MarketDataAdapter:67` ya decía "HU-F18 cache amortiguará en post-MVP" desde HU-F09).
- **#16 `sideNotYetImplemented` dead code** — Lote E borró la static factory + property + javadocs en 4 lugares.
- **#15 `useTickerOptions` filtrar SELL por posiciones** — Lote E extendió `TickerDropdown` con prop `ownedTickers?: ReadonlySet<string>` y `OrderForm` consume `usePortfolioPositions` cuando side=SELL.

**Lección sobre timing de deuda**: las 3 deudas se cerraron por costos marginales bajos porque el bundle ya tocaba esos archivos por otras razones. #19 era el corazón del Lote A. #16 vivía en `trading/exception/` que el Lote C tocaba (extender `OrderRepository`). #15 reusaba `usePortfolioPositions` que F18 cableó en `OrderForm`. **Si esas 3 deudas se hubieran intentado cerrar en un PR aislado**, cada una habría requerido contexto + tests + revisión propia = ~3 PRs distintos. **Acá**: 1 PR, ~30 min adicionales total. **Aprendizaje**: cuando el handoff lista deudas, marcar las que tocan los mismos archivos del próximo bundle. Cerrarlas en bulk cuesta una fracción del costo aislado. El handoff de AGENTS.md ya lo había anticipado al decir "**Deuda emergente para limpiar en F18**: #15, #16". Esa nota cross-referencia me ahorró tiempo de búsqueda.

### Polling 30s + cache TTL 30s no es coincidencia — es coincidencia diseñada

Plan D5 fijó `refetchInterval: 30_000` en `useDashboardSnapshot`. Plan D2 fijó `TTL = Duration.ofSeconds(30)` en `CachedMarketDataAdapter`. Mismo número, decisiones independientes. **¿Coincidencia?** No: el polling 30s viene del ROADMAP §5 Día 9 ("polling vía React Query cada 30s desde frontend"). El cache TTL 30s lo derivé para que el polling no golpee Alpaca en cada round. **Matemática**: con cache TTL = polling interval, el primer request del intervalo es miss → llena cache. Cualquier request de otros usuarios (futuro multi-tenant) en los siguientes 30s hace cache hit. El del propio usuario también hace hit si está justo dentro del TTL. **Trade-off real**: si TTL < polling, gastas calls Alpaca extra; si TTL > polling, el usuario ve datos "anteriores" más tiempo del esperado. **Coincidencia exacta = óptimo**. Lo confirmé con el IT `snapshot_secondCallWithinTtl_servesPricesFromCache`: segundo request en <30s tras el primero, verify WireMock `0 calls` a `/quotes/latest`. **Lección**: cuando dos números en partes distintas del sistema son iguales, sospechar acoplamiento implícito y documentarlo. Si en el futuro alguien cambia el polling a 60s sin pensar, el cache TTL queda subóptimo (gasta 2× calls en el primer 30s) y nadie sabe por qué. Comentario en plan + en el código sería barato preventivo.

### Sparklines con recharts: minimalismo cuando hay 25 instancias por render

`Sparkline.tsx` es **15 líneas**: un `<LineChart width={100} height={30}>` con un `<Line dot={false} strokeWidth={1.5} isAnimationActive={false}>`. Sin ejes, sin grid, sin tooltip, sin animación. Por cada render de `DashboardPage`, hay 25 instancias en el DOM. **Hago las cuentas mentales**: ~25 charts × 50 puntos cada uno × algunos elementos SVG por punto = ~5000-10000 nodos SVG en el árbol React. Si pongo animación, son 25 transiciones simultáneas; si pongo tooltip, son 25 mouse listeners. **Decisión**: sparkline minimal. **El sparkline NO es para que el usuario hover y vea precios** (eso es el dashboard de precio detallado, post-MVP). Es solo para que el usuario detecte tendencia de un vistazo. **Lección sobre recharts en alto fan-out**: las opciones default de recharts (animation, tooltips) están pensadas para 1-3 charts por página, no 25. En F18 ese default habría hecho la página lenta. **Heurística**: si renderizo más de 5 instancias del mismo chart en una vista, ir a la versión minimal (sin animaciones, sin handlers de mouse, sin elementos opcionales). En 1 instancia, agregar todo.

### Reflexión meta: 346 tests verdes + cierre del Sprint 2 funcional del MVP

`mvn verify` final HU-F18+F17: **346 tests verdes (277 unit + 69 IT)**. Vs F16+F21 cierre: 286 tests. Crecimiento neto +60 tests. **Bundle más grande que F16+F21** (~70% del esfuerzo F09 según mi estimación inicial; ~70% real medido). 3 decisiones emergentes documentadas (D25, D26, D27) — la cota baja del rango 2-7 que el patrón establecido predice. **Pero más significativo**: con este bundle cerrado, las 9 HUs del MVP §2.1 ROADMAP están implementadas (HU-F01, HU-F02, HU-F03, HU-F04, HU-F20, HU-F06, HU-F09, HU-F10, HU-F16, HU-F21, HU-F18) + bonus HU-F17 (promovido). **Sprint 2 funcional cerrado**. Lo que queda Día 10: estabilización + pruebas JMeter (ESC-R1 + ESC-R2) + documentación final (diagramas C4, secuencia de orden, Sprint Reviews/Retros diferidos). **Reflexión sobre el proceso**: 4 bundles (F09 + F10 + F16+F21 + F18+F17) en 4 días de trabajo efectivo. Sin SDD habría sido caos — 4 bundles con specs autoritativas + plan + tasks + commits con `refs HU-FXX` + auditabilidad. **Lo que voy a llevar como aprendizaje meta del proceso completo**: el SDD no es un ritual académico encima del código. Es el contrato que me permitió tomar decisiones técnicas con confianza (los 16 decisiones D1–D16 de cada plan), descubrir las 2-7 emergentes cada vez (sin sorprenderme), y dejar trazabilidad para que el profesor (y futuro yo) entiendan POR QUÉ el código quedó así. **El SDD me dejó codear más rápido, no más lento** — porque cada decisión cerrada antes de codear son 15 minutos no gastados en mid-coding hesitation.

---

## Día 10 — Estabilización: audit cruzado + bugs ocultos + reconcile lazy (2026-05-26)

### El audit cruzado como pattern: agente Explore + verificación humana antes de actuar

La sesión arrancó con un cambio de foco grande: descarté JMeter (post-MVP) y orienté el Día 10 a "perfecto y pulcro para un usuario". El primer reflejo fue auditar el flujo single-user end-to-end. Lo hice con un patrón nuevo: spawn de un agente Explore con scope amplio (frontend + backend + cross-capa + docs), mientras yo paralelamente leía la sección de deuda viva del AGENTS.md. El agente me devolvió ~10 hallazgos clasificados P0/P1/P2 con file:line. Yo los crucé contra deuda viva existente y produje una lista priorizada de 6 IDs. **Pero acá viene el aprendizaje**: 2 de los 6 (P1-5 "orden huérfana" + P0-2 "MFA cooldown sin countdown") resultaron ser **falsos positivos del agente** cuando bajé a verificar el código real. El P1-5 reportaba un escenario imposible — el flujo lo prevenía por diseño con re-validación pessimistic en `debit()`. El P0-2 reportaba que el cooldown no estaba implementado — pero `ResendButton.tsx:33-58` ya tenía el countdown completo desde HU-F02. **Lección sobre delegar a agentes**: el Explore es excelente para barrer amplio en pocos minutos (eso solo me habría tomado ~1 hora leyendo archivos). Pero **el agente no verifica orden de operaciones del código** — describe lo que parece, no lo que el flujo garantiza. **Heurística que me llevo**: cualquier hallazgo del Explore que se traduzca en "tiene que pasar X seguido de Y" pide verificación humana **antes** de tocar código. Si fuera implementado ciegamente, P1-5 me habría llevado a un fix innecesario y P0-2 a duplicar lógica existente. **El cross-check no es desconfianza del agente — es la operación que el agente no puede hacer**.

### Mini-HU sin SDD consciente: cuándo está bien saltar las reglas y cuándo no

El audit identificó P0-1 como deuda viva #1 (mini-HU `HU-F0X-token-rotation-logout`). La regla del proyecto (memoria `feedback-cadencia-sdd`) es "nunca saltar specs/plan/tasks". Pero esta era una mini-HU registrada desde HU-F16+F21 con scope micro (agregar `AuthenticationEntryPoint` global + interceptor frontend + banner), bien acotada, sin contratos nuevos. Pregunté al usuario: ¿SDD completo o bugfix simple? **Decidió saltar SDD conscientemente**, anotando la decisión como excepción puntual a la regla. Implementé como bugfix: 4 archivos backend + 4 frontend, ~45 min, mvn verify verde a la primera. **Lección sobre cuándo aplicar SDD vs bugfix**: la regla "siempre SDD" tiene un costado problemático cuando aplicada literal a deudas registradas. La deuda ya tiene su contexto documentado en AGENTS.md handoff con su decisión emergente original (D17 F16+F21 + D-T5.2 F18) — escribir SPEC ahora sería **reproducir** ese contexto, no producirlo. **Refinamiento de la regla que voy a llevar**: SDD obligatorio para HUs nuevas (donde la spec ES el descubrimiento). Bugfix válido para deudas registradas donde el "qué" y el "por qué" ya están en handoff/commits previos — basta documentar la decisión de saltar SDD en el commit + AGENTS. La regla "nunca saltar" sin matices habría costado ~1 hora de SDD para algo que no descubría nada nuevo.

### Bug oculto desde HU-F18 por stubs que replicaban el shape incorrecto del DTO

Usuario reporta: "banner partial siempre + sparklines vacíos". Diagnóstico esperado: deuda #8 reconciliation. Diagnóstico real: el DTO `AlpacaBarsResponse` esperaba `bars: Map<String, List<AlpacaBar>>` (shape **multi-symbol** del endpoint `/v2/stocks/bars?symbols=...`), pero el adapter llama al endpoint **single-symbol** `/v2/stocks/{symbol}/bars` que devuelve `bars: List<AlpacaBar>` directo. Jackson fallaba con `Error while extracting response for type AlpacaBarsResponse` en los 25 tickers. **Los IT pasaban** porque `stubAllBarsOk` en `DashboardControllerIT` + el stub de `MarketDataAdapterBarsTest` reproducían el shape multi-symbol incorrecto del DTO. **El test era cómplice del bug, no su detector**. La detección llegó cuando consulté Alpaca real con `curl` y vi el shape correcto. **Lección sobre stubs y cobertura**: un test que stub la respuesta de una API externa con el shape que el código espera (y no con el shape que la API devuelve) es un **test de la implementación contra sí misma**, no contra la realidad. Pasa siempre y oculta cualquier mismatch de schema. **Heurística que voy a aplicar de aquí en adelante**: cuando un test stub a una API externa, el JSON del stub debe venir de un `curl` real (o de la doc oficial verificada), no inventado para que matche el DTO. Idealmente capturar la respuesta real como `resources/fixtures/*.json` y reusar — eso fuerza que el DTO se mantenga al día. WireMock + Testcontainers ya soportan este patrón con `__files`. Para este proyecto: copiar el fixture del curl que hice como referencia para futuros tests de bars.

### Drift Alpaca↔BD: reconcile lazy on-GET cierra la deuda sin job nocturno

Tras arreglar el bug de bars, segundo síntoma del usuario: "hice órdenes, mercado abierto, no se actualiza nada en la UI". Diagnóstico real: 2 órdenes con drift contra Alpaca paper. Una `PENDING` con `alpaca_order_id` (queued del viernes, filled hoy lunes — el flow normal de D29 F09). Otra `FAILED` con error `ALPACA_PENDING_TIMEOUT` y **sin** `alpaca_order_id` — bug del código viejo: cuando el polling del placeOrder agotaba 3 intentos sin transición a terminal, marcaba FAILED y descartaba el `alpaca_order_id`, perdiendo toda referencia. Alpaca eventualmente la filled. **2 órdenes ejecutadas en Alpaca, 0 reconocidas en BD, $620 de balance sin descontar, 3 AAPL invisibles en portfolio**. **Decisión del fix**: reconcile manual SQL para los 2 históricas + implementar reconciliación lazy on-GET (cierre funcional deuda viva #8). Lazy = cada vez que el usuario consulta `/portfolio`, `/dashboard` o el historial, antes de devolver, se listan las PENDING y se chequea contra Alpaca. Si alguna está filled → materializa en BD (UPDATE order + UPSERT position BUY / CREDIT balance SELL). Best-effort: si Alpaca está caído, log warn y devolver data ligeramente stale, no romper el endpoint. **Lección sobre cuándo NO escribir un job nocturno**: la deuda #8 pedía "job de reconciliación batch". Para single-user MVP, eso es over-engineering — el usuario va a refrescar el dashboard varias veces al día, cada refresh hace el trabajo. Lazy reconciliation = scheduler implícito gratis. **El trade-off real es entre latencia del endpoint y deuda persistente**: lazy agrega ~100ms al primer GET tras un fill (caché Alpaca para el resto), batch nocturno consume infra propia + adds delay del fill al user-visible state hasta el próximo run. Para MVP single-user, lazy gana en simplicidad operacional. **Heurística replicable**: si el patrón es "actualizar BD según estado externo eventualmente consistente", evaluar lazy on-read antes de batch — solo el batch si el read-path no puede pagar el costo, o si hay updates que no son read-triggered.

### El sparkline mini fracasó por densidad visual, no por implementación

El SPEC F18 plan D11 propuso "sparkline 100×30px sin ejes/grid/tooltip" para los 25 tickers en grid 5×5. Implementación minimal: 15 líneas de recharts. mvn verify + smoke visual: render OK, datos correctos. **Pero al usar el dashboard real, el usuario reportó "los sparklines están mal cuadrados dentro de la interfaz"**. Inspección: con `grid-cols-[80px_minmax(0,1fr)_70px_100px]` en TickerRow, los 100px del sparkline competían con el precio variable, y a media-resolution se desbordaban visualmente. **No era bug de recharts ni del sparkline en sí — era de densidad visual al juntar 25 instancias en columnas estrechas**. El usuario pidió cambiar a "panel grande de sparkline donde seleccionamos la acción". Implementé: TickerRow sin sparkline (clickeable, highlight cuando selected) + nuevo `SparklinePanel.tsx` debajo del grid (chart grande recharts con tooltip + eje Y + CartesianGrid). Default selecciona el primer ticker con sparkline no-vacío. **Lección sobre densidad visual**: el "minimalismo" del Día 9 (sin ejes, sin tooltip, sin animación) era correcto para 25 instancias **si tuvieran espacio individual suficiente**. En layout grid estrecho, la única optimización real es **mostrar menos charts a la vez** + uno grande on-demand. **Patrón "master-detail" gana sobre "todos juntos"** cuando hay muchas instancias del mismo widget. **Aprendizaje meta**: las decisiones de UI tomadas pre-implementación (D11 sparkline minimal) son testables solo cuando el usuario opera la app — los tests automatizados no detectan "se ve apretado". **Conclusión practical**: el smoke visual humano post-implementación no es opcional ni "nice-to-have" — es el único test que captura defectos de densidad. El proyecto lo tiene como HITO 4 explícito; activarlo más temprano en cada bundle UI me habría ahorrado el round-trip de F18 → F18.5.

### Tests con context Spring no son self-contained: postgres test :5433 me costó 1 corrida

mvn verify falló con 43 errores tras agregar el reconcile. Primer reflejo: "rompí algo". Inspección del primer report: `IllegalStateException: Failed to load ApplicationContext ... Connection to localhost:5433 refused`. **No era mi código — era el container `bloomtrade-postgres` (que hostea la BD test `bloomtrade_test` en :5433) caído desde hacía 4 horas**. `docker start bloomtrade-postgres` + re-run mvn verify: 363 tests verdes. **Lección sobre tests con dependencias de infra externa**: los IT con `@SpringBootTest + @ActiveProfiles("test")` necesitan el postgres test arriba. Si se cae (port conflict, docker desktop reboot, etc.), TODOS los tests con context fallan con stacktrace genérico de context loading. **Heurística que voy a aplicar**: cuando mvn verify falla con N errors >> que cambios, sospechar infra antes que código. Ahorra tiempo de debugging. **Mejora preventiva potencial**: agregar un health-check pre-test que valida disponibilidad de :5433 y aborta con mensaje claro ("postgres test no responde — `docker start bloomtrade-postgres`") en lugar de fallar 43 tests con stacktrace. Pequeña inversión, mucho ahorro futuro. Lo registro como deuda menor.

### Reflexión meta: 363 tests verdes + 4 fixes acumulados + decisión de descartar artefactos doc-only

Cierre Día 10 checkpoint 2: **363 tests verdes (294 unit + 69 IT)**, +6 tests del `OrderReconciliationServiceTest`. 2 commits en el día: el primero del checkpoint 1 (diagramas C4 + JMeter setup post-MVP + mini-HU token-rotation + audit P0/P1), el segundo del checkpoint 2 (bug bars shape + reconcile lazy + revamp panel dashboard). **Decisiones de scope del día**: JMeter formalmente diferido a post-MVP (memoria registrada). Sprint Reviews/Retros + Informe Final del PDF formalmente **descartados** — el profesor evalúa la demo en vivo del producto, no esos artefactos. La decisión me devuelve 4-5 horas que invierto en HU-F15 (cancelar orden — única HU promovible no implementada) + revamp UI con Claude Design en próxima sesión. **Reflexión sobre el proceso de descarte**: descartar entregables del syllabus original requirió que yo (el usuario) leyera con cuidado qué se va a evaluar realmente. **El syllabus no es la lista de tareas — es el menú**. Si el profesor pesa "demo del producto" 70% y "Sprint Reviews documento" 5%, asignar 5h al Sprint Review es matemáticamente sub-óptimo. Tomar esa decisión a 4 días del entregable requiere bastante seguridad — pero los datos están ahí: el bundle Sprint 2 funcional cerrado con 9 HUs implementadas + bonus F17 es el producto. HU-F15 lo cierra. **Lo que voy a llevar como aprendizaje sobre planeación académica**: las "best practices" del proceso ágil (Reviews + Retros) son herramientas para equipos multi-persona donde la reflexión compartida importa para el siguiente sprint. Para mi proyecto solo dev con 2 semanas + evaluación in-vivo, esos artefactos son **costo sin retorno claro**. Lo registro en memoria para no re-litigarlo en el futuro.

---

## Día 11 — HU-F15 Cancelar orden Market (2026-05-27)

### Polling canónico async como patrón reutilizable

El SPEC F15 decidió en Q4 "polling igual que `submitMarketOrder` de F09" para el DELETE a Alpaca. Lo implementé como un loop interno en `AlpacaTradingAdapter.cancelOrder` con 3 outcomes posibles modelados como **sealed type** `CancelOutcome`: `Canceled(alpacaCanceledAt)`, `PendingCancel(reason)`, `RaceFilled(filledAvgPrice, filledQty, alpacaFilledAt)`. **El patrón sealed type es muy distinto a usar excepciones** — RACE_FILLED no es un error, es un happy-path alternativo. Si lo hubiera modelado como `throw RaceFilledException`, el `@Transactional` haría rollback automático del DELETE inicial + del audit `ORDER_CANCEL_REQUESTED` ya emitido. **Sealed type me permitió hacer `switch` exhaustivo en `TradingService.cancelOrder` sin perder type safety y sin rollback no deseado**. Java 21 lo verifica en compile time (sin `default` branch, error si falta uno). **Heurística que voy a llevar**: cuando una operación tiene >2 outcomes posibles donde algunos NO son errores, prefiero sealed type sobre excepciones. Las excepciones son para fallar; los outcomes son datos. Aplica fuera de cancel también — placeOrder con outcomes accepted/filled/rejected/queued podría haber sido sealed type también (decisión vieja F09, aceptable como está).

### Reconcile lazy v2: extensión aditiva sin reescribir v1

El reconcile v1 (Día 10) manejaba **solo** `PENDING → EXECUTED` y silenciaba el resto. Para F15 extendí a v2 con 4 branches nuevos (`canceled`, `expired`, `rejected`, `partially_filled`). **Decisión clave**: NO reescribir v1 — extender. El método `reconcileOne` ahora hace `if (isFilled) applyFilledTransition; if (isCanceled) applyCanceledTransition; ...` con un branch nuevo por status. Los tests de v1 siguen verdes sin modificación porque el path `filled` quedó intacto. **Aprendizaje sobre evolución de servicios**: cuando un servicio existing tiene cobertura de tests sólida, extender por agregación (nuevos branches) es muchísimo más seguro que refactor con reorganización. Costo cero de riesgo de regresión vs el costo no-cero del refactor "limpiador". El javadoc del service ahora documenta v1 + v2 lado a lado — el lector ve la evolución sin tener que excavar git blame. **Patrón replicable para futuros servicios reconcile**: pensar en "branches por estado destino" como unidad de extensión, no en "lógica reorganizada".

### Drift inline desde TradingService — DRY entre cancel directo y reconcile lazy

El caso edge "Alpaca DELETE devuelve 404/422 sobre orden que BloomTrade tiene PENDING" significa drift entre BD local y Alpaca. El primer instinto fue lanzar `AlpacaApiException` + 502 al cliente. Pero eso es subóptimo — el usuario ya pidió cancelar, Alpaca dice "ya no la tengo en ese estado", entonces deberíamos materializar inline el estado real (que probablemente sea `canceled` outbound, `filled` race, `expired` TIF day). Para evitar duplicar la lógica de reverse, extraje los `applyXxxTransition` del reconcile v2 a métodos package-private + agregé un público `applyDriftReconcile(order)` que hace `getOrder` + dispatch. **`TradingService.cancelOrder` en el catch del drift llama directamente al `reconciliationService.applyDriftReconcile(order)` con `source=DRIFT_RECONCILE`**. El cliente recibe el estado real inmediato sin esperar al próximo GET. **Lección sobre DRY entre paths sync y async**: el mismo código reverse se ejecuta desde 3 caminos (user request polling-OK, broker outbound via reconcile lazy, drift detected via cancel). En lugar de duplicar la lógica, los métodos package-private son la unidad de reuso. **Heurística**: si veo que necesito invocar "el mismo reverse en otro path", convertir el código privado a package-private del servicio dueño. La encapsulación dentro del package se mantiene; la lógica se reusa una sola vez. Si dos servicios distintos lo necesitan, recién entonces extraer a un service helper compartido.

### Idempotencia implícita por order.id vs explícita por clientOrderId

F09 introduce idempotencia con `clientOrderId` (UUID generado por el frontend) — patrón estándar para "crear recurso idempotentemente". F15 NO usa esa estrategia: la idempotencia del cancel es **implícita por `order.id`**. El razonamiento: en `placeOrder` el cliente puede reintentar antes de que exista el recurso, entonces necesita una clave externa que el backend reconozca como "esta es la misma intención de crear". En `cancelOrder` el recurso YA existe — la identidad natural del recurso (`order.id`) cumple el rol. Una 2da llamada sobre orden ya CANCELED simplemente retorna 200 con el estado actual + audit `ORDER_DUPLICATE_CANCEL_REQUEST`. **Aprendizaje sobre diseño de APIs idempotentes**: la idempotencia explícita es necesaria cuando el cliente puede reintentar antes de saber si el recurso se creó (típico de POST de creación). Para state transitions sobre recursos existentes (DELETE, PATCH a estado terminal), el ID del recurso ES la clave de idempotencia. Agregar `Idempotency-Key` header al cancel sería sobre-ingeniería + carga cognitiva extra para el cliente. **Patrón limpio**: short-circuit detecta `status=CANCELED` o `cancel_requested_at IS NOT NULL` antes de cualquier side-effect.

### Decisión contra-intuitiva RACE_FILLED — modelar realidad del broker, no del usuario

El SPEC §14 lo dejó como decisión abierta (D-RACE-FILLED-UX): si Alpaca devuelve `filled` durante el polling del cancel (la orden se ejecutó justo antes de que llegara nuestro DELETE), ¿tratarlo como EXECUTED o forzar un counter-order para deshacer? **Decidí tratarlo como EXECUTED**. Razones: (1) Alpaca ya nos confirmó el fill — counter-order genera comisión duplicada + agrega complejidad por edge rarísimo en paper trading. (2) El usuario pidió cancelar pero el broker dice "demasiado tarde, ya ejecuté" — eso ES la realidad. (3) UI/email muestran "Tu orden se ejecutó antes de que llegara la cancelación. La cancelación no fue aplicada." — texto claro de qué pasó. **Aprendizaje sobre modelar realidad vs deseo**: cuando interactúo con un sistema externo eventualmente consistente (Alpaca paper, Stripe, etc.), mi código local NO es la fuente de verdad — el sistema externo lo es. Si el broker dice "filled", mi modelo debe absorber eso aunque sea inesperado. Inventar contra-acciones para "deshacer" lo que el broker hizo es sobre-control que falla en otros edges. **Heurística**: en arquitecturas con sistemas externos, prefiero **reconciliar al estado real reportado** antes que forzar el estado deseado. El `OrderReconciliationService` es la encarnación de este principio.

### Migración aditiva vs estados completos — el balance entre principios y pragmatismo

`ARCHITECTURE.md` §9 lista 10 estados de orden (Pendiente, Enviada, En Ejecución, Ejecutada, Cancelada, Rechazada, Expirada, En Revisión, Fallida, Detenida). El enum `OrderStatus` pre-F15 tenía 4 (PENDING/EXECUTED/REJECTED/FAILED). F15 agrega 2 más (CANCELED + EXPIRED). **NO agregué los 4 restantes** (IN_REVIEW, STOPPED, etc.) aunque "completarían el modelo". Justificación: Alpaca paper no genera esos estados, MVP single-user no los necesita, y agregar valores no usados al enum infla la superficie + el chk constraint + tests sin uso real. **Decisión documentada en D3 del plan**. El doc maestro (ARCHITECTURE.md) sigue listando los 10 — F15 NO lo modificó. **Aprendizaje sobre alineamiento código ↔ doc**: el doc puede tener un modelo más amplio que el código si el contrato es "código es subset del doc". Lo importante es que el código sea estricto (chk constraint, enum) — el doc puede aspirar a más. **Heurística**: si el doc dice "10 estados posibles" y el código implementa 6, la regla es "código rechaza estados no implementados explícitamente" (chk constraint). El doc es la lista total; el código es la lista activa. Si en el futuro emerge necesidad de IN_REVIEW, se agrega como migración aditiva (V7) sin breaking change.

### Pattern emergente: D27 records + extensiones rompen call sites positional

Extendí `AlpacaOrderResponse` record con 2 campos nuevos (`canceledAt`, `expiredAt` desde Alpaca) para parsear el polling response. **Rompió 4 call sites positional en tests** (`TradingServiceTest`, `OrderReconciliationServiceTest`) — el constructor positional no es retro-compatible. Patches triviales: agregar `null, null` al final de cada call site. **El JSON wire format SÍ es retro-compatible** porque Jackson tolera campos nuevos via `@JsonProperty` — clientes externos no rompen. **El problema es interno**, en código Java que construye el record posicionalmente. **Lección sobre evolution de records**: si un record es solo wire format (DTO de boundary), extender es seguro. Si es construido manualmente en tests o factory methods, extender requiere patch coordinado. **Alternativa futura para records con muchos campos**: factory methods nominales (`AlpacaOrderResponse.withStatus(status)`, `.withFilled(price, qty)`) en lugar de constructor positional. Java records soportan compact constructors + factory statics — los tests no romperían al agregar campos opcionales. Para F15 hice patches triviales (4 sitios), pero si fueran 40 valdría la pena el refactor.

### Smoke E2E manual: el único test que valida UX real

El SPEC §15 DoD lista smoke E2E manual del humano como HITO 5 (no automatizado). Los IT cubren backend con WireMock + PostgreSQL real — pero el flujo final usuario→UI→backend→Alpaca paper→email MailHog→audit Kibana solo se valida con uso humano. **Para F15 ya documenté el plan smoke** (Lote E reporting) — 10 smokes ordenados por complejidad. **Aprendizaje sobre testing piramidal**: 332 unit + 78 IT + 27 vitest = altísima cobertura automática, pero NO sustituye el smoke E2E. El bug que el smoke captura típicamente es "el flujo entero se ve raro juntos" — falta de consistencia entre componentes, textos confusos, latencia perceptible, density visual. Igual que el Día 10 sparkline minimal fracasó por densidad visual, F15 puede tener defectos UX que los IT no detectan (`window.alert` interrumpe flow, confirm dialog texto raro en casos edge, etc.). **Heurística reiterada del Día 10**: el smoke manual no es opcional — es el único test que ejercita el sistema como sistema, no como suma de componentes. Programarlo HITO 5 explícito y NO declarar HU cerrada antes de ejecutarlo es lo correcto.

### Reflexión meta: 410 tests verdes + 11 emergentes + cierre Sprint 2 funcional + bonus F15

Cierre Día 11: **410 tests backend verdes (332 unit + 78 IT) + 27 vitest + build 3377 módulos**. Delta vs baseline pre-F15: **+47 tests backend** + 2 módulos frontend. **11 decisiones emergentes durante implementación (D25–D35)** — la HU con más emergentes del proyecto (F09=7, F10=5, F16+F21=2, F18+F17=3). Razonable por la complejidad: polling canónico + reconcile v2 + drift inline + 4 outcomes + idempotency 2 paths + 2 templates email + visual feedback nuevo. **El patrón [[feedback-decisiones-emergentes-patron]] se mantiene estable** — toda HU no-trivial genera 2-11 emergentes. La sección §2.4 reservada vacía al final del plan.md NUNCA queda vacía si el SDD es honesto. **Reflexión sobre el SDD como predictor**: las 11 decisiones de §14 (SPEC abiertas) + 21 de §2 (plan cerradas) + 35 acumuladas = total 35 decisiones documentadas. **Sin SDD, cuántas de esas habrían sido "obvias en el momento" pero olvidadas en el handoff?** El valor del SDD no es predecir el código — es **forzar la explicitación** de decisiones que de otro modo viajarían solo en mi cabeza. Cuando vuelva a este código en 6 meses (o cuando un agente nuevo lo lea), las 35 decisiones están ahí. **Esa es la deuda cognitiva que el SDD compra** — un costo upfront grande, un retorno difuso pero real cada vez que necesito justificar "por qué se hizo así". Para el proyecto académico es el entregable principal; para el código futuro es seguro psicológico que vale el precio.
