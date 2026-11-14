# Manually invoked Forge build probe. Never modifies Arcana or deploys software.
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$ForgeSha = '583714b8bfbd9cb04f8bc39ad95861190caf7691'
$Worktree = Join-Path $Root 'third_party\forge'
$Reports = Join-Path $Root 'reports'
foreach ($command in @('git', 'java', 'mvn', 'python')) {
  if (-not (Get-Command $command -ErrorAction SilentlyContinue)) {
    throw "Missing required command: $command (Python 3 needed)"
  }
}
New-Item -ItemType Directory -Path (Join-Path $Root 'third_party') -Force | Out-Null
New-Item -ItemType Directory -Path $Reports -Force | Out-Null
if (-not (Test-Path (Join-Path $Worktree '.git'))) {
  & git clone --quiet --filter=blob:none --no-checkout 'https://github.com/Card-Forge/forge.git' $Worktree
  if ($LASTEXITCODE -ne 0) { throw 'Forge clone failed' }
}
& git -C $Worktree fetch --quiet --depth 1 origin $ForgeSha
if ($LASTEXITCODE -ne 0) { throw 'Forge pinned commit fetch failed' }
& git -C $Worktree checkout --quiet --detach $ForgeSha
if ($LASTEXITCODE -ne 0) { throw 'Forge checkout failed' }
$Actual = (& git -C $Worktree rev-parse HEAD).Trim()
if ($Actual -ne $ForgeSha) { throw "Unexpected Forge commit $Actual" }
@(
  "Timestamp UTC: $((Get-Date).ToUniversalTime().ToString('o'))",
  (& cmd /c 'java -version 2>&1' | Out-String),
  (& mvn -version | Out-String)
) | Set-Content -Path (Join-Path $Reports 'environment.txt')
$Start = Get-Date
Push-Location $Worktree
try {
  $OldPreference = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  try {
    & mvn -B -pl forge-ai -am test *> (Join-Path $Reports 'forge-build.log')
    $ExitCode = $LASTEXITCODE
  } finally { $ErrorActionPreference = $OldPreference }
} finally { Pop-Location }
$Duration = [int]((Get-Date) - $Start).TotalSeconds
& python (Join-Path $Root 'scripts\collect_maven_results.py') --root $Worktree --out (Join-Path $Reports 'forge-build.json') --sha $ForgeSha --exit-code $ExitCode --duration-seconds $Duration
if ($LASTEXITCODE -ne 0) { throw 'Result collection failed' }
if ($ExitCode -ne 0) { throw "Forge Maven build/test failed (exit $ExitCode); see reports/forge-build.log" }
Write-Output 'Forge Maven build/test completed. NOT a gameplay or Android acceptance test.'
