# PhoenixCrates → ExcellentCrates 7

Migración de las cajas del Survival a ExcellentCrates 7.1.0 (nuestra rama `ederus`, en `C:\ec7\src`).

1. `node calidad-por-id.js <calidades.json> <carpeta con las .bak-calidades-20261006> calidad-por-id.json`
   asigna a cada premio su calidad con la misma lógica que `crates-calidades/generador.js`.
2. `python -I convertir.py <PhoenixCrates del Survival> <EDM/crates/config.yml del dev> calidad-por-id.json <salida>`
   escribe `objects/` (crate, reward, key, rarity). El peso de cada premio es
   P(calidad) × P(premio dentro de la calidad) × 10000, así que el sorteo plano de la 7 da exactamente
   el porcentaje que muestra la línea "◆ Calidad · x %" del lore.
   - La Legendaria toma calidades y pesos del módulo de calidades de EDM (diseño del 2026-10-12).
   - `cajaepica` y `vote` usan el diseño de `calidades.json`; en el Survival sus calidades no iban en orden.
   - Las demás conservan la probabilidad exacta del Survival.
3. `python -I llaves.py <database.db de PhoenixCrates> <data.db de ExcellentCrates>` copia las llaves
   virtuales a `key_storage`. Se corre con el servidor apagado, sobre la `data.db` que ya creó el plugin.
   Los topes globales agotados van a `reward_limits` con `player_id` = `00000000-0000-0000-0000-000000000000`.

Los ids de cajas y llaves no cambian: la rama `ederus` acepta `/crates key give|take|giveall` con la sintaxis
de la 6 y `%excellentcrates_keys_<llave>%`, incluso con ids que llevan guion bajo.
