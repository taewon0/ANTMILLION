const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { join } = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

function extract(source, name) {
    let start = source.indexOf('function ' + name + '(');
    assert(start >= 0, name);
    if (source.slice(start - 6, start) === 'async ') start -= 6;
    let end = source.indexOf('{', start), depth = 1;
    while (depth && ++end < source.length) {
        if (source[end] === '{') depth++;
        if (source[end] === '}') depth--;
    }
    assert.equal(depth, 0);
    return source.slice(start, end + 1);
}

function page(path, names) {
    const topics = new Map();
    const requests = [];
    const context = vm.createContext({
        contextPath: '/antmillion',
        console: { log() {}, warn() {}, error() {} },
        currentStockCodes: ['005930'],
        currentHoldingStocks: [{ code: '005930', active: true }, { code: '000660', active: false }],
        subscribedTopics: {}, subscribedStockTopics: {},
        stompConnected: true, isMockMode: false,
        sentimentTimeout: null, hogaTimeout: null,
        pendingSubscription: false,
        document: { querySelector() { return null; } },
        fetch(url) { requests.push(url); throw new Error('Unexpected HTTP request'); },
        stompClient: {
            connected: true,
            subscribe(topic, callback) {
                assert(!topics.has(topic), '한 연결에서 종목·종류별 중복 구독이 없어야 합니다.');
                topics.set(topic, callback);
                return { unsubscribe() { topics.delete(topic); } };
            }
        },
        clearTimeout() {}, setTimeout() { return 1; }
    });
    const source = readFileSync(join(__dirname, '../../src/main/webapp/resources/js/', path), 'utf8');
    names.forEach(name => vm.runInContext(extract(source, name), context));
    return { context, topics, requests };
}

test('메인은 동일 종목 재구독·종료 시 자기 구독만 정리', () => {
    const { context, topics, requests } = page('main/main.js', [
        'subscribeMainStocks', 'subscribeStockToRealtime', 'unsubscribeAllStocks'
    ]);
    context.subscribeMainStocks(['005930', '000660']);
    context.subscribeStockToRealtime('005930');
    assert.equal(topics.size, 2);
    context.unsubscribeAllStocks();
    context.unsubscribeAllStocks();
    assert.equal(topics.size, 0);
    assert.equal(requests.length, 0);
});

test('목록은 화면 종목 변경·빈 목록·중복 종료 시 이전 토픽을 정리', () => {
    const { context, topics, requests } = page('stocklist/stocklist.js', [
        'subscribeCurrentStocks', 'subscribeStockTopic', 'unsubscribeFrontendOnly', 'unsubscribeAllStocks'
    ]);
    context.subscribeCurrentStocks();
    assert(topics.has('/topic/kis-trade/present005930'));
    context.currentStockCodes = ['000660'];
    context.subscribeCurrentStocks();
    assert.deepEqual([...topics.keys()], ['/topic/kis-trade/present000660']);
    context.currentStockCodes = [];
    context.subscribeCurrentStocks();
    context.unsubscribeAllStocks();
    context.unsubscribeAllStocks();
    assert.equal(topics.size, 0);
    assert.equal(requests.length, 0);
});

test('상세는 현재가·호가를 별도 구독하고 중복 요청·종료를 처리', async () => {
    const { context, topics, requests } = page('stock/detail.js', [
        'subscribeCurrentStocks', 'subscribeStockTopic', 'subscribeStockHoga', 'unsubscribeAllStocks'
    ]);
    await context.subscribeCurrentStocks();
    await context.subscribeCurrentStocks();
    assert.deepEqual([...topics.keys()].sort(), [
        '/topic/kis-trade/ask-bid005930', '/topic/kis-trade/present005930'
    ]);
    context.unsubscribeAllStocks();
    context.unsubscribeAllStocks();
    assert.equal(topics.size, 0);
    assert.equal(requests.length, 0);
});

test('보유 화면은 비활성 종목을 제외하고 보유 목록 갱신·종료 시 자기 구독을 정리', () => {
    const { context, topics, requests } = page('mypage/mypage.js', [
        'subscribeHoldingStocks', 'subscribeStockTopicForHolding', 'unsubscribeAllHoldingStocks'
    ]);
    context.subscribeHoldingStocks();
    context.subscribeHoldingStocks();
    assert.deepEqual([...topics.keys()], ['/topic/kis-trade/present005930']);
    context.currentHoldingStocks = [{ code: '000660', active: true }];
    context.subscribeHoldingStocks();
    assert.deepEqual([...topics.keys()], ['/topic/kis-trade/present000660']);
    context.currentHoldingStocks = [];
    context.subscribeHoldingStocks();
    context.unsubscribeAllHoldingStocks();
    context.unsubscribeAllHoldingStocks();
    assert.equal(topics.size, 0);
    assert.equal(requests.length, 0);
});
