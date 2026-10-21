package com.antmillion.mypage.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.antmillion.marketdata.provider.CurrentPriceProvider;
import org.springframework.stereotype.Service;

import com.antmillion.mypage.dto.AccountInfoDTO;
import com.antmillion.mypage.dto.ExecutedOrdersDTO;
import com.antmillion.mypage.dto.RealizedProfitDTO;
import com.antmillion.mypage.dto.StockHoldingsDTO;
import com.antmillion.mypage.mapper.MyPageMapper;

import lombok.RequiredArgsConstructor;
@RequiredArgsConstructor
@Service
public class MyPageServiceImpl implements MyPageService {

    private final MyPageMapper myPageMapper;
    private final CurrentPriceProvider currentPriceProvider;
    
    //1. 주식잔고
    @Override
    public List<StockHoldingsDTO> getStockHoldings(Long accountId) {
        // 1. DB에서 DTO 리스트로 가져오기
        List<StockHoldingsDTO> holdings = myPageMapper.selectStockHoldings(accountId);
        
        // 2. 각 DTO별로 API 호출 및 계산 데이터 세팅
        for (StockHoldingsDTO stock : holdings) {
            if (!stock.isActive()) {
                continue;
            }
            Integer quote = currentPriceProvider.getCurrentPrice(stock.getCode());
            if (quote == null || quote <= 0) {
                continue;
            }
            long currentPrice = quote;
            long totalValue = currentPrice * stock.getShares();
            long profit = totalValue - stock.getBuyPrice();
            
            double profitRate = 0.0;
            if (stock.getBuyPrice() > 0) {
                profitRate = Math.round(((double) profit / stock.getBuyPrice() * 100) * 100) / 100.0;
            }

            // DTO에 값 주입
            stock.setCurrentPrice(currentPrice);
            stock.setTotalValue(totalValue);
            stock.setProfit(profit);
            stock.setProfitRate(profitRate);
        }
        
        // 3. 종목명(name) 기준 ㄱㄴㄷ(오름차순) 정렬
        holdings.sort((a, b) -> a.getName().compareTo(b.getName()));
        
        // 평가금액 기준 내림차순 정렬
        //holdings.sort((a, b) -> Long.compare(b.getTotalValue(), a.getTotalValue()));
        
        return holdings;
    }
    
    //2. 실현손익
    @Override
    public List<RealizedProfitDTO> getRealizedProfit(Long accountId, String startDate, String endDate) {
        Map<String, Object> params = new HashMap<>();
        params.put("accountId", accountId);
        params.put("startDate", startDate);
        params.put("endDate", endDate);
        
        return myPageMapper.selectRealizedProfit(params);
    }
    
    //3. 체결내역
    @Override
    public List<ExecutedOrdersDTO> getExecutedOrders(Long accountId, String startDate, String endDate) {
        return myPageMapper.selectExecutedOrders(accountId, startDate, endDate);
    }
    
    
    
    //4. 계좌정보
    @Override
    public AccountInfoDTO getAccountInfo(Long accountId) {
        return myPageMapper.selectAccountInfo(accountId);
    }
}
