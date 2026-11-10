param([Parameter(Mandatory=$true)][ValidatePattern('^emulator-[0-9]+$')][string]$Serial, [string]$Adb='adb', [string]$Label='api36-development', [ValidateSet('range','ignore-range','tamper')][string]$Mode='range')
$ErrorActionPreference='Stop'
$taskRoot=Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$taskOut=Join-Path $taskRoot "build/phase5/$Label"
New-Item -ItemType Directory -Force $taskOut | Out-Null
function Invoke-Adb([string[]]$Arguments) {
    $taskText=(& $Adb -s $Serial @Arguments 2>&1 | Out-String)
    if($LASTEXITCODE -ne 0){throw $taskText}
    return $taskText
}
if((Invoke-Adb @('shell','getprop','sys.boot_completed')).Trim() -ne '1'){throw 'Emulator not booted'}
foreach($taskRelative in @('app/build/outputs/apk/debug/app-debug.apk','app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')) {
    $taskSource=Join-Path $taskRoot $taskRelative
    $taskHash=(Get-FileHash $taskSource -Algorithm SHA256).Hash
    $taskCopy=Join-Path $taskOut (Split-Path $taskSource -Leaf)
    Copy-Item -LiteralPath $taskSource -Destination $taskCopy
    if((Get-FileHash $taskCopy -Algorithm SHA256).Hash -ne $taskHash -or (Get-FileHash $taskSource -Algorithm SHA256).Hash -ne $taskHash){throw 'APK changed during snapshot'}
    Invoke-Adb @('install','-r','-t',$taskCopy) | Out-Null
}
Invoke-Adb @('shell','am','force-stop','jp.linkserver.beastlocator') | Out-Null
Invoke-Adb @('shell','appops','set','jp.linkserver.beastlocator','REQUEST_INSTALL_PACKAGES','allow') | Out-Null
if([int](Invoke-Adb @('shell','getprop','ro.build.version.sdk')).Trim() -ge 33){
    Invoke-Adb @('shell','pm','grant','jp.linkserver.beastlocator','android.permission.POST_NOTIFICATIONS') | Out-Null
}
Invoke-Adb @('shell','svc','wifi','enable') | Out-Null
Invoke-Adb @('reverse','tcp:37571','tcp:37571') | Out-Null
$taskPython=(Get-Command python).Source
$taskStdout=Join-Path $taskOut 'server.out'
$taskServerLog=Join-Path $taskOut 'requests.jsonl'
if(Test-Path $taskServerLog){throw 'Use a new run label; keep previous HTTP evidence'}
$taskProcess=Start-Process -FilePath $taskPython -ArgumentList @('-u',(Join-Path $PSScriptRoot 'range-server.py'),
    '--apk',(Join-Path $taskOut 'app-debug.apk'),'--log',$taskServerLog,'--mode',$Mode) -WorkingDirectory $taskRoot -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput $taskStdout -RedirectStandardError (Join-Path $taskOut 'server.err')
$taskServer=$null
try {
    for($taskTry=0;$taskTry -lt 20;$taskTry++) {
        $taskRaw=[string](Get-Content $taskStdout -Raw -ErrorAction SilentlyContinue)
        if(-not [string]::IsNullOrWhiteSpace($taskRaw)){$taskServer=$taskRaw | ConvertFrom-Json;break}
        Start-Sleep -Milliseconds 250
    }
    if(-not $taskServer){throw 'HTTP server did not start'}
    foreach($taskStage in @('stage1DownloadAndKeepCheckpointForForceStop','stage2ResumeInNewProcessAndPrepareVerifiedInstallRequest')) {
        Invoke-Adb @('shell','am','force-stop','jp.linkserver.beastlocator') | Out-Null
        $taskResult=Invoke-Adb @('shell','am','instrument','-w','-r','-e','resumeMode',$Mode,'-e','class',"jp.linkserver.beastlocator.RangeProcessTest#$taskStage",
            'jp.linkserver.beastlocator.test/androidx.test.runner.AndroidJUnitRunner')
        $taskResult | Set-Content -Encoding utf8 (Join-Path $taskOut "$taskStage.txt")
        if($taskResult -notmatch 'OK \(1 test\)' -or $taskResult -match 'FAILURES|Process crashed|INSTRUMENTATION_FAILED'){throw "Failed $taskStage"}
        "$taskStage passed"
    }
    $taskRequests=@(Get-Content $taskServerLog | ForEach-Object {$_ | ConvertFrom-Json})
    $taskRanges=@($taskRequests | Where-Object {$_.status -eq 206 -and $_.start -gt 0})
    if($Mode -eq 'range' -and ($taskRanges.Count -lt 1 -or @($taskRequests | Where-Object {$_.start -eq 0}).Count -ne 1)){throw 'HTTP evidence does not prove persisted Range continuation'}
    $taskEvidence=Invoke-Adb @('shell','run-as','jp.linkserver.beastlocator','cat','files/range-process-evidence.json') | ConvertFrom-Json
    if(($Mode -eq 'range' -and $taskEvidence.restoredBytes -ne $taskRanges[0].start) -or $taskEvidence.installerLaunches -ne 0 -or $taskEvidence.explicitInstallerLaunches -ne 1){throw 'Restored progress differs from HTTP resume or installer launch count is incorrect'}
    if($Mode -eq 'ignore-range' -and @($taskRequests | Where-Object {$_.range -and $_.status -eq 200 -and $_.start -eq 0}).Count -lt 1){throw 'Missing 200 fallback evidence'}
    if($Mode -eq 'tamper' -and ($taskEvidence.restoredBytes -ne 0 -or @($taskRequests | Where-Object {$_.start -eq 0}).Count -lt 2)){throw 'Corrupted partial was not discarded'}
    $taskEvidence | ConvertTo-Json | Set-Content (Join-Path $taskOut 'apk-evidence.json')
    Invoke-Adb @('shell','run-as','jp.linkserver.beastlocator','rm','files/range-process-evidence.json') | Out-Null
    $taskRequests | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $taskOut 'http-evidence.json')
    'Real signed APK resumed in a separate process and prepared successfully'
} finally {
    Invoke-Adb @('reverse','--remove','tcp:37571') | Out-Null
    if($taskServer) {
        $taskOwned=Get-CimInstance Win32_Process -Filter "ProcessId=$($taskServer.pid)"
        if($taskOwned -and $taskOwned.CommandLine -like '*range-server.py*' -and $taskOwned.CommandLine.Contains($taskOut)){
            Stop-Process -Id $taskServer.pid
        }
    } elseif(-not $taskProcess.HasExited){$taskProcess.Kill()}
}
