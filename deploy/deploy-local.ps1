param([string]$Maven='mvn.cmd', [string]$ImageTag=('lab-'+(Get-Date -Format 'yyyyMMdd-HHmmss')), [switch]$SkipBuild)
$ErrorActionPreference='Stop'
$repo=Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $repo
function Run-Checked { param([string]$Program,[string[]]$Arguments)
 & $Program @Arguments
 if($LASTEXITCODE -ne 0){throw "Command failed: $Program (exit $LASTEXITCODE)"}
}
$env:BILIMILI_IMAGE_TAG=$ImageTag
Run-Checked python @('deploy/render_local.py')
if(-not $SkipBuild){
 Run-Checked $Maven @('-B','-ntp','-pl','gateway_service,user-service,video-service,article-service,message-service','-am','package')
 foreach($module in @('gateway_service','user-service','video-service','article-service','message-service')){
  $name=$module.Replace('_','-')
  Run-Checked docker @('build','--provenance=false','-t',"bilimili/${name}:${ImageTag}",$module)
 }
}
Run-Checked kubectl @('--context','docker-desktop','apply','-f','deploy/.local/infrastructure.json')
foreach($name in @('mysql','redis','nacos','elasticsearch')){
 Run-Checked kubectl @('--context','docker-desktop','-n','bilimili','rollout','status',"deployment/$name",'--timeout=300s')
}
& "$PSScriptRoot/start-local.ps1" -Restart -InfrastructureOnly
Start-Sleep -Seconds 2
Run-Checked python @('deploy/initialize_local.py')
Run-Checked kubectl @('--context','docker-desktop','apply','-f','deploy/k8s/applications.yaml')
foreach($name in @('user-service','video-service','article-service','message-service','gateway-service')){
 Run-Checked kubectl @('--context','docker-desktop','-n','bilimili','rollout','status',"deployment/$name",'--timeout=180s')
}
& "$PSScriptRoot/start-local.ps1" -Restart
Start-Sleep -Seconds 2
$pod=(& kubectl --context docker-desktop -n bilimili get pods -l app=user-service -o jsonpath='{.items[0].metadata.name}')
Run-Checked kubectl @('--context','docker-desktop','-n','bilimili','exec',$pod,'--','mkdir','-p','/data/media/demo')
Run-Checked kubectl @('--context','docker-desktop','-n','bilimili','cp','deploy/demo/.',"${pod}:/data/media/demo/")
Run-Checked python @('deploy/verify_local.py','--persistence')
Run-Checked python @('deploy/verify_local.py','--snapshot')
Run-Checked kubectl @('--context','docker-desktop','-n','bilimili','cp','deploy/demo/.',"${pod}:/data/media/demo/")
Run-Checked kubectl @('--context','docker-desktop','-n','bilimili','cp','deploy/.local/verification.json',"${pod}:/data/media/demo/verification.json")
Write-Output 'Verified demo: http://localhost:18080/media/demo/index.html'
