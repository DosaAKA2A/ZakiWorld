# EDM - nucleo de Ederus

Fusion de **Rip 3.3.5**, **Anomaly 1.14.1**, **EderusMain 1.4.0** (libros encantados y
avisos de las tiendas de MobCoins) y la **tienda propia** en un solo plugin.

## Como esta montado

`EDMPlugin` es el unico plugin que ve Bukkit. Cada pieza vive como un **modulo**
(`net.ederus.edm.Module`), que implementa `org.bukkit.plugin.Plugin` delegando en el
nucleo. Gracias a eso el codigo original no hubo que reescribirlo: sigue registrando
eventos, programando tareas y creando `NamespacedKey` igual que cuando era un plugin
suelto.

Dos cosas que el modulo NO delega, y son deliberadas:

- **`getName()` devuelve el nombre historico** (`Rip`, `Anomaly`, `EderusMain`). Asi
  `new NamespacedKey(plugin, "boss")` sigue dando `anomaly:boss` y las entidades ya
  marcadas en el mundo se reconocen despues de la fusion. Cambiarlo romperia los jefes
  y los cuerpos a medio camino.
- **`getDataFolder()` da `plugins/EDM/<id>`**, no `plugins/EDM`.

## Modulos

| id | Que es | Comandos |
|----|--------|----------|
| `rip` | Efectos de kill y muerte | `/rip` |
| `anomaly` | Jefes por fases y botin | `/anomaly` (`/anomalia`, `/anom`) |
| `core` | Libros encantados y avisos de tiendas | `/main` (`/ederus`, `/edmain`), `/ederuslibro` |
| `tienda` | Tienda propia: compra, venta, topes y registro | `/etienda` (`/etnd`) |

Se apagan por separado en `plugins/EDM/config.yml` (`modulos.<id>: false`). Si uno
revienta al arrancar, se anota en consola y los otros siguen: antes eran tres plugins y
un fallo solo se llevaba el suyo, aqui ese aislamiento hay que ponerlo a mano.

## Datos

Al primer arranque copia `plugins/Rip`, `plugins/Anomaly` y `plugins/EderusMain` a
`plugins/EDM/{rip,anomaly,core}`. **No borra nada**: para volver atras basta con reponer
los tres jars viejos.

## Compilar

Paper 26.1 esta compilado con Java 25, asi que **hace falta JDK 25** (con el 21 el
compilador rechaza las clases de paper-api). Maven:

    mvn -f pom.xml package    # JAVA_HOME apuntando a un JDK 25

La dependencia es `io.papermc.paper:paper-api:26.1.2.build.74-stable`. Ojo: Paper cambio
el versionado y **ya no existe** `26.1.2-R0.1-SNAPSHOT`, que es lo que piden los pom
viejos de Anomaly y Rip.

## Cambios respecto a los plugins sueltos

- `Material.CHAIN` no existe en 26.1: el efecto *Cadenas Espectrales* usa `IRON_CHAIN`.
- `EderusMain` se registra a mano como ejecutor de `/main` y `/ederuslibro`. Antes era la
  clase del plugin y Bukkit le enrutaba los comandos sola; ahora la clase del plugin es
  EDM y sin esto los dos comandos solo imprimian su linea de uso.
- `Settings` de Anomaly acepta `Plugin` en vez de `JavaPlugin`.

## El modulo `tienda`

Sustituto propio de EconomyShopGUI: menu, motor, precio dinamico, Ofertas y
Demandas, pantalla de cantidad y buscador.

Dos reglas del motor que no se tocan:

1. **Primero se quitan los items, despues se paga.** Si el banco falla, se
   devuelven. Al reves, un error de Vault regala el dinero y el item.
2. **Solo se compran items "a pelo"**, comparando contra un `ItemStack` recien
   creado con `isSimilar()`. Enumerar meta por meta (nombre, lore,
   encantamientos, PDC...) siempre se queda corto en la version siguiente. Sin
   esto la tienda se tragaria un MMOItems por el precio de su material.

El catalogo (`plugins/EDM/tienda/precios.yml`) **se niega a cargar** si encuentra
un bucle de precio o una clave repetida, y en ese caso deja el catalogo anterior
en pie en vez de arrancar con precios malos.

La clave de un articulo es el material, o `MATERIAL:VARIANTE` para los spawners
(`SPAWNER:PIG`): los 8 de Ederus son todos `SPAWNER` y solo cambian en el mob.
Al entregarlos, el motor construye el spawner con su mob dentro; si no pudiera,
aborta antes de cobrar.

Necesita **Vault**, que se engancha en el primer tick y no en `onEnable`: el
proveedor de economia se registra en su propio `onEnable` y el orden de carga
entre plugins no esta garantizado.

El catalogo se genera con `plugins/ederus-src/EderusTienda-src/scripts/migrar-esgui.js`
a partir de los YAML de EconomyShopGUI.

### La pantalla de cantidad (1.3.0)

La ventana "COMPRANDO -> X": botones de -32/-8/-1 y +1/+8/+32, un boton que pide
la cantidad exacta por el chat, el minimo, el maximo y el total ANTES de pagar.

- Los tres saltos, el multiplicador del shift y si el click normal la abre se
  cambian en `config.yml` (`cantidad:`). En modo `directo` la tienda vuelve a
  comprar de 1 en 1 con el click y la pantalla se abre con **Q**.
- **La pantalla no calcula nada**: el precio, el total y el maximo salen de
  `Motor.totalCompraDe`, `Motor.totalVentaDe` y `Motor.maximoCompra/maximoVenta`,
  que son los mismos que usa la venta. Si cada uno hiciera su cuenta, un dia
  enseñaria un numero y cobraria otro.
- Si ahora mismo no se puede ni una unidad NO se abre: se lanza la operacion
  normal para que el motor diga el motivo de verdad (te faltan $400, no te cabe).
- El maximo dice **por que** es ese (dinero, espacio, tope, oferta del dia...).
  Un tope sin explicacion parece un fallo del plugin.

### El buscador (1.3.0)

Brujula en el menu principal, `/shop <texto>` o `/etienda buscar <texto>`. Busca
por clave y por nombre visible, asi que "hierro" encuentra el Iron ingot. Los
resultados se enseñan como una categoria virtual (`@buscar:<texto>`), con lo que
heredan paginacion, lore y clicks sin codigo aparte.

Los dos comparten `EntradaChat`, que pregunta por el chat: cancela la linea para
que no salga en publico, caduca al minuto y atiende la respuesta con `runTask`
porque el evento de chat es asincrono.

### Tope diario de servidor

`rotacion.tope-diario-oferta` y `tope-diario-demanda` son el "Quedan hoy X en el
mundo" del lore. **Desde la 1.3.0 un 0 lo quita** (antes el minimo era 1 y poner
0 dejaba el articulo agotado desde el primer segundo, justo lo contrario).
