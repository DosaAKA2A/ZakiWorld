# EDM - nucleo de Ederus

Fusion de **Rip 3.3.5**, **Anomaly 1.14.1** y **EderusMain 1.4.0** (libros encantados y
avisos de las tiendas de MobCoins) en un solo plugin.

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
