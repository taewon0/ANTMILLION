const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { join } = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const ranks = () => Array.from({ length: 6 }, (_, i) => ({
    hts_kor_isnm: '합성종목' + (i + 1), mksc_shrn_iscd: '00000' + (i + 1), data_rank: '' + (i + 1),
    stck_prpr: i === 0 ? '071000' : '2000', acml_vol: '001234',
    prdy_ctrt: i === 0 ? '1.25' : i === 1 ? '-0.50' : null, isFavorite: false
}));

async function screen(main, data, fail = false) {
    const path = main ? 'main/main.js' : 'stocklist/stocklist.js';
    const source = readFileSync(join(__dirname, '../../src/main/webapp/resources/js', path), 'utf8');
    const container = { innerHTML: '' };
    const name = { textContent: '' };
    const code = { textContent: '' };
    const requests = [];
    const charts = [];
    const subscriptions = [];
    const errors = [];
    let paginationCount = 0;
    const context = vm.createContext({
        contextPath: '/antmillion', currentPage: 2, itemsPerPage: 2, totalPages: 0,
        currentStockCodes: [], stompConnected: true, isMockMode: false,
        document: {
            getElementById: () => container,
            querySelector: selector => selector.endsWith('-name') ? name : code
        },
        console: { log() {}, error: (...args) => errors.push(args) },
        setTimeout() {},
        getInterestCodesPromise: async () => ['000002'],
        attachMainFavoriteListeners() {}, attachMainStockItemListeners() {},
        attachFavoriteListeners() {}, attachStockItemListeners() {},
        drawStockMinuteChart: stockCode => charts.push(stockCode),
        subscribeMainStocks: stockCodes => subscriptions.push(Array.from(stockCodes)),
        subscribeCurrentStocks: () => subscriptions.push(Array.from(context.currentStockCodes)),
        renderPagination: () => paginationCount++,
        fetch: async url => {
            requests.push(url);
            if (fail) throw new Error('synthetic failure');
            return { json: async () => data };
        }
    });
    const start = source.indexOf(main ? 'function createMainStockItemHTML(' : 'function createStockItemHTML(');
    const end = source.indexOf(main ? 'function attachMainFavoriteListeners(' : 'function renderFavoriteStocks(', start);
    assert(start >= 0 && end > start, '실제 목록 조회·렌더링 함수를 실행합니다.');
    vm.runInContext(source.slice(start, end), context);
    if (main) context.renderMainStocks();
    else context.renderAllStocks();
    await new Promise(resolve => setImmediate(resolve));
    return { context, container, name, code, requests, charts, subscriptions, errors, paginationCount };
}

test('메인 기존 순위 URL·JSON으로 상위 5개·가격·대비율·관심 표시와 구독 유지', async () => {
    const result = await screen(true, ranks());
    assert.deepEqual(result.requests, ['/antmillion/api/kis/volumeRank']);
    assert.equal(result.errors.length, 0);
    assert.equal((result.container.innerHTML.match(/class="main-stocklist-item"/g) || []).length, 5);
    assert(!result.container.innerHTML.includes('data-id="000006"'));
    assert(result.container.innerHTML.includes('71,000원'));
    assert(result.container.innerHTML.includes('+1.25%'));
    assert(result.container.innerHTML.includes('-0.5%'));
    assert(result.container.innerHTML.includes('0.00%'));
    assert(result.container.innerHTML.includes('main-stocklist-favorite-btn active" data-id="000002"'));
    assert.equal(result.name.textContent, '합성종목1');
    assert.equal(result.code.textContent, '000001');
    assert.deepEqual(result.charts, ['000001']);
    assert.deepEqual(result.subscriptions, [['000001', '000002', '000003', '000004', '000005']]);
});

test('종목 목록 기존 페이징 URL·응답으로 페이지 순위·가격·관심 표시와 구독 유지', async () => {
    const result = await screen(false, { data: ranks().slice(0, 2), currentPage: 2, totalItems: 6, totalPages: 3 });
    assert.deepEqual(result.requests, ['/antmillion/api/kis/volumeRank/paged?page=2&size=2']);
    assert.equal(result.errors.length, 0);
    assert.equal(result.context.totalPages, 3);
    assert.equal(result.paginationCount, 1);
    assert(result.container.innerHTML.includes('stocklist-rank">3</span>'));
    assert(result.container.innerHTML.includes('stocklist-rank">4</span>'));
    assert(result.container.innerHTML.includes('71,000원'));
    assert(result.container.innerHTML.includes('합성종목2'));
    assert(result.container.innerHTML.includes('stocklist-favorite-btn active'));
    assert(result.container.innerHTML.includes('-0.5%'));
    assert.deepEqual(result.subscriptions, [['000001', '000002']]);
});

test('빈 순위는 메인·종목 목록을 비우고 기존 빈 구독·0페이지 처리 유지', async () => {
    const main = await screen(true, []);
    const list = await screen(false, { data: [], currentPage: 2, totalItems: 0, totalPages: 0 });
    for (const result of [main, list]) {
        assert.equal(result.container.innerHTML, '');
        assert.equal(result.errors.length, 0);
        assert.deepEqual(result.subscriptions, [[]]);
    }
    assert.deepEqual(main.charts, []);
    assert.equal(list.context.totalPages, 0);
});

test('조회 실패 시 메인 로그·종목 목록 기존 오류 안내 유지', async () => {
    const main = await screen(true, null, true);
    const list = await screen(false, null, true);
    assert.equal(main.errors.length, 1);
    assert.equal(list.errors.length, 1);
    assert.equal(list.container.innerHTML, '<p>데이터를 불러오는 중 오류가 발생했습니다.</p>');
    assert.deepEqual(main.subscriptions, []);
    assert.deepEqual(list.subscriptions, []);
});
