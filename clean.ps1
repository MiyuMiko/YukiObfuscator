$ErrorActionPreference = 'Stop'

$projectRoot = [System.IO.Path]::GetFullPath($PSScriptRoot)
$buildRoot = [System.IO.Path]::GetFullPath((Join-Path $projectRoot 'build'))
$expectedBuildRoot = [System.IO.Path]::GetFullPath((Join-Path $projectRoot 'build'))

if ($buildRoot -ne $expectedBuildRoot -or $buildRoot -eq $projectRoot) {
    throw "Refusing to clean unexpected path: $buildRoot"
}

if (Test-Path -LiteralPath $buildRoot) {
    Remove-Item -LiteralPath $buildRoot -Recurse -Force
    Write-Host "Removed $buildRoot"
} else {
    Write-Host 'Nothing to clean.'
}
