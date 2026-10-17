const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { join } = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const source = readFileSync(join(__dirname, '../../src/main/webapp/resources/js/mypage/mypage.js'), 'utf8');
const start = source.indexOf('function renderExecutedOrders(filter)');
const end = source.indexOf('function loadAccountInfo()', start);
assert(start >= 0 && end > start);

function render(rows, filter) {
    const tbody = { innerHTML: '' };
    const context = vm.createContext({
        document: { querySelector: () => tbody },
        window: { executedOrderData: rows },
        formatNumber: value => String(value)
    });
    vm.runInContext(source.slice(start, end), context);
    context.renderExecutedOrders(filter);
    return tbody.innerHTML;
}

function order(status, executedQty) {
    return {
        stockName: '보존종목', stockCode: 'TEST01', status, type: 'BUY',
        orderQty: 10, executedQty, unexecutedQty: 10 - executedQty,
        unexecutedAmount: (10 - executedQty) * 1000,
        orderPrice: 1000, orderAmount: 10000, executedPrice: executedQty ? 1000 : null,
        orderTime: '2026-10-16 09:00:00', executedTime: '2026-10-16 10:11:12'
    };
}

test('취소된 부분체결도 체결 탭에 유지하고 원래 체결시각을 표시', () => {
    const html = render([order('CANCEL', 2)], 'executed');
    assert(html.includes('보존종목'));
    assert(html.includes('<td>2</td>'));
    assert(html.includes('2026-10-16 10:11:12'));
});

test('취소 잔량을 대기 중인 수량·금액으로 표시하지 않음', () => {
    const html = render([order('CANCEL', 2)], 'all');
    assert(html.includes('취소'));
    assert(!html.includes('<td>8</td>'));
    assert(!html.includes('<td>8000</td>'));
});

test('체결 탭은 기체결이 없는 대기 주문을 제외하고 완료·부분체결을 포함', () => {
    assert(!render([order('WAIT', 0)], 'executed').includes('보존종목'));
    assert(render([order('COMPLETED', 10)], 'executed').includes('보존종목'));
    assert(render([order('PARTIAL', 2)], 'executed').includes('보존종목'));
});

test('미체결 탭은 취소 주문을 제외하고 정상 대기·부분체결 잔량을 유지', () => {
    assert(!render([order('CANCEL', 2)], 'unexecuted').includes('보존종목'));
    assert(render([order('WAIT', 0)], 'unexecuted').includes('<td>10</td>'));
    assert(render([order('PARTIAL', 2)], 'unexecuted').includes('<td>8</td>'));
});
