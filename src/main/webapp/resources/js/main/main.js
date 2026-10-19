var stompClient = stompClient || null;
var subscribedTopics = subscribedTopics || {};

function isLoggedIn() {
    if (typeof window.__isAuthenticated === 'undefined') return null;
    return window.__isAuthenticated === true;
}

function showLoginRequiredModal() {
    let modal = document.getElementById('login-required-modal');

    // 없으면 동적으로 생성
    if (!modal) {
        modal = document.createElement('div');
        modal.id = 'login-required-modal';
        modal.className = 'login-required-modal';
        modal.innerHTML = `
            <div class="login-required-backdrop" data-close="true"></div>
            <div class="login-required-panel" role="dialog" aria-modal="true">
                <div class="login-required-body">로그인이 필요합니다.</div>
                <div class="login-required-actions">
                    <button type="button" class="login-required-ok" data-close="true">확인</button>
                </div>
            </div>
        `;
        document.body.appendChild(modal);

        // 닫기(백드롭/확인 버튼)
        modal.addEventListener('click', (e) => {
            if (e.target && e.target.getAttribute('data-close') === 'true') {
                modal.classList.remove('active');
            }
        });

        // ESC 닫기
        document.addEventListener('keydown', (e) => {
            if (e.key === 'Escape') modal.classList.remove('active');
        });
    }

    modal.classList.add('active');
}

function ensureLoginOrModal() {
    const v = isLoggedIn();
    if (v === false) {
        showLoginRequiredModal();
        return false;
    }
    return true;
}

// 비로그인 상태면 interest/list를 아예 호출하지 않게(시큐리티 막혀 있어도 안전)
function getInterestCodesPromise() {
    const v = isLoggedIn();
    if (v === false) return Promise.resolve([]);

    return fetch(contextPath + '/api/interest/list', { cache: 'no-store' })
        .then(res => {
            if (!res.ok) return [];
            const ct = res.headers.get('content-type') || '';
            if (!ct.includes('application/json')) return [];
            return res.json();
        })
        .catch(() => []);
}


// STOMP 연결
function connectStomp() {
    var url = contextPath + '/ws-stomp';
    var socket = new SockJS(url);
    stompClient = Stomp.over(socket);
    stompClient.debug = null;
    stompClient.connect({}, function (frame) {
        console.log('STOMP 연결 성공: ' + frame);

        // (종목 리스트 렌더링 안에서 구독이 자동으로 이뤄짐)
        renderMainStocks();
    }, function(error) {
        console.error('STOMP 연결 실패: ' + error);
        // 5초 후 재연결 시도
        setTimeout(connectStomp, 5000);
    });
}

// 날짜 포멧 변경
function formatDate(yyyymmdd) {
    return {
        year: Number(yyyymmdd.substring(0, 4)),
        month: Number(yyyymmdd.substring(4, 6)),
        day: Number(yyyymmdd.substring(6, 8)),
    };
}

// 종목 아이템 HTML 생성 함수
function createMainStockItemHTML(stock, index) {
    const favoriteIcon = stock.isFavorite ? '♥' : '♡';
    const favoriteClass = stock.isFavorite ? 'active' : '';
    const currentPrice = Number(stock.stck_prpr).toLocaleString('ko-KR') + '원';
    const imgUrl = contextPath + '/resources/images/stock/' + stock.mksc_shrn_iscd + '.png';
    let changeText = '0.00%';
    let changeClass = '';

    if (stock.prdy_ctrt) {
        const changeValue = parseFloat(stock.prdy_ctrt);
        if (changeValue > 0) {
            changeText = '+' + changeValue + '%';
            changeClass = 'positive';
        } else if (changeValue < 0) {
            changeText = changeValue + '%';
            changeClass = 'negative';
        } else {
            changeText = changeValue + '%';
        }
    }

    return `
        <div class="main-stocklist-item" data-id="${stock.mksc_shrn_iscd}">
            <div class="main-stocklist-favorite">
                <span class="main-stocklist-rank">${index + 1}</span>
                <button class="main-stocklist-favorite-btn ${favoriteClass}" data-id="${stock.mksc_shrn_iscd}">${favoriteIcon}</button>
            </div>
            <div class="main-stocklist-info">
                <div class="main-stocklist-logo">
                    <img src="${imgUrl}" alt="${stock.hts_kor_isnm}"
                        onerror="this.src='${contextPath}/resources/images/antmillion-logo.png'">
                </div>
                <div class="main-signal-lamp" id="signal-${stock.mksc_shrn_iscd}"></div>
                <span class="main-stocklist-name">${stock.hts_kor_isnm}</span>
                
            </div>
            <div class="main-stocklist-price">${currentPrice}</div>
            <div class="main-stocklist-change ${changeClass}">${changeText}</div>
            <div class="main-stocklist-sentiment">
                <div class="main-stocklist-sentiment-bar">
                    <div class="main-stocklist-sentiment-buy main-sentiment-inactive" style="width: 50%;"></div>
                    <div class="main-stocklist-sentiment-sell main-sentiment-inactive" style="width: 50%;"></div>
                </div>
                <div class="main-stocklist-sentiment-labels">
                    <span class="main-stocklist-sentiment-buy-label"></span>
                    <span class="main-stocklist-sentiment-sell-label"></span>
                </div>
            </div>
        </div>
    `;
}

// 종목 리스트 렌더링 함수
function renderMainStocks() {
    const container = document.getElementById('main-stocklist-Container');
    if (!container) return;

    fetch(contextPath + '/api/kis/volumeRank')
        .then(res => res.json())
        .then(data => {
            return getInterestCodesPromise().then(interestCodes => {
                if (!Array.isArray(interestCodes)) interestCodes = [];

                const html = data
                    .slice(0, 5)
                    .map((stock, index) => {
                        stock.isFavorite = interestCodes.includes(stock.mksc_shrn_iscd);
                        return createMainStockItemHTML(stock, index);
                    }).join('');

                container.innerHTML = html;

                // 이벤트 리스너
                attachMainFavoriteListeners();
                attachMainStockItemListeners();

                // 차트 기본 1번
                if (data.length > 0) {
                    const firstStock = data[0];
                    const chartStockName = document.querySelector('.main-chart-stock-name');
                    const chartStockCode = document.querySelector('.main-chart-stock-code');

                    if (chartStockName) chartStockName.textContent = firstStock.hts_kor_isnm;
                    if (chartStockCode) chartStockCode.textContent = firstStock.mksc_shrn_iscd;

                    drawStockMinuteChart(firstStock.mksc_shrn_iscd);
                }

                // 렌더링 완료 후 구독
                if (!isMockMode) {
                    const stockCodes = data.slice(0, 5).map(s => s.mksc_shrn_iscd);
                    subscribeMainStocks(stockCodes);
                }
                
                // 신호등 업데이트는 시차를 두고 실행
                setTimeout(() => {
                    console.log('[System] 신호등 상태 업데이트 시작');
                    updateAllTrafficSignals();
                }, 500);
            });
        })
        .catch(error => {
            console.error('메인 종목 로드 실패:', error);
        });
}

// 즐겨찾기(구독) 버튼 이벤트 리스너 등록 (이벤트 위임 + 비로그인 모달)
function attachMainFavoriteListeners() {
    const container = document.getElementById('main-stocklist-Container');
    if (!container) return;

    if (container.dataset.favBound === '1') return;
    container.dataset.favBound = '1';

    container.addEventListener('click', function (e) {
        const btn = e.target.closest('.main-stocklist-favorite-btn');
        if (!btn) return;

        e.stopPropagation();
        e.preventDefault();

        if (!ensureLoginOrModal()) return;

        const stockCode = btn.getAttribute('data-id');

        fetch(contextPath + '/api/interest/toggle', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ stockCode })
        })
        .then(res => {
            // 비로그인이거나 권한 없으면 모달
            if (res.status === 401 || res.status === 403) {
                showLoginRequiredModal();
                return null;
            }
            const ct = res.headers.get('content-type') || '';
            if (!ct.includes('application/json')) {
                showLoginRequiredModal();
                return null;
            }
            return res.json();
        })
        .then(data => {
            if (!data) return;

            if (data.success) {
                btn.classList.toggle('active');
                btn.textContent = data.isInterest ? '♥' : '♡';
            } else if (data.message === 'LOGIN_REQUIRED') {
                showLoginRequiredModal();
            }
        })
        .catch(err => {
            console.error('관심종목 토글 실패:', err);
        });
    }, true);
}

// 종목 아이템 클릭 이벤트 리스너 등록
function attachMainStockItemListeners() {

    document.querySelectorAll('.main-stocklist-item').forEach((item, index) => {
        // 첫 번째 항목을 active로 설정
        if (index === 0) {
            item.classList.add('main-active');
        }
        
        // mouseenter 이벤트 추가
        item.addEventListener('mouseenter', function(e) {
            // 즐겨찾기 버튼 클릭은 제외
            if (e.target.closest('.main-stocklist-favorite-btn')) {
                return;
            }
            
            // 모든 항목의 active 제거
            document.querySelectorAll('.main-stocklist-item').forEach(i => i.classList.remove('main-active'));
            
            // 현재 항목을 active로 설정
            this.classList.add('main-active');
            
            // 종목명 가져오기
            const stockName = this.querySelector('.main-stocklist-name').textContent;
            
            // 차트 헤더 업데이트
            const stockCode = this.getAttribute('data-id');
            const chartStockCode = document.querySelector('.main-chart-stock-code');
            const chartStockName = document.querySelector('.main-chart-stock-name');
            if (chartStockName) {
                chartStockName.textContent = stockName;
            }
            if (chartStockCode) {
                chartStockCode.textContent = stockCode;
            }
            
            // 차트 업데이트
            drawStockMinuteChart(stockCode);
        });
        
        // click 이벤트 추가
        item.addEventListener('click', function(e) {
            // 즐겨찾기 버튼 클릭은 제외
            if (e.target.closest('.main-stocklist-favorite-btn')) {
                return;
            }

            const stockId = this.getAttribute('data-id');

            // contextPath는 JSP에서 전역 변수로 설정되어 있음
            window.location.href = contextPath + '/stock/detail?code=' + stockId;
        });
    });
}

// DOM이 로드되면 초기화
document.addEventListener('DOMContentLoaded', function() {
    scheduleMarketClose();
    initializeMainPage();
    
    // 10분마다 신호등 상태만 별도로 업데이트
    setInterval(function() {
        console.log('[Auto Update] 신호등 상태 갱신');
        updateAllTrafficSignals();
    }, 600000);
});

function initializeMainPage() {
    connectStomp();

    // 레이아웃 완전 확정 후 차트 초기화
    requestAnimationFrame(() => {
        requestAnimationFrame(() => {
            initializeCharts();
        });
    });


    // 미션 버튼 이벤트
    initializeMissionButton();
}

// 차트 초기화 (TradingView Lightweight Charts 사용)
function initializeCharts() {
    //코스피 차트
    drawMarketIndexChart(
        '.main-grid-kospi',
        '#main-kospi-chart',
        contextPath + '/api/kis/marketIndex/0001'
    );
    //코스닥 차트
    drawMarketIndexChart(
        '.main-grid-kosdaq',
        '#main-kosdaq-chart',
        contextPath + '/api/kis/marketIndex/1001'
    );
    
    // 차트 생성 후 강제로 resize 이벤트 발생
    setTimeout(() => {
        window.dispatchEvent(new Event('resize'));
    }, 100);
    
    // window resize 이벤트 핸들러 등록
    window.addEventListener('resize', handleChartResize);
}

// 차트 리사이즈 핸들러
function handleChartResize() {
    // 코스피 차트 리사이즈
    const kospiChart = document.querySelector('#main-kospi-chart');
    if (kospiChart && kospiChart._chart) {
        const kospiContainer = kospiChart.parentElement;
        kospiChart._chart.resize(kospiContainer.clientWidth, kospiContainer.clientHeight);
    }
    
    // 코스닥 차트 리사이즈
    const kosdaqChart = document.querySelector('#main-kosdaq-chart');
    if (kosdaqChart && kosdaqChart._chart) {
        const kosdaqContainer = kosdaqChart.parentElement;
        kosdaqChart._chart.resize(kosdaqContainer.clientWidth, kosdaqContainer.clientHeight);
    }
    
    // 종목 차트 리사이즈
    if (stockChart) {
        const chartContainer = document.getElementById('main-stockChart');
        if (chartContainer) {
            stockChart.resize(chartContainer.clientWidth, chartContainer.clientHeight);
        }
    }
}

//코스피/코스닥 차트
function drawMarketIndexChart(cardSelector, chartId, apiUrl) {
    const card = document.querySelector(cardSelector);
    const chartEl = card.querySelector(chartId);
    const container = chartEl.parentElement;
    
    const chart = LightweightCharts.createChart(chartEl, {
        width: container.clientWidth,
        height: container.clientHeight,
        layout: {
            background: {type: 'solid', color: 'white'},
            textColor: 'black'
        },
        grid: {
            vertLines: { color: '#eee' },
            horzLines: { color: '#eee' }
        },
        timeScale: {
            borderColor: '#cccccc'
        },
        rightPriceScale: {
            visible: true,
            borderColor: '#cccccc'
        }
    });
    const candleSeries = chart.addSeries(
        LightweightCharts.CandlestickSeries,
        {
            upColor: '#e74c3c',
            downColor: '#3498db',
            borderUpColor: '#e74c3c',
            borderDownColor: '#3498db',
            wickUpColor: '#e74c3c',
            wickDownColor: '#3498db'
        }
    );

    fetch(apiUrl)
        .then(res => res.json())
        .then(data => {
            renderMarketIndexHeader(card, Number(data[0].bstp_nmix_prpr), Number(data[1].bstp_nmix_prpr))
            data.sort((a, b) => a.stck_bsop_date.localeCompare(b.stck_bsop_date))
            const chartData = data.map(row => ({
                time: formatDate(row.stck_bsop_date),
                open: Number(row.bstp_nmix_oprc),
                high: Number(row.bstp_nmix_hgpr),
                low: Number(row.bstp_nmix_lwpr),
                close: Number(row.bstp_nmix_prpr)
            }));

            candleSeries.setData(chartData);
            chart.timeScale().fitContent();

            setTimeout(() => {
                chart.resize(container.clientWidth, container.clientHeight);
            }, 50);
        });
    chartEl._chart = chart;
}

//코스피/코스닥 차트 헤더 변경 - 현재 수치, 전일대비
function renderMarketIndexHeader(cardEl, today, yesterday) {
    const presentPriceEl = cardEl.querySelector('.main-market-index-price');
    const changeValueEl = cardEl.querySelector('.main-change-value');
    const changePercentEl = cardEl.querySelector('.main-change-percent');

    const diff =  today - yesterday;
    const diffPercent = (diff / yesterday) * 100;

    presentPriceEl.textContent = today+'';
    changeValueEl.textContent = `${diff > 0 ? '+' : ''}${diff.toFixed(2)}`;
    changePercentEl.textContent = `${diff > 0 ? '+' : ''}${diffPercent.toFixed(2)}%`;
    const isPositive = diff > 0;
    [changeValueEl, changePercentEl].forEach(el => {
        el.classList.toggle('main-positive', isPositive);
        el.classList.toggle('main-negative', !isPositive);
    });
}

// 미션 버튼 이벤트
function initializeMissionButton() {
    const missionButton = document.querySelector('.main-mission-button');
    
    if (missionButton) {
        missionButton.addEventListener('click', function() {
            // 미션 페이지로 이동 또는 모달 표시
            window.showNotification('미션 페이지로 이동합니다...', 'info');
            
            // 실제로는 다음과 같이 페이지 이동
            setTimeout(() => {
                // window.location.href = 'quiz.jsp';
            }, 1000);
        });
    }
}

// 일별 분봉 조회 - 차트
let stockChart = null;
let candleSeries = null;
let currentCandleData = null; // 추가: 현재 진행 중인 캔들
let lastCandleUpdateTime = null; // 추가: 마지막 캔들 업데이트 시간
let currentChartStockCode = null; // 추가: 현재 차트에 표시 중인 종목 코드

// 시간 포맷 변환 함수
function formatToTimestamp(dateStr, timeStr) {
    const year = parseInt(dateStr.substring(0, 4));
    const month = parseInt(dateStr.substring(4, 6)) - 1;
    const day = parseInt(dateStr.substring(6, 8));
    const hour = parseInt(timeStr.substring(0, 2));
    const minute = parseInt(timeStr.substring(2, 4));
    const second = parseInt(timeStr.substring(4, 6)) || 0;

    // 로컬 시간대로 Date 객체 생성 후 Unix 타임스탬프로 변환
    const date = new Date(year, month, day, hour, minute, second);
    return Math.floor(date.getTime() / 1000);
}

// 차트 그리기 함수
function drawStockMinuteChart(stockCode) {
    const chartContainer = document.getElementById('main-stockChart');
    if (!chartContainer) return;

    // 1. 기존 차트 완전히 제거
    if (stockChart) {
        stockChart.remove();
        stockChart = null;
        candleSeries = null;
    }

    chartContainer.innerHTML = ''; // DOM도 초기화

    currentChartStockCode = stockCode;
    currentCandleData = null;
    lastCandleUpdateTime = null;

    // 2. 새 차트 생성
    stockChart = LightweightCharts.createChart(chartContainer, {
        width: chartContainer.clientWidth,
        height: chartContainer.clientHeight,
        localization: {
            locale: 'ko-KR',
            timeFormatter: (time) => {
                const date = new Date(time * 1000); // 타임존 보정 제거
                const m = date.getMonth() + 1;
                const d = date.getDate();
                const hh = String(date.getHours()).padStart(2, '0');
                const mm = String(date.getMinutes()).padStart(2, '0');
                return `${m}/${d} ${hh}:${mm}`;
            }
        },
        layout: {
            background: { type: 'solid', color: 'white' },
            textColor: '#333',
            fontFamily: "'Pretendard', sans-serif",
            fontSize: 12
        },
        grid: {
            vertLines: { color: '#f0f0f0' },
            horzLines: { color: '#f0f0f0' }
        },
        crosshair: {
            mode: 1,
            vertLine: {
                width: 1,
                color: '#C3BCDB44',
                style: 0
            },
            horzLine: {
                width: 1,
                color: '#C3BCDB44',
                style: 0
            }
        },
        rightPriceScale: {
            borderColor: '#cccccc',
            scaleMargins: {
                top: 0.1,
                bottom: 0.1
            }
        },
        timeScale: {
            borderColor: '#cccccc',
            timeVisible: true,
            secondsVisible: false,
            tickMarkFormatter: (time, tickMarkType, locale) => {
                const date = new Date(time * 1000); // 타임존 보정 제거
                const hh = String(date.getHours()).padStart(2, '0');
                const mm = String(date.getMinutes()).padStart(2, '0');
                return `${hh}:${mm}`;
            }
        }
    });

    candleSeries = stockChart.addSeries(LightweightCharts.CandlestickSeries, {
        upColor: '#e74c3c',
        downColor: '#3498db',
        borderVisible: false,
        wickUpColor: '#e74c3c',
        wickDownColor: '#3498db'
    });

    // 3. 초기 데이터 로드 (분봉 데이터)
    fetch(contextPath + `/api/kis/stream/${stockCode}`)
        .then(res => res.json())
        .then(data => {
            if (!data || data.length === 0) {
                console.warn('분봉 데이터가 없습니다:', stockCode);
                return;
            }

            // 4. 데이터 정렬
            data.sort((a, b) => {
                const timeA = formatToTimestamp(a.stck_bsop_date, a.stck_cntg_hour);
                const timeB = formatToTimestamp(b.stck_bsop_date, b.stck_cntg_hour);
                return timeA - timeB;
            });

            // 5. 차트 데이터 포맷 변환
            const formattedData = data.map(item => {
                const timestamp = formatToTimestamp(item.stck_bsop_date, item.stck_cntg_hour);
                return {
                    time: timestamp,
                    open: Number(item.stck_oprc),
                    high: Number(item.stck_hgpr),
                    low: Number(item.stck_lwpr),
                    close: Number(item.stck_prpr)
                };
            });

            // 6. 차트에 전체 데이터 설정 (setData 사용)
            candleSeries.setData(formattedData);
            stockChart.timeScale().fitContent();

            // 7. 현재 진행 중인 캔들 초기화 (마지막 데이터)
            if (formattedData.length > 0) {
                currentCandleData = { ...formattedData[formattedData.length - 1] };
                lastCandleUpdateTime = currentCandleData.time;
            }

            // 8. 실시간 데이터 구독 (해당 종목만)
            subscribeStockToRealtime(stockCode);
        })
        .catch(err => console.error('분봉 차트 데이터 로드 실패:', err));
}

// 실시간 데이터(체결) 구독: STOMP 토픽에서 받아서 캔들 업데이트
function subscribeStockToRealtime(stockCode) {
    if (!stompClient || !stompClient.connected) {
        console.warn('STOMP 연결이 없습니다. 구독 불가:', stockCode);
        return;
    }

    const topic = '/topic/kis-trade/present' + stockCode;

    // 이미 구독 중이면 해제 후 재구독
    if (subscribedTopics[stockCode]) {
        subscribedTopics[stockCode].unsubscribe();
        delete subscribedTopics[stockCode];
    }

    const subscription = stompClient.subscribe(topic, function(message) {
        const tradeData = JSON.parse(message.body);

        // 종목 리스트 현재가/등락률 업데이트
        updateMainStockRealtimePrice(stockCode, tradeData);

        // 차트는 현재 표시 중인 종목만 업데이트
        if (currentChartStockCode === stockCode) {
            updateCandleWithTrade(tradeData);
        }
    });

    subscribedTopics[stockCode] = subscription;
}

// 실시간 체결 데이터로 캔들 업데이트
function updateCandleWithTrade(tradeData) {
    if (!candleSeries) {
        console.warn('캔들 시리즈가 초기화되지 않았습니다');
        return;
    }

    const price = Number(tradeData.stckPrpr);
    const now = new Date();

    // 초 단위를 0으로 맞춰서 1분 단위 캔들 생성
    const base = new Date(now.getFullYear(), now.getMonth(), now.getDate(), now.getHours(), now.getMinutes(), 0);
    const timestamp = Math.floor(base.getTime() / 1000);

    // 새로운 1분봉이 시작되었거나 초기 데이터가 없을 때
    if (!currentCandleData || lastCandleUpdateTime !== timestamp) {
        currentCandleData = {
            time: timestamp,
            open: price,
            high: price,
            low: price,
            close: price
        };
        lastCandleUpdateTime = timestamp;
    } else {
        // 같은 1분 내에서는 high/low/close만 업데이트
        currentCandleData.high = Math.max(currentCandleData.high, price);
        currentCandleData.low = Math.min(currentCandleData.low, price);
        currentCandleData.close = price;
    }

    // update 메서드로 현재 캔들 갱신
    candleSeries.update(currentCandleData);
}

// 종목 리스트 실시간 가격/등락률 업데이트 (메인)
function updateMainStockRealtimePrice(stockCode, tradeData) {
    const stockItem = document.querySelector(`.main-stocklist-item[data-id="${stockCode}"]`);
    if (!stockItem) return;

    const priceElement = stockItem.querySelector('.main-stocklist-price');
    if (priceElement) {
        const price = Number(tradeData.stckPrpr).toLocaleString('ko-KR') + '원';
        priceElement.textContent = price;
    }

    const changeElement = stockItem.querySelector('.main-stocklist-change');
    if (changeElement) {
        let sign = '';
        let signClass = '';

        if (tradeData.prdySign === '1' || tradeData.prdySign === '2') {
            sign = '+';
            signClass = 'positive';
        } else if (tradeData.prdySign === '4' || tradeData.prdySign === '5') {
            sign = '';
            signClass = 'negative';
        } else {
            sign = '';
            signClass = '';
        }

        changeElement.textContent = sign + tradeData.prdyCtrt + '%';

        changeElement.classList.remove('positive', 'negative');
        if (signClass) {
            changeElement.classList.add(signClass);
        }
    }

    if (tradeData.shnuRate) {
        const buyRate = parseFloat(tradeData.shnuRate) * 100;
        const sellRate = 100 - buyRate;

        const buyBar = stockItem.querySelector('.main-stocklist-sentiment-buy');
        const sellBar = stockItem.querySelector('.main-stocklist-sentiment-sell');
        const buyLabel = stockItem.querySelector('.main-stocklist-sentiment-buy-label');
        const sellLabel = stockItem.querySelector('.main-stocklist-sentiment-sell-label');

        if (buyBar && sellBar) {
            buyBar.classList.remove('main-sentiment-inactive');
            sellBar.classList.remove('main-sentiment-inactive');

            buyBar.style.width = buyRate + '%';
            sellBar.style.width = sellRate + '%';
        }

        if (buyLabel && sellLabel) {
            buyLabel.textContent = Math.round(buyRate);
            sellLabel.textContent = Math.round(sellRate);
        }
    }
}


// ====== 아래는 원본 파일의 웹소켓(백엔드 구독), mock, 마감 스케줄 관련 로직 ======

function subscribeMainStocks(stockCodes) {
    stockCodes.forEach(code => subscribeStockToRealtime(code));
}


// ========== Mock 데이터 제어 함수 ==========

let isMockMode = false; // Mock 모드 플래그

// Mock 모드로 전환 (한투 구독 건너뛰고 Mock만 사용)
function enableMockMode() {
    isMockMode = true;
    console.log('Mock 모드 활성화');

    unsubscribeAllStocks();

    // 현재 화면에 표시된 종목 리스트 가져오기
    const stockItems = document.querySelectorAll('.main-stocklist-item');
    const mockStocks = [];

    stockItems.forEach(item => {
        const stockCode = item.getAttribute('data-id');
        const priceText = item.querySelector('.main-stocklist-price').textContent;
        const basePrice = parseInt(priceText.replace(/[^0-9]/g, ''));

        mockStocks.push({
            stockCode: stockCode,
            basePrice: basePrice
        });
    });

    // Mock 종목 등록
    fetch(contextPath + '/api/kis/mock/add-stocks', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json'
        },
        body: JSON.stringify(mockStocks)
    })
        .then(res => res.json())
        .then(response => {
            console.log('Mock 종목 등록:', response);

            // Mock 데이터 전송 시작
            return fetch(contextPath + '/api/kis/mock/start', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json'
                }
            });
        })
        .then(res => res.json())
        .then(response => {
            console.log('Mock 데이터 시작:', response);

            // STOMP 구독만 진행 (한투 백엔드 구독은 건너뜀)
            const stockCodes = mockStocks.map(s => s.stockCode);
            stockCodes.forEach(stockCode => {
                subscribeStockToRealtime(stockCode);
            });

            alert('테스트 모드 시작! 한투 연결 없이 가짜 데이터로 테스트합니다.');
        })
        .catch(error => {
            console.error('Mock 모드 활성화 실패:', error);
        });
}

// Mock 모드 비활성화
function disableMockMode() {
    isMockMode = false;

    // Mock 데이터 중지
    fetch(contextPath + '/api/kis/mock/stop', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json'
        }
    })
        .then(res => res.json())
        .then(response => {
            console.log('Mock 데이터 중지:', response);

            // Mock 종목 제거
            return fetch(contextPath + '/api/kis/mock/clear', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json'
                }
            });
        })
        .then(res => res.json())
        .then(response => {
            console.log('Mock 종목 제거:', response);
            alert('테스트 모드 종료! 실제 모드로 전환하려면 페이지를 새로고침하세요.');
        })
        .catch(error => {
            console.error('Mock 모드 비활성화 실패:', error);
        });
}

// 브라우저 콘솔에서 사용 가능하도록 전역으로 노출
window.enableMockMode = enableMockMode;
window.disableMockMode = disableMockMode;

// ========== 거래 비율 초기 상태로 복원 ==========
function resetSentimentToDefault() {
    console.log('[main.js] 모든 종목의 거래 비율을 초기 상태로 복원');

    const stockItems = document.querySelectorAll('.main-stocklist-item');

    stockItems.forEach(item => {
        const buyBar = item.querySelector('.main-stocklist-sentiment-buy');
        const sellBar = item.querySelector('.main-stocklist-sentiment-sell');
        const buyLabel = item.querySelector('.main-stocklist-sentiment-buy-label');
        const sellLabel = item.querySelector('.main-stocklist-sentiment-sell-label');

        if (buyBar && sellBar) {
            buyBar.classList.add('main-sentiment-inactive');
            sellBar.classList.add('main-sentiment-inactive');
            buyBar.style.width = '50%';
            sellBar.style.width = '50%';
        }

        if (buyLabel) buyLabel.textContent = '';
        if (sellLabel) sellLabel.textContent = '';
    });
}

// ========== 15:30, 20:00에 자동 실행 예약 ==========
function scheduleMarketClose() {
    const now = new Date();

    // 15:30 예약
    const today1530 = new Date(now);
    today1530.setHours(15, 30, 0, 0);
    const msUntil1530 = today1530 - now;

    if (msUntil1530 > 0) {
        console.log(`[main.js] 15:30까지 ${Math.floor(msUntil1530 / 1000 / 60)}분 남음`);
        setTimeout(() => {
            console.log('[main.js] 15:30 정규장 마감 - 기본값으로 전환');
            resetSentimentToDefault();
        }, msUntil1530);
    } else {
        console.log('[main.js] 오늘 15:30은 이미 지났습니다.');
    }

    // 20:00 예약
    const today2000 = new Date(now);
    today2000.setHours(20, 0, 0, 0);
    const msUntil2000 = today2000 - now;

    if (msUntil2000 > 0) {
        console.log(`[main.js] 20:00까지 ${Math.floor(msUntil2000 / 1000 / 60)}분 남음`);
        setTimeout(() => {
            console.log('[main.js] 20:00 시간외 마감 - 기본값으로 전환');
            resetSentimentToDefault();
        }, msUntil2000);
    } else {
        console.log('[main.js] 오늘 20:00은 이미 지났습니다.');
    }
    
}

//모든 종목의 신호등 상태를 서버에서 가져와 업데이트하는 함수
async function updateAllTrafficSignals() {
    const stockItems = document.querySelectorAll('.main-stocklist-item');
    console.log('[System] 메인 신호등 상태 업데이트 시작');

    for (const item of stockItems) {
        const stockCode = item.getAttribute('data-id'); // 메인은 data-id를 사용함에 유의
        if (!stockCode) continue;

        try {
            const res = await fetch(`${contextPath}/api/kis/foreigner-organization/${stockCode}`);
            const data = await res.json();
            const lamp = document.getElementById(`signal-${stockCode}`);
            
            if (lamp && data.signalColor) {
                // 기존 색상 클래스 모두 제거 후 새 색상 추가
                lamp.classList.remove('GREEN', 'RED', 'YELLOW');
                lamp.classList.add(data.signalColor);
            }
        } catch (err) {
            console.error(`[Signal Error] ${stockCode} 업데이트 실패:`, err);
        }
        
        // 0.05초 대기하여 네트워크 병목 방지
        await new Promise(resolve => setTimeout(resolve, 50));
    }
}

// ========== 이 연결의 모든 구독 해제 ==========
function unsubscribeAllStocks() {
    console.log('[main.js] 전체 구독 해제 시작...');

    // 프론트엔드 STOMP 구독 해제
    Object.keys(subscribedTopics).forEach(stockCode => {
        if (subscribedTopics[stockCode]) {
            subscribedTopics[stockCode].unsubscribe();
            console.log('[main.js] 토픽 구독 해제:', stockCode);
        }
    });
    subscribedTopics = {};
}

// ========== 페이지 떠날 때 전체 구독 해제 ==========
window.addEventListener('beforeunload', function(e) {
    unsubscribeAllStocks();

    if (stompClient && stompClient.connected) {
        stompClient.disconnect(function() {
            console.log('[main.js] STOMP 연결 종료');
        });
    }
    
    // resize 이벤트 핸들러 제거
    window.removeEventListener('resize', handleChartResize);
});

window.addEventListener('pagehide', function(e) {
    unsubscribeAllStocks();
    window.removeEventListener('resize', handleChartResize);
});
