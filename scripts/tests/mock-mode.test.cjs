const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { join } = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

function extractFunction(source, name) {
    let start = source.indexOf('function ' + name + '(');
    assert(start >= 0, name);
    if (source.slice(start - 6, start) === 'async ') start -= 6;
    let end = source.indexOf('{', start);
    let depth = 1;
    while (depth && ++end < source.length) {
        if (source[end] === '{') depth++;
        if (source[end] === '}') depth--;
    }
    assert.equal(depth, 0);
    return source.slice(start, end + 1);
}

function screen(page, enabled, failingPath, allowReal = false) {
    const source = readFileSync(join(__dirname, '../../src/main/webapp/resources/js/', page), 'utf8');
    const price = { textContent: '0원' };
    const item = {
        getAttribute: () => 'TEST01',
        querySelector: selector => selector === '.stocklist-price' ? price : null
    };
    const requests = [];
    const callbacks = new Map();
    const context = vm.createContext({
        window: { kisMockEnabled: enabled },
        contextPath: '/antmillion',
        document: {
            querySelectorAll: () => [item],
            querySelector: selector => selector.startsWith('.stocklist-item[') ? item : null,
            getElementById: id => id === 'detail-current-price-h3' ? price : null
        },
        console: { log() {}, warn() {}, error() {} },
        fetch: async (url, options = {}) => {
            requests.push({ url, options });
            if (!allowReal && !url.includes('/api/kis/mock/')) throw new Error('Unexpected KIS websocket request: ' + url);
            return {
                ok: !url.endsWith(failingPath || 'no-failure'), status: 404,
                json: async () => ({ success: true, isRunning: true, stockCount: 1 })
            };
        },
        stompClient: {
            connected: true,
            subscribe(topic, callback) {
                callbacks.set(topic, callback);
                return { unsubscribe() { callbacks.delete(topic); } };
            }
        },
        stompConnected: true,
        currentStockCodes: ['TEST01'],
        subscribedTopics: {},
        currentFetchController: null,
        pendingSubscription: false,
        sentimentTimeout: null,
        hogaTimeout: null,
        isMarketOrder: false,
        lastRealtimePrice: null,
        updateChartRealtime() {},
        checkFomoInRealtime() {},
        subscribeStockHoga() {},
        AbortController,
        clearTimeout() {},
        setTimeout(callback, delay) {
            if (delay === 700 || delay === 300) callback();
            return 1;
        },
        unsubscribeFrontendOnly() {
            callbacks.clear();
            context.subscribedTopics = {};
        }
    });
    const start = source.indexOf('const isMockMode =');
    const marker = 'window.checkMockStatus = checkMockStatus;';
    const end = source.indexOf(marker, start) + marker.length;
    assert(start >= 0 && end > start);
    vm.runInContext(source.slice(start, end), context);
    for (const name of ['subscribeCurrentStocks', 'subscribeStockTopic', 'updateStockRealtimePrice', 'unsubscribeAllStocks']) {
        vm.runInContext(extractFunction(source, name), context);
    }
    return { context, requests, callbacks, price };
}

for (const page of ['stocklist/stocklist.js', 'stock/detail.js']) {
    test(page + ': 기본 설정에서는 콘솔 함수도 mock 요청을 보내지 않음', async () => {
        const { context, requests } = screen(page, false);
        assert.equal(await context.enableMockMode(), false);
        assert.equal(await context.disableMockMode(), false);
        assert.equal(await context.checkMockStatus(), null);
        assert.equal(requests.length, 0);
    });

    test(page + ': 개발 프로필에서는 KIS 없이 등록·구독·방송 후 실제 가격 표시 갱신', async () => {
        const { context, requests, callbacks, price } = screen(page, true);
        assert.equal(await context.subscribeCurrentStocks(), true);
        assert.deepEqual(requests.map(r => r.url.split('/').pop()), ['add-stocks', 'start']);
        const stocks = JSON.parse(requests[0].options.body);
        assert.deepEqual(stocks, [{ stockCode: 'TEST01', basePrice: 10000 }]);
        const callback = callbacks.get('/topic/kis-trade/presentTEST01');
        assert(callback, 'Must use the existing price topic');
        callback({ body: JSON.stringify({ stckPrpr: '12345', prdySign: '1', prdyCtrt: '1.2', shnuRate: '0.55' }) });
        assert.equal(price.textContent, '12,345원');
        context.unsubscribeAllStocks();
        assert.equal(callbacks.size, 0);
        assert.equal(requests.length, 2, 'Mock unload must not unsubscribe shared KIS subscriptions');
    });

    test(page + ': 등록 실패 시 방송을 시작하지 않음', async () => {
        const { context, requests, callbacks } = screen(page, true, '/add-stocks');
        assert.equal(await context.enableMockMode(), false);
        assert.equal(requests.length, 1);
        assert.equal(callbacks.size, 0);
    });

    test(page + ': 중지·초기화·상태 조회 후에도 개발 모드를 유지', async () => {
        const { context, requests } = screen(page, true);
        assert.equal(await context.disableMockMode(), true);
        assert.equal((await context.checkMockStatus()).stockCount, 1);
        assert.equal(await context.subscribeCurrentStocks(), true);
        assert.deepEqual(requests.map(r => r.url.split('/').pop()), ['stop', 'clear', 'status', 'add-stocks', 'start']);
    });

    test(page + ': 일반 모드는 HTTP 없이 기존 STOMP 토픽으로 구독', async () => {
        const { context, requests } = screen(page, false, undefined, true);
        await context.subscribeCurrentStocks();
        assert.equal(requests.length, 0);
        assert(context.subscribedTopics.TEST01, '현재가 토픽을 구독해야 합니다.');
    });
}
