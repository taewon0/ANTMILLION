// ===== 전역 변수 (community.js와 공유) =====
// const 대신 var 사용으로 전역 스코프에 등록
if (typeof contextPath === 'undefined') {
    var contextPath = '/antmillion';
    console.log('[contextPath 설정]', contextPath);
}

// 로그인 필요 모달 + 로그인 가드

(function () {
    // 로그인 상태 판별
    window.__isLoggedIn = function () {
        try {
            return (typeof IS_LOGGED_IN !== 'undefined') ? !!IS_LOGGED_IN : false;
        } catch (e) {
            return false;
        }
    };

    // 모달 DOM 생성 (없으면 자동 생성)
    window.ensureLoginRequiredModal = function () {
        if (document.getElementById('loginRequiredModal')) return;

        const modal = document.createElement('div');
        modal.id = 'loginRequiredModal';
        modal.className = 'login-required-modal';
        modal.setAttribute('aria-hidden', 'true');

        modal.innerHTML = `
            <div class="login-required-modal__backdrop" data-close="true"></div>
            <div class="login-required-modal__dialog" role="dialog" aria-modal="true" aria-labelledby="loginRequiredTitle">
                <h4 id="loginRequiredTitle" class="login-required-modal__title">로그인이 필요합니다</h4>
                <button type="button" class="login-required-modal__btn" id="loginRequiredClose">확인</button>
            </div>
        `;

        document.body.appendChild(modal);

        const close = () => {
            modal.classList.remove('active');
            modal.setAttribute('aria-hidden', 'true');
        };

        const open = () => {
            modal.classList.add('active');
            modal.setAttribute('aria-hidden', 'false');
        };

        // 전역으로 노출 (다른 JS에서도 호출 가능)
        if (typeof window.openLoginRequiredModal !== 'function') {
            window.openLoginRequiredModal = open;
        }
        if (typeof window.closeLoginRequiredModal !== 'function') {
            window.closeLoginRequiredModal = close;
        }

        const closeBtn = document.getElementById('loginRequiredClose');
        if (closeBtn) closeBtn.addEventListener('click', close);

        modal.addEventListener('click', (e) => {
            if (e.target && e.target.dataset && e.target.dataset.close === 'true') close();
        });

        document.addEventListener('keydown', (e) => {
            if (e.key === 'Escape') close();
        });
    };

    // 로그인 필요 가드
    window.requireLogin = function (e) {
        if (window.__isLoggedIn && window.__isLoggedIn()) return true;

        if (e) {
            if (typeof e.preventDefault === 'function') e.preventDefault();
            if (typeof e.stopImmediatePropagation === 'function') e.stopImmediatePropagation();
            if (typeof e.stopPropagation === 'function') e.stopPropagation();
        }

        if (typeof window.ensureLoginRequiredModal === 'function') {
            window.ensureLoginRequiredModal();
        }

        if (typeof window.openLoginRequiredModal === 'function') {
            window.openLoginRequiredModal();
        } else {
            alert('로그인이 필요합니다');
        }
        return false;
    };
})();

// ==== 차트 관련 전역 변수 ====
let currentCandleData = null; // 현재 진행 중인 캔들 데이터
let lastCandleUpdateTime = null; // 마지막 캔들 업데이트 시간
let currentChartPeriod = 'minute';

// STOMP 관련 변수
let stompClient = null;
let subscribedTopics = {}; // 구독한 토픽 저장 {stockCode: subscription}
let currentStockCodes = []; // 현재 화면에 표시된 종목 코드들
// 타이머 변수를 전역 스코프에 선언
let sentimentTimeout = null; 
let hogaTimeout = null;

// ======= STOMP 연결 ======
function connectStomp() {
    return new Promise((resolve, reject) => {
        const url = contextPath + '/ws-stomp';
        const socket = new SockJS(url);
        stompClient = Stomp.over(socket);
        stompClient.debug = null;

        stompClient.connect({}, 
            (frame) => {
                console.log('STOMP 연결 성공: ' + frame);
                resolve(frame); 
            }, 
            (error) => {
                // 최초 연결 실패 시
                reject(error);
            }
        );
    });
}

// ======= 재연결 로직 =======
function handleReconnect() {
    console.log('5초 후 재연결을 시도합니다...');
    setTimeout(async () => {
        try {
            await connectStomp();
            console.log('재연결 성공! 현재 종목 구독을 시작합니다.');
            if (currentStockCodes.length > 0) {
                subscribeCurrentStocks();
            }
        } catch (e) {
            console.error('재연결 실패. 다시 시도합니다.');
            handleReconnect(); // 반복 재연결
        }
    }, 5000);
}

// ======= 현재 상세 화면 종목 및 호가 STOMP 구독 =======
async function subscribeCurrentStocks() {
    if (!stompClient || !stompClient.connected || currentStockCodes.length === 0) return;
    if (isMockMode) return enableMockMode();
    currentStockCodes.forEach(stockCode => {
        subscribeStockTopic(stockCode);
        subscribeStockHoga(stockCode);
    });
}

// ========== 개별 종목 STOMP 토픽 구독 ==========
function subscribeStockTopic(stockCode) {
    // 연결 안 됐으면 실행 취소
    if (!stompClient || !stompClient.connected) {
        console.warn('STOMP 연결 전입니다. 구독을 대기합니다.');
        return;
    }
    if (subscribedTopics[stockCode]) {
        console.log('이미 구독 중:', stockCode);
        return;
    }

    const topic = '/topic/kis-trade/present' + stockCode;
    const subscription = stompClient.subscribe(topic, function(message) {
        const tradeData = JSON.parse(message.body);
        console.log('실시간 데이터 수신:', tradeData);

        // 화면 업데이트
        updateStockRealtimePrice(stockCode, tradeData);
        
        // FOMO 실시간 체크
        const changeRate = parseFloat(tradeData.prdyCtrt || 0);
        window.lastFomoChangeRate = changeRate;
        checkFomoInRealtime(changeRate);
    });

    subscribedTopics[stockCode] = subscription;
    console.log('토픽 구독 완료:', topic);
    
    // 구독 시작 후 5초간 데이터가 한 번도 안 들어오면 문구 교체
    sentimentTimeout = setTimeout(() => {
        const sentimentText = document.querySelector('.detail-sentiment-text');
        // 수신된 데이터가 없을 때만 문구 변경
        if (sentimentText && sentimentText.innerText.includes('로딩중...')) {
            sentimentText.innerHTML = `<span>장 시간에 확인할 수 있어요</span>`;
            console.log(`[${stockCode}] 데이터 수신 없음 - 상태 전환`);
        }
    }, 8000);
}

let isMarketOrder = false; 
let lastRealtimePrice = "";

// ========== 현재가 업데이트 함수 ==========
function updateStockRealtimePrice(stockCode, tradeData) {
    // 1. 데이터 수신 시 타이머 즉시 취소
    if (sentimentTimeout) {
        clearTimeout(sentimentTimeout);
        sentimentTimeout = null;
        console.log(`[${stockCode}] 타이머 취소 - 데이터 수신 확인`);
    }

    const rawPrice = tradeData.stckPrpr;
    if (!rawPrice) return;

    // 현재가 업데이트
    lastRealtimePrice = rawPrice;
    const currentPrice = Number(rawPrice);
    const formattedPrice = currentPrice.toLocaleString('ko-KR') + '원';

    const currentPriceH3 = document.getElementById('detail-current-price-h3');
    if (currentPriceH3) {
        currentPriceH3.textContent = formattedPrice;
        console.log("현재가 업데이트 성공:", formattedPrice);
    }

    if (isMarketOrder) {
        const orderPriceElem = document.getElementById('order-display-price');
        if (orderPriceElem) orderPriceElem.textContent = formattedPrice;
    }

    // 차트에 실시간 현재가 반영
    updateChartRealtime(currentPrice);

    // . 매수/매도 비율 및 텍스트 업데이트
    const rawBuyRate = tradeData.shnuRate;
    if (rawBuyRate !== undefined && rawBuyRate !== null) {
        const sentimentText = document.querySelector('.detail-sentiment-text');

        // 비율 계산
        let buyRate = Math.round(rawBuyRate < 1 ? (rawBuyRate * 100) : rawBuyRate);
        const sellRate = 100 - buyRate;

        if (sentimentText) {
            if (buyRate >= 50) {
                sentimentText.innerHTML = `🔥 현재 투자자 <span id="sentiment-percent">${buyRate}</span>%가 <span class="detail-red-text" id="sentiment-direction">매수</span>쪽으로 몰려요!`;
            } else {
                sentimentText.innerHTML = `🔥 현재 투자자 <span id="sentiment-percent">${sellRate}</span>%가 <span class="detail-blue-text" id="sentiment-direction">매도</span>쪽으로 몰려요!`;
            }
            console.log(`[${stockCode}] 거래비율 업데이트: 매수 ${buyRate}% / 매도 ${sellRate}%`);
        }

        // 바(Bar) 활성화
        const buyBar = document.getElementById('buy-bar');
        const sellBar = document.getElementById('sell-bar');
        const buyText = document.getElementById('buy-percent');
        const sellText = document.getElementById('sell-percent');
        if (buyBar && sellBar) {
            buyBar.classList.remove('detail-sentiment-inactive');
            sellBar.classList.remove('detail-sentiment-inactive');
            buyBar.style.width = buyRate + '%';
            sellBar.style.width = sellRate + '%';
        }
        if (buyText) buyText.textContent = buyRate + '%';
        if (sellText) sellText.textContent = sellRate + '%';
    }
}

// ======= 시장가/지정가 선택 로직 =======
function initPriceTypeEvents() {
    const tabLimit = document.querySelector('.detail-card-label span:first-child'); // 지정가
    const tabMarket = document.querySelector('.detail-card-label span:last-child');  // 시장가
    const bigPriceElement = document.querySelector('.detail-big-price');

    if (!tabLimit || !tabMarket || !bigPriceElement) return;

    // 시장가 클릭
    tabMarket.addEventListener('click', function() {
        isMarketOrder = true;
        tabMarket.style.color = '#1F2937';
        tabMarket.classList.add('detail-active-type');
        tabLimit.style.color = '#6B7280';
        tabLimit.classList.remove('detail-active-type');

        // 즉시 현재가로 변경
        if (lastRealtimePrice) {
            bigPriceElement.textContent = Number(lastRealtimePrice).toLocaleString() + '원';
        }
    });

    // 지정가 클릭
    tabLimit.addEventListener('click', function() {
        isMarketOrder = false;

        tabLimit.style.color = '#1F2937';
        tabLimit.classList.add('detail-active-type');
        tabMarket.style.color = '#6B7280';
        tabMarket.classList.remove('detail-active-type');
    });
}
    
// ========== 모든 구독 해제 ==========
function unsubscribeAllStocks() {
    // 타이머가 존재할 때만 중단시키고 null로 초기화
    if (typeof sentimentTimeout !== 'undefined' && sentimentTimeout) {
        clearTimeout(sentimentTimeout);
        sentimentTimeout = null;
    }
    if (typeof hogaTimeout !== 'undefined' && hogaTimeout) {
        clearTimeout(hogaTimeout);
        hogaTimeout = null;
    }
    sentimentTimeout = null;
    hogaTimeout = null;
    console.log('구독 해제 시작...');

    // 프론트엔드 STOMP 구독 해제
    for (let stockCode in subscribedTopics) {
        if (subscribedTopics[stockCode]) {
            subscribedTopics[stockCode].unsubscribe();
            console.log('토픽 구독 해제:', stockCode);
        }
    }
    subscribedTopics = {};
}

// ========== 호가 데이터 수신 및 UI 업데이트 ==========
function updateHogaUI(hogaData) {
    // 1. 데이터 수신 시 타이머 즉시 취소
    if (hogaTimeout) {
        clearTimeout(hogaTimeout);
        hogaTimeout = null;
        console.log('호가 타이머 취소 - 데이터 수신 확인');
    }

    let hogaBox = document.querySelector('.detail-hoga-box');
    if (!hogaBox) return;

    // 2. placeholder가 있으면 무조건 비우기
    if (hogaBox.querySelector('.detail-hoga-placeholder')) {
        hogaBox.innerHTML = '';
        console.log('호가 placeholder 제거 - 실제 데이터 표시');
    }

    let hogaList = hogaBox.querySelector('.hoga-container');
    if (!hogaList) {
        hogaBox.innerHTML = '';
        hogaList = document.createElement('div');
        hogaList.className = 'hoga-container';
        hogaList.style.height = '100%';
        hogaBox.appendChild(hogaList);
    }

    hogaList.innerHTML = '';

    // 매도/매수 행 생성
    for (let i = 5; i >= 1; i--) {
        hogaList.appendChild(createHogaRow(hogaData[`askP${i}`], hogaData[`askPrsqn${i}`], 'ask'));
    }
    for (let i = 1; i <= 5; i++) {
        hogaList.appendChild(createHogaRow(hogaData[`bidP${i}`], hogaData[`bidPrsqn${i}`], 'bid'));
    }
}

// ========== 호가 행(Row) 생성 함수 (3분할 레이아웃) ==========
function createHogaRow(price, qty, type) {
    const row = document.createElement('div');
    row.className = `hoga-row ${type}-row`;
    
    // 클릭 시 가격 입력창 업데이트
    row.onclick = function() {
        if (!isMarketOrder) {
            const displayPrice = document.getElementById('order-display-price');
            if (displayPrice) displayPrice.textContent = Number(price).toLocaleString() + '원';
        }
    };

    // 3분할 구조: [왼쪽 칸] [가운데 칸] [오른쪽 칸]
    if (type === 'ask') {
        // 매도: [잔량] [가격] [공백]
        row.innerHTML = `
            <span class="hoga-col qty-col">${Number(qty).toLocaleString()}</span>
            <span class="hoga-col price-col">${Number(price).toLocaleString()}</span>
            <span class="hoga-col empty-col"></span>
        `;
    } else {
        // 매수: [공백] [가격] [잔량]
        row.innerHTML = `
            <span class="hoga-col empty-col"></span>
            <span class="hoga-col price-col">${Number(price).toLocaleString()}</span>
            <span class="hoga-col qty-col">${Number(qty).toLocaleString()}</span>
        `;
    }
    return row;
}

// 호가
function subscribeStockHoga(stockCode) {
    // 호가용 키 생성 (현재가와 구분하기 위해)
    const hogaKey = stockCode + '_hoga';

    // 이미 구독 중이면 건너뛰기
    if (subscribedTopics[hogaKey]) {
        console.log('이미 호가 구독 중:', stockCode);
        return;
    }
    // 백엔드와 일치하는 경로: /topic/kis-trade/ask-bid005930 형태
    const topic = '/topic/kis-trade/ask-bid' + stockCode;

    const subscription = stompClient.subscribe(topic, function(message) {
        const askBidData = JSON.parse(message.body);
        
        // 데이터 수신 성공
        if (hogaTimeout) {
            clearTimeout(hogaTimeout);
            hogaTimeout = null;
        }
        
        updateHogaUI(askBidData);
    });

    // subscribedTopics에 저장
    subscribedTopics[hogaKey] = subscription;
    
    // 구독 시작 후 5초간 데이터가 안 들어오면 문구 교체
    hogaTimeout = setTimeout(() => {
        const hogaBox = document.querySelector('.detail-hoga-box');
        // 여전히 placeholder가 '로딩중...' 상태라면 문구 교체
        if (hogaBox && hogaBox.querySelector('.detail-hoga-placeholder') && 
            hogaBox.innerText.includes('로딩중...')) {
            
            hogaBox.innerHTML = `
                <div class="detail-hoga-placeholder">
                    <span>호가는 장 시간에 볼 수 있어요</span>
                </div>
            `;
            console.log(`[${stockCode}] 호가 데이터 수신 없음 - 상태 전환`);
        }
    }, 8000);
}

// ===== API 호출 함수 =====
async function checkBiasAlert(stockCode) {
    try {
        const url = contextPath + '/api/bias-alert/check?stockCode=' + stockCode;
        console.log('[API URL]', url);
        
        const response = await fetch(url);
        console.log('[API 응답 상태]', response.status);
        
        if (response.status === 204) {
            console.log('[결과] 보유하지 않은 종목');
            return null;
        }
        
        if (!response.ok) {
            throw new Error('API 호출 실패: ' + response.status);
        }
        
        const data = await response.json();
        console.log('[API 결과]', data);
        return data;
        
    } catch (error) {
        console.error('[API 에러]', error);
        return null;
    }
}

// 손실회피 체크 함수
async function checkLossAversionAlert(stockCode) {
    console.log('[손실회피 API 호출] stockCode:', stockCode);
    
    try {
        const url = contextPath + '/api/bias-alert/check-loss-aversion?stockCode=' + stockCode;
        console.log('[손실회피 API URL]', url);
        
        const response = await fetch(url);
        console.log('[손실회피 API 응답 상태]', response.status);
        
        if (response.status === 204) {
            console.log('[결과] 보유하지 않은 종목');
            return null;
        }
        
        if (!response.ok) {
            throw new Error('API 호출 실패: ' + response.status);
        }
        
        const data = await response.json();
        console.log('[손실회피 API 결과]', data);
        return data;
        
    } catch (error) {
        console.error('[손실회피 API 에러]', error);
        return null;
    }
}

// 매몰비용오류 체크 함수
async function checkSunkCostAlert(stockCode) {
    console.log('[매몰비용오류 API 호출] stockCode:', stockCode);
    
    try {
        const url = contextPath + '/api/bias-alert/check-sunk-cost?stockCode=' + stockCode;
        console.log('[매몰비용오류 API URL]', url);
        
        const response = await fetch(url);
        console.log('[매몰비용오류 API 응답 상태]', response.status);
        
        if (response.status === 204) {
            console.log('[결과] 보유하지 않은 종목');
            return null;
        }
        
        if (!response.ok) {
            throw new Error('매몰비용오류 API 호출 실패: ' + response.status);
        }
        
        const data = await response.json();
        console.log('[매몰비용오류 API 결과]', data);
        return data;
        
    } catch (error) {
        console.error('[매몰비용오류 API 에러]', error);
        return null;
    }
}

// FOMO 체크 함수
async function checkFomoAlert(stockCode, changeRate) {
    console.log('[FOMO API 호출] stockCode:', stockCode, 'changeRate:', changeRate);
    
    try {
        const url = contextPath + '/api/bias-alert/check-fomo?stockCode=' + stockCode + '&changeRate=' + changeRate;
        console.log('[FOMO API URL]', url);
        
        const response = await fetch(url);
        console.log('[FOMO API 응답 상태]', response.status);
        
        if (response.status === 204) {
            console.log('[결과] 조회 실패');
            return null;
        }
        
        if (!response.ok) {
            throw new Error('FOMO API 호출 실패: ' + response.status);
        }
        
        const data = await response.json();
        console.log('[FOMO API 결과]', data);
        return data;
        
    } catch (error) {
        console.error('[FOMO API 에러]', error);
        return null;
    }
}

// 일별 분봉 조회 - 차트
let stockChart = null;
let candleSeries = null;

// 시간 포맷 변환 함수
function formatToTimestamp(dateStr, timeStr) {
    const year = parseInt(dateStr.substring(0, 4));
    const month = parseInt(dateStr.substring(4, 6)) - 1;
    const day = parseInt(dateStr.substring(6, 8));
    const hour = parseInt(timeStr.substring(0, 2));
    const minute = parseInt(timeStr.substring(2, 4));
    const second = parseInt(timeStr.substring(4, 6)) || 0;

    const date = new Date(year, month, day, hour, minute, second); // KST 시간대 기준
    const offsetInSeconds = date.getTimezoneOffset() * 60;
    return Math.floor(date.getTime() / 1000) - offsetInSeconds;
}

// 차트 그리기 함수
function drawDetailChart(stockCode) {
    const chartContainer = document.getElementById('detail-stockChart');
    if (!chartContainer) {
        console.error(".detail-chart-area 요소를 찾을 수 없음");
        return;
    }
    
    chartContainer.innerHTML = ''; // 기존 차트가 있다면 삭제
    
    const width = chartContainer.clientWidth;
    const height = chartContainer.clientHeight;

    stockChart = LightweightCharts.createChart(chartContainer, {
        width: width,
        height: height,
        localization: {
            locale: 'ko-KR',
            timeFormatter: (time) => {
                const date = new Date((time - 9 * 60 * 60) * 1000);
                const y = date.getFullYear();
                const m = date.getMonth() + 1;
                const d = date.getDate();
                const h = String(date.getHours()).padStart(2, '0');
                const min = String(date.getMinutes()).padStart(2, '0');
                return `${y}년 ${m}월 ${d}일 ${h}:${min}`;
            },
        },
        timeScale: {
            timeVisible: true, // 시간 표시
            secondsVisible: false,
            barSpacing: 10,
        },
        layout: {
            background: {type: 'solid', color: 'white'},
            textColor: 'black'
        },

    });

    candleSeries = stockChart.addSeries(LightweightCharts.CandlestickSeries, {
        upColor: '#e74c3c',
        downColor: '#3498db',
        borderUpColor: '#e74c3c',
        borderDownColor: '#3498db',
        wickUpColor: '#e74c3c',
        wickDownColor: '#3498db'
    });

    currentChartPeriod = 'minute';

    // API 호출
    fetch(`/antmillion/api/kis/stream/${stockCode}`)
    .then(res => res.json())
    .then(data => {
        if (!data || data.length === 0) {
            console.error("데이터가 비어있음");
            return;
        }

        const currentPrice = document.getElementById("detail-current-price-h3");
        currentPrice.innerText = Number(data[data.length-1].stck_prpr).toLocaleString('ko-KR') + '원';

        // 데이터 정렬
        data.sort((a, b) => (a.stck_bsop_date + a.stck_cntg_hour).localeCompare(b.stck_bsop_date + b.stck_cntg_hour));

        const chartData = [];
        const seenTimes = new Set();

        data.forEach(row => {
            const timestamp = formatToTimestamp(row.stck_bsop_date, row.stck_cntg_hour);
            
            // 중복 시간 데이터 제거
            if (!seenTimes.has(timestamp)) {
                chartData.push({
                    time: timestamp,
                    open: Number(row.stck_oprc),
                    high: Number(row.stck_hgpr),
                    low: Number(row.stck_lwpr),
                    close: Number(row.stck_prpr)
                });
                seenTimes.add(timestamp);
            }
        });

        console.log("변환된 차트 데이터:", chartData);
        
        if (chartData.length > 0) {
            candleSeries.setData(chartData);

            // 마지막 캔들을 현재 진행 중인 캔들로 설정
            currentCandleData = chartData[chartData.length - 1];
            lastCandleUpdateTime = currentCandleData.time;

            stockChart.timeScale().fitContent();
        }
    })
    .catch(err => console.error("API 호출 에러:", err));
}

// ========== 실시간 현재가로 차트 캔들 업데이트 ==========
function updateChartRealtime(currentPrice) {
    if (!candleSeries || !currentCandleData) return;

    let currentTime;

    // 기간별로 현재 시간 계산
    if (currentChartPeriod === 'minute') {
        currentTime = getCurrentMinuteTimestamp();
    } else {
        currentTime = getCurrentPeriodTime(currentChartPeriod);
    }

    // 새로운 기간이 시작되면 새 캔들 생성
    const lastTime = getCanonicalTime(lastCandleUpdateTime, currentChartPeriod);
    const nowTime = getCanonicalTime(currentTime, currentChartPeriod);

    // 새로운 분이 시작되면 새 캔들 생성
    if (nowTime !== lastTime) {
        console.log('새로운 분 시작 - 새 캔들 생성');

        currentCandleData = {
            time: currentTime,
            open: currentPrice,
            high: currentPrice,
            low: currentPrice,
            close: currentPrice
        };
        lastCandleUpdateTime = currentTime;

        // 새 캔들 추가
        candleSeries.update(currentCandleData);
    }
    // 같은 분 내에서는 기존 캔들 업데이트
    else {
        currentCandleData.close = currentPrice;
        currentCandleData.high = Math.max(currentCandleData.high, currentPrice);
        currentCandleData.low = Math.min(currentCandleData.low, currentPrice);

        // 기존 캔들 업데이트
        candleSeries.update(currentCandleData);
        console.log('캔들 업데이트:', currentCandleData);
    }
}

// ========== 현재 분의 timestamp 계산 (초는 0으로) ==========
function getCurrentMinuteTimestamp() {
    const now = new Date();
    now.setSeconds(0);
    now.setMilliseconds(0);

    const offsetInSeconds = now.getTimezoneOffset() * 60;
    return Math.floor(now.getTime() / 1000) - offsetInSeconds;
}

// ========== 일/주/월/년봉용: 현재 기간의 시간 계산 ==========
function getCurrentPeriodTime(period) {
    const now = new Date();

    if (period === 'D') {
        // 일봉: 오늘 날짜
        return {
            year: now.getFullYear(),
            month: now.getMonth() + 1,
            day: now.getDate()
        };
    } else if (period === 'W') {
        // 주봉: 이번 주 월요일 날짜
        const monday = new Date(now);
        const day = now.getDay();
        const diff = day === 0 ? -6 : 1 - day; // 일요일이면 -6, 아니면 월요일까지 차이
        monday.setDate(now.getDate() + diff);

        return {
            year: monday.getFullYear(),
            month: monday.getMonth() + 1,
            day: monday.getDate()
        };
    } else if (period === 'M') {
        // 월봉: 이번 달 1일
        return {
            year: now.getFullYear(),
            month: now.getMonth() + 1,
            day: 1
        };
    } else if (period === 'Y') {
        // 연봉: 올해 1월 1일
        return {
            year: now.getFullYear(),
            month: 1,
            day: 1
        };
    }

    return null;
}

// ========== 기간별 정규화된 시간 반환 (비교용) ==========
function getCanonicalTime(time, period) {
    if (period === 'minute') {
        // 분봉은 timestamp를 그대로 사용
        return time;
    } else {
        // 일/주/월/년봉은 {year, month, day} 객체를 문자열로 변환
        if (typeof time === 'object') {
            return `${time.year}-${String(time.month).padStart(2, '0')}-${String(time.day).padStart(2, '0')}`;
        }
        return time;
    }
}

// 날짜 포맷 변경 (main.js의 formatDate 함수와 동일)
function formatDate(yyyymmdd) {
    return {
        year: Number(yyyymmdd.substring(0, 4)),
        month: Number(yyyymmdd.substring(4, 6)),
        day: Number(yyyymmdd.substring(6, 8)),
    };
}

// 일/주/월/년봉 차트 그리기 함수
function drawPeriodChart(stockCode, period) {
    const chartContainer = document.getElementById('detail-stockChart');
    if (!chartContainer) {
        console.error("#detail-stockChart 요소를 찾을 수 없음");
        return;
    }

    chartContainer.innerHTML = ''; // 기존 차트 삭제

    const width = chartContainer.clientWidth;
    const height = chartContainer.clientHeight;

    stockChart = LightweightCharts.createChart(chartContainer, {
        width: width,
        height: height,
        layout: {
            background: {type: 'solid', color: 'white'},
            textColor: 'black'
        },
        grid: {
            vertLines: { color: '#eee' },
            horzLines: { color: '#eee' }
        },
        timeScale: {
            borderColor: '#cccccc',
            timeVisible: false,
            secondsVisible: false
        }
    });

    candleSeries = stockChart.addSeries(LightweightCharts.CandlestickSeries, {
        upColor: '#e74c3c',
        downColor: '#3498db',
        borderUpColor: '#e74c3c',
        borderDownColor: '#3498db',
        wickUpColor: '#e74c3c',
        wickDownColor: '#3498db'
    });

    currentChartPeriod = period;

    // API 호출
    fetch(`/antmillion/api/kis/periodChart/${stockCode}?period=${period}`)
        .then(res => res.json())
        .then(data => {
            if (!data || data.length === 0) {
                console.error("데이터가 비어있음");
                return;
            }

            // 데이터 정렬
            data.sort((a, b) => a.stck_bsop_date.localeCompare(b.stck_bsop_date));

            // 차트 데이터 변환
            const chartData = data.map(row => ({
                time: formatDate(row.stck_bsop_date),
                open: Number(row.stck_oprc),
                high: Number(row.stck_hgpr),
                low: Number(row.stck_lwpr),
                close: Number(row.stck_clpr)
            }));

            if (chartData.length > 0) {
                candleSeries.setData(chartData);

                // 마지막 캔들 저장
                currentCandleData = chartData[chartData.length - 1];
                lastCandleUpdateTime = getCanonicalTime(currentCandleData.time, period);

                stockChart.timeScale().fitContent();
            }
        })
        .catch(err => console.error("API 호출 에러:", err));
}

// ===== DOM 로드 후 실행 =====
document.addEventListener('DOMContentLoaded', async () => {
    const urlParams = new URLSearchParams(window.location.search);
    const stockCode = urlParams.get('code');

    if (!stockCode) {
        console.error("URL에 종목 코드가 없습니다.");
        return;
    }

    // --- 초기화 ---
    console.log('[페이지 초기화] 시작...');

    currentStockCodes = [stockCode];

    // UI 초기화
    resetHogaToDefault();
    resetSentimentToDefault();
    initPriceTypeEvents();

    // 로고 이미지 설정
    const imgUrl = contextPath + '/resources/images/stock/' + stockCode + '.png';
    const logoImg = document.getElementById('detail-stock-image');
    if (logoImg) {
        logoImg.src = imgUrl;
        logoImg.onerror = function () {
            this.src = contextPath + '/resources/images/antmillion-logo.png';
        }
    }

    // 차트 그리기
    drawDetailChart(stockCode);

    // 차트 기간 버튼 이벤트
    const periodButtons = document.querySelectorAll('.detail-period-btn');
    periodButtons.forEach(btn => {
        btn.addEventListener('click', function() {
            periodButtons.forEach(b => b.classList.remove('detail-period-active'));
            this.classList.add('detail-period-active');

            const period = this.getAttribute('data-period');
            currentCandleData = null;
            lastCandleUpdateTime = null;

            if (period === 'minute') {
                drawDetailChart(stockCode);
            } else {
                drawPeriodChart(stockCode, period);
            }
        });
    });

    // 반응형 대응
    window.addEventListener('resize', () => {
        if (stockChart) {
            const container = document.querySelector('#detail-stockChart');
            if (container) {
                stockChart.resize(container.clientWidth, container.clientHeight);
            }
        }
    });

    // 신호등 초기 업데이트
    scheduleMarketClose();
    updateDetailTrafficSignal();
    setInterval(() => {
        console.log('[Auto Update] 상세페이지 신호등 상태 갱신');
        updateDetailTrafficSignal();
    }, 600000);

    // 탭 전환 리스너 등록
    setupTabListeners();

    // --- STOMP 실행 (비동기 흐름 제어) ---
    try {
        console.log('STOMP 연결 시도...');
        await connectStomp();

        console.log('STOMP 연결 성공. 구독 시작.');
        await subscribeCurrentStocks();

    } catch (error) {
        console.error('STOMP 연결 실패:', error);
        handleReconnect();
    }

    // 관심종목 상태 확인
    checkFavoriteStatus();

    console.log('=== 매매 편향 체크 시스템 로드 완료 (API 연동) ===');
    const __loggedIn =
        (typeof window.__isLoggedIn === 'function') ? window.__isLoggedIn()
            : (typeof IS_LOGGED_IN !== 'undefined' ? !!IS_LOGGED_IN : false);

    // ===== 페이지 진입 시 편향 체크 =====
    if (__loggedIn) {
        try {
            console.log('[페이지 로드] 편향 체크 시작 - stockCode:', stockCode);

            const sunkCostData = await checkSunkCostAlert(stockCode);
            const lossData = await checkLossAversionAlert(stockCode);

            await new Promise(resolve => setTimeout(resolve, 1000));
            const fomoData = await checkFomoAlert(stockCode, window.lastFomoChangeRate || 0);

            if (sunkCostData && sunkCostData.hasAlert) {
                console.log('[우선순위 1] 매몰비용오류 경고');
                if (typeof showBiasAlert === 'function') {
                    showBiasAlert('SUNK_COST');
                    if (typeof checkUnreadAlerts === 'function') {
                        setTimeout(() => { checkUnreadAlerts(); }, 0);
                    }
                }
            } else if (lossData && lossData.hasAlert) {
                console.log('[우선순위 2] 손실회피 경고');
                if (typeof showBiasAlert === 'function') {
                    showBiasAlert('LOSS_AVERSION');
                    if (typeof checkUnreadAlerts === 'function') {
                        setTimeout(() => { checkUnreadAlerts(); }, 0);
                    }
                }
            } else if (fomoData && fomoData.hasAlert) {
                console.log('[우선순위 4] FOMO 경고');
                if (typeof showBiasAlert === 'function') {
                    showBiasAlert('FOMO');
                    if (typeof checkUnreadAlerts === 'function') {
                        setTimeout(() => { checkUnreadAlerts(); }, 0);
                    }
                }
            } else {
                console.log('[페이지 로드] 편향 조건 미달');
            }
        } catch (error) {
            console.error('[페이지 로드] 편향 체크 에러:', error);
        }
    } else {
        console.log('[비로그인] 편향 체크 스킵');
    }
});

// ===== 탭 전환 함수 (별도 함수로 분리) =====
function setupTabListeners() {
    const tabs = document.querySelectorAll('.detail-tab, .detail-tab-active');

    tabs.forEach(function(tab) {
        tab.addEventListener('click', async function(e) {
            if (typeof window.requireLogin === 'function' && !window.requireLogin(e)) return;
            const type = this.dataset.type;
            console.log('[탭 클릭]', type);

            // 모든 탭에서 active 클래스 제거
            document.querySelectorAll('.detail-tab, .detail-tab-active').forEach(function(t) {
                t.classList.remove('detail-tab-active');
                t.classList.add('detail-tab');
            });

            // 현재 클릭된 탭에 active 클래스 추가
            this.classList.remove('detail-tab');
            this.classList.add('detail-tab-active');

            const btn = document.getElementById('submit-btn');
            const inputCard = document.querySelector('.detail-input-card');
            const resultArea = document.querySelector('.detail-order-result');
            const sentimentSection = document.querySelector('.detail-sentiment-section');
            const pendingArea = document.getElementById('pending-list-area');

            const totalMoney = document.getElementById('total-money');
            const availableLabel = document.getElementById('available-label');
            const sentimentDir = document.getElementById('sentiment-direction');
            const sentimentPercent = document.getElementById('sentiment-percent');
            const buyPercent = document.getElementById('buy-percent');
            const sellPercent = document.getElementById('sell-percent');
            const buyBar = document.getElementById('buy-bar');
            const sellBar = document.getElementById('sell-bar');

            // 1. 대기(Pending) 탭 처리
            if (type === 'pending') {
                console.log('[UI 변경] 대기 목록 모드');
                // 매수/매도 관련 UI 숨기기
                if(inputCard) inputCard.style.display = 'none';
                if(resultArea) resultArea.style.display = 'none';
                if(sentimentSection) sentimentSection.style.display = 'none';
                if(btn) btn.style.display = 'none';

                // 대기 리스트 영역 보이기
                if(pendingArea) {
                    pendingArea.style.display = 'flex';
                }

                // AJAX 데이터 로드 함수 호출
                loadPendingOrders();
                return; // 대기 탭일 경우 아래 매수/매도 로직 실행 방지
            }

            // 2. 매수/매도 탭 공통 처리 (대기 영역 숨기기)
            if(pendingArea) pendingArea.style.display = 'none';
            if(inputCard) inputCard.style.display = 'flex';
            if(resultArea) resultArea.style.display = 'flex';
            if(sentimentSection) sentimentSection.style.display = 'block';
            if(btn) btn.style.display = 'block';

            // 3. 매수(Buy) 모드 로직
            if (type === 'buy') {
                console.log('[UI 변경] 매수 모드');
                btn.textContent = '매수';
                btn.style.background = '#e22939';
                totalMoney.style.color = '#e22939';
                availableLabel.textContent = '구매 가능 금액';
                sentimentDir.textContent = '매수';
                sentimentDir.className = 'detail-red-text';

                // 매수 탭 클릭 시 편향 체크 (우선순위: 매몰비용 > 손실회피 > FOMO)
                const urlParams = new URLSearchParams(window.location.search);
                const stockCode = urlParams.get('code');
                console.log('[종목 코드]', stockCode);

                if (stockCode) {
                    try {
                        // 우선순위 1: 매몰비용
                        const sunkCostData = await checkSunkCostAlert(stockCode);

                        // 우선순위 2: 손실회피
                        const lossData = await checkLossAversionAlert(stockCode);

                        // 우선순위 4: FOMO
                        // FOMO는 WebSocket 데이터 수신 대기 (0.5초)
                        await new Promise(resolve => setTimeout(resolve, 500));
                        const fomoData = await checkFomoAlert(stockCode, window.lastFomoChangeRate || 0);

                        if (sunkCostData && sunkCostData.hasAlert) {
                            console.log('[매수 탭] 매몰비용 경고: ' + sunkCostData.stockName + ' ' + sunkCostData.profitRate + '% 손실, ' + sunkCostData.holdingDays + '일 보유');

                            if (typeof showBiasAlert === 'function') {
                                showBiasAlert('SUNK_COST');

                                if (typeof checkUnreadAlerts === 'function') {
                                    setTimeout(() => { checkUnreadAlerts(); }, 0);
                                }
                            }
                        } else if (lossData && lossData.hasAlert) {
                            console.log('[매수 탭] 손실회피 경고: ' + lossData.stockName + ' ' + lossData.profitRate + '% 손실 중');

                            if (typeof showBiasAlert === 'function') {
                                showBiasAlert('LOSS_AVERSION');

                                if (typeof checkUnreadAlerts === 'function') {
                                    setTimeout(() => { checkUnreadAlerts(); }, 0);
                                }
                            }
                        } else if (fomoData && fomoData.hasAlert) {
                            console.log('[매수 탭] FOMO 경고: ' + fomoData.stockName + ' +' + fomoData.profitRate + '% 급등');

                            if (typeof showBiasAlert === 'function') {
                                showBiasAlert('FOMO');

                                if (typeof checkUnreadAlerts === 'function') {
                                    setTimeout(() => { checkUnreadAlerts(); }, 0);
                                }
                            }
                        } else {
                            console.log('[매수 탭] 편향 조건 미달');
                        }

                        // 실시간 체크도 활성화 (이미 20% 이상이면 표시)
                        if (window.lastFomoChangeRate >= 20 && !fomoAlertShown) {
                            console.log('[매수 탭] 실시간 등락률 20% 이상 - 경고 표시');
                            checkFomoInRealtime(window.lastFomoChangeRate);
                        }
                    } catch (error) {
                        console.error('[매수 탭] 편향 체크 에러:', error);
                    }
                } else {
                    console.warn('URL에 종목 코드(code) 없음');
                }

            } else if (type === 'sell') {
                console.log('[UI 변경] 매도 모드');
                btn.textContent = '매도';
                btn.style.background = '#2271e9';
                totalMoney.style.color = '#2271e9';
                availableLabel.textContent = '판매 가능 수량';
                sentimentDir.textContent = '매도';
                sentimentDir.className = 'detail-blue-text';

                // 매도 탭 클릭 시 위험회피 체크
                const urlParams2 = new URLSearchParams(window.location.search);
                const stockCode2 = urlParams2.get('code');
                console.log('[종목 코드]', stockCode2);

                if (stockCode2) {
                    try {
                        const riskData = await checkBiasAlert(stockCode2);

                        if (riskData && riskData.hasAlert) {
                            console.log('위험회피 경고: ' + riskData.stockName + ' +' + riskData.profitRate + '% 수익 중 (' + riskData.holdingDays + '일 보유)');

                            if (typeof showBiasAlert === 'function') {
                                showBiasAlert('RISK_AVERSION');

                                if (typeof checkUnreadAlerts === 'function') {
                                    setTimeout(() => { checkUnreadAlerts(); }, 0);
                                }
                            }
                        } else {
                            // 위험회피 조건 미달
                            if (riskData) {
                                console.log('위험회피 조건 미달 - 수익률: ' + riskData.profitRate + '%, 보유일수: ' + riskData.holdingDays + '일');
                            }
                            // 손실회피 경고는 유지하므로 hideBiasAlert() 호출 안 함
                            console.log('[경고] 기존 경고 유지 (손실회피 경고가 있을 수 있음)');
                        }
                    } catch (error) {
                        console.error('[매도 탭] 위험회피 체크 에러:', error);
                    }
                } else {
                    console.warn('URL에 종목 코드(code) 없음');
                }
            }
        });
    });
}

// 대기 목록 로드 함수
function loadPendingOrders() {
    const tbody = $('#pending-tbody');
    $.ajax({
        url: contextPath + '/api/stock/pending-list',
        type: 'GET',
        success: function(data) {
            tbody.empty();
            if (!data || data.length === 0) {
                tbody.append('<tr class="p-empty-row"><td colspan="5" class="p-empty-msg">미체결 내역이 없습니다.</td></tr>');
                return;
            }
            data.forEach(order => {
                const typeClass = order.transactionType === 'BUY' ? 'buy' : 'sell';
                
                let timeStr = "";
                if (Array.isArray(order.createdAt)) {
                    timeStr = order.createdAt[3].toString().padStart(2, '0') + ":" + 
                              order.createdAt[4].toString().padStart(2, '0');
                } else {
                    const d = new Date(order.createdAt);
                    timeStr = isNaN(d.getTime()) ? "--:--" : 
                              d.getHours().toString().padStart(2, '0') + ":" + 
                              d.getMinutes().toString().padStart(2, '0');
                }
                
                let row = `<tr>
                    <td class="p-time">${timeStr}</td>
                    <td>
                        <span class="p-stock-name">${order.stockName}</span>
                        <span class="p-type ${typeClass}">${order.transactionType === 'BUY' ? '매수' : '매도'}</span>
                    </td>
                    <td>
                        <div class="p-price">${order.orderPrice.toLocaleString()}원</div>
                        <div class="p-qty">${order.quantity}주</div>
                    </td>
                    <td class="p-unexecuted">${order.remainedQuantity}주</td>
                    <td>
                        <div class="p-btn-group">
                            <button class="p-edit-btn" onclick="openEditModal(${order.orderId}, ${order.orderPrice}, ${order.remainedQuantity})">정정</button>
                            <button class="p-cancel-btn" onclick="cancelOrder(${order.orderId})">취소</button>
                        </div>
                    </td>
                </tr>`;
                tbody.append(row);
            });
        }
    });
}
setInterval(loadPendingOrders, 3000); // 3초마다 대기 목록 새로고침

// 주문 취소 함수
function cancelOrder(orderId) {
    if (!confirm('정말 이 주문을 취소하시겠습니까?')) return;

    $.ajax({
        url: contextPath + '/api/stock/cancel',
        type: 'POST',
        contentType: 'application/json',
        data: JSON.stringify({ orderId: orderId }),
        success: function(res) {
            if (res.success) {
                alert(res.message);
                loadPendingOrders(); // 리스트 새로고침
            } else {
                alert(res.message);
            }
        },
        error: function() {
            alert('취소 처리 중 오류가 발생했습니다.');
        }
    });
}

// 주문 정정 팝업
function openEditModal(orderId, currentPrice, currentQty) {
    const newPrice = prompt("정정할 가격을 입력하세요", currentPrice);
    const newQty = prompt("정정할 수량을 입력하세요", currentQty);

    if (newPrice && newQty) {
        const orderData = {
            orderId: orderId,
            orderPrice: parseInt(newPrice),
            quantity: parseInt(newQty)
        };

        $.ajax({
            url: contextPath + '/api/stock/modify',
            type: 'POST',
            contentType: 'application/json',
            data: JSON.stringify(orderData),
            success: function(res) {
                alert(res.message);
                if (res.success) loadPendingOrders();
            },
            error: function(xhr) {
                alert("통신 에러 발생");
            }
        });
    }
}

// 페이지 로드 시 관심종목 상태 확인
function checkFavoriteStatus() {
    const btn = document.querySelector('.detail-favorite-btn');
    if (!btn) return;

    // 비로그인: 서버 호출 없이 기본 상태 유지
    if (typeof window.__isLoggedIn === 'function' && !window.__isLoggedIn()) {
        btn.textContent = '♡';
        btn.classList.remove('active');
        return;
    }

    const stockCode = btn.getAttribute('data-code');
    if (!stockCode) return;

    fetch(contextPath + '/api/interest/list')
        .then(res => res.json())
        .then(interestCodes => {
            const isFavorite = Array.isArray(interestCodes) && interestCodes.includes(stockCode);
            btn.textContent = isFavorite ? '♥' : '♡';
            if (isFavorite) btn.classList.add('active');
            else btn.classList.remove('active');
        })
        .catch(err => console.error('관심종목 상태 확인 실패:', err));
}

// 관심종목 토글 버튼 이벤트
const __favoriteBtn = document.querySelector('.detail-favorite-btn');
if (__favoriteBtn) {
    __favoriteBtn.addEventListener('click', function(e) {
        // 비로그인: 모달
        if (typeof window.requireLogin === 'function' && !window.requireLogin(e)) return;

        const stockCode = this.getAttribute('data-code');

        fetch(contextPath + '/api/interest/toggle', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({ stockCode: stockCode })
        })
            .then(res => res.json())
            .then(data => {
                if (data.success) {
                    this.classList.toggle('active');
                    this.textContent = data.isInterest ? '♥' : '♡';
                }
            })
            .catch(error => {
                console.error('관심종목 토글 실패:', error);
            });
    });
}

// ========== 페이지 떠날 때 구독 해제 ==========
window.addEventListener('beforeunload', function(e) {
    unsubscribeAllStocks();

    if (stompClient !== null && stompClient.connected) {
        stompClient.disconnect(function() {
            console.log('STOMP 연결 종료');
        });
    }
});

window.addEventListener('pagehide', function(e) {
    unsubscribeAllStocks();
});

// ========== Mock 데이터 제어 함수 ==========

// 개발 프로필에서만 mock 모드를 사용합니다. 중지 후에도 실제 KIS 구독으로 전환하지 않습니다.
const isMockMode = window.kisMockEnabled === true;

async function mockRequest(path, stocks) {
    if (!isMockMode) throw new Error('dev-mock 프로필에서만 사용할 수 있습니다.');
    const options = path === '/status' ? {} : {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' }
    };
    if (stocks) options.body = JSON.stringify(stocks);
    const response = await fetch(contextPath + '/api/kis/mock' + path, options);
    if (!response.ok) throw new Error('Mock 요청 실패: ' + response.status);
    const data = await response.json();
    if (data.success === false) throw new Error(data.message);
    return data;
}

// 종목은 현재 화면에서 읽고, STOMP 토픽 구독 후 방송을 시작합니다.
async function enableMockMode() {
    try {
        if (!isMockMode) throw new Error('dev-mock 프로필에서만 사용할 수 있습니다.');
        if (!stompClient || !stompClient.connected) throw new Error('STOMP 연결이 필요합니다.');
        const priceElement = document.getElementById('detail-current-price-h3');
        const price = Number((priceElement ? priceElement.textContent : '').replace(/[^0-9]/g, ''));
        const mockStocks = currentStockCodes.map(stockCode => ({
            stockCode: stockCode,
            basePrice: Number.isSafeInteger(price) && price > 0 ? price : 10000
        }));
        if (mockStocks.length === 0) throw new Error('화면에 mock 대상 종목이 없습니다.');
        await mockRequest('/add-stocks', mockStocks);
        mockStocks.forEach(stock => subscribeStockTopic(stock.stockCode));
        const response = await mockRequest('/start');
        if (!response.isRunning) throw new Error('Mock 방송을 시작하지 못했습니다.');
        console.log('Mock 데이터 시작:', response);
        return true;
    } catch (error) {
        console.error('Mock 모드 활성화 실패:', error);
        return false;
    }
}

async function disableMockMode() {
    try {
        await mockRequest('/stop');
        await mockRequest('/clear');
        console.log('Mock 데이터 중지 완료. 다시 시작하려면 enableMockMode()를 호출하세요.');
        return true;
    } catch (error) {
        console.error('Mock 모드 비활성화 실패:', error);
        return false;
    }
}

async function checkMockStatus() {
    try {
        const response = await mockRequest('/status');
        console.log('Mock 상태:', response);
        return response;
    } catch (error) {
        console.error('Mock 상태 확인 실패:', error);
        return null;
    }
}

// 기존 개발 콘솔 진입점은 유지하며 서버 프로필이 없으면 요청하지 않습니다.
window.enableMockMode = enableMockMode;
window.disableMockMode = disableMockMode;
window.checkMockStatus = checkMockStatus;


(function() {
    console.log('=== 주문 UI 초기화 시작 ===');
    
    const tabLimit = document.getElementById('tab-limit');
    const tabMarket = document.getElementById('tab-market');
    const priceInput = document.getElementById('order-price-input');
    const quantityInput = document.getElementById('order-quantity-input');
    const qtyMinus = document.getElementById('qty-minus');
    const qtyPlus = document.getElementById('qty-plus');
    const submitBtn = document.getElementById('submit-btn');
    const orderModal = document.getElementById('order-modal');
    const modalClose = document.getElementById('modal-close');
    const modalCancel = document.getElementById('modal-cancel');
    const modalConfirm = document.getElementById('modal-confirm');
    
    const tabBtns = document.querySelectorAll('.detail-tab, .detail-tab-active');
    const presetBtns = document.querySelectorAll('.detail-preset-btn');
    
    let currentOrderType = 'limit';
    let hasInitializedLimitPrice = false;
    let currentTransactionType = 'buy';
    let currentPrice = 0;
    let availableBalance = 0;
    let ownedQuantity = 0; // 보유 주식 수량
    let isBalanceLoaded = false;
    let isQuantityLoaded = false;
    
    // ===== 실시간 현재가 가져오기 =====
    function getCurrentPrice() {
        const priceElement = document.getElementById('detail-current-price-h3');
        if (priceElement) {
            const priceText = priceElement.textContent.trim();
            const price = parseInt(priceText.replace(/[^0-9]/g, '')) || 0;
            if (price > 0) {
                currentPrice = price;
                console.log('현재가 업데이트:', currentPrice.toLocaleString());
                return currentPrice;
            }
        }
        return currentPrice;
    }
    
    // ===== 서버에서 잔액 가져오기 =====
    function fetchAvailableBalance() {
        console.log('잔액 조회 시작...');
        fetch(contextPath + '/api/account/balance')
            .then(response => {
                console.log('잔액 API 응답 상태:', response.status);
                return response.json();
            })
            .then(data => {
                console.log('잔액 조회 응답:', data);
                if (data.success) {
                    availableBalance = data.balance || 0;
                    isBalanceLoaded = true;
                    console.log('✓ 잔액 로드 성공:', availableBalance.toLocaleString() + '원');
                    updateBalanceDisplay();
                } else {
                    console.error('잔액 조회 실패:', data.message);
                    // 실패해도 0으로 표시
                    availableBalance = 0;
                    isBalanceLoaded = true;
                    updateBalanceDisplay();
                }
            })
            .catch(error => {
                console.error('잔액 조회 오류:', error);
                // 오류 시에도 0으로 표시
                availableBalance = 0;
                isBalanceLoaded = true;
                updateBalanceDisplay();
            });
    }
    
    // ===== 보유 주식 수량 가져오기 (매도용) =====
    function fetchOwnedQuantity() {
        const stockCode = new URLSearchParams(window.location.search).get('code');
        if (!stockCode) {
            console.error('종목 코드 없음');
            return;
        }
        
        console.log('보유 수량 조회 시작...');
        fetch(contextPath + '/api/account/holdings?stockCode=' + stockCode)
            .then(response => response.json())
            .then(data => {
                console.log('보유 수량 응답:', data);
                if (data.success) {
                    ownedQuantity = data.quantity || 0;
                    isQuantityLoaded = true;
                    console.log('✓ 보유 수량:', ownedQuantity + '주');
                } else {
                    ownedQuantity = 0;
                    isQuantityLoaded = true;
                }
            })
            .catch(error => {
                console.error('보유 수량 조회 오류:', error);
                ownedQuantity = 0;
                isQuantityLoaded = true;
            });
    }
    
    // ===== 화면 표시 업데이트 =====
    function updateBalanceDisplay() {
        const labelElement = document.getElementById('available-label');
        const balanceElement = document.querySelector('.detail-order-result .detail-row .detail-big-price');
        
        if (currentTransactionType === 'buy') {
            labelElement.textContent = '구매 가능 금액';
            if (balanceElement) {
                balanceElement.textContent = availableBalance.toLocaleString() + '원';
            }
        } else if (currentTransactionType === 'sell') {
            labelElement.textContent = '판매 가능 수량';
            if (balanceElement) {
                balanceElement.textContent = ownedQuantity.toLocaleString() + '주';
            }
        }
    }
    
    // ===== 초기 설정 =====
    function initialize() {
        console.log('초기 설정 시작...');
        
        // 현재가 가져오기
        getCurrentPrice();
        
        // 가격 입력 필드 초기화
        if (priceInput && currentPrice > 0) {
            priceInput.value = currentPrice.toLocaleString();
            hasInitializedLimitPrice = true;
            console.log('✓ 가격 초기화:', priceInput.value);
        }
        
        // 잔액 및 보유 수량 가져오기
        fetchAvailableBalance();
        fetchOwnedQuantity();
        
        // 초기 주문금액 계산
        setTimeout(updateOrderAmount, 100);
        
        console.log('✓ 초기 설정 완료');
    }
    
    // 페이지 로드 후 초기화
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', initialize);
    } else {
        initialize();
    }
    
    // 지정가/시장가 탭 전환
    if (tabLimit) {
        tabLimit.addEventListener('click', function() {
            if (currentOrderType === 'limit') return;
            console.log('지정가 선택');
            currentOrderType = 'limit';
            tabLimit.classList.add('detail-active-type');
            tabLimit.style.color = '';
            tabMarket.classList.remove('detail-active-type');
            tabMarket.style.color = '#6B7280';
            
            priceInput.disabled = false;
            getCurrentPrice();
            priceInput.value = currentPrice.toLocaleString();
            hasInitializedLimitPrice = currentPrice > 0;
            updateOrderAmount();
        });
    }
    
    if (tabMarket) {
        tabMarket.addEventListener('click', function() {
            console.log('시장가 선택');
            currentOrderType = 'market';
            tabMarket.classList.add('detail-active-type');
            tabMarket.style.color = '';
            tabLimit.classList.remove('detail-active-type');
            tabLimit.style.color = '#6B7280';
            
            // 시장가일 때도 현재가를 숫자로 표시
            priceInput.disabled = true;
            getCurrentPrice();
            priceInput.value = currentPrice.toLocaleString();
            updateOrderAmount();
        });
    }
    
    // 매수/매도 탭 전환
    tabBtns.forEach(btn => {
        btn.addEventListener('click', function(e) {
            if (typeof window.requireLogin === 'function' && !window.requireLogin(e)) return;
            const type = this.getAttribute('data-type');
            
            tabBtns.forEach(b => {
                b.classList.remove('detail-tab-active');
                b.classList.add('detail-tab');
            });
            this.classList.remove('detail-tab');
            this.classList.add('detail-tab-active');
            
            if (type === 'buy') {
                currentTransactionType = 'buy';
                submitBtn.textContent = '매수';
                submitBtn.classList.remove('sell-mode');
                updateBalanceDisplay();
            } else if (type === 'sell') {
                currentTransactionType = 'sell';
                submitBtn.textContent = '매도';
                submitBtn.classList.add('sell-mode');
                fetchOwnedQuantity(); // 매도 시 보유 수량 새로고침
                setTimeout(updateBalanceDisplay, 100);
            }
        });
    });
    
    // 수량 조절
    if (qtyMinus) {
        qtyMinus.addEventListener('click', function() {
            let qty = parseInt(quantityInput.value) || 1;
            if (qty > 1) {
                quantityInput.value = qty - 1;
                updateOrderAmount();
            }
        });
    }
    
    if (qtyPlus) {
        qtyPlus.addEventListener('click', function() {
            let qty = parseInt(quantityInput.value) || 0;
            quantityInput.value = qty + 1;
            updateOrderAmount();
        });
    }
    
    if (quantityInput) {
        quantityInput.addEventListener('input', updateOrderAmount);
    }
    
    if (priceInput) {
        priceInput.addEventListener('input', function() {
            if (currentOrderType === 'limit') {
                hasInitializedLimitPrice = true;
                let value = this.value.replace(/[^0-9]/g, '');
                if (value) {
                    this.value = parseInt(value).toLocaleString();
                }
                updateOrderAmount();
            }
        });
    }
    
    // ===== 퍼센트 버튼 =====
    presetBtns.forEach(btn => {
        btn.addEventListener('click', function() {
            console.log('=== 퍼센트 버튼 클릭 ===');
            
            const percent = this.getAttribute('data-percent');
            console.log('선택된 퍼센트:', percent + '%');
            
            // 버튼 활성화
            presetBtns.forEach(b => b.classList.remove('active'));
            this.classList.add('active');
            
            // 현재가 확인
            getCurrentPrice();
            
            let price = currentPrice;
            if (currentOrderType === 'limit') {
                const inputPrice = parseInt(priceInput.value.replace(/[^0-9]/g, ''));
                if (inputPrice > 0) {
                    price = inputPrice;
                }
            }
            
            let quantity = 0;
            
            if (currentTransactionType === 'buy') {
                // 매수: 잔액 기반 계산
                if (!isBalanceLoaded) {
                    alert('잔액 정보를 불러오는 중입니다.\n잠시 후 다시 시도해주세요.');
                    return;
                }
                
                if (availableBalance <= 0) {
                    alert('구매 가능 금액이 없습니다.');
                    return;
                }
                
                console.log('매수 계산:', { percent: percent + '%', price: price.toLocaleString(), balance: availableBalance.toLocaleString() });
                
                if (price > 0) {
                    if (percent == 100) {
                        quantity = Math.floor(availableBalance / price);
                    } else {
                        quantity = Math.floor((availableBalance * percent / 100) / price);
                    }
                }
            } else if (currentTransactionType === 'sell') {
                // 매도: 보유 수량 기반 계산
                if (!isQuantityLoaded) {
                    alert('보유 수량 정보를 불러오는 중입니다.\n잠시 후 다시 시도해주세요.');
                    return;
                }
                
                if (ownedQuantity <= 0) {
                    alert('판매 가능한 주식이 없습니다.');
                    return;
                }
                
                console.log('매도 계산:', { percent: percent + '%', ownedQuantity: ownedQuantity });
                
                if (percent == 100) {
                    quantity = ownedQuantity;
                } else {
                    quantity = Math.floor(ownedQuantity * percent / 100);
                }
            }
            
            quantity = Math.max(1, quantity);
            console.log('→ 계산된 수량:', quantity + '주');
            quantityInput.value = quantity;
            updateOrderAmount();
        });
    });
    
    // ===== 주문 금액 업데이트 =====
    function updateOrderAmount() {
        let price = 0;
        
        if (currentOrderType === 'limit') {
            price = parseInt(priceInput.value.replace(/[^0-9]/g, '')) || 0;
        } else {
            getCurrentPrice();
            price = currentPrice;
        }
        
        const quantity = parseInt(quantityInput.value) || 0;
        const totalMoney = price * quantity;
        
        const totalMoneyElement = document.getElementById('total-money');
        if (totalMoneyElement) {
            if (totalMoney > 0) {
                totalMoneyElement.textContent = totalMoney.toLocaleString() + '원';
            } else {
                totalMoneyElement.textContent = '0원';
            }
        }
    }
    
    // 매수/매도 버튼
    if (submitBtn) {
        submitBtn.addEventListener('click', function(e) {
            if (typeof window.requireLogin === 'function' && !window.requireLogin(e)) return;
            getCurrentPrice();
            
            const quantity = parseInt(quantityInput.value) || 0;
            let price = 0;
            
            if (currentOrderType === 'limit') {
                price = parseInt(priceInput.value.replace(/[^0-9]/g, '')) || 0;
                if (price <= 0) {
                    alert('가격을 입력해주세요.');
                    return;
                }
            } else {
                price = currentPrice;
                if (price <= 0) {
                    alert('현재가를 확인할 수 없습니다.');
                    return;
                }
            }
            
            if (quantity <= 0) {
                alert('수량을 입력해주세요.');
                return;
            }
            
            // 매수 검증
            if (currentTransactionType === 'buy') {
                if (!isBalanceLoaded) {
                    alert('잔액 정보를 불러오는 중입니다.\n잠시 후 다시 시도해주세요.');
                    return;
                }
                
                const totalPrice = price * quantity;
                if (totalPrice > availableBalance) {
                    alert('구매 가능 금액이 부족합니다.\n\n필요 금액: ' + totalPrice.toLocaleString() + '원\n보유 금액: ' + availableBalance.toLocaleString() + '원');
                    return;
                }
            }
            
            // 매도 검증
            if (currentTransactionType === 'sell') {
                if (!isQuantityLoaded) {
                    alert('보유 수량 정보를 불러오는 중입니다.\n잠시 후 다시 시도해주세요.');
                    return;
                }
                
                if (quantity > ownedQuantity) {
                    alert('판매 가능한 수량이 부족합니다.\n\n주문 수량: ' + quantity + '주\n보유 수량: ' + ownedQuantity + '주');
                    return;
                }
            }
            
            updateModalInfo(price, quantity);
            orderModal.classList.add('active');
        });
    }
    
    // 모달 정보 업데이트
    function updateModalInfo(price, quantity) {
        const stockName = document.querySelector('.detail-stock-name').textContent.trim().split(' ')[0];
        const orderTypeText = currentOrderType === 'limit' ? '지정가' : '시장가';
        const totalMoney = price * quantity;
        
        document.getElementById('modal-title').textContent = 
            currentTransactionType === 'buy' ? '매수 주문 확인' : '매도 주문 확인';
        document.getElementById('modal-stock-name').textContent = stockName;
        document.getElementById('modal-order-type').textContent = orderTypeText;
        
        const priceRow = document.getElementById('modal-price-row');
        priceRow.style.display = 'flex';
        if (currentOrderType === 'market') {
            document.getElementById('modal-price').textContent = price.toLocaleString() + '원 (시장가)';
        } else {
            document.getElementById('modal-price').textContent = price.toLocaleString() + '원';
        }
        
        document.getElementById('modal-quantity').textContent = quantity + '주';
        
        const totalElement = document.getElementById('modal-total');
        totalElement.textContent = totalMoney.toLocaleString() + '원';
        
        totalElement.classList.remove('buy', 'sell');
        totalElement.classList.add(currentTransactionType);
        
        const confirmBtn = document.getElementById('modal-confirm');
        confirmBtn.textContent = currentTransactionType === 'buy' ? '매수' : '매도';
        confirmBtn.classList.remove('buy', 'sell');
        confirmBtn.classList.add(currentTransactionType);
    }
    
    // 모달 닫기
    if (modalClose) {
        modalClose.addEventListener('click', () => orderModal.classList.remove('active'));
    }
    if (modalCancel) {
        modalCancel.addEventListener('click', () => orderModal.classList.remove('active'));
    }
    if (orderModal) {
        orderModal.addEventListener('click', function(e) {
            if (e.target === orderModal) orderModal.classList.remove('active');
        });
    }
    
    // 주문 확인
    if (modalConfirm) {
        modalConfirm.addEventListener('click', function() {
            const stockCode = new URLSearchParams(window.location.search).get('code');
            const quantity = parseInt(quantityInput.value) || 0;
            
            let price = 0;
            if (currentOrderType === 'limit') {
                price = parseInt(priceInput.value.replace(/[^0-9]/g, '')) || 0;
            } else {
                price = currentPrice;
            }
            
            const orderData = {
                stockCode: stockCode,
                transactionType: currentTransactionType.toUpperCase(),
                orderType: currentOrderType.toUpperCase(),
                quantity: quantity,
                orderPrice: price
            };
            
            console.log('주문 전송:', orderData);
            
            fetch(contextPath + '/api/stock/order', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(orderData)
            })
            .then(response => response.json())
            .then(data => {
                console.log('주문 결과:', data);
                
                if (data.success) {
                    alert('주문이 완료되었습니다.');
                    orderModal.classList.remove('active');
                    
                    quantityInput.value = '1';
                    if (currentOrderType === 'limit') {
                        getCurrentPrice();
                        priceInput.value = currentPrice.toLocaleString();
                    }
                    updateOrderAmount();
                    presetBtns.forEach(b => b.classList.remove('active'));
                    
                    // 잔액/보유수량 새로고침
                    setTimeout(() => {
                        fetchAvailableBalance();
                        fetchOwnedQuantity();
                    }, 1000);
                } else {
                    alert('주문 실패: ' + (data.message || '알 수 없는 오류'));
                }
            })
            .catch(error => {
                console.error('주문 오류:', error);
                alert('주문 중 오류가 발생했습니다.');
            });
        });
    }
    
    // 실시간 가격 감지
    const priceElement = document.getElementById('detail-current-price-h3');
    if (priceElement) {
        const observer = new MutationObserver(function() {
            const prevPrice = currentPrice;
            getCurrentPrice();
            
            if (prevPrice !== currentPrice) {
                console.log('가격 변동:', prevPrice.toLocaleString() + '원 → ' + currentPrice.toLocaleString() + '원');
                
                // 첫 시세가 늦게 도착한 경우만 초기화하고, 지정가 입력은 이후 유지합니다.
                if (currentOrderType === 'limit' && !hasInitializedLimitPrice && currentPrice > 0) {
                    priceInput.value = currentPrice.toLocaleString();
                    hasInitializedLimitPrice = true;
                    updateOrderAmount();
                }
                
                // 시장가일 때 자동 업데이트
                if (currentOrderType === 'market') {
                    priceInput.value = currentPrice.toLocaleString();
                    updateOrderAmount();
                }
            }
        });
        
        observer.observe(priceElement, {
            childList: true,
            characterData: true,
            subtree: true
        });
        
        console.log('✓ 실시간 가격 감지 시작');
    }
    
    // 주기적 새로고침 (30초마다)
    setInterval(() => {
        fetchAvailableBalance();
        if (currentTransactionType === 'sell') {
            fetchOwnedQuantity();
        }
    }, 30000);
    
    console.log('=== 주문 UI 초기화 완료 ===');
})();

// ========== 거래 비율 초기 상태로 복원 ==========
function resetSentimentToDefault() {
    console.log('거래 비율을 초기 상태로 복원');

    const sentimentText = document.querySelector('.detail-sentiment-text');
    const buyBar = document.getElementById('buy-bar');
    const sellBar = document.getElementById('sell-bar');
    const buyPercent = document.getElementById('buy-percent');
    const sellPercent = document.getElementById('sell-percent');

    const now = new Date();
    const day = now.getDay(); 
    const currentTime = now.getHours() * 100 + now.getMinutes();
    // 평일 09:00 ~ 20:00까지는 데이터를 기다리는 상태로 설정
    const isTradingTime = day >= 1 && day <= 5 && currentTime >= 900 && currentTime <= 2000;

    if (sentimentText) {
        // 장 중일 때만 "로딩중..." 출력
        sentimentText.innerHTML = `<span>${isTradingTime ? '로딩중...' : '장 시간에 확인할 수 있어요'}</span>`;
    }

    if (buyBar && sellBar) {
        // 장 중(로딩 시점)일 때만 회색(inactive) 처리, 아니면 기본 상태
        if (isTradingTime) {
            buyBar.classList.add('detail-sentiment-inactive');
            sellBar.classList.add('detail-sentiment-inactive');
        } else {
            buyBar.classList.add('detail-sentiment-inactive');
            sellBar.classList.add('detail-sentiment-inactive');
        }
        buyBar.style.width = '50%';
        sellBar.style.width = '50%';
    }

    if (buyPercent) buyPercent.textContent = '';
    if (sellPercent) sellPercent.textContent = '';
}

// ========== 호가 초기 상태로 복원 ==========
function resetHogaToDefault() {
    console.log('호가를 초기 상태로 복원');

    const hogaBox = document.querySelector('.detail-hoga-box');
    if (!hogaBox) return;

    const now = new Date();
    const day = now.getDay(); 
    const hours = now.getHours();
    const minutes = now.getMinutes();
    const currentTime = hours * 100 + minutes;

    // 평일 09:00 ~ 20:00까지는 '로딩중...'이 뜨게 함
    const isTradingTime = day >= 1 && day <= 5 && currentTime >= 900 && currentTime <= 2000;

    // innerHTML을 사용하여 placeholder 구조와 함께 텍스트 주입
    hogaBox.innerHTML = `
        <div class="detail-hoga-placeholder">
            <span>${isTradingTime ? '로딩중...' : '호가는 장 시간에 볼 수 있어요'}</span>
        </div>
    `;
}

// ========== 특정 시간에 자동 실행 예약 ==========
function scheduleMarketClose() {
    const now = new Date();

    // 15:30 예약
    const today1530 = new Date(now);
    today1530.setHours(15, 30, 0, 0);
    const msUntil1530 = today1530 - now;

    if (msUntil1530 > 0) {
        console.log(`15:30까지 ${Math.floor(msUntil1530 / 1000 / 60)}분 남음`);
        setTimeout(() => {
            console.log('15:30 정규장 마감 - 기본값으로 전환');
            resetSentimentToDefault();
            resetHogaToDefault();
        }, msUntil1530);
    } else {
        console.log('오늘 15:30은 이미 지났습니다.');
    }

    // 20:00 예약
    const today2000 = new Date(now);
    today2000.setHours(20, 0, 0, 0);
    const msUntil2000 = today2000 - now;

    if (msUntil2000 > 0) {
        console.log(`20:00까지 ${Math.floor(msUntil2000 / 1000 / 60)}분 남음`);
        setTimeout(() => {
            console.log('20:00 시간외 마감 - 기본값으로 전환');
            resetSentimentToDefault();
            resetHogaToDefault();
        }, msUntil2000);
    } else {
        console.log('오늘 20:00은 이미 지났습니다.');
    }
}

// FOMO 실시간 체크
let fomoAlertShown = false;
let fomoConditionMet = false;
let fomoCheckTimeout = null;

async function checkFomoInRealtime(changeRate) {
    const urlParams = new URLSearchParams(window.location.search);
    const stockCode = urlParams.get('code');
    if (!stockCode) return;
    
    if (changeRate >= 20) {
        fomoConditionMet = true;
        const buyTab = document.querySelector('[data-type="buy"]');
        const isBuyTab = buyTab && buyTab.classList.contains('detail-tab-active');
        
        if (!fomoAlertShown && isBuyTab) {
            if (fomoCheckTimeout) clearTimeout(fomoCheckTimeout);
            fomoCheckTimeout = setTimeout(async () => {
                const data = await checkFomoAlert(stockCode, changeRate);
                if (data && data.hasAlert) {
                    if (typeof showBiasAlert === 'function') {
                        showBiasAlert('FOMO');
                        fomoAlertShown = true;
                    }
                    if (typeof checkUnreadAlerts === 'function') {
                        setTimeout(() => { checkUnreadAlerts(); }, 0);
                    }
                }
            }, 500);
        }
    } else {
        fomoConditionMet = false;
        fomoAlertShown = false;
    }
}

// [상세페이지용] 단일 종목 신호등 상태 업데이트 함수
function updateDetailTrafficSignal() {
    // URL 파라미터에서 code 가져오기 (이미 detail.js에 유사한 로직이 있음)
    const urlParams = new URLSearchParams(window.location.search);
    const stockCode = urlParams.get('code');
    
    if (!stockCode) return;

    fetch(`${contextPath}/api/kis/foreigner-organization/${stockCode}`)
        .then(res => res.json())
        .then(data => {
            const lamp = document.getElementById(`signal-${stockCode}`);
            if (lamp && data.signalColor) {
                // 기존 색상 클래스 제거 (서버에서 대문자로 올 경우 대응)
                lamp.classList.remove('GREEN', 'RED', 'YELLOW', 'signal-green', 'signal-red', 'signal-yellow');
                lamp.classList.add(data.signalColor); // 서버 데이터가 'RED'라면 클래스 'RED'가 추가됨
                console.log(`[Detail Signal Success] ${stockCode} : ${data.signalColor}`);
            }
        })
        .catch(err => console.log(`[Detail Signal Error] ${stockCode} 통신 실패`, err));
}
