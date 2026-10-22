package com.antmillion.marketdata.provider;

import com.antmillion.marketdata.dto.InvestorFlow;

/** 국내 종목의 외국인·기관 순매수 가집계 조회 계약입니다. 빈 값과 조회 예외를 보존합니다. */
public interface InvestorFlowProvider {
    InvestorFlow getInvestorFlow(String stockCode);
}
