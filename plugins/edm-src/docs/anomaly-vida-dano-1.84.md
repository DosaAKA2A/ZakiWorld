# Anomaly: vida y daño (EDM 1.84.0)

Revisión de cómo entra la vida al jefe y cómo sale su daño hacia los jugadores, después de quitar el escalado por jugadores y de subir los topes.

## Vida

- La vida que pelea es la del menú (`vida` de la ficha), sin más: `applyHealth(registry.health(tipo))` en los 18 `spawn()`. Ya no depende de cuánta gente hay cerca al abrir. Tope del ajuste: 800000 (antes 400000).
- Tope de 1024 de Minecraft: la entidad nunca pasa de 1024 de `max_health`. El resto se cobra con `damageScale = vida de la entidad / vida pedida`, que `onFinalDamage` (MONITOR) aplica a todo daño que recibe el jefe, venga de quien venga. Con 800000 cada golpe se multiplica por 0,00128: un golpe de 10 quita 0,0128 de vida real. La precisión del `float` de la vida a esa escala (unas 6 cienmilésimas cerca de 1024) sobra, así que no hay golpes que se pierdan por redondeo.
- Los gemelos de cobre reparten la vida del menú a medias (`total / 2`, mínimo 200 por gemelo), y KAM usa la misma escala vía `partDamageScale`.
- `survivalFloor`: recorta el golpe para que el jefe no baje del umbral mientras la fase lo pide. Trabaja sobre la vida de la entidad (fracción), así que no le afecta el tope ni la escala. Hoy solo lo usan los gemelos (2% mientras KAM siga en juego).
- Umbrales de fase: `PhaseBars.currentPhase` mira la fracción de vida de la entidad. Como la escala es la misma para todo golpe, las fases caen en el mismo porcentaje que antes.
- La bitácora del arranque ("ajustes") ya no habla de "extra por jugador": anota la vida del menú, el multiplicador de daño y cuánta gente había cerca al abrir.

## Daño

- Tope del multiplicador: x40 (antes x20). El menú sube de 0,5 en 0,5 hasta x5 y de 1 en 1 de ahí a x40; Shift salta de 5 en 5.
- Habilidades: `BossFight.hit()` aplica `multiplicador × daño propio de la habilidad`, apunta el golpe para el mensaje de muerte y reparte la salpicadura. Todo eso va dentro de `dealScaled`, que marca el golpe como "ya escalado".
- Cuerpo a cuerpo y lo que se dispara: `AnomalyManager.onDamage` (HIGHEST) multiplica por `damageMultiplier` todo golpe a un jugador cuyo autor sea de la anomalía abierta: el jefe, su maniquí, un esbirro suyo, o un proyectil, nube, colmillos o TNT cuyo tirador u origen sea uno de esos (`PlayerDeaths.fromAnomaly`). Si el golpe viene marcado por `dealScaled`, no se toca, y así no se multiplica dos veces lo que llega por `hit()`.
- Con esto pasan a escalar cosas que antes no lo hacían: las flechas del Cazador (`setDamage` propio), las bolas de fuego del Piromante, el escupitajo de la Quimera y cualquier mob invocado que pegue cuerpo a cuerpo.

## Daño que no pasa por `hit()`

Grep de `.damage(` y `setHealth(` sobre jugadores en `boss/*.java`:

| Dónde | Qué hace | Decisión |
|---|---|---|
| CopperTwins `strike()` (~585) | Daño de KEM y KAM, cada uno con su cuerpo; ya aplica el multiplicador y la Herrumbre | Se queda con su propio cálculo, pero ahora va dentro de `dealScaled` (el manager no lo vuelve a multiplicar) y apunta el golpe para el mensaje de muerte |
| Raiz, Mirada (~405) | `setHealth(vida − 2000)` a quien no mira: daño puro | Se deja como está. Es una sentencia: 2000 mata a cualquiera, así que el multiplicador no cambiaría nada. Se añadió `markKill` para que la muerte diga que fue ROTTEN |
| Piromante `setFireTicks` (~231 y ~295) | Prende fuego tras el golpe | El fuego es daño de ambiente (FIRE_TICK, sin autor) y no escala. El golpe en sí va por `hit()`. Se deja así: escalar el fuego exigiría seguir a cada jugador mientras arde |

El resto de `setHealth` del grep son curas del jefe o vida de esbirros y adornos (no tocan jugadores). No hay más `.damage(` sobre jugadores en los jefes.

## Lo que queda sin escalar a propósito

- Daño de ambiente que provoca una habilidad (fuego, caída tras un empujón, lava): no tiene autor y multiplicarlo afectaría también a lo que no es de la anomalía.
- La salpicadura reparte una parte del golpe ya escalado; no se escala otra vez.
- El daño propio de cada habilidad (`Skills/Anomalias/<id>.yml`, clave `dano`) sigue con su tope de 0 a 20, aparte del multiplicador de la ficha.
