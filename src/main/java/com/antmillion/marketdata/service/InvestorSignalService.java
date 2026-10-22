package com.antmillion.marketdata.service;

import com.antmillion.marketdata.dto.InvestorFlow;
import com.antmillion.marketdata.provider.InvestorFlowProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class InvestorSignalService {
    private final InvestorFlowProvider investorFlowProvider;

    public String getSignalColor(String stockCode) {
        InvestorFlow data = investorFlowProvider.getInvestorFlow(stockCode);
        if (data.getForeignNetQuantity() == null || data.getOrganizationNetQuantity() == null) {
            return null;
        }
        long foreignQuantity = Long.parseLong(data.getForeignNetQuantity());
        long organizationQuantity = Long.parseLong(data.getOrganizationNetQuantity());
        if (foreignQuantity > 0 && organizationQuantity > 0) {
            return "GREEN";
        } else if (foreignQuantity < 0 && organizationQuantity < 0) {
            return "RED";
        } else {
            return "YELLOW";
        }
    }
}
