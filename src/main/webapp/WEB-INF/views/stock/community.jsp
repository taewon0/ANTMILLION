<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8"%>
<!-- 커뮤니티 내용 -->
<div class="community-content">
    <!-- 게시글 목록 -->
    <div class="community-post-list" id="communityListContainer">
        <!-- JavaScript로 동적 생성됨 -->
    </div>
    
    <!-- 글쓰기 영역 -->
    <div class="community-write-section">
        <input type="text" class="community-write-input" id="communityInput" placeholder="게시글을 작성해보세요" maxlength="200">
        <button class="community-send-btn" id="btnSubmitCommunity">전송</button>
    </div>
</div>

<!-- 제이쿼리 라이브러리 -->
<script src="https://code.jquery.com/jquery-3.6.0.min.js"></script>
<!-- 커뮤니티 JS -->
<script src="${cpath}/resources/js/stock/community.js"></script>