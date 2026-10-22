const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { join } = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

async function runSignalScreen(screen, signalColor, fail = false, stockCode = '000001') {
    const detail = screen === 'stock/detail.js';
    const source = readFileSync(join(__dirname, '../../src/main/webapp/resources/js', screen), 'utf8');
    const name = detail ? 'updateDetailTrafficSignal' : 'updateAllTrafficSignals';
    const start = source.indexOf((detail ? 'function ' : 'async function ') + name + '(');
    const end = source.indexOf('\n}', start);
    assert(start >= 0 && end > start, '실제 화면 신호등 함수를 실행합니다.');
    const classes = new Set(['RED', 'signal-red']);
    const requests = [];
    const selectors = [];
    const errors = [];
    const context = vm.createContext({
        contextPath: '/antmillion', URLSearchParams,
        window: { location: { search: stockCode ? '?code=' + stockCode : '' } },
        document: {
            querySelectorAll(selector) {
                selectors.push(selector);
                return [{ getAttribute: attribute => {
                    assert.equal(attribute, screen === 'main/main.js' ? 'data-id' : 'data-code');
                    return stockCode;
                } }];
            },
            getElementById(id) {
                assert.equal(id, 'signal-' + stockCode);
                return { classList: {
                    remove: (...values) => values.forEach(value => classes.delete(value)),
                    add: value => classes.add(value)
                } };
            }
        },
        console: { log: (...args) => { if (detail && fail) errors.push(args); }, error: (...args) => errors.push(args) },
        setTimeout: callback => callback(),
        fetch: url => {
            requests.push(url);
            return fail ? Promise.reject(new Error('synthetic failure')) : Promise.resolve({ json: async () => ({ signalColor }) });
        }
    });
    vm.runInContext(source.slice(start, end + 2) + '\n' + name + '();', context);
    // 상세 함수는 Promise를 반환하지 않으므로 fetch/json/catch 작업의 완료를 기다립니다.
    await new Promise(resolve => setImmediate(resolve));
    if (!detail) assert.deepEqual(selectors, [screen === 'main/main.js' ? '.main-stocklist-item' : '.stocklist-item']);
    return { classes, requests, errors };
}

for (const screen of ['main/main.js', 'stocklist/stocklist.js', 'stock/detail.js']) {
    test(screen + ': 기존 URL·종목코드·세 색상 표시 유지', async () => {
        for (const color of ['GREEN', 'RED', 'YELLOW']) {
            const result = await runSignalScreen(screen, color);
            assert.deepEqual(result.requests, ['/antmillion/api/kis/foreigner-organization/000001']);
            assert(result.classes.has(color));
            for (const other of ['GREEN', 'RED', 'YELLOW'].filter(value => value !== color)) {
                assert(!result.classes.has(other));
            }
            if (screen === 'stock/detail.js') assert(!result.classes.has('signal-red'));
            assert.equal(result.errors.length, 0);
        }
    });

    test(screen + ': null·실패 시 기존 표시 유지와 코드 누락 시 조회 생략', async () => {
        const empty = await runSignalScreen(screen, null);
        const failed = await runSignalScreen(screen, null, true);
        const missing = await runSignalScreen(screen, 'GREEN', false, '');
        assert(empty.classes.has('RED'));
        assert(failed.classes.has('RED'));
        assert.equal(failed.errors.length, 1);
        assert.deepEqual(missing.requests, []);
    });
}
