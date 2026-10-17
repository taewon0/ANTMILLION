$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$previousPassword = $env:ANTMILLION_TEST_DB_PASSWORD
$previousRootPassword = $env:ANTMILLION_TEST_ROOT_PASSWORD
$composeArgs = @('compose', '-p', 'antmillion-test', '-f', 'docker-compose-test.yml')
$started = $false
$testExitCode = 1

Push-Location $projectRoot
try {
    # Process-local disposable credentials; never read .env.local or db.properties.
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

    & mvn -B '-Dproject.build.sourceEncoding=UTF-8' '-Dtest=IsolatedTestDataSourceTest,IsolatedTestDatabaseTest,TradingEngineServiceTest,StockOrderModifyTest,InterestControllerTest,KisStockMasterSourceTest,StockPreservationTest' test
    $testExitCode = $LASTEXITCODE

    # All fixture rows must have rolled back, including after failed tests.
    $sql = 'SELECT (SELECT COUNT(*) FROM member) + (SELECT COUNT(*) FROM account) + (SELECT COUNT(*) FROM stock) + (SELECT COUNT(*) FROM asset) + (SELECT COUNT(*) FROM stock_order) + (SELECT COUNT(*) FROM trade_log) + (SELECT COUNT(*) FROM realized_pnl);'
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
