param([string]$ImageTag=('frontend-'+(Get-Date -Format 'yyyyMMdd-HHmmss')), [switch]$SkipBuild)
$ErrorActionPreference='Stop'
$backendRoot=Split-Path -Parent $PSScriptRoot
$homeworkRoot=Split-Path -Parent $backendRoot
function Run-Checked { param([string]$Program,[string[]]$Arguments)
 & $Program @Arguments
 if($LASTEXITCODE -ne 0){throw "Command failed: $Program (exit $LASTEXITCODE)"}
}
if(-not $SkipBuild){ Run-Checked docker @('pull','nginx:alpine') }
foreach($frontendName in @('client','admin')){
 $frontendRoot=Join-Path $homeworkRoot ('hhjt-frontend-'+$frontendName+'-master')
 Push-Location -LiteralPath $frontendRoot
 try {
  if(-not $SkipBuild){
   Run-Checked npm.cmd @('ci','--no-audit','--no-fund')
   Run-Checked npm.cmd @('run','build')
   Run-Checked docker @('build','-t',"bilimili/${frontendName}-frontend:${ImageTag}",'.')
  }
  $manifest=Get-Content -Raw -LiteralPath ($frontendName+'-deployment.yaml')
  $manifest=$manifest.Replace("bilimili/${frontendName}-frontend:frontend-20261127", "bilimili/${frontendName}-frontend:${ImageTag}")
  $manifest | & kubectl --context docker-desktop apply -f -
  if($LASTEXITCODE -ne 0){throw 'Frontend manifest apply failed'}
  Run-Checked kubectl @('--context','docker-desktop','-n','bilimili','rollout','status',"deployment/${frontendName}-frontend",'--timeout=180s')
 } finally { Pop-Location }
}
& "$PSScriptRoot/start-local.ps1" -Restart
Write-Output 'Client: http://localhost:8787 ; Admin: http://localhost:8788'
