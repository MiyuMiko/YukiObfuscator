$ErrorActionPreference = 'Stop'

$projectRoot = $PSScriptRoot
$sourceRoot = Join-Path $projectRoot 'src/main/java'
$buildRoot = Join-Path $projectRoot 'build'
$classesRoot = Join-Path $buildRoot 'classes'
$jarPath = Join-Path $buildRoot 'yuki-obfuscator.jar'

if (Test-Path -LiteralPath $buildRoot) {
    Remove-Item -LiteralPath $buildRoot -Recurse -Force
}
New-Item -ItemType Directory -Path $classesRoot | Out-Null

$sources = Get-ChildItem -LiteralPath $sourceRoot -Filter '*.java' -Recurse | ForEach-Object FullName
if (-not $sources) {
    throw 'No Java source files found.'
}

& javac --release 17 -encoding UTF-8 -d $classesRoot $sources
if ($LASTEXITCODE -ne 0) { throw 'javac failed.' }

& java (Join-Path $projectRoot 'tools/JarBuilder.java') $classesRoot $jarPath dev.yuki.obfuscator.Main
if ($LASTEXITCODE -ne 0) { throw 'JAR packaging failed.' }

Write-Host "Built $jarPath"
