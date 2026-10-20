package com.antmillion.kis.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter @NoArgsConstructor @AllArgsConstructor @Builder
public class KisWebSocketTransactionPriceRequest {
    private Header header;
    private Body body;

    @Getter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class Header {
        @JsonProperty("approval_key")
        private String approvalKey;
        private String custtype;       // P: 개인, B: 법인
        @JsonProperty("tr_type")
        private String trType;         // 1: 구독, 2: 해제
        @JsonProperty("content_type")
        private String contentType;    // 문자 인코딩: utf-8
    }

    @Getter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class Body {
        private Input input;

        @Getter @NoArgsConstructor @AllArgsConstructor @Builder
        public static class Input {
            @JsonProperty("tr_id")
            private String trId;
            @JsonProperty("tr_key")
            private String trKey;      // 종목코드 (005930 등)
        }
    }
}
