# Anomaly

Eventos anómalos para **Ederus**. Cada cierto tiempo (o cuando lo pide un operador) se
abre una grieta en un punto libre del mapa, sale un jefe por fases y quien lo mate se
lleva un botín que se configura entero desde el menú.

La intención de fondo es empujar a la gente a **salir del spawn** y a **pelear en grupo**:
el jefe escala con el número de jugadores, varias habilidades castigan dispersarse y el
botín se reparte entre todos los que participaron, no solo entre quien da el último golpe.

- **Versión:** 1.13.0
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
| `/anomaly logros` | Cuántas anomalías llevas derrotadas y cuáles te faltan |

Alias: `/anomalia`, `/anom`.

### El menú

- **Panel** — iniciar, **ir a la anomalía**, elegir, botín, ajustes, detener y estado en vivo.
  El botón de viaje te deja a diez bloques del jefe, en suelo firme y mirándolo: encima no,
  que caer dentro del alcance de un jefe que ya pelea es una muerte gratis.
- **Anomalías** — click para elegirla, click derecho para abrir su ficha,
  shift para encenderla o apagarla.
- **Ficha** — todas sus habilidades (fase, duración, enfriamiento y qué hace) y abajo
  los dos mandos de esa anomalía, izquierda para subir y derecha para bajar:
  - **Vida del jefe** — ±100 (±500 con shift). Enseña también cuánta vida tendría con
    cinco jugadores, que es el número que de verdad importa al calibrar.
  - **Daño de las habilidades** — un multiplicador que afecta a **todas** las habilidades
    de esa anomalía a la vez, ±0.1 (±0.5 con shift). Por defecto **1.0**. No toca el golpe
    cuerpo a cuerpo normal, que es un atributo de la entidad.
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
  en **rojo** y la Cabra en **blanco**. Sale del equipo de marcador (Minecraft no deja pintar
  el contorno de otra forma), por eso tiene que ser uno de los dieciséis colores con nombre.

  Una anomalía puede declarar `glowColor()` a **null**, y eso es una decisión de diseño, no
  un descuido: ni brilla ni levanta pilar de luz. Solo se sabe dónde está por las coordenadas
  del anuncio, y cuando llegas ya la tienes encima. Es el caso del **Conejo Asesino**.

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

## El Conejo Asesino

El killer bunny del Aether. Su gracia es la de siempre: **cada vez que muerde a alguien
se multiplica**. La copia también muerde, y también se multiplica, así que la pelea se le
va de las manos al grupo en segundos si no las limpian.

- **Tope de 20 copias vivas.** No es decorativo: sin él, veinte jugadores mordidos a la
  vez tiran el servidor.
- **Cuantas más copias vivas, menos daño recibe el grande** (hasta la mitad). Eso es lo
  que convierte la multiplicación en una mecánica en vez de en un estorbo: si el grupo se
  dedica solo al jefe, el jefe casi no baja. Hay que repartirse.
- **No brilla ni levanta pilar**, por petición expresa. Es la única que aparece por sorpresa.
- **Es indistinguible de sus copias**, y eso es el diseño, no un descuido: tamaño de conejo
  normal (sin `scale`), **sin nombre encima**, el mismo nombre oculto en todos para que ni
  el NBT lo delate, y las partículas de ambiente las sueltan todos por igual. Lo único que
  cambia entre el jefe y una copia es la vida y el daño, que no se ven. La barra de jefe es
  la única pista: solo baja cuando pegas al de verdad.
- Elemento **tierra**, campo abierto y seco.

| Fase | Habilidades |
|---|---|
| I — la plaga | Camada · Salto Asesino · Madriguera · Carrera en Zigzag · Patada Trasera |
| II — la marea | Enjambre · Frenesí · Mordisco Profundo · Campo de Madrigueras · Zarpazo Giratorio |
| III — la horda | Estampida de Pelaje · Salto Lunar · División Final · Mordida Final |
| Cualquiera | Devorar — se come una copia y se cura un 5 % con ella · **Cambiazo** — se intercambia el sitio con sus copias varias veces, con humo simultáneo en los dos puntos para que no se pueda deducir quién salió de dónde |

Transiciones: **ojos rojos** (I → II, saca cuatro copias y muerde más fuerte) y **la horda**
(II → III, se parte hasta el tope de golpe). Al morir, las copias se deshacen una a una.

---

## Storm Rider

Un ahogado con tridente montado en un **phantom gigante**. El tamaño es real, no un truco
de partículas: `Phantom#setSize(8)` escala el modelo **y la caja de golpe**, y
`setAnchorLocation` lo mantiene sobrevolando la arena en vez de irse a dar una vuelta.
Elemento **viento** (cumbres y cielo abierto), brillo **aqua**.

Tres fases deliberadamente desiguales:

- **Fase I — desde el aire.** Vuela a trece bloques y el cuerpo a cuerpo le hace un **20 %**
  del daño; las flechas entran enteras. Es lo que obliga a pelearla con arco. Seis
  habilidades: Lanza de Tormenta, Picado, Ojo del Huracán, Descarga, Bandada, Viento Cortante.
- **Fase II — a pie.** El phantom se parte y el jinete cae con un rayo encima. Repertorio
  corto a propósito: Barrido de Tridente, Maremoto, Ancla de Tormenta, Carga de Marea.
- **Fase III — berserker.** Saca el **segundo tridente**, sube velocidad de ataque a 4.0,
  movimiento a 0.52 y daño a 20. Frenesí de Tridentes, Doble Tajo, Tormenta Perfecta,
  Salto del Trueno.

Y en cualquier fase, Relámpago Guía.

**"Menos vida" en la fase 3 se hace subiendo el daño que recibe (×1.6), no bajándole la
vida máxima.** Bajársela le subiría la fracción de vida y lo devolvería a la fase 2, y el
combate entraría en bucle — el mismo trampa que con la resurrección del Caballero. El
efecto para quien pelea es idéntico: dura mucho menos.

Los tridentes que lanza se quedan clavados y se pueden recoger, lo que en un survival es
una fábrica de tridentes gratis. Se les quita la recogida y se marcan para que la limpieza
final se los lleve.

---

## El Leviatán de Sal

Un guardián anciano descomunal (`scale 2.6`) **en el fondo del mar**. Elemento **agua**,
brillo aqua oscuro. Es la primera anomalía que pelea sumergida de verdad: el buscador de
sitios baja por la columna de agua hasta el lecho y planta la arena tres bloques por
encima, exigiendo al menos ocho de profundidad.

**El problema obvio de una pelea sumergida es que sin pociones es una tortura, no un
combate.** Se resuelve con la propia ficción: mientras estás **dentro** de su arena
(22 bloques) el abismo te deja respirar y ver — poder de conducto, refrescado cada segundo.
En cuanto sales, se acaba el favor. Así la arena tiene borde sin paredes invisibles, y
huir del jefe pasa a ser peor idea que quedarse. El borde se dibuja con partículas forzadas
para que se vea dónde acaba el aire.

Quince habilidades: haz cargado de guardián, coro de espinas, remolino que te hunde,
columnas de burbujas que te disparan hacia arriba (lejos de él y cerca del borde), banco de
guardianes, haces encadenados que saltan de uno a otro, tinta que ciega, **Presión** (cuanto
más alto estás, más duele: castiga huir a la superficie), látigo de marea, torbellino de
espinas, rayo giratorio, implosión, maelstrom, mordida y el Canto de Sal.

---

## El Coro Abisal

**El jefe más distinto del catálogo: no se gana pegando más fuerte, se gana coordinándose.**

Tres cantores orbitan un núcleo **intocable**, unidos a él por haces de luz. El núcleo no
recibe daño mientras el coro cante. Hay que apagar a los cantores en el **orden que marcan
sus luces** — blanco, amarillo, rojo — y solo entonces se abre una ventana para castigarlo.

- **Acertar el orden** → el núcleo queda expuesto. La ventana empieza en 12 s y se acorta
  un segundo por cada ronda ganada.
- **Fallar el orden** → el coro se rehace entero, todos los presentes reciben daño y hay
  que volver a empezar.
- Los **Ecos** del Enjambre Abisal no cuentan para el orden: matarlos no avanza ni penaliza.
- Cada cambio de fase rehace el coro y cierra la ventana que hubiera.

Doce habilidades, menos que las demás **a propósito**: aquí el contenido es el puzzle, y
llenarlo de golpes lo taparía.

Este boss necesitó dos ganchos nuevos en `BossFight`: `onMinionDeath` (para comprobar el
orden) y `allowLongInvulnerability` (porque el vigilante que quita la invulnerabilidad a la
fuerza, pensado para animaciones que mueren a medias, aquí rompería la mecánica entera).

---

## Darkness

Un **enderman colosal** y, con diferencia, el jefe más duro del catálogo. Todo lo suyo
es morado, negro y ciego: no hay una sola habilidad que no quite visión. Brilla en
**morado** hasta la fase 3, donde él mismo se cambia a **blanco**.

- **Septeto** — se parte en siete dobles que vibran de rabia mientras el original se cura.
  Los dobles no atacan ni se mueven: son un escondite. Hay que dar con el de verdad y
  meterle 260 de daño para romperle el ritual; si no, recupera una barbaridad.
- **Coronas del Vacío** — de cuatro a ocho **columnas de faro moradas** girando a su
  alrededor, cada vez más rápido. Son entidades de visualización estiradas, la misma
  técnica del haz de Agonía en Rip: un faro de verdad exigiría construir la pirámide.
- **Campo Cinético** — sujeta a todos los de alrededor, los levanta despacio mientras él
  se carga vibrando, y revienta mandándolos volando muy lejos.
- **Colapso** — en la última fase crece hasta coloso y abre un **agujero negro** que se lo
  traga todo siete segundos antes de estallar.

Quince habilidades y **cero esbirros**: los siete dobles son su curación, no ayudantes.

---

## Herbola

Un **bogged** con una **amapola por arma** que convierte en jardín todo lo que pisa: musgo, azalea y flores, dejando
alfombra de musgo por donde pasa igual que un muñeco de nieve deja nieve. Encima lleva un
**loro rojo** cantándole, y ese canto es lo que la mantiene entera.

- **Fase I** — el loro le da regeneración, resistencia y velocidad mientras cante.
- **Fase II** — el loro se suelta, ataca **en picado** y cada impacto te **amarra al suelo**
  varios segundos. Es inmortal a propósito: no se puede quitar del medio.
- **Fase III** — llegan bandadas de loros rojos y verdes que **buscan altura, se tiran en
  picado y revientan** al tocar el suelo, empujando a quien pillen.

**MODIFICA EL MUNDO Y LO DEJA ASÍ**, por decisión del servidor: el jardín es permanente.
Precisamente por eso la **lista blanca** es lo más importante del boss — solo convierte
terreno natural (tierra, piedra, arena, nieve…) y **nunca** un cofre, una puerta ni nada
construido. Tope de 4000 bloques por pelea, y el log dice cuántos dejó.

**Al morir no acaba la pelea: empieza el llanto del Cantor.** El loro se eleva iluminado
en rojo mientras carga un círculo de 18 bloques durante **12 segundos**, con cuenta atrás y
avisando a cada uno si está dentro o fuera. Al final estalla: daño por cercanía y todo el
círculo convertido en jardín para siempre. Por eso Herbola alarga la espera de limpieza
(`deathAnimationTicks`) — con la de serie se borraba al Cantor a mitad de la cuenta atrás.

---

## La Quimera

Tres animales mal cosidos en uno: **cuerpo de fiera** (un ravager), una **cabra**
clavada en el lomo y una **cola de serpiente** arrastrándose detrás. No es un dibujo en
partículas: son entidades de verdad montadas unas sobre otras, que es la única forma
honesta de hacer una quimera cuando el servidor **no puede repintar una criatura**.

### Los cinco pilares

**La Quimera no se puede matar mientras quede uno de sus cinco pilares en pie.** No son
adornos ni texturas estiradas: son construcciones de **bloques de verdad** — base de
escaleras, columna de ladrillo de piedra, el **ladrillo cincelado** a la altura de la
cara y una losa de remate. Solo cede el cincelado; picar cualquier otra pieza no hace
nada, porque si se pudieran desmontar por abajo la mecánica se resolvería cavando. Al
partir el cincelado **se derrumba el pilar entero**.

Y ahí está la tensión: **esos mismos pilares son la única cobertura contra su mirada**,
así que cada uno que se tira es un escondite menos. El terreno se devuelve tal cual.

### La mirada de la cola

Quien mire de frente a la serpiente cuando levanta la cabeza **se queda de piedra** — y
petrificar no es matar, es **clavarte en el sitio** delante de una fiera de tres cabezas.
Tres salvaciones: apartar la vista, **el escudo levantado** (como Perseo: si además la
mirabas, la mirada rebota) o **ponerse detrás de un pilar**.

| Fase | Habilidades |
|---|---|
| I — la fiera | Mirada de la Cola · Embestida de la Fiera · Berrido de la Cabra · Nido de Víboras · Escupitajo Venenoso |
| II — la cabra rabiosa | Mirada en Barrido · Zarpazo Triple · Cornada Ascendente · Lluvia de Colmillos · Veneno Ancestral |
| III — la serpiente | La Mirada Entera · Pisotón de la Fiera |
| Cualquiera | Siseo |

Necesita `allowLongInvulnerability`: sin eso, el vigilante que quita la invulnerabilidad
a los veinte segundos haría inútiles los pilares.

---

## La Bruja

Una bruja con su **caldero** y un **Sapo Blanco** —rana de variante cálida— que la
acompaña **andando** a su lado. Elemento **tierra**, brillo **dorado**.

El caldero no va en la cabeza: quedaba fatal, un bloque enorme flotando que además
tapaba el nombre. Es lo que siempre tuvo que ser, **un caldero puesto en el suelo**
donde cocina, y aparece cuando le hace falta (al hervir y ya fijo en la fase 3).

El sapo es la mitad del jefe:

- **Fase I** — trota a su lado, intocable; le croa regeneración y resistencia.
- **Fase II** — **crece hasta 2.6** y pelea como esbirro fuerte con 140 de vida propia:
  salta encima de marcas, engancha con la lengua al que huye y da brincos por su
  cuenta. Se le puede matar, pero **matárselo la desata**: +20% de daño y más
  velocidad el resto de la pelea.
- **Fase III** — el caldero rebosa: el Gran Hechizo (círculo con cuenta atrás de diez
  segundos), murciélagos que ciegan y la Pócima Final sobre todos a la vez.

| Fase | Habilidades |
|---|---|
| I — el aquelarre | Pócima Virulenta · Caldero Hirviente · Maleficio · Canto del Sapo · Risa de Bruja · Hervor Súbito |
| II — el sapo baja | Salto del Sapo · Lengua Látigo · Lluvia de Sapos · Brebaje Oscuro |
| III — el caldero rebosa | El Gran Hechizo · Nube de Murciélagos · Pócima Final |
| Cualquiera | Trago Amargo |

Como es una bruja de vanilla, además bebe y lanza sus pociones por su cuenta; eso
viene gratis con la entidad y es sabor, no el plato. Al morir, el caldero se vuelca y
se derrama; si el sapo sigue vivo, se marcha solo dando brincos.

---

## El Mimic

El único jefe que no quiere que lo encuentres. Elemento **tierra** y, como el Conejo,
**sin brillo, sin pilar y sin nombre encima**: el camuflaje es el jefe entero. Cada
fase es un engaño distinto.

### Fase I — el rebaño

Al llegar a las coordenadas solo hay un **grupo de animales del bioma** (vacas y ovejas
en la llanura, zorros y conejos en la nieve, ocelotes y loros en la jungla…). Uno de
ellos es él. La única pista es la de siempre en un mimic: **pegarle**. Al recibir daño
**crece de golpe** — cuánto depende del animal: un pollo pasa a 3.4, una vaca a 2.5 —
y suelta sus habilidades: embestida, pisotón en ondas, el chillido con una voz que no
es suya y la estampida de señuelos. Si lo dejan demasiado rato a la vista, **Camuflaje**:
destello, cuerpo nuevo de otra especie, rebaño nuevo, y vuelta a no ser nadie. Así
hasta acabar la fase.

Que le han pegado se detecta **vigilando la vida en cada tick**, no por evento: así
cuentan la espada, las flechas y hasta el `/anomaly hurt` de las pruebas. El cuerpo
cambia con el truco del Storm Rider (entidad nueva, vida y máximo copiados).

### Fase II — los cofres

Se esconde **dentro de uno de cinco cofres** puestos en círculo. Los cuatro falsos
**muerden** a quien los toca (un shulker invisible de 1 de vida bajo cada cofre; al
romperlo, dentellada a su verdugo); el verdadero es **el propio jefe**, invisible y
encogido dentro del cofre — por eso la barra baja al acertar, que es la confirmación.
Mientras el grupo duda, **La Codicia** roe a todos los presentes con daño por tiempo
que **crece cuanto más tardan** (de 1 hasta 8 por tanda). Al encontrarlo revienta el
cofre, da la cara unos segundos y vuelve a esconderse. Así hasta el 33 %.

### Fase III — el robo de rostro

Se copia a un **jugador de verdad**: su cara (la cabeza con su skin), su armadura y
sus armas clonadas pieza a pieza, y **su nombre a secas** encima — sin estrella, como
un jugador más. Pega muchísimo más fuerte que el original. Y a la **mitad de la fase
se desata**: brillo rojo (el único momento en que brilla), velocidad de ataque 4.0,
más daño, más velocidad y el repertorio berserker — frenesí de golpes, salto
carnicero, torbellino de acero y la sombra que aparece por la espalda del copiado.

Al morir, los disfraces se le caen entre destellos, y debajo no había nada.

**No puede morirse sin haber engañado en las tres fases.** Con buen equipo, un solo
mandoble se llevaba el 40 % de la barra y el jefe se moría al reventar el cofre, sin
llegar nunca a robarle la cara a nadie. El `survivalFloor` de `BossFight` recorta el
golpe hasta dejarlo justo en el umbral: la fase salta en el tick siguiente y el suelo
se levanta solo.

---

## Rabby

**Un tipo bajito con cara de buena persona que está ahí parado saludando.** Y de
verdad no hace nada: se le puede rodear, mirar y hasta ignorar. Elemento **tierra**,
sin brillo y sin pilar de luz — es un vecino cualquiera hasta que deja de serlo.

El aspecto sale de una **skin de verdad**, y de la forma nativa: pelea un zombi
**invisible** con toda su IA, y encima lleva pegado un **MANNEQUIN** —una entidad viva
con forma de jugador y perfil de skin propio— que es lo que se ve. Los golpes que recibe
el maniquí se le pasan al jefe, así que para quien pelea el cuerpo visible *es* el jefe.
Es la única manera de que salga **exactamente** la skin pedida: no depende de que haya
LibsDisguises ni de que Mojang resuelva un nombre. El mecanismo vive en
`BossFight#wearShell` y lo comparte con el Mimic.

### Pegarle es la mala idea

**Rabby es pasivo hasta que alguien le pega.** Ese primer golpe cae un rayo, se pone
nombre de anomalía y a partir de ahí no para. Todo lo suyo es desplazamiento y castigo:

| Fase | Habilidades |
|---|---|
| I — los puños | Carrera Fantasma · Batazo · Pisotón Sónico · Ráfaga de Golpes |
| II — los combos | **Combo Aéreo** · Acoso Relámpago · Puño Cometa · Patada Giratoria |
| III — se acabó | **Concentración** · **Carga Devastadora** · Onda Expansiva · Tromba Final |
| Cualquiera | Paso Relámpago · Burla |

- **Combo Aéreo** es la firma: te **batea al cielo** como quien saca un home run, se
  **teletransporta** a tu altura, te mete una tanda de golpes ahí arriba siguiéndote el
  paso para que no te caigas a mitad, y te **clava contra el suelo** — el remate es lo
  que más duele, y la caída encima.
- **Concentración** es el interruptor de la pelea: un **rayo**, **brillo blanco** (el
  único que tiene) y **cinco veces el daño** durante quince segundos. Los combos, que ya
  multiplican de por sí, se vuelven una sentencia.
- **Carga Devastadora**: se traga las **estelas** de media arena —las mismas motas que
  convergen cuando muere el dragón, que es exactamente la lectura de "esto se está
  concentrando aquí"— durante ocho segundos y lo suelta de golpe. A bocajarro **revienta
  a cualquiera que no lleve buen equipo**; en el borde sigue siendo un susto muy serio.

Todo lo que levanta a un jugador pasa por `push`/`lift`, que conceden permiso de vuelo
temporal: sin eso el servidor expulsa por "volar" a mitad del combo.

---

## El Cazador

Un **piglin brute** vestido de cuero negro y con sombrero. No es fuerza bruta: es un
cazador, y lo que lo hace peligroso es que **no pelea siempre igual**. Cambia de arma
delante de ti, y con el arma cambia todo lo demás — **el arma que lleva en la mano
siempre dice lo que va a hacer**, que es lo que lo hace legible: si saca el hacha, viene.

| Fase | Cómo pelea |
|---|---|
| I — el acecho | **Ballesta y arco.** Se mantiene lejos y **retrocede si te acercas**: perseguirlo a la carrera es perder. Andanada de Ballesta · Lluvia de Flechas · Saeta Perforante · Marcar la Presa |
| II — el cepo | Siembra el suelo de **trampas**. Se ven —una placa que parpadea y hace clic— pero cubren tanto terreno que acabas pisando una, y revienta con muy buen radio. Cepo · Cepo Dirigido · Red de Cepos · Retirada Calculada |
| III — la estocada | Tira lo de lejos y saca la **lanza de netherita**, el hacha y la espada. Estocada de Lanza · Hachazo Descendente · Danza de Espada |
| Cualquiera | Cambio de Arma |

Además de sus habilidades tiene un **pulso de combate propio** que corre cada segundo:
mira a qué distancia estás y actúa sin esperar turno — lejos saca ballesta o arco y
reparte una ráfaga **entre varios objetivos**, a media distancia la lanza (que alcanza a
todo lo que pille en la línea) y encima el hacha o la espada. Sin eso se quedaba largos
ratos parado entre habilidad y habilidad, que es justo lo contrario de un genio de las
armas. **La lanza ya no es exclusiva de la fase 3**: la usa en cualquiera.

Las **minas se ven**: un bloque rojo levantado del suelo, con **contorno rojo** —los
displays admiten color libre, no solo los dieciséis del marcador— y un chispazo cada
segundo. La primera versión ponía una placa a ras de suelo que quedaba enterrada en la
hierba, y así la trampa castigaba la mala suerte en vez de castigar no mirar. Caducan
al minuto y medio: sin eso la arena acabaría siendo un campo de minas del que no se sale.

---

## Áragon

Una araña **enorme y lentísima** (escala 2.8: a 4.5 se quedaba flotando, porque con una caja de golpe tan grande el servidor la expulsa hacia arriba en cuanto roza un bloque). Ese es el diseño entero: Áragon casi no pelea. Se
arrastra, teje y pone huevos; **quien te muerde son sus hijas**, y son muchas.

- **Crías diminutas y a montones.** Salen a camadas de veinte y pico, del tamaño de un
  puño (atributo `scale`, que es todo lo que hace falta), corriendo más rápido de lo que
  se puede retroceder. Sueltas no son nada; en enjambre te comen. Tope de **ciento diez
  vivas**, y la arena se repone sola mientras haya menos de cuarenta y cinco.
- **Y atacan de verdad.** Las arañas de Minecraft son **neutrales de día**: sin forzarlas,
  el enjambre se quedaba paseando mientras el grupo pegaba tranquilamente a la madre. Cada
  segundo se les reparte objetivo entre todos los presentes y se empuja a las que se
  descuelgan, que es lo que convierte "muchas arañas" en "te comen".
- **Los huevos.** Bolas blancas plantadas por la arena, que **laten cada vez más rápido**
  según se acercan a abrirse. Si se rompen a tiempo no pasa nada; si no, **eclosionan** y
  sale otra camada. Son la única forma de que el enjambre deje de crecer, y por eso
  obligan a repartirse: unos al jefe, otros a los huevos.
- **La tela.** Telaraña de verdad, la que frena. Solo se teje sobre aire —nunca sustituye
  un bloque de nadie— y se devuelve entera al cerrar el evento.

Lleva **muy pocas partículas sueltas a propósito**: lo que tiene que llenar la pantalla
son las patas, no el confeti.

---

## El Piromante

Un **aldeano del desierto** —el de la túnica más naranja que hay— con armadura de cuero
teñida de rojo y una **vara de blaze**. Es el único jefe puramente mágico y puramente a
distancia: no tiene un solo golpe cuerpo a cuerpo, todo sale ardiendo de las manos, y
**encima persigue**, así que no se le gana quedándose lejos.

| Fase | Habilidades |
|---|---|
| I — la chispa | Bola de Fuego · Andanada de Brasas · Aliento de Fuego · Rastro de Brasas |
| II — la hoguera | Mar de Llamas · Meteoros · Muro de Fuego · Guardia de Brasas |
| III — el infierno | **Nova Ígnea** · Columna de Lava · Anillo de Cenizas |
| Cualquiera | Marca Ardiente |

Sus conos y sus bolas **apuntan al objetivo, no a donde mira**. Un aldeano gira la
cabeza por su cuenta, así que con la versión anterior, si no te tenía de frente, el ataque
no te tocaba y parecía que el jefe no hacía nada. Y la bola de fuego sale **una por
cabeza, hasta tres**: contra un grupo, apuntar a uno solo no se nota.

**Quema el suelo de verdad**, y de ahí sale su única regla dura: cada fuego que prende
pasa antes por WorldGuard, con dos bloques de margen. Dentro de una región protegida —de
la administración o el terreno de un jugador— **no se enciende nada**. Y todo lo que
prende queda anotado y se apaga al cerrar el evento: el jardín permanente es cosa de
Herbola; aquí no queda ni una brasa.

---

## El árbol de logros

Diecisiete logros: la raíz, uno por anomalía y **El que las vio todas**, que exige las quince.
Todos los de jefe son de tipo *challenge*, así que traen el marco morado y el sonido de
desafío de vanilla.

Minecraft solo lee logros desde un **datapack**, así que el plugin se escribe el suyo
dentro del mundo al arrancar y lo reescribe cuando sube `PACK_VERSION`. Tras instalarlo
por primera vez hace falta un `/minecraft:reload` o un reinicio; el log lo dice.

- El logro se concede a **todos los que participaron**, no a quien da el último golpe.
- Todos los iconos y el fondo son **texturas vanilla** a propósito: un datapack que apunte
  a una textura inexistente se ve como un cuadro morado y negro.
- `World#getWorldFolder()` devuelve la carpeta de la **dimensión** en Paper moderno
  (`world/dimensions/minecraft/overworld`). Los datapacks van en la raíz del mundo, así
  que la ruta se compone con `getWorldContainer()` y el nombre del nivel.
- El `pack.mcmeta` usa `min_format`/`max_format` como pares `[mayor, menor]`, calcado del
  datapack que genera el propio Bukkit. El viejo `pack_format` a secas ya no vale.

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
- **El daño de las habilidades pasa todo por `BossFight.hit()`.** Por eso el multiplicador
  configurable se aplica en un solo sitio. Si alguna habilidad futura llama a `player.damage()`
  directamente, se saltará el ajuste sin avisar.
- **El Storm Rider ES el phantom en la fase 1.** Primero fue montura, luego elytra, y al
  final lo correcto: el jefe es la propia criatura voladora y al entrar en fase 2 se
  estrella en picado y **cambia de cuerpo** al ahogado, copiando vida y máximo para que la
  barra ni se entere. Que el phantom no sea una montura quita de en medio toda la fricción
  que daba su IA de vuelo.
- **Nadie debe ser expulsado por volar.** Cualquier empuje con componente vertical
  concede permiso de vuelo temporal al jugador y se lo retira después. Sin eso el servidor
  echaba a la gente en cuanto una habilidad la levantaba. Va por `push()` **y por
  `lift()`**: quien llame a `setVelocity` a pelo se salta el permiso, que es justo lo que
  hacía el campo cinético de Darkness.
- **Para que se vea una skin concreta, MANNEQUIN.** Es una entidad viva con forma de
  jugador y perfil propio (`setProfile`), así que la skin es exactamente la pedida. El
  patrón está en `BossFight#wearShell`: pelea el mob invisible de siempre, se ve el
  maniquí, y `AnomalyManager` redirige al jefe los golpes que reciba el maniquí. No hace
  falta LibsDisguises ni fiarse de que Mojang resuelva un nombre.
- **Escalar un mob demasiado lo deja flotando.** Con una caja de golpe enorme el servidor
  lo expulsa hacia arriba en cuanto roza un bloque. Áragon a 4.5 no pisaba el suelo; a 2.8
  sí. Si aun así hace falta, un empujón hacia abajo cuando no está apoyado lo arregla.
- **Las arañas son NEUTRALES de día.** Un enjambre que no ataca no es un enjambre: hay que
  repartirles objetivo a mano, igual que a cualquier esbirro que deba pelear sí o sí.
- **Un mob con IA propia mira a donde quiere.** Cualquier ataque en cono que use
  `getDirection()` del jefe fallará cuando su IA le gire la cabeza. Los conos apuntan al
  objetivo, nunca a la orientación de la entidad.
- **Con grupos, los ataques de un solo objetivo se diluyen.** Lo que se lanza a "uno" debe
  repartirse entre varios o barrer un área; si no, cuatro de cada cinco jugadores no ven
  nunca la habilidad.
- **El fondo de un logro es un SPRITE, no una ruta de textura.** En 26.1.2 lo válido es
  `minecraft:gui/advancements/backgrounds/end` (o stone, nether, adventure, husbandry).
  Poner `minecraft:textures/block/sculk.png` —que fue lo correcto en su día— pinta el
  cuadriculado morado y negro de textura ausente y deja el título ilegible. Se comprobó
  abriendo los propios logros de vanilla dentro del jar del servidor.
- **Al añadir una anomalía hay que subir también `PACK_VERSION`.** Es lo único que hace
  que el datapack se reescriba; si no, el servidor arranca con el catálogo nuevo y los
  logros viejos.
- **NUNCA editar los `.java` con PowerShell.** `Get-Content`/`Set-Content` de PS 5.1 leen
  en ANSI un fichero UTF-8 sin BOM y devuelven mojibake en cada `✦` y cada tilde. Para
  reemplazos masivos, Python con `encoding="utf-8"` explícito.
- **Nada de `jump_boost` con amplificador 128 para anular el salto.** Era el truco de las
  versiones viejas, cuando el amplificador era un byte y 128 se leía como −128. Hoy son
  ciento veintiocho niveles de salto: al intentar saltar te ibas al cielo y el servidor te
  expulsaba por moverte demasiado rápido. El amarre (`BossFight#root`) anula el salto a
  mano, tick a tick, cortando solo la velocidad que sube.
- **Un mob con pasajero pierde media IA de combate.** Herbola se quedaba mirando con el
  Cantor en la cabeza. No basta con confiar en que "el esqueleto ya disparará solo": hay
  que renovarle el objetivo a menudo y lanzarle la flecha a mano.
- **Las partículas de bloque y de objeto no pintan nada sin su dato.** `FALLING_DUST`,
  `BLOCK_CRUMBLE`, `DUST_PILLAR` e `ITEM` exigen un `BlockData` o un `ItemStack`;
  llamarlas sin él no da error, simplemente no se ve. `Compat.defaultData` los rellena.
- **Variar la partícula, no solo el color.** Durante un tiempo casi todo se pintó con
  `DUST` recoloreado y se acababa notando: todas las anomalías soltaban la misma bolita
  de colores. Hay un catálogo entero — `VAULT_CONNECTION` para el reclamo de los cofres,
  `INFESTED` para lo que está vivo y no debería, `ENTITY_EFFECT` para las serpientes,
  `REVERSE_PORTAL` para lo que se concentra, `DUST_PILLAR` para la piedra que cae.
- **La cabra necesita agresividad escrita a mano.** `Goat` es un animal PASIVO en Minecraft:
  no tiene IA que persiga ni que ataque, por eso se quedaba parada mirando.
- **El menú de botín no puede cancelar los clics del inventario propio.** Si se cancelan
  todos, el jugador no puede coger nada con el cursor y el menú parece congelado.
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
jar --create --file Anomaly-1.13.0.jar -C build .
```

El `pom.xml` está para quien tenga Maven; `paper-api` es `provided`.
WorldGuard **no** es dependencia de compilación a propósito: el hook es por reflexión.

Al subir de versión hay que tocar **tres sitios**: `pom.xml` (`<version>` y `<finalName>`),
`plugin.yml` (`version:`) y la constante `VERSION` de `AnomalyPlugin` (la lee `/anomaly info`).
