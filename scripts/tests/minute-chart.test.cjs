const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { join } = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

function extractFunction(source, name) {
    const start = source.indexOf('function ' + name + '(');
    assert(start >= 0, name);
    let end = source.indexOf('{', start);
    let depth = 1;
    while (depth && ++end < source.length) {
        if (source[end] === '{') depth++;
        if (source[end] === '}') depth--;
    }
    assert.equal(depth, 0);
    return source.slice(start, end + 1);
}

async function chartScreen(page, rows) {
    const main = page === 'main';
    const source = readFileSync(join(__dirname, '../../src/main/webapp/resources/js/',
        main ? 'main/main.js' : 'stock/detail.js'), 'utf8');
    const requests = [];
    const plotted = [];
    const subscribed = [];
    let fitCount = 0;
    const price = { innerText: '' };
    const container = { clientWidth: 800, clientHeight: 280, innerHTML: '' };
    const context = vm.createContext({
        stockChart: null,
        candleSeries: null,
        currentCandleData: null,
        lastCandleUpdateTime: null,
        currentChartPeriod: null,
        contextPath: '/antmillion',
        document: { getElementById: id => id === 'detail-current-price-h3' ? price : container },
        console: { log() {}, warn() {}, error() {} },
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
        },
        subscribeStockToRealtime: stockCode => subscribed.push(stockCode)
    });
    const draw = main ? 'drawStockMinuteChart' : 'drawDetailChart';
    vm.runInContext(extractFunction(source, 'formatToTimestamp') + '\n' + extractFunction(source, draw), context);
    context[draw]('005930');
    await new Promise(resolve => setImmediate(resolve));
    return { requests, plotted, subscribed, fitCount, context, price };
}

for (const page of ['main', 'detail']) {
    test(page + ': 분봉 URL·문자열 가격·날짜 정렬·마지막 캔들 유지', async () => {
        const screen = await chartScreen(page, [
            { stck_bsop_date: '20261021', stck_cntg_hour: '153000', stck_prpr: '071000',
                stck_oprc: '70000', stck_hgpr: '72000', stck_lwpr: '69000' },
            { stck_bsop_date: '20261021', stck_cntg_hour: '152900', stck_prpr: '70000',
                stck_oprc: '69000', stck_hgpr: '71000', stck_lwpr: '68000' }
        ]);
        const expectedTime = minute => {
            const date = new Date(2026, 9, 21, 15, minute, 0);
            return Math.floor(date.getTime() / 1000) - (page === 'detail' ? date.getTimezoneOffset() * 60 : 0);
        };
        assert.deepEqual(screen.requests, ['/antmillion/api/kis/stream/005930']);
        assert.deepEqual(screen.plotted, [[
            { time: expectedTime(29), open: 69000, high: 71000, low: 68000, close: 70000 },
            { time: expectedTime(30), open: 70000, high: 72000, low: 69000, close: 71000 }
        ]]);
        assert.equal(screen.context.currentCandleData.close, 71000);
        assert.equal(screen.context.lastCandleUpdateTime, expectedTime(30));
        assert.equal(screen.fitCount, 1);
        if (page === 'main') assert.deepEqual(screen.subscribed, ['005930']);
        else assert.equal(screen.context.currentChartPeriod, 'minute');
    });

    test(page + ': 빈 결과는 캔들·실시간 구독을 추가하지 않음', async () => {
        const screen = await chartScreen(page, []);
        assert.deepEqual(screen.requests, ['/antmillion/api/kis/stream/005930']);
        assert.deepEqual(screen.plotted, []);
        assert.deepEqual(screen.subscribed, []);
        assert.equal(screen.fitCount, 0);
    });
}
