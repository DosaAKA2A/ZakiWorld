# ZakiWorld Store 🟣

Tienda oficial de ZakiWorld — `store.zakiworld.net`

## Estado
- [x] Frontend con diseño propio (Dimensión Amatista) y animaciones
- [ ] Conectar cuenta de Tebex (checkout + API Headless)
- [ ] Datos reales: paquetes, precios, logo
- [ ] ID del servidor de Discord (widget activado)
- [ ] IP real del servidor

## Configuración
Editar el objeto `CONFIG` al inicio del `<script>` en `index.html`:
- `SERVER_IP` — IP del servidor de Minecraft
- `DISCORD_GUILD_ID` — ID del servidor de Discord
- `TEBEX_IDENT` — ident del checkout de Tebex.js

## Skins
Los avatares y renders de cuerpo usan mc-heads.net (gratuito, por username).

## Despliegue
GitHub Pages: Settings → Pages → Deploy from branch `main` / root.
Luego apuntar `store.zakiworld.net` con un CNAME a `<usuario>.github.io`.
