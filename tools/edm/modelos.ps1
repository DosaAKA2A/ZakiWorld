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

& python -I $conversor (Join-Path $fuentes 'Fireball.bdengine') `
    --name conde-bola --output (Join-Path $salida 'conde-bola.json') `
    --title 'Fireball' --source-url 'https://www.block-display.com/bd/54337' `
    --author 'Modxel' --license 'BDL BY' --fit center `
    --sha256 ab8a8a1b2ef3f5e5d960a4bc5e7ab0a0d45f39a2c040b837a8401a5bf820d9d8 `
    --expect 'head=33' @extra
exit $LASTEXITCODE
