# Anomaly

Eventos anómalos para **Ederus**. Cada cierto tiempo (o cuando lo pide un operador) se
abre una grieta en un punto libre del mapa, sale un jefe por fases y quien lo mate se
lleva un botín que se configura entero desde el menú.

La intención de fondo es empujar a la gente a **salir del spawn** y a **pelear en grupo**:
el jefe escala con el número de jugadores, varias habilidades castigan dispersarse y el
botín se reparte entre todos los que participaron, no solo entre quien da el último golpe.

- **Versión:** 1.2.0
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
| `/anomaly at <x> <y> <z> [id] [mundo]` | La abre en esas coordenadas; funciona desde consola |
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
- **Anomalías** — click para elegirla, click derecho para abrir su ficha,
  shift para encenderla o apagarla.
- **Ficha** — todas sus habilidades (fase, duración, enfriamiento y qué hace) y abajo
  la **vida base del jefe**, con − y + de 100 (500 con shift). El menú enseña también
  cuánta vida tendría con cinco jugadores, que es lo que de verdad importa al calibrar.
- **Botín** — dos modos. En *colocar*, traes el objeto en el cursor y haces click:
  se guarda una **copia**, no pierdes tu objeto. En *ajustar*, cambias probabilidad,
  cantidad y a quién le toca.
- **Ajustes** — distancias, tiempos, escalado por jugadores y anomalías automáticas.
  Cada cambio se escribe en `config.yml` al momento.

---

## El anuncio

```
✦ Una Anomalía ha aparecido en 412 71 -1180
```

Una línea y nada más. Ni marcos, ni segunda fila, ni gris sobre negro: todo lo demás
vive en el hover, que es donde se puede leer con calma.

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

**Tres barras de jefe**, un tercio de vida cada una, pero **de una en una**: cuando la de
la fase en curso se agota, desaparece y sale la siguiente. No se ven las tres a la vez a
propósito, ni siquiera para avisar de cuánto queda.

### Fase I — montado
| Habilidad | Qué hace |
|---|---|
| Carga de Lanza | Marca un pasillo y galopa por él |
| Barrido de Guadaña | Tres barridos concéntricos; solo pega el borde de cada onda |
| Pisotón de la Montura | El caballo se encabrita y descarga los cascos |
| Estandarte de Guerra | Le da resistencia; hay que **derribarlo a golpes** |
| Relincho Aterrador | Cono de miedo: ciega, marea y frena |
| Llamada de Jinetes | Salen 2-4 jinetes menores con arco |

### Fase II — a pie
| Habilidad | Qué hace |
|---|---|
| Estocada Fantasma | Se teletransporta a la espalda del más lejano |
| Tajo Descendente | Parte el suelo en línea recta de un tajo |
| Cadena de Hueso | Arrastra al que se aleja de vuelta al centro |
| Juramento Roto | Si te alejas más de 16 bloques en 7 s, golpe brutal |
| Círculo de Osario | Cerco que se cierra de 18 a 7 bloques |
| Guardia de Netherita | 160 de escudo que hay que romper a golpes |

### Fase III — heraldo
| Habilidad | Qué hace |
|---|---|
| Sismo del Páramo | Cuatro pisotones seguidos, cada uno con su onda |
| Salto Demoledor | Salta muy alto y cae de lleno sobre la marca |
| Última Carga | El fantasma de la montura vuelve |
| Grito del Páramo | Haz sónico frontal de 24 bloques |

### Cualquier fase
| Habilidad | Qué hace |
|---|---|
| Cacería | Le echa el ojo al que se aleja y va a por él a la carrera |
| Leva de Huesos | Recluta 3-6 caídos que salen del suelo |

El Caballero es **fuerza bruta, no magia**: no hay una sola arma que flote, se eleve o
caiga sola del cielo. Todo lo que pega lo pega él, con la lanza o con los cascos, y los
escombros que levanta son del bloque que esté pisando.

### Las tres animaciones guionizadas

- **Descabalgue (I → II)** — el caballo se encabrita, se deshace en huesos y el caballero
  cae de pie con una onda expansiva.
- **Resurrección Fallida (II → III)** — se vuelve invulnerable y tres **anclas de hueso**
  lo sostienen. Si el grupo las rompe a tiempo, pierde la armadura y recibe un 30 % más de
  daño; si no, se levanta pegando un 35 % más fuerte. **No se cura a propósito**: curarse
  lo devolvería a la fase 2 y el combate entraría en bucle.
- **Ocaso (muerte)** — la armadura se desprende pieza a pieza y la grieta se cierra.

---

## La Cabra Gritona

La cabra chillona del Aether, pero del tamaño de una casa (atributo `scale`), en
**blanco** y de elemento **viento**, así que solo aparece en cumbres y a cielo abierto.

Todo lo suyo nace del **grito**: empuja, hace daño, marea y **parte el cielo en rayos**
sobre quien lo haya recibido, dejándola ardiendo en blanco. Lo demás es embestir, saltar
y no dejarse mover.

| Fase | Habilidades |
|---|---|
| I — la embestida | Grito Atronador · Embestida de Cuernos · Pisotón de Pezuñas · Berrido · Salto Montañés |
| II — la rabia | Tormenta de Balidos · Rebote · Manada · Cornada Ascendente · Pelaje Blanco |
| III — el trueno | Grito del Trueno · Estampida · Cielo Partido · Aullido Final |
| Cualquiera | Tozudez |

Transiciones: **se le parte un cuerno** (I → II, se vuelve más rápida) y **el cielo le
responde** (II → III, arde en blanco y pega un 25 % más).

Los rayos son `strikeLightningEffect`, es decir **solo el efecto visual**, y el daño lo
aplica el plugin a mano. Un rayo de verdad prende fuego al terreno y esto es un survival.

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
- **Nada de armas flotantes en el Caballero.** Se quitaron a propósito las cinco
  habilidades en las que las lanzas brotaban, llovían o formaban muros: leídas en juego
  parecían magia, y el Caballero tiene que ser fuerza bruta. Las ondas de choque llevan
  un `Set` de UUID por lanzamiento, porque si no el anillo golpea al mismo jugador un tick
  tras otro mientras lo atraviesa y un solo pisotón lo mata.
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
jar --create --file Anomaly-1.2.0.jar -C build .
```

El `pom.xml` está para quien tenga Maven; `paper-api` es `provided`.
WorldGuard **no** es dependencia de compilación a propósito: el hook es por reflexión.

Al subir de versión hay que tocar **tres sitios**: `pom.xml` (`<version>` y `<finalName>`),
`plugin.yml` (`version:`) y la constante `VERSION` de `AnomalyPlugin` (la lee `/anomaly info`).
