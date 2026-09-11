$ErrorActionPreference = 'Stop'

& (Join-Path $PSScriptRoot 'build.ps1')
$fixture = Join-Path $PSScriptRoot 'examples/simple'
$testRoot = Join-Path $PSScriptRoot 'build/test-output'
$featureRoot = Join-Path $PSScriptRoot 'build/feature-output'
$featureRepeatRoot = Join-Path $PSScriptRoot 'build/feature-repeat-output'
$dryRoot = Join-Path $PSScriptRoot 'build/dry-output'
$invalidMapRoot = Join-Path $PSScriptRoot 'build/invalid-map-output'

if (Test-Path -LiteralPath $testRoot) {
    Remove-Item -LiteralPath $testRoot -Recurse -Force
}

& java -jar (Join-Path $PSScriptRoot 'build\yuki-obfuscator.jar') $fixture $testRoot --seed 42
if ($LASTEXITCODE -ne 0) { throw 'Obfuscator test run failed.' }

$map = Get-Content -LiteralPath (Join-Path $testRoot 'obfuscation-map.json') -Raw | ConvertFrom-Json
if (-not $map.variables.total -or -not $map.variables.result -or
    -not $map.variables.globalCount -or -not $map.variables.first -or -not $map.variables.second) {
    throw 'Expected variable mappings were not generated.'
}
if ($map.variables.Mode -or $map.variables.FAST -or $map.variables.SLOW -or
    $map.types.FAST -or $map.types.SLOW) {
    throw 'Enum names were incorrectly treated as variables.'
}
foreach ($name in @('alpha', 'beta', 'inheritedCount', 'values', 'operation', 'rawMessage',
    'currentValue', 'legacy', 'amount')) {
    if (-not $map.variables.$name) { throw "Expected mapping for $name was not generated." }
}
if ($map.variables.count_type) {
    throw 'A project-level type alias was incorrectly treated as a variable.'
}
foreach ($name in @('Accumulator', 'LegacyCounter', 'Mode', 'count_type')) {
    if (-not $map.types.$name) { throw "Expected type mapping for $name was not generated." }
}
if (($map.files.PSObject.Properties | Measure-Object).Count -ne 3) {
    throw 'Expected three source files to be renamed.'
}

$cpp = Get-ChildItem -LiteralPath $testRoot -Filter '*.cpp' | Select-Object -First 1
$content = Get-Content -LiteralPath $cpp.FullName -Raw
if ($content -match 'int\s+total\b' -or $content -notmatch '#include\s+"f_[A-Za-z]+\.hpp"' -or
    $content -notmatch [regex]::Escape($map.variables.total)) {
    throw 'Source identifiers or include path were not rewritten correctly.'
}
if ($content -notmatch '"total stays in strings"' -or $content -match '// total stays in comments' -or
    $content -match 'original block comment' -or
    $content -notmatch 'R"\(result stays in raw strings\)"') {
    throw 'Original comments were not removed safely.'
}

$cmake = Get-Content -LiteralPath (Join-Path $testRoot 'CMakeLists.txt') -Raw
if ($cmake -match '\bmain\.cpp\b' -or $cmake -match '\bcalculator\.hpp\b') {
    throw 'Build file paths were not rewritten.'
}

& java -jar (Join-Path $PSScriptRoot 'build\yuki-obfuscator.jar') $fixture $featureRoot --seed 42 `
    --keep-file 'main.cpp' --no-map --exclude-file (Join-Path $PSScriptRoot 'examples/exclude-names.txt') `
    --strength high --random-comments --comment-rate 100 --comment-length 12
if ($LASTEXITCODE -ne 0) { throw 'Feature option test run failed.' }
if (-not (Test-Path -LiteralPath (Join-Path $featureRoot 'main.cpp'))) {
    throw '--keep-file did not preserve main.cpp.'
}
if (Test-Path -LiteralPath (Join-Path $featureRoot 'obfuscation-map.json')) {
    throw '--no-map still produced a mapping file.'
}
$featureContent = Get-Content -LiteralPath (Join-Path $featureRoot 'main.cpp') -Raw
if ($featureContent -notmatch '\bresult\b' -or $featureContent -notmatch '\bglobalCount\b') {
    throw '--exclude-file did not preserve excluded names.'
}
if ($featureContent -notmatch '/\*[A-Za-z]{12}\*/') {
    throw 'Random English comments were not inserted.'
}
if ($featureContent -notmatch '\bv_[IlOo01]{24}\b') {
    throw 'High-strength identifiers were not generated.'
}
if ($featureContent -notmatch '"total stays in strings"' -or
    $featureContent -notmatch 'R"\(result stays in raw strings\)"') {
    throw 'Random comments changed a string literal.'
}
if ($featureContent -match '// total stays in comments') {
    throw 'An original comment survived before random comments were inserted.'
}
$inputLineCount = (Get-Content -LiteralPath (Join-Path $fixture 'main.cpp')).Count
$outputLineCount = (Get-Content -LiteralPath (Join-Path $featureRoot 'main.cpp')).Count
if ($inputLineCount -ne $outputLineCount) {
    throw 'Random comments changed source line count.'
}

& java -jar (Join-Path $PSScriptRoot 'build\yuki-obfuscator.jar') $fixture $featureRepeatRoot --seed 42 `
    --keep-file 'main.cpp' --no-map --exclude-file (Join-Path $PSScriptRoot 'examples/exclude-names.txt') `
    --strength high --random-comments --comment-rate 100 --comment-length 12
if ($LASTEXITCODE -ne 0) { throw 'Repeatability test run failed.' }
$featureHash = (Get-FileHash -LiteralPath (Join-Path $featureRoot 'main.cpp')).Hash
$repeatHash = (Get-FileHash -LiteralPath (Join-Path $featureRepeatRoot 'main.cpp')).Hash
if ($featureHash -ne $repeatHash) {
    throw 'Same seed did not produce deterministic random comments.'
}

& java -jar (Join-Path $PSScriptRoot 'build\yuki-obfuscator.jar') $fixture $dryRoot --seed 42 --dry-run
if ($LASTEXITCODE -ne 0) { throw 'Dry-run failed.' }
if (Test-Path -LiteralPath $dryRoot) {
    throw '--dry-run unexpectedly created output files.'
}

& java -jar (Join-Path $PSScriptRoot 'build\yuki-obfuscator.jar') $fixture $invalidMapRoot `
    --seed 42 --map '../outside.json'
if ($LASTEXITCODE -ne 2 -or (Test-Path -LiteralPath $invalidMapRoot)) {
    throw 'Invalid map path was not rejected before output creation.'
}

$compiler = Get-Command g++ -ErrorAction SilentlyContinue
if ($compiler) {
    & $compiler.Source -std=c++17 $cpp.FullName -o (Join-Path $testRoot 'sample.exe')
    if ($LASTEXITCODE -ne 0) { throw 'Generated C++ did not compile.' }
}

Write-Host 'All tests passed.'
