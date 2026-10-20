const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { join } = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

function element(value = '', attributes = {}) {
    const listeners = new Map();
    const classes = new Set();
    return {
        value, textContent: '', disabled: false, style: {},
        classList: { add: name => classes.add(name), remove: name => classes.delete(name) },
        getAttribute: name => attributes[name],
        addEventListener(name, callback) {
            if (!listeners.has(name)) listeners.set(name, []);
            listeners.get(name).push(callback);
        },
        fire(name) {
            for (const callback of listeners.get(name) || []) callback.call(this, { target: this });
        }
    };
}

async function orderScreen(initialPrice = 10000) {
    const source = readFileSync(join(__dirname, '../../src/main/webapp/resources/js/stock/detail.js'), 'utf8');
    const marker = source.indexOf("console.log('=== 주문 UI 초기화 시작 ===');");
    const start = source.lastIndexOf('(function()', marker);
    const end = source.indexOf('// ========== 거래 비율 초기 상태로 복원', marker);
    assert(start >= 0 && end > start, 'Run the actual order UI closure');
    const ids = [
        'tab-limit', 'tab-market', 'order-price-input', 'order-quantity-input', 'qty-minus', 'qty-plus',
        'submit-btn', 'order-modal', 'modal-close', 'modal-cancel', 'modal-confirm', 'available-label',
        'total-money', 'detail-current-price-h3', 'modal-title', 'modal-stock-name', 'modal-order-type',
        'modal-price-row', 'modal-price', 'modal-quantity', 'modal-total'
    ];
    const nodes = Object.fromEntries(ids.map(id => [id, element()]));
    nodes['order-quantity-input'].value = '1';
    nodes['detail-current-price-h3'].textContent = initialPrice.toLocaleString() + '원';
    const buy = element('', { 'data-type': 'buy' });
    const sell = element('', { 'data-type': 'sell' });
    const stockName = { textContent: '합성종목' };
    const balance = element();
    const requests = [];
    const alerts = [];
    let onPriceChange;
    const document = {
        readyState: 'complete', activeElement: null,
        getElementById: id => nodes[id] || null,
        querySelector: selector => selector === '.detail-stock-name' ? stockName : balance,
        querySelectorAll: selector => selector === '.detail-tab, .detail-tab-active' ? [buy, sell] : []
    };
    const context = vm.createContext({
        document, contextPath: '/antmillion', URLSearchParams,
        window: { location: { search: '?code=TEST01' } },
        console: { log() {}, error() {} },
        alert: message => alerts.push(message),
        fetch: async (url, options = {}) => {
            requests.push({ url, options });
            assert(['/antmillion/api/account/balance', '/antmillion/api/account/holdings?stockCode=TEST01',
                '/antmillion/api/stock/order'].includes(url), 'Only local API stubs are allowed');
            return { json: async () => ({ success: true, balance: 1000000, quantity: 10 }) };
        },
        setTimeout(callback, delay) { if (delay <= 100) callback(); },
        setInterval() {},
        MutationObserver: class {
            constructor(callback) { onPriceChange = callback; }
            observe(target) { assert.equal(target, nodes['detail-current-price-h3']); }
        }
    });
    vm.runInContext(source.slice(start, end), context);
    await new Promise(resolve => setImmediate(resolve));
    return {
        nodes, buy, sell, document, requests, alerts,
        tick(price) {
            nodes['detail-current-price-h3'].textContent = price.toLocaleString() + '원';
            onPriceChange();
        },
        edit(price) {
            document.activeElement = nodes['order-price-input'];
            nodes['order-price-input'].value = price;
            nodes['order-price-input'].fire('input');
            document.activeElement = nodes['order-quantity-input'];
            nodes['order-price-input'].fire('blur');
        }
    };
}

for (const side of ['buy', 'sell']) {
    test(side + ': 지정가는 시세·포커스 이동·재선택 후에도 유지하고 같은 값으로 전송', async () => {
        const screen = await orderScreen();
        const { nodes } = screen;
        screen[side].fire('click');
        screen.tick(10100);
        assert.equal(nodes['order-price-input'].value, '10,000');
        screen.edit('9000');
        screen.tick(9000);
        screen.tick(9100);
        assert.equal(nodes['order-price-input'].value, '9,000');
        nodes['tab-limit'].fire('click');
        assert.equal(nodes['order-price-input'].value, '9,000');
        assert.equal(nodes['total-money'].textContent, '9,000원');
        nodes['submit-btn'].fire('click');
        assert.equal(nodes['modal-price'].textContent, '9,000원');
        screen.tick(9200);
        nodes['modal-confirm'].fire('click');
        await new Promise(resolve => setImmediate(resolve));
        const order = screen.requests.find(request => request.url.endsWith('/api/stock/order'));
        assert.deepEqual(JSON.parse(order.options.body), {
            stockCode: 'TEST01', transactionType: side.toUpperCase(), orderType: 'LIMIT', quantity: 1, orderPrice: 9000
        });
        assert.deepEqual(screen.alerts, ['주문이 완료되었습니다.']);
    });
}

test('시장가는 시세·총액을 갱신하고 지정가 전환 시점부터 고정', async () => {
    const { nodes, tick } = await orderScreen();
    nodes['tab-market'].fire('click');
    assert.equal(nodes['order-price-input'].disabled, true);
    nodes['order-quantity-input'].value = '2';
    tick(11000);
    assert.equal(nodes['order-price-input'].value, '11,000');
    assert.equal(nodes['total-money'].textContent, '22,000원');
    nodes['tab-limit'].fire('click');
    assert.equal(nodes['order-price-input'].disabled, false);
    tick(12000);
    assert.equal(nodes['order-price-input'].value, '11,000');
    assert.equal(nodes['total-money'].textContent, '22,000원');
});

test('지정가를 비워도 다음 시세가 사용자 편집을 덮어쓰지 않음', async () => {
    const { nodes, edit, tick } = await orderScreen();
    edit('');
    tick(11000);
    assert.equal(nodes['order-price-input'].value, '');
    assert.equal(nodes['total-money'].textContent, '0원');
});

test('초기 시세가 늦게 도착하면 지정가를 한 번만 설정', async () => {
    const { nodes, tick } = await orderScreen(0);
    tick(10000);
    assert.equal(nodes['order-price-input'].value, '10,000');
    assert.equal(nodes['total-money'].textContent, '10,000원');
    tick(11000);
    assert.equal(nodes['order-price-input'].value, '10,000');
});

test('첫 시세보다 먼저 입력한 지정가도 유지', async () => {
    const { nodes, edit, tick } = await orderScreen(0);
    edit('9000');
    tick(10000);
    assert.equal(nodes['order-price-input'].value, '9,000');
    assert.equal(nodes['total-money'].textContent, '9,000원');
});
