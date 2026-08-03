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
