<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ taglib prefix="sec" uri="http://www.springframework.org/security/tags" %>
<%@ taglib prefix="spring" uri="http://www.springframework.org/tags" %>
<!DOCTYPE html>
<html lang="ko">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>똑똑한개미 - 종목</title>
    
    <%@ include file="/WEB-INF/views/common/common.jsp" %>
    <link rel="stylesheet" href="${cpath}/resources/css/common/sidebar.css">
    <link rel="stylesheet" href="${cpath}/resources/css/stocklist/stocklist.css">
    <link rel="stylesheet" href="${cpath}/resources/css/common/header.css">
</head>
<body>
<div class="stocklist-app-container">
    <%@ include file="../common/sidebar.jsp" %>
    <%@ include file="../common/header.jsp" %>

    <main class="stocklist-main-content">
        <div class="stocklist-card">
            <div class="stocklist-tabs">
                <button class="stocklist-tab-btn active">전체종목</button>
                <button class="stocklist-tab-btn">관심종목</button>
            </div>
            <div class="stocklist-header">
                <div class="stocklist-time" id="stocklist-sort-label">거래대금 순위·오늘 <%= new java.text.SimpleDateFormat("HH:mm").format(new java.util.Date()) %> 기준</div>
                <div class="stocklist-view-options">
                </div>
            </div>
            <div class="stocklist-table-header">
                <div class="stocklist-header-cell"></div>
                <div class="stocklist-header-cell">종목명
                    <div class="question-wrap">
				        <span class="question-icon">?</span>
				        <div class="tooltip-box">
				            <p class="tooltip-title">신호등은 외국인/기관의 매수세를 나타내요</p>
				            <ul class="tooltip-list">
				                <li><span class="dot green"></span> 외국인/기관 동시 매수세</li>
				                <li><span class="dot yellow"></span> 외국인/기관 한곳만 매수세</li>
				                <li><span class="dot red"></span> 외국인/기관 동시 매도세</li>
				            </ul>
				        </div>
				    </div>
                </div>
                <div class="stocklist-header-cell">현재가</div>
                <div class="stocklist-header-cell">등락률</div>
                <div class="stocklist-header-cell">거래 비율
                    <div class="question-wrap">
                        <span class="question-icon">?</span>
                        <div class="tooltip-box">
                            <p class="tooltip-title">투자자들의 매수·매도 체결비중을 나타내요</p>
                            <ul class="tooltip-list">
                                <li>투자자들의 심리와 수급추세를 알 수 있어요.</li>
                                <li><span style="color: red;">⚠️군중심리를 주의하세요.</span></li>
                            </ul>
                        </div>
                    </div>
                </div>
            </div>

            <!-- 종목 리스트 -->
            <div class="stocklist-list" id="stocklist-Container">
            </div>
        </div>
    </main>
</div>
<!-- SockJS 라이브러리 -->
<script src="https://cdnjs.cloudflare.com/ajax/libs/sockjs-client/1.5.1/sockjs.min.js"></script>

<!-- STOMP 라이브러리 -->
<script src="https://cdnjs.cloudflare.com/ajax/libs/stomp.js/2.3.3/stomp.min.js"></script>

<script>
    // contextPath 변수 설정 (stocklist.js에서 사용)
    var contextPath = '${cpath}';
    window.__isAuthenticated = false;
</script>
<sec:authorize access="!isAnonymous()">
  <script>
    window.__isAuthenticated = true;
  </script>
</sec:authorize>
<spring:eval expression="@environment.acceptsProfiles('dev-mock')" var="kisMockEnabled" />
<script>window.kisMockEnabled = ${kisMockEnabled};</script>
<script src="${cpath}/resources/js/stocklist/stocklist.js"></script>
</body>
</html>
