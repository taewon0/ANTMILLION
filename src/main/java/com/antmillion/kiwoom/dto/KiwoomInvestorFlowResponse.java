package com.antmillion.kiwoom.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/** 필요한 시각·수량만 읽으며 원격 메시지와 toString을 노출하지 않습니다. */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class KiwoomInvestorFlowResponse {
    @JsonProperty("return_code")
    @JsonAlias("returnCode")
    private Integer returnCode;
    @JsonProperty("opmr_invsr_trde_chart")
    private List<Flow> flows;

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Flow {
        @JsonProperty("tm")
        private String time;
        @JsonProperty("frgnr_invsr")
        private String foreignNetQuantity;
        @JsonProperty("orgn")
        private String organizationNetQuantity;
    }
}
