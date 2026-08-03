# Rip — código fuente

Plugin de efectos de kill y muerte de ZakiWorld (`net.zakiworld.rip`, Iris Studio).

## Aviso sobre este código

El fuente original del plugin se perdió. Este árbol es una **reconstrucción por descompilación** del `Rip-3.1.0.jar` desplegado, hecha el 3 de agosto de 2026. Compila con 0 errores y genera las mismas 16 clases que el jar original, y se verificó que su comportamiento es idéntico. Lo que no conserva son los comentarios y el formato del original, y los nombres de variables locales son los que puso el descompilador.

Si el fuente original llegara a aparecer, es mejor base que este.

## Compilar

```
mvn package
```

Requiere JDK 17 o superior. La dependencia es `paper-api 1.20.1` a propósito: el plugin resuelve en tiempo de ejecución lo que cambia entre versiones (partículas, entidad `Mannequin`), así que compilar contra la API vieja es lo que le permite funcionar desde 1.20 hasta 26.x.

## Historial de versiones

### 3.1.9 — 3 de agosto de 2026

**Los enfriamientos pasan a ser tiempos de juego, no duraciones de animación.** Antes el valor de fábrica de cada efecto era lo que tardaba su animación, de 1 a 9,5 segundos: servía para que no se apilaran, pero no para que un legendario se sintiera raro. Ahora los marca la calidad: común 10 s, poco común 20 s, raro 40 s, mítico 75 s, legendario 120 s e inmortal 180 s.

El tope de la validación sube de 60 a **600 segundos**, que era lo que impedía poner los 3 minutos. Y el aviso de solape ahora compara contra la duración real de la animación y no contra el enfriamiento de fábrica, que es lo que corresponde.

Sigue siendo por efecto: los valores por calidad son solo el punto de partida y cada línea del YAML se cambia por separado.

**Banner de consola en rojo.** Sale por el `ConsoleCommandSender` en vez del logger, así que va en color y sin el prefijo `[Rip]` repetido en cada línea del dibujo. El logo lleva un degradado de rojo vivo arriba a rojo oscuro abajo. Todos los caracteres son ASCII o Latin-1 para que no salgan interrogantes en consolas de Windows.

### 3.1.8 — 3 de agosto de 2026

**El `config.yml` de un servidor que ya existía ahora recibe las claves nuevas.** `saveDefaultConfig()` no toca un archivo que ya está, así que al actualizar desde la 3.1.0 no aparecían `ocultar-cuerpo`, `calidades` ni `enfriamientos`: el plugin usaba los valores de fábrica y no había nada que editar en el archivo. Al arrancar se añaden los bloques que falten, con sus comentarios, sin tocar lo que ya estuviera puesto.

**Nuevo `max-animaciones-simultaneas`, por defecto 3.** El enfriamiento por jugador ya impedía que uno que mata a cinco de golpe lanzara cinco animaciones: solo suena la primera. Lo que no cubría es cinco víctimas distintas muriendo en el mismo tick, porque cada una es un jugador distinto con su propio enfriamiento. Este límite cuenta animaciones de cualquiera a la vez; las que no entran no suenan. `0` lo desactiva.

Verificado en Paper 26.1.2 con un `config.yml` de la era 3.1.0: se añaden las cuatro claves y se conservan `cooldown-ms` y el bloque `sixtyseven`. Un mismo asesino con cinco bajas de golpe lanza 1 animación de 5; cinco víctimas distintas a la vez lanzan 3 de 5 con el límite en 3.

### 3.1.7 — 3 de agosto de 2026

**Los enfriamientos se editan desde `config.yml`**, en la clave `enfriamientos`, con la misma forma anidada por tipo que `calidades` y con los 54 efectos listados. El valor va **en segundos** y admite decimales, de 0 a 60. Los valores de fábrica son exactamente lo que dura cada animación.

Se acepta poner a un efecto menos de lo que dura su animación, pero avisa por consola, porque a partir de ahí ese jugador puede tener dos animaciones a la vez, que es justo lo que el enfriamiento evitaba. Un valor no numérico o fuera de rango se descarta con aviso y el efecto se queda con su valor de fábrica; borrar la clave entera devuelve todo a fábrica. `cooldown-ms` sigue siendo el mínimo global y gana si es mayor.

El menú, `/rip list` y `/rip test` leen todos el valor efectivo, así que reflejan los cambios sin tocar nada más.

### 3.1.6 — 3 de agosto de 2026

- **Las calidades se reasignan desde `config.yml`**, en la clave `calidades`, anidada por tipo (`kill:` / `death:`) y con los 54 efectos listados. Los valores válidos son `COMUN`, `POCO_COMUN`, `RARO`, `MITICO`, `LEGENDARIO` e `INMORTAL`, y no distinguen mayúsculas. Un valor inválido o un id que no existe se ignoran con un aviso en consola y el efecto se queda con su calidad de fábrica; borrar la clave entera devuelve todo a fábrica. Se aplica con `/rip reload`.

  La calidad va anidada por tipo a propósito: una clave suelta `kill.swordfall` la partiría Bukkit por el punto y no se encontraría nunca. La lista `ocultar-cuerpo` sí usa esa forma porque son cadenas dentro de una lista, no claves.

- **Nuevo `/rip info`**, sin permiso, con el ASCII, la versión, la autoría y de qué va el plugin.

### 3.1.5 — 3 de agosto de 2026

En el Juicio de Espadas, el maniquí desaparece en el momento en que la espada gigante toca el suelo (tick 103) en vez de aguantar hasta el tick 160. Se lleva consigo el humo y el sonido de teletransporte, y se le añadieron partículas de alma. Las espadas siguen clavadas hasta el 160 como antes.

### 3.1.4 — 3 de agosto de 2026

**El cuerpo desaparece al instante en los efectos que lo destruyen.** Al morir, el cliente reproduce la animación vanilla: el jugador se pone rojo y cae de lado durante cerca de un segundo. En una explosión o un despegue eso se ve mal, porque el cadáver sigue tumbándose por debajo de la animación. Y en el Juicio de Espadas y el Despegue Estelar era peor, porque el clon nace de pie en el mismo punto y durante un rato hay dos cuerpos superpuestos.

Ahora los efectos marcados esconden al muerto del resto de clientes en el mismo tick de la muerte, con `Player#hidePlayer`, y lo vuelven a mostrar al reaparecer. No se toca al jugador ni al evento: solo deja de enviarse su entidad, así que el cliente no tiene qué animar.

La lista está en `config.yml`, en la clave `ocultar-cuerpo`, con formato `kill.<id>` o `death.<id>`. Vienen marcados 25 de los 54: los que revientan, desintegran, congelan, absorben o se llevan el cuerpo. Si borras la clave entera se vuelve a los valores de fábrica; si la dejas vacía no se oculta nunca. El menú indica en el lore qué efectos lo hacen.

Contra el riesgo de dejar a alguien invisible hay tres redes: se revela al reaparecer, se limpia al salir del servidor, y un barrido cada 5 segundos revela a cualquiera que esté oculto y ya no esté muerto. Al desactivar el plugin se revela a todos.

### 3.1.3 — 3 de agosto de 2026

- **Juicio de Espadas en oro.** Las once espadas de la lluvia y la gigante final son de oro. Antes eran de hierro y netherita.
- **Ninguna animación empuja.** Se eliminó el `knockback`, que en la explosión y en el impacto del Juicio zarandeaba a todo bicho viviente en un radio de 5 bloques. Son animaciones, no deben mover a nadie.
- **Enfriamiento por efecto.** Cada efecto tiene ahora su propia duración declarada en `RipEffect`, y su enfriamiento es exactamente esa duración, con un mínimo de 1 segundo. Un jugador no puede tener dos animaciones corriendo a la vez, y cuanto más larga es la animación más espera. Va de 1 s en los efectos instantáneos a 9,5 s en el Juicio de Espadas. El `cooldown-ms` del config sigue existiendo pero solo actúa como mínimo global.
- **El enfriamiento se ve en el menú**, en el lore de cada efecto y para todos los usuarios, no solo para los administradores. Si el jugador está en espera aparece además cuánto le queda. `/rip list` también lo muestra en columna.
- El nodo de permiso de cada efecto sigue viéndose solo con `rip.admin`, que es `default: op`.
- `/rip test` respeta el enfriamiento en vez de saltárselo, que era la forma más fácil de apilar animaciones.
- Declarados en `plugin.yml` los permisos `rip.kill.blastoff` y `rip.death.sixtyseven`, que existían pero no estaban registrados. Siguen fuera de `rip.*` a propósito: son los dos INMORTAL y se conceden a mano.
- Nuevo ASCII de arranque en consola.

### 3.1.2 — 3 de agosto de 2026

Arregla el Juicio de Espadas, que en servidores 1.21.9 y posteriores se cortaba en el tick 84 de 190 y dejaba el maniquí y las espadas clavados en el mundo para siempre.

La causa era que en 1.21.9 las partículas `FLASH` y `DRAGON_BREATH` pasaron a exigir datos obligatorios (`Color` y `Float`). Lanzarlas sin ellos tira `IllegalArgumentException`, y como `EffectRunner.animate()` no ejecutaba el `onEnd` al capturar una excepción, la limpieza no llegaba a correr nunca.

- `Compat.spawn` consulta `getDataType()` y aporta un dato por defecto cuando la partícula lo exige. Afectaba a 19 puntos de llamada de todo el plugin, no solo al Juicio de Espadas.
- `EffectRunner.animate()` tolera hasta 5 errores antes de rendirse y ejecuta el `onEnd` siempre, también al abortar.
- Barredor cada 100 ticks que elimina cualquier entidad de efecto con más de 30 segundos de vida.
- `MannequinHook` borra el maniquí si falla cualquier paso posterior al spawn, y le aplica `setPersistent(false)` nada más crearlo para que un maniquí filtrado no acabe guardado en el fichero del mundo.
- `RipPlugin.VERSION` seguía diciendo `"3.0.0"`.

Verificado en Paper 26.1.2 y en Paper 1.21.9.

#### Sobre Rip-4.0.0.jar

En este mismo repo hay un `Rip-4.0.0.jar` de julio de 2026 que es una rama paralela, no una versión posterior. Ya traía dos de estos arreglos —ejecutar el `onEnd` al fallar y un barrido de huérfanos— pero no el de las partículas, así que también se corta en el tick 84. Y le faltan cosas que sí tiene la línea 3.1.x: el menú con cabeza en los 54 efectos y la campana del Juicio.

La 3.1.2 parte de la 3.1.0, que es la que está desplegada, y reincorpora las dos protecciones de la 4.0.0. Con eso la 4.0.0 queda obsoleta.
