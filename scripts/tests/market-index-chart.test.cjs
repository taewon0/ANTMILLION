const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { join } = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

async function indexScreen(responseFor) {
    const source = readFileSync(join(__dirname, '../../src/main/webapp/resources/js/main/main.js'), 'utf8');
    const requests = [];
    const plotted = [];
    const cards = new Map();
    let fitCount = 0;
    const context = vm.createContext({
        contextPath: '/antmillion',
        document: { querySelector: selector => cards.get(selector) },
        window: { dispatchEvent() {}, addEventListener() {} },
        Event: class {},
        setTimeout: callback => callback(),
        LightweightCharts: {
            CandlestickSeries: 'candlestick',
            createChart: () => ({
                addSeries: () => ({ setData: data => plotted.push(JSON.parse(JSON.stringify(data))) }),
                timeScale: () => ({ fitContent: () => fitCount++ }),
                resize() {}
            })
        },
        fetch: async url => {
            requests.push(url);
            const base = url.endsWith('0001') ? 2500 : 800;
            return { json: async () => responseFor ? responseFor(base) : [
                { stck_bsop_date: '20261021', bstp_nmix_prpr: '0' + (base + 0.5),
                    bstp_nmix_oprc: '' + (base - 10.25), bstp_nmix_hgpr: '' + (base + 10.75), bstp_nmix_lwpr: '' + (base - 20) },
                { stck_bsop_date: '20261020', bstp_nmix_prpr: '' + (base - 0.5),
                    bstp_nmix_oprc: '' + (base - 11.25), bstp_nmix_hgpr: '' + (base + 9.75), bstp_nmix_lwpr: '' + (base - 21) }
            ] };
        }
    });
    for (const name of ['kospi', 'kosdaq']) {
        const elements = new Map();
        const chart = { parentElement: { clientWidth: 800, clientHeight: 280 } };
        elements.set('#main-' + name + '-chart', chart);
        for (const selector of ['.main-market-index-price', '.main-change-value', '.main-change-percent']) {
            elements.set(selector, { textContent: '', classList: { toggle() {} } });
        }
        cards.set('.main-grid-' + name, { querySelector: selector => elements.get(selector), elements });
    }
    const dateStart = source.indexOf('function formatDate(');
    const dateEnd = source.indexOf('function createMainStockItemHTML(', dateStart);
    const chartStart = source.indexOf('function initializeCharts(');
    const chartEnd = source.indexOf('function initializeMissionButton(', chartStart);
    assert(dateStart >= 0 && dateEnd > dateStart && chartStart >= 0 && chartEnd > chartStart,
        '실제 지수 차트·헤더·날짜 변환 함수를 실행합니다.');
    vm.runInContext(source.slice(dateStart, dateEnd) + source.slice(chartStart, chartEnd), context);
    context.initializeCharts();
    await new Promise(resolve => setImmediate(resolve));
    return { requests, plotted, cards, fitCount };
}

test('코스피·코스닥 기존 URL·소수 JSON으로 날짜 정렬과 캔들 표시 유지', async () => {
    const screen = await indexScreen();
    assert.deepEqual(screen.requests, [
        '/antmillion/api/kis/marketIndex/0001', '/antmillion/api/kis/marketIndex/1001'
    ]);
    assert.deepEqual(screen.plotted, [2500, 800].map(base => [
        { time: { year: 2026, month: 10, day: 20 }, open: base - 11.25, high: base + 9.75, low: base - 21, close: base - 0.5 },
        { time: { year: 2026, month: 10, day: 21 }, open: base - 10.25, high: base + 10.75, low: base - 20, close: base + 0.5 }
    ]));
    assert.equal(screen.fitCount, 2);
});

test('원본 최신순 응답으로 지수 현재값과 전일 대비 표시 유지', async () => {
    const screen = await indexScreen();
    for (const [name, base] of [['kospi', 2500], ['kosdaq', 800]]) {
        const elements = screen.cards.get('.main-grid-' + name).elements;
        assert.equal(elements.get('.main-market-index-price').textContent, '' + (base + 0.5));
        assert.equal(elements.get('.main-change-value').textContent, '+1.00');
        assert.equal(elements.get('.main-change-percent').textContent, '+' + (100 / (base - 0.5)).toFixed(2) + '%');
    }
});

test('최대 100개 지수 응답의 전체 캔들과 최신 두 값의 전일 대비 표시 유지', async () => {
    for (const count of [99, 100]) {
        const screen = await indexScreen(base => Array.from({ length: count }, (_, i) => {
            const date = new Date(Date.UTC(2026, 9, 23 - i)).toISOString().slice(0, 10).replaceAll('-', '');
            return { stck_bsop_date: date, bstp_nmix_prpr: '' + (base + 0.5 - i),
                bstp_nmix_oprc: '' + (base - i), bstp_nmix_hgpr: '' + (base + 1 - i),
                bstp_nmix_lwpr: '' + (base - 1 - i) };
        }));
        assert.equal(screen.fitCount, 2);
        for (const [index, name, base] of [[0, 'kospi', 2500], [1, 'kosdaq', 800]]) {
            const candles = screen.plotted[index];
            assert.equal(candles.length, count);
            const oldest = new Date(Date.UTC(2026, 9, 23 - count + 1));
            assert.deepEqual(candles[0], {
                time: { year: oldest.getUTCFullYear(), month: oldest.getUTCMonth() + 1, day: oldest.getUTCDate() },
                open: base - count + 1, high: base - count + 2, low: base - count,
                close: base + 1.5 - count
            });
            assert.deepEqual(candles.at(-1), { time: { year: 2026, month: 10, day: 23 },
                open: base, high: base + 1, low: base - 1, close: base + 0.5 });
            const elements = screen.cards.get('.main-grid-' + name).elements;
            assert.equal(elements.get('.main-market-index-price').textContent, '' + (base + 0.5));
            assert.equal(elements.get('.main-change-value').textContent, '+1.00');
            assert.equal(elements.get('.main-change-percent').textContent,
                '+' + (100 / (base - 0.5)).toFixed(2) + '%');
        }
    }
});
