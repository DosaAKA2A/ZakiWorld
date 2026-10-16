# Modelos de block-display.com en EDM (formato 2)

EDM reproduce modelos hechos en el editor BDEngine (ficheros `.bdengine`, contenedor PRJ2 gzip con `scene.json`),
como hace Calamity con el portal de la Parca. El `.bdengine` original NUNCA va dentro del jar: un conversor
(`tools/edm/bde-export.py`) lo pasa a un JSON saneado que solo guarda geometria, textos, texturas de cabeza y
fotogramas de animacion. Nada de NBT, comandos, URLs ni ajustes del editor.

## JSON saneado (`src/main/resources/models/<nombre>.json`)

```
{
  "format": 2,
  "name": "conde-circulo",
  "source": "https://www.block-display.com/bd/56202",   (informativo)
  "author": "TheDuckInBlue", "license": "BDL BY",         (informativo)
  "sourceSha256": "<sha256 del .bdengine>",
  "bounds": {"radius": 1.0, "minY": 0.0, "maxY": 0.2},    (tras normalizar: radio horizontal 1,0 y suelo en y=0)
  "groups": [
    {"id": "g1", "name": "Group 4", "parent": null,
     "matrix": [16 valores, FILA MAYOR, local al padre (o al mundo si parent es null)],
     "pivot": [x, y, z],                                   (opcional; por omision 0,0,0)
     "keys": [ {"time": 0, "position": [x,y,z], "rotation": [rx,ry,rz], "scale": [sx,sy,sz]}, ... ]  (opcional)
    }
  ],
  "pieces": [
    {"id": "<uuid>", "group": "g1" | null,
     "kind": "block" | "head" | "item" | "text",
     "matrix": [16 valores, fila mayor, LOCAL a su grupo (o al mundo si group es null)],
     "brightness": {"sky": 15, "block": 15},               (opcional)
     "material": "RED_CONCRETE",                            (kind block: nombre de org.bukkit.Material)
     "texture": "<valor base64 de la propiedad textures>",  (kind head)
     "material": "WITHER_SKELETON_SKULL",                   (kind item: solo *_SKULL o *CANDLE; los pone --item-swap)
     "text": "ᚦ", "color": "#B3122E", "background": 0, "shadow": false  (kind text)
    }
  ]
}
```

Reglas:
- Las matrices son las del editor (BDE exporta por filas; JOML las lee por columnas: `new Matrix4f().set(valores).transpose()`).
- Un grupo con `keys` se anima como en `ModeloStargate` de Calamity: `translation(p).rotateXYZ(r).scale(s).translate(-pivot)`
  sustituye a su `matrix` en ese tiempo; `time` esta en ticks del editor (20 = 1 s). Entre claves se interpola linealmente.
- La matriz final de una pieza = mundo * cadena de grupos * matriz local. El cargador la aplica con
  `Display#setTransformationMatrix` e interpolacion de 2 ticks.
- `bounds` sirve para escalar: el cargador recibe el radio o la altura deseada y multiplica.
- Recolor: el conversor cambia materiales segun un mapa (por ejemplo `yellow_concrete_powder -> RED_CONCRETE`);
  el cargador tambien acepta un mapa de sustitucion al instanciar, para reutilizar un modelo con otra paleta.

## Cargador (`net.ederus.edm.anomaly.fx.ModeloBde`)

- `ModeloBde.cargar("conde-circulo")` lee el recurso una vez (cache) y valida: format 2, matrices afines finitas,
  materiales existentes, textos sin componentes, texturas base64 validas.
- `modelo.instanciar(Location base, Opciones)` crea los displays (BlockDisplay / ItemDisplay con cabeza / TextDisplay)
  con brillo 15, interpolacion 2, `setDisplayWidth/Height` segun los bounds, y devuelve una `Instancia`.
- `Instancia.tick(int t)`: aplica las claves del modelo (si las hay), la rotacion extra por tick que pida la opcion,
  el crecimiento por pieza (retardo por angulo + smoothstep, como `ModeloCirculo` de Calamity) y el desmontaje
  (encoger y salir disparado). `Instancia.retirar()` borra todo. Todas las entidades van a la lista que indique
  el dueño (en un jefe, `spawned`).
