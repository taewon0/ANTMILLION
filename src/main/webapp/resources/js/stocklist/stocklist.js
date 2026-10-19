/* ===========================
   종목 리스트 페이지 JavaScript
   =========================== */

let currentTab = 'all'; // 'all' 또는 'favorite'
let currentPage = 1;
const itemsPerPage = 10;
let totalPages = 1;

// 관심종목 페이징 변수 추가
let favoritePage = 1;
let favoriteTotalPages = 1;

// STOMP 관련 변수
let stompClient = null;
let subscribedTopics = {}; // 구독한 토픽 저장 {stockCode: subscription}
let currentStockCodes = []; // 현재 화면에 표시된 종목 코드들
let stompConnected = false; // STOMP 연결 상태 플래그 추가
let pendingSubscription = false; // 대기 중인 구독 요청 플래그

// =====================================================
// 로그인 상태 체크 + "로그인이 필요합니다" 모달
// - 스타일은 stocklist.css에서 처리
// =====================================================
function isLoggedIn() {
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

        // 닫기(백드롭/버튼)
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
    if (!isLoggedIn()) {
        showLoginRequiredModal();
        return false;
    }
    return true;
}

// 관심종목 코드 목록 가져오기(비로그인이면 빈 배열)
function getInterestCodesPromise() {
    if (!isLoggedIn()) return Promise.resolve([]);
    return fetch(`${contextPath}/api/interest/list`)
        .then(res => res.ok ? res.json() : [])
        .catch(() => []);
}



// ========== STOMP 연결 (Promise 기반으로 변경) ==========
function connectStomp() {
    return new Promise((resolve, reject) => {
        const url = contextPath + '/ws-stomp';
        const socket = new SockJS(url);
        stompClient = Stomp.over(socket);

        // STOMP 디버그 로그 끄기
        stompClient.debug = null;

        stompClient.connect({}, function (frame) {
            console.log('STOMP 연결 성공: ' + frame);
            stompConnected = true;

            // 대기 중인 구독이 있다면 즉시 처리
            if (pendingSubscription && currentStockCodes.length > 0) {
                console.log('대기 중이던 구독 처리:', currentStockCodes.length + '개 종목');
                pendingSubscription = false;
                subscribeCurrentStocks();
            }

            resolve(frame);
        }, function(error) {
            console.error('STOMP 연결 실패: ' + error);
            stompConnected = false;

            // 5초 후 재연결 시도
            setTimeout(() => {
                connectStomp().then(() => {
                    // 재연결 성공 시 현재 종목 재구독
                    if (currentStockCodes.length > 0) {
                        subscribeCurrentStocks();
                    }
                });
            }, 5000);

            reject(error);
        });
    });
}

// ========== 현재 화면 종목들 구독 ==========
function subscribeCurrentStocks() {
    if (!stompClient || !stompConnected) {
        pendingSubscription = true;
        return;
    }
    // STOMP 해제/구독 이벤트가 이 연결의 KIS 참조도 함께 정리합니다.
    unsubscribeFrontendOnly();
    if (currentStockCodes.length === 0) return;
    if (isMockMode) return enableMockMode();
    currentStockCodes.forEach(stockCode => subscribeStockTopic(stockCode));
}

// ========== 이 연결의 종목 구독 해제 ==========
function unsubscribeFrontendOnly() {
    console.log('프론트엔드 구독 해제 시작...');

    for (let stockCode in subscribedTopics) {
        if (subscribedTopics[stockCode]) {
            subscribedTopics[stockCode].unsubscribe();
            console.log('토픽 구독 해제:', stockCode);
        }
    }
    subscribedTopics = {};
}

// ========== 개별 종목 STOMP 토픽 구독 ==========
function subscribeStockTopic(stockCode) {
    if (subscribedTopics[stockCode]) {
        console.log('이미 구독 중:', stockCode);
        return;
    }

    const topic = '/topic/kis-trade/present' + stockCode;
    const subscription = stompClient.subscribe(topic, function(message) {
        const tradeData = JSON.parse(message.body);
        // console.log('실시간 데이터 수신:', tradeData);

        // 화면 업데이트
        updateStockRealtimePrice(stockCode, tradeData);
    });

    subscribedTopics[stockCode] = subscription;
    console.log('토픽 구독 완료:', topic);
}

// ========== 실시간 가격 화면 업데이트 ==========
function updateStockRealtimePrice(stockCode, tradeData) {
    const stockItem = document.querySelector(`.stocklist-item[data-code="${stockCode}"]`);
    if (!stockItem) return;

    // 현재가 업데이트
    const priceElement = stockItem.querySelector('.stocklist-price');
    if (priceElement) {
        const price = Number(tradeData.stckPrpr).toLocaleString('ko-KR') + '원';
        priceElement.textContent = price;
    }

    // 등락률 업데이트
    const changeElement = stockItem.querySelector('.stocklist-change');
    if (changeElement) {
        // 1,2: 상승(+), 3: 보합(0), 4,5: 하락(-)
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

        // 색상 변경
        changeElement.classList.remove('positive', 'negative');
        if (signClass) {
            changeElement.classList.add(signClass);
        }
    }

    // 거래 비율 업데이트
    if (tradeData.shnuRate) {
        const buyRate = parseFloat(tradeData.shnuRate) * 100;
        const sellRate = 100 - buyRate;

        const buyBar = stockItem.querySelector('.stocklist-sentiment-buy');
        const sellBar = stockItem.querySelector('.stocklist-sentiment-sell');
        const buyLabel = stockItem.querySelector('.stocklist-sentiment-buy-label');
        const sellLabel = stockItem.querySelector('.stocklist-sentiment-sell-label');

        if (buyBar && sellBar) {
            buyBar.classList.remove('stocklist-sentiment-inactive');
            sellBar.classList.remove('stocklist-sentiment-inactive');

            buyBar.style.width = buyRate + '%';
            sellBar.style.width = sellRate + '%';
        }

        if (buyLabel && sellLabel) {
            buyLabel.textContent = Math.round(buyRate);
            sellLabel.textContent = Math.round(sellRate);
        }
    }
}

// ========== 이 연결의 모든 구독 해제 ==========
function unsubscribeAllStocks() {
    unsubscribeFrontendOnly();
}

// ========== 종목 아이템 HTML 생성 함수 ==========
function createStockItemHTML(stock, index, isFavorite) {
    const favoriteIcon = isFavorite ? '♥' : '♡';
    const favoriteClass = isFavorite ? 'active' : '';

    // 현재가 포맷팅
    const currentPrice = Number(stock.stck_prpr || 0).toLocaleString('ko-KR') + '원';

    // 등락률 계산
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

    const imgUrl = contextPath + '/resources/images/stock/' + stock.mksc_shrn_iscd + '.png';

    return `
        <div class="stocklist-item" data-code="${stock.mksc_shrn_iscd}">
            <div class="stocklist-favorite">
                <span class="stocklist-rank">${index + 1}</span>
                <button class="stocklist-favorite-btn ${favoriteClass}" 
                        data-code="${stock.mksc_shrn_iscd}">${favoriteIcon}</button>
            </div>
            <div class="stocklist-info">
                <div class="stocklist-logo">
                    <img src="${imgUrl}" alt="${stock.hts_kor_isnm}" 
                        onerror="this.src='${contextPath}/resources/images/antmillion-logo.png'">
                </div>
                <div class="main-signal-lamp" id="signal-${stock.mksc_shrn_iscd}"></div>
                <span class="stocklist-name">${stock.hts_kor_isnm}</span>
            </div>
            <div class="stocklist-price">${currentPrice}</div>
            <div class="stocklist-change ${changeClass}">${changeText}</div>
            <div class="stocklist-sentiment">
                <div class="stocklist-sentiment-bar">
                    <div class="stocklist-sentiment-buy stocklist-sentiment-inactive" style="width: 50%;"></div>
                    <div class="stocklist-sentiment-sell stocklist-sentiment-inactive" style="width: 50%;"></div>
                </div>
                <div class="stocklist-sentiment-labels">
                    <span class="stocklist-sentiment-buy-label"></span>
                    <span class="stocklist-sentiment-sell-label"></span>
                </div>
            </div>
        </div>
    `;
}

// ========== 전체 종목 렌더링 (페이징) ==========
function renderAllStocks() {
    const container = document.getElementById('stocklist-Container');

    fetch(`${contextPath}/api/kis/volumeRank/paged?page=${currentPage}&size=${itemsPerPage}`)
        .then(res => res.json())
        .then(async responseData => {
            const stocks = responseData.data;
            totalPages = responseData.totalPages;
            currentStockCodes = stocks.map(stock => stock.mksc_shrn_iscd);

            // 관심종목 목록 가져오기 (비로그인이면 빈 배열)
            return getInterestCodesPromise().then(interestCodes => {
                const startIndex = (currentPage - 1) * itemsPerPage;
                const html = stocks.map((stock, index) => {
                    const isFavorite = interestCodes.includes(stock.mksc_shrn_iscd);
                    return createStockItemHTML(stock, startIndex + index, isFavorite);
                }).join('');

                // 1. 화면 먼저 렌더링
                container.innerHTML = html;

                // 2. UI 이벤트 바인딩
                renderPagination();
                attachFavoriteListeners();
                attachStockItemListeners();

                // 3. STOMP 구독 실행
                if (stompConnected) {
                    console.log('[System] 실시간 구독 시작');
                    subscribeCurrentStocks();
                } else {
                    console.log('[System] STOMP 연결 대기 중...');
                    pendingSubscription = true;
                }

                // 4. 신호등 업데이트는 시차를 두고 실행
                setTimeout(() => {
                    console.log('[System] 신호등 상태 업데이트 시작');
                    updateAllTrafficSignals();
                }, 500);
            });
        })
        .catch(error => {
            console.error('종목 데이터 로드 실패:', error);
            container.innerHTML = '<p>데이터를 불러오는 중 오류가 발생했습니다.</p>';
        });
}

// ========== 관심종목 렌더링 ==========
function renderFavoriteStocks() {
    const container = document.getElementById('stocklist-Container');

    // 비로그인 상태면 관심종목 탭 접근 불가
    if (!ensureLoginOrModal()) {
        return;
    }

    // 페이징된 관심종목 상세 정보 API 호출

    fetch(`${contextPath}/api/interest/details/paged?page=${favoritePage}&size=${itemsPerPage}`)
        .then(res => res.json())
        .then(async responseData => { // async 추가 (내부 await 사용을 위해)

            // 1. 관심종목이 없는 경우 예외 처리
            if (responseData.totalItems === 0) {
                container.innerHTML = '<p style="text-align: center; padding: 40px; color: #9CA3AF;">조회 가능한 관심종목이 없습니다.</p>';
                document.getElementById('pagination-container')?.remove();
                currentStockCodes = [];
                unsubscribeAllStocks();
                return;
            }

            const stocks = responseData.data;
            favoriteTotalPages = responseData.totalPages;

            // 2. 페이지 범위 초과 시 자동 조정
            if (favoritePage > favoriteTotalPages) {
                favoritePage = favoriteTotalPages;
                renderStocks();
                return;
            }

            // 3. 현재 화면의 종목 코드들 저장
            currentStockCodes = stocks.map(stock => stock.stockCode);

            // 4. HTML 생성 및 즉시 렌더링
            const startIndex = (favoritePage - 1) * itemsPerPage;
            const html = stocks.map((stock, index) => {
                return createFavoriteStockItemHTML(stock, startIndex + index);
            }).join('');

            container.innerHTML = html;

            // 5. UI 부가 기능 실행 (페이지네이션 및 리스너)
            renderFavoritePagination();
            attachFavoriteListeners();
            attachStockItemListeners();

            // 6. STOMP 구독 실행
            if (stompConnected) {
                console.log('[관심종목] STOMP 구독 시작');
                subscribeCurrentStocks();
            } else {
                console.log('[관심종목] STOMP 연결 대기 중...');
                pendingSubscription = true;
            }

            // 7. 신호등 업데이트 - 구독 후 시차를 두고 순차 호출
            setTimeout(() => {
                console.log('[관심종목] 신호등 상태 갱신 시작');
                updateAllTrafficSignals();
            }, 300);

        })
        .catch(error => {
            console.error('관심종목 데이터 로드 실패:', error);
            container.innerHTML = '<p style="text-align: center; padding: 20px;">관심종목 데이터를 불러오는데 실패했습니다.</p>';
        });
}

// ========== 관심종목용 HTML 생성 함수 ==========
function createFavoriteStockItemHTML(stock, index) {
    // 현재가 포맷팅
    const currentPrice = Number(stock.stck_prpr || 0).toLocaleString('ko-KR') + '원';
    console.log(stock.stockName);
    console.log(stock);
    // 등락률 계산
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

    const imgUrl = contextPath + '/resources/images/stock/' + stock.stockCode + '.png';

    return `
        <div class="stocklist-item" data-code="${stock.stockCode}">
            <div class="stocklist-favorite">
                <span class="stocklist-rank">${index + 1}</span>
                <button class="stocklist-favorite-btn active" 
                        data-code="${stock.stockCode}">♥</button>
            </div>
            <div class="stocklist-info">
                <div class="stocklist-logo">
                    <img src="${imgUrl}" alt="${stock.stockName}" 
                        onerror="this.src='${contextPath}/resources/images/antmillion-logo.png'">
                </div>
                <div class="main-signal-lamp" id="signal-${stock.stockCode}"></div>
                <span class="stocklist-name">${stock.stockName}</span>
            </div>
            <div class="stocklist-price">${currentPrice}</div>
            <div class="stocklist-change ${changeClass}">${changeText}</div>
            <div class="stocklist-sentiment">
                <div class="stocklist-sentiment-bar">
                    <div class="stocklist-sentiment-buy stocklist-sentiment-inactive" style="width: 50%;"></div>
                    <div class="stocklist-sentiment-sell stocklist-sentiment-inactive" style="width: 50%;"></div>
                </div>
                <div class="stocklist-sentiment-labels">
                    <span class="stocklist-sentiment-buy-label"></span>
                    <span class="stocklist-sentiment-sell-label"></span>
                </div>
            </div>
        </div>
    `;
}

// ========== 관심종목 페이지네이션 렌더링 ==========
function renderFavoritePagination() {
    // 기존 페이지네이션 제거
    const existingPagination = document.getElementById('pagination-container');
    if (existingPagination) {
        existingPagination.remove();
    }

    // 관심종목 탭이 아니거나 페이지가 1개 이하면 표시 안함
    if (currentTab !== 'favorite' || favoriteTotalPages <= 1) {
        return;
    }

    const stocklistCard = document.querySelector('.stocklist-card');
    const paginationContainer = document.createElement('div');
    paginationContainer.id = 'pagination-container';
    paginationContainer.className = 'pagination-container';

    let paginationHTML = '';

    // 이전 버튼
    if (favoritePage > 1) {
        paginationHTML += `<button class="pagination-btn favorite-page-btn" data-page="${favoritePage - 1}">이전</button>`;
    }

    // 페이지 번호
    for (let i = 1; i <= favoriteTotalPages; i++) {
        const activeClass = i === favoritePage ? 'active' : '';
        paginationHTML += `<button class="pagination-btn favorite-page-btn ${activeClass}" data-page="${i}">${i}</button>`;
    }

    // 다음 버튼
    if (favoritePage < favoriteTotalPages) {
        paginationHTML += `<button class="pagination-btn favorite-page-btn" data-page="${favoritePage + 1}">다음</button>`;
    }

    paginationContainer.innerHTML = paginationHTML;
    stocklistCard.appendChild(paginationContainer);

    // 관심종목 페이지네이션 버튼 이벤트 리스너
    document.querySelectorAll('.favorite-page-btn').forEach(btn => {
        btn.addEventListener('click', function() {
            favoritePage = parseInt(this.getAttribute('data-page'));
            renderStocks();
        });
    });
}

// ========== 종목 리스트 렌더링 (탭에 따라 분기) ==========
function renderStocks() {
    if (currentTab === 'all') {
        renderAllStocks();
    } else {
        renderFavoriteStocks();
    }
}

// ========== 페이지네이션 렌더링 ==========
function renderPagination() {
    // 기존 페이지네이션 제거
    const existingPagination = document.getElementById('pagination-container');
    if (existingPagination) {
        existingPagination.remove();
    }

    // 전체 종목 탭이 아니면 페이지네이션 표시 안함
    if (currentTab !== 'all') {
        return;
    }

    const stocklistCard = document.querySelector('.stocklist-card');
    const paginationContainer = document.createElement('div');
    paginationContainer.id = 'pagination-container';
    paginationContainer.className = 'pagination-container';

    let paginationHTML = '';

    // 이전 버튼
    if (currentPage > 1) {
        paginationHTML += `<button class="pagination-btn" data-page="${currentPage - 1}">이전</button>`;
    }

    // 페이지 번호
    for (let i = 1; i <= totalPages; i++) {
        const activeClass = i === currentPage ? 'active' : '';
        paginationHTML += `<button class="pagination-btn ${activeClass}" data-page="${i}">${i}</button>`;
    }

    // 다음 버튼
    if (currentPage < totalPages) {
        paginationHTML += `<button class="pagination-btn" data-page="${currentPage + 1}">다음</button>`;
    }

    paginationContainer.innerHTML = paginationHTML;
    stocklistCard.appendChild(paginationContainer);

    // 페이지네이션 버튼 이벤트 리스너
    document.querySelectorAll('.pagination-btn').forEach(btn => {
        btn.addEventListener('click', function() {
            currentPage = parseInt(this.getAttribute('data-page'));
            renderStocks();
        });
    });
}

// ========== 즐겨찾기 버튼 이벤트 리스너 등록 ==========
function attachFavoriteListeners() {
    const container = document.getElementById('stocklist-Container');
    if (!container) return;

    // 이미 한번 바인딩했으면 다시 안함
    if (container.dataset.favBound === '1') return;
    container.dataset.favBound = '1';

    container.addEventListener('click', function(e) {
        const btn = e.target.closest('.stocklist-favorite-btn');
        if (!btn) return;

        e.stopPropagation();
        e.preventDefault();

        // 비로그인 상태면 모달
        if (!ensureLoginOrModal()) return;

        const stockCode = btn.getAttribute('data-code');

        fetch(`${contextPath}/api/interest/toggle`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ stockCode })
        })
            .then(res => {
                // 서버에서 401/403 떨어져도 모달 띄우기(플래그가 잘못 잡혀도 안전)
                if (res.status === 401 || res.status === 403) {
                    showLoginRequiredModal();
                    return null;
                }
                const ct = res.headers.get('content-type') || '';
                if (!ct.includes('application/json')) {
                    // 로그인 페이지 HTML로 리다이렉트된 경우 등
                    showLoginRequiredModal();
                    return null;
                }
                return res.json();
            })
            .then(data => {
                if (!data) return;

                if (data.message === 'LOGIN_REQUIRED') {
                    showLoginRequiredModal();
                    return;
                }

                if (data.success) {
                    btn.classList.toggle('active');
                    btn.textContent = data.isInterest ? '♥' : '♡';

                    if (currentTab === 'favorite') {
                        renderStocks();
                    }
                }
            })
            .catch(err => console.error('관심종목 토글 실패:', err));
    }, true); // 캡처링으로 먼저 잡아내서 안전
}

// ========== 종목 아이템 클릭 이벤트 리스너 등록 ==========
function attachStockItemListeners() {
    document.querySelectorAll('.stocklist-item').forEach(item => {
        item.addEventListener('click', function(e) {
            // 즐겨찾기 버튼 클릭은 제외
            if (e.target.closest('.stocklist-favorite-btn')) {
                return;
            }

            const code = this.dataset.code;
            window.location.href = contextPath + '/stock/detail?code=' + code;
        });
    });
}

// ========== 탭 전환 이벤트 ==========
document.querySelectorAll('.stocklist-tab-btn').forEach((btn, index) => {
    btn.addEventListener('click', function() {

        // 두 번째 버튼(관심종목)은 로그인 필요
        if (index === 1 && !isLoggedIn()) {
            showLoginRequiredModal();
            return;
        }

        document.querySelectorAll('.stocklist-tab-btn').forEach(b => b.classList.remove('active'));
        this.classList.add('active');

        // 첫 번째 버튼: 전체종목, 두 번째 버튼: 관심종목
        currentTab = index === 0 ? 'all' : 'favorite';
        // 탭 변경 시 각각의 페이지 초기화
        if (currentTab === 'all') {
            currentPage = 1;
        } else {
            favoritePage = 1;
        }
        updateSortLabel();
        renderStocks();
    });
});

// ========== 정렬 라벨 업데이트 함수 ==========
function updateSortLabel() {
    const sortLabel = document.getElementById('stocklist-sort-label');

    if (currentTab === 'all') {
        // 전체 종목: 거래대금 순위 표시
        const now = new Date();
        const hours = String(now.getHours()).padStart(2, '0');
        const minutes = String(now.getMinutes()).padStart(2, '0');
        sortLabel.textContent = `거래대금 순위·오늘 ${hours}:${minutes} 기준`;
    } else {
        // 관심 종목: 등록일순 표시
        sortLabel.textContent = '등록일순';
    }
}

// ========== 페이지 떠날 때 전체 구독 해제 ==========
window.addEventListener('beforeunload', function(e) {
    unsubscribeAllStocks();

    if (stompClient !== null && stompConnected) {
        stompClient.disconnect(function() {
            console.log('STOMP 연결 종료');
        });
    }
});

window.addEventListener('pagehide', function(e) {
    unsubscribeAllStocks();
});

// ========== 초기화 ==========
document.addEventListener('DOMContentLoaded', function() {
    scheduleMarketClose();

    // STOMP 연결 후 렌더링
    connectStomp()
        .then(() => {
            console.log('STOMP 연결 완료 - 초기 렌더링 시작');
            // 초기 정렬 라벨 설정
            updateSortLabel();
            // 초기 렌더링
            renderStocks();
        })
        .catch(error => {
            console.error('STOMP 초기 연결 실패, 렌더링은 진행:', error);
            // STOMP 연결 실패해도 화면은 보여줌
            updateSortLabel();
            renderStocks();
        });

    // 10분마다 신호등 상태만 별도로 업데이트
    setInterval(function() {
        console.log('[Auto Update] 신호등 상태 갱신');
        updateAllTrafficSignals();
    }, 600000);
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
        const mockStocks = Array.from(document.querySelectorAll('.stocklist-item')).map(item => {
            const price = Number(item.querySelector('.stocklist-price').textContent.replace(/[^0-9]/g, ''));
            return {
                stockCode: item.getAttribute('data-code'),
                basePrice: Number.isSafeInteger(price) && price > 0 ? price : 10000
            };
        });
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

// ========== 거래 비율 초기 상태로 복원 ==========
function resetSentimentToDefault() {
    console.log('[stocklist.js] 모든 종목의 거래 비율을 초기 상태로 복원');

    const stockItems = document.querySelectorAll('.stocklist-item');

    stockItems.forEach(item => {
        const buyBar = item.querySelector('.stocklist-sentiment-buy');
        const sellBar = item.querySelector('.stocklist-sentiment-sell');
        const buyLabel = item.querySelector('.stocklist-sentiment-buy-label');
        const sellLabel = item.querySelector('.stocklist-sentiment-sell-label');

        if (buyBar && sellBar) {
            buyBar.classList.add('stocklist-sentiment-inactive');
            sellBar.classList.add('stocklist-sentiment-inactive');
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
        console.log(`[stocklist.js] 15:30까지 ${Math.floor(msUntil1530 / 1000 / 60)}분 남음`);
        setTimeout(() => {
            console.log('[stocklist.js] 15:30 정규장 마감 - 기본값으로 전환');
            resetSentimentToDefault();
        }, msUntil1530);
    } else {
        console.log('[stocklist.js] 오늘 15:30은 이미 지났습니다.');
    }

    // 20:00 예약
    const today2000 = new Date(now);
    today2000.setHours(20, 0, 0, 0);
    const msUntil2000 = today2000 - now;

    if (msUntil2000 > 0) {
        console.log(`[stocklist.js] 20:00까지 ${Math.floor(msUntil2000 / 1000 / 60)}분 남음`);
        setTimeout(() => {
            console.log('[stocklist.js] 20:00 시간외 마감 - 기본값으로 전환');
            resetSentimentToDefault();
        }, msUntil2000);
    } else {
        console.log('[stocklist.js] 오늘 20:00은 이미 지났습니다.');
    }
}

//모든 종목의 신호등 상태를 서버에서 가져와 업데이트하는 함수
async function updateAllTrafficSignals() {
    const stockItems = document.querySelectorAll('.stocklist-item');
    for (const item of stockItems) {
        const stockCode = item.getAttribute('data-code');
        if (!stockCode) continue;

        try {
            const res = await fetch(`${contextPath}/api/kis/foreigner-organization/${stockCode}`);
            const data = await res.json();
            const lamp = document.getElementById(`signal-${stockCode}`);
            if (lamp && data.signalColor) {
                lamp.classList.remove('GREEN', 'RED', 'YELLOW');
                lamp.classList.add(data.signalColor);
            }
        } catch (e) {
            console.error(stockCode + " 신호등 에러");
        }
        // 0.05초 대기 후 다음 종목 처리 (브라우저 부하 분산)
        await new Promise(resolve => setTimeout(resolve, 50));
    }
}
