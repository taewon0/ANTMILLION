package com.antmillion.kiwoom.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/** 원격 메시지·연속 조회 키와 toString을 노출하지 않습니다. */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class KiwoomMarketIndexChartResponse {
    @JsonProperty("inds_cd")
    private String indexCode;
    @JsonProperty("return_code")
    private Integer returnCode;
    @JsonProperty("inds_dt_pole_qry")
    private List<Price> prices;

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Price {
        @JsonProperty("dt")
        private String date;
        @JsonProperty("cur_prc")
        private String closeValue;
        @JsonProperty("open_pric")
        private String openValue;
        @JsonProperty("high_pric")
        private String highValue;
        @JsonProperty("low_pric")
        private String lowValue;
    }
}
