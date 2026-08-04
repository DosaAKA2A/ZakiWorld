# Anomaly

Eventos anómalos para **Ederus**. Cada cierto tiempo (o cuando lo pide un operador) se
abre una grieta en un punto libre del mapa, sale un jefe por fases y quien lo mate se
lleva un botín que se configura entero desde el menú.

La intención de fondo es empujar a la gente a **salir del spawn** y a **pelear en grupo**:
el jefe escala con el número de jugadores, varias habilidades castigan dispersarse y el
botín se reparte entre todos los que participaron, no solo entre quien da el último golpe.

- **Versión:** 1.1.0
- **Paquete:** `net.zakiworld.anomaly`
- **Probado contra:** Paper 26.1.2 (MC 26.1.2), compilado con `--release 21`
- **Permiso único:** `anomaly.gui` (`default: op`)

---

## Cómo se usa

Todo se maneja desde `/anomaly`. El menú es el camino principal; los subcomandos hacen
lo mismo desde consola.

| Comando | Qué hace |
|---|---|
| `/anomaly` | Abre el panel |
| `/anomaly start [id]` | Busca sitio válido y abre la anomalía |
| `/anomaly here [id]` | La abre donde estás, sin buscar ni mirar protecciones |
| `/anomaly stop` | La cierra y limpia la escena |
| `/anomaly info` | Estado del plugin y del evento |
| `/anomaly abilities [id]` | Lista las habilidades |
| `/anomaly test <id\|all>` | Lanza una habilidad ya, saltándose fase y enfriamiento |
| `/anomaly hurt <vida>` | Le baja vida a mano, para ver las fases sin juntar un grupo |

Alias: `/anomalia`, `/anom`.

### El menú

- **Panel** — iniciar, **ir a la anomalía**, elegir, botín, ajustes, detener y estado en vivo.
  El botón de viaje te deja a diez bloques del jefe, en suelo firme y mirándolo: encima no,
  que caer dentro del alcance de un jefe que ya pelea es una muerte gratis.
- **Anomalías** — click para elegirla, click derecho para ver sus habilidades,
  shift para encenderla o apagarla, rueda para subir la vida base.
- **Habilidades** — ficha de cada una: fase, duración, enfriamiento y qué hace.
- **Botín** — dos modos. En *colocar*, traes el objeto en el cursor y haces click:
  se guarda una **copia**, no pierdes tu objeto. En *ajustar*, cambias probabilidad,
  cantidad y a quién le toca.
- **Ajustes** — distancias, tiempos, escalado por jugadores y anomalías automáticas.
  Cada cambio se escribe en `config.yml` al momento.

---

## El anuncio

```
  ▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬
  ✦  Una Anomalía apareció en   412 · 71 · -1180
     pasa el ratón por Anomalía para saber a qué se enfrentan · se cierra en 15 min
  ▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬
```

- **"Anomalía"** es un hover: al pasar el ratón cuenta qué anomalía es, su **elemento**,
  **de dónde viene** y, en un color distinto (dorado), **qué suelta**, con probabilidad
  y destinatario.
- Las **coordenadas** se copian al portapapeles con un click. Sin nombre de mundo: la línea
  gana quitándoselo y no aporta nada.

---

## Elemento y brillo

Cada anomalía declara dos cosas que la definen de un vistazo:

- **Elemento** — `TIERRA`, `AGUA` o `VIENTO`. No es una etiqueta decorativa: es el filtro
  de terreno del buscador. Una de tierra exige suelo firme y seco y rechaza el sitio si más
  del 10 % de la arena está bajo agua; una de agua exige justo lo contrario. El Caballero
  Sepulcral es de **tierra**.
- **Brillo** — un color propio, visible **a través del terreno** y a mucha más distancia que
  cualquier partícula. Es en la práctica la forma de encontrar al jefe. El Caballero brilla
  en **rojo**. Sale del equipo de marcador (Minecraft no deja pintar el contorno de otra
  forma), por eso tiene que ser uno de los dieciséis colores con nombre.

A eso se suma un **pilar de luz** del mismo color sobre el jefe, que se puede apagar con
`anuncio.pilar-de-luz` si alguna vez pesa.

## Dónde aparece

El buscador prueba hasta 40 puntos, cargando los trozos de forma **asíncrona** (nunca
congela el servidor), y descarta un sitio si:

1. El terreno no encaja con el **elemento** de la anomalía (agua, altura, suelo firme).
2. Está dentro o cerca de una región de **WorldGuard** — lo que cubre también los claims
   de **ProtectionStones**, porque los crea como regiones WG. El margen es configurable
   para que la pelea, que se mueve, no acabe dentro del terreno de nadie.
3. Está a menos de X del spawn del mundo.
4. Tiene pinta de base aunque no haya claim: cofres, camas, hornos, yunques, spawners…
5. No hay hueco suficiente o el terreno es demasiado escarpado (el jefe va montado).

La distancia se mide **desde un jugador**, nunca desde el spawn del mundo si hay alguien
conectado — y eso incluye a quien esté en creativo. Anclarse al spawn hacía que todas las
anomalías salieran alrededor del centro del mapa. El punto se sortea repartido por **área**
(raíz cuadrada del radio), que si no se amontonan todos en la distancia mínima.

El hook de WorldGuard es **entero por reflexión**: si mañana se quita, el plugin sigue
arrancando y lo dice en el log; solo pierde esa comprobación.

---

## El Caballero Sepulcral

Esqueleto con armadura de netherita, **lanza de netherita** (el arma nueva de la versión)
y montado en un caballo esqueleto. Viene del Páramo de Batalla del Aether.

**Tres barras de jefe apiladas**, un tercio de vida cada una. Se vacían de arriba abajo:
cuando la de arriba llega a cero desaparece y la siguiente pasa a ser la principal.

### Fase I — montado
| Habilidad | Qué hace |
|---|---|
| Carga de Lanza | Marca un pasillo y galopa por él |
| Barrido de Guadaña | Tres barridos concéntricos; solo pega el borde de cada onda |
| Lanzas del Páramo | Bajo los pies de cada uno brotan cinco lanzas |
| Estandarte de Guerra | Le da resistencia; hay que **derribarlo a golpes** |
| Relincho Aterrador | Cono de miedo: ciega, marea y frena |
| Llamada de Jinetes | Salen 2-4 jinetes menores con arco |

### Fase II — a pie
| Habilidad | Qué hace |
|---|---|
| Estocada Fantasma | Se teletransporta a la espalda del más lejano |
| Muro de Lanzas | Nueve lanzas atraviesan la arena |
| Cadena de Hueso | Arrastra al que se aleja de vuelta al centro |
| Juramento Roto | Si te alejas más de 16 bloques en 7 s, golpe brutal |
| Círculo de Osario | Cerco que se cierra de 18 a 7 bloques |
| Guardia de Netherita | 160 de escudo que hay que romper a golpes |

### Fase III — heraldo
| Habilidad | Qué hace |
|---|---|
| Lluvia de Acero | 8 s de lanzas del cielo sobre marcas que te persiguen |
| Tormenta Espectral | Se eleva y descarga rayos |
| Última Carga | El fantasma de la montura vuelve |
| Grito del Páramo | Haz sónico frontal de 24 bloques |

### Cualquier fase
| Habilidad | Qué hace |
|---|---|
| Marca del Sepulcro | Señala al que se aleja y le tira una lanza encima |
| Leva de Huesos | Recluta 3-6 caídos que salen del suelo |

### Las tres animaciones guionizadas

- **Descabalgue (I → II)** — el caballo se encabrita, se deshace en huesos y el caballero
  cae de pie con una onda expansiva.
- **Resurrección Fallida (II → III)** — se vuelve invulnerable y tres **anclas de hueso**
  lo sostienen. Si el grupo las rompe a tiempo, pierde la armadura y recibe un 30 % más de
  daño; si no, se levanta pegando un 35 % más fuerte. **No se cura a propósito**: curarse
  lo devolvería a la fase 2 y el combate entraría en bucle.
- **Ocaso (muerte)** — la armadura se desprende pieza a pieza y la grieta se cierra.

---

## Decisiones que conviene no deshacer

- **Vida por encima de 1024.** El atributo `max_health` de Minecraft topa en 1024 y pasarse
  revienta el spawn (lo cazó el servidor de pruebas). La entidad se queda en el tope y el
  exceso se cobra **reduciendo el daño entrante**: para el jugador la pelea dura lo que
  dicen los ajustes, pero el servidor nunca ve un valor ilegal.
- **`setMaximumNoDamageTicks(6)`.** Con la invulnerabilidad vanilla de 20 ticks solo le pega
  uno del grupo. Bajarla es lo que hace que repartirse el trabajo sirva de algo.
- **Los soportes de armadura no aceptan `setDropChance`** en Paper 26 (`non-Mob entity`).
  Llamarlo rompía el estandarte y, peor, las anclas de la fase 3, lo que dejaba al jefe
  invulnerable para siempre. Hay además un **vigilante** que le quita la invulnerabilidad
  a la fuerza si pasa de 20 s, para que ninguna pelea pueda quedar sin final.
- **Partículas con datos obligatorios.** Desde 1.21.9 `FLASH` exige `Color` y `DRAGON_BREATH`
  un `Float`; llamarlas sin el dato aborta la animación entera. `Compat.spawn` pone el dato
  por defecto, igual que en Rip.
- **`Stop` no es un error.** Cortar una animación antes de tiempo es una salida normal y no
  ensucia el log; `Anim` siempre ejecuta la limpieza, incluso si un tick lanza una excepción.
- **Ver y pelear son cosas distintas.** `playersNear` son los que reciben daño (solo
  supervivencia y aventura); `viewersNear` son los que ven barras, títulos y avisos, e
  incluye a quien está en creativo. Confundirlos hacía que un operador probando el evento
  no viera la barra de jefe y creyera que el jefe no había aparecido.
- **El brillo se limpia al terminar.** Si no se saca a la entidad del equipo de marcador,
  el equipo se llena de UUID de entidades muertas y crece sin parar.
- **Interruptor de empuje.** `combate.permitir-empuje` apaga todo el empuje de golpe, por la
  misma razón por la que se prohibió en las animaciones de Rip.

---

## Añadir una anomalía nueva

1. Una clase que extienda `BossFight` con su `spawn()`, `onPhaseChange()` y `onDeath()`.
2. Un `AnomalyType` con su ficha (nombre, color, icono, de dónde viene, amenaza, vida).
3. Su lista de `Ability` (fase, enfriamiento, duración, peso, icono, qué hace).
4. Una línea en el constructor de `AnomalyRegistry`.

Menú, anuncio, botín, barras y limpieza no hay que tocarlos: ya trabajan contra la lista.

---

## Compilar

No hay Maven en el PATH. Con el JDK 25 portátil y `paper-api 26.1.2`:

```
javac --release 21 -encoding UTF-8 -cp "<paper-api + libs del servidor>" -d build @sources
jar --create --file Anomaly-1.1.0.jar -C build .
```

El `pom.xml` está para quien tenga Maven; `paper-api` es `provided`.
WorldGuard **no** es dependencia de compilación a propósito: el hook es por reflexión.

Al subir de versión hay que tocar **tres sitios**: `pom.xml` (`<version>` y `<finalName>`),
`plugin.yml` (`version:`) y la constante `VERSION` de `AnomalyPlugin` (la lee `/anomaly info`).
