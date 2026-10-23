package com.antmillion.kiwoom.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/** 필요한 필드만 읽으며 원격 메시지·연속 조회 키·toString을 노출하지 않습니다. */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class KiwoomPeriodChartResponse {
    @JsonProperty("stk_cd")
    private String stockCode;
    @JsonProperty("return_code")
    private Integer returnCode;
    @JsonProperty("stk_dt_pole_chart_qry")
    private List<Price> daily;
    @JsonProperty("stk_stk_pole_chart_qry")
    private List<Price> weekly;
    @JsonProperty("stk_mth_pole_chart_qry")
    private List<Price> monthly;
    @JsonProperty("stk_yr_pole_chart_qry")
    private List<Price> yearly;

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Price {
        @JsonProperty("dt")
        private String date;
        @JsonProperty("cur_prc")
        private String closePrice;
        @JsonProperty("open_pric")
        private String openPrice;
        @JsonProperty("high_pric")
        private String highPrice;
        @JsonProperty("low_pric")
        private String lowPrice;
    }
}
