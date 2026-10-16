# Regenera los modelos de block-display.com que usa el CONDE en EDM.
# Uso: powershell -File tools/edm/modelos.ps1 [-Check]
param([switch]$Check)
$ErrorActionPreference = 'Stop'
$aqui = Split-Path -Parent $MyInvocation.MyCommand.Path
$repo = Resolve-Path (Join-Path $aqui '..\..')
$fuentes = Join-Path $aqui 'fuentes'
$salida = Join-Path $repo 'plugins\edm-src\src\main\resources\models'
$conversor = Join-Path $aqui 'bde-export.py'
$extra = @()
if ($Check) { $extra = @('--check') }

& python -I $conversor (Join-Path $fuentes 'light_magic_circle.bdengine') `
    --name conde-circulo --output (Join-Path $salida 'conde-circulo.json') `
    --title 'Light Magic Circle animated' --source-url 'https://www.block-display.com/bd/56202' `
    --author 'TheDuckInBlue' --license 'BDL BY' --fit floor `
    --sha256 569beb41a350cf270532e406ce91223d62da17451e7065f4d141188bb064daac `
    --expect 'block=48,text=48' `
    --recolor 'yellow_concrete_powder=RED_CONCRETE' --text-color B3122E @extra
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

# El Sello y el Pentaculo: el circulo del Cuerno de Calamity en rojo, con calaveras y velas en lugar de sus items.
$swaps = @()
foreach ($i in 'redstone', 'glowstone_dust', 'gunpowder', 'sugar') { $swaps += @('--item-swap', "$i=RED_CANDLE:block:2.5") }
foreach ($i in 'blaze_powder', 'golden_apple', 'porkchop', 'ender_pearl', 'nether_star', 'diamond', 'totem_of_undying', 'amethyst_shard') {
    $swaps += @('--item-swap', "$i=WITHER_SKELETON_SKULL:item:1.6")
}
& python -I $conversor (Join-Path $fuentes 'magic_circle_spell.bdengine') `
    --name conde-sello --output (Join-Path $salida 'conde-sello.json') `
    --title 'Magic Circle Spell' --source-url 'https://www.block-display.com/bd/140451' `
    --author 'ExoticPlunger' --license 'BDL BY' --fit floor --static --drop-text `
    --sha256 413636ea04217f7e1a1006e5aa1305236f7c52ae88602e9d3225649952e28947 `
    --expect 'block=392,item=8' --recolor 'yellow_concrete=RED_CONCRETE' @swaps @extra
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

# La bola de Sangre Ardiente ya no sale de un .bdengine: la genera un script (solo bloques).
& python -I (Join-Path $aqui 'gen-bola.py') --output (Join-Path $salida 'conde-bola.json') @extra
exit $LASTEXITCODE
