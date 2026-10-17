// contextPath는 common.jsp에서 전역으로 설정되어 있음
console.log('종목 동기화 페이지 로드');

const syncButton = document.getElementById('syncButton');
const syncResult = document.getElementById('syncResult');
const resultIcon = document.getElementById('resultIcon');
const resultTitle = document.getElementById('resultTitle');
const resultContent = document.getElementById('resultContent');

// 동기화 버튼 클릭 이벤트
syncButton.addEventListener('click', async function() {
    console.log('동기화 버튼 클릭');

    // 버튼 비활성화 및 로딩 상태
    syncButton.disabled = true;
    syncButton.classList.add('loading');

    // 기존 결과 숨기기
    syncResult.style.display = 'none';

    try {
        console.log('동기화 요청 전송 중...');

        const response = await fetch(contextPath + '/stock/sync-execute', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            }
        });

        const data = await response.json();
        console.log('동기화 응답:', data);

        if (data.success) {
            // 성공 처리
            showSuccessResult(data);
        } else {
            // 실패 처리
            showErrorResult(data);
        }

    } catch (error) {
        console.error('동기화 오류:', error);
        showErrorResult({
            message: '서버와의 통신 중 오류가 발생했습니다.'
        });
    } finally {
        // 버튼 활성화
        syncButton.disabled = false;
        syncButton.classList.remove('loading');
    }
});

// 성공 결과 표시
function showSuccessResult(data) {
    syncResult.className = 'sync-result success';
    resultIcon.textContent = '✅';
    resultTitle.textContent = '동기화 완료';

    resultContent.innerHTML = `
        <div class="result-row">
            <span class="result-label">📥 다운로드한 종목 수</span>
            <span class="result-value highlight">${data.totalDownloaded.toLocaleString()}개</span>
        </div>
        <div class="result-row">
            <span class="result-label">🔢 동기화 전 종목 수</span>
            <span class="result-value">${data.beforeCount.toLocaleString()}개</span>
        </div>
        <div class="result-row">
            <span class="result-label">✨ 새로 추가된 종목</span>
            <span class="result-value success">+${data.insertedCount.toLocaleString()}개</span>
        </div>
        <div class="result-row">
            <span class="result-label">비활성화된 종목</span>
            <span class="result-value error">${data.deactivatedCount.toLocaleString()}개</span>
        </div>
        <div class="result-row">
            <span class="result-label">📊 동기화 후 종목 수</span>
            <span class="result-value highlight">${data.afterCount.toLocaleString()}개</span>
        </div>
    `;

    syncResult.style.display = 'block';

    // 부드러운 스크롤
    syncResult.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
}

// 실패 결과 표시
function showErrorResult(data) {
    syncResult.className = 'sync-result error';
    resultIcon.textContent = '❌';
    resultTitle.textContent = '동기화 실패';

    resultContent.innerHTML = `
        <div class="result-message">
            ${data.message || '알 수 없는 오류가 발생했습니다.'}
        </div>
    `;

    syncResult.style.display = 'block';

    // 부드러운 스크롤
    syncResult.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
}

console.log('종목 동기화 스크립트 초기화 완료');
