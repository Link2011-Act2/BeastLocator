param([Parameter(Mandatory=$true)][ValidatePattern('^emulator-[0-9]+$')][string]$Serial,[string]$Adb='adb',[string]$Label='api36-permission-v1')
$ErrorActionPreference='Stop'
$taskRoot=Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$taskOut=Join-Path $taskRoot "build/phase5/$Label"
New-Item -ItemType Directory -Force $taskOut|Out-Null
$taskStages=@('stage1DeniedDoubleSettingsPersistsIntent','stage2DeniedRestartAndCancelledSettingsRemainSingleAction','stage3GrantedRestartOffersDownloadWithoutReopeningSettings','stage4PreparedLeaseSurvivesPermissionRevocation','stage5GrantedPreparedLeaseOffersOneExplicitInstall')
foreach($taskIndex in 0..4){
    & $Adb -s $Serial shell am force-stop jp.linkserver.beastlocator
    $taskPermission=if($taskIndex -in @(2,4)){'allow'}else{'deny'}
    & $Adb -s $Serial shell appops set jp.linkserver.beastlocator REQUEST_INSTALL_PACKAGES $taskPermission
    $taskResult= & $Adb -s $Serial shell am instrument -w -e class "jp.linkserver.beastlocator.UpdatePermissionRecoveryTest#$($taskStages[$taskIndex])" jp.linkserver.beastlocator.test/androidx.test.runner.AndroidJUnitRunner
    $taskResult|Set-Content (Join-Path $taskOut "$($taskStages[$taskIndex]).log") -Encoding utf8
    if(($taskResult -join "`n") -notmatch 'OK \(1 test\)' -or ($taskResult -join "`n") -match 'FAILURES|INSTRUMENTATION_FAILED|Process crashed'){throw "Permission stage $taskIndex failed"}
    $taskStages[$taskIndex]
}
