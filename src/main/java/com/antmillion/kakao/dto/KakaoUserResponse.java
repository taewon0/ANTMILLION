package com.antmillion.kakao.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public class KakaoUserResponse {

    private Long id; // 카카오 사용자 식별자

    @JsonProperty("kakao_account")
    private KakaoAccount kakaoAccount;

    private Properties properties;

    public Long getId() { return id; }
    public KakaoAccount getKakaoAccount() { return kakaoAccount; }
    public Properties getProperties() { return properties; }

    public static class KakaoAccount {
        private String email;

        @JsonProperty("has_email")
        private Boolean hasEmail;

        @JsonProperty("email_needs_agreement")
        private Boolean emailNeedsAgreement;

        public String getEmail() { return email; }
        public Boolean getHasEmail() { return hasEmail; }
        public Boolean getEmailNeedsAgreement() { return emailNeedsAgreement; }
    }

    public static class Properties {
        private String nickname;

        @JsonProperty("profile_image")
        private String profileImage;

        public String getNickname() { return nickname; }
        public String getProfileImage() { return profileImage; }
    }
}
