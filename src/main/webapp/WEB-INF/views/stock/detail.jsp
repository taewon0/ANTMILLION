<%@ page language="java" contentType="text/html; charset=UTF-8"
    pageEncoding="UTF-8"%>
<%@ taglib prefix="spring" uri="http://www.springframework.org/tags" %>
<!DOCTYPE html>
<html lang="ko">
<head>
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>종목 상세 페이지</title>
<%@ include file="/WEB-INF/views/common/common.jsp"%>
<link rel="stylesheet" href="${cpath}/resources/css/stock/detail.css">
<link rel="stylesheet" href="${cpath}/resources/css/common/sidebar.css">
<link rel="stylesheet" href="${cpath}/resources/css/common/header.css">
<link rel="stylesheet" href="${cpath}/resources/css/stock/community.css">
</head>
<body class="detail-body">
    <div class="detail-container">
        <%@ include file="../common/sidebar.jsp"%>
        <main class="detail-main-content">
            <%@ include file="../common/header.jsp"%>
            <div class="detail-stock-container">
                <div class="detail-left-panel">
                    <section class="detail-stock-header">
                        <img id="detail-stock-image" src="" alt="">
                        <div class="detail-text-col">
                            <div>
                                <h2 class="detail-stock-name">                              
                                    ${stock.stockName} <span class="stock-code">${stock.stockCode}</span>
                                                       <span class="main-signal-lamp" id="signal-${stock.stockCode}"></span>
                                </h2>
                            </div>
                            <div class="detail-current-price">
                                <h3 id="detail-current-price-h3"></h3>
                            </div>
                        </div>
                        <button class="detail-favorite-btn" data-code="${stock.stockCode}">
                            ♡</button>
                    </section>

                    <section class="detail-chart-area">
                        <!-- 차트 기간 선택 버튼 추가 -->
                        <div class="detail-chart-period-buttons">
                            <button class="detail-period-btn detail-period-active" data-period="minute">분</button>
                            <button class="detail-period-btn" data-period="D">일</button>
                            <button class="detail-period-btn" data-period="W">주</button>
                            <button class="detail-period-btn" data-period="M">월</button>
                            <button class="detail-period-btn" data-period="Y">년</button>
                        </div>
                        <div id="detail-stockChart" style="width: 100%; height: 280px;"></div>
                    </section>
                    <section class="detail-bottom-split">
                        <section class="detail-hoga-box">
                            <div class="detail-hoga-placeholder">
                                <span></span>
                            </div>
                        </section>
                        <section class="detail-community-box">
                            <div class="detail-strong">
                                <h3>커뮤니티</h3>
                            </div>

                            <!-- 커뮤니티 내용 include -->
                            <%@ include file="/WEB-INF/views/stock/community.jsp"%>

                        </section>
                    </section>
                </div>
                <aside class="detail-right-panel">
                    <div class="detail-order-top">
                        <h3>주문하기</h3>
                    </div>
                    <div class="detail-tab-group">
                        <button class="detail-tab-active" data-type="buy">매수</button>
                        <button class="detail-tab" data-type="sell">매도</button>
                        <button class="detail-tab" data-type="pending">대기</button>
                    </div>
                    <div class="detail-input-card">
                        <div class="detail-row">
                            <span id="tab-limit" class="detail-price-type detail-active-type"
                                style="cursor: pointer;">지정가</span><span
                                style="color: var(- -text-tertiary); margin: 0 2px;">|</span><span
                                id="tab-market" class="detail-price-type"
                                style="cursor: pointer; color: #6B7280;">시장가</span>
                        </div>
                        <div class="detail-row">
                            <span>가격</span>
                            <div class="detail-price-input-wrapper">
                                <input type="text" class="detail-price-input"
                                    id="order-price-input" value="" placeholder="가격 입력" /> <span
                                    class="detail-price-unit">원</span>
                            </div>
                        </div>
                        <div class="detail-row">
                            <span>수량</span>
                            <div class="detail-quantity-wrapper">
                                <button class="detail-qty-btn" id="qty-minus">-</button>
                                <input type="number" class="detail-quantity-input"
                                    id="order-quantity-input" value="1" min="1" />
                                <button class="detail-qty-btn" id="qty-plus">+</button>
                                <span class="detail-quantity-unit">주</span>
                            </div>
                        </div>
                        <div class="detail-quantity-preset">
                            <button class="detail-preset-btn" data-percent="10">10%</button>
                            <button class="detail-preset-btn" data-percent="25">25%</button>
                            <button class="detail-preset-btn" data-percent="50">50%</button>
                            <button class="detail-preset-btn" data-percent="100">최대</button>
                        </div>
                    </div>

                    <div class="detail-pending-card" id="pending-list-area"
                        style="display: none;">
                        <div class="detail-pending-header"></div>
                        <div class="detail-pending-body">
                            <table class="detail-pending-table">
                                <thead>
                                    <tr>
                                        <th>주문시간</th>
                                        <th>종목/구분</th>
                                        <th>단가/수량</th>
                                        <th>미체결</th>
                                        <th>관리</th>
                                    </tr>
                                </thead>
                                <tbody id="pending-tbody">
                                    <tr class="p-empty-row">
                                        <td colspan="5" class="p-empty-msg">
                                            <p>미체결 내역이 없습니다.</p>
                                        </td>
                                    </tr>
                                </tbody>
                            </table>
                        </div>
                    </div>

    <!-- 주문 확인 팝업 -->
    <div class="detail-order-modal" id="order-modal">
        <div class="detail-modal-content">
            <div class="detail-modal-header">
                <h3 class="detail-modal-title" id="modal-title">매수 주문 확인</h3>
                <button class="detail-modal-close" id="modal-close">×</button>
            </div>
            <div class="detail-modal-body">
                <div class="detail-modal-info-row">
                    <span class="detail-modal-label">종목명</span> <span
                        class="detail-modal-value" id="modal-stock-name">삼성전자</span>
                </div>
                <div class="detail-modal-info-row">
                    <span class="detail-modal-label">주문유형</span> <span
                        class="detail-modal-value" id="modal-order-type">지정가</span>
                </div>
                <div class="detail-modal-info-row" id="modal-price-row">
                    <span class="detail-modal-label">주문가격</span> <span
                        class="detail-modal-value" id="modal-price">129,300원</span>
                </div>
                <div class="detail-modal-info-row">
                    <span class="detail-modal-label">주문수량</span> <span
                        class="detail-modal-value" id="modal-quantity">10주</span>
                </div>
                <div class="detail-modal-total">
                    <span class="detail-modal-total-label">총 주문금액</span> <span
                        class="detail-modal-total-value buy" id="modal-total">1,293,000원</span>
                </div>
            </div>
            <div class="detail-modal-actions">
                <button class="detail-modal-btn detail-modal-btn-cancel"
                    id="modal-cancel">취소</button>
                <button class="detail-modal-btn detail-modal-btn-confirm"
                    id="modal-confirm">매수</button>
            </div>
        </div>
    </div>

    <div class="detail-order-result">
        <div class="detail-row">
            <span id="available-label">구매 가능 금액</span> <span
                class="detail-big-price">0원</span>
        </div>
        <div class="detail-total-row">
            <span>주문금액</span> <span class="detail-total-money" id="total-money">0원</span>
        </div>
    </div>
    <div class="detail-sentiment-section">
        <div class="detail-sentiment-text">
            <span></span>
        </div>
        <div class="detail-percent-labels">
            <div style="width: 50%;" id="buy-percent"></div>
            <div style="width: 50%;" id="sell-percent"></div>
        </div>
        <div>
            <div class="detail-progress-bar">
                <div class="detail-fill-buy detail-sentiment-inactive"
                    style="width: 50%;" id="buy-bar"></div>
                <div class="detail-fill-sell detail-sentiment-inactive"
                    style="width: 50%;" id="sell-bar"></div>
            </div>
        </div>
    </div>
    <button class="detail-btn-submit" id="submit-btn">매수</button>
    </aside>
    </div>
    </main>
    </div>

    <!-- 주문 확인 팝업 -->
    <div class="detail-order-modal" id="order-modal">
        <div class="detail-modal-content">
            <div class="detail-modal-header">
                <h3 class="detail-modal-title" id="modal-title">매수 주문 확인</h3>
                <button class="detail-modal-close" id="modal-close">×</button>
            </div>
            <div class="detail-modal-body">
                <div class="detail-modal-info-row">
                    <span class="detail-modal-label">종목명</span> <span
                        class="detail-modal-value" id="modal-stock-name">삼성전자</span>
                </div>
                <div class="detail-modal-info-row">
                    <span class="detail-modal-label">주문유형</span> <span
                        class="detail-modal-value" id="modal-order-type">지정가</span>
                </div>
                <div class="detail-modal-info-row" id="modal-price-row">
                    <span class="detail-modal-label">주문가격</span> <span
                        class="detail-modal-value" id="modal-price">129,300원</span>
                </div>
                <div class="detail-modal-info-row">
                    <span class="detail-modal-label">주문수량</span> <span
                        class="detail-modal-value" id="modal-quantity">10주</span>
                </div>
                <div class="detail-modal-total">
                    <span class="detail-modal-total-label">총 주문금액</span> <span
                        class="detail-modal-total-value buy" id="modal-total">1,293,000원</span>
                </div>
            </div>
            <div class="detail-modal-actions">
                <button class="detail-modal-btn detail-modal-btn-cancel"
                    id="modal-cancel">취소</button>
                <button class="detail-modal-btn detail-modal-btn-confirm"
                    id="modal-confirm">매수</button>
            </div>
        </div>
    </div>


    <!-- SockJS 라이브러리 -->
    <script
        src="https://cdnjs.cloudflare.com/ajax/libs/sockjs-client/1.5.1/sockjs.min.js"></script>

    <!-- STOMP 라이브러리 -->
    <script
        src="https://cdnjs.cloudflare.com/ajax/libs/stomp.js/2.3.3/stomp.min.js"></script>
    <!-- Adding the standalone version of Lightweight charts -->
    <script
        src="https://unpkg.com/lightweight-charts/dist/lightweight-charts.standalone.production.js"></script>
    <spring:eval expression="@environment.acceptsProfiles('dev-mock')" var="kisMockEnabled" />
    <script>window.kisMockEnabled = ${kisMockEnabled};</script>
    <script src="${cpath}/resources/js/stock/detail.js"></script>
</body>
</html>
