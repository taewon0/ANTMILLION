const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { join } = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

async function chartScreen(period, rows) {
    const source = readFileSync(join(__dirname, '../../src/main/webapp/resources/js/stock/detail.js'), 'utf8');
    const start = source.indexOf('function getCanonicalTime(');
    const end = source.indexOf('// ===== DOM 로드 후 실행 =====', start);
    assert(start >= 0 && end > start, '실제 기간별 차트 함수와 날짜 변환 함수를 실행합니다.');
    const requests = [];
    const plotted = [];
    let fitCount = 0;
    const container = { clientWidth: 800, clientHeight: 280, innerHTML: '' };
    const context = vm.createContext({
        document: { getElementById: id => id === 'detail-stockChart' ? container : null },
        console: { error() {} },
        LightweightCharts: {
            CandlestickSeries: 'candlestick',
            createChart: () => ({
                addSeries: () => ({ setData: data => plotted.push(JSON.parse(JSON.stringify(data))) }),
                timeScale: () => ({ fitContent: () => fitCount++ })
            })
        },
        fetch: async url => {
            requests.push(url);
            return { json: async () => rows };
        }
    });
    vm.runInContext(source.slice(start, end), context);
    context.drawPeriodChart('005930', period);
    await new Promise(resolve => setImmediate(resolve));
    return { requests, plotted, fitCount, context };
}

for (const period of ['D', 'W', 'M', 'Y']) {
    test(period + ': 기존 URL·JSON으로 날짜 정렬과 캔들 표시 유지', async () => {
        const screen = await chartScreen(period, [
            { stck_bsop_date: '20261021', stck_clpr: '071000', stck_oprc: '70000', stck_hgpr: '72000', stck_lwpr: '69000' },
            { stck_bsop_date: '20261020', stck_clpr: '70000', stck_oprc: '69000', stck_hgpr: '71000', stck_lwpr: '68000' }
        ]);
        assert.deepEqual(screen.requests, ['/antmillion/api/kis/periodChart/005930?period=' + period]);
        assert.deepEqual(screen.plotted, [[
            { time: { year: 2026, month: 10, day: 20 }, open: 69000, high: 71000, low: 68000, close: 70000 },
            { time: { year: 2026, month: 10, day: 21 }, open: 70000, high: 72000, low: 69000, close: 71000 }
        ]]);
        assert.equal(screen.context.currentChartPeriod, period);
        assert.equal(screen.context.lastCandleUpdateTime, '2026-10-21');
        assert.equal(screen.fitCount, 1);
    });
}

test('빈 결과는 캔들을 추가하지 않음', async () => {
    const screen = await chartScreen('D', []);
    assert.deepEqual(screen.plotted, []);
    assert.equal(screen.fitCount, 0);
});
