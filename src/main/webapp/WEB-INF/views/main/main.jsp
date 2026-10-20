<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ taglib prefix="sec" uri="http://www.springframework.org/security/tags" %>
<!DOCTYPE html>
<html lang="ko">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>똑똑한개미 - 메인</title>
    <%@ include file="/WEB-INF/views/common/common.jsp" %>
    <link rel="stylesheet" href="${cpath}/resources/css/main/main.css">
    <link rel="stylesheet" href="${cpath}/resources/css/common/sidebar.css">
    <link rel="stylesheet" href="${cpath}/resources/css/common/header.css">
</head>
<body>
<div class="main-app-container">
    <!-- 사이드바 include -->
    <%@ include file="../common/sidebar.jsp" %>

    <!-- 메인 콘텐츠 영역 -->
    <main class="main-main-content">
        <!-- 헤더 include -->
        <%@ include file="../common/header.jsp" %>

        <!-- 콘텐츠 그리드 -->
        <div class="main-content-grid">
            <!-- 나의 랭크 카드 -->
            <div class="main-rank-card main-grid-rank">
            <c:choose>
                <c:when test="${not empty userRank}">
                    <div class="main-rank-header">
                        <h2 class="main-card-title">나의 개미</h2>
                        <div class="main-rank-image-container">
                            <img src="${cpath}/${userRank.rankImage}" alt="랭크 이미지" class="main-rank-image" id="main-rankImage">
                        </div>
                    </div>
                    <div class="main-exp-bar-container"
                         id="mainExpContainer"
                         data-current="${userRank.currentPoint}"
                         data-max="${userRank.nextRankPoint}"
                         data-start="${userRank.currentRankStartPoint}">

                        <div class="main-exp-bar" id="mainExpBar" style="width: 0%;"></div>

                        <div class="main-exp-text">
                            <span class="main-exp-current">${userRank.currentPoint}</span>
                            <span class="main-exp-divider">/</span>
                            <c:choose>
                                <c:when test="${userRank.nextRankPoint == 0 || userRank.nextRankPoint >= 10000}">
                                    <span class="main-exp-max">MAX</span>
                                </c:when>
                                <c:otherwise>
                                    <span class="main-exp-max">${userRank.nextRankPoint}</span>
                                </c:otherwise>
                            </c:choose>
                        </div>
                    </div>
                </c:when>
                <c:otherwise>
                    <div class="main-rank-header" style="flex-direction: column; text-align: center;">
                        <h2 class="main-card-title">로그인이 필요해요!</h2>
                        <div class="main-rank-image-container">
                            <img src="${cpath}/resources/images/profile/challenger-ant.png"
                                 alt="기본 이미지" class="main-rank-image" style="opacity: 0.5; filter: grayscale(100%);">
                        </div>
                        <p class="main-rank-text">지금 로그인하고<br>나의 투자 랭크를 확인해보세요.</p>
                    </div>
                </c:otherwise>
            </c:choose>
            </div>

            <!-- 코스피 카드 -->
            <div class="main-market-index-card main-grid-kospi">
                <div class="main-market-index-header">
                    <div class="main-market-index-info">
                        <h3 class="main-market-index-name">코스피</h3>
                        <p class="main-market-index-symbol">KOSPI</p>
                    </div>
                </div>
                <div class="main-market-index-price"></div>
                <div class="main-market-index-change">
                    <span class="main-change-label"></span>
                    <span class="main-change-value main-positive"></span>
                    <span class="main-change-percent main-positive"></span>
                </div>
                <div class="main-market-index-chart-container">
                    <div id="main-kospi-chart"></div>
                </div>
            </div>

            <!-- 미션 카드 -->
            <div class="main-mission-card main-grid-mission">
                <div class="main-mission-header">
                    <h3>오늘의 미션</h3>
                </div>
                <p class="main-mission-description">퀴즈 풀고 뉴스 읽으면
                    <strong>하루 최대 700P</strong> 획득할 수 있어요!<br>지금 당장 나의 개미 랭크를 높여보세요!
                </p>
                <c:choose>
                    <c:when test="${missionCompleted}">
                        <span class="main-mission-completed-button">미션완료</span>
                    </c:when>
                    <c:otherwise>
                        <a href="${cpath}/mission" class="main-mission-button">도전하기</a>
                    </c:otherwise>
                </c:choose>
            </div>

            <!-- 코스닥 카드 -->
            <div class="main-market-index-card main-grid-kosdaq">
                <div class="main-market-index-header">
                    <div class="main-market-index-info">
                        <h3 class="main-market-index-name">코스닥</h3>
                        <p class="main-market-index-symbol">KOSDAQ</p>
                    </div>
                </div>
                <div class="main-market-index-price"></div>
                <div class="main-market-index-change">
                    <span class="main-change-label"></span>
                    <span class="main-change-value main-positive"></span>
                    <span class="main-change-percent main-positive"></span>
                </div>
                <div class="main-market-index-chart-container">
                    <div id="main-kosdaq-chart"></div>
                </div>
            </div>

            <!-- 주식 테이블과 차트 영역 -->
            <div class="main-stock-section main-grid-table">
                <!-- 주식 테이블 -->
                <div class="main-stock-table-card">
                    <div class="main-stocklist-header">
                        <div class="main-stocklist-time">거래대금 순위·오늘 <%= new java.text.SimpleDateFormat("HH:mm").format(new java.util.Date()) %> 기준</div>
                    </div>
                    <div class="main-stocklist-table-header">
                        <div class="main-stocklist-header-cell"></div>
                        <div class="main-stocklist-header-cell">종목명
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
                        <div class="main-stocklist-header-cell">현재가</div>
                        <div class="main-stocklist-header-cell">등락률</div>
                        <div class="main-stocklist-header-cell">거래 비율
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
                    <div class="main-stocklist-list" id="main-stocklist-Container">
                        <!-- JavaScript로 동적 생성 -->
                    </div>
                </div>
                
                <!-- 주식 차트 -->
				<div class="main-stock-chart-card">
				    <div class="main-chart-header">
				        <h3 class="main-chart-stock-name" id="displayStockName"></h3>
				        <p class="main-chart-stock-code" id="displayStockCode"></p>
				    </div>
				    <div class="main-stock-chart-container" style="width: 100%; height: 400px;">
				        <div id="main-stockChart" style="width: 100%; height: 100%;"></div>
				    </div>
				</div>
            </div>
        </div>
    </main>
</div>
<script>
    document.addEventListener('DOMContentLoaded', function() {
        // HTML 태그에서 데이터 가져오기
        const container = document.getElementById('mainExpContainer');
        const progressBar = document.getElementById('mainExpBar');

        if (container && progressBar) {
            // data- 속성은 문자열이므로 숫자로 변환
            const currentPoint = parseInt(container.getAttribute('data-current')) || 0;
            const nextRankPoint = parseInt(container.getAttribute('data-max')) || 0;
            const startPoint = parseInt(container.getAttribute('data-start')) || 0;

            // 미션 페이지와 동일한 계산 로직 적용
            let percent = 0;

            // 최고 레벨(nextRankPoint가 0이거나 10000 이상)인지 확인
            if (nextRankPoint > 0 && nextRankPoint < 10000) {
                const rangeTotal = nextRankPoint - startPoint;   // 이번 랭크의 전체 구간
                const rangeCurrent = currentPoint - startPoint;  // 내가 달성한 구간

                if (rangeTotal > 0) {
                    percent = (rangeCurrent / rangeTotal) * 100;
                }
            } else {
                percent = 100;
            }

            // 0 ~ 100% 사이로 제한
            percent = Math.max(0, Math.min(100, percent));

            setTimeout(() => {
                progressBar.style.width = percent + "%";
            }, 100);
        }
    });

    // 페이지 로드 시 스크롤을 맨 위로 이동
    window.onload = function() {
        window.scrollTo(0, 0);
    };

    // 페이지 진입 시 즉시 스크롤 초기화 (더 빠른 실행)
    if (history.scrollRestoration) {
        history.scrollRestoration = 'manual';
    }
</script>
<!-- Lightweight Charts 독립 실행 버전을 불러옵니다. -->
<script src="https://unpkg.com/lightweight-charts/dist/lightweight-charts.standalone.production.js"></script>
<script> const contextPath = '${cpath}'; </script>

<script>
  window.__isAuthenticated = false;
</script>
<sec:authorize access="!isAnonymous()">
  <script>
    window.__isAuthenticated = true;
  </script>
</sec:authorize>

<!-- SockJS 라이브러리 -->
<script src="https://cdnjs.cloudflare.com/ajax/libs/sockjs-client/1.5.1/sockjs.min.js"></script>

<!-- STOMP 라이브러리 -->
<script src="https://cdnjs.cloudflare.com/ajax/libs/stomp.js/2.3.3/stomp.min.js"></script>
<script src="${cpath}/resources/js/main/main.js"></script>
</body>
</html>
