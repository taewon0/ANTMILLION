package com.antmillion.marketdata.dto;

import lombok.Value;

/** 국내 종목의 외국인·기관 순매수 가집계입니다. 주 단위 원본 문자열과 누락 값을 보존합니다. */
@Value
public class InvestorFlow {
    String foreignNetQuantity;
    String organizationNetQuantity;
    String combinedNetQuantity;
}
