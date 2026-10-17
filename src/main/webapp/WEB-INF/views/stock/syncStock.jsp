<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8"%>
<!DOCTYPE html>
<html lang="ko">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>종목 동기화</title>
    <%@ include file="/WEB-INF/views/common/common.jsp"%>
    <link rel="stylesheet" href="${cpath}/resources/css/stock/syncStock.css">
</head>
<body>
<div class="sync-container">
    <div class="sync-card">
        <div class="sync-header">
            <h1 class="sync-title">🔄 종목 동기화</h1>
            <p class="sync-description">
                한국투자증권에서 최신 상장 종목 정보를 다운로드하여<br>
                데이터베이스를 자동으로 업데이트합니다.
            </p>
        </div>

        <div class="sync-info-box">
            <div class="info-item">
                <span class="info-icon">📊</span>
                <div class="info-text">
                    <div class="info-label">대상 시장</div>
                    <div class="info-value">코스피 + 코스닥</div>
                </div>
            </div>
            <div class="info-item">
                <span class="info-icon">⚡</span>
                <div class="info-text">
                    <div class="info-label">동기화 방식</div>
                    <div class="info-value">신규·재등장 반영 + 누락 종목 비활성화</div>
                </div>
            </div>
            <div class="info-item">
                <span class="info-icon">🔒</span>
                <div class="info-text">
                    <div class="info-label">데이터 출처</div>
                    <div class="info-value">한국투자증권 공식</div>
                </div>
            </div>
        </div>

        <button class="sync-button" id="syncButton">
            <span class="button-text">종목 동기화</span>
            <span class="button-loader" style="display: none;">
                    <span class="spinner"></span>
                    동기화 중...
                </span>
        </button>

        <div class="sync-result" id="syncResult" style="display: none;">
            <div class="result-header">
                <span class="result-icon" id="resultIcon">✅</span>
                <h3 class="result-title" id="resultTitle">동기화 완료</h3>
            </div>
            <div class="result-content" id="resultContent">
                <!-- 결과 내용이 여기에 표시됩니다 -->
            </div>
        </div>

        <div class="sync-warning">
            <span class="warning-icon">⚠️</span>
            <div class="warning-text">
                동기화 작업은 수 초에서 수십 초가 소요될 수 있습니다.<br>
                작업이 완료될 때까지 페이지를 닫지 마세요.
            </div>
        </div>
    </div>
</div>
<script> const contextPath = '${cpath}'; </script>
<script src="${cpath}/resources/js/stock/syncStock.js"></script>
</body>
</html>
