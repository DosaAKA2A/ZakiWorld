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

Sustituto propio de EconomyShopGUI. De momento **sin interfaz**: solo el motor,
que es donde un fallo no rompe una partida sino que imprime dinero.

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
