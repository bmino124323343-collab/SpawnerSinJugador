# SpawnerSinJugador 1.5.0 — Paper 1.21.x y 26.x

Plugin para Paper: hace que los spawners **colocados por jugadores** sigan generando criaturas sin jugadores cerca, con panel de ajustes por spawner (`/spawnersj`). Los spawners naturales no se tocan.

## Compatibilidad

| Servidor | Java del servidor | Estado |
|---|---|---|
| Paper 1.21.x | 21+ | Objetivo del build por defecto |
| Paper 26.1 – 26.3 | 25 | El mismo .jar debería cargar (bytecode Java 21 corre en Java 25) |
| Folia / Spigot | — | No soportado |

Paper 26.3 está en beta: prueba en una copia del servidor antes de usarlo en producción.

## Compilar

Requiere JDK 21+ y Maven. Ejecuta `compilar.bat` (o `mvn -U clean package`).
Resultado: `target/SpawnerSinJugador-1.5.0.jar` → cópialo a `plugins/`.

Opcional, build específico para 26.x (JDK 25): `mvn -Ppaper26 clean package`.
Si esa versión de la API ya no existe, usa `-Dpaper.api.version=<versión disponible>`.

## Cambios respecto a 1.4.0

- **Corregido:** el intervalo por spawner dependía de `getCurrentTick() % intervalo`; como la tarea corre en ticks desfasados, casi nunca coincidía y el spawner rara vez generaba. Ahora cada spawner lleva su propio temporizador.
- **Corregido:** al cargar chunks, las criaturas del plugin se detectan con `EntitiesLoadEvent` (en versiones modernas las entidades cargan por separado).
- El panel se identifica con un `InventoryHolder` propio en vez del título, y usa Adventure (compatible entre 1.21 y 26).
- Al romper un spawner se borra su configuración individual.
- Build de 1 jar para 1.21.x–26.x (`api-version: 1.21`, Java 21).

## Permiso

`spawnersj.admin` (por defecto, operadores).

## Aviso

Código **no compilado ni probado** en servidor real. Prueba el .jar en una copia de seguridad.

## Obtener el .jar sin instalar nada

1. Crea un repositorio en GitHub y sube el contenido de esta carpeta (incluida `.github`).
2. Pestaña **Actions** → "Compilar jar" → espera a que termine.
3. Descarga el artefacto `SpawnerSinJugador-jar` (contiene el `.jar`).
