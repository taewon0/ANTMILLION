package com.antmillion.kis.service;

import com.antmillion.kis.dto.ForeignerOrganization;
import com.antmillion.marketdata.dto.InvestorFlow;
import com.antmillion.marketdata.provider.InvestorFlowProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class KisInvestorFlowProvider implements InvestorFlowProvider {
    private final KisApiService kisApiService;

    @Override
    public InvestorFlow getInvestorFlow(String stockCode) {
        // 기존 토큰·종목별 캐시·공유 제한을 재사용하며 캐시 객체는 노출하지 않습니다.
        ForeignerOrganization data = kisApiService.getForeignerOrganizationData(stockCode);
        return new InvestorFlow(data.getForeignNetQty(), data.getOrganizationNetQty(), data.getSumFrgnOrgnNetQty());
    }
}
