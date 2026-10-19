// ===========================
// STOMP 웹소켓 관련 변수 (추가)
// ===========================
let stompClient = null;
let subscribedStockTopics = {}; // 구독한 종목 토픽 저장 {stockCode: subscription}
let currentHoldingStocks = []; // 현재 보유 중인 종목 정보 (code 포함)

// 계좌 잔고
let accountBalance = 0;

// ===========================
// STOMP 연결
// ===========================
function connectStompForStockHoldings() {
    const url = contextPath + '/ws-stomp';
    const socket = new SockJS(url);
    stompClient = Stomp.over(socket);
    stompClient.debug = null;
    stompClient.connect({}, function (frame) {
        console.log('마이페이지 STOMP 연결 성공: ' + frame);

        // 연결 성공 후 보유 종목들 구독
        if (currentHoldingStocks.length > 0) {
            subscribeHoldingStocks();
        }
    }, function(error) {
        console.error('마이페이지 STOMP 연결 실패: ' + error);
        // 5초 후 재연결 시도
        setTimeout(connectStompForStockHoldings, 5000);
    });
}

// ===========================
// 보유 종목 STOMP 구독
// ===========================
function subscribeHoldingStocks() {
    if (!stompClient || !stompClient.connected) return;
    const stockCodes = currentHoldingStocks.filter(stock => stock.active !== false).map(stock => stock.code);
    // 보유 목록 갱신 시 사라진 종목도 이 연결에서만 해제합니다.
    Object.keys(subscribedStockTopics).forEach(stockCode => {
        if (!stockCodes.includes(stockCode)) {
            subscribedStockTopics[stockCode].unsubscribe();
            delete subscribedStockTopics[stockCode];
        }
    });
    stockCodes.forEach(stockCode => subscribeStockTopicForHolding(stockCode));
}

// ===========================
// 개별 종목 STOMP 토픽 구독
// ===========================
function subscribeStockTopicForHolding(stockCode) {
    if (subscribedStockTopics[stockCode]) {
        console.log('이미 구독 중:', stockCode);
        return;
    }

    const topic = '/topic/kis-trade/present' + stockCode;
    const subscription = stompClient.subscribe(topic, function(message) {
        const tradeData = JSON.parse(message.body);
        console.log('마이페이지 실시간 데이터 수신:', tradeData);

        // 화면 업데이트
        updateStockHoldingRealtime(stockCode, tradeData);
    });

    subscribedStockTopics[stockCode] = subscription;
    console.log('마이페이지 토픽 구독 완료:', topic);
}

// ===========================
// 실시간 현재가로 주식 잔고 카드 업데이트
// ===========================
function updateStockHoldingRealtime(stockCode, tradeData) {
    const holdingStock = currentHoldingStocks.find(stock => stock.code === stockCode);
    if (!holdingStock || holdingStock.active === false) return;
    // 해당 종목의 DOM 요소 찾기
    const stockCard = document.querySelector(`.mypage-stock-card[data-code="${stockCode}"]`);
    if (!stockCard) return;

    // 현재가 업데이트
    const currentPrice = Number(tradeData.stckPrpr);
    if (!Number.isFinite(currentPrice) || currentPrice <= 0) return;
    const currentPriceElement = stockCard.querySelector('.stock-current-price');
    if (currentPriceElement) {
        currentPriceElement.textContent = currentPrice.toLocaleString() + '원';
    }

    // 해당 종목의 보유 정보 찾기

    const shares = holdingStock.shares;
    const buyPrice = holdingStock.buyPrice;

    // 평가금액 = 현재가 × 보유수량
    const totalValue = currentPrice * shares;

    // 수익금액 = 평가금액 - 매수금액
    const profit = totalValue - buyPrice;

    // 수익률 = (수익금액 / 매수금액) × 100
    const profitRate = ((profit / buyPrice) * 100).toFixed(2);

    // currentHoldingStocks 배열의 해당 종목 데이터도 업데이트
    holdingStock.currentPrice = currentPrice;
    holdingStock.totalValue = totalValue;
    holdingStock.profit = profit;
    holdingStock.profitRate = profitRate;

    // 평가금액 업데이트
    const totalValueElement = stockCard.querySelector('.stock-total-value');
    if (totalValueElement) {
        totalValueElement.textContent = totalValue.toLocaleString() + '원';
    }

    // 수익금액 및 수익률 업데이트
    const profitElement = stockCard.querySelector('.mypage-stock-profit');
    if (profitElement) {
        const profitClass = profit > 0 ? 'positive' : profit < 0 ? 'negative' : 'neutral';
        const profitSign = profit > 0 ? '+' : '';

        profitElement.className = `mypage-stock-profit ${profitClass}`;
        profitElement.textContent = `${profitSign}${profit.toLocaleString()}원(${profitSign}${profitRate}%)`;
    }

    updateTotalAsset();
}

// ===========================
// 모든 구독 해제
// ===========================
function unsubscribeAllHoldingStocks() {
    console.log('마이페이지 구독 해제 시작...');

    // 프론트엔드 STOMP 구독 해제
    for (let stockCode in subscribedStockTopics) {
        if (subscribedStockTopics[stockCode]) {
            subscribedStockTopics[stockCode].unsubscribe();
            console.log('토픽 구독 해제:', stockCode);
        }
    }
    subscribedStockTopics = {};
}

// ===========================
// BiasType 매핑
// ===========================
const biasTypeMap = {
    RISK_AVERSION: { title: "위험회피 주의", icon: "⚠️" },
    LOSS_AVERSION: { title: "손실회피 주의", icon: "⚠️" },
    SUNK_COST: { title: "매몰비용오류 경고", icon: "⚠️" },
    FOMO: { title: "FOMO 주의", icon: "⚠️" }
};

// ===========================
// DOM 요소
// ===========================
let stockList, realizedList, executedTable, tradingList;
let tabButtons, subTabButtons, filterButtons;

// ===========================
// 초기화
// ===========================
document.addEventListener('DOMContentLoaded', function() {
    console.log("마이페이지 로드 완료");
    
    // 1. UI 요소 참조 및 기본 이벤트 연결
    initializeElements();
    setupEventListeners(); 
    
    // 2. 첫 화면 데이터 로드 (주식 잔고 → 심리경고 순서)
    loadStockHoldings();
    // loadHistoryData()는 loadStockHoldings() 완료 후 호출됨!
    
    // 탭 기간 조회 초기화
    initializeRealizedDates();
    initializeExcutedDates();
    
    // 3. 심리경고 날짜 초기화 및 이벤트
    initializeHistoryDates();
    setupHistoryEventListeners();
});

// ===========================
// 이벤트 리스너 통합 설정
// ===========================
function setupEventListeners() {
    // 메인 탭 전환 버튼 이벤트
    tabButtons.forEach(button => {
        button.addEventListener('click', function() {
            const target = this.dataset.tab; // 'stock', 'realized', 'executed', 'account'
            console.log("탭 전환:", target);
            
            // 1. 모든 버튼 및 패널 비활성화
            tabButtons.forEach(btn => btn.classList.remove('active'));
            document.querySelectorAll('.mypage-tab-panel').forEach(panel => {
                panel.classList.remove('active');
            });
            
            // 2. 현재 클릭한 탭 활성화
            this.classList.add('active');
            const targetPanel = document.getElementById(`${target}-panel`);
            if (targetPanel) targetPanel.classList.add('active');
            
            // 3. 탭별 데이터 로드
            if (target === 'stock') {
                loadStockHoldings();
            } else if (target === 'realized') {
                loadRealizedProfit(); 
            } else if (target === 'executed') { 
                loadExecutedOrders(); 
            } else if (target === 'account') { 
                loadAccountInfo();
            }
        });
    });

    // 실현손익 날짜 변경 시 자동 조회
    const realizedDates = [
        document.getElementById("realizedStartDate"), 
        document.getElementById("realizedEndDate")
    ];
    realizedDates.forEach(input => {
        if(input) {
            input.addEventListener("change", () => loadRealizedProfit());
        }
    });
    
    
    // 체결내역 전용 리스너
    // 1. 체결내역 서브탭(전체/체결/미체결) 전환 이벤트
    subTabButtons.forEach(button => {
        button.addEventListener('click', function() {
            subTabButtons.forEach(btn => btn.classList.remove('active'));
            this.classList.add('active');
            
            const subtab = this.dataset.subtab;
            renderExecutedOrders(subtab); // 필터링 함수 호출
        });
    });

    // 2. 체결내역 날짜 변경 시 자동 조회
    const executedDates = [
        document.getElementById("executedStartDate"), 
        document.getElementById("executedEndDate")
    ];
    executedDates.forEach(input => {
        if(input) {
            input.addEventListener("change", () => loadExecutedOrders());
        }
    });
    

    // 기존의 나머지 리스너들 (서브탭 등) 유지...
    setupDateFilters(); 
}

function initializeElements() {
    // DOM 요소 가져오기
    stockList = document.getElementById('stockList');
    realizedList = document.getElementById('realizedList');
    executedTable = document.getElementById('executedTable');
    tradingList = document.getElementById('tradingList');
    
    // 버튼 요소
    tabButtons = document.querySelectorAll('.mypage-tab-button');
    subTabButtons = document.querySelectorAll('.mypage-sub-tab-button');
    filterButtons = document.querySelectorAll('.mypage-filter-button');
}



// ===========================
// 기간 선택 필터 설정
// ===========================
function setupDateFilters() {
    // 날짜 입력 필드들
    const dateInputs = document.querySelectorAll('.mypage-date-input');
    const clearButtons = document.querySelectorAll('.mypage-date-clear');
    
    // 오늘 날짜로 초기화
    const today = new Date().toISOString().split('T')[0];
    dateInputs.forEach(input => {
        if (!input.value) {
            input.value = today;
        }
    });
    
    // 클리어 버튼 이벤트
    clearButtons.forEach(button => {
        button.addEventListener('click', function(e) {
            e.preventDefault();
            const targetId = this.getAttribute('data-target');
            const targetInput = document.getElementById(targetId);
            if (targetInput) {
                targetInput.value = '';
            }
        });
    });
    
    // 날짜 변경 이벤트
    dateInputs.forEach(input => {
        input.addEventListener('change', function() {
            console.log(`Date changed: ${this.id} = ${this.value}`);
        });
    });
}

// ===========================
// 탭 전환
// ===========================
function switchTab(tabName) {
    // 모든 탭 버튼 비활성화
    tabButtons.forEach(btn => btn.classList.remove('active'));
    
    // 클릭된 탭 버튼 활성화
    document.querySelector(`[data-tab="${tabName}"]`).classList.add('active');
    
    // 모든 탭 패널 숨기기
    document.querySelectorAll('.mypage-tab-panel').forEach(panel => {
        panel.classList.remove('active');
    });
    
    // 선택된 탭 패널 표시
    const panelId = `${tabName}-panel`;
    document.getElementById(panelId).classList.add('active');
}

function switchSubTab(subtab) {
    // 모든 서브 탭 버튼 비활성화
    subTabButtons.forEach(btn => btn.classList.remove('active'));
    
    // 클릭된 서브 탭 버튼 활성화
    document.querySelector(`[data-subtab="${subtab}"]`).classList.add('active');
    
    // 체결내역 데이터 필터링 및 렌더링
    renderExecutedOrders(subtab);
}

function filterTradingHistory(filter) {
    // 모든 필터 버튼 비활성화
    filterButtons.forEach(btn => btn.classList.remove('active'));
    
    // 클릭된 필터 버튼 활성화
    document.querySelector(`[data-filter="${filter}"]`).classList.add('active');
    
    // 매매내역 데이터 필터링 및 렌더링
    renderTradingHistory(filter);
}

// ===========================
// 초기 데이터 렌더링
// ===========================
function renderInitialData() {
    //renderStockHoldings();
    loadStockHoldings(); // 
    renderRealizedProfits();
    renderExecutedOrders('all');
    renderTradingHistory('all');
}

// DB에서 주식 잔고 데이터를 가져오는 함수 (수정)
function loadStockHoldings() {
    // 1. 계좌 정보 조회 (잔고 가져오기)
    fetch(contextPath + '/mypage/api/account-info')
        .then(res => res.json())
        .then(accountData => {
            // 계좌 잔고 저장
            accountBalance = accountData.balance || 0;
            console.log('계좌 잔고:', accountBalance);

            // 2. 주식 잔고 조회
            return fetch(contextPath + '/mypage/api/stock-holdings');
        })
        .then(res => res.json())
        .then(data => {
            if (!data || data.length === 0) {
                if (stockList) stockList.innerHTML = "<p class='no-data'>보유 중인 주식이 없습니다.</p>";
                // 잔고만 있는 경우에도 총 자산 업데이트
                currentHoldingStocks = [];
                updateTotalAsset();
                
                // 주식이 없어도 심리경고는 로드
                loadHistoryData();
                return;
            }

            // 현재 보유 종목 정보 저장 (code 포함)
            currentHoldingStocks = data;
            console.log('주식 잔고 로드 완료:', currentHoldingStocks.length, '개');

            // 화면 렌더링
            renderStockHoldings(data);

            // 추가: 초기 총 자산 업데이트
            updateTotalAsset();

            // STOMP 연결 및 구독
            if (stompClient && stompClient.connected) {
                subscribeHoldingStocks();
            } else {
                connectStompForStockHoldings();
            }
            
            // 주식 잔고 로드 완료 후 심리경고 로드
            loadHistoryData();
        })
        .catch(err => {
            console.error("주식 잔고 로드 실패:", err);
            if (stockList) stockList.innerHTML = "<p class='no-data'>데이터 로드 실패</p>";
            
            // 에러가 나도 심리경고는 로드
            loadHistoryData();
        });
}

// ===========================
// 총 자산 계산 및 업데이트
// ===========================
function updateTotalAsset() {
    if (currentHoldingStocks.some(stock => stock.totalValue == null || stock.profit == null)) {
        const totalAssetElement = document.getElementById('totalAsset');
        const totalProfitElement = document.getElementById('totalProfit');
        if (totalAssetElement) totalAssetElement.textContent = '평가 불가';
        if (totalProfitElement) {
            totalProfitElement.className = 'mypage-profit-amount neutral';
            totalProfitElement.textContent = '평가 불가';
        }
        return;
    }
    // 1. 보유 주식의 총 평가금액, 총 매수금액, 총 수익 계산
    let totalStockValue = 0;  // 보유 주식 총 평가금액
    let totalBuyPrice = 0;     // 총 매수금액
    let totalProfit = 0;       // 총 수익금액

    currentHoldingStocks.forEach(stock => {
        totalStockValue += (stock.totalValue || 0);
        totalBuyPrice += (stock.buyPrice || 0);
        totalProfit += (stock.profit || 0);
    });

    // 2. 내 자산 총액 = 계좌 잔고 + 보유 주식 총 평가금액
    const totalAsset = accountBalance + totalStockValue;

    // 3. 총 평가손익률 = (총 평가손익 / 총 매수금액) × 100
    const totalProfitRate = totalBuyPrice > 0
        ? ((totalProfit / totalBuyPrice) * 100).toFixed(2)
        : '0.00';

    // 4. DOM 업데이트 - 내 자산 총액
    const totalAssetElement = document.getElementById('totalAsset');
    if (totalAssetElement) {
        totalAssetElement.textContent = totalAsset.toLocaleString() + '원';
    }

    // 5. DOM 업데이트 - 총 평가 손익
    const totalProfitElement = document.getElementById('totalProfit');
    if (totalProfitElement) {
        const profitClass = totalProfit > 0 ? 'positive' : totalProfit < 0 ? 'negative' : 'neutral';
        const profitSign = totalProfit > 0 ? '+' : '';

        // 기존 클래스를 유지하면서 positive/negative만 변경
        totalProfitElement.className = `mypage-profit-amount ${profitClass}`;
        totalProfitElement.textContent = `${profitSign}${totalProfit.toLocaleString()}원(${profitSign}${totalProfitRate}%)`;
    }
}

// ===========================
// 주식잔고 렌더링 
// ===========================
function renderStockHoldings(holdings) { 
    if (!stockList) return;
    
    // 데이터가 없을 때 처리 추가
    if (!holdings || holdings.length === 0) {
        stockList.innerHTML = "<p class='no-data'>보유 중인 주식이 없습니다.</p>";
        return;
    }
    
    // sampleData.stockHoldings 대신 holdings 사용
    stockList.innerHTML = holdings.map(stock => {
        const canValue = stock.currentPrice != null && stock.totalValue != null && stock.profit != null;
        const profitClass = stock.profit > 0 ? 'positive' : stock.profit < 0 ? 'negative' : 'neutral';
        const profitSign = stock.profit > 0 ? '+' : '';
        
        // 상세 페이지 URL 생성
        const detailUrl = `${contextPath}/stock/detail?code=${stock.code}`;
        
        return `
            <div class="mypage-stock-card" data-code="${stock.code}"
                 ${stock.active === false ? '' : `onclick="location.href='${detailUrl}'" style="cursor: pointer;"`}>
                <div class="mypage-stock-header">
                    <div>
                        <div class="mypage-stock-name">${stock.name}${stock.active === false ? ' (비활성)' : ''}</div>
                        <div class="mypage-stock-shares">현금 ${stock.shares}주</div>
                    </div>
                    <div class="mypage-stock-profit ${profitClass}">
                        ${canValue ? `${profitSign}${stock.profit.toLocaleString()}원(${profitSign}${stock.profitRate ? stock.profitRate : '0.00'}%)` : '평가 불가'}
                    </div>
                </div>
                <div class="mypage-stock-details">
                    <div class="mypage-stock-detail-item">
                        <span class="mypage-detail-label">매수 금액</span>
                        <span class="mypage-detail-value">${stock.buyPrice.toLocaleString()}원</span>
                    </div>
                    <div class="mypage-stock-detail-item">
                        <span class="mypage-detail-label">평균단가</span>
                        <span class="mypage-detail-value">${stock.avgBuyPrice.toLocaleString()}원</span>
                    </div>
                    <div class="mypage-stock-detail-item">
                        <span class="mypage-detail-label">평가 금액</span>
                        <span class="mypage-detail-value stock-total-value">${canValue ? stock.totalValue.toLocaleString() + '원' : '평가 불가'}</span>
                    </div>
                    <div class="mypage-stock-detail-item">
                        <span class="mypage-detail-label">현재가</span>
                        <span class="mypage-detail-value stock-current-price">${canValue ? stock.currentPrice.toLocaleString() + '원' : '평가 불가'}</span>
                    </div>
                </div>
            </div>
        `;
    }).join('');
}

// 실현손익 데이터를 불러오는 함수
function loadRealizedProfit() {
    // 1. JSP input에서 날짜 값 가져오기
    const startDate = document.getElementById("realizedStartDate").value;
    const endDate = document.getElementById("realizedEndDate").value;

    console.log("조회 기간:", startDate, "~", endDate);

    // 2. URL에 파라미터 추가
    const url = `${contextPath}/mypage/api/realized-profit?startDate=${startDate}&endDate=${endDate}`;

    fetch(url)
        .then(res => res.json())
        .then(data => {
            renderRealizedProfit(data);
        })
        .catch(err => console.error("실현손익 조회 실패:", err));
}

// ===========================
// 실현손익 렌더링
// ===========================
function renderRealizedProfit(data) {
    const listContainer = document.querySelector("#realizedList");
    if (!listContainer) return;

    if (!data || data.length === 0) {
        listContainer.innerHTML = "<p class='no-data'>해당 기간 내 실현손익 내역이 없습니다.</p>";
        return;
    }

    // 1. 데이터 월별 그룹화 처리
    const groupedData = {};

    data.forEach(item => {
        let dateObj;
        if (Array.isArray(item.tradeDate)) {
            // [2026, 1, 22, ...] 형태 처리
            dateObj = new Date(item.tradeDate[0], item.tradeDate[1] - 1, item.tradeDate[2]);
        } else {
            dateObj = new Date(item.tradeDate);
        }

        const monthKey = `${dateObj.getFullYear()}년 ${dateObj.getMonth() + 1}월`;
        
        if (!groupedData[monthKey]) {
            groupedData[monthKey] = {
                month: monthKey,
                totalProfit: 0,
                items: []
            };
        }
        
        groupedData[monthKey].items.push({
            ...item,
            formattedDate: `${dateObj.getMonth() + 1}월 ${dateObj.getDate()}일`
        });
        groupedData[monthKey].totalProfit += item.profit;
    });

    // 2. HTML 생성 (기존 UI 디자인 적용)
    let html = "";
    
    // 월별로 반복
    Object.values(groupedData).sort((a, b) => b.month.localeCompare(a.month)).forEach(group => {
        const isMonthProfitable = group.totalProfit > 0;
        const monthClass = isMonthProfitable ? 'positive' : 'negative';
        const monthSign = isMonthProfitable ? '+' : '';

        html += `
            <div class="mypage-realized-item">
                <div class="mypage-realized-header">
                    <span class="mypage-realized-date">${group.month} </span>
                    <span class="mypage-realized-amount ${monthClass}">
                        ${monthSign}${formatNumber(group.totalProfit)}원
                    </span>
                </div>
                
                <div class="mypage-realized-detail">
                    ${group.items.map(item => {
                        const isItemProfitable = item.profit >= 0;
                        const itemClass = isItemProfitable ? 'positive' : 'negative';
                        const itemSign = isItemProfitable ? '+' : '';
                        
                        
                        return `
                            <div style="margin-top: 15px; padding-top: 15px; border-top: 1px solid var(--border-color);">
                                <div class="mypage-realized-stock" style="font-weight: bold; font-size: 15px; margin-bottom: 5px;">
                                    ${item.name}
                                </div>
                                <div class="mypage-realized-info" style="display: flex; justify-content: space-between; font-size: 13px;">
                                    <span>${item.formattedDate} · ${item.shares}주 매도</span>
                                    <span class="${itemClass}" style="font-weight: bold;">
                                        ${itemSign}${formatNumber(item.profit)}원 (${itemSign}${Number(item.profitRate).toFixed(2)}%)
                                    </span>
                                </div>
                                <div style="display: flex; gap: 10px; font-size: 11px; color: var(--text-secondary); margin-top: 4px;">
                                    <span>매수가: ${formatNumber(item.buyPrice)}원</span>
                                    <span>매도가: ${formatNumber(item.sellPrice)}원</span>
                                </div>
                            </div>
                        `;
                    }).join('')}
                </div>
            </div>
        `;
    });

    listContainer.innerHTML = html;
}


/**
 * 서버에서 체결내역 데이터를 가져옴
 */
function loadExecutedOrders() {
    const startDate = document.getElementById("executedStartDate").value;
    const endDate = document.getElementById("executedEndDate").value;
    
    const activeSubTab = document.querySelector('.mypage-sub-tab-button.active')?.dataset.subtab || 'all';

    fetch(`${contextPath}/mypage/api/executed-orders?startDate=${startDate}&endDate=${endDate}`)
        .then(res => res.json())
        .then(data => {
            window.executedOrderData = data; // 전역 변수에 데이터 저장
            renderExecutedOrders(activeSubTab);
        })
        .catch(err => {
            console.error("체결내역 로드 실패:", err);
            const tbody = document.querySelector('#executedTable tbody');
            if(tbody) tbody.innerHTML = '<tr><td colspan="6" class="no-data">데이터를 불러오는 중 오류가 발생했습니다.</td></tr>';
        });
}

/**
 * 데이터를 기반으로 이미지와 같은 2줄 구조 테이블 렌더링
 */
function renderExecutedOrders(filter) {
    const tbody = document.querySelector('#executedTable tbody');
    if (!tbody || !window.executedOrderData) return;

    // 체결 이력은 주문의 현재 상태와 무관하게 기체결 수량을 기준으로 표시합니다.
    let filtered = window.executedOrderData;
    if (filter === 'executed') {
        filtered = window.executedOrderData.filter(o => o.executedQty > 0);
    } else if (filter === 'unexecuted') {
        filtered = window.executedOrderData.filter(o => o.status === "WAIT" || o.status === "PARTIAL");
    }

    if (filtered.length === 0) {
        tbody.innerHTML = '<tr><td colspan="6" class="no-data">해당 기간 또는 조건에 일치하는 체결내역이 없습니다.</td></tr>';
        return;
    }

    // 2. 2줄 구조 HTML 생성
    // map 함수의 두 번째 인자인 i(인덱스)를 사용하여 짝수 세트를 판별합니다.
    tbody.innerHTML = filtered.map((o, i) => {
        const isCancelled = o.status === 'CANCEL';
        const isBuy = o.type === 'BUY' || o.type === '매수';
        const typeClass = isBuy ? 'mypage-buy-badge' : 'mypage-sell-badge';
        const typeText = isBuy ? '매수' : '매도';
        
        //  홀수인덱스인 경우 'bg-light' 클래스를 추가하여 배경색을 줍니다.
        const rowBgClass = i % 2 === 1 ? 'bg-light' : '';
        
        return `
            <tr class="mypage-main-row ${rowBgClass}">
                <td class="mypage-stock-name-cell">${o.stockName}</td>
                <td>${formatNumber(o.orderQty)}</td>
                <td>${formatNumber(o.executedQty)}</td>
                <td>${isCancelled ? '취소' : formatNumber(o.unexecutedQty)}</td>
                <td>${isCancelled ? '-' : formatNumber(o.unexecutedAmount)}</td>
                <td class="time-col">${o.orderTime}</td>
            </tr>
            <tr class="mypage-sub-row ${rowBgClass}" style="border-bottom: 1px solid #eee;">
                <td><span class="${typeClass}">${typeText}</span></td>
                <td>${formatNumber(o.orderPrice)}</td>
                <td>${o.executedPrice ? formatNumber(Math.floor(o.executedPrice)) : '-'}</td>
                <td>${formatNumber(o.orderAmount)}</td>
                <td class="stock-code">${o.stockCode}</td>
                <td class="time-col">${o.executedTime || '-'}</td>
            </tr>
        `;
    }).join('');
}

/**
 * 계좌 정보 데이터를 서버에서 가져와 화면에 렌더링
 */
function loadAccountInfo() {
    fetch(`${contextPath}/mypage/api/account-info`)
        .then(response => {
            if (!response.ok) throw new Error("계좌 정보 로드 실패");
            return response.json();
        })
        .then(data => {
            if (data) {
                document.getElementById("userNickname").innerText = data.nickname;
                document.getElementById("accNumber").innerText = data.accountNumber || "정보 없음";
                document.getElementById("accBalance").innerText = formatNumber(data.balance || 0);
                
                // 날짜 처리 로직 개선
                const createDateEl = document.getElementById("accCreateDate");
                if (data.createdAt) {
                    // 서버 데이터가 [2026, 1, 22] 배열 형식이거나 문자열인 경우 모두 대응
                    const date = Array.isArray(data.createdAt) 
                        ? new Date(data.createdAt[0], data.createdAt[1] - 1, data.createdAt[2])
                        : new Date(data.createdAt);

                    if (!isNaN(date.getTime())) {
                        createDateEl.innerText = date.toLocaleDateString('ko-KR');
                    } else {
                        createDateEl.innerText = data.createdAt; // 변환 실패 시 원본 표시
                    }
                }
            }
        })
        .catch(error => console.error("Error:", error));
}


/**
 * 실현손익탭 기간 날짜 필드 초기화 (한달전 ~ 오늘 날짜로)
 */
function initializeRealizedDates() {
    const now = new Date(); 
    const today = now.toISOString().split('T')[0];
    
    const startDateInput = document.getElementById("executedStartDate");
    const endDateInput = document.getElementById("executedEndDate");
    
    if (startDateInput) {
        startDateInput.value = today;
        console.log("기간 시작 날짜 초기화:", today);
    }
    
    if (endDateInput) {
        endDateInput.value = today;
        console.log("기간 종료 날짜 초기화:", today);
    }
}

/**
 * 체결내역탭 기간 날짜 필드 초기화 (한달전 ~ 오늘 날짜로)
 */
 function initializeExcutedDates() {
    const now = new Date(); 
    const today = now.toISOString().split('T')[0];
    
    // 한 달 전 날짜 계산
    const lastMonth = new Date(now.setMonth(now.getMonth() - 1)).toISOString().split('T')[0];
    
    const startDateInput = document.getElementById("realizedStartDate");
    const endDateInput = document.getElementById("realizedEndDate");
    
    if (startDateInput) {
        // 오늘(today) 대신 한 달 전(lastMonth) 값을 할당
        startDateInput.value = lastMonth;
        console.log("기간 시작 날짜 초기화:", lastMonth);
    }
    
    if (endDateInput) {
        endDateInput.value = today;
        console.log("기간 종료 날짜 초기화:", today);
    }
}

/**
 * 심리경고 날짜 필드 초기화 (최근 7일 ~ 오늘)
 */
function initializeHistoryDates() {
    const today = new Date();
    const sevenDaysAgo = new Date();
    sevenDaysAgo.setDate(today.getDate() - 7);
    
    const todayStr = today.toISOString().split('T')[0];
    const sevenDaysAgoStr = sevenDaysAgo.toISOString().split('T')[0];
    
    const startDateInput = document.getElementById("alertStartDate");
    const endDateInput = document.getElementById("alertEndDate");
    
    if (startDateInput) {
        startDateInput.value = sevenDaysAgoStr;  // 7일 전
        console.log("심리경고 시작 날짜 초기화:", sevenDaysAgoStr);
    }
    
    if (endDateInput) {
        endDateInput.value = todayStr;  // 오늘
        console.log("심리경고 종료 날짜 초기화:", todayStr);
    }
}

/**
 * 심리경고 데이터 로드
 */
function loadHistoryData() {
    const alertList = document.querySelector(".mypage-alert-list");
    if (!alertList) {
        console.error("심리경고 목록 요소를 찾을 수 없습니다.");
        return;
    }

    const startDateInput = document.getElementById("alertStartDate");
    const endDateInput = document.getElementById("alertEndDate");
    
    if (!startDateInput || !endDateInput) {
        console.error("날짜 입력 필드를 찾을 수 없습니다.");
        return;
    }

    const startDate = startDateInput.value;
    const endDate = endDateInput.value;

    console.log("심리경고 조회 - startDate:", startDate, "endDate:", endDate);

    let url = "/antmillion/api/history/list";

    if (startDate && endDate) {
        url += `?startDate=${startDate}&endDate=${endDate}`;
    }

    console.log("요청 URL:", url);

    fetch(url)
        .then(res => {
            if (!res.ok) {
                throw new Error(`HTTP error! status: ${res.status}`);
            }
            return res.json();
        })
        .then(data => {
            console.log("받은 데이터:", data);
            
            if (!data || data.length === 0) {
                alertList.innerHTML = "<p class='no-data'>해당 기간의 심리경고가 없습니다.</p>";
                return;
            }

            alertList.innerHTML = "";
                      
            data.forEach(history => {
                const date = new Date(history.time);
                const formattedDate = 
                    date.getFullYear() + "/" +
                    String(date.getMonth() + 1).padStart(2, '0') + "/" +
                    String(date.getDate()).padStart(2, '0') + " " +
                    String(date.getHours()).padStart(2, '0') + ":" +
                    String(date.getMinutes()).padStart(2, '0') + ":" +
                    String(date.getSeconds()).padStart(2, '0');

                const biasInfo = biasTypeMap[history.biasType] || {
                    title: "심리 편향 경고",
                    icon: "⚠️"
                };

                const transactionClass = history.transactionType === '매수' ? 'buy' : 'sell';
                const transactionText = history.transactionType || '-';
                
                const stockImgSrc = `/antmillion/resources/images/stock/${history.stockCode}.png`;
                
                // trim() 및 대소문자 통일하여 비교
                const historyStockCode = (history.stockCode || '').toString().trim();
                
                console.log(`[심리경고] 종목: ${history.stockName} (${historyStockCode}) 확인 중...`);
                
                const holdingStock = currentHoldingStocks.find(stock => {
                    const holdingCode = (stock.code || '').toString().trim();
                    const match = holdingCode === historyStockCode;
                    
                    if (!match) {
                        console.log(`  - 비교: "${holdingCode}" !== "${historyStockCode}"`);
                    } else {
                        console.log(`   보유 종목 발견! ${stock.shares}주`);
                    }
                    
                    return match;
                });
                
                const holdingInfo = holdingStock 
                    ? `${holdingStock.shares}주 보유`
                    : `보유 주식이 없습니다`;

                const html = `
                    <div class="mypage-warning-container" data-stock-code="${history.stockCode}" data-bias-type="${history.biasType}" data-time="${history.time}" onclick="scrollToStockCard('${history.stockCode}')">
                        <div class="mypage-warning-header">
                            <span class="warning-icon">⚠️</span>
                            <span class="warning-title">${biasInfo.title}</span>
                            <span class="info-icon" data-bias-type="${history.biasType}">?</span>
                        </div>
                        
                        <div style="text-align: right; color: #999; font-size: 13px; margin-bottom: 12px;">
                            ${formattedDate}
                        </div>

                        <div style="display: flex; align-items: center; gap: 12px; padding: 15px; background: #f9f9f9; border-radius: 8px; margin-bottom: 15px;">
                            <img src="${stockImgSrc}" 
                                 alt="${history.stockName}" 
                                 style="width: 40px; height: 40px; border-radius: 8px; object-fit: contain; background: #fff;"
                                 onerror="this.src='/antmillion/resources/images/antmillion-logo.png'">
                            <div style="flex: 1;">
                                <div style="display: flex; align-items: center; gap: 8px; margin-bottom: 4px;">
                                    <span style="font-weight: bold; font-size: 16px; color: #333;">${history.stockName || "-"}</span>
                                    <span style="color: #999; font-size: 13px;">${history.stockCode}</span>
                                </div>
                                <div style="color: #666; font-size: 13px;">
                                    ${holdingInfo}
                                </div>
                            </div>
                        </div>

                        <p class="mypage-alert-description">
                            ${history.messageDetail || ""}
                        </p>
                    </div>
                `;
                
                alertList.insertAdjacentHTML("beforeend", html);
            });
            
            console.log("렌더링 완료");
            
            // 툴팁 설정
            setupBiasTooltips();
        })
        .catch(error => {
            console.error("심리경고 데이터 로드 실패:", error);
            alertList.innerHTML = "<p class='no-data'>심리경고 데이터를 불러오지 못했습니다.</p>";
        });
}

/**
 * 편향 설명 툴팁 설정 (HTML 지원 버전)
 */
function setupBiasTooltips() {
    const biasDescriptions = {
        'RISK_AVERSION': '<strong>위험회피란?</strong>투자자들이 이익 구간에서 불확실한 더 큰 이익보다 확실한 작은 이익을 선택하는 경향입니다.',
        'LOSS_AVERSION': '<strong>손실회피란?</strong>투자자들이 이익의 기쁨보다 손실의 고통을 더 크게 느껴 손실을 확정하지 않고 손실 구간에서 과도한 위험을 감수하는 경향입니다.',
        'SUNK_COST': '<strong>매몰비용오류란?</strong>이미 지불하여 되찾을 수 없는 비용(시간·돈·노력 등)에 미련을 두어, 미래의 가치보다 과거의 투자분에 집착하여 비합리적인 의사결리는 현상입니다.',
        'FOMO': '<strong>FOMO(포모)란?</strong>나만 기회를 놓치고 소외될 것 같은 공포(Fear Of Missing Out)를 뜻합니다. 급등하는 차트를 보며 이성적 판단 없이 추격 매수하는 심리적 상태입니다.'
    };

    const infoIcons = document.querySelectorAll('.info-icon');
    
    // 1. 공통 툴팁 엘리먼트 (딱 하나만 생성하여 재사용)
    let tooltipEl = document.getElementById('js-custom-tooltip');
    if (!tooltipEl) {
        tooltipEl = document.createElement('div');
        tooltipEl.id = 'js-custom-tooltip';
        tooltipEl.className = 'js-custom-tooltip'; // CSS에서 스타일링할 클래스
        document.body.appendChild(tooltipEl);
    }

    infoIcons.forEach(icon => {
        const biasType = icon.getAttribute('data-bias-type');
        const description = biasDescriptions[biasType] || '심리 편향에 대한 설명입니다.';
        
        // 2. 마우스 이벤트 리스너 정의 (기존 리스너가 있다면 덮어씌움)
        icon.onmouseenter = function(e) {
            tooltipEl.innerHTML = description; // HTML 해석 (볼드 적용)
            tooltipEl.style.display = 'block';

            // 위치 계산 (아이콘 위쪽 중앙)
            const rect = icon.getBoundingClientRect();
            const scrollY = window.pageYOffset;
            const scrollX = window.pageXOffset;

            tooltipEl.style.top = (rect.top + scrollY - tooltipEl.offsetHeight - 12) + 'px';
            tooltipEl.style.left = (rect.left + scrollX + (rect.width / 2) - (tooltipEl.offsetWidth / 2)) + 'px';
            
            tooltipEl.classList.add('show');
        };

        icon.onmouseleave = function() {
            tooltipEl.classList.remove('show');
            tooltipEl.style.display = 'none';
        };
    });
}

/**
 * 심리경고 이벤트 리스너 설정
 */
function setupHistoryEventListeners() {
    const startDateInput = document.getElementById("alertStartDate");
    const endDateInput = document.getElementById("alertEndDate");
    
    if (startDateInput) {
        startDateInput.addEventListener("change", function() {
            console.log("심리경고 시작 날짜 변경:", this.value);
            loadHistoryData();
        });
    }
    
    if (endDateInput) {
        endDateInput.addEventListener("change", function() {
            console.log("심리경고 종료 날짜 변경:", this.value);
            loadHistoryData();
        });
    }
}

// ===========================
// 유틸리티 함수
// ===========================
function formatNumber(num) {
    return num.toLocaleString('ko-KR');
}

function formatCurrency(num) {
    return `${formatNumber(num)}원`;
}

function formatPercent(num) {
    const sign = num >= 0 ? '+' : '';
    return `${sign}${num}%`;
}
// ===== 알림에서 이동 시 스크롤 + 강조 =====

function getUrlParams() {
    const params = new URLSearchParams(window.location.search);
    return {
        stock: params.get('stock'),
        bias: params.get('bias'),
        time: params.get('time')
    };
}

function scrollToAlert(stockCode, biasType, time) {
    console.log('[스크롤] 대상 찾기:', stockCode, biasType, time);
    
    const alertDate = new Date(time);
    const alertDateStr = alertDate.toISOString().split('T')[0];
    const today = new Date().toISOString().split('T')[0];
    
    console.log('[스크롤] 알림 날짜:', alertDateStr, '/ 오늘:', today);
    
    const startDateInput = document.getElementById("alertStartDate");
    const endDateInput = document.getElementById("alertEndDate");
    
    if (startDateInput && endDateInput) {
        if (alertDateStr < today) {
            console.log('[스크롤] 날짜 범위 확장:', alertDateStr, '~', today);
            startDateInput.value = alertDateStr;
            endDateInput.value = today;
            
            loadHistoryData();
            
            setTimeout(() => {
                scrollToAlertAfterLoad(stockCode, biasType, time);
            }, 800);
            
            return;
        }
    }
    
    // 오늘 날짜도 대기 시간 추가 (데이터 로딩 확인)
    console.log('[스크롤] 오늘 알림 - 데이터 로딩 대기');
    setTimeout(() => {
        scrollToAlertAfterLoad(stockCode, biasType, time);
    }, 500);
}

function scrollToAlertAfterLoad(stockCode, biasType, time) {
    console.log('[스크롤] 경고 찾기 시작:', stockCode, biasType);
    
    const alerts = document.querySelectorAll('.mypage-warning-container');
    
    if (alerts.length === 0) {
        console.log('[스크롤] ⚠️ 경고 카드를 찾을 수 없습니다');
        return;
    }
    
    console.log('[스크롤] 총', alerts.length, '개 경고 검사 중...');
    
    alerts.forEach((alert, index) => {
        const dataStockCode = alert.getAttribute('data-stock-code');
        const dataBiasType = alert.getAttribute('data-bias-type');
        const dataTime = alert.getAttribute('data-time');
        
        const targetTime = new Date(time).getTime();
        const alertTime = parseInt(dataTime);
        
        if (dataStockCode === stockCode && 
            dataBiasType === biasType && 
            targetTime === alertTime) {
            
            console.log('[스크롤] 정확한 대상 발견! (index:', index, ')');
            
            setTimeout(() => {
                // 기존 심리경고 하이라이트 모두 제거
                document.querySelectorAll('.mypage-warning-container.highlight').forEach(card => {
                    card.classList.remove('highlight');
                });
                
                alert.scrollIntoView({
                    behavior: 'smooth',
                    block: 'center'
                });
                
                // 심리경고 하이라이트 (계속 유지)
                alert.classList.add('highlight');
                console.log('[스크롤] 심리경고 하이라이트 적용 (계속 유지)');
                
                // 주식잔고도 동시에 하이라이트!
                scrollToStockCard(stockCode);
                
                window.history.replaceState({}, document.title, '/antmillion/mypage');
                console.log('[스크롤] URL 파라미터 제거 완료');
                
            }, 500);
        }
    });
}

window.addEventListener('load', function() {
    const params = getUrlParams();
    
    if (params.stock && params.bias && params.time) {
        console.log('[알림 이동] 파라미터 감지:', params);
        
        setTimeout(() => {
            scrollToAlert(params.stock, params.bias, params.time);
        }, 500);
    }
});

// ===========================
// 심리경고 → 주식잔고 스크롤 기능
// ===========================
function scrollToStockCard(stockCode) {
    console.log('[심리경고 → 주식잔고] 이동 시작:', stockCode);
    
    const stockTab = document.querySelector('[data-tab="stock"]');
    if (stockTab && !stockTab.classList.contains('active')) {
        stockTab.click();
        console.log('[심리경고 → 주식잔고] 주식잔고 탭 활성화');
    }
    
    setTimeout(() => {
        const stockCard = document.querySelector(`.mypage-stock-card[data-code="${stockCode}"]`);
        
        if (stockCard) {
            console.log('[심리경고 → 주식잔고] 종목 카드 발견!', stockCode);
            
            // 기존 하이라이트 모두 제거
            document.querySelectorAll('.mypage-stock-card.stock-highlight').forEach(card => {
                card.classList.remove('stock-highlight');
            });
            
            // 스크롤
            stockCard.scrollIntoView({
                behavior: 'smooth',
                block: 'center'
            });
            
            // 하이라이트 추가 (계속 유지, 3초 제거 없음)
            stockCard.classList.add('stock-highlight');
            console.log('[심리경고 → 주식잔고] 하이라이트 적용 (계속 유지)');
            
        } else {
            console.log('[심리경고 → 주식잔고] ⚠️ 해당 종목 미보유:', stockCode);
            alert('해당 종목을 보유하고 있지 않습니다.');
        }
    }, 300);
}


// ===========================
// 페이지 떠날 때 구독 해제
// ===========================
window.addEventListener('beforeunload', function(e) {
    unsubscribeAllHoldingStocks();

    if (stompClient !== null && stompClient.connected) {
        stompClient.disconnect(function() {
            console.log('마이페이지 STOMP 연결 종료');
        });
    }
});

window.addEventListener('pagehide', function(e) {
    unsubscribeAllHoldingStocks();
});
