<%@ page language="java" contentType="text/html; charset=UTF-8"
         pageEncoding="UTF-8" %>
<!DOCTYPE html>
<html lang="ko">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>똑똑한개미 - 내 정보</title>
    <%@ include file="/WEB-INF/views/common/common.jsp" %>
    <link rel="stylesheet" href="${cpath}/resources/css/myinfo/myinfo.css">
    <link rel="stylesheet" href="${cpath}/resources/css/common/sidebar.css">
    <link rel="stylesheet" href="${cpath}/resources/css/common/header.css">
</head>
<body>
    <div class="myinfo-app-container">
        <%@ include file="../common/sidebar.jsp"%>
        <%@ include file="../common/header.jsp"%>
        <main class="myinfo-main-content">
            <!-- 내 정보 카드 -->
            <div class="myinfo-card">
                <div class="myinfo-header">
                    <h2 class="myinfo-title">내 정보</h2>
                    <div class="myinfo-date">${joinDate}</div>
                </div>
                <div class="myinfo-content">
                    <!-- 프로필 섹션 -->
                    <div class="myinfo-profile-section">
                        <div class="myinfo-ant-image">
                            <c:choose>
                                <c:when test="${not empty userRank and not empty userRank.rankImage}">
                                    <img src="${cpath}/${userRank.rankImage}" alt="개미 캐릭터" />
                                </c:when>
                                <c:otherwise>
                                    <img src="${cpath}/resources/images/profile/bronze-ant.png" alt="개미 캐릭터" />
                                </c:otherwise>
                            </c:choose>
                        </div>
                        <div class="myinfo-details">
                            <div class="myinfo-field">
                                <label class="myinfo-label">닉네임</label>
                                <div class="myinfo-value" id="myinfo-nickname">${nickname}</div>
                            </div>
                            <div class="myinfo-field">
                                <label class="myinfo-label">이메일</label>
                                <div class="myinfo-value" id="myinfo-email">${emailDisplay}</div>
                            </div>
                            <div class="myinfo-field">
                                <label class="myinfo-label">비밀번호</label>
                                <button class="myinfo-password-btn" id="changePasswordBtn">
                                    비밀번호 변경
                                </button>
                            </div>
                        </div>
                    </div>
                </div>
                <!-- 회원탈퇴 버튼 -->
                <div class="myinfo-footer">
                    <button class="myinfo-delete-btn" id="deleteAccountBtn">
                        회원탈퇴 하기
                    </button>
                </div>
            </div>
        </main>

        <!-- 비밀번호 변경 모달 -->
        <div class="myinfo-modal" id="passwordModal">
            <div class="myinfo-modal-content">
                <button class="myinfo-modal-close" onclick="closePasswordModal()">✕</button>
                <h2 class="myinfo-modal-title">비밀번호 변경</h2>

                <!-- 카카오 계정 안내 메시지(카카오 계정이면 폼 대신 이 문구를 보여줌) -->
                <p class="myinfo-modal-message" id="passwordModalMessage" style="display:none;"></p>

                <form class="myinfo-password-form" id="passwordForm">
                    <div class="myinfo-input-group">
                        <input type="password" class="myinfo-input" placeholder="기존 비밀번호 입력" id="currentPassword" required>
                    </div>
                    <div class="myinfo-input-group">
                        <input type="password" class="myinfo-input" placeholder="새 비밀번호 입력" id="newPassword" required>
                    </div>
                    <div class="myinfo-input-group">
                        <input type="password" class="myinfo-input" placeholder="새 비밀번호 확인" id="confirmPassword" required>
                    </div>
                    <button type="submit" class="myinfo-submit-btn">비밀번호 변경하기</button>
                </form>
            </div>
        </div>

        <!-- 회원탈퇴 모달 -->
        <div class="myinfo-modal" id="deleteModal">
            <div class="myinfo-modal-content">
                <button class="myinfo-modal-close" onclick="closeDeleteModal()">✕</button>
                <h2 class="myinfo-modal-title">회원탈퇴</h2>
                <p class="myinfo-modal-message">
                    회원탈퇴 시 계정은 비활성화되며 복구할 수 없습니다. 단, 서비스 이용 기록은 일정 기간 보관될 수 있습니다.
                </p>

                <form id="deleteForm">
                    <!-- LOCAL 계정일 때만 비밀번호 입력(카카오 계정이면 JS에서 숨김) -->
                    <div class="myinfo-input-group" id="deletePasswordGroup">
                        <input type="password" class="myinfo-input" placeholder="비밀번호를 입력해 주세요" id="deletePassword">
                    </div>

                    <div class="myinfo-modal-buttons">
                        <button type="button" class="myinfo-cancel-btn" onclick="closeDeleteModal()">취소</button>
                        <button type="submit" class="myinfo-confirm-btn">회원탈퇴</button>
                    </div>
                </form>
            </div>
        </div>

        <script>
            const cpath = "${pageContext.request.contextPath}";
            const isKakao = ${isKakao};
        </script>
        <script src="${cpath}/resources/js/myinfo/myinfo.js"></script>
    </div>
</body>
</html>
