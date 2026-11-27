param([switch]$Restart, [switch]$InfrastructureOnly)
$ErrorActionPreference='Stop'
$labRoot=Split-Path -Parent $PSScriptRoot
$runtime=Join-Path $PSScriptRoot '.local'
New-Item -ItemType Directory -Force -Path $runtime | Out-Null
$kubectl=(Get-Command kubectl -ErrorAction Stop).Source
foreach($entry in @(@('gateway','gateway-service','18080:8080'),@('nacos','nacos','18848:8848'),@('es','elasticsearch','19200:9200'),@('client','client-frontend','8787:8787'),@('admin','admin-frontend','8788:8788'))) {
 if($InfrastructureOnly -and $entry[0] -notin @('nacos','es')){continue}
 if($entry[0] -in @('client','admin')){
  $exists=& $kubectl --context docker-desktop -n bilimili get service $entry[1] --ignore-not-found -o name
  if(-not $exists){continue}
 }
 $pidFile=Join-Path $runtime ($entry[0]+'-forward.pid')
 $oldProcess=$null
 if(Test-Path -LiteralPath $pidFile){
  $savedPid=[int](Get-Content -LiteralPath $pidFile)
  $oldProcess=Get-CimInstance Win32_Process -Filter "ProcessId=$savedPid" -ErrorAction SilentlyContinue
  if($oldProcess -and ($oldProcess.Name -ne 'kubectl.exe' -or $oldProcess.CommandLine -notlike "*port-forward*svc/$($entry[1])*$($entry[2])*")){
   # Windows may reuse a PID after restart. Never stop the unrelated process.
   Write-Warning "Ignoring stale $($entry[0]) PID record; the unrelated process is left running."
   $oldProcess=$null
  }
 }
 if($oldProcess -and -not $Restart){continue}
 if($oldProcess){Stop-Process -Id $savedPid -ErrorAction Stop}
 $process=Start-Process -FilePath $kubectl -ArgumentList @('--context','docker-desktop','-n','bilimili','port-forward',"svc/$($entry[1])",$entry[2],'--address','127.0.0.1') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runtime ($entry[0]+'-forward.log')) -RedirectStandardError (Join-Path $runtime ($entry[0]+'-forward.err.log'))
 $process.Id | Set-Content -LiteralPath $pidFile
 Write-Output "$($entry[0]): localhost:$($entry[2].Split(':')[0]) (PID $($process.Id))"
}
