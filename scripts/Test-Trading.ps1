$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$previousPassword = $env:ANTMILLION_TEST_DB_PASSWORD
$previousRootPassword = $env:ANTMILLION_TEST_ROOT_PASSWORD
$composeArgs = @('compose', '-p', 'antmillion-test', '-f', 'docker-compose-test.yml')
$started = $false
$testExitCode = 1

Push-Location $projectRoot
try {
    # 프로세스에서만 사용하는 일회성 접속 정보를 생성하며 .env.local이나 db.properties는 읽지 않습니다.
    $env:ANTMILLION_TEST_DB_PASSWORD = [Guid]::NewGuid().ToString('N')
    $env:ANTMILLION_TEST_ROOT_PASSWORD = [Guid]::NewGuid().ToString('N')
    $existing = & docker @composeArgs ps --all --quiet
    if ($LASTEXITCODE -ne 0) { throw 'Unable to inspect the test Compose project.' }
    if ($existing) { throw 'antmillion-test already exists. Stop that test run before starting another.' }

    $started = $true
    & docker @composeArgs up -d --wait --wait-timeout 180
    if ($LASTEXITCODE -ne 0) {
        & docker @composeArgs logs --tail 60 mysql-test
        throw 'Disposable test MySQL failed to start.'
    }

    & mvn -B '-Dproject.build.sourceEncoding=UTF-8' '-Dtest=IsolatedTestDataSourceTest,IsolatedTestDatabaseTest,TradingEngineServiceTest,TradingExecutionAtomicityTest,OrderExecutionConcurrencyTest,StockOrderModifyTest,InterestControllerTest,KisStockMasterSourceTest,StockPreservationTest,StockSyncSchedulerTest,StockSyncJobTest,KisSubscriptionRegistryTest,OrderQuoteSubscriptionSchedulerTest,OrderQuoteSubscriptionIntegrationTest,MockDevelopmentModeTest' test
    $testExitCode = $LASTEXITCODE

    # 테스트 실패 시에도 모든 합성 테스트 데이터가 롤백 또는 정리되었는지 확인합니다.
    $sql = 'SELECT (SELECT COUNT(*) FROM member) + (SELECT COUNT(*) FROM account) + (SELECT COUNT(*) FROM stock) + (SELECT COUNT(*) FROM asset) + (SELECT COUNT(*) FROM stock_order) + (SELECT COUNT(*) FROM trade_log) + (SELECT COUNT(*) FROM realized_pnl) + (SELECT COUNT(*) FROM stock_sync_run);'
    $remaining = $sql | & docker @composeArgs exec -T mysql-test sh -c 'MYSQL_PWD=$MYSQL_PASSWORD exec mysql --protocol=TCP -h 127.0.0.1 -u antmillion_test antmillion_test --batch --skip-column-names'
    if ($LASTEXITCODE -ne 0 -or "$remaining".Trim() -ne '0') {
        throw 'Fixture rollback verification failed.'
    }
    Write-Host 'Fixture rollback verified: no test rows remain.'
} finally {
    try {
        if ($started) {
            & docker @composeArgs down
            if ($LASTEXITCODE -ne 0) { throw 'Unable to remove the disposable test Compose project.' }
        }
    } finally {
        $env:ANTMILLION_TEST_DB_PASSWORD = $previousPassword
        $env:ANTMILLION_TEST_ROOT_PASSWORD = $previousRootPassword
        Pop-Location
    }
}
exit $testExitCode
